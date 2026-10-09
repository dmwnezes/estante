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
 * A estante inteira (vídeos e listas), guardada num arquivo JSON dentro do app.
 * Toda mudança atualiza [state] na hora e grava o arquivo em segundo plano.
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

    // ---------- vídeos ----------

    /** Coloca vídeos novos na estante (ignora os que já estão lá). Devolve os que entraram. */
    fun add(items: List<Video>): List<Video> {
        val existing = current.videos.map { it.source to it.ref }.toSet()
        val fresh = items.filter { (it.source to it.ref) !in existing }
            .mapIndexed { i, v -> v.copy(addedAt = if (v.addedAt > 0) v.addedAt else clock() + i) }
        if (fresh.isNotEmpty()) change { it.copy(videos = it.videos + fresh) }
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
        change { s -> s.copy(videos = s.videos.map { if (it.shelf == from) it.copy(shelf = name) else it }) }
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
