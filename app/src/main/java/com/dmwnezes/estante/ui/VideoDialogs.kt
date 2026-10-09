package com.dmwnezes.estante.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Theaters
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Playlist
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.TmdbResult
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatDuration
import com.dmwnezes.estante.data.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/** Ficha do DVD: a caixa abre e mostra o disco; progresso, sinopse e ações. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideoSheet(
    video: Video,
    onPlay: (fromStart: Boolean) -> Unit,
    onEdit: () -> Unit,
    onAddToList: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
    onParty: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }

    // Abre a caixa ao aparecer; o disco gira devagar e acelera ao tocar em Assistir.
    val open = remember { Animatable(0f) }
    val boost = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(180); open.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 90f)) }
    val idle by rememberInfiniteTransition(label = "disco").animateFloat(
        0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "giro",
    )
    var starting by remember { mutableStateOf(false) }
    fun play(fromStart: Boolean) {
        if (starting) return
        starting = true
        scope.launch {
            boost.animateTo(900f, tween(650))
            onPlay(fromStart)
        }
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            AppGraph.library.update(video.id) { it.copy(subtitle = uri.toString()) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Cinema.surface,
        shape = Shapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp).padding(bottom = 20.dp),
        ) {
            OpenCase(
                video.cover, video.title, open.value, idle + boost.value,
                Modifier.align(Alignment.CenterHorizontally).widthIn(max = 300.dp).fillMaxWidth(0.82f),
            )
            Spacer(Modifier.height(18.dp))
            Text(video.title, color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
            Spacer(Modifier.height(4.dp))
            val info = buildList {
                video.year?.let { add(it) }
                if (video.durationMs > 0) add(formatDuration(video.durationMs))
                add(if (video.source == Source.DRIVE) "Google Drive" else "No celular")
                if (video.finished) add("assistido")
            }.joinToString(" · ")
            Text("${video.shelf} · $info", color = Cinema.muted, fontSize = 13.sp)
            if (video.genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    video.genres.forEach { g ->
                        Text(g, color = Cinema.accent, fontSize = 12.sp, modifier = Modifier.clip(Shapes.pill).background(Cinema.surfaceHigh).padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
            video.synopsis?.let {
                Spacer(Modifier.height(10.dp))
                var expanded by remember { mutableStateOf(false) }
                Text(
                    it, color = Cinema.text.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            Spacer(Modifier.height(18.dp))
            val resumeAt = Resume.startAt(video)
            if (resumeAt > 0) {
                PillButton("Continuar de ${formatTime(resumeAt)}", Icons.Rounded.PlayArrow, { play(false) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PillButton("Assistir do início", Icons.Rounded.Replay, { play(true) }, Modifier.fillMaxWidth(), filled = false)
            } else {
                PillButton(if (video.finished) "Assistir de novo" else "Assistir", Icons.Rounded.PlayArrow, { play(true) }, Modifier.fillMaxWidth())
            }
            if (onParty != null && video.source == Source.DRIVE) {
                Spacer(Modifier.height(10.dp))
                PillButton("Assistir junto", Icons.Rounded.Groups, onParty, Modifier.fillMaxWidth(), filled = false)
            }
            Spacer(Modifier.height(14.dp))
            SheetAction(Icons.Rounded.Edit, "Editar capa e título", onEdit)
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar a uma lista", onAddToList)
            SheetAction(
                Icons.Rounded.Subtitles,
                when {
                    video.subtitle?.startsWith("content:") == true -> "Legenda: arquivo escolhido"
                    video.subtitle?.startsWith("drive:") == true -> "Legenda: encontrada no Drive"
                    video.source == Source.DRIVE -> "Legenda: automática (.srt na pasta)"
                    else -> "Legenda: escolher arquivo .srt"
                },
                { subtitleMenu = true },
            )
            SheetAction(Icons.Rounded.DeleteOutline, "Tirar da estante", { confirmRemove = true }, Cinema.red)
        }
    }

    if (subtitleMenu) AlertDialog(
        onDismissRequest = { subtitleMenu = false },
        title = { Text("Legenda") },
        text = {
            Text(
                if (video.source == Source.DRIVE) "Se houver um arquivo .srt ou .vtt com o mesmo nome do vídeo na mesma pasta do Drive, ele entra sozinho. Você também pode escolher um arquivo do celular."
                else "Escolha um arquivo .srt ou .vtt do celular para este vídeo.",
            )
        },
        confirmButton = {
            TextButton(onClick = { subtitleMenu = false; pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*")) }) { Text("Escolher arquivo") }
        },
        dismissButton = {
            if (video.subtitle != null) TextButton(onClick = { subtitleMenu = false; AppGraph.library.update(video.id) { it.copy(subtitle = null) } }) { Text("Tirar legenda") }
            else TextButton(onClick = { subtitleMenu = false }) { Text("Fechar") }
        },
    )

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Tirar da estante?") },
            text = { Text("O vídeo continua no ${if (video.source == Source.DRIVE) "Google Drive" else "celular"}. Só sai da estante (e das listas).") },
            confirmButton = { TextButton(onClick = { confirmRemove = false; onRemove() }) { Text("Tirar", color = Cinema.red) } },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit, tint: Color = Cinema.text) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(text, color = tint, fontSize = 16.sp)
    }
}

/** O que foi escolhido no TMDB e ainda não foi salvo. */
private data class PendingMeta(val synopsis: String?, val year: String?, val genres: List<String>)

