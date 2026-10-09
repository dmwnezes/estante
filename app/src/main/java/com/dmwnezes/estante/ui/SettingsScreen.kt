package com.dmwnezes.estante.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.update.Updater

@Composable
fun SettingsScreen(onBack: () -> Unit, onCheckUpdate: () -> Unit, updateAvailable: Boolean) {
    val context = LocalContext.current
    val account by AppGraph.auth.account.collectAsState()
    var confirmLogout by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Cinema.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            TopBar("Ajustes", onBack)

            Section("Google Drive") {
                if (account.connected) {
                    Text(account.name ?: "Conta conectada", color = Cinema.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    account.email?.let { Text(it, color = Cinema.muted, fontSize = 14.sp) }
                    Spacer(Modifier.height(12.dp))
                    PillButton("Desconectar", null, { confirmLogout = true }, filled = false)
                } else {
                    Text("Não conectado", color = Cinema.muted, fontSize = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    ConnectDrive(onBack = null, compact = true)
                }
            }

            Section("Aparência da estante") {
                ThemePicker()
            }

            Section("Capas da internet") { TmdbSettings() }

            Section("Pastas sincronizadas") { SyncSettings() }

            Section("Configurar o Google (uma vez só)") {
                Text(
                    "Para o login funcionar, o app precisa estar registrado no Google Cloud como “Android”, com estes dois dados. Toque para copiar.",
                    color = Cinema.muted, fontSize = 14.sp,
                )
                Spacer(Modifier.height(10.dp))
                CopyRow(context, "Nome do pacote", AppGraph.auth.packageName)
                CopyRow(context, "Impressão digital SHA-1", AppGraph.auth.signingSha1())
                Text("O passo a passo completo está no README do projeto no GitHub.", color = Cinema.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Section("Atualizações") {
                Text("Versão instalada: ${Updater.currentName}", color = Cinema.muted, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                PillButton(if (updateAvailable) "Instalar nova versão" else "Buscar atualização", Icons.Rounded.SystemUpdate, onCheckUpdate, filled = updateAvailable)
            }

            Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                CreatorCredit()
            }
        }
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Desconectar o Google Drive?") },
        text = { Text("Os DVDs continuam na estante, mas os vídeos do Drive só tocam depois de conectar de novo.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; AppGraph.auth.disconnect() }) { Text("Desconectar", color = Cinema.red) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(Shapes.card).background(Cinema.surface).padding(20.dp)) {
        Text(title, color = Cinema.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun CopyRow(context: Context, label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Shapes.field).background(Cinema.surfaceHigh)
            .clickable {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText(label, value))
                Toast.makeText(context, "$label copiado", Toast.LENGTH_SHORT).show()
            }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Cinema.muted, fontSize = 12.sp)
            Text(value, color = Cinema.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Rounded.ContentCopy, "Copiar", tint = Cinema.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ThemePicker() {
    val current = ShelfThemes.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        ShelfThemes.all.forEach { t ->
            val selected = t.key == current.key
            Column(
                Modifier.weight(1f).clip(Shapes.field)
                    .background(t.wall)
                    .then(if (selected) Modifier.border(2.dp, Cinema.accent, Shapes.field) else Modifier)
                    .clickable { ShelfThemes.select(t) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                androidx.compose.runtime.CompositionLocalProvider(LocalShelfTheme provides t) {
                    Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        listOf(0xFFC8643F, 0xFFF0A94B, 0xFF5C6FA8).forEachIndexed { i, c ->
                            Box(Modifier.weight(1f).height(if (i == 1) 32.dp else 26.dp).clip(RoundedCornerShape(3.dp)).background(androidx.compose.ui.graphics.Color(c)))
                        }
                    }
                    Plank(Modifier.height(14.dp))
                }
                Text(t.name, color = Cinema.text, fontSize = 11.sp, maxLines = 2, lineHeight = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

@Composable
private fun TmdbSettings() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var key by remember { mutableStateOf(AppGraph.tmdb.key) }
    var status by remember { mutableStateOf<String?>(if (AppGraph.tmdb.hasKey) "Chave salva." else null) }
    var auto by remember { mutableStateOf(com.dmwnezes.estante.data.Importer.autoTmdb) }
    Text(
        "Pôsteres, sinopse, ano e gênero vêm do TMDB (themoviedb.org), que é grátis. Crie uma conta lá, vá em Configurações > API, peça uma chave (uso pessoal) e cole aqui a “Chave da API” ou o “Token de leitura”.",
        color = Cinema.muted, fontSize = 14.sp,
    )
    Spacer(Modifier.height(10.dp))
    PillButton("Abrir o site do TMDB", null, {
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.themoviedb.org/settings/api"))) }
    }, filled = false)
    Spacer(Modifier.height(12.dp))
    androidx.compose.material3.OutlinedTextField(
        value = key, onValueChange = { key = it }, singleLine = true, shape = Shapes.field,
        label = { Text("Chave do TMDB") }, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    PillButton("Salvar e testar", null, {
        AppGraph.tmdb.key = key
        status = "Testando…"
        scope.launch { status = if (AppGraph.tmdb.check()) "Chave funcionando!" else "A chave não foi aceita. Confira se copiou inteira." }
    }, enabled = key.isNotBlank())
    status?.let { Text(it, color = if (it.startsWith("A chave")) Cinema.red else Cinema.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    Spacer(Modifier.height(6.dp))
    CheckRow("Ao adicionar vídeos, buscar capa e sinopse sozinho (só quando o título bater certinho)", auto) {
        auto = !auto; com.dmwnezes.estante.data.Importer.autoTmdb = auto
    }
}

@Composable
private fun SyncSettings() {
    val state by AppGraph.library.state.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    if (state.syncs.isEmpty()) {
        Text(
            "Nenhuma ainda. No Google Drive, toque em “Tudo” numa pasta e marque “Sincronizar”: vídeos novos nela entram na estante sozinhos, sempre que você abrir o app.",
            color = Cinema.muted, fontSize = 14.sp,
        )
        return
    }
    state.syncs.forEach { f ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(Shapes.field).background(Cinema.surfaceHigh).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(f.name, color = Cinema.text, fontSize = 15.sp)
                val dest = f.boxId?.let { state.box(it)?.title?.let { t -> "série $t" } } ?: "prateleira ${f.shelf}"
                Text("→ $dest", color = Cinema.muted, fontSize = 12.sp)
            }
            androidx.compose.material3.IconButton(onClick = { AppGraph.library.removeSync(f.folderId) }) {
                Icon(androidx.compose.material.icons.Icons.Rounded.Close, "Parar de sincronizar", tint = Cinema.muted)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    PillButton(if (running) "Verificando…" else "Verificar agora", androidx.compose.material.icons.Icons.Rounded.Sync, {
        running = true
        scope.launch {
            val n = com.dmwnezes.estante.data.Importer.syncAll(force = true)
            result = when (n) { 0 -> "Nada novo."; 1 -> "1 vídeo novo na estante."; else -> "$n vídeos novos na estante." }
            running = false
        }
    }, enabled = !running, filled = false)
    result?.let { Text(it, color = Cinema.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
}
