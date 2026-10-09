package com.dmwnezes.estante.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.dmwnezes.estante.AppGraph
import com.dmwnezes.estante.drive.DriveItem
import com.dmwnezes.estante.ui.thumbnailFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Coloca vídeos na estante e busca as capas em segundo plano. */
object Importer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Vídeos do Google Drive. Devolve quantos entraram (os repetidos são ignorados). */
    fun addDrive(items: List<DriveItem>, shelf: String): Int {
        val lib = AppGraph.library
        val byId = items.associateBy { it.id }
        val added = lib.add(items.filterNot { it.isFolder }.map {
            Video(
                id = lib.newId(), source = Source.DRIVE, ref = it.id,
                title = titleFromFileName(it.name), shelf = shelf,
                durationMs = it.durationMs, sizeBytes = it.sizeBytes,
            )
        })
        scope.launch {
            added.forEach { v ->
                val path = AppGraph.covers.fromDriveThumbnail(byId[v.ref]?.thumbnail ?: thumbnailFor(v.ref))
                if (path != null && lib.current.video(v.id)?.cover == null) lib.setCover(v.id, path)
            }
        }
        return added.size
    }

    /** Vídeos escolhidos no celular. O app guarda a permissão de abrir o arquivo depois. */
    fun addLocal(context: Context, uris: List<Uri>, shelf: String): Int {
        val lib = AppGraph.library
        val cr = context.contentResolver
        val items = uris.map { uri ->
            runCatching { cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = uri.lastPathSegment ?: "Vídeo"
            var size = 0L
            runCatching {
                cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        size = c.getLong(1)
                    }
                }
            }
            Video(id = lib.newId(), source = Source.LOCAL, ref = uri.toString(), title = titleFromFileName(name), shelf = shelf, sizeBytes = size)
        }
        val added = lib.add(items)
        scope.launch {
            added.forEach { v ->
                val uri = Uri.parse(v.ref)
                val dur = AppGraph.covers.localDuration(uri)
                if (dur > 0) lib.update(v.id) { it.copy(durationMs = dur) }
                val path = AppGraph.covers.fromLocalFrame(uri)
                if (path != null && lib.current.video(v.id)?.cover == null) lib.setCover(v.id, path)
            }
        }
        return added.size
    }
}
