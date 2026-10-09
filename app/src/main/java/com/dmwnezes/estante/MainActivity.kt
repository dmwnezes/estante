package com.dmwnezes.estante

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.dmwnezes.estante.data.Importer
import com.dmwnezes.estante.data.ShelfItem
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.player.Pip
import com.dmwnezes.estante.player.PlayerScreen
import com.dmwnezes.estante.ui.AddToPlaylistDialog
import com.dmwnezes.estante.ui.BoxEditDialog
import com.dmwnezes.estante.ui.BoxScreen
import com.dmwnezes.estante.ui.DrivePickerScreen
import com.dmwnezes.estante.ui.EditVideoDialog
import com.dmwnezes.estante.ui.EstanteTheme
import com.dmwnezes.estante.ui.PlaylistDetailScreen
import com.dmwnezes.estante.ui.PlaylistsScreen
import com.dmwnezes.estante.ui.SettingsScreen
import com.dmwnezes.estante.ui.ShelfChoiceDialog
import com.dmwnezes.estante.ui.ShelfScreen
import com.dmwnezes.estante.ui.ShelfThemes
import com.dmwnezes.estante.ui.SplashCredits
import com.dmwnezes.estante.ui.VideoSheet
import com.dmwnezes.estante.update.Release
import com.dmwnezes.estante.update.UpdateDialog
import com.dmwnezes.estante.update.Updater
import kotlinx.coroutines.launch

/** FragmentActivity porque a janela de escolher a TV (Chromecast) precisa. */
/** Atalho pedido pelo ícone do app (segurar o ícone). */
object Launch {
    var shortcut by androidx.compose.runtime.mutableStateOf<String?>(null)

    fun read(intent: android.content.Intent?) {
        intent?.getStringExtra("abrir")?.let { shortcut = it }
    }
}

