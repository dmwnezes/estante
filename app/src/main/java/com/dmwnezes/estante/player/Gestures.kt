package com.dmwnezes.estante.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.rounded.ZoomInMap
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private data class Hud(val icon: ImageVector, val text: String, val align: Alignment, val level: Float? = null)

/**
 * Gestos do player (quando os controles estão escondidos):
 * - deslizar para cima/baixo na metade esquerda = brilho; na direita = volume;
 * - toque duplo na esquerda/direita = volta/avança 10 s;
 * - toque simples = mostra os controles.
 */
@Composable
fun GestureLayer(
    enabled: Boolean,
    activity: Activity?,
    onTap: () -> Unit,
    onSeek: (forward: Boolean) -> Unit,
    fill: Boolean = false,
    onFill: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var hud by remember { mutableStateOf<Hud?>(null) }
    var hudAt by remember { mutableLongStateOf(0L) }
    var seekCount by remember { mutableStateOf(0) }

    fun show(h: Hud) { hud = h; hudAt = System.currentTimeMillis() }

    LaunchedEffect(hudAt) {
        if (hud == null) return@LaunchedEffect
        delay(800)
        hud = null
        seekCount = 0
    }

    // Brilho volta ao do sistema quando sai do player.
    DisposableEffect(activity) {
        onDispose {
            activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
        }
    }

    fun currentBrightness(): Float {
        val b = activity?.window?.attributes?.screenBrightness ?: -1f
        if (b >= 0f) return b
        val sys = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (sys / 255f).coerceIn(0.01f, 1f)
    }

    Box(
        Modifier.fillMaxSize().then(
            if (!enabled) Modifier else Modifier
                .pointerInput(fill) {
                    // Um dedo deslizando na vertical = brilho/volume; dois dedos em pinça = preencher a tela.
                    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val left = down.position.x < size.width / 2
                        var level = if (left) currentBrightness() else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVol
                        var mode = 0 // 0 = indefinido, 1 = arrastando, 2 = pinça
                        var startSpan = 0f
                        var done = false
                        while (true) {
                            // Fase "Initial": decide antes do detector de toques, que então ignora pinça e arrasto.
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val span = (pressed[0].position - pressed[1].position).getDistance()
                                if (mode != 2) { mode = 2; startSpan = span }
                                ev.changes.forEach { it.consume() }
                                if (!done && startSpan > 0) {
                                    val z = span / startSpan
                                    if (z > 1.18f && !fill) { onFill(true); done = true; show(Hud(Icons.Rounded.ZoomOutMap, "Preenchendo a tela", Alignment.Center)) }
                                    if (z < 0.85f && fill) { onFill(false); done = true; show(Hud(Icons.Rounded.ZoomInMap, "Vídeo inteiro", Alignment.Center)) }
                                }
                                continue
                            }
                            if (mode == 2) continue
                            val c = pressed[0]
                            val dy = c.position.y - c.previousPosition.y
                            if (mode == 0) {
                                val moved = c.position - down.position
                                if (kotlin.math.abs(moved.y) > viewConfiguration.touchSlop && kotlin.math.abs(moved.y) > kotlin.math.abs(moved.x)) mode = 1
                                else continue
                            }
                            c.consume()
                            level = (level - dy / (size.height * 0.75f)).coerceIn(0f, 1f)
                            if (left) {
                                activity?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = level.coerceAtLeast(0.01f) } }
                                show(Hud(Icons.Rounded.LightMode, "${(level * 100).toInt()}%", Alignment.Center, level))
                            } else {
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * maxVol).toInt(), 0)
                                show(Hud(Icons.AutoMirrored.Rounded.VolumeUp, "${(level * 100).toInt()}%", Alignment.Center, level))
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { off ->
                            val forward = off.x > size.width / 2
                            onSeek(forward)
                            seekCount = if (hud?.icon == (if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind)) seekCount + 1 else 1
                            show(
                                Hud(
                                    if (forward) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                                    "${if (forward) "+" else "−"}${seekCount * 10} s",
                                    if (forward) Alignment.CenterEnd else Alignment.CenterStart,
                                )
                            )
                        },
                    )
                }
        ),
    ) {
        AnimatedVisibility(hud != null, Modifier.align(hud?.align ?: Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            val h = hud
            if (h != null) {
                Box(
                    Modifier.padding(horizontal = 60.dp).clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(h.icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        if (h.level != null) {
                            Box(Modifier.width(120.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                                Box(Modifier.fillMaxWidth(h.level).height(6.dp).background(Color(0xFF9B8CFF)))
                            }
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(h.text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
