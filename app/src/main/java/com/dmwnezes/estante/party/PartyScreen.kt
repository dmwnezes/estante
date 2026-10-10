package com.dmwnezes.estante.party

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
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
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveAuth
import com.dmwnezes.estante.drive.DriveShare
import com.dmwnezes.estante.player.findActivity
import com.dmwnezes.estante.player.playUri
import com.dmwnezes.estante.ui.Cinema
import com.dmwnezes.estante.ui.PillButton
import com.dmwnezes.estante.ui.Shapes
import com.dmwnezes.estante.ui.TopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Step { CHECKING, NOT_CONFIGURED, NEED_NAME, INCOMPATIBLE, NOT_SHARED, BAD_KEY, FAILED, ROOM }

/** Cor fixa por pessoa (pelo nome), para o avatar e o nome no chat. */
fun personColor(name: String): Color {
    val colors = listOf(Color(0xFF8E6BE8), Color(0xFFE8836B), Color(0xFF5FB8A3), Color(0xFFE8C14F), Color(0xFF6FA8E8), Color(0xFFE56F9C))
    return colors[Math.floorMod(name.lowercase().hashCode(), colors.size)]
}

/**
 * Assistir junto: você abre a sala daqui, manda o link, e quem estiver no site toca o mesmo
 * filme ao mesmo tempo. Filme em cima, quem está na sala no meio, chat embaixo.
 */
