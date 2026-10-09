package com.dmwnezes.estante.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveClient
import com.dmwnezes.estante.ui.PillButton
import kotlinx.coroutines.delay

/** Endereço que o player abre para cada DVD. */
fun playUri(v: Video): Uri = when (v.source) {
    Source.DRIVE -> Uri.parse(DriveClient.streamUrl(v.ref))
    Source.LOCAL -> Uri.parse(v.ref)
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Player em tela cheia (deitado). Toca a fila em sequência, começa cada vídeo de onde
 * parou e salva a posição a cada poucos segundos, ao pausar, ao trocar de vídeo e ao sair.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(queue: List<Video>, startIndex: Int, fromStart: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val library = AppGraph.library

    var title by remember { mutableStateOf(queue.getOrNull(startIndex)?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf("") }
    var controlsVisible by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    val player = remember {
        val http = OkHttpDataSource.Factory(AppGraph.driveHttp).setUserAgent("Estante-app")
        val source = DefaultDataSource.Factory(context, http)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                val items = queue.map { v ->
                    MediaItem.Builder()
                        .setUri(playUri(v))
                        .setMediaId(v.id)
                        .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
                        .build()
                }
                val first = queue.getOrNull(startIndex)
                val startMs = if (first == null || fromStart) 0L else Resume.startAt(library.current.video(first.id) ?: first)
                setMediaItems(items, startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0)), startMs)
                prepare()
                playWhenReady = true
            }
    }

    /** Guarda onde parou o vídeo que está tocando agora. */
    fun saveCurrent() {
        val id = player.currentMediaItem?.mediaId ?: return
        val dur = player.duration.takeIf { it > 0 } ?: 0L
        library.savePosition(id, player.currentPosition, dur)
    }

    fun updateTitles() {
        val i = player.currentMediaItemIndex
        title = queue.getOrNull(i)?.title.orEmpty()
        subtitle = if (queue.size > 1) "${i + 1} de ${queue.size}" else ""
    }

    // Tela deitada, sem barras do sistema e sempre acesa enquanto o player estiver aberto.
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val window = activity?.window
        val oldOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = window?.let { WindowCompat.getInsetsController(it, view) }
        insets?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            insets?.show(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = oldOrientation
        }
    }

    DisposableEffect(player) {
        var lastId: String? = player.currentMediaItem?.mediaId
        var lastPos = 0L
        var lastDur = 0L
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Fecha a conta do vídeo anterior (terminou ou o usuário pulou).
                lastId?.let { prev ->
                    if (prev != mediaItem?.mediaId) {
                        val endPos = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) lastDur else lastPos
                        library.savePosition(prev, endPos, lastDur)
                    }
                }
                lastId = mediaItem?.mediaId
                updateTitles()
                error = null
                // Ao passar para o próximo da lista, continua de onde tinha parado nele.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val v = mediaItem?.mediaId?.let { library.current.video(it) }
                    val at = v?.let(Resume::startAt) ?: 0L
                    if (at > 0 && player.currentPosition < 1000) player.seekTo(at)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveCurrent()
            }

            override fun onEvents(p: Player, events: Player.Events) {
                lastPos = p.currentPosition
                if (p.duration > 0) lastDur = p.duration
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    saveCurrent()
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Sem conexão com o Google Drive. Confira a internet."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "O Drive recusou o vídeo. Ele pode ter sido apagado ou movido, ou a conta precisa ser conectada de novo."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
                        "Não encontrei o arquivo no celular. Ele pode ter sido apagado ou movido."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        "Este formato de vídeo não é suportado pelo celular (os mais seguros são MP4 e MKV)."
                    else -> "Não consegui tocar este vídeo (${e.errorCodeName})."
                }
            }
        }
        player.addListener(listener)
        updateTitles()
        onDispose {
            saveCurrent()
            player.removeListener(listener)
            player.release()
        }
    }

    // Salva a cada 5 s enquanto toca (se o celular desligar de repente, perde no máximo isso).
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) saveCurrent()
        }
    }

    // Saiu do app: pausa e salva.
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) { player.pause(); saveCurrent() }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    BackHandler { onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = player
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(queue.size > 1)
                    setShowPreviousButton(queue.size > 1)
                    setShowSubtitleButton(true)
                    setKeepContentOnPlayerReset(true)
                    controllerShowTimeoutMs = 3500
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                        controlsVisible = vis == android.view.View.VISIBLE
                    })
                }
            },
        )

        // Título e voltar, junto com os controles.
        AnimatedVisibility(controlsVisible || error != null, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar à estante", tint = Color.White) }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(40.dp).background(Color.Black.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(msg, color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Tentar de novo", null, { error = null; player.prepare(); player.play() })
                    if (player.hasNextMediaItem()) PillButton("Próximo", null, { error = null; player.seekToNextMediaItem(); player.prepare(); player.play() }, filled = false)
                }
            }
        }
    }
}
