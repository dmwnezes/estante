package com.dmwnezes.estante.update

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dmwnezes.estante.AppGraph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Janela de atualização: busca a versão nova, baixa e abre o instalador.
 * [initial] permite abrir já com uma versão encontrada na checagem automática.
 */
@Composable
fun UpdateDialog(initial: Release? = null, onSkip: (Release) -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val updater = remember { Updater(AppGraph.http) }
    var state by remember { mutableStateOf<UpdateState>(initial?.let { UpdateState.Available(it) } ?: UpdateState.Checking) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun check() {
        state = UpdateState.Checking
        job = scope.launch {
            state = runCatching { updater.latest() }.fold(
                { r -> if (updater.isNewer(r)) UpdateState.Available(r) else UpdateState.UpToDate },
                { UpdateState.Failed("Não consegui verificar agora. Confira a internet.") },
            )
        }
    }

    fun download(r: Release) {
        state = UpdateState.Downloading(r, 0f)
        job = scope.launch {
            state = runCatching { updater.download(context, r) { p -> state = UpdateState.Downloading(r, p) } }.fold(
                { f -> UpdateState.Ready(r, f) },
                { UpdateState.Failed("O download falhou. Tente de novo.") },
            )
        }
    }

    fun install(s: UpdateState.Ready) {
        if (!updater.canInstall(context)) updater.openInstallPermission(context)
        else updater.install(context, s.apk)
    }

    LaunchedEffect(Unit) { if (initial == null) check() }

    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = {
            Text(
                when (val s = state) {
                    is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready -> "Nova versão disponível"
                    UpdateState.UpToDate -> "Você já está na versão mais nova"
                    is UpdateState.Failed -> "Atualização"
                    UpdateState.Checking -> "Buscando atualizações…"
                }
            )
        },
        text = {
            Column {
                Text("Versão instalada: ${Updater.currentName}", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                when (val s = state) {
                    UpdateState.Checking -> {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)))
                    }
                    UpdateState.UpToDate -> {}
                    is UpdateState.Failed -> Text(s.message, modifier = Modifier.heightIn(min = 24.dp))
                    is UpdateState.Available -> Notes(s.release)
                    is UpdateState.Downloading -> {
                        Notes(s.release)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(3.dp)))
                        Text("Baixando… ${(s.progress * 100).toInt()}%", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
                    }
                    is UpdateState.Ready -> {
                        Notes(s.release)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            if (updater.canInstall(context)) "Pronto! Toque em Instalar."
                            else "Na primeira vez, o Android pede para você permitir que a Estante instale atualizações. Ative e volte aqui para tocar em Instalar.",
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Available -> TextButton(onClick = { download(s.release) }) {
                    Text("Baixar e instalar (${s.release.sizeBytes / 1_048_576} MB)")
                }
                is UpdateState.Ready -> TextButton(onClick = { install(s) }) { Text("Instalar") }
                is UpdateState.Failed -> TextButton(onClick = ::check) { Text("Tentar de novo") }
                is UpdateState.Downloading, UpdateState.Checking -> {}
                UpdateState.UpToDate -> TextButton(onClick = onDismiss) { Text("Ok") }
            }
        },
        dismissButton = {
            if (state !is UpdateState.UpToDate) TextButton(onClick = {
                job?.cancel()
                (state as? UpdateState.Available)?.let { onSkip(it.release) }
                onDismiss()
            }) { Text("Agora não") }
        },
    )
}

@Composable
private fun Notes(r: Release) {
    Text("Nova versão: ${r.tag.removePrefix("v")}", fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
    if (r.notes.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Text("O que mudou:", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            Text(r.notes, fontSize = 14.sp)
        }
    }
}