@Composable
fun PartyScreen(video: Video, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.CHECKING) }
    var failMsg by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<PartySession?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var acceptIncompatible by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(PartyConfig.name.ifBlank { AppGraph.auth.account.value.name?.substringBefore(' ').orEmpty() }) }
    var avatar by remember { mutableStateOf(PartyConfig.avatar.ifBlank { Avatars.random() }) }

    val sub = remember(video.subtitle) { video.subtitle?.takeIf { it.startsWith("drive:") }?.removePrefix("drive:") }
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    var autoShareTried by remember { mutableStateOf(false) }

    /** Compartilha o filme (e a legenda) e espera o Google liberar para a chave do site. Devolve o erro, se houver. */
    suspend fun shareFiles(token: String): String? = runCatching {
        DriveShare.makePublic(AppGraph.http, token, video.ref)
        sub?.let { runCatching { DriveShare.makePublic(AppGraph.http, token, it) } }
        repeat(6) {
            if (PartyConfig.checkShared(video.ref) == ShareCheck.Ok) return@runCatching null
            delay(1_200)
        }
        null
    }.getOrElse { DriveShare.explain(it) }

    val shareConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode != Activity.RESULT_OK) { sharing = false; shareError = "Você fechou a tela do Google antes de permitir."; return@rememberLauncherForActivityResult }
        scope.launch {
            val err = runCatching { AppGraph.auth.finishShare(res.data) }.fold({ shareFiles(it) }, { AppGraph.auth.explain(it) })
            sharing = false
            shareError = err
            if (err == null) retry++
        }
    }
    fun startShare() {
        sharing = true
        shareError = null
        scope.launch {
            runCatching { AppGraph.auth.beginShare() }.onSuccess { r ->
                when (r) {
                    is DriveAuth.ShareAuth.NeedsScreen ->
                        runCatching { shareConsent.launch(IntentSenderRequest.Builder(r.intent.intentSender).build()) }
                            .onFailure { sharing = false; shareError = "Não consegui abrir a tela do Google." }
                    is DriveAuth.ShareAuth.Token -> {
                        val err = shareFiles(r.token)
                        sharing = false
                        shareError = err
                        if (err == null) retry++
                    }
                }
            }.onFailure { sharing = false; shareError = AppGraph.auth.explain(it) }
        }
    }

    LaunchedEffect(retry, acceptIncompatible) {
        step = Step.CHECKING
        when {
            !PartyConfig.ready -> { step = Step.NOT_CONFIGURED; return@LaunchedEffect }
            PartyConfig.name.isBlank() || Avatars.find(PartyConfig.avatar) == null -> { step = Step.NEED_NAME; return@LaunchedEffect }
            !acceptIncompatible && !PartySync.iphoneFriendly(video.fileName, null) -> { step = Step.INCOMPATIBLE; return@LaunchedEffect }
        }
        when (val c = PartyConfig.checkShared(video.ref)) {
            ShareCheck.NotShared -> {
                // Já deu a permissão antes: o app compartilha sozinho, sem perguntar de novo.
                if (AppGraph.auth.shareGranted && !autoShareTried) {
                    autoShareTried = true
                    val r = runCatching { AppGraph.auth.beginShare() }.getOrNull()
                    if (r is DriveAuth.ShareAuth.Token) {
                        val err = shareFiles(r.token)
                        if (err == null) { retry++; return@LaunchedEffect }
                        shareError = err
                    }
                }
                step = Step.NOT_SHARED; return@LaunchedEffect
            }
            ShareCheck.BadKey -> { step = Step.BAD_KEY; return@LaunchedEffect }
            is ShareCheck.Failed -> { failMsg = c.message; step = Step.FAILED; return@LaunchedEffect }
            ShareCheck.Ok -> {}
        }
        // A legenda também precisa estar liberada (senão o site fica sem ela, sem avisar).
        if (sub != null && AppGraph.auth.shareGranted && PartyConfig.checkShared(sub) == ShareCheck.NotShared) {
            (runCatching { AppGraph.auth.beginShare() }.getOrNull() as? DriveAuth.ShareAuth.Token)?.let {
                runCatching { DriveShare.makePublic(AppGraph.http, it.token, sub) }
            }
        }
        val s = PartySession(PartyConfig.db(), PartySync.newCode(), PartyConfig.name, PartyConfig.avatar)
        runCatching {
            s.create(
                RoomVideo(video.ref, video.title, PartyConfig.apiKey, sub, PartySync.roomMime(video.fileName)),
                Resume.startAt(video) / 1000.0,
            )
        }.onFailure { failMsg = it.message ?: "Não consegui criar a sala."; step = Step.FAILED; return@LaunchedEffect }
        s.start()
        session = s
        step = Step.ROOM
    }

    DisposableEffect(Unit) { onDispose { session?.leave() } }

    Box(Modifier.fillMaxSize().background(Cinema.bgBottom)) {
        if (step == Step.ROOM && session != null) {
            Room(video, session!!, onBack)
            return@Box
        }
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar("Assistir junto", onBack)
            Column(
                Modifier.fillMaxSize().padding(horizontal = 28.dp).imePadding(),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    Step.CHECKING, Step.ROOM -> {
                        CircularProgressIndicator(color = Cinema.accent)
                        Spacer(Modifier.height(14.dp))
                        Text("Preparando a sala…", color = Cinema.muted)
                    }
                    Step.NOT_CONFIGURED -> Info(
                        "Falta configurar uma vez",
                        "Para a sala funcionar, cole o endereço do Firebase e a chave do Google em Ajustes > Assistir junto. O passo a passo está lá.",
                    ) { PillButton("Abrir Ajustes", null, onOpenSettings) }
                    Step.NEED_NAME -> {
                        Text("Como você quer aparecer na sala?", color = Cinema.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        PersonPic(name, avatar, 84.dp)
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Seu nome") }, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        Text("Escolha sua foto", color = Cinema.muted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        AvatarPicker(avatar, { avatar = it }, size = 46.dp)
                        Spacer(Modifier.height(18.dp))
                        PillButton("Continuar", null, { PartyConfig.name = name; PartyConfig.avatar = avatar; retry++ }, enabled = name.isNotBlank())
                    }
                    Step.INCOMPATIBLE -> Info(
                        "Esse formato não toca no iPhone",
                        "O Safari do iPhone toca MP4, MOV e MKV (MKV só no iOS 17.1 ou mais novo). Este arquivo (${video.fileName ?: "formato desconhecido"}) provavelmente não vai abrir para ela. Dá para converter para MP4 no computador com o conversor da Estante (Ajustes › Converter filmes no computador) e colocar no Drive.",
                    ) {
                        PillButton("Abrir assim mesmo", null, { acceptIncompatible = true }, filled = false)
                    }
                    Step.NOT_SHARED -> Info(
                        "O filme ainda não está compartilhado",
                        "O celular dela não entra na sua conta do Google, então o filme precisa estar como “Qualquer pessoa com o link · Leitor”. O app pode fazer isso por você (na primeira vez o Google pede sua permissão; depois é automático).",
                    ) {
                        if (sharing) {
                            CircularProgressIndicator(color = Cinema.accent)
                            Spacer(Modifier.height(10.dp))
                            Text("Compartilhando…", color = Cinema.muted)
                        } else {
                            shareError?.let {
                                Text(it, color = Cinema.yellow, fontSize = 14.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(14.dp))
                            }
                            PillButton("Compartilhar pelo app", Icons.Rounded.Share, { startShare() }, Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            PillButton("Abrir no Drive", null, {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/file/d/${video.ref}/view"))) }
                            }, Modifier.fillMaxWidth(), filled = false)
                            Spacer(Modifier.height(10.dp))
                            PillButton("Já compartilhei, tentar de novo", null, { retry++ }, Modifier.fillMaxWidth(), filled = false)
                        }
                    }
                    Step.BAD_KEY -> Info(
                        "A chave do Google não funcionou",
                        "Confira em Ajustes > Assistir junto se a chave foi colada inteira e se a Google Drive API está liberada para ela.",
                    ) { PillButton("Abrir Ajustes", null, onOpenSettings) }
                    Step.FAILED -> Info("Não deu para abrir a sala", failMsg) { PillButton("Tentar de novo", null, { retry++ }) }
                }
            }
        }
    }
}

