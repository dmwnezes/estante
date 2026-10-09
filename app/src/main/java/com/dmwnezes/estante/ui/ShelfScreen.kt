package com.dmwnezes.estante.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.ShelfItem
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatTime
import com.dmwnezes.estante.data.sortedBy
import java.text.Normalizer
import kotlin.math.roundToInt

private fun searchKey(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")

/** Filtra pela busca: título do DVD, da série ou de qualquer episódio. */
fun filterItems(items: List<ShelfItem>, query: String): List<ShelfItem> {
    val q = searchKey(query.trim())
    if (q.isBlank()) return items
    return items.filter { item ->
        searchKey(item.title).contains(q) || (item is ShelfItem.Series && item.episodes.any { searchKey(it.title).contains(q) })
    }
}

/** Para onde o DVD arrastado vai: prateleira e antes de qual item (nulo = no fim). */
data class DropTarget(val shelf: String, val beforeId: String?, val highlightId: String?)

/** Tela principal: a estante com as prateleiras de DVDs e séries. */
@Composable
fun ShelfScreen(
    state: LibraryState,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onOpen: (ShelfItem) -> Unit,
    onEdit: (ShelfItem) -> Unit,
    onResume: (Video) -> Unit,
    onAddDrive: () -> Unit,
    onAddLocal: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    updateAvailable: Boolean = false,
    onMove: (itemId: String, shelf: String, beforeId: String?) -> Unit = { _, _, _ -> },
    theme: ShelfTheme = ShelfThemes.current,
) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 5 else 3
    var addMenu by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // Posições na tela, para saber onde o dedo está durante o arrastar.
    val slots = remember { mutableStateMapOf<String, Rect>() }           // id do item → caixa
    val rows = remember { mutableStateMapOf<String, Pair<String, Rect>>() } // chave da fileira → (prateleira, área)
    val labels = remember { mutableStateMapOf<String, Rect>() }          // prateleira → plaquinha
    var origin by remember { mutableStateOf(Offset.Zero) }

    var dragged by remember { mutableStateOf<ShelfItem?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var dragSize by remember { mutableStateOf(Rect.Zero) }
    var target by remember { mutableStateOf<DropTarget?>(null) }

    val all = state.shelfItems
    val filtered = filterItems(all, query)
    val shelvesShown: List<Pair<String, List<ShelfItem>>> =
        if (query.isNotBlank()) listOf("Resultados" to filtered.sortedBy(sort))
        else state.shelves.map { sh -> sh to all.filter { it.shelf == sh }.sortedBy(sort) }

    fun itemAt(p: Offset): ShelfItem? {
        val root = p + origin
        val id = slots.entries.firstOrNull { it.value.contains(root) }?.key ?: return null
        return all.firstOrNull { it.id == id }
    }

    fun computeTarget(p: Offset): DropTarget? {
        val root = p + origin
        val moving = dragged ?: return null
        // Em cima de um DVD: entra antes dele (metade esquerda) ou depois (metade direita).
        slots.entries.firstOrNull { it.value.contains(root) && it.key != moving.id }?.let { (id, r) ->
            val item = all.firstOrNull { it.id == id } ?: return null
            val shelfList = shelvesShown.firstOrNull { it.first == item.shelf }?.second ?: return null
            val i = shelfList.indexOfFirst { it.id == id }
            val before = if (root.x < r.center.x) id else shelfList.drop(i + 1).firstOrNull { it.id != moving.id }?.id
            return DropTarget(item.shelf, before, id)
        }
        // Num espaço vazio da fileira: vai para depois do último DVD dela.
        rows.values.firstOrNull { it.second.contains(root) }?.let { (shelf, _) ->
            val shelfList = shelvesShown.firstOrNull { it.first == shelf }?.second.orEmpty()
            val rowKeyItems = rows.entries.firstOrNull { it.value.second.contains(root) }?.key
            val rowIndex = rowKeyItems?.substringAfterLast('#')?.toIntOrNull() ?: return DropTarget(shelf, null, null)
            val after = shelfList.chunked(columns).getOrNull(rowIndex)?.lastOrNull()
            val idx = shelfList.indexOfFirst { it.id == after?.id }
            val before = if (idx >= 0) shelfList.drop(idx + 1).firstOrNull { it.id != moving.id }?.id else null
            return DropTarget(shelf, before, null)
        }
        // Na plaquinha: vai para o fim daquela prateleira.
        labels.entries.firstOrNull { it.value.inflate(16f).contains(root) }?.let { return DropTarget(it.key, null, null) }
        return null
    }

    // Rolagem automática quando o dedo encosta em cima ou embaixo da tela.
    LaunchedEffect(dragged != null) {
        if (dragged == null) return@LaunchedEffect
        val edge = with(density) { 90.dp.toPx() }
        while (dragged != null) {
            withFrameNanos { }
            val h = listState.layoutInfo.viewportSize.height
            val y = dragPos.y
            val speed = when {
                y < edge -> -(edge - y) / 6f
                y > h - edge -> (y - (h - edge)) / 6f
                else -> 0f
            }
            if (speed != 0f) { listState.scrollBy(speed); target = computeTarget(dragPos) }
        }
    }

    CompositionLocalProvider(LocalShelfTheme provides theme) {
        Box(
            Modifier.fillMaxSize().background(theme.wall)
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(columns, query, sort, all) {
                    // Segurar e arrastar um DVD (a busca fica de fora: nela a ordem não é a da estante).
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val item = itemAt(down.position) ?: return@awaitEachGesture
                        val pressed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                                if (!c.pressed) return@withTimeoutOrNull false
                                if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                            }
                            @Suppress("UNREACHABLE_CODE") false
                        }
                        if (pressed != null) return@awaitEachGesture // soltou ou mexeu antes: toque normal ou rolagem
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (query.isNotBlank()) {
                            // Na busca, segurar só abre a edição.
                            down.consume(); onEdit(item); return@awaitEachGesture
                        }
                        dragged = item
                        dragSize = slots[item.id] ?: Rect.Zero
                        dragPos = down.position
                        var moved = 0f
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Initial)
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            c.consume()
                            if (!c.pressed) break
                            moved = maxOf(moved, (c.position - down.position).getDistance())
                            dragPos = c.position
                            target = computeTarget(c.position)
                        }
                        val t = target
                        val moving = dragged
                        dragged = null
                        target = null
                        if (moving == null) return@awaitEachGesture
                        if (moved < viewConfiguration.touchSlop * 2) onEdit(moving)
                        else if (t != null && !(t.shelf == moving.shelf && t.beforeId == moving.id)) {
                            onMove(moving.id, t.shelf, t.beforeId)
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                },
        ) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding(),
                state = listState,
                contentPadding = PaddingValues(bottom = 120.dp),
            ) {
                item(key = "header") {
                    Header(state, sort, onSort, onPlaylists, onSettings, updateAvailable, searching, onSearch = { searching = !searching; if (!searching) query = "" })
                }
                if (searching) item(key = "search") { SearchField(query) { query = it } }

                if (all.isEmpty()) {
                    item(key = "empty") { EmptyShelf(onAddDrive, onAddLocal) }
                } else {
                    val going = state.continueWatching
                    if (going.isNotEmpty() && query.isBlank()) {
                        item(key = "continue") {
                            Text(
                                "Continuar assistindo", color = Cinema.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                                modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp),
                            )
                            ContinueRow(going, state, onResume)
                        }
                    }
                    if (query.isNotBlank() && filtered.isEmpty()) {
                        item(key = "none") {
                            Text("Nada encontrado para “$query”.", color = Cinema.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp))
                        }
                    }
                    shelvesShown.forEach { (shelf, list) ->
                        if (list.isEmpty()) return@forEach
                        item(key = "label-$shelf") {
                            ShelfLabel(shelf, list.size, Modifier.padding(top = 8.dp).onGloballyPositioned { labels[shelf] = it.boundsInRoot() })
                        }
                        list.chunked(columns).forEachIndexed { i, row ->
                            item(key = "row-$shelf-${row.first().id}") {
                                ItemRow(
                                    row, columns,
                                    rowKey = "$shelf#$i",
                                    draggedId = dragged?.id, highlightId = target?.highlightId,
                                    onOpen = onOpen,
                                    onSlot = { id, r -> slots[id] = r },
                                    onRow = { key, r -> rows[key] = shelf to r },
                                )
                            }
                        }
                    }
                }
            }

            // O DVD "na mão" enquanto arrasta.
            dragged?.let { d ->
                val w = with(density) { dragSize.width.toDp() }
                ItemCase(
                    d,
                    Modifier.width(w).zIndex(10f)
                        .offset { IntOffset((dragPos.x - dragSize.width / 2).roundToInt(), (dragPos.y - dragSize.height / 2).roundToInt()) }
                        .graphicsLayer { scaleX = 1.1f; scaleY = 1.1f; rotationZ = -4f; shadowElevation = 30f },
                )
            }

            AnimatedVisibility(dragged == null, Modifier.align(Alignment.BottomEnd)) {
                Box(Modifier.navigationBarsPadding().padding(20.dp)) {
                    ExtendedFloatingActionButton(
                        onClick = { addMenu = true },
                        containerColor = Cinema.accent, contentColor = Cinema.onAccent, shape = Shapes.pill,
                        icon = { Icon(Icons.Rounded.Add, null) },
                        text = { Text("Adicionar", fontWeight = FontWeight.SemiBold) },
                    )
                    DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                        DropdownMenuItem(text = { Text("Do Google Drive") }, leadingIcon = { Icon(Icons.Rounded.AddToDrive, null) }, onClick = { addMenu = false; onAddDrive() })
                        DropdownMenuItem(text = { Text("Do celular") }, leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) }, onClick = { addMenu = false; onAddLocal() })
                    }
                }
            }
        }
    }
}

