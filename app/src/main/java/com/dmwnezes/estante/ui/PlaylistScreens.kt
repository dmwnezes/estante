package com.dmwnezes.estante.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Library
import com.dmwnezes.estante.data.Playlist
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatDuration
import com.dmwnezes.estante.data.formatTime

/** Todas as listas de reprodução. */
@Composable
fun PlaylistsScreen(
    state: LibraryState,
    library: Library,
    onBack: () -> Unit,
    onOpen: (Playlist) -> Unit,
    onPlay: (Playlist) -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar("Listas de reprodução", onBack)
            if (state.playlists.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(36.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = Cinema.accent, modifier = Modifier.size(60.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nenhuma lista ainda", color = Cinema.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Junte vídeos para assistir em sequência — uma série, uma maratona, as aulas de um curso.",
                        color = Cinema.muted, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    PillButton("Criar lista", Icons.Rounded.Add, { creating = true })
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.playlists, key = { it.id }) { p ->
                        PlaylistCard(p, state.videosOf(p), { onOpen(p) }, { onPlay(p) })
                    }
                }
            }
        }
        if (state.playlists.isNotEmpty()) {
            PillButton(
                "Nova lista", Icons.Rounded.Add, { creating = true },
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            )
        }
    }
    if (creating) {
        NameDialog("Nova lista", "", onDismiss = { creating = false }) { name ->
            creating = false
            onOpen(library.createPlaylist(name))
        }
    }
}

@Composable
private fun PlaylistCard(p: Playlist, videos: List<Video>, onClick: () -> Unit, onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Shapes.card).background(Cinema.surface).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Três capas empilhadas, como DVDs encostados.
        Box(Modifier.width(92.dp).height(84.dp)) {
            val shown = videos.take(3)
            if (shown.isEmpty()) {
                Box(Modifier.width(58.dp).height(82.dp).clip(Shapes.case).background(Cinema.surfaceHigh))
            }
            shown.reversed().forEachIndexed { i, v ->
                val depth = shown.size - 1 - i
                DvdCase(
                    v,
                    Modifier.width(58.dp).offset(x = (depth * 15).dp)
                        .graphicsLayer { rotationZ = depth * 4f; alpha = 1f - depth * 0.12f },
                    showProgress = false,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, color = Cinema.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val total = videos.sumOf { it.durationMs }
            Text(
                listOfNotNull(
                    if (videos.size == 1) "1 vídeo" else "${videos.size} vídeos",
                    total.takeIf { it > 0 }?.let(::formatDuration),
                ).joinToString(" · "),
                color = Cinema.muted, fontSize = 13.sp,
            )
        }
        if (videos.isNotEmpty()) {
            IconButton(onClick = onPlay, modifier = Modifier.clip(Shapes.pill).background(Cinema.accent)) {
                Icon(Icons.Rounded.PlayArrow, "Tocar lista", tint = Cinema.onAccent)
            }
        }
    }
}

/** Uma lista: tocar tudo, reordenar, tirar e acrescentar vídeos. */
@Composable
fun PlaylistDetailScreen(
    playlist: Playlist,
    state: LibraryState,
    library: Library,
    onBack: () -> Unit,
    onPlayFrom: (index: Int) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val videos = state.videosOf(playlist)

    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(playlist.name, onBack) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Renomear", tint = Cinema.text) }
                IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.DeleteOutline, "Apagar lista", tint = Cinema.text) }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Tocar tudo", Icons.Rounded.PlayArrow, { onPlayFrom(0) }, Modifier.weight(1f), enabled = videos.isNotEmpty())
                PillButton("Vídeos", Icons.Rounded.Add, { adding = true }, filled = false)
            }
            Spacer(Modifier.height(8.dp))
            if (videos.isEmpty()) {
                Text(
                    "Lista vazia. Toque em “Vídeos” para escolher o que entra.",
                    color = Cinema.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 40.dp)) {
                itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.field).clickable { onPlayFrom(i) }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${i + 1}", color = Cinema.muted, fontSize = 14.sp, modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                        DvdCase(v, Modifier.width(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.title, color = Cinema.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val at = Resume.startAt(v)
                            Text(
                                when {
                                    at > 0 -> "parou em ${formatTime(at)}"
                                    v.finished -> "assistido"
                                    v.durationMs > 0 -> formatDuration(v.durationMs)
                                    else -> ""
                                },
                                color = if (at > 0) Cinema.accent else Cinema.muted, fontSize = 12.sp,
                            )
                        }
                        Column {
                            IconButton(onClick = { library.moveInPlaylist(playlist.id, i, i - 1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowUp, "Subir", tint = if (i > 0) Cinema.text else Cinema.outline)
                            }
                            IconButton(onClick = { library.moveInPlaylist(playlist.id, i, i + 1) }, enabled = i < videos.lastIndex, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowDown, "Descer", tint = if (i < videos.lastIndex) Cinema.text else Cinema.outline)
                            }
                        }
                        IconButton(onClick = { library.removeFromPlaylist(playlist.id, v.id) }) {
                            Icon(Icons.Rounded.Close, "Tirar da lista", tint = Cinema.muted)
                        }
                    }
                }
            }
        }
    }

    if (renaming) NameDialog("Renomear lista", playlist.name, onDismiss = { renaming = false }) {
        library.renamePlaylist(playlist.id, it); renaming = false
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Apagar “${playlist.name}”?") },
        text = { Text("Só a lista é apagada. Os DVDs continuam na estante.") },
        confirmButton = { TextButton(onClick = { deleting = false; library.deletePlaylist(playlist.id); onBack() }) { Text("Apagar", color = Cinema.red) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
    )
    if (adding) PickVideosDialog(state.videos.filterNot { it.id in playlist.videoIds }, onDismiss = { adding = false }) { ids ->
        library.addToPlaylist(playlist.id, ids); adding = false
    }
}

/** Marcar vários DVDs da estante de uma vez. */
@Composable
private fun PickVideosDialog(options: List<Video>, onDismiss: () -> Unit, onConfirm: (List<String>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher vídeos") },
        text = {
            if (options.isEmpty()) Text("Todos os DVDs da estante já estão nesta lista.", color = Cinema.muted)
            else Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                options.sortedBy { it.title.lowercase() }.forEach { v ->
                    val on = v.id in picked
                    Row(
                        Modifier.fillMaxWidth().clip(Shapes.field).clickable { if (on) picked.remove(v.id) else picked.add(v.id) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DvdCase(v, Modifier.width(34.dp), showProgress = false)
                        Spacer(Modifier.width(10.dp))
                        Text(v.title, color = Cinema.text, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Icon(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (on) Cinema.accent else Cinema.muted)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = { onConfirm(picked.toList()) }) {
                Text(if (picked.isEmpty()) "Adicionar" else "Adicionar ${picked.size}")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
