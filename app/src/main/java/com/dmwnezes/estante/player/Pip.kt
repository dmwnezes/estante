package com.dmwnezes.estante.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Janelinha flutuante (picture-in-picture). O player avisa quando está tocando; ao sair
 * do app (botão início), a Activity entra na janelinha.
 */
object Pip {
    /** Player aberto e tocando: pode virar janelinha. */
    @Volatile var playing = false
    var aspect = Rational(16, 9)

    /** A tela está na janelinha agora. */
    var inPip by mutableStateOf(false)

    fun params(): PictureInPictureParams {
        val safe = aspect.toFloat().coerceIn(0.42f, 2.38f).let { if (it == aspect.toFloat()) aspect else Rational(16, 9) }
        val b = PictureInPictureParams.Builder().setAspectRatio(safe)
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(playing).setSeamlessResizeEnabled(true)
        return b.build()
    }

    fun update(activity: Activity?) {
        activity ?: return
        runCatching { activity.setPictureInPictureParams(params()) }
    }

    fun enter(activity: Activity?): Boolean {
        activity ?: return false
        return runCatching { activity.enterPictureInPictureMode(params()) }.getOrDefault(false)
    }
}
