package com.dmwnezes.estante.player

import android.content.Context
import android.net.Uri
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Servidorzinho HTTP dentro do celular, só na rede Wi-Fi de casa. A TV (Chromecast) pede o
 * vídeo a ele, e ele busca no Google Drive com o login do app (a TV não tem como entrar
 * na sua conta) ou lê o arquivo do celular. Tem um código secreto no endereço, então
 * ninguém mais na rede consegue pedir vídeos.
 */
object CastProxy {

    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private lateinit var app: Context

    /** Liga o servidor (uma vez) e devolve a porta. */
    @Synchronized
    fun start(context: Context): Int {
        app = context.applicationContext
        server?.let { if (!it.isClosed) return it.localPort }
        val s = ServerSocket(0)
        server = s
        pool.execute {
            while (!s.isClosed) {
                val socket = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { handle(socket) }; runCatching { socket.close() } }
            }
        }
        return s.localPort
    }

    /** IP do celular na rede local (Wi-Fi). */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedByDescending { it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    fun videoUrl(context: Context, v: Video): String? {
        val ip = localIp() ?: return null
        return "http://$ip:${start(context)}/$secret/v/${v.id}"
    }

    fun subtitleUrl(context: Context, v: Video): String? {
        val ip = localIp() ?: return null
        return "http://$ip:${start(context)}/$secret/s/${v.id}.vtt"
    }

    /** Tipo do vídeo pelo nome do arquivo (o Chromecast precisa saber). */
    fun mimeFor(v: Video): String = when (v.fileName?.substringAfterLast('.', "")?.lowercase()) {
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "m4v", "mp4" -> "video/mp4"
        "avi" -> "video/x-msvideo"
        else -> "video/mp4"
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = input.readLine() ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(" ")
        val method = parts.getOrNull(0) ?: return
        val path = parts.getOrNull(1)?.split("/")?.filter { it.isNotEmpty() } ?: return
        val out = socket.getOutputStream()
        if (method == "OPTIONS") { respond(out, 204, "No Content", emptyMap()); return }
        if (path.size < 3 || path[0] != secret) { respond(out, 404, "Not Found", emptyMap()); return }
        val video = AppGraph.library.current.video(path[2].removeSuffix(".vtt")) ?: run { respond(out, 404, "Not Found", emptyMap()); return }
        val head = method == "HEAD"
        when (path[1]) {
            "v" -> serveVideo(out, video, headers["range"], head)
            "s" -> serveSubtitle(out, video, head)
            else -> respond(out, 404, "Not Found", emptyMap())
        }
    }

    private val cors = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Headers" to "Content-Type, Range",
        "Access-Control-Expose-Headers" to "Content-Length, Content-Range",
    )

    private fun respond(out: OutputStream, code: Int, msg: String, headers: Map<String, String>, body: InputStream? = null, length: Long = -1) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\n")
        (cors + headers).forEach { (k, v) -> sb.append("$k: $v\r\n") }
        if (length >= 0) sb.append("Content-Length: $length\r\n") else if (body == null) sb.append("Content-Length: 0\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        body?.use { it.copyTo(out, 64 * 1024) }
        out.flush()
    }

    private fun serveVideo(out: OutputStream, v: Video, range: String?, head: Boolean) {
        when (v.source) {
            Source.DRIVE -> {
                // Repassa o pedido ao Drive (com o login) e devolve a resposta como veio.
                val req = Request.Builder().url(DriveClient.streamUrl(v.ref)).apply { range?.let { header("Range", it) } }.build()
                val resp = AppGraph.driveHttp.newBuilder().readTimeout(java.time.Duration.ofMinutes(5)).build().newCall(req).execute()
                resp.use {
                    val h = mutableMapOf("Content-Type" to mimeFor(v), "Accept-Ranges" to "bytes")
                    it.header("Content-Range")?.let { cr -> h["Content-Range"] = cr }
                    val len = it.body?.contentLength() ?: -1
                    respond(out, it.code, if (it.code == 206) "Partial Content" else "OK", h, if (head) null else it.body?.byteStream(), len)
                }
            }
            Source.LOCAL -> {
                val pfd = app.contentResolver.openFileDescriptor(Uri.parse(v.ref), "r") ?: run { respond(out, 404, "Not Found", emptyMap()); return }
                pfd.use {
                    val total = it.statSize
                    val stream = FileInputStream(it.fileDescriptor)
                    val (start, end) = parseRange(range, total)
                    val len = end - start + 1
                    stream.channel.position(start)
                    val h = mutableMapOf("Content-Type" to mimeFor(v), "Accept-Ranges" to "bytes")
                    val partial = range != null
                    if (partial) h["Content-Range"] = "bytes $start-$end/$total"
                    respond(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", h, if (head) null else LimitedStream(stream, len), len)
                }
            }
        }
    }

    private fun serveSubtitle(out: OutputStream, v: Video, head: Boolean) {
        val file = File(app.cacheDir, "legendas").listFiles()?.firstOrNull { it.nameWithoutExtension == v.id }
            ?: run { respond(out, 404, "Not Found", emptyMap()); return }
        val text = file.readText().let { if (file.extension == "srt") Subtitles.srtToVtt(it) else it }
        val bytes = text.toByteArray(Charsets.UTF_8)
        respond(out, 200, "OK", mapOf("Content-Type" to "text/vtt; charset=utf-8"), if (head) null else bytes.inputStream(), bytes.size.toLong())
    }

    /** "bytes=100-" → (100, total-1). */
    fun parseRange(range: String?, total: Long): Pair<Long, Long> {
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) } ?: return 0L to total - 1
        val a = m.groupValues[1].toLongOrNull()
        val b = m.groupValues[2].toLongOrNull()
        return when {
            a != null -> a.coerceIn(0, total - 1) to (b ?: (total - 1)).coerceIn(0, total - 1)
            b != null -> (total - b).coerceAtLeast(0) to total - 1
            else -> 0L to total - 1
        }
    }

    private class LimitedStream(private val s: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else s.read().also { if (it >= 0) left-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = s.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
        override fun close() = s.close()
    }
}
