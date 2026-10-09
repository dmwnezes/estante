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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.dmwnezes.estante.data.LibraryState
import com.dmwnezes.estante.data.Resume
import com.dmwnezes.estante.data.SortOrder
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.data.formatTime
import com.dmwnezes.estante.data.sortedBy

/** Tela principal: a estante com as prateleiras de DVDs. */
@Composable
fun ShelfScreen(
    state: LibraryState,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onOpen: (Video) -> Unit,
    onEdit: (Video) -> Unit,
    onResume: (Video) -> Unit,
    onAddDrive: () -> Unit,
    onAddLocal: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    updateAvailable: Boolean = false,
) {
    val columns = if (LocalConfiguration.current.screenWidthDp >= 600) 5 else 3
    var addMenu by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 120.dp),
        ) {
            item { Header(state, sort, onSort, onPlaylists, onSettings, updateAvailable) }

            if (state.videos.isEmpty()) {
                item { EmptyShelf(onAddDrive, onAddLocal) }
            } else {
                val going = state.continueWatching
                if (going.isNotEmpty()) {
                    item {
                        Text(
                            "Continuar assistindo",
                            color = Cinema.text, fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                            modifier = Modifier.padding(start = 18.dp, top = 6.dp, bottom = 8.dp),
                        )
                        ContinueRow(going, onResume)
                    }
                }
                state.shelves.forEach { shelf ->
                    val list = state.videos.filter { it.shelf == shelf }.sortedBy(sort)
                    item(key = "label-$shelf") { ShelfLabel(shelf, list.size, Modifier.padding(top = 8.dp)) }
                    list.chunked(columns).forEachIndexed { i, row ->
                        item(key = "row-$shelf-$i") { ShelfRow(row, columns, onOpen, onEdit) }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp)) {
            ExtendedFloatingActionButton(
                onClick = { addMenu = true },
                containerColor = Cinema.accent,
                contentColor = Cinema.onAccent,
                shape = Shapes.pill,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Adicionar", fontWeight = FontWeight.SemiBold) },
            )
            DropdownMenu(addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Do Google Drive") },
                    leadingIcon = { Icon(Icons.Rounded.AddToDrive, null) },
                    onClick = { addMenu = false; onAddDrive() },
                )
                DropdownMenuItem(
                    text = { Text("Do celular") },
                    leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) },
                    onClick = { addMenu = false; onAddLocal() },
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: LibraryState,
    sort: SortOrder,
    onSort: (SortOrder) -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
    updateAvailable: Boolean,
) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Estante", color = Cinema.text, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
            val n = state.videos.size
            Text(
                when (n) { 0 -> "Nenhum DVD ainda"; 1 -> "1 DVD"; else -> "$n DVDs" } +
                    if (state.playlists.isNotEmpty()) " · ${state.playlists.size} ${if (state.playlists.size == 1) "lista" else "listas"}" else "",
                color = Cinema.muted, fontSize = 13.sp,
            )
        }
        if (state.videos.isNotEmpty()) {
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
            if (updateAvailable) {
                Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(9.dp).clip(CircleShape).background(Cinema.accent))
            }
        }
    }
}

/** Fileira que rola de lado com os vídeos começados, sobre uma tábua fixa. */
@Composable
private fun ContinueRow(videos: List<Video>, onResume: (Video) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        LazyRow(
            Modifier.fillMaxWidth().offset(y = 5.dp).zIndex(1f),
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(videos, key = { it.id }) { v ->
                DvdCase(v, Modifier.width(104.dp), onClick = { onResume(v) })
            }
        }
        Plank()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            userScrollEnabled = false,
        ) {
            items(videos.take(8), key = { "t" + it.id }) { v ->
                Column(Modifier.width(104.dp)) {
                    Text(v.title, color = Cinema.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    Text(
                        "parou em ${formatTime(Resume.startAt(v))}",
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
