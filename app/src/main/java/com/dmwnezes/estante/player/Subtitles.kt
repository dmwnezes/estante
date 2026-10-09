package com.dmwnezes.estante.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap

/** Legenda pronta para o player: arquivo local em UTF-8. */
data class ReadySubtitle(val file: File, val isVtt: Boolean)

/**
 * Prepara a legenda de cada vídeo: a escolhida pelo usuário ou um .srt/.vtt com o mesmo
 * nome na pasta do Drive. Sempre grava uma cópia em UTF-8 (legendas brasileiras costumam
 * vir em Windows-1252 e apareceriam com acentos quebrados).
 */
object Subtitles {

    /** Vídeos do Drive já procurados sem achar legenda (nesta sessão). */
    private val notFound = ConcurrentHashMap.newKeySet<String>()

    /** Bytes → texto: UTF-8 se for válido; senão Windows-1252. */
    fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try {
            utf8.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return text.removePrefix("﻿")
    }

    /** SRT → WebVTT (o Chromecast só entende WebVTT). */
    fun srtToVtt(srt: String): String =
        "WEBVTT\n\n" + srt.replace("\r\n", "\n").replace(Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})"""), "$1.$2")

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** Baixa/lê a legenda do vídeo e devolve o arquivo pronto (ou nulo se não houver). */
    suspend fun prepare(context: Context, v: Video): ReadySubtitle? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "legendas").apply { mkdirs() }
        var ref = v.subtitle
        if (ref == null && v.source == Source.DRIVE && v.ref !in notFound) {
            val found = withTimeoutOrNull(6_000) { runCatching { AppGraph.drive.findSubtitle(v.ref) }.getOrNull() }
            if (found != null) {
                ref = "drive:${found.id}"
                AppGraph.library.update(v.id) { it.copy(subtitle = ref) }
            } else notFound += v.ref
        }
        ref ?: return@withContext null
        runCatching {
            val (bytes, name) = when {
                ref.startsWith("drive:") -> {
                    val id = ref.removePrefix("drive:")
                    val req = Request.Builder().url(DriveClient.streamUrl(id)).build()
                    val b = AppGraph.driveHttp.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null } ?: return@runCatching null
                    b to (runCatching { AppGraph.drive.file(id).name }.getOrNull() ?: "legenda.srt")
                }
                else -> {
                    val uri = Uri.parse(ref)
                    val b = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
                    b to (displayName(context, uri) ?: "legenda.srt")
                }
            }
            val text = decode(bytes)
            val vtt = name.lowercase().endsWith(".vtt") || text.trimStart().startsWith("WEBVTT")
            val out = File(dir, "${v.id}.${if (vtt) "vtt" else "srt"}")
            out.writeText(text, Charsets.UTF_8)
            ReadySubtitle(out, vtt)
        }.getOrNull()
    }
}
