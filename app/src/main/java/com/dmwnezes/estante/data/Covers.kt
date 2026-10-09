package com.dmwnezes.estante.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID

/**
 * Capas dos DVDs: imagens guardadas na pasta do app, sempre com um nome novo
 * (assim a tela nunca mostra a capa antiga em cache depois de trocar).
 */
class Covers(context: Context, private val http: OkHttpClient) {

    private val app = context.applicationContext
    private val dir = File(app.filesDir, "capas").apply { mkdirs() }

    private fun newFile() = File(dir, "${UUID.randomUUID()}.jpg")

    /** Reduz para no máximo [MAX] px de altura e grava em JPEG. */
    private fun save(bmp: Bitmap): String {
        val scaled = if (bmp.height > MAX) {
            val w = (bmp.width * MAX.toFloat() / bmp.height).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, MAX, true)
        } else bmp
        val f = newFile()
        f.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return f.absolutePath
    }

    /** Imagem escolhida na galeria. */
    suspend fun fromImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        ImageLoad.decode(app, uri, MAX)?.let { runCatching { save(it) }.getOrNull() }
    }

    /** Miniatura que o Google Drive gera para o vídeo (pedida em tamanho maior). */
    suspend fun fromDriveThumbnail(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val big = if (Regex("=s\\d+$").containsMatchIn(link)) link.replace(Regex("=s\\d+$"), "=s1000") else link
        return fromUrl(big)
    }

    /** Baixa uma imagem (pôster do TMDB, miniatura do Drive) e guarda como capa. */
    suspend fun fromUrl(url: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank()) return@withContext null
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val bytes = resp.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::save)
            }
        }.getOrNull()
    }

    /** Um quadro tirado de 10% do vídeo guardado no celular. */
    suspend fun fromLocalFrame(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val frame = r.getFrameAtTime(dur * 100L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let(::save)
            } finally {
                r.release()
            }
        }.getOrNull()
    }

    /** Duração do vídeo guardado no celular, em ms (0 se não der para ler). */
    suspend fun localDuration(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                r.release()
            }
        }.getOrDefault(0L)
    }

    companion object {
        const val MAX = 900
    }
}
