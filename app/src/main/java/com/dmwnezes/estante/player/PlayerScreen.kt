package com.dmwnezes.estante.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Rational
import android.view.ContextThemeWrapper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.mediarouter.app.MediaRouteButton
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveClient
import com.dmwnezes.estante.ui.PillButton
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Endereço que o player abre para cada DVD. */
fun playUri(v: Video): Uri = when (v.source) {
    Source.DRIVE -> Uri.parse(DriveClient.streamUrl(v.ref))
    Source.LOCAL -> Uri.parse(v.ref)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Item do player no celular, com legenda se houver. */
@OptIn(UnstableApi::class)
private fun localItem(v: Video, sub: ReadySubtitle?): MediaItem = MediaItem.Builder()
    .setUri(playUri(v))
    .setMediaId(v.id)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
    .apply {
        if (sub != null) setSubtitleConfigurations(
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(sub.file))
                    .setMimeType(if (sub.isVtt) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("pt").setLabel("Legenda")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )
        )
    }
    .build()

/** Item para a TV: o endereço do servidorzinho do celular. */
private fun castItem(context: Context, v: Video): MediaItem? {
    val url = CastProxy.videoUrl(context, v) ?: return null
    return MediaItem.Builder()
        .setUri(url)
        .setMediaId(v.id)
        .setMimeType(CastProxy.mimeFor(v))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
        .build()
}

