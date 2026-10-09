package com.dmwnezes.estante.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Box as SeriesBox
import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Episodes
import com.dmwnezes.estante.data.Importer
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatDuration
import com.dmwnezes.estante.data.formatTime
import kotlinx.coroutines.launch
import java.io.File

/** Uma série: capa, sinopse, "continuar" no episódio certo e a lista por temporada. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScreen(
    box: SeriesBox,
    state: LibraryState,
    onBack: () -> Unit,
    onPlay: (queue: List<Video>, index: Int, fromStart: Boolean) -> Unit,
    onEditEpisode: (Video) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val episodes = state.episodesOf(box.id)
    val seasons = episodes.map { it.season }.distinct().sorted()
    var season by remember(box.id) { mutableIntStateOf(episodes.getOrNull(Episodes.nextIndex(episodes))?.season ?: seasons.firstOrNull() ?: 0) }
    var editing by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }
    val sync = state.syncs.firstOrNull { it.boxId == box.id }
    val next = Episodes.nextIndex(episodes)

    Box(Modifier.fillMaxSize().background(ShelfThemes.current.wall)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                TopBar(box.title, onBack) {
                    IconButton(onClick = { editing = true }) { Icon(Icons.Rounded.Edit, "Editar série", tint = Cinema.text) }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    BoxCase(box, episodes, Modifier.width(130.dp), showProgress = false)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(box.title, color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(box.year, if (seasons.count { it > 0 } > 1) "${seasons.count { it > 0 }} temporadas" else null, Episodes.progressLabel(episodes)).joinToString(" · "),
                            color = Cinema.muted, fontSize = 13.sp,
                        )
                        if (box.genres.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                box.genres.forEach { g -> Text(g, color = Cinema.accent, fontSize = 12.sp, modifier = Modifier.clip(Shapes.pill).background(Cinema.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp)) }
                            }
                        }
                    }
                }
            }
            box.synopsis?.let { syn ->
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        syn, color = Cinema.text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).clickable { expanded = !expanded },
                    )
                }
            }
            if (episodes.isNotEmpty()) item {
                val e = episodes[next]
                val at = Resume.startAt(e)
                val label = buildString {
                    append(if (at > 0) "Continuar" else if (episodes.all { it.finished }) "Assistir de novo" else if (episodes.none { it.finished }) "Assistir" else "Próximo")
                    if (e.episodeLabel.isNotBlank()) append(" · ${e.episodeLabel}")
                    if (at > 0) append(" · ${formatTime(at)}")
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                    PillButton(label, Icons.Rounded.PlayArrow, { onPlay(episodes, next, false) }, Modifier.fillMaxWidth())
                    Text(e.title, color = Cinema.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleChip(
                        if (box.favorite) "Favorito" else "Favoritar",
                        if (box.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        box.favorite, Cinema.yellow, Modifier.weight(1f),
                    ) { AppGraph.library.setFavorite(box.id, !box.favorite) }
                    ToggleChip(
                        if (box.toWatch) "Na pilha" else "Pôr na pilha",
                        Icons.Rounded.Layers, box.toWatch, Cinema.accent, Modifier.weight(1f),
                    ) { AppGraph.library.setToWatch(box.id, !box.toWatch) }
                }
            }
            if (sync != null) item {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).clip(Shapes.pill).background(Cinema.surface)
                        .clickable(enabled = !syncing) {
                            syncing = true
                            scope.launch { Importer.syncAll(force = true); syncing = false }
                        }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Cinema.accent)
                    else Icon(Icons.Rounded.Sync, null, tint = Cinema.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (syncing) "Buscando episódios novos…" else "Pasta sincronizada · buscar episódios novos", color = Cinema.text, fontSize = 13.sp)
                }
            }
            if (seasons.size > 1) item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { s -> Chip(if (s == 0) "Extras" else "Temporada $s", selected = s == season) { season = s } }
                }
            }
            val shown = if (seasons.size > 1) episodes.filter { it.season == season } else episodes
            items(shown, key = { it.id }) { e ->
                EpisodeRow(
                    e,
                    isNext = episodes.getOrNull(next)?.id == e.id,
                    onPlay = { onPlay(episodes, episodes.indexOf(e), false) },
                    onEdit = { onEditEpisode(e) },
                )
            }
            if (episodes.isEmpty()) item {
                Text("Nenhum episódio nesta série ainda.", color = Cinema.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
            }
        }
    }

    if (editing) BoxEditDialog(box, episodes, state.shelves, onDismiss = { editing = false }, onDeleted = { editing = false; onBack() })
}

@Composable
private fun EpisodeRow(e: Video, isNext: Boolean, onPlay: () -> Unit, onEdit: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val lib = AppGraph.library
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).clip(Shapes.field)
            .background(if (isNext) Cinema.surfaceHigh else Cinema.surface.copy(alpha = 0.6f))
            .clickable(onClick = onPlay).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(96.dp).height(56.dp).clip(RoundedCornerShape(10.dp)).background(Cinema.surfaceHigh)) {
            CoverImage(e.cover, e.title)
            val p = Resume.progress(e)
            if (Resume.startAt(e) > 0) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Cinema.accent))
            }
            if (e.finished) Icon(Icons.Rounded.CheckCircle, "Assistido", tint = Cinema.accent, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (e.episodeLabel.isNotBlank()) Text(e.episodeLabel, color = if (isNext) Cinema.accent else Cinema.muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(e.title, color = Cinema.text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val at = Resume.startAt(e)
            val sub = when {
                at > 0 -> "parou em ${formatTime(at)}"
                e.durationMs > 0 -> formatDuration(e.durationMs)
                else -> null
            }
            sub?.let { Text(it, color = Cinema.muted, fontSize = 12.sp) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais", tint = Cinema.muted) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (e.finished) "Marcar como não assistido" else "Marcar como assistido") },
                    onClick = { menu = false; lib.update(e.id) { it.copy(finished = !e.finished, positionMs = 0, watchedAt = System.currentTimeMillis()) } },
                )
                DropdownMenuItem(text = { Text("Editar episódio") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Tirar da série (vira DVD solto)") }, onClick = { menu = false; lib.takeOutOfBox(e.id) })
                DropdownMenuItem(text = { Text("Tirar da estante", color = Cinema.red) }, onClick = { menu = false; lib.remove(e.id) })
            }
        }
    }
}

/** Editar a série: nome, temporada/episódio já vêm dos arquivos; aqui é capa, título e prateleira. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxEditDialog(box: SeriesBox, episodes: List<Video>, shelves: List<String>, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(box.title) }
    var shelf by remember { mutableStateOf(box.shelf) }
    var cover by remember { mutableStateOf(box.cover) }
    var busy by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var pendingTmdb by remember { mutableStateOf<com.dmwnezes.estante.data.TmdbResult?>(null) }
    val created = remember { mutableStateListOf<String>() }

    fun useNew(path: String?) { if (path != null) { created += path; cover = path } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { busy = true; useNew(AppGraph.covers.fromImage(uri)); busy = false }
    }

    fun cancel() { created.filter { it != box.cover }.forEach { runCatching { File(it).delete() } }; onDismiss() }

    fun save() {
        val lib = AppGraph.library
        val r = pendingTmdb
        scope.launch {
            val genres = r?.let { AppGraph.tmdb.genreNames(it.genreIds) }
            lib.updateBox(box.id) {
                it.copy(
                    title = title.trim().ifBlank { box.title }, shelf = shelf.trim().ifBlank { DEFAULT_SHELF },
                    synopsis = r?.overview ?: it.synopsis, year = r?.year ?: it.year, genres = genres?.ifEmpty { null } ?: it.genres,
                )
            }
            if (cover != box.cover) lib.setBoxCover(box.id, cover)
            created.filter { it != cover }.forEach { runCatching { File(it).delete() } }
            onDismiss()
        }
    }

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(Shapes.card).background(Cinema.surface).verticalScroll(rememberScrollState()).padding(22.dp)) {
            Text("Editar série", color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(118.dp)) {
                    BoxCase(box.copy(title = title, cover = cover), episodes, Modifier.fillMaxWidth(), showProgress = false)
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cinema.accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChoice(Icons.Rounded.Language, "Da internet") { searching = true }
                    SmallChoice(Icons.Rounded.Image, "Da galeria") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    SmallChoice(Icons.Rounded.Theaters, "1º episódio") {
                        val first = episodes.firstOrNull() ?: return@SmallChoice
                        scope.launch {
                            busy = true
                            useNew(
                                if (first.source == Source.DRIVE) AppGraph.covers.fromDriveThumbnail(thumbnailFor(first.ref))
                                else AppGraph.covers.fromLocalFrame(Uri.parse(first.ref))
                            )
                            busy = false
                        }
                    }
                    SmallChoice(Icons.Rounded.AutoAwesome, "Capa automática") { cover = null }
                }
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Nome da série") }, singleLine = true, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(shelf, { shelf = it }, label = { Text("Prateleira") }, singleLine = true, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (shelves + DEFAULT_SHELF).distinct().filter { it != shelf }.forEach { s -> Chip(s) { shelf = s } }
            }
            Spacer(Modifier.height(14.dp))
            SheetAction(Icons.Rounded.DeleteOutline, "Desfazer a série", { deleting = true }, Cinema.red)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::cancel) { Text("Cancelar") }
                Spacer(Modifier.width(8.dp))
                PillButton("Salvar", null, ::save, enabled = !busy)
            }
        }
    }

    if (searching) TmdbSearchDialog(title, seriesFirst = true, onDismiss = { searching = false }) { r, useTitle ->
        searching = false
        pendingTmdb = r
        if (useTitle) title = r.title
        scope.launch { busy = true; useNew(AppGraph.covers.fromUrl(r.posterUrl)); busy = false }
    }

    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Desfazer “${box.title}”?") },
        text = { Text("Você pode manter os episódios na estante como DVDs soltos ou tirar tudo. Nada é apagado do Drive nem do celular.") },
        confirmButton = {
            TextButton(onClick = { deleting = false; AppGraph.library.deleteBox(box.id, keepEpisodes = true); onDeleted() }) { Text("Manter episódios") }
        },
        dismissButton = {
            TextButton(onClick = { deleting = false; AppGraph.library.deleteBox(box.id, keepEpisodes = false); onDeleted() }) { Text("Tirar tudo", color = Cinema.red) }
        },
    )
}