class MainActivity : FragmentActivity() {

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { Pip.inPip = it.isInPictureInPictureMode }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A estante fica em pé; só o player deita a tela.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        AppGraph.init(this)
        ShelfThemes.load()
        com.dmwnezes.estante.ui.ShelfLight.load()
        com.dmwnezes.estante.data.Resume.recapMs = if (AppGraph.prefs.getBoolean("recap", true)) 10_000 else 0
        addOnPictureInPictureModeChangedListener(pipListener)
        if (savedInstanceState == null) Launch.read(intent)
        setContent { EstanteTheme { EstanteApp() } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        Launch.read(intent)
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    /** Botão início com vídeo tocando: vira janelinha (no Android 12+ isso já é automático). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Pip.playing && Build.VERSION.SDK_INT < 31) Pip.enter(this)
    }
}

/** Telas do app. */
sealed interface Screen {
    data object Shelf : Screen
    data object Drive : Screen
    data object Playlists : Screen
    data class PlaylistDetail(val id: String) : Screen
    data class Series(val id: String) : Screen
    data object Settings : Screen
    data class Player(val queue: List<String>, val start: Int, val fromStart: Boolean) : Screen
    data class Party(val videoId: String) : Screen
    data object Diary : Screen
    data class DiaryEntry(val id: String) : Screen
}

@Composable
fun EstanteApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = AppGraph.library
    val state by library.state.collectAsState()
    val prefs = AppGraph.prefs

    // Vindo de um atalho, pula a abertura animada.
    var splash by rememberSaveable { mutableStateOf(Launch.shortcut == null) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Shelf) }
    val screen = stack.last()
    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    var sort by remember { mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(SortOrder.TITLE)) }
    var openVideo by remember { mutableStateOf<String?>(null) }
    var editVideo by remember { mutableStateOf<String?>(null) }
    var editBox by remember { mutableStateOf<String?>(null) }
    var listVideo by remember { mutableStateOf<String?>(null) }
    var localPicked by remember { mutableStateOf<List<Uri>?>(null) }

    var showUpdate by remember { mutableStateOf(false) }
    var foundUpdate by remember { mutableStateOf<Release?>(null) }
    var newerAvailable by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    fun addedMsg(n: Int) = when (n) { 0 -> "Esses vídeos já estavam na estante"; 1 -> "1 vídeo na estante"; else -> "$n vídeos na estante" }

    fun setSort(o: SortOrder) { sort = o; prefs.edit().putString("sort", o.name).apply() }

    fun play(queue: List<Video>, start: Int = 0, fromStart: Boolean = false) {
        if (queue.isEmpty()) return
        go(Screen.Player(queue.map { it.id }, start, fromStart))
    }

    /** Episódio de série: toca ele e segue para os próximos da série. */
    fun resume(v: Video) {
        val boxId = v.boxId
        if (boxId != null && state.box(boxId) != null) {
            val eps = state.episodesOf(boxId)
            play(eps, eps.indexOfFirst { it.id == v.id }.coerceAtLeast(0))
        } else play(listOf(v))
    }

    val pickLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) localPicked = uris
    }

    // Busca versão nova uma vez por abertura (depois da abertura animada).
    LaunchedEffect(splash) {
        if (splash) return@LaunchedEffect
        val updater = Updater(AppGraph.http)
        runCatching { updater.latest() }.getOrNull()?.let { r ->
            if (updater.isNewer(r)) {
                newerAvailable = true
                if (prefs.getString("skippedUpdate", null) != r.tag) foundUpdate = r
            }
        }
    }

    // Pastas sincronizadas: verifica ao abrir e ao voltar para o app (no máximo a cada 30 min).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) scope.launch {
                val n = runCatching { Importer.syncAll() }.getOrDefault(0)
                if (n > 0) toast(if (n == 1) "1 vídeo novo das pastas sincronizadas" else "$n vídeos novos das pastas sincronizadas")
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    // Atalhos do ícone.
    LaunchedEffect(Launch.shortcut, splash) {
        val sc = Launch.shortcut ?: return@LaunchedEffect
        if (splash) return@LaunchedEffect
        Launch.shortcut = null
        stack.clear(); stack += Screen.Shelf
        val st = library.current
        when (sc) {
            "continuar" -> st.continueWatching.firstOrNull()?.let(::resume) ?: toast("Nada começado ainda. Escolha um DVD na estante.")
            "sortear" -> {
                // Prefere a pilha "para ver"; senão, algo não assistido; senão, qualquer um.
                val items = st.shelfItems
                val pick = items.filter { it.toWatch }.ifEmpty {
                    items.filter { i -> when (i) { is ShelfItem.Single -> !i.video.finished; is ShelfItem.Series -> i.episodes.any { !it.finished } } }
                }.ifEmpty { items }.randomOrNull()
                when (pick) {
                    null -> toast("A estante está vazia.")
                    is ShelfItem.Single -> { toast("🎲 Sorteado: ${pick.title}"); openVideo = pick.id }
                    is ShelfItem.Series -> { toast("🎲 Sorteado: ${pick.title}"); go(Screen.Series(pick.id)) }
                }
            }
            "junto" -> {
                val v = st.continueWatching.firstOrNull { it.source == com.dmwnezes.estante.data.Source.DRIVE }
                    ?: st.videos.filter { it.source == com.dmwnezes.estante.data.Source.DRIVE }.maxByOrNull { it.watchedAt }
                if (v != null) go(Screen.Party(v.id)) else toast("Abra um filme do Drive e toque em Assistir junto.")
            }
        }
    }

    if (splash) {
        SplashCredits { splash = false }
        return
    }

    BackHandler(stack.size > 1) { back() }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it::class },
        label = "telas",
    ) { s ->
        when (s) {
            Screen.Shelf -> ShelfScreen(
                state = state,
                sort = sort,
                onSort = ::setSort,
                onOpen = { item ->
                    when (item) {
                        is ShelfItem.Single -> openVideo = item.id
                        is ShelfItem.Series -> go(Screen.Series(item.id))
                    }
                },
                onEdit = { item ->
                    when (item) {
                        is ShelfItem.Single -> editVideo = item.id
                        is ShelfItem.Series -> editBox = item.id
                    }
                },
                onResume = ::resume,
                onAddDrive = { go(Screen.Drive) },
                onAddLocal = { pickLocal.launch(arrayOf("video/*")) },
                onPlaylists = { go(Screen.Playlists) },
                onSettings = { go(Screen.Settings) },
                updateAvailable = newerAvailable,
                onMove = { id, shelf, before ->
                    if (shelf == com.dmwnezes.estante.ui.FAV_SHELF) {
                        library.setFavorite(id, true)
                        toast("★ Nos favoritos")
                    } else {
                        // Arrastar fixa a ordem atual como "minha ordem" e passa a usar ela.
                        if (sort != SortOrder.MANUAL) { library.freezeOrder(sort); setSort(SortOrder.MANUAL) }
                        library.move(id, shelf, before)
                    }
                },
                theme = ShelfThemes.current,
            )
            Screen.Drive -> DrivePickerScreen(onBack = ::back, onAdded = { n -> toast(addedMsg(n)); back() })
            Screen.Playlists -> PlaylistsScreen(
                state = state, library = library, onBack = ::back,
                onOpen = { go(Screen.PlaylistDetail(it.id)) },
                onPlay = { p -> play(state.videosOf(p)) },
            )
            is Screen.PlaylistDetail -> {
                val p = state.playlist(s.id)
                if (p == null) LaunchedEffect(Unit) { back() }
                else PlaylistDetailScreen(p, state, library, onBack = ::back, onPlayFrom = { i -> play(state.videosOf(p), i) })
            }
            is Screen.Series -> {
                val box = state.box(s.id)
                if (box == null) LaunchedEffect(Unit) { back() }
                else BoxScreen(
                    box, state, onBack = ::back,
                    onPlay = { queue, i, fromStart -> play(queue, i, fromStart) },
                    onEditEpisode = { editVideo = it.id },
                )
            }
            Screen.Settings -> SettingsScreen(onBack = ::back, onCheckUpdate = { showUpdate = true }, updateAvailable = newerAvailable, onDiary = { go(Screen.Diary) })
            Screen.Diary -> com.dmwnezes.estante.party.DiaryScreen(onBack = ::back, onOpen = { go(Screen.DiaryEntry(it)) })
            is Screen.DiaryEntry -> com.dmwnezes.estante.party.DiaryEntryScreen(s.id, onBack = ::back)
            is Screen.Party -> {
                val v = state.video(s.videoId)
                if (v == null) LaunchedEffect(Unit) { back() }
                else com.dmwnezes.estante.party.PartyScreen(v, onBack = ::back, onOpenSettings = { back(); go(Screen.Settings) })
            }
            is Screen.Player -> {
                val queue = s.queue.mapNotNull { library.current.video(it) }
                if (queue.isEmpty()) LaunchedEffect(Unit) { back() }
                else PlayerScreen(queue, s.start.coerceIn(0, queue.lastIndex), s.fromStart, onExit = ::back)
            }
        }
    }

    openVideo?.let { id ->
        val v = state.video(id)
        if (v == null) LaunchedEffect(id) { openVideo = null }
        else VideoSheet(
            video = v,
            onPlay = { fromStart -> openVideo = null; play(listOf(v), 0, fromStart) },
            onEdit = { openVideo = null; editVideo = id },
            onAddToList = { openVideo = null; listVideo = id },
            onRemove = { openVideo = null; library.remove(id) },
            onDismiss = { openVideo = null },
            onParty = { openVideo = null; go(Screen.Party(id)) },
            onDiary = { entryId -> openVideo = null; go(Screen.DiaryEntry(entryId)) },
        )
    }
    editVideo?.let { id ->
        state.video(id)?.let { v -> EditVideoDialog(v, state.shelves, onDismiss = { editVideo = null }) } ?: LaunchedEffect(id) { editVideo = null }
    }
    editBox?.let { id ->
        state.box(id)?.let { b -> BoxEditDialog(b, state.episodesOf(id), state.shelves, onDismiss = { editBox = null }, onDeleted = { editBox = null }) }
            ?: LaunchedEffect(id) { editBox = null }
    }
    listVideo?.let { id ->
        state.video(id)?.let { v -> AddToPlaylistDialog(v, state.playlists, onDismiss = { listVideo = null }) } ?: LaunchedEffect(id) { listVideo = null }
    }
    localPicked?.let { uris ->
        ShelfChoiceDialog(
            count = uris.size,
            shelves = state.shelves,
            allowSeries = true,
            onConfirm = { shelf, series, _ ->
                val n = Importer.addLocal(context, uris, shelf, series)
                localPicked = null
                toast(addedMsg(n))
            },
            onDismiss = { localPicked = null },
        )
    }

    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false })
    foundUpdate?.let { r ->
        UpdateDialog(initial = r, onSkip = { prefs.edit().putString("skippedUpdate", it.tag).apply() }, onDismiss = { foundUpdate = null })
    }
}