@Composable
private fun Info(title: String, text: String, actions: @Composable () -> Unit) {
    Text(title, color = Cinema.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(text, color = Cinema.muted, fontSize = 15.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(20.dp))
    actions()
}

/** Mensagem de sistema ("Fulana entrou") só na tela, não vai para o banco. */
private data class Line(val key: String, val at: Long, val msg: ChatMessage?, val system: String?)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun Room(video: Video, session: PartySession, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val people by session.people.collectAsState()
    val messages by session.messages.collectAsState()
    val typing by session.typing.collectAsState()
    val reactions by session.reactions.collectAsState()
    // Relógio de 1 s para o "digitando…" sumir na hora certa.
    val tick by androidx.compose.runtime.produceState(0L) { while (true) { value = session.serverNow(); delay(1_000) } }
    val typingNames = typing.values.filter { PartySync.typing(it.second, tick) }.map { it.first }

    fun saveDiary() {
        runCatching {
            AppGraph.diary.save(
                "${session.code}-${session.startedAt}", video.id, video.title, video.cover,
                session.startedAt, System.currentTimeMillis(), session.everyone.values, session.messages.value, session.me,
            )
        }
    }
    // Guarda no diário de minuto em minuto (e ao sair).
    LaunchedEffect(Unit) { while (true) { delay(60_000); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveDiary() } } }
    val host by session.host.collectAsState()
    val remote by session.remote.collectAsState()
    val error by session.error.collectAsState()
    var text by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
    var showPeople by remember { mutableStateOf(false) }
    var lastBig by remember { mutableLongStateOf(0L) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var myAvatar by remember { mutableStateOf(session.myAvatar) }
    var now by remember { mutableLongStateOf(session.serverNow()) }
    val systemLines = remember { mutableStateListOf<Line>() }
    var viewing by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var sendingPhoto by remember { mutableStateOf(false) }
    val roomScope = rememberCoroutineScope()
    // Foto escolhida: reduz e manda (o texto que estiver digitado vai junto como legenda).
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            sendingPhoto = true
            roomScope.launch {
                val data = ChatImages.prepare(context, uri)
                if (data != null) { session.sendImage(data, text); text = "" }
                else android.widget.Toast.makeText(context, "Não consegui abrir essa foto.", android.widget.Toast.LENGTH_SHORT).show()
                sendingPhoto = false
            }
        }
    }
    val listState = rememberLazyListState()
    val imeVisible = WindowInsets.isImeVisible

    /** Até quando ignorar eventos do player (porque fui eu que mexi por causa da sala). */
    var suppressUntil by remember { mutableLongStateOf(0L) }
    var lastState by remember { mutableStateOf<PlayState?>(null) }
    /** Horário (do servidor) em que a contagem 3, 2, 1 termina e o filme começa; 0 = sem contagem. */
    var countdownAt by remember { mutableLongStateOf(0L) }
    var countdownBy by remember { mutableStateOf("") }

    val player = remember {
        val http = OkHttpDataSource.Factory(AppGraph.driveHttp).setUserAgent("Estante-app")
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build().apply {
                setMediaItem(MediaItem.Builder().setUri(playUri(video)).setMediaId(video.id).build(), Resume.startAt(video))
                prepare()
                playWhenReady = false
            }
    }

    fun mine(playing: Boolean) {
        val pos = player.currentPosition / 1000.0
        session.sendState(playing, pos)
        lastState = PlayState(playing, pos, session.serverNow(), session.me)
    }

    fun applyRemote(st: PlayState) {
        lastState = st
        if (PartySync.inCountdown(st, session.serverNow())) {
            // Alguém deu play: fica parado no ponto certo e conta 3, 2, 1 junto.
            suppressUntil = System.currentTimeMillis() + 900
            if (PartySync.needsSeek(player.currentPosition / 1000.0, st.position, 0.3)) player.seekTo((st.position * 1000).toLong())
            player.playWhenReady = false
            countdownBy = people.firstOrNull { it.id == st.by }?.name.orEmpty()
            countdownAt = st.at
            return
        }
        countdownAt = 0
        val expected = PartySync.expected(st, session.serverNow())
        suppressUntil = System.currentTimeMillis() + 900
        if (PartySync.needsSeek(player.currentPosition / 1000.0, expected)) player.seekTo((expected * 1000).toLong())
        player.playWhenReady = st.playing
    }

    // O que eu faço no player vai para a sala.
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (System.currentTimeMillis() < suppressUntil) return
                if (reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) return
                if (playWhenReady) {
                    // Dei play: segura, avisa a sala e todo mundo começa junto depois de 3, 2, 1.
                    suppressUntil = System.currentTimeMillis() + 900
                    player.playWhenReady = false
                    if (countdownAt > 0) return
                    val pos = player.currentPosition / 1000.0
                    val at = session.serverNow() + PartySync.COUNTDOWN_MS
                    session.sendState(true, pos, at, countdown = true)
                    lastState = PlayState(true, pos, at, session.me)
                    countdownBy = ""
                    countdownAt = at
                } else {
                    countdownAt = 0
                    mine(false)
                }
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (System.currentTimeMillis() < suppressUntil) return
                if (reason == Player.DISCONTINUITY_REASON_SEEK) mine(player.playWhenReady)
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            AppGraph.library.savePosition(video.id, player.currentPosition, player.duration.takeIf { it > 0 } ?: 0L)
            saveDiary()
            player.release()
        }
    }

    // O que os outros fazem chega aqui.
    LaunchedEffect(remote) { remote?.let { applyRemote(it) } }

    // Fim da contagem: começa a tocar (se ninguém pausou nesse meio-tempo).
    LaunchedEffect(countdownAt) {
        val at = countdownAt
        if (at <= 0) return@LaunchedEffect
        while (session.serverNow() < at) delay(40)
        val st = lastState
        if (countdownAt == at && st != null && st.playing && st.at == at) {
            suppressUntil = System.currentTimeMillis() + 900
            player.playWhenReady = true
        }
        if (countdownAt == at) countdownAt = 0
    }

    // A cada 4 s confere se não escorregou (rede lenta, travadinha): mais de 1,5 s de diferença, ajusta.
    LaunchedEffect(Unit) {
        while (true) {
            delay(4_000)
            now = session.serverNow()
            val st = lastState ?: continue
            if (st.playing && player.isPlaying) {
                val exp = PartySync.expected(st, session.serverNow())
                if (PartySync.needsSeek(player.currentPosition / 1000.0, exp, 1.5)) {
                    suppressUntil = System.currentTimeMillis() + 900
                    player.seekTo((exp * 1000).toLong())
                }
            }
        }
    }

    // Avisos de quem entrou e saiu.
    var known by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(people, now) {
        val online = people.filter { PartySync.online(it, session.serverNow()) }
        val ids = online.map { it.id }.toSet()
        if (known.isNotEmpty() || ids.size > 1) {
            online.filter { it.id !in known && it.id != session.me }.forEach { systemLines += Line("in-${it.id}-${it.lastSeen}", session.serverNow(), null, "${it.name} entrou na sala") }
            people.filter { it.id in known && it.id !in ids && it.id != session.me }.forEach { systemLines += Line("out-${it.id}-${now}", session.serverNow(), null, "${it.name} saiu") }
        }
        known = ids
    }

    val lines = (messages.map { Line(it.id, it.at, it, null) } + systemLines).sortedBy { it.at }
    LaunchedEffect(lines.size, imeVisible) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    // Tela acesa; tela cheia deitada quando pedir.
    DisposableEffect(full) {
        view.keepScreenOn = true
        val insets = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (full) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { view.keepScreenOn = false; activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; insets?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    BackHandler { if (full) full = false else onBack() }

    val link = PartySync.link(session.code, PartyConfig.dbUrl)
    fun share() {
        val msg = "Bora assistir “${video.title}” comigo? Entra aqui: $link"
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Convidar para a sala"))
    }

    viewing?.let { PhotoViewer(it) { viewing = null } }
    if (showPeople) PeopleDialog(
        people = people.filter { PartySync.online(it, now) }.map { if (it.id == session.me) it.copy(avatar = myAvatar) else it },
        me = session.me,
        myAvatar = myAvatar,
        onPick = { key -> myAvatar = key; PartyConfig.avatar = key; session.setAvatar(key) },
        onClose = { showPeople = false },
    )

    Column(Modifier.fillMaxSize().then(if (full) Modifier else Modifier.statusBarsPadding().navigationBarsPadding().imePadding())) {
        // Filme (com o teclado aberto fica menor, para sobrar espaço ao chat)
        val onlineNow = people.filter { PartySync.online(it, now) }
        Box(
            when {
                full -> Modifier.fillMaxSize().background(Color.Black)
                imeVisible -> Modifier.fillMaxWidth().height(190.dp).background(Color.Black)
                else -> Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
            }
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        this.player = player
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShowSubtitleButton(true)
                        controllerShowTimeoutMs = 3000
                        setFullscreenButtonClickListener { full = it }
                    }
                },
            )
            if (!full) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Sair da sala", tint = Color.White)
            }
            FloatingReactions(reactions, since = session.startedAt - 5_000, serverNow = { session.serverNow() })
            if (countdownAt > 0) Countdown(countdownAt, countdownBy) { session.serverNow() }
            // Teclado aberto: quem está na sala fica por cima do vídeo, no canto.
            if (imeVisible && !full) Row(Modifier.align(Alignment.BottomStart).padding(10.dp), horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                onlineNow.take(5).forEach { p ->
                    Box(Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)).padding(2.dp)) {
                        PersonPic(p.name, p.avatar, 26.dp)
                    }
                }
            }
        }
        if (full) return@Column

        // Quem está na sala: uma linha só (bolinhas, filme/código e convidar); some com o teclado
        val online = onlineNow
        if (!imeVisible) Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().clip(Shapes.pill).background(Cinema.surface)
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Toque nas fotos (ou nos nomes): abre quem está na sala e deixa trocar a sua foto.
            Row(horizontalArrangement = Arrangement.spacedBy((-9).dp), modifier = Modifier.clip(Shapes.pill).clickable { showPeople = true }) {
                online.take(5).forEach { p ->
                    Box(Modifier.size(32.dp).clip(CircleShape).background(Cinema.surface).padding(2.dp)) {
                        PersonPic(p.name, p.avatar, 28.dp)
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).clickable { showPeople = true }) {
                Text(
                    if (online.size <= 1) "Esperando alguém entrar…" else online.joinToString(", ") { if (it.id == session.me) "você" else it.name },
                    color = Cinema.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Sala ${session.code} · ${video.title}", color = Cinema.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            IconButton(
                onClick = ::share,
                modifier = Modifier.size(38.dp).clip(CircleShape).background(Cinema.accent),
            ) { Icon(Icons.Rounded.Share, "Convidar", tint = Cinema.onAccent, modifier = Modifier.size(18.dp)) }
        }
        error?.let { Text(it, color = Cinema.red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp)) }

        // Chat
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (lines.isEmpty()) item {
                Text("O chat da sala aparece aqui.", color = Cinema.muted.copy(alpha = 0.7f), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 20.dp))
            }
            items(lines, key = { it.key }) { line ->
                val m = line.msg
                if (m == null) Text(line.system.orEmpty(), color = Cinema.muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
                else Bubble(m, mine = m.by == session.me, onPhoto = { viewing = it })
            }
        }

        // "Fulana está digitando…"
        androidx.compose.animation.AnimatedVisibility(typingNames.isNotEmpty()) {
            Row(Modifier.padding(start = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                TypingDots()
                Spacer(Modifier.width(8.dp))
                Text(PartySync.typingLabel(typingNames).orEmpty(), color = Cinema.muted, fontSize = 12.sp)
            }
        }
        // Reações rápidas (somem com o teclado aberto)
        if (!imeVisible) Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            REACTIONS.forEach { e ->
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Cinema.surface).combinedClickable(
                        onClick = { session.sendReaction(e) },
                        // Segurar: reação gigante (no máximo uma a cada 3 s).
                        onLongClick = {
                            val t = System.currentTimeMillis()
                            if (t - lastBig >= 3_000) {
                                lastBig = t
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                session.sendReaction(e, big = true)
                            }
                        },
                    ),
                    contentAlignment = Alignment.Center,
                ) { Text(e, fontSize = 22.sp) }
            }
        }
        // Escrever
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !sendingPhoto,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Cinema.surface),
            ) {
                if (sendingPhoto) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Cinema.accent)
                else Icon(Icons.Rounded.Image, "Mandar foto", tint = Cinema.accent)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text, onValueChange = {
                    text = it.take(500)
                    if (text.isBlank()) session.sendTyping(stopped = true) else session.sendTyping()
                },
                placeholder = { Text("Mensagem para a sala") },
                shape = RoundedCornerShape(28.dp), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { session.sendChat(text); text = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Cinema.surface, focusedContainerColor = Cinema.surfaceHigh,
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Cinema.accent.copy(alpha = 0.5f),
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { session.sendChat(text); text = "" },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (text.isNotBlank()) Cinema.accent else Cinema.outline),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Enviar", tint = if (text.isNotBlank()) Cinema.onAccent else Cinema.muted) }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, onPhoto: (androidx.compose.ui.graphics.ImageBitmap) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = if (m.image != null) 240.dp else 280.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) Cinema.accent else Cinema.surfaceHigh)
                .padding(if (m.image != null) 4.dp else 0.dp)
                .padding(horizontal = if (m.image != null) 0.dp else 12.dp, vertical = if (m.image != null) 0.dp else 8.dp),
        ) {
            if (!mine) Text(
                m.name, color = personColor(m.name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = if (m.image != null) Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp) else Modifier,
            )
            m.image?.let { ChatPhoto(m.id, it, onPhoto) }
            if (m.text.isNotBlank()) Text(
                m.text, color = if (mine) Cinema.onAccent else Cinema.text, fontSize = 15.sp,
                modifier = if (m.image != null) Modifier.padding(horizontal = 8.dp, vertical = 6.dp) else Modifier,
            )
        }
    }
}