/** Uma fileira: até [columns] itens em pé sobre a tábua, com os títulos embaixo. */
@Composable
private fun ItemRow(
    items: List<ShelfItem>,
    columns: Int,
    rowKey: String,
    draggedId: String?,
    highlightId: String?,
    onOpen: (ShelfItem) -> Unit,
    onSlot: (String, Rect) -> Unit,
    onRow: (String, Rect) -> Unit,
) {
    Column(Modifier.fillMaxWidth().onGloballyPositioned { onRow(rowKey, it.boundsInRoot()) }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp).offset(y = 5.dp).zIndex(1f),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 0 until columns) {
                val item = items.getOrNull(i)
                Box(Modifier.weight(1f)) {
                    if (item != null) {
                        val hl = item.id == highlightId
                        ItemCase(
                            item,
                            Modifier.fillMaxWidth()
                                .onGloballyPositioned { onSlot(item.id, it.boundsInRoot()) }
                                .graphicsLayer { alpha = if (item.id == draggedId) 0.25f else 1f; val s = if (hl) 0.94f else 1f; scaleX = s; scaleY = s }
                                .then(if (hl) Modifier.border(2.dp, Cinema.accent, Shapes.case) else Modifier),
                            onClick = { onOpen(item) },
                        )
                    }
                }
            }
        }
        Plank()
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            for (i in 0 until columns) {
                Box(Modifier.weight(1f)) {
                    items.getOrNull(i)?.let { item ->
                        Column(Modifier.fillMaxWidth()) {
                            Text(
                                item.title, color = Cinema.muted, fontSize = 12.sp, lineHeight = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                            )
                            itemCaption(item)?.let {
                                Text(it, color = Cinema.accent.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    OutlinedTextField(
        value = query, onValueChange = onChange,
        placeholder = { Text("Buscar na estante") }, singleLine = true, shape = Shapes.field,
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Limpar") } },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).focusRequester(focus),
    )
}

@Composable
private fun Header(
    state: LibraryState,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    updateAvailable: Boolean,
    searching: Boolean,
    onSearch: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Estante", color = Cinema.text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            val singles = state.videos.count { it.boxId == null || state.box(it.boxId) == null }
            val parts = buildList {
                add(when (singles) { 0 -> "Nenhum DVD"; 1 -> "1 DVD"; else -> "$singles DVDs" })
                if (state.boxes.isNotEmpty()) add(if (state.boxes.size == 1) "1 série" else "${state.boxes.size} séries")
                if (state.playlists.isNotEmpty()) add(if (state.playlists.size == 1) "1 lista" else "${state.playlists.size} listas")
            }
            Text(parts.joinToString(" · "), color = Cinema.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (state.videos.isNotEmpty()) {
            IconButton(onClick = onSearch) { Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Buscar", tint = Cinema.text) }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, "Ordenar", tint = Cinema.text) }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortOrder.entries.forEach { o ->
                        DropdownMenuItem(
                            text = { Text(o.label) },
                            leadingIcon = { if (o == sort) Icon(Icons.Rounded.Check, null) else Spacer(Modifier.size(24.dp)) },
                            onClick = { sortMenu = false; onSort(o) },
                        )
                    }
                }
            }
        }
        IconButton(onClick = onPlaylists) { Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, "Listas de reprodução", tint = Cinema.text) }
        Box {
            IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Ajustes", tint = Cinema.text) }
            if (updateAvailable) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(9.dp).clip(CircleShape).background(Cinema.accent))
        }
    }
}

/** Fileira que rola de lado com os vídeos começados, sobre uma tábua fixa. */
@Composable
private fun ContinueRow(videos: List<Video>, state: LibraryState, onResume: (Video) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        LazyRow(
            Modifier.fillMaxWidth().offset(y = 5.dp).zIndex(1f),
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(videos, key = { it.id }) { v ->
                val box = v.boxId?.let { state.box(it) }
                val shown = if (box != null && v.cover == null) v.copy(cover = box.cover, title = box.title) else v
                DvdCase(shown, Modifier.width(104.dp), onClick = { onResume(v) })
            }
        }
        Plank()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(videos.take(8), key = { "t" + it.id }) { v ->
                val box = v.boxId?.let { state.box(it) }
                Column(Modifier.width(104.dp)) {
                    Text(box?.title ?: v.title, color = Cinema.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        if (box != null && v.episodeLabel.isNotBlank()) "${v.episodeLabel} · ${formatTime(Resume.startAt(v))}" else "parou em ${formatTime(Resume.startAt(v))}",
                        color = Cinema.accent.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** Estante vazia: uma tábua sem nada e os dois jeitos de começar. */
@Composable
private fun EmptyShelf(onAddDrive: () -> Unit, onAddLocal: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().height(120.dp))
        Plank(Modifier.padding(horizontal = 30.dp))
        Spacer(Modifier.height(28.dp))
        Text("Sua estante está vazia", color = Cinema.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Traga seus vídeos do Google Drive ou do celular. Cada um vira um DVD, com capa e título do seu jeito.",
            color = Cinema.muted, fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 36.dp),
        )
        Spacer(Modifier.height(22.dp))
        PillButton("Escolher no Google Drive", Icons.Rounded.AddToDrive, onAddDrive)
        Spacer(Modifier.height(10.dp))
        PillButton("Escolher no celular", Icons.Rounded.PhoneAndroid, onAddLocal, filled = false)
    }
}

@Composable
fun PillButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    enabled: Boolean = true,
) {
    val bg = if (filled) Cinema.accent else Cinema.surfaceHigh
    val fg = if (filled) Cinema.onAccent else Cinema.text
    Row(
        modifier
            .clip(Shapes.pill)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 22.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = if (enabled) fg else fg.copy(alpha = 0.6f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
