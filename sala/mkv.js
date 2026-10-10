/*
 * Estante — toca MKV em qualquer navegador com MSE (inclusive iPhone com iOS 17.1+).
 *
 * O MKV quase sempre traz vídeo H.264/H.265 e áudio AAC/AC3 — formatos que o Safari toca —
 * só que numa "caixa" que ele não abre. Este arquivo lê o MKV em pedaços (pedidos com Range,
 * direto do Drive), tira os quadros da caixa MKV e monta MP4 fragmentado na hora, que vai para
 * o player pelo MediaSource / ManagedMediaSource. Nada é convertido: a imagem e o som são os
 * mesmos bytes do arquivo original.
 *
 * Partes:
 *   MkvFile  — lê o MKV (EBML): faixas, índice (Cues), blocos de cada Cluster, lacing.
 *   Mp4Mux   — monta o cabeçalho (init) e os fragmentos (moof+mdat) do MP4.
 *   MkvPlayer— liga tudo ao <video>: buffer à frente, limpeza atrás, pulos, legendas embutidas.
 */
(function (root) {
  "use strict";

  // ------------------------------------------------------------------ EBML ---
  const ID = {
    EBML: 0x1a45dfa3, DocType: 0x4282, Segment: 0x18538067,
    SeekHead: 0x114d9b74, Seek: 0x4dbb, SeekID: 0x53ab, SeekPosition: 0x53ac,
    Info: 0x1549a966, TimecodeScale: 0x2ad7b1, Duration: 0x4489,
    Tracks: 0x1654ae6b, TrackEntry: 0xae, TrackNumber: 0xd7, TrackType: 0x83, FlagDefault: 0x88, FlagForced: 0x55aa,
    Language: 0x22b59c, LanguageIETF: 0x22b59d, Name: 0x536e, CodecID: 0x86, CodecPrivate: 0x63a2, DefaultDuration: 0x23e383,
    Video: 0xe0, PixelWidth: 0xb0, PixelHeight: 0xba, DisplayWidth: 0x54b0, DisplayHeight: 0x54ba,
    Audio: 0xe1, SamplingFrequency: 0xb5, OutputSamplingFrequency: 0x78b5, Channels: 0x9f,
    ContentEncodings: 0x6d80, ContentEncoding: 0x6240, ContentCompression: 0x5034, ContentCompAlgo: 0x4254,
    ContentCompSettings: 0x4255, ContentEncryption: 0x5035,
    Cues: 0x1c53bb6b, CuePoint: 0xbb, CueTime: 0xb3, CueTrackPositions: 0xb7, CueTrack: 0xf7, CueClusterPosition: 0xf1,
    Cluster: 0x1f43b675, Timecode: 0xe7, SimpleBlock: 0xa3, BlockGroup: 0xa0, Block: 0xa1, BlockDuration: 0x9b,
    ReferenceBlock: 0xfb, Void: 0xec, Tags: 0x1254c367, Chapters: 0x1043a770, Attachments: 0x1941a469,
  };
  const TOP_LEVEL = new Set([ID.SeekHead, ID.Info, ID.Tracks, ID.Cues, ID.Cluster, ID.Tags, ID.Chapters, ID.Attachments]);

  /** Lê um ID EBML (com o marcador de tamanho). */
  function readId(b, p) {
    const first = b[p];
    if (first === undefined) return null;
    let len = 1, mask = 0x80;
    while (len <= 4 && !(first & mask)) { len++; mask >>= 1; }
    if (len > 4 || p + len > b.length) return null;
    let v = 0;
    for (let i = 0; i < len; i++) v = v * 256 + b[p + i];
    return { value: v, length: len };
  }

  /** Lê um tamanho EBML (sem o marcador). unknown = "tamanho desconhecido" (todos os bits 1). */
  function readSize(b, p) {
    const first = b[p];
    if (first === undefined) return null;
    let len = 1, mask = 0x80;
    while (len <= 8 && !(first & mask)) { len++; mask >>= 1; }
    if (len > 8 || p + len > b.length) return null;
    let v = first & (mask - 1);
    let allOnes = v === mask - 1;
    for (let i = 1; i < len; i++) { v = v * 256 + b[p + i]; if (b[p + i] !== 0xff) allOnes = false; }
    return { value: v, length: len, unknown: allOnes };
  }

  /** Cabeçalho de elemento: id, tamanho do cabeçalho e tamanho dos dados. */
  function readHeader(b, p) {
    const id = readId(b, p);
    if (!id) return null;
    const size = readSize(b, p + id.length);
    if (!size) return null;
    return { id: id.value, hlen: id.length + size.length, size: size.value, unknown: size.unknown };
  }

  function uint(b, p, n) { let v = 0; for (let i = 0; i < n; i++) v = v * 256 + b[p + i]; return v; }
  function float(b, p, n) {
    const dv = new DataView(b.buffer, b.byteOffset + p, n);
    return n === 4 ? dv.getFloat32(0) : n === 8 ? dv.getFloat64(0) : 0;
  }
  function str(b, p, n) { let s = ""; for (let i = 0; i < n; i++) { if (!b[p + i]) break; s += String.fromCharCode(b[p + i]); } return s; }
  function utf8(b) { return new TextDecoder("utf-8").decode(b); }

  /** Percorre os filhos de um elemento mestre. */
  function children(b, start, end, cb) {
    let p = start;
    while (p < end) {
      const h = readHeader(b, p);
      if (!h) break;
      const dataStart = p + h.hlen;
      const dataEnd = h.unknown ? end : Math.min(end, dataStart + h.size);
      cb(h.id, dataStart, dataEnd, h);
      p = dataEnd;
    }
  }

  // ------------------------------------------------------------------ MKV ---
  /**
   * Arquivo MKV lido aos pedaços. fetchRange(a, b) devolve Uint8Array dos bytes [a, b] (inclusive).
   */
  class MkvFile {
    constructor(fetchRange, size) {
      this.fetchRange = fetchRange;
      this.size = size;
      this.cache = new Map(); // bloco de 64 KB -> bytes
      this.timecodeScale = 1e6;
      this.duration = 0;
      this.tracks = [];
      this.cues = [];
      this.firstCluster = -1;
      this.segStart = 0;
      this.segEnd = size;
    }

    async bytes(pos, len) {
      if (len <= 0) return new Uint8Array(0);
      const end = Math.min(this.size, pos + len) - 1;
      if (end < pos) return new Uint8Array(0);
      if (len > 256 * 1024) return this.fetchRange(pos, end);
      const B = 65536;
      const first = Math.floor(pos / B), last = Math.floor(end / B);
      const parts = [];
      for (let i = first; i <= last; i++) {
        let blk = this.cache.get(i);
        if (!blk) {
          blk = await this.fetchRange(i * B, Math.min(this.size, (i + 1) * B) - 1);
          this.cache.set(i, blk);
          if (this.cache.size > 48) this.cache.delete(this.cache.keys().next().value);
        }
        parts.push(blk);
      }
      const all = parts.length === 1 ? parts[0] : concat(parts);
      const off = pos - first * B;
      return all.subarray(off, off + (end - pos + 1));
    }

    /**
     * Leitura do filme em blocos grandes (4 MB): o mesmo pedido ao Drive serve vários Clusters.
     * Menos pedidos = menos chance de o Google achar que é abuso e bloquear o arquivo.
     */
    async bigBytes(pos, len) {
      if (this.rc) return this.rc.get(pos, len, true);
      const B = 4 * 1024 * 1024;
      if (len <= 0) return new Uint8Array(0);
      const end = Math.min(this.size, pos + len) - 1;
      const first = Math.floor(pos / B), last = Math.floor(end / B);
      if (!this.big) this.big = new Map();
      const parts = [];
      for (let i = first; i <= last; i++) {
        let blk = this.big.get(i);
        if (!blk) {
          blk = this.fetchRange(i * B, Math.min(this.size, (i + 1) * B) - 1);
          this.big.set(i, blk);
          blk.catch(() => this.big.delete(i));
          while (this.big.size > 6) this.big.delete(this.big.keys().next().value);
        }
        parts.push(await blk);
      }
      const all = parts.length === 1 ? parts[0] : concat(parts);
      const off = pos - first * B;
      return all.subarray(off, off + (end - pos + 1));
    }

    async header(pos) {
      const b = await this.bytes(pos, 16);
      const h = readHeader(b, 0);
      if (!h) throw new Error("MKV: cabeçalho inválido em " + pos);
      return h;
    }

    async open() {
      const head = await this.bytes(0, 64);
      const eb = readHeader(head, 0);
      if (!eb || eb.id !== ID.EBML) throw new Error("Não é um arquivo MKV");
      const segPos = eb.hlen + eb.size;
      const seg = await this.header(segPos);
      if (seg.id !== ID.Segment) throw new Error("MKV sem Segment");
      this.segStart = segPos + seg.hlen;
      this.segEnd = seg.unknown ? this.size : Math.min(this.size, this.segStart + seg.size);

      const seek = {};
      let p = this.segStart;
      let gotInfo = false, gotTracks = false;
      // Elementos do topo até o primeiro Cluster.
      for (let guard = 0; p < this.segEnd && guard < 64; guard++) {
        const h = await this.header(p);
        if (h.id === ID.Cluster) { this.firstCluster = p; break; }
        if (h.unknown) break;
        const ds = p + h.hlen;
        if (h.id === ID.SeekHead) this.parseSeekHead(await this.bytes(ds, h.size), seek);
        else if (h.id === ID.Info) { this.parseInfo(await this.bytes(ds, h.size)); gotInfo = true; }
        else if (h.id === ID.Tracks) { this.parseTracks(await this.bytes(ds, h.size)); gotTracks = true; }
        else if (h.id === ID.Cues) this.parseCues(await this.bytes(ds, h.size));
        p = ds + h.size;
      }
      const readAt = async (id, fn) => {
        if (seek[id] == null) return;
        const pos = this.segStart + seek[id];
        const h = await this.header(pos);
        if (h.id === id) fn(await this.bytes(pos + h.hlen, h.size));
      };
      if (!gotInfo) await readAt(ID.Info, (b) => this.parseInfo(b));
      if (!gotTracks) await readAt(ID.Tracks, (b) => this.parseTracks(b));
      if (!this.cues.length) await readAt(ID.Cues, (b) => this.parseCues(b));
      if (seek[ID.Cluster] != null && this.firstCluster < 0) this.firstCluster = this.segStart + seek[ID.Cluster];
      if (this.firstCluster < 0) throw new Error("MKV sem vídeo (nenhum Cluster)");
      if (!this.tracks.length) throw new Error("MKV sem faixas");
      return this;
    }

    parseSeekHead(b, out) {
      children(b, 0, b.length, (id, s, e) => {
        if (id !== ID.Seek) return;
        let sid = 0, spos = null;
        children(b, s, e, (cid, cs, ce) => {
          if (cid === ID.SeekID) sid = uint(b, cs, ce - cs);
          else if (cid === ID.SeekPosition) spos = uint(b, cs, ce - cs);
        });
        if (sid && spos != null && out[sid] == null) out[sid] = spos;
      });
    }

    parseInfo(b) {
      let dur = 0;
      children(b, 0, b.length, (id, s, e) => {
        if (id === ID.TimecodeScale) this.timecodeScale = uint(b, s, e - s);
        else if (id === ID.Duration) dur = float(b, s, e - s);
      });
      this.duration = (dur * this.timecodeScale) / 1e9;
    }

    parseTracks(b) {
      children(b, 0, b.length, (id, s, e) => {
        if (id !== ID.TrackEntry) return;
        const t = { number: 0, type: 0, codec: "", priv: null, defaultDuration: 0, lang: "und", name: "", isDefault: true,
          width: 0, height: 0, rate: 0, channels: 1, strip: null, encrypted: false };
        children(b, s, e, (cid, cs, ce) => {
          switch (cid) {
            case ID.TrackNumber: t.number = uint(b, cs, ce - cs); break;
            case ID.TrackType: t.type = uint(b, cs, ce - cs); break;
            case ID.FlagDefault: t.isDefault = uint(b, cs, ce - cs) === 1; break;
            case ID.Language: t.lang = str(b, cs, ce - cs) || "und"; break;
            case ID.LanguageIETF: t.ietf = str(b, cs, ce - cs); break;
            case ID.Name: t.name = utf8(b.subarray(cs, ce)); break;
            case ID.CodecID: t.codec = str(b, cs, ce - cs); break;
            case ID.CodecPrivate: t.priv = b.slice(cs, ce); break;
            case ID.DefaultDuration: t.defaultDuration = uint(b, cs, ce - cs) / 1e9; break;
            case ID.Video:
              children(b, cs, ce, (vid, vs, ve) => {
                if (vid === ID.PixelWidth) t.width = uint(b, vs, ve - vs);
                else if (vid === ID.PixelHeight) t.height = uint(b, vs, ve - vs);
              });
              break;
            case ID.Audio:
              children(b, cs, ce, (aid, as, ae) => {
                if (aid === ID.SamplingFrequency) t.rate = float(b, as, ae - as);
                else if (aid === ID.OutputSamplingFrequency) t.outRate = float(b, as, ae - as);
                else if (aid === ID.Channels) t.channels = uint(b, as, ae - as);
              });
              break;
            case ID.ContentEncodings:
              children(b, cs, ce, (eid, es, ee) => {
                if (eid !== ID.ContentEncoding) return;
                children(b, es, ee, (xid, xs, xe) => {
                  if (xid === ID.ContentEncryption) t.encrypted = true;
                  if (xid !== ID.ContentCompression) return;
                  let algo = 0, settings = null;
                  children(b, xs, xe, (yid, ys, ye) => {
                    if (yid === ID.ContentCompAlgo) algo = uint(b, ys, ye - ys);
                    else if (yid === ID.ContentCompSettings) settings = b.slice(ys, ye);
                  });
                  if (algo === 3 && settings) t.strip = settings; // "header stripping"
                  else t.unsupportedCompression = true;
                });
              });
              break;
          }
        });
        this.tracks.push(t);
      });
    }

    parseCues(b) {
      const cues = [];
      children(b, 0, b.length, (id, s, e) => {
        if (id !== ID.CuePoint) return;
        let time = 0, pos = null;
        children(b, s, e, (cid, cs, ce) => {
          if (cid === ID.CueTime) time = uint(b, cs, ce - cs);
          else if (cid === ID.CueTrackPositions && pos == null) {
            children(b, cs, ce, (pid, ps, pe) => { if (pid === ID.CueClusterPosition) pos = uint(b, ps, pe - ps); });
          }
        });
        if (pos != null) cues.push({ t: (time * this.timecodeScale) / 1e9, pos: this.segStart + pos });
      });
      cues.sort((a, b2) => a.t - b2.t);
      // Um ponto por Cluster.
      this.cues = cues.filter((c, i) => i === 0 || c.pos !== cues[i - 1].pos);
    }

    /** Posição do Cluster para começar a tocar em [t] segundos. */
    clusterFor(t) {
      if (t <= 0 || !this.cues.length) {
        if (t <= 0 || !this.duration) return { pos: this.firstCluster, exact: true };
        // Sem índice: estima pela proporção do arquivo e procura o próximo Cluster.
        const frac = Math.min(0.995, Math.max(0, t / this.duration));
        return { pos: Math.floor(this.firstCluster + (this.segEnd - this.firstCluster) * frac), exact: false };
      }
      let lo = 0, hi = this.cues.length - 1, best = 0;
      while (lo <= hi) { const m = (lo + hi) >> 1; if (this.cues[m].t <= t + 0.001) { best = m; lo = m + 1; } else hi = m - 1; }
      return { pos: this.cues[best].pos, exact: true };
    }

    /** Procura o próximo Cluster a partir de [pos] (quando não há índice). */
    async resync(pos) {
      const CHUNK = 256 * 1024;
      for (let p = pos; p < this.segEnd; p += CHUNK - 4) {
        const b = await this.bytes(p, CHUNK);
        for (let i = 0; i + 4 <= b.length; i++) {
          if (b[i] === 0x1f && b[i + 1] === 0x43 && b[i + 2] === 0xb6 && b[i + 3] === 0x75) {
            const h = readHeader(b, i);
            if (h && !h.unknown && h.size > 0 && h.size < 64 * 1024 * 1024) return p + i;
          }
        }
      }
      return -1;
    }

    /**
     * Lê o Cluster em [pos]. Devolve { next, frames: [{ track, pts, key, data, dur }] }.
     * Pula elementos que não são Cluster (Cues, Tags…) e devolve next sem quadros.
     */
    async cluster(pos) {
      if (pos >= this.segEnd) return { next: -1, frames: [] };
      const B = 4 * 1024 * 1024;
      const cached = this.rc ? this.rc.has(pos) && this.rc.has(pos + 15) : this.big && this.big.has(Math.floor(pos / B)) && (pos % B) < B - 16;
      const h = cached
        ? readHeader(await this.bigBytes(pos, 16), 0) || await this.header(pos)
        : await this.header(pos);
      const ds = pos + h.hlen;
      if (h.id !== ID.Cluster) {
        if (h.unknown || !TOP_LEVEL.has(h.id) && h.id !== ID.Void) {
          // Lixo ou posição estimada: procura o próximo Cluster.
          const n = await this.resync(pos + 1);
          return { next: n, frames: [] };
        }
        return { next: ds + h.size, frames: [] };
      }
      let body, end;
      if (h.unknown) {
        // Tamanho desconhecido: lê um pedaço e corta no próximo elemento de topo.
        body = await this.bytes(ds, Math.min(16 * 1024 * 1024, this.segEnd - ds));
        let p = 0;
        while (p < body.length) {
          const ch = readHeader(body, p);
          if (!ch || TOP_LEVEL.has(ch.id)) break;
          p += ch.hlen + ch.size;
        }
        body = body.subarray(0, Math.min(p, body.length));
        end = ds + body.length;
      } else {
        body = await this.bigBytes(ds, h.size);
        end = ds + h.size;
      }
      return { next: end, frames: this.parseCluster(body) };
    }

    parseCluster(b) {
      const frames = [];
      let tc = 0;
      const byNum = new Map(this.tracks.map((t) => [t.number, t]));
      const scale = this.timecodeScale / 1e9;
      children(b, 0, b.length, (id, s, e) => {
        if (id === ID.Timecode) tc = uint(b, s, e - s);
        else if (id === ID.SimpleBlock) this.parseBlock(b, s, e, true, tc, scale, byNum, frames, 0, false);
        else if (id === ID.BlockGroup) {
          let bs = -1, be = -1, dur = 0, hasRef = false;
          children(b, s, e, (cid, cs, ce) => {
            if (cid === ID.Block) { bs = cs; be = ce; }
            else if (cid === ID.BlockDuration) dur = uint(b, cs, ce - cs);
            else if (cid === ID.ReferenceBlock) hasRef = true;
          });
          if (bs >= 0) this.parseBlock(b, bs, be, false, tc, scale, byNum, frames, dur * scale, !hasRef);
        }
      });
      return frames;
    }

    parseBlock(b, s, e, simple, tc, scale, byNum, out, blockDur, groupKey) {
      const tn = readSize(b, s);
      if (!tn) return;
      const track = byNum.get(tn.value);
      if (!track) return;
      let p = s + tn.length;
      const rel = (b[p] << 8 | b[p + 1]) << 16 >> 16; // int16 com sinal
      const flags = b[p + 2];
      p += 3;
      const key = simple ? !!(flags & 0x80) : groupKey;
      const lacing = (flags >> 1) & 3;
      const pts = (tc + rel) * scale;
      let sizes;
      if (lacing === 0) sizes = [e - p];
      else {
        const n = b[p] + 1;
        p++;
        sizes = [];
        if (lacing === 1) { // Xiph
          for (let i = 0; i < n - 1; i++) { let sz = 0, v; do { v = b[p++]; sz += v; } while (v === 255); sizes.push(sz); }
        } else if (lacing === 3) { // EBML
          const first = readSize(b, p); p += first.length; sizes.push(first.value);
          for (let i = 1; i < n - 1; i++) {
            const d = readSize(b, p); p += d.length;
            const bias = Math.pow(2, 7 * d.length - 1) - 1;
            sizes.push(sizes[i - 1] + (d.value - bias));
          }
        } else { // tamanho fixo
          const each = Math.floor((e - p) / n);
          for (let i = 0; i < n - 1; i++) sizes.push(each);
        }
        const used = sizes.reduce((a, x) => a + x, 0);
        sizes.push(e - p - used);
      }
      const step = track.defaultDuration || (sizes.length > 1 && blockDur ? blockDur / sizes.length : frameSeconds(track));
      for (let i = 0; i < sizes.length; i++) {
        let data = b.subarray(p, p + sizes[i]);
        p += sizes[i];
        if (track.strip) data = concat([track.strip, data]);
        out.push({ track: track.number, pts: pts + i * step, key: key || track.type !== 1, data, dur: sizes.length === 1 ? blockDur : step });
      }
    }
  }

  /** Duração de um quadro de áudio (para lacing e para o último quadro). */
  function frameSeconds(t) {
    const sr = t.outRate || t.rate || 48000;
    switch (t.codec) {
      case "A_AAC": case "A_AAC/MPEG4/LC": case "A_AAC/MPEG2/LC": return (t.aacSbr ? 2048 : 1024) / sr;
      case "A_AC3": return 1536 / sr;
      case "A_EAC3": return (t.eac3Samples || 1536) / sr;
      case "A_MPEG/L3": return (sr < 32000 ? 576 : 1152) / sr;
      case "A_OPUS": return 0.02;
      default: return t.defaultDuration || 0.02;
    }
  }

  function concat(parts) {
    let n = 0;
    for (const p of parts) n += p.length;
    const out = new Uint8Array(n);
    let o = 0;
    for (const p of parts) { out.set(p, o); o += p.length; }
    return out;
  }

  // ------------------------------------------------------- bits auxiliares ---
  class Bits {
    constructor(b) { this.b = b; this.p = 0; }
    read(n) { let v = 0; for (let i = 0; i < n; i++) { const byte = this.b[this.p >> 3]; v = v * 2 + ((byte >> (7 - (this.p & 7))) & 1); this.p++; } return v; }
  }

  /** AC-3: campos do primeiro quadro para a caixa dac3. */
  function ac3Info(frame) {
    if (!frame || frame[0] !== 0x0b || frame[1] !== 0x77) return null;
    const r = new Bits(frame);
    r.read(16); r.read(16); // sync + crc
    const fscod = r.read(2), frmsizecod = r.read(6);
    const bsid = r.read(5), bsmod = r.read(3), acmod = r.read(3);
    if ((acmod & 1) && acmod !== 1) r.read(2);
    if (acmod & 4) r.read(2);
    if (acmod === 2) r.read(2);
    const lfeon = r.read(1);
    return { fscod, bsid, bsmod, acmod, lfeon, bitRateCode: frmsizecod >> 1 };
  }

  /** E-AC-3: campos do primeiro quadro para a caixa dec3. */
  function eac3Info(frame) {
    if (!frame || frame[0] !== 0x0b || frame[1] !== 0x77) return null;
    const r = new Bits(frame);
    r.read(16);
    r.read(2); r.read(3); // strmtyp, substreamid
    const frmsiz = r.read(11);
    let fscod = r.read(2), numblkscod = 3, sr;
    if (fscod === 3) { const fscod2 = r.read(2); sr = [24000, 22050, 16000][fscod2]; fscod = 0; } else { numblkscod = r.read(2); sr = [48000, 44100, 32000][fscod]; }
    const acmod = r.read(3), lfeon = r.read(1), bsid = r.read(5);
    const blocks = [1, 2, 3, 6][numblkscod];
    const bytes = (frmsiz + 1) * 2;
    const dataRate = Math.round((bytes * 8 * sr) / (blocks * 256) / 1000);
    return { fscod, bsid, acmod, lfeon, dataRate, samples: blocks * 256 };
  }

  // -------------------------------------------------------------- MP4 box ---
  function u8(v) { return [v & 255]; }
  function u16(v) { return [(v >> 8) & 255, v & 255]; }
  function u24(v) { return [(v >> 16) & 255, (v >> 8) & 255, v & 255]; }
  function u32(v) { return [(v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255]; }
  function u64(v) { const hi = Math.floor(v / 4294967296), lo = v - hi * 4294967296; return u32(hi).concat(u32(lo)); }
  function bytesOf(x) { return x instanceof Uint8Array ? x : new Uint8Array(x); }
  function box(type, ...parts) {
    const body = concat(parts.map(bytesOf));
    const out = new Uint8Array(8 + body.length);
    out.set(u32(8 + body.length), 0);
    for (let i = 0; i < 4; i++) out[4 + i] = type.charCodeAt(i);
    out.set(body, 8);
    return out;
  }
  function fullbox(type, version, flags, ...parts) { return box(type, [version].concat(u24(flags)), ...parts); }
  const ZERO = (n) => new Array(n).fill(0);

  /** hvcC sem VPS/SPS/PPS (vêm dentro dos quadros) = "hev1"; com eles = "hvc1". */
  function hevcTag(t) { return t.priv && t.priv.length > 22 && t.priv[22] > 0 ? "hvc1" : "hev1"; }

  /** "mp4a.40.2", "avc1.64001f"… — o texto que o MediaSource precisa para saber o formato. */
  function codecString(t) {
    const p = t.priv;
    switch (t.kind) {
      case "avc": return "avc1." + [p[1], p[2], p[3]].map((x) => x.toString(16).padStart(2, "0")).join("");
      case "hevc": {
        const space = p[1] >> 6, tier = (p[1] >> 5) & 1, profile = p[1] & 31;
        let compat = uint(p, 2, 4), rev = 0;
        for (let i = 0; i < 32; i++) { rev = (rev << 1) | (compat & 1); compat >>>= 1; }
        let s = hevcTag(t) + "." + ["", "A", "B", "C"][space] + profile + "." + (rev >>> 0).toString(16) + "." + (tier ? "H" : "L") + p[12];
        const cons = Array.from(p.subarray(6, 12));
        while (cons.length && cons[cons.length - 1] === 0) cons.pop();
        for (const c of cons) s += "." + c.toString(16);
        return s;
      }
      case "vp9": return "vp09.00.10.08";
      case "aac": return "mp4a.40." + t.aot;
      case "mp3": return "mp4a.6B";
      case "ac3": return "ac-3";
      case "eac3": return "ec-3";
      case "opus": return "opus";
    }
    return "";
  }

  /** Descobre se a faixa pode virar MP4 e prepara o que falta. */
  function prepareTrack(t, firstFrame) {
    const c = t.codec;
    if (t.encrypted || t.unsupportedCompression) return false;
    if (t.type === 1) {
      if (c === "V_MPEG4/ISO/AVC" && t.priv && t.priv.length > 6) { t.kind = "avc"; t.ts = 90000; return true; }
      if (c === "V_MPEGH/ISO/HEVC" && t.priv && t.priv.length > 22) { t.kind = "hevc"; t.ts = 90000; return true; }
      if (c === "V_VP9") { t.kind = "vp9"; t.ts = 90000; return true; }
      return false;
    }
    if (t.type === 2) {
      t.sr = Math.round(t.outRate || t.rate || 48000);
      if (c.startsWith("A_AAC")) {
        t.kind = "aac";
        if (!t.priv || !t.priv.length) {
          // A_AAC/MPEG2/LC antigo, sem configuração: monta uma (AAC-LC).
          const idx = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000].indexOf(Math.round(t.rate || t.sr));
          const f = idx < 0 ? 3 : idx;
          t.priv = new Uint8Array([(2 << 3) | (f >> 1), ((f & 1) << 7) | ((t.channels || 2) << 3)]);
        }
        t.aot = t.priv[0] >> 3;
        if (t.aot === 31) t.aot = 32 + (((t.priv[0] & 7) << 3) | (t.priv[1] >> 5));
        // A taxa de amostragem de verdade (o MKV às vezes guarda a "de saída" do HE-AAC).
        const fi = ((t.priv[0] & 7) << 1) | (t.priv[1] >> 7);
        const srs = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];
        if (fi < 13 && t.aot !== 5 && t.aot !== 29) t.sr = srs[fi];
        t.ts = t.sr;
        t.frameDur = 1024;
        return true;
      }
      if (c === "A_AC3") { t.kind = "ac3"; t.ac3 = ac3Info(firstFrame); t.ts = t.sr; t.frameDur = 1536; return !!t.ac3; }
      if (c === "A_EAC3") {
        t.kind = "eac3"; t.eac3 = eac3Info(firstFrame); t.ts = t.sr;
        if (!t.eac3) return false;
        t.frameDur = t.eac3.samples; t.eac3Samples = t.eac3.samples;
        return true;
      }
      if (c === "A_MPEG/L3") { t.kind = "mp3"; t.ts = t.sr; t.frameDur = t.sr < 32000 ? 576 : 1152; return true; }
      if (c === "A_OPUS" && t.priv && t.priv.length >= 19) { t.kind = "opus"; t.sr = 48000; t.ts = 48000; t.frameDur = 0; return true; }
      return false;
    }
    return false;
  }

  function sampleEntry(t) {
    if (t.type === 1) {
      const w = t.width || 640, h = t.height || 360;
      const visual = [].concat(ZERO(6), u16(1), ZERO(16), u16(w), u16(h), u32(0x00480000), u32(0x00480000), u32(0), u16(1), ZERO(32), u16(0x18), u16(0xffff));
      if (t.kind === "avc") return box("avc1", visual, box("avcC", t.priv));
      if (t.kind === "hevc") return box(hevcTag(t), visual, box("hvcC", t.priv));
      if (t.kind === "vp9") return box("vp09", visual, fullbox("vpcC", 1, 0, [0, 10, (8 << 4) | (1 << 1), 2, 2, 2].concat(u16(0))));
    }
    const ch = t.channels || 2;
    const audio = (sr) => [].concat(ZERO(6), u16(1), ZERO(8), u16(ch), u16(16), u16(0), u16(0), u32(Math.min(sr, 65535) * 65536));
    if (t.kind === "aac" || t.kind === "mp3") {
      const dsi = t.kind === "aac" ? [].concat([5, t.priv.length], Array.from(t.priv)) : [];
      const dcd = [].concat([t.kind === "aac" ? 0x40 : 0x6b, 0x15], u24(0), u32(0), u32(0), dsi);
      const esd = [].concat(u16(0), [0], [4, dcd.length], dcd, [6, 1, 2]);
      return box("mp4a", audio(t.sr), fullbox("esds", 0, 0, [3, esd.length], esd));
    }
    if (t.kind === "ac3") {
      const a = t.ac3;
      const r = new Uint8Array(3);
      let v = (a.fscod << 22) | (a.bsid << 17) | (a.bsmod << 14) | (a.acmod << 11) | (a.lfeon << 10) | (a.bitRateCode << 5);
      r[0] = (v >> 16) & 255; r[1] = (v >> 8) & 255; r[2] = v & 255;
      return box("ac-3", audio(t.sr), box("dac3", r));
    }
    if (t.kind === "eac3") {
      const a = t.eac3;
      // data_rate(13) num_ind_sub(3)=0 | fscod(2) bsid(5) res(1) asvc(1) bsmod(3) acmod(3) lfeon(1) res(3) num_dep_sub(4) res(1)
      const b = new Uint8Array(5);
      const w1 = (a.dataRate << 3) | 0;
      b[0] = (w1 >> 8) & 255; b[1] = w1 & 255;
      b[2] = (a.fscod << 6) | (a.bsid << 1);
      b[3] = (0 << 7) | (0 << 6) | (0 << 3) | 0; // asvc, bsmod (0)
      b[3] = (a.acmod << 1) | a.lfeon;
      b[4] = 0;
      return box("ec-3", audio(t.sr), box("dec3", b));
    }
    if (t.kind === "opus") {
      const p = t.priv; // "OpusHead" (little-endian) -> dOps (big-endian)
      const chs = p[9], preskip = p[10] | (p[11] << 8), rate = (p[12] | (p[13] << 8) | (p[14] << 16) | (p[15] << 24)) >>> 0;
      const gain = p[16] | (p[17] << 8), family = p[18];
      let d = [0, chs].concat(u16(preskip), u32(rate), u16(gain), [family]);
      if (family !== 0 && p.length >= 21 + chs) d = d.concat(Array.from(p.subarray(19, 21 + chs)));
      return box("Opus", [].concat(ZERO(6), u16(1), ZERO(8), u16(chs), u16(16), u16(0), u16(0), u32(48000 * 65536)), box("dOps", d));
    }
    return null;
  }

  // ---------------------------------------------------------------- Muxer ---
  class Mp4Mux {
    constructor(tracks) {
      this.tracks = tracks; // [{ id, ...track }]
      this.seq = 1;
      this.last = new Map(); // id -> último dts (ticks)
      this.shift = null; // segundos somados a todos os tempos (ver fragment)
    }

    init() {
      const traks = this.tracks.map((t) => {
        const isV = t.type === 1;
        const tkhd = fullbox("tkhd", 0, 3, [].concat(u32(0), u32(0), u32(t.id), u32(0), u32(0), ZERO(8), u16(0), u16(0),
          u16(isV ? 0 : 0x0100), u16(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000),
          u32(isV ? (t.width || 640) * 65536 : 0), u32(isV ? (t.height || 360) * 65536 : 0)));
        const mdhd = fullbox("mdhd", 0, 0, [].concat(u32(0), u32(0), u32(t.ts), u32(0), u16(0x55c4), u16(0)));
        const hdlr = fullbox("hdlr", 0, 0, [].concat(u32(0), Array.from(isV ? "vide" : "soun").map((c) => c.charCodeAt(0)), ZERO(12), [0]));
        const xmhd = isV ? fullbox("vmhd", 0, 1, ZERO(8)) : fullbox("smhd", 0, 0, ZERO(4));
        const dinf = box("dinf", fullbox("dref", 0, 0, u32(1), fullbox("url ", 0, 1)));
        const stbl = box("stbl", fullbox("stsd", 0, 0, u32(1), t.entry || sampleEntry(t)),
          fullbox("stts", 0, 0, u32(0)), fullbox("stsc", 0, 0, u32(0)), fullbox("stsz", 0, 0, u32(0), u32(0)), fullbox("stco", 0, 0, u32(0)));
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", xmhd, dinf, stbl)));
      });
      const mvhd = fullbox("mvhd", 0, 0, [].concat(u32(0), u32(0), u32(1000), u32(0), u32(0x10000), u16(0x0100), ZERO(10),
        u32(0x10000), u32(0), u32(0), u32(0), u32(0x10000), u32(0), u32(0), u32(0), u32(0x40000000), ZERO(24), u32(this.tracks.length + 1)));
      const mvex = box("mvex", ...this.tracks.map((t) => fullbox("trex", 0, 0, [].concat(u32(t.id), u32(1), u32(0), u32(0), u32(0)))));
      const ftyp = box("ftyp", Array.from("isom").map((c) => c.charCodeAt(0)), u32(0x200), ...["isom", "iso6", "mp41"].map((s) => Array.from(s).map((c) => c.charCodeAt(0))));
      return concat([ftyp, box("moov", mvhd, ...traks, mvex)]);
    }

    /** Pulo: o próximo fragmento pode voltar no tempo. */
    reset() { this.last.clear(); }

    /** Monta um fragmento (moof+mdat) com os quadros de um Cluster. */
    fragment(frames) {
      if (this.shift == null) {
        // Atraso de reordenação (quadros B): todo o filme anda esse tanto para a frente,
        // assim o tempo de decodificação nunca passa do de exibição (o Safari é exigente nisso).
        const v = this.tracks.find((t) => t.type === 1);
        const fs = v ? frames.filter((f) => f.track === v.number) : [];
        if (fs.length) {
          const pts = fs.map((f) => Math.round(f.pts * v.ts));
          const sorted = pts.slice().sort((a, b) => a - b);
          let d = 0;
          for (let i = 0; i < pts.length; i++) d = Math.max(d, sorted[i] - pts[i]);
          this.shift = Math.min(d, v.ts) / v.ts;
        } else if (!v) this.shift = 0;
      }
      const trafs = [];
      for (const t of this.tracks) {
        const fs = frames.filter((f) => f.track === t.number);
        if (!fs.length) continue;
        trafs.push(t.type === 1 ? this.videoSamples(t, fs) : this.audioSamples(t, fs));
      }
      return this.build(trafs);
    }

    /** moof+mdat a partir das amostras já prontas de cada faixa ({ id, base, samples, data }). */
    build(trafs) {
      if (!trafs.length) return null;
      const build = (offsets) => {
        let off = 0;
        const parts = trafs.map((tr, i) => {
          const sampleFlags = tr.samples.map((s) => s.flags);
          const trun = fullbox("trun", 1, 0x000f01, [].concat(u32(tr.samples.length), u32(offsets ? offsets[i] : 0)),
            ...tr.samples.map((s, k) => [].concat(u32(s.dur), u32(s.size), u32(sampleFlags[k]), u32(s.cts >>> 0))));
          return box("traf", fullbox("tfhd", 0, 0x020000, u32(tr.id)), fullbox("tfdt", 1, 0, u64(tr.base)), trun);
        });
        return box("moof", fullbox("mfhd", 0, 0, u32(this.seq)), ...parts);
      };
      const probe = build(null);
      const offsets = [];
      let pos = probe.length + 8;
      for (const tr of trafs) { offsets.push(pos); pos += tr.data.reduce((a, d) => a + d.length, 0); }
      const moof = build(offsets);
      this.seq++;
      const datas = [];
      for (const tr of trafs) datas.push(...tr.data);
      const mdat = box("mdat", concat(datas));
      return concat([moof, mdat]);
    }

    videoSamples(t, fs) {
      const ts = t.ts;
      const sh = Math.round((this.shift || 0) * ts);
      const raw = fs.map((f) => Math.round(f.pts * ts));
      const sorted = raw.slice().sort((a, b) => a - b);
      const pts = raw.map((x) => x + sh);
      let lastDts = this.last.has(t.id) ? this.last.get(t.id) : null;
      const dts = sorted.map((d) => { if (d < 0) d = 0; if (lastDts != null && d <= lastDts) d = lastDts + 1; lastDts = d; return d; });
      const typical = t.defaultDuration ? Math.round(t.defaultDuration * ts) : (dts.length > 1 ? Math.round((dts[dts.length - 1] - dts[0]) / (dts.length - 1)) : 3750);
      this.last.set(t.id, dts[dts.length - 1]);
      const samples = fs.map((f, i) => ({
        dur: i < fs.length - 1 ? Math.max(1, dts[i + 1] - dts[i]) : Math.max(1, typical),
        size: f.data.length,
        flags: f.key ? 0x02000000 : 0x01010000,
        cts: Math.max(pts[i], 0) - dts[i],
      }));
      return { id: t.id, base: dts[0], samples, data: fs.map((f) => f.data) };
    }

    audioSamples(t, fs) {
      const ts = t.ts;
      let base = Math.round((fs[0].pts + (this.shift || 0)) * ts);
      // Emenda com o pedaço anterior: o MKV guarda o tempo arredondado em milissegundos, e um
      // buraco/sobreposição minúsculo entre pedaços vira estalo ou trava no Safari.
      if (this.last.has(t.id) && Math.abs(base - this.last.get(t.id)) < ts / 20) base = this.last.get(t.id);
      if (base < 0) base = 0;
      const samples = fs.map((f, i) => {
        let dur = t.frameDur;
        if (!dur) dur = i < fs.length - 1 ? Math.max(1, Math.round((fs[i + 1].pts - f.pts) * ts)) : Math.max(1, Math.round((f.dur || 0.02) * ts));
        return { dur, size: f.data.length, flags: 0x02000000, cts: 0 };
      });
      this.last.set(t.id, base + samples.reduce((a, s) => a + s.dur, 0));
      return { id: t.id, base, samples, data: fs.map((f) => f.data) };
    }
  }

  /** Legenda embutida (SRT/ASS/WebVTT) → texto puro. */
  function subtitleText(codec, data) {
    let s = utf8(data);
    if (codec === "S_TEXT/ASS" || codec === "S_TEXT/SSA") {
      // ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text
      const parts = s.split(",");
      s = parts.length > 8 ? parts.slice(8).join(",") : s;
      s = s.replace(/\{[^}]*\}/g, "").replace(/\\N/gi, "\n").replace(/\\h/g, " ");
    } else {
      s = s.replace(/<[^>]+>/g, "");
    }
    return s.trim();
  }

  /** Escolhe as faixas: o primeiro vídeo e o áudio "melhor" (português, depois o padrão). */
  function chooseTracks(file, firstFrames) {
    const video = file.tracks.find((t) => t.type === 1 && prepareTrack(t, null));
    const audios = file.tracks.filter((t) => t.type === 2);
    const isPt = (t) => /^(por|pt|pob|pt-br)$/i.test(t.lang) || /^pt/i.test(t.ietf || "") || /portugu|dublad/i.test(t.name || "");
    const ranked = audios.slice().sort((a, b) => (isPt(b) - isPt(a)) || (b.isDefault - a.isDefault));
    let audio = null;
    const skipped = [];
    for (const a of ranked) {
      if (prepareTrack(a, firstFrames.get(a.number))) { audio = a; break; }
      skipped.push(a.codec);
    }
    const subs = file.tracks.filter((t) => t.type === 17 && /^S_TEXT\/(UTF8|ASS|SSA|WEBVTT)$/.test(t.codec));
    const sub = subs.slice().sort((a, b) => (isPt(b) - isPt(a)) || (b.isDefault - a.isDefault))[0] || null;
    return { video, audio, sub, skippedAudio: skipped, unsupportedVideo: !video ? (file.tracks.find((t) => t.type === 1) || {}).codec : null };
  }

  // ---------------------------------------------------------------- MP4 ---
  const be32 = (b, p) => ((b[p] << 24) >>> 0) + (b[p + 1] << 16) + (b[p + 2] << 8) + b[p + 3];
  const be16 = (b, p) => (b[p] << 8) | b[p + 1];
  const i32 = (b, p) => be32(b, p) | 0;
  const be64 = (b, p) => be32(b, p) * 4294967296 + be32(b, p + 4);
  const type4 = (b, p) => String.fromCharCode(b[p], b[p + 1], b[p + 2], b[p + 3]);
  /** Percorre as caixas de [s, e): cb(tipo, inícioDoConteúdo, fim, inícioDaCaixa). */
  function boxes(b, s, e, cb) {
    let p = s;
    while (p + 8 <= e) {
      let size = be32(b, p), hl = 8;
      const type = type4(b, p + 4);
      if (size === 1) { size = be64(b, p + 8); hl = 16; } else if (size === 0) size = e - p;
      if (size < hl || p + size > e) break;
      if (cb(type, p + hl, p + size, p) === false) break;
      p += size;
    }
  }
  function child(b, s, e, type) { let r = null; boxes(b, s, e, (t, cs, ce, bs) => { if (t === type) { r = { s: cs, e: ce, box: bs }; return false; } }); return r; }
  function path(b, s, e, types) { let r = { s, e }; for (const t of types) { r = child(b, r.s, r.e, t); if (!r) return null; } return r; }
  /** Descritor MPEG-4 (esds): tamanho em até 4 bytes. */
  function descr(b, p) { const tag = b[p++]; let len = 0; for (let i = 0; i < 4; i++) { const c = b[p++]; len = (len << 7) | (c & 0x7f); if (!(c & 0x80)) break; } return { tag, len, s: p }; }

  /** MP4/MOV lido aos pedaços: índice (moov) + tabela de amostras de cada faixa. */
  class Mp4File {
    constructor(fetchRange, size) { this.fetchRange = fetchRange; this.size = size; this.tracks = []; this.duration = 0; }

    async open() {
      let pos = 0, moov = null;
      for (let guard = 0; pos + 8 <= this.size && guard < 200; guard++) {
        const h = await this.fetchRange(pos, Math.min(this.size, pos + 16) - 1);
        let size = be32(h, 0), hl = 8;
        const type = type4(h, 4);
        if (size === 1) { size = be64(h, 8); hl = 16; } else if (size === 0) size = this.size - pos;
        if (size < hl) throw new Error("MP4 inválido");
        if (type === "moov") {
          if (size > 256 * 1024 * 1024) throw new Error("índice grande demais");
          moov = await this.fetchRange(pos + hl, pos + size - 1);
          break;
        }
        pos += size;
      }
      if (!moov) throw new Error("MP4 sem índice (moov)");
      this.parse(moov);
      return this;
    }

    parse(b) {
      if (child(b, 0, b.length, "mvex")) this.fragmented = true;
      const mvhd = child(b, 0, b.length, "mvhd");
      let movieTs = 1000;
      if (mvhd) {
        const v1 = b[mvhd.s] === 1;
        movieTs = be32(b, mvhd.s + (v1 ? 20 : 12));
        const d = v1 ? be64(b, mvhd.s + 24) : be32(b, mvhd.s + 16);
        this.duration = movieTs ? d / movieTs : 0;
      }
      boxes(b, 0, b.length, (type, s, e) => { if (type === "trak") { const t = this.parseTrak(b, s, e, movieTs); if (t) this.tracks.push(t); } });
    }

    parseTrak(b, s, e, movieTs) {
      const t = { id: 0, handler: "", ts: 0, lang: "und", width: 0, height: 0, off: 0 };
      const tkhd = child(b, s, e, "tkhd");
      if (tkhd) {
        const v1 = b[tkhd.s] === 1;
        t.id = be32(b, tkhd.s + (v1 ? 20 : 12));
        t.width = be32(b, tkhd.e - 8) >>> 16; t.height = be32(b, tkhd.e - 4) >>> 16;
      }
      const mdia = child(b, s, e, "mdia");
      if (!mdia) return null;
      const mdhd = child(b, mdia.s, mdia.e, "mdhd");
      if (mdhd) {
        const v1 = b[mdhd.s] === 1;
        t.ts = be32(b, mdhd.s + (v1 ? 20 : 12));
        const l = be16(b, mdhd.s + (v1 ? 32 : 20));
        // MOV antigo usa os códigos de idioma do Mac (abaixo de 0x400); MP4 usa as 3 letras ISO.
        if (l < 0x400) t.lang = { 0: "eng", 1: "fra", 2: "deu", 3: "ita", 6: "spa", 8: "por" }[l] || "und";
        else if (l !== 0x7fff) t.lang = String.fromCharCode(((l >> 10) & 31) + 0x60, ((l >> 5) & 31) + 0x60, (l & 31) + 0x60);
      }
      const hdlr = child(b, mdia.s, mdia.e, "hdlr");
      if (hdlr) t.handler = type4(b, hdlr.s + 8);
      if (t.handler !== "vide" && t.handler !== "soun") return null;
      if (!t.ts) return null;
      // Edit list: onde a mídia começa de verdade (atraso dos quadros B / "priming" do AAC).
      const elst = path(b, s, e, ["edts", "elst"]);
      if (elst) {
        const v1 = b[elst.s] === 1, n = be32(b, elst.s + 4), sz = v1 ? 20 : 12;
        let p = elst.s + 8, empty = 0;
        for (let i = 0; i < n && p + sz <= elst.e; i++, p += sz) {
          const dur = v1 ? be64(b, p) : be32(b, p);
          const mt = v1 ? be32(b, p + 8) * 4294967296 + be32(b, p + 12) : i32(b, p + 4);
          if (mt === -1 || mt === 4294967295 * 4294967296 + 4294967295 || (v1 && be32(b, p + 8) === 0xffffffff)) { empty += dur / movieTs; continue; }
          t.off = mt / t.ts - empty;
          break;
        }
      }
      const stbl = path(b, mdia.s, mdia.e, ["minf", "stbl"]);
      if (!stbl) return null;
      const stsd = child(b, stbl.s, stbl.e, "stsd");
      if (!stsd) return null;
      const es = stsd.s + 8, esize = be32(b, es);
      t.entryType = type4(b, es + 4);
      t.rawEntry = b.slice(es, es + esize);
      t.type = t.handler === "vide" ? 1 : 2;
      const tbl = (type) => child(b, stbl.s, stbl.e, type);
      t.stts = tbl("stts"); t.ctts = tbl("ctts"); t.stsc = tbl("stsc"); t.stsz = tbl("stsz"); t.stz2 = tbl("stz2");
      t.stco = tbl("stco"); t.co64 = tbl("co64"); t.stss = tbl("stss");
      t.b = b;
      return t;
    }

    /** Monta as tabelas (posição, tamanho, tempos e se é quadro-chave) de cada amostra. */
    samples(t) {
      if (t.s) return t.s;
      const b = t.b;
      let n = 0, sizes;
      if (t.stsz) {
        const fixed = be32(b, t.stsz.s + 4); n = be32(b, t.stsz.s + 8);
        sizes = new Uint32Array(n);
        for (let i = 0; i < n; i++) sizes[i] = fixed || be32(b, t.stsz.s + 12 + i * 4);
      } else if (t.stz2) {
        const field = b[t.stz2.s + 7]; n = be32(b, t.stz2.s + 8);
        sizes = new Uint32Array(n);
        for (let i = 0; i < n; i++) {
          const p = t.stz2.s + 12;
          sizes[i] = field === 16 ? be16(b, p + i * 2) : field === 8 ? b[p + i] : ((b[p + (i >> 1)] >> ((i & 1) ? 0 : 4)) & 15);
        }
      } else throw new Error("MP4 sem tamanhos de amostra");
      const chunks = [];
      if (t.stco) { const c = be32(b, t.stco.s + 4); for (let i = 0; i < c; i++) chunks.push(be32(b, t.stco.s + 8 + i * 4)); }
      else if (t.co64) { const c = be32(b, t.co64.s + 4); for (let i = 0; i < c; i++) chunks.push(be64(b, t.co64.s + 8 + i * 8)); }
      const offset = new Float64Array(n);
      const stsc = [];
      if (t.stsc) { const c = be32(b, t.stsc.s + 4); for (let i = 0; i < c; i++) stsc.push([be32(b, t.stsc.s + 8 + i * 12), be32(b, t.stsc.s + 12 + i * 12)]); }
      let k = 0;
      for (let i = 0; i < stsc.length && k < n; i++) {
        const firstChunk = stsc[i][0], per = stsc[i][1];
        const lastChunk = i + 1 < stsc.length ? stsc[i + 1][0] - 1 : chunks.length;
        for (let c = firstChunk; c <= lastChunk && k < n; c++) {
          let off = chunks[c - 1];
          for (let j = 0; j < per && k < n; j++) { offset[k] = off; off += sizes[k]; k++; }
        }
      }
      const dts = new Float64Array(n), cts = new Int32Array(n), key = new Uint8Array(n);
      let d = 0; k = 0;
      if (t.stts) {
        const c = be32(b, t.stts.s + 4);
        for (let i = 0; i < c; i++) {
          const cnt = be32(b, t.stts.s + 8 + i * 8), delta = be32(b, t.stts.s + 12 + i * 8);
          for (let j = 0; j < cnt && k < n; j++) { dts[k++] = d; d += delta; }
        }
      }
      for (; k < n; k++) { dts[k] = d; }
      if (t.ctts) {
        const c = be32(b, t.ctts.s + 4); k = 0;
        for (let i = 0; i < c; i++) {
          const cnt = be32(b, t.ctts.s + 8 + i * 8), o = i32(b, t.ctts.s + 12 + i * 8);
          for (let j = 0; j < cnt && k < n; j++) cts[k++] = o;
        }
      }
      if (t.stss) { const c = be32(b, t.stss.s + 4); for (let i = 0; i < c; i++) { const x = be32(b, t.stss.s + 8 + i * 4) - 1; if (x >= 0 && x < n) key[x] = 1; } }
      else key.fill(1);
      t.end = d; // tempo final (ticks)
      t.s = { n, offset, size: sizes, dts, cts, key };
      return t.s;
    }
  }

  /** "avc1.640028", "mp4a.40.2"… a partir da entrada stsd original do MP4. */
  function mp4Codec(t) {
    const b = t.rawEntry, et = t.entryType;
    if (t.type === 1) {
      const kids = 86; // cabeçalho (8) + campos de vídeo (78)
      if (et === "avc1" || et === "avc3") { const c = child(b, kids, b.length, "avcC"); return c ? et + "." + [b[c.s + 1], b[c.s + 2], b[c.s + 3]].map((x) => x.toString(16).padStart(2, "0")).join("") : null; }
      if (et === "hvc1" || et === "hev1") { const c = child(b, kids, b.length, "hvcC"); if (!c) return null; const s = codecString({ kind: "hevc", priv: b.subarray(c.s, c.e) }); return et + s.slice(4); }
      if (et === "vp09") { const c = child(b, kids, b.length, "vpcC"); if (!c) return "vp09.00.10.08"; const p2 = (x) => String(x).padStart(2, "0"); return `vp09.${p2(b[c.s + 4])}.${p2(b[c.s + 5])}.${p2(b[c.s + 6] >> 4)}`; }
      if (et === "av01") { const c = child(b, kids, b.length, "av1C"); if (!c) return null; const x = b[c.s + 1], y = b[c.s + 2]; const bd = (y >> 6) & 1 ? ((y >> 5) & 1 ? 12 : 10) : 8; return `av01.${x >> 5}.${String(x & 31).padStart(2, "0")}${y >> 7 ? "H" : "M"}.${String(bd).padStart(2, "0")}`; }
      return null;
    }
    if (et === "mp4a") {
      const es = audioChild(t, "esds");
      if (!es) return "mp4a.40.2";
      let d = descr(b, es.s + 4);
      if (d.tag !== 3) return "mp4a.40.2";
      let p = d.s + 2; const fl = b[p++]; if (fl & 0x80) p += 2; if (fl & 0x40) p += 1 + b[p]; if (fl & 0x20) p += 2;
      d = descr(b, p);
      if (d.tag !== 4) return "mp4a.40.2";
      const oti = b[d.s];
      if (oti === 0x6b || oti === 0x69) return "mp4a." + oti.toString(16).toUpperCase();
      if (oti === 0x40 || oti === 0x67 || oti === 0x66) {
        const ds = descr(b, d.s + 13);
        if (ds.tag === 5) { let aot = b[ds.s] >> 3; if (aot === 31) aot = 32 + (((b[ds.s] & 7) << 3) | (b[ds.s + 1] >> 5)); return "mp4a.40." + aot; }
        return "mp4a.40.2";
      }
      return "mp4a." + oti.toString(16);
    }
    if (et === "ac-3") return "ac-3";
    if (et === "ec-3") return "ec-3";
    if (et === "Opus") return "opus";
    if (et === "fLaC") return "flac";
    return null;
  }
  /** Caixa de configuração do áudio (esds, dac3…), mesmo dentro do "wave" dos MOV antigos. */
  function audioChild(t, type) {
    const b = t.rawEntry, ver = be16(b, 16);
    const start = 36 + (ver === 1 ? 16 : ver === 2 ? 36 : 0);
    return child(b, start, b.length, type) || (() => { const w = child(b, start, b.length, "wave"); return w ? child(b, w.s, w.e, type) : null; })();
  }
  /** Entrada de áudio limpa (formato ISO), com a configuração original dentro. */
  function audioEntry(t) {
    const b = t.rawEntry;
    const cfgType = { mp4a: "esds", "ac-3": "dac3", "ec-3": "dec3", Opus: "dOps", fLaC: "dfLa" }[t.entryType];
    const c = cfgType && audioChild(t, cfgType);
    if (!c) return null;
    const ch = be16(b, 24) || 2, sr = be32(b, 32) >>> 16 || t.ts;
    const fields = [].concat(ZERO(6), u16(1), ZERO(8), u16(ch), u16(16), u16(0), u16(0), u32(Math.min(sr, 65535) * 65536));
    return box(t.entryType, fields, b.subarray(c.box, c.e));
  }

  /** Fonte MP4 para o player: faixas, segmentos por GOP e leitura das amostras. */
  async function mp4Source(fetchRange, size, rc) {
    const file = await new Mp4File(fetchRange, size).open();
    if (file.fragmented) throw Object.assign(new Error("MP4 fragmentado"), { fallback: true });
    const isPt = (t) => /^(por|pob)$/.test(t.lang);
    const vids = file.tracks.filter((t) => t.type === 1 && mp4Codec(t));
    const auds = file.tracks.filter((t) => t.type === 2 && mp4Codec(t) && audioEntry(t));
    const video = vids[0];
    if (!video) throw Object.assign(new Error("vídeo sem formato conhecido"), { fallback: true });
    const audio = auds.slice().sort((a, b) => isPt(b) - isPt(a))[0] || null;
    const tracks = [video, audio].filter(Boolean);
    tracks.forEach((t, i) => {
      t.number = t.id; t.id = i + 1; t.mime = mp4Codec(t);
      t.entry = t.type === 1 ? t.rawEntry : audioEntry(t);
      file.samples(t);
    });
    // Alinha as faixas pelo edit list: tudo anda "maxOff" para a frente (nada fica negativo).
    const maxOff = Math.max(0, ...tracks.map((t) => t.off || 0));
    tracks.forEach((t) => { t.shiftTicks = Math.round((maxOff - (t.off || 0)) * t.ts); });
    const V = video.s;
    // Segmentos: de um quadro-chave ao próximo (pelo menos ~1,5 s cada).
    const segs = [];
    let start = 0;
    for (let i = 1; i < V.n; i++) {
      if (!V.key[i]) continue;
      if ((V.dts[i] - V.dts[start]) / video.ts >= 1.5) { segs.push([start, i]); start = i; }
    }
    if (V.n) segs.push([start, V.n]);
    const vt = (i) => i < V.n ? (V.dts[i] + V.shiftTicks) / video.ts : (video.end + video.shiftTicks) / video.ts;
    V.shiftTicks = video.shiftTicks;
    const segTime = segs.map(([a]) => (V.dts[a] + V.cts[a] + video.shiftTicks) / video.ts);
    // Áudio de cada segmento: amostras com tempo dentro do trecho do vídeo.
    const A = audio ? audio.s : null;
    const aRange = [];
    if (A) {
      let k = 0;
      for (let j = 0; j < segs.length; j++) {
        const tEnd = j + 1 < segs.length ? vt(segs[j + 1][0]) : Infinity;
        const a0 = k;
        while (k < A.n && (A.dts[k] + audio.shiftTicks) / audio.ts < tEnd) k++;
        aRange.push([a0, k]);
      }
    }
    const spans = (j) => {
      const out = [];
      const add = (S, a, z) => { for (let i = a; i < z; i++) out.push([S.offset[i], S.size[i]]); };
      add(V, segs[j][0], segs[j][1]);
      if (A) add(A, aRange[j][0], aRange[j][1]);
      out.sort((x, y) => x[0] - y[0]);
      // Junta amostras vizinhas em poucos pedidos.
      const runs = [];
      for (const [o, l] of out) {
        const last = runs[runs.length - 1];
        if (last && o - (last[0] + last[1]) < 512 * 1024) last[1] = Math.max(last[1], o + l - last[0]);
        else runs.push([o, l]);
      }
      return runs;
    };
    const trackTraf = (t, S, a, z, data) => {
      const samples = [];
      for (let i = a; i < z; i++) {
        const dur = i + 1 < S.n ? S.dts[i + 1] - S.dts[i] : (i > 0 ? S.dts[i] - S.dts[i - 1] : 1);
        samples.push({ dur: Math.max(1, dur), size: S.size[i], flags: t.type === 1 ? (S.key[i] ? 0x02000000 : 0x01010000) : 0x02000000, cts: t.type === 1 ? S.cts[i] : 0 });
      }
      return { id: t.id, base: S.dts[a] + t.shiftTicks, samples, data };
    };
    return {
      kind: "mp4", tracks, duration: file.duration || vt(V.n), shift: maxOff,
      first: 0,
      isEnd: (c) => c >= segs.length,
      cursorFor(t) { t += maxOff; let lo = 0, hi = segs.length - 1, best = 0; while (lo <= hi) { const m = (lo + hi) >> 1; if (segTime[m] <= t + 0.05) { best = m; lo = m + 1; } else hi = m - 1; } return best; },
      plan(c, target) {
        const out = []; let sum = 0;
        for (let j = c; j < segs.length && sum < target; j++) for (const r of spans(j)) { out.push(r); sum += r[1]; }
        return out;
      },
      async read(c) {
        const runs = spans(c);
        const bufs = [];
        for (const [o, l] of runs) bufs.push([o, await rc.get(o, l, true)]);
        const slice = (S, i) => { const o = S.offset[i]; for (const [ro, rb] of bufs) if (o >= ro && o + S.size[i] <= ro + rb.length) return rb.subarray(o - ro, o - ro + S.size[i]); return new Uint8Array(S.size[i]); };
        const vd = []; for (let i = segs[c][0]; i < segs[c][1]; i++) vd.push(slice(V, i));
        const trafs = [trackTraf(video, V, segs[c][0], segs[c][1], vd)];
        if (A && aRange[c][1] > aRange[c][0]) {
          const ad = []; for (let i = aRange[c][0]; i < aRange[c][1]; i++) ad.push(slice(A, i));
          trafs.push(trackTraf(audio, A, aRange[c][0], aRange[c][1], ad));
        }
        return { next: c + 1, trafs, until: c + 1 < segs.length ? segTime[c + 1] : vt(V.n) };
      },
      info: { video: video.mime, audio: audio ? audio.mime : null, skipped: file.tracks.filter((t) => t.type === 2 && !auds.includes(t)).map((t) => t.entryType) },
    };
  }

  // ------------------------------------------------------- cache de rede ---
  /**
   * Pedaços de 4 MB do arquivo guardados na memória. O player lê daqui, e um "carregador"
   * vai baixando adiantado (vários minutos), um pedaço de cada vez — se a internet ou o
   * Drive soluçarem, o filme continua com o que já veio.
   */
  class RangeCache {
    constructor(fetchRange, size, cap) {
      this.fetchRange = fetchRange; this.size = size;
      this.B = 4 * 1024 * 1024;
      this.cap = cap || 192 * 1024 * 1024;
      this.blocks = new Map(); this.pending = new Map(); this.done = new Set(); this.bytes = 0;
    }
    has(pos) { return this.blocks.has(Math.floor(pos / this.B)); }
    block(i) {
      const got = this.blocks.get(i);
      if (got) return Promise.resolve(got);
      let p = this.pending.get(i);
      if (!p) {
        p = this.fetchRange(i * this.B, Math.min(this.size, (i + 1) * this.B) - 1).then((b) => {
          this.pending.delete(i);
          if (!this.blocks.has(i)) { this.blocks.set(i, b); this.bytes += b.length; this.trim(); }
          return b;
        }, (e) => { this.pending.delete(i); throw e; });
        this.pending.set(i, p);
      }
      return p;
    }
    trim() {
      if (this.bytes <= this.cap) return;
      for (const i of [...this.done]) { if (this.bytes <= this.cap) return; this.drop(i); }
      for (const i of [...this.blocks.keys()]) { if (this.bytes <= this.cap) return; this.drop(i); }
    }
    drop(i) { const b = this.blocks.get(i); if (b) { this.bytes -= b.length; this.blocks.delete(i); } this.done.delete(i); }
    /** Primeiro pedaço de [pos, pos+len) que ainda não está aqui nem chegando (-1 = tudo certo). */
    missing(pos, len) {
      const first = Math.floor(pos / this.B), last = Math.floor((Math.min(this.size, pos + len) - 1) / this.B);
      for (let i = first; i <= last; i++) if (!this.blocks.has(i) && !this.pending.has(i)) return i;
      return -1;
    }
    /** Bytes [pos, pos+len). consume = o player já usou: esses pedaços saem primeiro da memória. */
    async get(pos, len, consume) {
      const end = Math.min(this.size, pos + len) - 1;
      if (end < pos) return new Uint8Array(0);
      const first = Math.floor(pos / this.B), last = Math.floor(end / this.B);
      const parts = [];
      for (let i = first; i <= last; i++) parts.push(await this.block(i));
      if (consume) for (let i = first; i < last; i++) this.done.add(i);
      const all = parts.length === 1 ? parts[0] : concat(parts);
      const off = pos - first * this.B;
      return all.subarray(off, off + (end - pos + 1));
    }
  }

  // --------------------------------------------------------------- Player ---
  /**
   * Toca MKV ou MP4/MOV num <video> pelo MediaSource, com o nosso próprio carregamento.
   * url = endereço que aceita Range (Drive com chave).
   * opts: size, sizeUrl, format ("mkv" | "mp4" | auto), aheadSeconds (padrão 600), cacheMB (padrão 192),
   *   onError(msg, passageiro, info), onRecover(), onInfo(msg), onFallback(motivo) (MP4 que o
   *   player não sabe ler: use o <video> normal), subtitles (bool).
   */
  function StreamPlayer(video, url, opts) {
    opts = opts || {};
    const MS = root.ManagedMediaSource || root.MediaSource;
    const managed = !!root.ManagedMediaSource && MS === root.ManagedMediaSource;
    // transient = problema passageiro (o player continua tentando sozinho).
    const fail = (m, transient, info) => { if (!destroyed && opts.onError) opts.onError(m, !!transient, info || null); };
    const recovered = () => { if (!destroyed && opts.onRecover) opts.onRecover(); };
    if (!MS) { fail("Este navegador não consegue tocar MKV (no iPhone, precisa do iOS 17.1 ou mais novo)."); return null; }

    let watchdog = 0, stuckSince = 0, appendedUntil = 0, started = false;
    let destroyed = false, gen = 0, cursor = -1, src = null, rc = null, mux = null, sb = null, streaming = true, ended = false;
    let subTrack = null, failing = false, targetBytes = 32 * 1024 * 1024;
    const ms = new MS();
    if (managed) video.disableRemotePlayback = true;
    let objUrl = null;
    try {
      if (managed && "srcObject" in video) video.srcObject = ms;
      else { objUrl = URL.createObjectURL(ms); video.src = objUrl; }
    } catch (_) { objUrl = URL.createObjectURL(ms); video.src = objUrl; }
    if (managed) {
      ms.addEventListener("startstreaming", () => { streaming = true; kick(); });
      ms.addEventListener("endstreaming", () => { streaming = false; });
    }

    async function fetchRange(a, b) {
      for (let attempt = 0; ; attempt++) {
        try {
          const r = await fetch(url, { headers: { Range: `bytes=${a}-${b}` } });
          if (!r.ok) throw await httpError(r);
          const buf = new Uint8Array(await r.arrayBuffer());
          // Servidor que ignorou o Range: recorta.
          if (r.status === 200 && buf.length > b - a + 1) return buf.subarray(a, b + 1);
          return buf;
        } catch (e) {
          if (e.fatal || attempt >= 4 || destroyed) throw e;
          await sleep(600 * (attempt + 1) * (e.status === 429 || /rateLimit/i.test(e.reason || "") ? 8 : 1));
        }
      }
    }

    async function getSize() {
      if (opts.size) return opts.size;
      const r = await fetch(url, { headers: { Range: "bytes=0-0" } });
      if (!r.ok) throw await httpError(r);
      const cr = r.headers.get("Content-Range");
      if (cr && /\/(\d+)$/.test(cr)) return +RegExp.$1;
      if (opts.sizeUrl) { try { const j = await (await fetch(opts.sizeUrl)).json(); if (j && j.size) return +j.size; } catch (_) {} }
      throw new Error("Não consegui saber o tamanho do arquivo.");
    }

    /** Erro HTTP do Drive → mensagem certa. Cota e "muitos pedidos" são passageiros. */
    async function httpError(r) {
      let reason = "";
      try { const j = await r.json(); reason = (j.error && ((j.error.errors && j.error.errors[0] && j.error.errors[0].reason) || j.error.status)) || ""; } catch (_) {}
      const e = new Error("HTTP " + r.status + (reason ? " " + reason : ""));
      e.status = r.status; e.reason = reason;
      if (r.status === 429 || /rateLimit/i.test(reason) || r.status >= 500) return e; // tenta de novo
      e.fatal = true;
      if (r.status === 404 || /notFound|forbidden|insufficient/i.test(reason) || (r.status === 403 && !reason)) e.msg = NOT_SHARED;
      else if (/downloadQuotaExceeded/i.test(reason)) e.msg = "O Google bloqueou downloads desse filme por hoje (muita gente baixou o mesmo arquivo em 24 h).";
      else if (/keyInvalid|API_KEY|referer|blocked/i.test(reason)) e.msg = "A chave do Google usada pela sala não funcionou. Confira em Ajustes › Assistir junto no app.";
      else if (/cannotDownloadAbusiveFile|abusive/i.test(reason)) e.msg = "O Google marcou esse arquivo como suspeito e não deixa baixar pela chave do site.";
      else e.msg = "O Drive recusou o filme (" + r.status + (reason ? ", " + reason : "") + ").";
      return e;
    }

    function bufferedAhead() {
      const t = video.currentTime, b = video.buffered;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= t + 0.3 && b.end(i) > t) return b.end(i) - t;
      return 0;
    }
    function isBuffered(t) {
      const b = video.buffered;
      for (let i = 0; i < b.length; i++) if (b.start(i) <= t + 0.1 && b.end(i) > t + 0.5) return true;
      return false;
    }

    function waitUpdate() {
      return new Promise((res) => { if (!sb.updating) return res(); sb.addEventListener("updateend", res, { once: true }); });
    }
    // Uma operação de cada vez no SourceBuffer (um pulo no meio de um append dava InvalidStateError).
    let chain = Promise.resolve();
    function exclusive(fn) {
      const run = chain.then(fn);
      chain = run.catch(() => {});
      return run;
    }
    /** Acrescenta [buf]; devolve false se um pulo tornou este pedaço velho (aí não acrescenta). */
    function append(buf, my) {
      return exclusive(async () => {
        for (let attempt = 0; attempt < 3; attempt++) {
          if (destroyed || (my != null && my !== gen)) return false;
          await waitUpdate();
          if (ms.readyState === "closed") return false;
          try { sb.appendBuffer(buf); await waitUpdate(); return true; }
          catch (e) {
            if (e.name !== "QuotaExceededError") throw e;
            await evictNow(true);
          }
        }
        return false;
      });
    }
    function evict(force) { return exclusive(() => evictNow(force)); }
    async function evictNow(force) {
      const t = video.currentTime, b = video.buffered;
      if (!b.length) return;
      const keepBehind = force ? 5 : 25;
      if (t - b.start(0) > keepBehind + 5) {
        await waitUpdate();
        try { sb.remove(0, Math.max(0, t - keepBehind)); await waitUpdate(); } catch (_) {}
      }
      if (force && b.length && b.end(b.length - 1) - t > 60) {
        await waitUpdate();
        try { sb.remove(t + 60, Infinity); await waitUpdate(); } catch (_) {}
      }
    }

    let kickResolve = null;
    function kick() { if (kickResolve) { kickResolve(); kickResolve = null; } }
    function nap(ms2) { return new Promise((r) => { kickResolve = r; setTimeout(r, ms2); }); }

    /** Carregador: baixa adiantado (até "aheadSeconds" de filme) para aguentar soluços da internet. */
    async function loader(my) {
      let backoff = 0;
      while (!destroyed && my === gen) {
        if (cursor < 0 || src.isEnd(cursor)) { await sleep(2000); continue; }
        let next = -1;
        for (const [p, l] of src.plan(cursor, targetBytes)) { next = rc.missing(p, l); if (next >= 0) break; }
        if (next < 0) { await sleep(1500); continue; }
        try { await rc.block(next); backoff = 0; }
        catch (e) {
          if (e.fatal || destroyed || my !== gen) return;
          backoff = Math.min(30000, backoff ? backoff * 2 : 2000);
          await sleep(backoff);
        }
      }
    }

    async function pump(my) {
      while (!destroyed && my === gen) {
        if (bufferedAhead() > (managed ? 40 : 60) || (managed && !streaming && bufferedAhead() > 8)) { await nap(700); continue; }
        if (src.isEnd(cursor)) {
          if (!ended && ms.readyState === "open") { await exclusive(async () => { await waitUpdate(); if (my === gen && ms.readyState === "open") try { ms.endOfStream(); } catch (_) {} }); ended = true; }
          await nap(1500);
          continue;
        }
        let r;
        try { r = await src.read(cursor); }
        catch (e) {
          if (my !== gen || destroyed) return;
          if (e.fatal) { fail(e.msg || NOT_SHARED, false, { status: e.status, reason: e.reason }); return; }
          failing = true;
          fail("A conexão caiu enquanto carregava o filme. Tentando de novo…", true);
          await sleep(2000);
          continue;
        }
        if (my !== gen || destroyed) return;
        if (failing) { failing = false; recovered(); }
        cursor = r.next;
        let frag = null, until = r.until || 0;
        if (r.trafs) frag = mux.build(r.trafs);
        else if (r.frames && r.frames.length) {
          const media = r.frames.filter((f) => src.ids.has(f.track));
          frag = mux.fragment(media);
          if (subTrack) addCues(r.frames.filter((f) => f.track === subTrack.number));
          until = media.reduce((m, f) => Math.max(m, f.pts), 0) + (mux.shift || 0);
        }
        if (!frag) continue;
        try { if (!(await append(frag, my))) return; }
        catch (e) {
          if (my !== gen || destroyed) return;
          // MP4 que o navegador recusou logo no começo: deixa o <video> normal tocar.
          if (src.kind === "mp4" && !started && opts.onFallback) { opts.onFallback("append " + e.name); return; }
          fail("O navegador recusou um pedaço do filme (" + e.name + ").");
          return;
        }
        started = true;
        ended = false;
        appendedUntil = Math.max(appendedUntil, until);
        if (!managed || !streaming) await evict(false);
      }
    }

    // Legendas embutidas (MKV).
    let textTrack = null;
    const cueSeen = new Set();
    function addCues(frames) {
      if (!textTrack || !frames.length) return;
      for (const f of frames) {
        const key = f.pts.toFixed(3);
        if (cueSeen.has(key)) continue;
        cueSeen.add(key);
        const text = subtitleText(subTrack.codec, f.data);
        if (!text) continue;
        const Cue = root.VTTCue || root.TextTrackCue;
        const sh = (mux && mux.shift) || 0;
        try { textTrack.addCue(new Cue(f.pts + sh, f.pts + sh + (f.dur || 3), text)); } catch (_) {}
      }
    }

    function onSeeking() {
      if (!src || !mux || destroyed) return;
      if (isBuffered(video.currentTime)) return;
      reposition();
    }
    /** Recomeça a baixar a partir do ponto atual (pulo, ou o navegador jogou fora o que tinha). */
    function reposition() {
      const t = video.currentTime;
      gen++;
      appendedUntil = 0;
      cursor = src.cursorFor(t - src.shiftNow());
      mux.reset();
      ended = false;
      const my = gen;
      exclusive(async () => {
        await waitUpdate();
        if (my === gen && ms.readyState === "open") { try { sb.abort(); } catch (_) {} }
      }).then(() => { if (my === gen) { pump(my); loader(my); } });
    }

    /** Fonte MKV (Clusters). */
    async function mkvSource(size) {
      const file = await new MkvFile(fetchRange, size).open();
      file.rc = rc;
      const first = await file.cluster(file.firstCluster);
      const firstFrames = new Map();
      for (const f of first.frames) if (!firstFrames.has(f.track)) firstFrames.set(f.track, f.data);
      const chosen = chooseTracks(file, firstFrames);
      if (!chosen.video) throw Object.assign(new Error("O vídeo deste MKV usa um formato que o navegador não toca (" + (chosen.unsupportedVideo || "desconhecido") + ")."), { user: true });
      const tracks = [chosen.video, chosen.audio].filter(Boolean);
      tracks.forEach((t) => { t.mime = codecString(t); });
      return {
        kind: "mkv", tracks, duration: file.duration, sub: chosen.sub,
        first: file.firstCluster,
        isEnd: (c) => c < 0 || c >= file.segEnd,
        cursorFor: (t) => (t > 1 ? file.clusterFor(t).pos : file.firstCluster),
        plan: (c, target) => [[c, target]],
        read: async (c) => { const cl = await file.cluster(c); return { next: cl.next, frames: cl.frames }; },
        info: { video: codecString(chosen.video), audio: chosen.audio ? chosen.audio.codec : null, skipped: chosen.skippedAudio },
      };
    }

    let fmt = "";
    ms.addEventListener("sourceopen", async () => {
      try {
        const size = await getSize();
        const cap = (opts.cacheMB || 192) * 1024 * 1024;
        rc = new RangeCache(fetchRange, size, cap);
        // Pelos primeiros bytes (o nome/tipo do arquivo pode mentir).
        fmt = looksLikeMkv(await fetchRange(0, 3)) ? "mkv" : "mp4";
        try { src = fmt === "mkv" ? await mkvSource(size) : await mp4Source(fetchRange, size, rc); }
        catch (e) {
          if (e.fatal) throw e;
          if (fmt === "mp4" && opts.onFallback) { opts.onFallback(e.message); return; }
          throw e;
        }
        if (destroyed) return;
        const tracks = src.tracks;
        const mime = () => `video/mp4; codecs="${tracks.map((t) => t.mime).join(",")}"`;
        if (!MS.isTypeSupported(mime()) && tracks.length > 1) {
          if (MS.isTypeSupported(`video/mp4; codecs="${tracks[0].mime}"`)) {
            if (opts.onInfo) opts.onInfo("O áudio deste arquivo (" + String(tracks[1].codec || tracks[1].mime || "?").replace("A_", "") + ") não toca neste aparelho; o filme vai sem som.");
            tracks.splice(1, 1);
          }
        }
        if (!MS.isTypeSupported(mime())) {
          if (src.kind === "mp4" && opts.onFallback) { opts.onFallback("codec " + mime()); return; }
          fail("Este aparelho não toca o formato de vídeo deste arquivo (" + tracks[0].mime + ").");
          return;
        }
        if (tracks.length === 1 && src.info.skipped.length && opts.onInfo) opts.onInfo("O áudio deste arquivo (" + String(src.info.skipped[0]).replace("A_", "") + ") não toca no navegador; o filme vai sem som.");
        tracks.forEach((t, i) => { t.id = i + 1; });
        src.ids = new Set(tracks.map((t) => t.number));
        mux = new Mp4Mux(tracks);
        src.shiftNow = () => (src.kind === "mp4" ? src.shift : (mux.shift || 0));
        const dur = src.duration || 0;
        // Quanto baixar adiantado: "aheadSeconds" de filme, limitado pela memória reservada.
        const bps = dur > 0 ? size / dur : 500 * 1024;
        targetBytes = Math.max(16 * 1024 * 1024, Math.min(cap * 0.8, (opts.aheadSeconds || 600) * bps));
        sb = ms.addSourceBuffer(mime());
        if (dur) { try { ms.duration = dur + (src.kind === "mp4" ? src.shift : 0); } catch (_) {} }
        await append(mux.init());
        if (src.sub && opts.subtitles !== false) {
          subTrack = src.sub;
          textTrack = video.addTextTrack("subtitles", subTrack.name || "Legenda", subTrack.lang || "pt");
          textTrack.mode = "showing";
        }
        video.addEventListener("seeking", onSeeking);
        // Vigia: se o vídeo está parado esperando dados que já "passaram" (o navegador descartou
        // um trecho para economizar memória), volta a baixar dali.
        watchdog = setInterval(() => {
          if (destroyed || video.paused || video.seeking || video.ended || !mux) { stuckSince = 0; return; }
          const end = video.duration || dur;
          // Só é buraco se já entregamos dados além deste ponto (senão é só a internet carregando).
          if (isBuffered(video.currentTime) || appendedUntil < video.currentTime + 1 || (end && video.currentTime > end - 1)) { stuckSince = 0; return; }
          if (!stuckSince) { stuckSince = Date.now(); return; }
          if (Date.now() - stuckSince > 2500) { stuckSince = 0; reposition(); }
        }, 1000);
        // Começa do ponto onde o vídeo já está (a sala pode ter pedido um pulo antes).
        const t0 = video.currentTime;
        cursor = t0 > 1 ? src.cursorFor(t0 - src.shiftNow()) : src.first;
        pump(gen);
        loader(gen);
      } catch (e) {
        if (destroyed) return;
        // Não é bloqueio do Drive e não é MKV: o <video> normal ainda pode dar conta.
        if (!(e && e.fatal) && fmt !== "mkv" && opts.onFallback) { opts.onFallback(e && e.message); return; }
        fail(e && e.fatal ? (e.msg || NOT_SHARED) : e && e.user ? e.message : "Não consegui abrir este filme: " + (e && e.message ? e.message : e), false, e && e.fatal ? { status: e.status, reason: e.reason } : null);
      }
    }, { once: true });

    return {
      destroy() {
        destroyed = true; gen++;
        clearInterval(watchdog);
        video.removeEventListener("seeking", onSeeking);
        if (textTrack) { try { textTrack.mode = "disabled"; } catch (_) {} }
        if (objUrl) URL.revokeObjectURL(objUrl);
        if (rc) { rc.blocks.clear(); rc.bytes = 0; }
      },
      /** Quanto já está baixado à frente (segundos aproximados), para mostrar/testar. */
      get aheadSeconds() {
        if (!rc || !src || src.isEnd(cursor) || !src.duration) return 0;
        let bytes = 0;
        for (const [p, l] of src.plan(cursor, targetBytes)) {
          const first = Math.floor(p / rc.B), last = Math.floor((Math.min(rc.size, p + l) - 1) / rc.B);
          let ok = true;
          for (let i = first; i <= last; i++) if (!rc.blocks.has(i)) { ok = false; break; }
          if (!ok) break;
          bytes += Math.max(0, Math.min(l, rc.size - p));
        }
        const left = Math.max(0, src.duration - video.currentTime);
        return Math.min(left, Math.round(bytes / (rc.size / src.duration)) + Math.round(bufferedAhead()));
      },
      get kind() { return src ? src.kind : null; },
    };
  }
  const MkvPlayer = StreamPlayer;

  const NOT_SHARED = "O Drive não liberou o filme (ele precisa estar compartilhado como “qualquer pessoa com o link”).";

  function sleep(ms2) { return new Promise((r) => setTimeout(r, ms2)); }

  /** É MKV/WebM? (pelos primeiros bytes) */
  function looksLikeMkv(b) { return b && b.length >= 4 && b[0] === 0x1a && b[1] === 0x45 && b[2] === 0xdf && b[3] === 0xa3; }

  const api = { MkvFile, Mp4File, mp4Source, mp4Codec, RangeCache, Mp4Mux, MkvPlayer, StreamPlayer, prepareTrack, chooseTracks, codecString, looksLikeMkv, subtitleText, readHeader };
  root.EstanteMkv = api;
  if (typeof module !== "undefined" && module.exports) module.exports = api;
})(typeof window !== "undefined" ? window : globalThis);