/** Três pontinhos pulando (alguém digitando). */
@Composable
private fun TypingDots() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pontos")
    val phase by t.animateFloat(
        0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "f",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val lift = kotlin.math.sin(((phase - i * 0.18f) * 2 * Math.PI).toFloat()).coerceAtLeast(0f)
            Box(Modifier.offset(y = (-4 * lift).dp).size(6.dp).clip(CircleShape).background(Cinema.accent.copy(alpha = 0.5f + 0.5f * lift)))
        }
    }
}

/**
 * Emojis subindo por cima do filme. Cada reação nova (de qualquer um) aparece uma vez,
 * nasce embaixo numa posição aleatória e sobe balançando até sumir.
 */
@Composable
fun FloatingReactions(reactions: List<Reaction>, since: Long, serverNow: () -> Long) {
    val shown = remember { mutableStateListOf<Pair<Reaction, Float>>() }
    val bigs = remember { mutableStateListOf<Reaction>() }
    val seen = remember { mutableSetOf<String>() }
    LaunchedEffect(reactions) {
        val now = serverNow()
        reactions.filter { it.id !in seen && it.at >= since && now - it.at < 8_000 }.forEach {
            seen += it.id
            if (it.big) bigs += it else shown += it to (0.15f + kotlin.random.Random.nextFloat() * 0.7f)
        }
        reactions.forEach { seen += it.id }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        val w = maxWidth
        bigs.toList().forEach { r -> key("big-" + r.id) { BigReaction(r) { bigs.removeAll { it.id == r.id } } } }
        shown.toList().forEach { (r, x) ->
            key(r.id) {
                val anim = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) {
                    anim.animateTo(1f, androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                    shown.removeAll { it.first.id == r.id }
                }
                val p = anim.value
                val sway = kotlin.math.sin(p * 9f) * 14f
                Column(
                    Modifier.offset(x = w * x + sway.dp - 20.dp, y = h - (h * 0.85f * p) - 40.dp)
                        .graphicsLayer { alpha = (1f - p * p).coerceIn(0f, 1f); val sc = 0.7f + 0.6f * kotlin.math.min(1f, p * 4); scaleX = sc; scaleY = sc },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(r.emoji, fontSize = 34.sp)
                    Text(r.name, color = Color.White, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 5.dp))
                }
            }
        }
    }
}

