package com.dmwnezes.estante.party

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Fotos do chat da sala. Vão dentro da própria mensagem no Firebase, como texto
 * ("data:image/jpeg;base64,…"), já reduzidas: assim não precisa do Storage (que é pago)
 * e o site do iPhone mostra sem nada a mais.
 */
object ChatImages {

    /** Lado maior da foto enviada. */
    const val MAX_SIDE = 1024
    /** Tamanho máximo de cada foto (em bytes, antes do base64). */
    const val MAX_BYTES = 180_000

    private val prefix = Regex("""^data:image/(jpeg|png|webp);base64,""")

    /** Só aceita imagens de verdade (nada de outro tipo de link escondido na mensagem). */
    fun isValid(data: String?): Boolean = data != null && prefix.containsMatchIn(data.take(40))

    /** Escolhe a qualidade: começa em 82% e vai baixando até caber em [MAX_BYTES]. */
    fun jpeg(bmp: Bitmap): ByteArray {
        var q = 82
        var out: ByteArray
        do {
            val bos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, q, bos)
            out = bos.toByteArray()
            q -= 10
        } while (out.size > MAX_BYTES && q >= 32)
        return out
    }

    /** Reduz a foto escolhida e devolve pronta para mandar (ou nulo se não deu para abrir). */
    suspend fun prepare(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val bmp = com.dmwnezes.estante.data.ImageLoad.decode(context, uri, MAX_SIDE) ?: return@runCatching null
            // Fundo branco para PNG com transparência (JPEG não tem transparência).
            val flat = if (bmp.hasAlpha()) {
                Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also {
                    val c = android.graphics.Canvas(it); c.drawColor(android.graphics.Color.WHITE); c.drawBitmap(bmp, 0f, 0f, null)
                }
            } else bmp
            "data:image/jpeg;base64," + Base64.encodeToString(jpeg(flat), Base64.NO_WRAP)
        }.getOrNull()
    }

    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Texto da mensagem → imagem (guardada em memória para não decodificar de novo ao rolar). */
    suspend fun decode(key: String, data: String): ImageBitmap? {
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching {
                val bytes = Base64.decode(data.substringAfter(","), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()?.also { cache.put(key, it) }
        }
    }
}

/** Foto dentro do balão do chat; toque abre em tela cheia. */
@Composable
fun ChatPhoto(key: String, data: String, onOpen: (ImageBitmap) -> Unit) {
    val img by produceState<ImageBitmap?>(null, key) { value = ChatImages.decode(key, data) }
    val ratio = img?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 2f) } ?: 1.33f
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).heightIn(max = 320.dp).clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(enabled = img != null) { img?.let(onOpen) },
        contentAlignment = Alignment.Center,
    ) {
        val i = img
        if (i == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.6f))
        else Image(i, "Foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Foto em tela cheia: pinça para ampliar, dois toques volta ao normal, X ou voltar fecha. */
@Composable
fun PhotoViewer(img: ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, p, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        pan = if (scale == 1f) Offset.Zero else pan + p
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; pan = Offset.Zero }, onTap = { if (scale == 1f) onClose() })
                },
        ) {
            Image(
                img, "Foto", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y },
            )
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Rounded.Close, "Fechar", tint = Color.White)
            }
        }
    }
}
