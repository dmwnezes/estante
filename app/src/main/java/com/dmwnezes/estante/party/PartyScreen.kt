package com.dmwnezes.estante.party

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Image
import androidx.activity.compose.rememberLauncherForActivityResult
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

    LaunchedEffect(retry, acceptIncompatible) {
        step = Step.CHECKING
        when {
            !PartyConfig.ready -> { step = Step.NOT_CONFIGURED; return@LaunchedEffect }
            PartyConfig.name.isBlank() -> { step = Step.NEED_NAME; return@LaunchedEffect }
            !acceptIncompatible && !PartySync.iphoneFriendly(video.fileName, null) -> { step = Step.INCOMPATIBLE; return@LaunchedEffect }
        }
        when (val c = PartyConfig.checkShared(video.ref)) {
            ShareCheck.NotShared -> { step = Step.NOT_SHARED; return@LaunchedEffect }
            ShareCheck.BadKey -> { step = Step.BAD_KEY; return@LaunchedEffect }
            is ShareCheck.Failed -> { failMsg = c.message; step = Step.FAILED; return@LaunchedEffect }
            ShareCheck.Ok -> {}
        }
        val s = PartySession(PartyConfig.db(), PartySync.newCode(), PartyConfig.name)
        val sub = video.subtitle?.takeIf { it.startsWith("drive:") }?.removePrefix("drive:")
        runCatching {
            s.create(
                RoomVideo(video.ref, video.title, PartyConfig.apiKey, sub, if (video.fileName?.lowercase()?.endsWith(".mov") == true) "video/quicktime" else "video/mp4"),
                Resume.startAt(video) / 1000.0,
            )
        }.onFailure { failMsg = it.message ?: "Não consegui criar a sala."; step = Step.FAILED; return@LaunchedEffect }
        s.start()
        session = s
        step = Step.ROOM
    }

    DisposableEffect(Unit) { onDispose { session?.leave() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF17120F))) {
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
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(name, { name = it.take(24) }, singleLine = true, label = { Text("Seu nome") }, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(14.dp))
                        PillButton("Continuar", null, { PartyConfig.name = name; retry++ }, enabled = name.isNotBlank())
                    }
                    Step.INCOMPATIBLE -> Info(
                        "Esse formato não toca no iPhone",
                        "O Safari do iPhone toca MP4 e MOV. Este arquivo (${video.fileName ?: "formato desconhecido"}) provavelmente não vai abrir para ela. Dá para converter para MP4 no computador (HandBrake, grátis) e colocar no Drive.",
                    ) {
                        PillButton("Abrir assim mesmo", null, { acceptIncompatible = true }, filled = false)
                    }
                    Step.NOT_SHARED -> Info(
                        "O filme ainda não está compartilhado",
                        "O iPhone dela não entra na sua conta do Google. No Drive, compartilhe o filme (ou a pasta inteira de filmes, assim vale para todos) como “Qualquer pessoa com o link · Leitor”. Depois volte aqui.",
                    ) {
                        PillButton("Abrir no Drive", null, {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://drive.google.com/file/d/${video.ref}/view"))) }
                        })
                        Spacer(Modifier.height(10.dp))
                        PillButton("Já compartilhei, tentar de novo", null, { retry++ }, filled = false)
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

@OptIn(UnstableApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Room(video: Video, session: PartySession, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val view = LocalView.current
    val people by session.people.collectAsState()
    val messages by session.messages.collectAsState()
    val host by session.host.collectAsState()
    val remote by session.remote.collectAsState()
    val error by session.error.collectAsState()
    var text by remember { mutableStateOf("") }
    var full by remember { mutableStateOf(false) }
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
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) mine(playWhenReady)
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
            player.release()
        }
    }

    // O que os outros fazem chega aqui.
    LaunchedEffect(remote) { remote?.let { applyRemote(it) } }

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

    Column(Modifier.fillMaxSize().then(if (full) Modifier else Modifier.statusBarsPadding().navigationBarsPadding().imePadding())) {
        // Filme
        Box(if (full) Modifier.fillMaxSize().background(Color.Black) else Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
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
        }
        if (full) return@Column

        // Quem está na sala (com o teclado aberto vira uma linha só, para sobrar espaço para o chat)
        val online = people.filter { PartySync.online(it, now) }
        if (imeVisible) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(Shapes.pill).background(Color(0xFF241D19))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                    online.take(5).forEach { p ->
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF241D19)).padding(1.5.dp).clip(CircleShape).background(personColor(p.name)),
                            contentAlignment = Alignment.Center,
                        ) { Text(p.name.trim().take(1).uppercase(), color = Color(0xFF1A1310), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold) }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    online.joinToString(", ") { if (it.id == session.me) "você" else it.name },
                    color = Cinema.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text("Sala ${session.code}", color = Cinema.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else Column(
            Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color(0xFF241D19)).padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = Cinema.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Sala ${session.code}", color = Cinema.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    Modifier.clip(Shapes.pill).background(Cinema.accent).clickable(onClick = ::share).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Share, null, tint = Cinema.onAccent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Convidar", color = Cinema.onAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(online, key = { it.id }) { p -> Avatar(p, isHost = p.id == host, isMe = p.id == session.me) }
                if (online.size <= 1) item {
                    Column(Modifier.padding(top = 6.dp)) {
                        Text("Esperando alguém entrar…", color = Cinema.muted, fontSize = 13.sp)
                        Text("Toque em Convidar e mande o link.", color = Cinema.muted.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
            error?.let { Text(it, color = Cinema.red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
        }

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

        // Escrever
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !sendingPhoto,
                modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF241D19)),
            ) {
                if (sendingPhoto) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Cinema.accent)
                else Icon(Icons.Rounded.Image, "Mandar foto", tint = Cinema.accent)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it.take(500) },
                placeholder = { Text("Mensagem para a sala") },
                shape = RoundedCornerShape(28.dp), maxLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { session.sendChat(text); text = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color(0xFF241D19), focusedContainerColor = Color(0xFF2B231E),
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = Cinema.accent.copy(alpha = 0.5f),
                ),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { session.sendChat(text); text = "" },
                enabled = text.isNotBlank(),
                modifier = Modifier.size(48.dp).clip(CircleShape).background(if (text.isNotBlank()) Cinema.accent else Color(0xFF3A302A)),
            ) { Icon(Icons.AutoMirrored.Rounded.Send, "Enviar", tint = if (text.isNotBlank()) Cinema.onAccent else Cinema.muted) }
        }
    }
}

@Composable
fun Avatar(p: Person, isHost: Boolean, isMe: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(62.dp)) {
        Box {
            Box(
                Modifier.size(50.dp).clip(CircleShape).background(personColor(p.name))
                    .then(if (isMe) Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(p.name.trim().take(1).uppercase().ifBlank { "?" }, color = Color(0xFF1A1310), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }
            if (isHost) Text("👑", fontSize = 15.sp, modifier = Modifier.align(Alignment.TopCenter).offset(y = (-12).dp))
            Box(Modifier.align(Alignment.BottomEnd).size(14.dp).clip(CircleShape).background(Color(0xFF17120F)).padding(2.dp).clip(CircleShape).background(Color(0xFF5FD38D)))
        }
        Spacer(Modifier.height(4.dp))
        Text(if (isMe) "${p.name} (você)" else p.name, color = Cinema.text, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Bubble(m: ChatMessage, mine: Boolean, onPhoto: (androidx.compose.ui.graphics.ImageBitmap) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = if (m.image != null) 240.dp else 280.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (mine) 18.dp else 4.dp, bottomEnd = if (mine) 4.dp else 18.dp))
                .background(if (mine) Cinema.accent else Color(0xFF2B231E))
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