/**
 * Editar o DVD: título, prateleira e capa (galeria, internet, quadro do vídeo ou capa gerada).
 * Uma capa nova só substitui a antiga ao tocar em Salvar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditVideoDialog(video: Video, shelves: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(video.title) }
    var shelf by remember { mutableStateOf(video.shelf) }
    var cover by remember { mutableStateOf(video.cover) }
    var meta by remember { mutableStateOf<PendingMeta?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    val created = remember { mutableListOf<String>() }

    fun useNew(path: String?) {
        if (path == null) { error = "Não consegui usar essa imagem."; return }
        created += path
        cover = path
        error = null
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) scope.launch { busy = true; useNew(AppGraph.covers.fromImage(uri)); busy = false }
    }

    fun frame() {
        scope.launch {
            busy = true
            val path = when (video.source) {
                Source.LOCAL -> AppGraph.covers.fromLocalFrame(Uri.parse(video.ref))
                Source.DRIVE -> {
                    val link = runCatching { AppGraph.drive.file(video.ref).thumbnail }.getOrNull()
                    AppGraph.covers.fromDriveThumbnail(link ?: thumbnailFor(video.ref))
                }
            }
            useNew(path)
            busy = false
        }
    }

    fun cancel() {
        created.filter { it != video.cover }.forEach { runCatching { File(it).delete() } }
        onDismiss()
    }

    fun save() {
        val lib = AppGraph.library
        lib.update(video.id) {
            val m = meta
            it.copy(
                title = title.trim().ifBlank { video.title },
                shelf = shelf.trim().ifBlank { DEFAULT_SHELF },
                synopsis = m?.synopsis ?: it.synopsis, year = m?.year ?: it.year, genres = m?.genres?.ifEmpty { null } ?: it.genres,
            )
        }
        if (cover != video.cover) lib.setCover(video.id, cover)
        created.filter { it != cover }.forEach { runCatching { File(it).delete() } }
        onDismiss()
    }

    Dialog(onDismissRequest = ::cancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(16.dp).fillMaxWidth().clip(Shapes.card).background(Cinema.surface)
                .verticalScroll(rememberScrollState()).padding(22.dp),
        ) {
            Text("Editar DVD", color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(118.dp)) {
                    DvdCase(video.copy(title = title.ifBlank { video.title }, cover = cover), Modifier.fillMaxWidth(), showProgress = false)
                    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Cinema.accent)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChoice(Icons.Rounded.Language, "Da internet") { searching = true }
                    SmallChoice(Icons.Rounded.Image, "Da galeria") {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    SmallChoice(Icons.Rounded.Theaters, "Quadro do vídeo", ::frame)
                    SmallChoice(Icons.Rounded.AutoAwesome, "Capa automática") { cover = null }
                }
            }
            error?.let { Text(it, color = Cinema.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            meta?.let { m ->
                Text(
                    listOfNotNull(m.year, m.genres.joinToString(", ").ifBlank { null }, if (m.synopsis != null) "com sinopse" else null).joinToString(" · "),
                    color = Cinema.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, maxLines = 3, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(value = shelf, onValueChange = { shelf = it }, label = { Text("Prateleira") }, singleLine = true, shape = Shapes.field, modifier = Modifier.fillMaxWidth())
            val suggestions = (shelves + DEFAULT_SHELF).distinct().filter { it != shelf }
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { s -> Chip(s) { shelf = s } }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = ::cancel) { Text("Cancelar") }
                Spacer(Modifier.width(8.dp))
                PillButton("Salvar", null, ::save, enabled = !busy)
            }
        }
    }

    if (searching) TmdbSearchDialog(
        initial = title, seriesFirst = false,
        onDismiss = { searching = false },
    ) { r, useTitle ->
        searching = false
        scope.launch {
            busy = true
            useNew(AppGraph.covers.fromUrl(r.posterUrl))
            if (useTitle) title = r.title
            meta = PendingMeta(r.overview, r.year, AppGraph.tmdb.genreNames(r.genreIds))
            busy = false
        }
    }
}

/**
 * Busca de capas no TMDB: mostra os pôsteres e devolve o escolhido.
 * [onPick] recebe o resultado e se o título oficial deve substituir o atual.
 */
