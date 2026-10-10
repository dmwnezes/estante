package com.dmwnezes.estante.party

import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.data.Video
import com.dmwnezes.estante.drive.DriveAuth
import com.dmwnezes.estante.drive.DriveShare
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quando o Google bloqueia o download de um filme pelo link ("limite de downloads"), quem é
 * dono continua vendo normal, mas o site não. Uma cópia do arquivo tem limite novo: o app faz a
 * cópia, libera pelo link e a sala passa a tocar a cópia. Quando a sala fecha, a cópia vai para
 * a lixeira.
 */
object PartyFix {

    /** Nome da cópia: o do arquivo original com "(cópia da sala)" antes da extensão. */
    fun copyName(video: Video): String {
        val name = video.fileName?.takeIf { it.isNotBlank() } ?: return "${video.title} (cópia da sala)"
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} (cópia da sala)${name.substring(dot)}" else "$name (cópia da sala)"
    }

    /** Faz a cópia, libera pelo link e espera o Google aceitar. Devolve o id da cópia. */
    suspend fun freshCopy(token: String, video: Video): String {
        val id = DriveShare.copy(AppGraph.http, token, video.ref, copyName(video))
        PartyConfig.roomCopies = PartyConfig.roomCopies + id
        DriveShare.makePublic(AppGraph.http, token, id)
        repeat(8) {
            if (PartyConfig.checkShared(id) == ShareCheck.Ok && PartyConfig.checkDownload(id) == ShareCheck.Ok) return id
            delay(1_500)
        }
        return id
    }

    /** Joga na lixeira as cópias feitas para salas que já fecharam (em segundo plano, sem tela). */
    fun cleanupLater() {
        // Só as que existem agora: uma cópia feita depois (sala nova abrindo) não pode ir junto.
        val ids = PartyConfig.roomCopies
        if (ids.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            val auth = runCatching { AppGraph.auth.beginShare() }.getOrNull() as? DriveAuth.ShareAuth.Token ?: return@launch
            for (id in ids) {
                runCatching { DriveShare.trash(AppGraph.http, auth.token, id) }
                    .onSuccess { PartyConfig.roomCopies = PartyConfig.roomCopies - id }
            }
        }
    }
}
