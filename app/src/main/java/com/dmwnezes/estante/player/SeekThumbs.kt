package com.dmwnezes.estante.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Miniaturas da barra de tempo: [COUNT] quadros espalhados pelo vídeo, gerados em segundo plano
 * enquanto ele toca e guardados no celular (na próxima vez já estão prontos).
 * A ordem de geração vai "preenchendo" o vídeo: começo, meio, quartos, oitavos…
 * assim a prévia fica útil logo nos primeiros segundos.
 */
class SeekThumbs(private val context: Context, val video: Video) {

    companion object {
        const val COUNT = 48
        const val WIDTH = 240

        /** 0, 24, 12, 36, 6, 18, 30, 42, … (cada passo divide os buracos ao meio). */
        fun order(n: Int): List<Int> {
            val out = LinkedHashSet<Int>()
            var step = n
            while (step >= 1) {
                var i = 0
                while (i < n) { out += i; i += step }
                step /= 2
            }
            return out.toList()
        }

        /** Quadro mais próximo já pronto para a posição [fraction] (0–1). */
        fun nearest(ready: Set<Int>, fraction: Float, n: Int = COUNT): Int? {
            if (ready.isEmpty()) return null
            val want = (fraction.coerceIn(0f, 1f) * (n - 1)).toInt()
            return ready.minByOrNull { kotlin.math.abs(it - want) }
        }
    }

    private val dir = File(context.cacheDir, "miniaturas/${video.ref.hashCode().toUInt().toString(16)}").apply { mkdirs() }
    private val frames = ConcurrentHashMap<Int, ImageBitmap>()

    /** Muda a cada quadro novo (para a tela atualizar). */
    var version by mutableIntStateOf(0)
        private set

    fun frame(fraction: Float): ImageBitmap? = nearest(frames.keys, fraction)?.let { frames[it] }

    private fun file(i: Int) = File(dir, "$i.jpg")

    private fun canDownload(): Boolean {
        if (video.source == Source.LOCAL) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return !cm.isActiveNetworkMetered || AppGraph.prefs.getBoolean("thumbsMobile", false)
    }

    /** Carrega as que já existem e gera as que faltam. Rodar fora da thread principal. */
    suspend fun run(durationMs: Long) {
        if (durationMs <= 0) return
        for (i in 0 until COUNT) {
            val f = file(i)
            if (f.exists()) BitmapFactory.decodeFile(f.path)?.let { frames[i] = it.asImageBitmap() }
        }
        version++
        if (frames.size >= COUNT || !canDownload()) return
        delay(6_000) // deixa o vídeo carregar primeiro
        val r = MediaMetadataRetriever()
        try {
            when (video.source) {
                Source.LOCAL -> r.setDataSource(context, Uri.parse(video.ref))
                Source.DRIVE -> {
                    val token = AppGraph.auth.tokenBlocking() ?: return
                    r.setDataSource(DriveClient.streamUrl(video.ref), mapOf("Authorization" to "Bearer $token"))
                }
            }
            for (i in order(COUNT)) {
                coroutineContext.ensureActive()
                if (frames.containsKey(i)) continue
                val atUs = durationMs * 1000L * i / (COUNT - 1).coerceAtLeast(1)
                val bmp: Bitmap = (
                    if (Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(atUs.coerceAtLeast(1), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, WIDTH, WIDTH)
                    else r.getFrameAtTime(atUs.coerceAtLeast(1), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        Bitmap.createScaledBitmap(b, WIDTH, (WIDTH * b.height / b.width.coerceAtLeast(1)).coerceAtLeast(1), true)
                    }
                    ) ?: continue
                runCatching { file(i).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) } }
                frames[i] = bmp.asImageBitmap()
                version++
            }
        } catch (_: Exception) {
            // Sem miniaturas: a barra continua mostrando só o tempo.
        } finally {
            runCatching { r.release() }
        }
    }
}