/**
 * Player em tela cheia (deitado). Toca a fila em sequência, começa cada vídeo de onde
 * parou e salva a posição a cada poucos segundos, ao pausar, ao trocar de vídeo e ao sair.
 * Tem legenda (.srt/.vtt), gestos, janelinha flutuante e envio para a TV (Chromecast).
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(queue: List<Video>, startIndex: Int, fromStart: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val library = AppGraph.library
    val start = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))

    var title by remember { mutableStateOf(queue.getOrNull(start)?.title.orEmpty()) }
    var subtitle by remember { mutableStateOf("") }
    var controlsVisible by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var preparing by remember { mutableStateOf(true) }
    var casting by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var fill by remember { mutableStateOf(AppGraph.prefs.getBoolean("fillScreen", false)) }
    var currentId by remember { mutableStateOf(queue.getOrNull(start)?.id) }
    /** Arrastando a barra de tempo: fração (0–1), posição em ms e x na tela. */
    var scrub by remember { mutableStateOf<Triple<Float, Long, Float>?>(null) }
    val inPip = Pip.inPip

    val exo = remember {
        val http = OkHttpDataSource.Factory(AppGraph.driveHttp).setUserAgent("Estante-app")
        val source = DefaultDataSource.Factory(context, http)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
            .apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredTextLanguage("pt").build()
            }
    }
    val castContext = remember { runCatching { CastContext.getSharedInstance(context) }.getOrNull() }
    val castPlayer = remember { castContext?.let { runCatching { CastPlayer(it) }.getOrNull() } }
    val session = remember { runCatching { MediaSession.Builder(context, exo).setId("estante-${System.nanoTime()}").build() }.getOrNull() }

    /** O player que está valendo agora (celular ou TV). */
    var active: Player by remember { mutableStateOf(exo) }

    fun saveCurrent() {
        val p = active
        val id = p.currentMediaItem?.mediaId ?: return
        library.savePosition(id, p.currentPosition, p.duration.takeIf { it > 0 } ?: 0L)
    }

    fun updateTitles() {
        val i = active.currentMediaItemIndex
        title = queue.getOrNull(i)?.title.orEmpty()
        subtitle = listOfNotNull(
            queue.getOrNull(i)?.episodeLabel?.ifBlank { null },
            if (queue.size > 1) "${i + 1} de ${queue.size}" else null,
            if (casting) "na TV" else null,
        ).joinToString(" · ")
    }

    // Legendas: a do primeiro vídeo antes de começar; as outras em segundo plano.
    LaunchedEffect(Unit) {
        val first = queue.getOrNull(start)
        val firstSub = first?.let { Subtitles.prepare(context, it) }
        val items = queue.mapIndexed { i, v -> localItem(v, if (i == start) firstSub else null) }
        val startMs = if (first == null || fromStart) 0L else Resume.resumeFrom(library.current.video(first.id) ?: first)
        exo.setMediaItems(items, start, startMs)
        first?.let { exo.setPlaybackSpeed(library.speedFor(it.id)) }
        exo.prepare()
        exo.playWhenReady = true
        preparing = false
        coroutineScope {
            queue.mapIndexed { i, v ->
                async {
                    if (i == start) return@async
                    val sub = Subtitles.prepare(context, v) ?: return@async
                    // Troca o item na fila sem atrapalhar o que está tocando.
                    if (exo.currentMediaItemIndex != i && i < exo.mediaItemCount) exo.replaceMediaItem(i, localItem(v, sub))
                }
            }.awaitAll()
        }
    }

    fun switchToCast() {
        val cp = castPlayer ?: return
        val items = queue.map { castItem(context, it) }
        if (items.any { it == null }) {
            Toast.makeText(context, "Conecte o celular no mesmo Wi-Fi da TV.", Toast.LENGTH_LONG).show()
            return
        }
        val i = exo.currentMediaItemIndex
        val pos = exo.currentPosition
        cp.setMediaItems(items.filterNotNull(), i, pos)
        cp.prepare()
        cp.playWhenReady = true
        exo.pause()
        active = cp
        casting = true
        playerView?.player = cp
        runCatching { session?.player = cp }
        updateTitles()
    }

    fun switchToPhone() {
        val cp = castPlayer ?: return
        val i = cp.currentMediaItemIndex.coerceIn(0, (exo.mediaItemCount - 1).coerceAtLeast(0))
        val pos = cp.currentPosition
        saveCurrent()
        exo.seekTo(i, pos)
        active = exo
        casting = false
        playerView?.player = exo
        runCatching { session?.player = exo }
        updateTitles()
    }

    // Tela deitada, sem barras do sistema e sempre acesa enquanto o player estiver aberto.
    DisposableEffect(Unit) {
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
            Pip.playing = false
            Pip.update(activity)
        }
    }

    DisposableEffect(exo) {
        var lastId: String? = null
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
                mediaItem?.mediaId?.let { id -> active.setPlaybackSpeed(library.speedFor(id)) }
                currentId = mediaItem?.mediaId
                updateTitles()
                error = null
                // Ao passar para o próximo da lista, continua de onde tinha parado nele.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                    val v = mediaItem?.mediaId?.let { library.current.video(it) }
                    val at = v?.let(Resume::resumeFrom) ?: 0L
                    if (at > 0 && active.currentPosition < 1000) active.seekTo(at)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) saveCurrent()
                Pip.playing = isPlaying && active === exo
                Pip.update(activity)
            }

            override fun onEvents(p: Player, events: Player.Events) {
                if (p !== active) return
                lastPos = p.currentPosition
                if (p.duration > 0) lastDur = p.duration
                if (lastId == null) lastId = p.currentMediaItem?.mediaId
            }

            override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
                // Velocidade escolhida fica lembrada para este vídeo (ou para a série toda).
                active.currentMediaItem?.mediaId?.let { library.saveSpeed(it, params.speed) }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    Pip.aspect = Rational(videoSize.width, videoSize.height)
                    Pip.update(activity)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) saveCurrent()
            }

            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sem conexão. Confira a internet."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                        "O Drive recusou o vídeo. Ele pode ter sido apagado ou movido, ou a conta precisa ser conectada de novo."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "Não encontrei o arquivo no celular. Ele pode ter sido apagado ou movido."
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ->
                        if (casting) "A TV não conseguiu tocar este formato (o mais seguro para a TV é MP4)."
                        else "Este formato de vídeo não é suportado pelo celular (os mais seguros são MP4 e MKV)."
                    else -> "Não consegui tocar este vídeo (${e.errorCodeName})."
                }
            }
        }
        exo.addListener(listener)
        castPlayer?.addListener(listener)
        castPlayer?.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = switchToCast()
            override fun onCastSessionUnavailable() = switchToPhone()
        })
        onDispose {
            saveCurrent()
            exo.removeListener(listener)
            castPlayer?.removeListener(listener)
            castPlayer?.setSessionAvailabilityListener(null)
            if (casting) runCatching { castPlayer?.stop() }
            castPlayer?.release()
            session?.release()
            exo.release()
        }
    }

    // Já estava conectado na TV ao abrir o player.
    LaunchedEffect(preparing) {
        if (!preparing && castPlayer?.isCastSessionAvailable == true) switchToCast()
    }

    // Miniaturas do vídeo que está tocando agora.
    val thumbs = remember(currentId) { currentId?.let { id -> library.current.video(id)?.let { SeekThumbs(context, it) } } }
    LaunchedEffect(thumbs) {
        val t = thumbs ?: return@LaunchedEffect
        var dur = 0L
        while (dur <= 0) { dur = exo.duration.takeIf { it > 0 } ?: t.video.durationMs; if (dur <= 0) delay(1000) }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { t.run(dur) }
    }

    // Salva a cada 5 s enquanto toca (se o celular desligar de repente, perde no máximo isso).
    LaunchedEffect(exo) {
        while (true) {
            delay(5_000)
            if (active.isPlaying) saveCurrent()
        }
    }

    // Saiu do app de vez: pausa e salva (na janelinha ou na TV continua).
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                if (!casting) exo.pause()
                saveCurrent()
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(inPip) { playerView?.useController = !inPip; if (inPip) playerView?.hideController() }

    BackHandler { onExit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    this.player = active
                    resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowNextButton(queue.size > 1)
                    setShowPreviousButton(queue.size > 1)
                    setShowSubtitleButton(true)
                    setKeepContentOnPlayerReset(true)
                    controllerShowTimeoutMs = 3500
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { vis ->
                        controlsVisible = vis == android.view.View.VISIBLE
                    })
                    playerView = this
                    // Miniaturas: acompanha a barra de tempo enquanto ela é arrastada.
                    (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as? androidx.media3.ui.DefaultTimeBar)?.let { bar ->
                        fun report(pos: Long) {
                            val dur = active.duration.takeIf { it > 0 } ?: return
                            val loc = IntArray(2); bar.getLocationInWindow(loc)
                            val f = (pos.toFloat() / dur).coerceIn(0f, 1f)
                            val pad = bar.paddingLeft + 12 * resources.displayMetrics.density
                            scrub = Triple(f, pos, loc[0] + pad + (bar.width - 2 * pad) * f)
                        }
                        bar.addListener(object : androidx.media3.ui.TimeBar.OnScrubListener {
                            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) = report(position)
                            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) = report(position)
                            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) { scrub = null }
                        })
                    }
                }
            },
        )

        if (!inPip) {
            // Gestos só quando os controles estão escondidos (assim os botões continuam funcionando).
            GestureLayer(
                enabled = !controlsVisible && error == null && !preparing,
                activity = activity,
                onTap = { playerView?.showController() },
                onSeek = { fwd -> if (fwd) active.seekForward() else active.seekBack() },
                fill = fill,
                onFill = { f ->
                    fill = f
                    AppGraph.prefs.edit().putBoolean("fillScreen", f).apply()
                    playerView?.resizeMode = if (f) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                },
            )

            // Prévia da cena ao arrastar a barra.
            scrub?.let { (f, pos, x) -> SeekPreview(thumbs, f, pos, x) }

            if (casting) {
                Column(
                    Modifier.align(Alignment.Center).padding(bottom = 80.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Rounded.Cast, null, tint = Color(0xFF9B8CFF), modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Tocando na TV", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Use os controles abaixo para pausar e avançar.", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }

            // Título, voltar, janelinha e TV, junto com os controles.
            AnimatedVisibility(controlsVisible || error != null || casting, enter = fadeIn(), exit = fadeOut()) {
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
                    if (castContext != null) {
                        AndroidView(
                            modifier = Modifier.size(48.dp),
                            factory = { ctx ->
                                MediaRouteButton(ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)).also {
                                    runCatching { CastButtonFactory.setUpMediaRouteButton(ctx.applicationContext, it) }
                                }
                            },
                        )
                    }
                    if (!casting) IconButton(onClick = { Pip.enter(activity) }) {
                        Icon(Icons.Rounded.PictureInPictureAlt, "Janelinha flutuante", tint = Color.White)
                    }
                }
            }

            if (preparing) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color(0xFF9B8CFF))

            error?.let { msg ->
                Column(
                    Modifier.align(Alignment.Center).padding(40.dp).background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(24.dp)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(msg, color = Color.White, textAlign = TextAlign.Center, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Tentar de novo", null, { error = null; active.prepare(); active.play() })
                        if (active.hasNextMediaItem()) PillButton("Próximo", null, { error = null; active.seekToNextMediaItem(); active.prepare(); active.play() }, filled = false)
                    }
                }
            }
        }
    }
}

/** Quadrinho acima da barra com a cena e o tempo do ponto arrastado. */
@Composable
private fun SeekPreview(thumbs: SeekThumbs?, fraction: Float, positionMs: Long, xPx: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val v = thumbs?.version // lê para atualizar quando chegar quadro novo
    val img = thumbs?.frame(fraction)
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = 168.dp
        val xDp = with(density) { xPx.toDp() } - w / 2
        val clamped = xDp.coerceIn(8.dp, maxWidth - w - 8.dp)
        Column(
            Modifier.align(Alignment.BottomStart).padding(bottom = 92.dp).offset(x = clamped).width(w),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (img != null) {
                androidx.compose.foundation.Image(
                    img, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.width(w).height(w * 9 / 16).clip(RoundedCornerShape(10.dp))
                        .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(10.dp)),
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(
                com.dmwnezes.estante.data.formatTime(positionMs), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    @Suppress("UNUSED_EXPRESSION") v
}
