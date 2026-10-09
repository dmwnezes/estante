package com.dmwnezes.estante.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface

/**
 * Abre uma imagem do celular já reduzida para caber em [maxSide] px (lado maior).
 * Android 9+: ImageDecoder (gira a foto sozinho). Android 8: BitmapFactory + rotação do EXIF.
 */
object ImageLoad {
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { d, info, _ ->
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = maxSide.toFloat() / maxOf(w, h)
                if (scale < 1f) d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        } else {
            val cr = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
                ?: return@runCatching null
            val rotation = runCatching {
                cr.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            }.getOrDefault(0)
            val scale = (maxSide.toFloat() / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
            if (rotation == 0 && scale >= 1f) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postScale(scale, scale); postRotate(rotation.toFloat()) }, true)
        }
    }.getOrNull()
}
