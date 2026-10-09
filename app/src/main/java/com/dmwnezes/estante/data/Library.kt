package com.dmwnezes.estante.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * A estante inteira (vídeos, séries, listas e pastas sincronizadas), guardada num JSON
 * dentro do app. Toda mudança atualiza [state] na hora e grava o arquivo em segundo plano.
 */
class Library(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    val current: LibraryState get() = _state.value

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private fun load(): LibraryState = runCatching {
        if (file.exists()) LibraryState.fromJson(JSONObject(file.readText())) else LibraryState()
    }.getOrElse {
        // Arquivo corrompido: guarda uma cópia e começa de novo, sem travar o app.
        runCatching { file.copyTo(File(file.parentFile, file.name + ".quebrado"), overwrite = true) }
        LibraryState()
    }

    private fun change(block: (LibraryState) -> LibraryState) {
        _state.update(block)
        val snapshot = _state.value
        io.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(snapshot.toJson().toString())
                if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
            }
        }
    }

    fun newId(): String = UUID.randomUUID().toString()

    /** Próxima posição no fim de uma prateleira (ordem manual). */
    private fun nextOrder(s: LibraryState, shelf: String): Double =
        (s.shelfItems.filter { it.shelf == shelf }.maxOfOrNull { it.order } ?: 0.0) + 1.0

    // ---------- vídeos ----------

    /** Coloca vídeos novos na estante (ignora os que já estão lá). Devolve os que entraram. */
    fun add(items: List<Video>): List<Video> {
        var fresh: List<Video> = emptyList()
        change { s ->
            val existing = s.videos.map { it.source to it.ref }.toSet()
            val orders = mutableMapOf<String, Double>()
            fresh = items.filter { (it.source to it.ref) !in existing }.distinctBy { it.source to it.ref }
                .mapIndexed { i, v ->
                    val o = if (v.boxId != null) 0.0 else (orders[v.shelf] ?: nextOrder(s, v.shelf)).also { orders[v.shelf] = it + 1.0 }
                    v.copy(addedAt = if (v.addedAt > 0) v.addedAt else clock() + i, order = o)
                }
            if (fresh.isEmpty()) s else s.copy(videos = s.videos + fresh)
        }
        return fresh
    }

    fun isOnShelf(source: Source, ref: String): Boolean = current.videos.any { it.source == source && it.ref == ref }

    fun update(id: String, block: (Video) -> Video) = change { s ->
        s.copy(videos = s.videos.map { if (it.id == id) block(it) else it })
    }

    /** Tira da estante, apaga a capa salva e remove das listas. */
    fun remove(id: String) {
        current.video(id)?.cover?.let { runCatching { File(it).delete() } }
        change { s ->
            s.copy(
                videos = s.videos.filterNot { it.id == id },
                playlists = s.playlists.map { p -> p.copy(videoIds = p.videoIds.filterNot { it == id }) },
            )
        }
    }

    fun savePosition(id: String, positionMs: Long, durationMs: Long) =
        update(id) { Resume.record(it, positionMs, durationMs, clock()) }

    /** Troca a capa, apagando o arquivo da anterior. */
    fun setCover(id: String, path: String?) {
        val old = current.video(id)?.cover
        if (old != null && old != path) runCatching { File(old).delete() }
        update(id) { it.copy(cover = path) }
    }

    fun renameShelf(from: String, to: String) {
        val name = to.trim().ifBlank { return }
        change { s ->
            s.copy(
                videos = s.videos.map { if (it.shelf == from) it.copy(shelf = name) else it },
                boxes = s.boxes.map { if (it.shelf == from) it.copy(shelf = name) else it },
            )
        }
    }

    /**
     * Arrastar e soltar: põe o item (DVD ou série) na prateleira [shelf], logo antes de
     * [beforeId] (ou no fim, se nulo). Renumera a prateleira para a ordem ficar fixa.
     */
    fun move(itemId: String, shelf: String, beforeId: String?) = change { s ->
        val items = s.shelfItems
        val moving = items.firstOrNull { it.id == itemId } ?: return@change s
        val sorted = items.filter { it.shelf == shelf && it.id != itemId }.sortedBy(SortOrder.MANUAL).toMutableList()
        val at = beforeId?.let { b -> sorted.indexOfFirst { it.id == b } }?.takeIf { it >= 0 } ?: sorted.size
        sorted.add(at, moving)
        val newOrder = sorted.mapIndexed { i, it -> it.id to (i + 1).toDouble() }.toMap()
        s.copy(
            videos = s.videos.map { v -> newOrder[v.id]?.let { v.copy(order = it, shelf = shelf) } ?: v },
            boxes = s.boxes.map { b -> newOrder[b.id]?.let { b.copy(order = it, shelf = shelf) } ?: b },
        )
    }

    /** Fixa a ordem atual (por título, por exemplo) como "minha ordem" antes de começar a arrastar. */
    fun freezeOrder(order: SortOrder) = change { s ->
        val newOrder = s.shelfItems.groupBy { it.shelf }.values
            .flatMap { list -> list.sortedBy(order).mapIndexed { i, it -> it.id to (i + 1).toDouble() } }.toMap()
        s.copy(
            videos = s.videos.map { v -> newOrder[v.id]?.let { v.copy(order = it) } ?: v },
            boxes = s.boxes.map { b -> newOrder[b.id]?.let { b.copy(order = it) } ?: b },
        )
    }

    // ---------- séries ----------

    fun createBox(title: String, shelf: String, folderId: String? = null): Box {
        var box = Box(newId(), title.trim().ifBlank { "Série" }, shelf = shelf, addedAt = clock(), folderId = folderId)
        change { s ->
            box = box.copy(order = nextOrder(s, shelf))
            s.copy(boxes = s.boxes + box)
        }
        return box
    }

    fun updateBox(id: String, block: (Box) -> Box) = change { s ->
        s.copy(boxes = s.boxes.map { if (it.id == id) block(it) else it })
    }

    fun setBoxCover(id: String, path: String?) {
        val old = current.box(id)?.cover
        if (old != null && old != path) runCatching { File(old).delete() }
        updateBox(id) { it.copy(cover = path) }
    }

    /** Junta vídeos que já estão na estante numa série. */
    fun putInBox(boxId: String, videoIds: List<String>) = change { s ->
        s.copy(videos = s.videos.map { if (it.id in videoIds) it.copy(boxId = boxId) else it })
    }

    fun takeOutOfBox(videoId: String) = change { s ->
        val v = s.video(videoId) ?: return@change s
        val shelf = v.boxId?.let { s.box(it)?.shelf } ?: v.shelf
        s.copy(videos = s.videos.map { if (it.id == videoId) it.copy(boxId = null, shelf = shelf, order = nextOrder(s, shelf)) else it })
    }

    /**
     * Desfaz a série. [keepEpisodes] = os episódios voltam como DVDs soltos na mesma prateleira;
     * senão saem da estante junto.
     */
    fun deleteBox(id: String, keepEpisodes: Boolean) {
        val s0 = current
        val box = s0.box(id) ?: return
        box.cover?.let { runCatching { File(it).delete() } }
        if (!keepEpisodes) s0.episodesOf(id).forEach { e -> e.cover?.let { runCatching { File(it).delete() } } }
        change { s ->
            val epIds = s.videos.filter { it.boxId == id }.map { it.id }.toSet()
            var o = nextOrder(s, box.shelf)
            s.copy(
                boxes = s.boxes.filterNot { it.id == id },
                syncs = s.syncs.filterNot { it.boxId == id },
                videos = if (keepEpisodes) s.videos.map { if (it.id in epIds) it.copy(boxId = null, shelf = box.shelf, order = o++) else it }
                else s.videos.filterNot { it.id in epIds },
                playlists = if (keepEpisodes) s.playlists else s.playlists.map { p -> p.copy(videoIds = p.videoIds.filterNot { it in epIds }) },
            )
        }
    }

    // ---------- pastas sincronizadas ----------

    fun addSync(folder: SyncFolder) = change { s ->
        s.copy(syncs = s.syncs.filterNot { it.folderId == folder.folderId } + folder)
    }

    fun removeSync(folderId: String) = change { s -> s.copy(syncs = s.syncs.filterNot { it.folderId == folderId }) }

    fun markSynced(folderId: String) = change { s ->
        s.copy(syncs = s.syncs.map { if (it.folderId == folderId) it.copy(lastSync = clock()) else it })
    }

    // ---------- listas ----------

    fun createPlaylist(name: String, videoIds: List<String> = emptyList()): Playlist {
        val p = Playlist(newId(), name.trim().ifBlank { "Nova lista" }, videoIds.distinct(), clock())
        change { it.copy(playlists = it.playlists + p) }
        return p
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name.trim().ifBlank { it.name }) }

    fun deletePlaylist(id: String) = change { s -> s.copy(playlists = s.playlists.filterNot { it.id == id }) }

    fun addToPlaylist(playlistId: String, videoIds: List<String>) = editPlaylist(playlistId) { p ->
        p.copy(videoIds = p.videoIds + videoIds.filterNot { it in p.videoIds })
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) = editPlaylist(playlistId) { p ->
        p.copy(videoIds = p.videoIds.filterNot { it == videoId })
    }

    /** Move o item da posição [from] para [to] dentro da lista. */
    fun moveInPlaylist(playlistId: String, from: Int, to: Int) = editPlaylist(playlistId) { p ->
        if (from !in p.videoIds.indices || to !in p.videoIds.indices) p
        else p.copy(videoIds = p.videoIds.toMutableList().apply { add(to, removeAt(from)) })
    }

    private fun editPlaylist(id: String, block: (Playlist) -> Playlist) = change { s ->
        s.copy(playlists = s.playlists.map { if (it.id == id) block(it) else it })
    }
}
