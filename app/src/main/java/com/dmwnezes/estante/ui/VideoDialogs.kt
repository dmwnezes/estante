package com.dmwnezes.estante.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Replay
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.DEFAULT_SHELF
import com.dmwnezes.estante.data.Playlist
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.Source
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatDuration
import com.dmwnezes.estante.data.formatTime
import kotlinx.coroutines.launch
import java.io.File

/** Ficha do DVD: capa grande, progresso e ações. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoSheet(
    video: Video,
    onPlay: (fromStart: Boolean) -> Unit,
    onEdit: () -> Unit,
    onAddToList: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Cinema.surface,
        shape = Shapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DvdCase(video, Modifier.width(120.dp))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = Cinema.text, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(video.shelf, color = Cinema.accent, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    val info = buildList {
                        if (video.durationMs > 0) add(formatDuration(video.durationMs))
                        add(if (video.source == Source.DRIVE) "Google Drive" else "No celular")
                        if (video.finished) add("assistido")
                    }.joinToString(" · ")
                    Text(info, color = Cinema.muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            val resumeAt = Resume.startAt(video)
            if (resumeAt > 0) {
                PillButton("Continuar de ${formatTime(resumeAt)}", Icons.Rounded.PlayArrow, { onPlay(false) }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                PillButton("Assistir do início", Icons.Rounded.Replay, { onPlay(true) }, Modifier.fillMaxWidth(), filled = false)
            } else {
                PillButton(if (video.finished) "Assistir de novo" else "Assistir", Icons.Rounded.PlayArrow, { onPlay(true) }, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(14.dp))
            SheetAction(Icons.Rounded.Edit, "Editar capa e título", onEdit)
            SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Adicionar a uma lista", onAddToList)
            SheetAction(Icons.Rounded.DeleteOutline, "Tirar da estante", { confirmRemove = true }, Cinema.red)
        }
    }
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
private fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit, tint: Color = Cinema.text) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint)
        Spacer(Modifier.width(14.dp))
        Text(text, color = tint, fontSize = 16.sp)
    }
}

/**
 * Editar o DVD: título, prateleira e capa (galeria, quadro do vídeo ou capa gerada).
 * Uma capa nova só substitui a antiga ao tocar em Salvar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditVideoDialog(video: Video, shelves: List<String>, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(video.title) }
    var shelf by remember { mutableStateOf(video.shelf) }
    var cover by remember { mutableStateOf(video.cover) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
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
        lib.update(video.id) { it.copy(title = title.trim().ifBlank { video.title }, shelf = shelf.trim().ifBlank { DEFAULT_SHELF }) }
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
                    SmallChoice(Icons.Rounded.Image, "Da galeria") {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                    SmallChoice(Icons.Rounded.Theaters, "Quadro do vídeo", ::frame)
                    SmallChoice(Icons.Rounded.AutoAwesome, "Capa automática") { cover = null }
                }
            }
            error?.let { Text(it, color = Cinema.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Título") }, singleLine = false, maxLines = 3,
                shape = Shapes.field, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = shelf, onValueChange = { shelf = it },
                label = { Text("Prateleira") }, singleLine = true,
                shape = Shapes.field, modifier = Modifier.fillMaxWidth(),
            )
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
}

/** Endereço da miniatura do Drive a partir do id (funciona com o token do Google). */
fun thumbnailFor(fileId: String) = "https://drive.google.com/thumbnail?id=$fileId&sz=w1000"

@Composable
private fun SmallChoice(icon: ImageVector, text: String, onClick: () -> Unit) {
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

/** Em qual prateleira os vídeos novos entram. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShelfChoiceDialog(count: Int, shelves: List<String>, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var shelf by remember { mutableStateOf(shelves.firstOrNull() ?: DEFAULT_SHELF) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (count == 1) "Colocar 1 vídeo em…" else "Colocar $count vídeos em…") },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    (shelves + DEFAULT_SHELF).distinct().forEach { s -> Chip(s, selected = s == shelf) { shelf = s } }
                }
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = shelf, onValueChange = { shelf = it },
                    label = { Text("Prateleira (ou crie uma nova)") }, singleLine = true,
                    shape = Shapes.field, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text("As capas vêm do próprio vídeo; dá para trocar depois.", color = Cinema.muted, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(shelf.trim().ifBlank { DEFAULT_SHELF }) }) { Text("Colocar na estante") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