@Composable
fun TmdbSearchDialog(initial: String, seriesFirst: Boolean, onDismiss: () -> Unit, onPick: (TmdbResult, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(com.dmwnezes.estante.data.Tmdb.cleanQuery(initial).first) }
    var results by remember { mutableStateOf<List<TmdbResult>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var useTitle by remember { mutableStateOf(false) }
    val hasKey = AppGraph.tmdb.hasKey

    fun search() {
        if (!hasKey || query.isBlank()) return
        loading = true; error = null
        scope.launch {
            runCatching { AppGraph.tmdb.search(query, seriesFirst) }
                .onSuccess { results = it; if (it.isEmpty()) error = "Nada encontrado. Tente outro nome." }
                .onFailure { error = it.message ?: "Não consegui buscar agora." }
            loading = false
        }
    }
    LaunchedEffect(Unit) { search() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(14.dp).fillMaxWidth().heightIn(max = 640.dp).clip(Shapes.card).background(Cinema.surface).padding(18.dp)) {
            Text("Capa da internet", color = Cinema.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            if (!hasKey) {
                Text(
                    "Para buscar capas, sinopse e gêneros, cole uma chave grátis do TMDB em Ajustes > Capas da internet. O passo a passo está lá.",
                    color = Cinema.muted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { TextButton(onClick = onDismiss) { Text("Ok") } }
                return@Column
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, shape = Shapes.field,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().clip(Shapes.pill).clickable { useTitle = !useTitle }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (useTitle) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = Cinema.accent)
                Spacer(Modifier.width(8.dp))
                Text("Usar também o título oficial", color = Cinema.text, fontSize = 14.sp)
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp)) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center).padding(30.dp), color = Cinema.accent)
                    error != null && results.isEmpty() -> Text(error!!, color = Cinema.muted, modifier = Modifier.align(Alignment.Center).padding(20.dp))
                    else -> LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(results, key = { "${it.isSeries}-${it.id}" }) { r ->
                            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onPick(r, useTitle) }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(CASE_RATIO).clip(RoundedCornerShape(8.dp)).background(Cinema.surfaceHigh)) {
                                    if (r.thumbUrl != null) AsyncImage(r.thumbUrl, r.title, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                                    else GeneratedCover(r.title, Modifier.matchParentSize())
                                }
                                Text(r.title, color = Cinema.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp, modifier = Modifier.padding(top = 4.dp))
                                Text(listOfNotNull(r.year, if (r.isSeries) "série" else "filme").joinToString(" · "), color = Cinema.muted, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Dados do TMDB", color = Cinema.muted.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancelar") }
            }
        }
    }
}

