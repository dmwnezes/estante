package com.dmwnezes.estante

import android.content.Context
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dmwnezes.estante.data.Importer
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.player.PlayerScreen
import com.dmwnezes.estante.ui.AddToPlaylistDialog
import com.dmwnezes.estante.ui.DrivePickerScreen
import com.dmwnezes.estante.ui.EditVideoDialog
import com.dmwnezes.estante.ui.EstanteTheme
import com.dmwnezes.estante.ui.PlaylistDetailScreen
import com.dmwnezes.estante.ui.PlaylistsScreen
import com.dmwnezes.estante.ui.SettingsScreen
import com.dmwnezes.estante.ui.ShelfChoiceDialog
import com.dmwnezes.estante.ui.ShelfScreen
import com.dmwnezes.estante.ui.SplashCredits
import com.dmwnezes.estante.ui.VideoSheet
import com.dmwnezes.estante.update.Release
import com.dmwnezes.estante.update.UpdateDialog
import com.dmwnezes.estante.update.Updater

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // A estante fica em pé; só o player deita a tela.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        AppGraph.init(this)
        setContent { EstanteTheme { EstanteApp() } }
    }
}

/** Telas do app. */
sealed interface Screen {
    data object Shelf : Screen
    data object Drive : Screen
    data object Playlists : Screen
    data class PlaylistDetail(val id: String) : Screen
    data object Settings : Screen
    data class Player(val queue: List<String>, val start: Int, val fromStart: Boolean) : Screen
}

@Composable
fun EstanteApp() {
    val context = LocalContext.current
    val library = AppGraph.library
    val state by library.state.collectAsState()
    val prefs = remember { context.getSharedPreferences("app", Context.MODE_PRIVATE) }

    var splash by rememberSaveable { mutableStateOf(true) }
    val stack = remember { mutableStateListOf<Screen>(Screen.Shelf) }
    val screen = stack.last()
    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    var sort by remember { mutableStateOf(runCatching { SortOrder.valueOf(prefs.getString("sort", "")!!) }.getOrDefault(SortOrder.TITLE)) }
    var openVideo by remember { mutableStateOf<String?>(null) }
    var editVideo by remember { mutableStateOf<String?>(null) }
    var listVideo by remember { mutableStateOf<String?>(null) }
    var localPicked by remember { mutableStateOf<List<Uri>?>(null) }

    var showUpdate by remember { mutableStateOf(false) }
    var foundUpdate by remember { mutableStateOf<Release?>(null) }
    var newerAvailable by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun play(queue: List<Video>, start: Int = 0, fromStart: Boolean = false) {
        if (queue.isEmpty()) return
        go(Screen.Player(queue.map { it.id }, start, fromStart))
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
                onSort = { sort = it; prefs.edit().putString("sort", it.name).apply() },
                onOpen = { openVideo = it.id },
                onEdit = { editVideo = it.id },
                onResume = { play(listOf(it)) },
                onAddDrive = { go(Screen.Drive) },
                onAddLocal = { pickLocal.launch(arrayOf("video/*")) },
                onPlaylists = { go(Screen.Playlists) },
                onSettings = { go(Screen.Settings) },
                updateAvailable = newerAvailable,
            )
            Screen.Drive -> DrivePickerScreen(
                onBack = ::back,
                onAdded = { n ->
                    toast(if (n == 1) "1 DVD na estante" else if (n == 0) "Esses vídeos já estavam na estante" else "$n DVDs na estante")
                    back()
                },
            )
            Screen.Playlists -> PlaylistsScreen(
                state = state,
                library = library,
                onBack = ::back,
                onOpen = { go(Screen.PlaylistDetail(it.id)) },
                onPlay = { p -> play(state.videosOf(p)) },
            )
            is Screen.PlaylistDetail -> {
                val p = state.playlist(s.id)
                if (p == null) LaunchedEffect(Unit) { back() }
                else PlaylistDetailScreen(
                    playlist = p,
                    state = state,
                    library = library,
                    onBack = ::back,
                    onPlayFrom = { i -> play(state.videosOf(p), i) },
                )
            }
            Screen.Settings -> SettingsScreen(
                onBack = ::back,
                onCheckUpdate = { showUpdate = true },
                updateAvailable = newerAvailable,
            )
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
        )
    }
    editVideo?.let { id ->
        state.video(id)?.let { v -> EditVideoDialog(v, state.shelves, onDismiss = { editVideo = null }) } ?: LaunchedEffect(id) { editVideo = null }
    }
    listVideo?.let { id ->
        state.video(id)?.let { v -> AddToPlaylistDialog(v, state.playlists, onDismiss = { listVideo = null }) } ?: LaunchedEffect(id) { listVideo = null }
    }
    localPicked?.let { uris ->
        ShelfChoiceDialog(
            count = uris.size,
            shelves = state.shelves,
            onConfirm = { shelf ->
                val n = Importer.addLocal(context, uris, shelf)
                localPicked = null
                toast(if (n == 1) "1 DVD na estante" else if (n == 0) "Esses vídeos já estavam na estante" else "$n DVDs na estante")
            },
            onDismiss = { localPicked = null },
        )
    }

    if (showUpdate) UpdateDialog(onDismiss = { showUpdate = false })
    foundUpdate?.let { r ->
        UpdateDialog(initial = r, onSkip = { prefs.edit().putString("skippedUpdate", it.tag).apply() }, onDismiss = { foundUpdate = null })
    }
}