/** Quem está na sala; tocando na sua foto dá para trocar. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeopleDialog(people: List<Person>, me: String, myAvatar: String?, onPick: (String) -> Unit, onClose: () -> Unit) {
    var picking by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Cinema.surface).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (picking) "Escolha sua foto" else "Na sala agora", color = Cinema.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (picking) {
                AvatarPicker(myAvatar, { onPick(it); picking = false }, size = 48.dp)
                Spacer(Modifier.height(16.dp))
                PillButton("Voltar", null, { picking = false }, filled = false)
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    people.forEach { p ->
                        val isMe = p.id == me
                        Column(
                            Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).then(if (isMe) Modifier.clickable { picking = true } else Modifier).padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box {
                                PersonPic(p.name, p.avatar, 58.dp, if (isMe) Modifier.border(2.dp, Cinema.accent, CircleShape) else Modifier)
                                if (isMe) Box(
                                    Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(Cinema.accent),
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Edit, "Trocar foto", tint = Cinema.onAccent, modifier = Modifier.size(13.dp)) }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(if (isMe) "você" else p.name, color = Cinema.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Toque na sua foto para trocar", color = Cinema.muted, fontSize = 12.sp)
                Spacer(Modifier.height(14.dp))
                PillButton("Fechar", null, onClose, filled = false)
            }
        }
    }
}

/** Reação gigante: o emoji explode no meio da tela, com pedacinhos voando para os lados. */
@Composable
private fun BigReaction(r: Reaction, onDone: () -> Unit) {
    val anim = remember { androidx.compose.animation.core.Animatable(0f) }
    val bits = remember { List(12) { i -> (i / 12f) * 6.2832f + kotlin.random.Random.nextFloat() * 0.4f to (0.7f + kotlin.random.Random.nextFloat() * 0.6f) } }
    LaunchedEffect(Unit) {
        anim.animateTo(1f, androidx.compose.animation.core.tween(1800, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        onDone()
    }
    val p = anim.value
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        bits.forEach { (a, d) ->
            val dist = 130f * d * kotlin.math.min(1f, p * 1.3f)
            Text(
                r.emoji, fontSize = 28.sp,
                modifier = Modifier.graphicsLayer {
                    translationX = kotlin.math.cos(a) * dist * density
                    translationY = kotlin.math.sin(a) * dist * density
                    alpha = (1f - p).coerceIn(0f, 1f)
                    rotationZ = a * 20f * p
                },
            )
        }
        val sc = when {
            p < 0.18f -> 0.2f + p / 0.18f * 1.05f
            p < 0.3f -> 1.25f - (p - 0.18f) / 0.12f * 0.25f
            else -> 1f + (p - 0.3f) * 0.8f
        }
        Text(r.emoji, fontSize = 110.sp, modifier = Modifier.graphicsLayer { scaleX = sc; scaleY = sc; alpha = if (p < 0.75f) 1f else ((1f - p) / 0.25f).coerceIn(0f, 1f) })
        Text(
            r.name, color = Color.White, fontSize = 12.sp,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp).graphicsLayer { alpha = if (p < 0.7f) 1f else ((1f - p) / 0.3f).coerceIn(0f, 1f) }
                .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** Contagem 3, 2, 1 por cima do filme antes de começar junto. */
@Composable
private fun Countdown(at: Long, by: String, serverNow: () -> Long) {
    val n by androidx.compose.runtime.produceState(PartySync.countdownNumber(at, serverNow()), at) {
        while (true) { value = PartySync.countdownNumber(at, serverNow()); delay(50) }
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(n) {
        if (n <= 0) return@LaunchedEffect
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        pop.snapTo(1.5f)
        pop.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f))
    }
    Box(Modifier.fillMaxSize().background(Color(0x8C0A071E)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = (2.5f - pop.value * 1.5f).coerceIn(0f, 1f) }
                    .size(110.dp).clip(CircleShape).background(Cinema.accent.copy(alpha = 0.25f)).padding(10.dp).clip(CircleShape).background(Cinema.accent),
                contentAlignment = Alignment.Center,
            ) { Text(if (n > 0) "$n" else "▶", color = Cinema.onAccent, fontSize = 50.sp, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.height(14.dp))
            Text(if (by.isBlank()) "Começando junto…" else "$by deu o play", color = Color.White, fontSize = 14.sp)
        }
    }
}