/** Endereço da miniatura do Drive a partir do id (funciona com o token do Google). */
fun thumbnailFor(fileId: String) = "https://drive.google.com/thumbnail?id=$fileId&sz=w1000"

@Composable
fun SmallChoice(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Shapes.pill).background(Cinema.surfaceHigh).clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Cinema.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Cinema.text, fontSize = 14.sp)
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.clip(Shapes.pill)
            .background(if (selected) Cinema.accent else Cinema.surfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(text, color = if (selected) Cinema.onAccent else Cinema.text, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Escolher em quais listas o DVD entra (ou criar uma nova). */
@Composable
fun AddToPlaylistDialog(video: Video, playlists: List<Playlist>, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adicionar a uma lista") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (playlists.isEmpty()) Text("Você ainda não tem listas. Crie a primeira:", color = Cinema.muted)
                playlists.forEach { p ->
                    val inside = video.id in p.videoIds
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                            if (inside) AppGraph.library.removeFromPlaylist(p.id, video.id)
                            else AppGraph.library.addToPlaylist(p.id, listOf(video.id))
                        }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(if (inside) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (inside) Cinema.accent else Cinema.muted)
                        Spacer(Modifier.width(12.dp))
                        Text(p.name, color = Cinema.text, modifier = Modifier.weight(1f))
                        Text("${p.videoIds.size}", color = Cinema.muted, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("Nova lista") }, singleLine = true, shape = Shapes.field,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(enabled = newName.isNotBlank(), onClick = {
                            AppGraph.library.createPlaylist(newName, listOf(video.id)); newName = ""
                        }) { Text("Criar") }
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Pronto") } },
    )
}

/**
 * Em qual prateleira os vídeos novos entram. Com [allowSeries], dá para juntar tudo numa
 * série; com [allowSync], ligar a pasta para vídeos novos entrarem sozinhos.
 * [onConfirm] recebe (prateleira, nome da série ou nulo, sincronizar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfChoiceDialog(
    count: Int,
    shelves: List<String>,
    onConfirm: (String, String?, Boolean) -> Unit,
    onDismiss: () -> Unit,
    allowSeries: Boolean = false,
    seriesName: String = "",
    allowSync: Boolean = false,
    startAsSeries: Boolean = false,
) {
    var shelf by remember { mutableStateOf(shelves.firstOrNull() ?: DEFAULT_SHELF) }
    var asSeries by remember { mutableStateOf(startAsSeries) }
    var name by remember { mutableStateOf(seriesName) }
    var sync by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Colocar 1 vídeo em…" else "Colocar $count vídeos em…") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (shelves + DEFAULT_SHELF).distinct().forEach { s -> Chip(s, selected = s == shelf) { shelf = s } }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = shelf, onValueChange = { shelf = it },
                    label = { Text("Prateleira (ou crie uma nova)") }, singleLine = true,
                    shape = Shapes.field, modifier = Modifier.fillMaxWidth(),
                )
                if (allowSeries && count > 1) {
                    Spacer(Modifier.height(8.dp))
                    CheckRow("Juntar como série (caixa com temporadas)", asSeries) { asSeries = !asSeries }
                    if (asSeries) OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text("Nome da série") }, singleLine = true,
                        shape = Shapes.field, modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (allowSync) CheckRow("Sincronizar: vídeos novos nesta pasta entram sozinhos", sync) { sync = !sync }
                Spacer(Modifier.height(8.dp))
                Text("As capas vêm do próprio vídeo ou da internet; dá para trocar depois.", color = Cinema.muted, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(shelf.trim().ifBlank { DEFAULT_SHELF }, if (asSeries) name.trim().ifBlank { "Série" } else null, sync) }) { Text("Colocar na estante") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun CheckRow(text: String, checked: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(Shapes.field).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (checked) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null, tint = Cinema.accent)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Cinema.text, fontSize = 14.sp)
    }
}
