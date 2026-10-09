package com.dmwnezes.estante.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Importer
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.formatDuration
import com.dmwnezes.estante.drive.DriveItem
import kotlinx.coroutines.launch

private enum class DriveTab(val label: String) { MINE("Meu Drive"), SHARED("Compartilhados"), SEARCH("Buscar") }

/** Navegar pelo Google Drive e marcar os vídeos que vão para a estante. */
@Composable
fun DrivePickerScreen(onBack: () -> Unit, onAdded: (Int) -> Unit) {
    val account by AppGraph.auth.account.collectAsState()
    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        if (!account.connected) ConnectDrive(onBack) else DriveBrowser(onBack, onAdded)
    }
}

/** Botão "Conectar Google Drive", com o login do Google. */
@Composable
fun ConnectDrive(onBack: (() -> Unit)?, compact: Boolean = false) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val auth = AppGraph.auth

    suspend fun loadProfile() {
        runCatching { AppGraph.drive.about() }.onSuccess { (email, name) -> auth.setProfile(email, name) }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        scope.launch {
            runCatching { auth.finish(res.data) }
                .onSuccess { loadProfile() }
                .onFailure { error = auth.explain(it) }
            busy = false
        }
    }

    fun connect() {
        busy = true; error = null
        scope.launch {
            runCatching { auth.begin() }
                .onSuccess { pi ->
                    if (pi != null) consent.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                    else { loadProfile(); busy = false }
                }
                .onFailure { error = auth.explain(it); busy = false }
        }
    }

    if (compact) {
        Column {
            PillButton(if (busy) "Conectando…" else "Conectar Google Drive", Icons.Rounded.AddToDrive, ::connect, enabled = !busy)
            error?.let { Text(it, color = Cinema.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
        }
        return
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        if (onBack != null) TopBar("Google Drive", onBack)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Rounded.AddToDrive, null, tint = Cinema.accent, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text("Conecte seu Google Drive", color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "A Estante só lê seus arquivos para listar e tocar os vídeos. Nada é alterado nem apagado no Drive.",
                color = Cinema.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            PillButton(if (busy) "Conectando…" else "Conectar Google Drive", Icons.Rounded.AddToDrive, ::connect, enabled = !busy)
            error?.let {
                Spacer(Modifier.height(14.dp))
                Text(it, color = Cinema.red, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun DriveBrowser(onBack: () -> Unit, onAdded: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val library by AppGraph.library.state.collectAsState()

    var tab by remember { mutableStateOf(DriveTab.MINE) }
    val path = remember { mutableStateListOf("root" to "Meu Drive") }
    var items by remember { mutableStateOf<List<DriveItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var needsReconnect by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    val selected = remember { mutableStateMapOf<String, DriveItem>() }
    var askShelf by remember { mutableStateOf<List<DriveItem>?>(null) }
    var gathering by remember { mutableStateOf(false) }

    val folderId = path.last().first
    LaunchedEffect(tab, folderId, reload) {
        if (tab == DriveTab.SEARCH && query.isBlank()) { items = emptyList(); return@LaunchedEffect }
        loading = true; error = null
        runCatching {
            when (tab) {
                DriveTab.MINE -> AppGraph.drive.children(folderId)
                DriveTab.SHARED -> if (path.size > 1) AppGraph.drive.children(folderId) else AppGraph.drive.sharedWithMe()
                DriveTab.SEARCH -> AppGraph.drive.search(query)
            }
        }.onSuccess { items = it }
            .onFailure {
                val api = it as? com.dmwnezes.estante.drive.DriveApiException
                needsReconnect = api?.needsReconnect == true || it.message?.contains("desconectado") == true
                error = api?.friendly ?: "Não consegui abrir o Drive. ${it.message ?: ""}".trim()
            }
        loading = false
    }

    fun switchTab(t: DriveTab) {
        tab = t
        path.clear()
        path += (if (t == DriveTab.SHARED) "shared" else "root") to t.label
    }

    BackHandler(path.size > 1) { path.removeAt(path.lastIndex) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopBar("Escolher vídeos", onBack)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DriveTab.entries.forEach { t -> Chip(t.label, selected = t == tab) { switchTab(t) } }
        }
        if (tab == DriveTab.SEARCH) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Nome do vídeo") }, singleLine = true, shape = Shapes.field,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { reload++ }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            )
        } else if (path.size > 1) {
            Breadcrumb(path.map { it.second }) { i -> while (path.size > i + 1) path.removeAt(path.lastIndex) }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cinema.accent)
                error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(error!!, color = Cinema.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    if (needsReconnect) {
                        PillButton("Conectar de novo", Icons.Rounded.AddToDrive, { AppGraph.auth.disconnect() })
                        Spacer(Modifier.height(10.dp))
                    }
                    PillButton("Tentar de novo", null, { reload++ }, filled = false)
                }
                items.isEmpty() -> Text(
                    if (tab == DriveTab.SEARCH && query.isBlank()) "Digite o nome e toque em buscar." else "Nenhuma pasta ou vídeo aqui.",
                    color = Cinema.muted, modifier = Modifier.align(Alignment.Center),
                )
                else -> {
                    val videos = items.filterNot { it.isFolder }
                    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 110.dp)) {
                        if (videos.isNotEmpty()) item {
                            val all = videos.all { it.id in selected }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${videos.size} ${if (videos.size == 1) "vídeo" else "vídeos"}", color = Cinema.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(
                                    if (all) "Desmarcar todos" else "Marcar todos",
                                    color = Cinema.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clip(Shapes.pill).clickable {
                                        if (all) videos.forEach { selected.remove(it.id) }
                                        else videos.filterNot { AppGraph.library.isOnShelf(Source.DRIVE, it.id) }.forEach { selected[it.id] = it }
                                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                        items(items, key = { it.id }) { item ->
                            val onShelf = library.videos.any { it.source == Source.DRIVE && it.ref == item.id }
                            DriveRow(
                                item = item,
                                checked = item.id in selected,
                                onShelf = onShelf,
                                onClick = {
                                    when {
                                        item.isFolder -> path += item.id to item.name
                                        onShelf -> {}
                                        item.id in selected -> selected.remove(item.id)
                                        else -> selected[item.id] = item
                                    }
                                },
                                onAddFolder = {
                                    gathering = true
                                    scope.launch {
                                        val all = runCatching { AppGraph.drive.videosDeep(item.id) }.getOrDefault(emptyList())
                                            .filterNot { AppGraph.library.isOnShelf(Source.DRIVE, it.id) }
                                        gathering = false
                                        if (all.isEmpty()) error = "Essa pasta não tem vídeos novos." else askShelf = all
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (gathering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cinema.accent)

            if (selected.isNotEmpty()) {
                PillButton(
                    "Colocar ${selected.size} na estante", Icons.Rounded.AddToDrive,
                    { askShelf = selected.values.toList() },
                    Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(20.dp),
                )
            }
        }
    }

    askShelf?.let { list ->
        ShelfChoiceDialog(
            count = list.size,
            shelves = library.shelves,
            onConfirm = { shelf ->
                val n = Importer.addDrive(list, shelf)
                askShelf = null
                selected.clear()
                onAdded(n)
            },
            onDismiss = { askShelf = null },
        )
    }
}

@Composable
private fun DriveRow(item: DriveItem, checked: Boolean, onShelf: Boolean, onClick: () -> Unit, onAddFolder: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).background(Cinema.surfaceHigh), contentAlignment = Alignment.Center) {
            if (item.isFolder) {
                Icon(Icons.Rounded.Folder, null, tint = Cinema.accent)
            } else {
                Icon(Icons.Rounded.Videocam, null, tint = Cinema.muted)
                if (item.thumbnail != null) {
                    AsyncImage(
                        model = item.thumbnail, imageLoader = AppGraph.images, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = Cinema.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val sub = when {
                item.isFolder -> "Pasta"
                onShelf -> "Já está na estante"
                else -> listOfNotNull(
                    item.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                    item.sizeBytes.takeIf { it > 0 }?.let(::formatSize),
                ).joinToString(" · ")
            }
            if (sub.isNotBlank()) Text(sub, color = if (onShelf) Cinema.accent else Cinema.muted, fontSize = 12.sp)
        }
        when {
            item.isFolder -> {
                Text(
                    "Tudo", color = Cinema.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(Shapes.pill).clickable(onClick = onAddFolder).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Cinema.muted)
            }
            onShelf -> Icon(Icons.Rounded.CheckCircle, null, tint = Cinema.accent.copy(alpha = 0.5f))
            else -> Icon(if (checked) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (checked) Cinema.accent else Cinema.muted)
        }
    }
}

@Composable
private fun Breadcrumb(names: List<String>, onJump: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        names.forEachIndexed { i, n ->
            if (i > 0) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Cinema.muted, modifier = Modifier.size(18.dp))
            Text(
                n, color = if (i == names.lastIndex) Cinema.text else Cinema.muted, fontSize = 14.sp,
                fontWeight = if (i == names.lastIndex) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.clip(Shapes.pill).clickable { onJump(i) }.padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/** Barra de cima com voltar e título. */
@Composable
fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = Cinema.text) }
        Text(title, color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** 1.530.000.000 → "1,5 GB"; 420.000.000 → "420 MB". */
fun formatSize(bytes: Long): String =
    if (bytes >= 1_000_000_000) "%.1f GB".format(java.util.Locale("pt", "BR"), bytes / 1e9) else "${bytes / 1_000_000} MB"
