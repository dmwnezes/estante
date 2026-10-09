package com.dmwnezes.estante.party

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.ui.Cinema
import com.dmwnezes.estante.ui.CoverImage
import com.dmwnezes.estante.ui.TopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR"))
private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale("pt", "BR"))
private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dayFmt).replaceFirstChar { it.uppercase() }
private fun hour(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)

/** "com Mandis" / "com Mandis e Ana" (sem contar você). */
fun withWhom(e: DiaryEntry, myName: String): String {
    val others = e.people.filter { !it.equals(myName, ignoreCase = true) }
    return when (others.size) {
        0 -> "só você"
        1 -> "com ${others[0]}"
        else -> "com " + others.dropLast(1).joinToString(", ") + " e ${others.last()}"
    }
}

@Composable
private fun PhotoThumb(path: String, modifier: Modifier, onOpen: (ImageBitmap) -> Unit) {
    val img by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Cinema.surfaceHigh).clickable(enabled = img != null) { img?.let(onOpen) }) {
        img?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Todas as sessões de "assistir junto", da mais recente para a mais antiga. */
@Composable
fun DiaryScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val entries by AppGraph.diary.entries.collectAsState()
    val me = PartyConfig.name
    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar("Diário de sessões", onBack)
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Groups, null, tint = Cinema.accent, modifier = Modifier.size(60.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nenhuma sessão ainda", color = Cinema.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text("Cada vez que vocês assistirem juntos, o filme, a data, as conversas e as fotos ficam guardados aqui.", color = Cinema.muted, textAlign = TextAlign.Center)
                }
                return@Column
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(entries, key = { it.id }) { e ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Cinema.surface).clickable { onOpen(e.id) }.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(54.dp).aspectRatio(0.71f).clip(RoundedCornerShape(6.dp))) { CoverImage(e.cover, e.title) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.title, color = Cinema.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(withWhom(e, me), color = Cinema.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${day(e.startedAt)} · ${e.minutes} min · ${e.messages.size} ${if (e.messages.size == 1) "mensagem" else "mensagens"}",
                                    color = Cinema.muted, fontSize = 12.sp,
                                )
                            }
                        }
                        val photos = e.photos
                        if (photos.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(photos.take(8)) { p -> PhotoThumb(p, Modifier.size(64.dp)) { onOpen(e.id) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Uma sessão: capa, quem estava, as fotos e a conversa inteira. */
@Composable
fun DiaryEntryScreen(id: String, onBack: () -> Unit) {
    val entries by AppGraph.diary.entries.collectAsState()
    val e = entries.firstOrNull { it.id == id }
    if (e == null) { androidx.compose.runtime.LaunchedEffect(Unit) { onBack() }; return }
    var viewing by remember { mutableStateOf<ImageBitmap?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val me = PartyConfig.name

    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                TopBar(e.title, onBack) {
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Rounded.DeleteOutline, "Apagar do diário", tint = Cinema.text) }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(96.dp).aspectRatio(0.71f).clip(RoundedCornerShape(8.dp))) { CoverImage(e.cover, e.title) }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(withWhom(e, me).replaceFirstChar { it.uppercase() }, color = Cinema.accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Text(day(e.startedAt), color = Cinema.text, fontSize = 14.sp)
                        Text("das ${hour(e.startedAt)} às ${hour(e.endedAt)} · ${e.minutes} min", color = Cinema.muted, fontSize = 13.sp)
                    }
                }
            }
            val photos = e.photos
            if (photos.isNotEmpty()) {
                item { Text("Fotos", color = Cinema.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)) }
                items(photos.chunked(3)) { row ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { p -> PhotoThumb(p, Modifier.weight(1f).aspectRatio(1f)) { viewing = it } }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (e.messages.any { it.text.isNotBlank() }) {
                item { Text("Conversa", color = Cinema.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp)) }
                items(e.messages.filter { it.text.isNotBlank() }) { m ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                    ) {
                        Column(
                            Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(18.dp))
                                .background(if (m.mine) Cinema.accent else Cinema.surfaceHigh).padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            if (!m.mine) Text(m.name, color = personColor(m.name), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(m.text, color = if (m.mine) Cinema.onAccent else Cinema.text, fontSize = 15.sp)
                            Text(hour(m.at), color = (if (m.mine) Cinema.onAccent else Cinema.muted).copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.align(Alignment.End))
                        }
                    }
                }
            }
        }
    }
    viewing?.let { PhotoViewer(it) { viewing = null } }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text("Apagar esta sessão do diário?") },
        text = { Text("A conversa e as fotos guardadas desta sessão saem do celular.") },
        confirmButton = { TextButton(onClick = { deleting = false; AppGraph.diary.delete(e.id); onBack() }) { Text("Apagar", color = Cinema.red) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancelar") } },
    )
}
