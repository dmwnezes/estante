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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.Normalizer

/** Coloca vídeos na estante, monta séries e busca capas em segundo plano. */
object Importer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncLock = Mutex()

    private val lib get() = AppGraph.library

    /** Ajuste "buscar capa e sinopse na internet ao adicionar" (só vale com chave do TMDB). */
    var autoTmdb: Boolean
        get() = AppGraph.prefs.getBoolean("autoTmdb", true)
        set(v) { AppGraph.prefs.edit().putBoolean("autoTmdb", v).apply() }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Só aceita o resultado automático do TMDB se o título bater de verdade (evita capa errada em vídeo caseiro). */
    fun goodMatch(query: String, result: TmdbResult): Boolean {
        val q = norm(Tmdb.cleanQuery(query).first)
        val r = norm(result.title)
        return q.isNotBlank() && r.isNotBlank() && result.posterPath != null && (q == r || (q.length >= 4 && (r.startsWith(q) || q.startsWith(r) && r.length >= 4)))
    }

    /** Busca no TMDB e devolve o melhor resultado aceitável (ou nulo). */
    private suspend fun autoMatch(title: String, series: Boolean): TmdbResult? {
        if (!autoTmdb || !AppGraph.tmdb.hasKey) return null
        return runCatching { AppGraph.tmdb.search(title, series) }.getOrNull()
            ?.firstOrNull { goodMatch(title, it) && it.isSeries == series }
            ?: runCatching { AppGraph.tmdb.search(title, series) }.getOrNull()?.firstOrNull { goodMatch(title, it) }
    }

    /** Aplica capa/sinopse/ano/gêneros do TMDB a um DVD. */
    suspend fun applyTmdbToVideo(videoId: String, r: TmdbResult, useTitle: Boolean) {
        val genres = AppGraph.tmdb.genreNames(r.genreIds)
        val cover = AppGraph.covers.fromUrl(r.posterUrl)
        lib.update(videoId) { v ->
            v.copy(
                title = if (useTitle) r.title else v.title,
                synopsis = r.overview ?: v.synopsis, year = r.year ?: v.year,
                genres = genres.ifEmpty { v.genres },
            )
        }
        if (cover != null) lib.setCover(videoId, cover)
    }

    suspend fun applyTmdbToBox(boxId: String, r: TmdbResult, useTitle: Boolean) {
        val genres = AppGraph.tmdb.genreNames(r.genreIds)
        val cover = AppGraph.covers.fromUrl(r.posterUrl)
        lib.updateBox(boxId) { b ->
            b.copy(
                title = if (useTitle) r.title else b.title,
                synopsis = r.overview ?: b.synopsis, year = r.year ?: b.year,
                genres = genres.ifEmpty { b.genres },
            )
        }
        if (cover != null) lib.setBoxCover(boxId, cover)
    }

    private fun driveVideo(it: DriveItem, shelf: String, boxId: String? = null, folderName: String = "") = Video(
        id = lib.newId(), source = Source.DRIVE, ref = it.id,
        title = titleFromFileName(it.name), shelf = shelf, fileName = it.name,
        durationMs = it.durationMs, sizeBytes = it.sizeBytes, boxId = boxId,
    ).let { v ->
        if (boxId == null) v else {
            val n = Episodes.parse(it.name, Episodes.seasonFromFolder(folderName))
            v.copy(season = n.season, episode = n.episode)
        }
    }

    /** Capas dos vídeos recém-adicionados: TMDB (filmes) ou miniatura do Drive. */
    private fun fetchCovers(added: List<Video>, thumbs: Map<String, String?>, tryTmdb: Boolean) {
        scope.launch {
            added.forEach { v ->
                if (tryTmdb && v.boxId == null) {
                    val m = autoMatch(v.title, series = false)
                    if (m != null) { applyTmdbToVideo(v.id, m, useTitle = false); return@forEach }
                }
                val path = when (v.source) {
                    Source.DRIVE -> AppGraph.covers.fromDriveThumbnail(thumbs[v.ref] ?: thumbnailFor(v.ref))
                    Source.LOCAL -> AppGraph.covers.fromLocalFrame(Uri.parse(v.ref))
                }
                if (path != null && lib.current.video(v.id)?.cover == null) lib.setCover(v.id, path)
            }
        }
    }

    /** Capa da série: TMDB; senão a miniatura do primeiro episódio. */
    private fun fetchBoxCover(boxId: String) {
        scope.launch {
            val box = lib.current.box(boxId) ?: return@launch
            if (box.cover != null) return@launch
            val m = autoMatch(box.title, series = true)
            if (m != null) { applyTmdbToBox(boxId, m, useTitle = false); return@launch }
            val first = lib.current.episodesOf(boxId).firstOrNull() ?: return@launch
            val path = when (first.source) {
                Source.DRIVE -> AppGraph.covers.fromDriveThumbnail(thumbnailFor(first.ref))
                Source.LOCAL -> AppGraph.covers.fromLocalFrame(Uri.parse(first.ref))
            }
            if (path != null && lib.current.box(boxId)?.cover == null) lib.setBoxCover(boxId, path)
        }
    }

    /** Vídeos do Google Drive. Devolve quantos entraram (os repetidos são ignorados). */
    fun addDrive(items: List<DriveItem>, shelf: String): Int {
        val added = lib.add(items.filterNot { it.isFolder }.map { driveVideo(it, shelf) })
        fetchCovers(added, items.associate { it.id to it.thumbnail }, tryTmdb = true)
        return added.size
    }

    /**
     * Uma pasta do Drive vira uma série: subpastas viram temporadas ("Temporada 2", "S02"…),
     * episódios numerados pelo nome. [sync] liga a pasta para episódios novos entrarem sozinhos.
     */
    fun addDriveSeries(folderId: String, folderName: String, videos: List<Pair<DriveItem, String>>, shelf: String, sync: Boolean): Int {
        val box = lib.createBox(titleFromFileName(folderName), shelf, folderId)
        val added = lib.add(videos.map { (item, folder) -> driveVideo(item, shelf, box.id, folder) }
            .let { list -> list.map { v -> if (v.season == 0 && list.any { it.season > 0 }) v.copy(season = 1) else v } })
        if (sync) lib.addSync(SyncFolder(folderId, folderName, shelf, box.id, System.currentTimeMillis()))
        fetchBoxCover(box.id)
        fetchCovers(added, videos.associate { it.first.id to it.first.thumbnail }, tryTmdb = false)
        return added.size
    }

    /** Liga uma pasta a uma prateleira e já traz os vídeos de agora. */
    fun syncFolderToShelf(folderId: String, folderName: String, shelf: String, videos: List<DriveItem>): Int {
        lib.addSync(SyncFolder(folderId, folderName, shelf, null, System.currentTimeMillis()))
        return addDrive(videos.filterNot { lib.isOnShelf(Source.DRIVE, it.id) }, shelf)
    }

    /** Vídeos escolhidos no celular. [seriesName] não nulo junta tudo numa série. */
    fun addLocal(context: Context, uris: List<Uri>, shelf: String, seriesName: String? = null): Int {
        val cr = context.contentResolver
        val box = seriesName?.let { lib.createBox(it, shelf) }
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
            val n = if (box != null) Episodes.parse(name) else Episodes.Number(0, 0)
            Video(
                id = lib.newId(), source = Source.LOCAL, ref = uri.toString(), title = titleFromFileName(name),
                shelf = shelf, sizeBytes = size, fileName = name, boxId = box?.id, season = n.season, episode = n.episode,
            )
        }
        val added = lib.add(items)
        scope.launch {
            added.forEach { v ->
                val dur = AppGraph.covers.localDuration(Uri.parse(v.ref))
                if (dur > 0) lib.update(v.id) { it.copy(durationMs = dur) }
            }
        }
        box?.let { fetchBoxCover(it.id) }
        fetchCovers(added, emptyMap(), tryTmdb = box == null)
        return added.size
    }

    /**
     * Passa pelas pastas sincronizadas e traz os vídeos novos. Devolve quantos entraram.
     * [force] ignora o intervalo mínimo de 30 min entre verificações.
     */
    suspend fun syncAll(force: Boolean = false): Int = syncLock.withLock {
        if (!AppGraph.auth.account.value.connected) return 0
        var total = 0
        val now = System.currentTimeMillis()
        for (f in lib.current.syncs) {
            if (!force && now - f.lastSync < 30 * 60_000) continue
            val found = runCatching { AppGraph.drive.videosDeepNamed(f.folderId, f.name) }.getOrNull() ?: continue
            val fresh = found.filterNot { lib.isOnShelf(Source.DRIVE, it.first.id) }
            if (fresh.isNotEmpty()) {
                val box = f.boxId?.let { lib.current.box(it) }
                val added = lib.add(fresh.map { (item, folder) -> driveVideo(item, box?.shelf ?: f.shelf, box?.id, folder) })
                fetchCovers(added, fresh.associate { it.first.id to it.first.thumbnail }, tryTmdb = box == null)
                total += added.size
            }
            lib.markSynced(f.folderId)
        }
        total
    }
}
