package com.dmwnezes.estante.data

import org.json.JSONArray
import org.json.JSONObject

/** De onde o vídeo vem. */
enum class Source { DRIVE, LOCAL }

const val DEFAULT_SHELF = "Minha estante"

private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
private fun JSONObject.strList(key: String): List<String> =
    optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()

/**
 * Um "DVD" da estante (ou um episódio, quando [boxId] aponta para uma série).
 * [ref] é o id do arquivo no Google Drive ou o endereço (content://) do vídeo no celular.
 * [cover] é o caminho da imagem da capa salva dentro do app (ou nulo para a capa gerada).
 * [subtitle] é "drive:<id>" ou um content:// de um arquivo .srt/.vtt.
 */
data class Video(
    val id: String,
    val source: Source,
    val ref: String,
    val title: String,
    val cover: String? = null,
    val shelf: String = DEFAULT_SHELF,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false,
    val addedAt: Long = 0,
    val watchedAt: Long = 0,
    val sizeBytes: Long = 0,
    val order: Double = 0.0,
    val fileName: String? = null,
    val synopsis: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val boxId: String? = null,
    val season: Int = 0,
    val episode: Int = 0,
    val subtitle: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source.name).put("ref", ref).put("title", title)
        .put("cover", cover ?: JSONObject.NULL).put("shelf", shelf)
        .put("positionMs", positionMs).put("durationMs", durationMs).put("finished", finished)
        .put("addedAt", addedAt).put("watchedAt", watchedAt).put("sizeBytes", sizeBytes)
        .put("order", order)
        .put("fileName", fileName ?: JSONObject.NULL)
        .put("synopsis", synopsis ?: JSONObject.NULL)
        .put("year", year ?: JSONObject.NULL)
        .put("genres", JSONArray(genres))
        .put("boxId", boxId ?: JSONObject.NULL)
        .put("season", season).put("episode", episode)
        .put("subtitle", subtitle ?: JSONObject.NULL)

    /** "T1 · E3" para episódios; vazio para filmes. */
    val episodeLabel: String
        get() = when {
            season > 0 && episode > 0 -> "T$season · E$episode"
            episode > 0 -> "E$episode"
            else -> ""
        }

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"),
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.DRIVE),
            ref = o.getString("ref"),
            title = o.optString("title"),
            cover = o.str("cover"),
            shelf = o.optString("shelf").ifBlank { DEFAULT_SHELF },
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            finished = o.optBoolean("finished"),
            addedAt = o.optLong("addedAt"),
            watchedAt = o.optLong("watchedAt"),
            sizeBytes = o.optLong("sizeBytes"),
            order = o.optDouble("order", 0.0),
            fileName = o.str("fileName"),
            synopsis = o.str("synopsis"),
            year = o.str("year"),
            genres = o.strList("genres"),
            boxId = o.str("boxId"),
            season = o.optInt("season"),
            episode = o.optInt("episode"),
            subtitle = o.str("subtitle"),
        )
    }
}

/**
 * Uma série (box): vários episódios numa caixa só, separados por temporada.
 * [folderId] é a pasta do Drive de onde ela veio (para buscar episódios novos).
 */
data class Box(
    val id: String,
    val title: String,
    val cover: String? = null,
    val shelf: String = DEFAULT_SHELF,
    val addedAt: Long = 0,
    val order: Double = 0.0,
    val synopsis: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val folderId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("cover", cover ?: JSONObject.NULL).put("shelf", shelf)
        .put("addedAt", addedAt).put("order", order)
        .put("synopsis", synopsis ?: JSONObject.NULL).put("year", year ?: JSONObject.NULL)
        .put("genres", JSONArray(genres)).put("folderId", folderId ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Box(
            id = o.getString("id"),
            title = o.optString("title"),
            cover = o.str("cover"),
            shelf = o.optString("shelf").ifBlank { DEFAULT_SHELF },
            addedAt = o.optLong("addedAt"),
            order = o.optDouble("order", 0.0),
            synopsis = o.str("synopsis"),
            year = o.str("year"),
            genres = o.strList("genres"),
            folderId = o.str("folderId"),
        )
    }
}

/** Pasta do Drive ligada a uma prateleira (ou série): vídeo novo nela entra sozinho. */
data class SyncFolder(
    val folderId: String,
    val name: String,
    val shelf: String = DEFAULT_SHELF,
    val boxId: String? = null,
    val lastSync: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("folderId", folderId).put("name", name).put("shelf", shelf)
        .put("boxId", boxId ?: JSONObject.NULL).put("lastSync", lastSync)

    companion object {
        fun fromJson(o: JSONObject) = SyncFolder(
            folderId = o.getString("folderId"),
            name = o.optString("name"),
            shelf = o.optString("shelf").ifBlank { DEFAULT_SHELF },
            boxId = o.str("boxId"),
            lastSync = o.optLong("lastSync"),
        )
    }
}

/** Lista de reprodução: vídeos tocados em sequência, na ordem escolhida. */
data class Playlist(
    val id: String,
    val name: String,
    val videoIds: List<String> = emptyList(),
    val createdAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("createdAt", createdAt)
        .put("videoIds", JSONArray(videoIds))

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            id = o.getString("id"),
            name = o.optString("name"),
            videoIds = o.strList("videoIds"),
            createdAt = o.optLong("createdAt"),
        )
    }
}

/** O que fica em pé na prateleira: um DVD solto ou uma caixa de série. */
sealed interface ShelfItem {
    val id: String
    val title: String
    val shelf: String
    val order: Double
    val addedAt: Long
    val watchedAt: Long

    data class Single(val video: Video) : ShelfItem {
        override val id get() = video.id
        override val title get() = video.title
        override val shelf get() = video.shelf
        override val order get() = video.order
        override val addedAt get() = video.addedAt
        override val watchedAt get() = video.watchedAt
    }

    data class Series(val box: Box, val episodes: List<Video>) : ShelfItem {
        override val id get() = box.id
        override val title get() = box.title
        override val shelf get() = box.shelf
        override val order get() = box.order
        override val addedAt get() = box.addedAt
        override val watchedAt get() = episodes.maxOfOrNull { it.watchedAt } ?: 0L
    }
}

data class LibraryState(
    val videos: List<Video> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val boxes: List<Box> = emptyList(),
    val syncs: List<SyncFolder> = emptyList(),
) {
    fun video(id: String): Video? = videos.firstOrNull { it.id == id }
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }
    fun box(id: String): Box? = boxes.firstOrNull { it.id == id }
    fun videosOf(p: Playlist): List<Video> = p.videoIds.mapNotNull(::video)

    /** Episódios da série na ordem: temporada, episódio, título. */
    fun episodesOf(boxId: String): List<Video> = videos.filter { it.boxId == boxId }.sortedWith(Episodes.order)

    /** Tudo o que fica em pé nas prateleiras (episódios ficam dentro das caixas). */
    val shelfItems: List<ShelfItem>
        get() {
            val byBox = videos.filter { it.boxId != null }.groupBy { it.boxId }
            return videos.filter { it.boxId == null || box(it.boxId) == null }.map { ShelfItem.Single(it) } +
                boxes.map { ShelfItem.Series(it, (byBox[it.id] ?: emptyList()).sortedWith(Episodes.order)) }
        }

    /** Nomes das prateleiras: "Minha estante" primeiro, depois em ordem alfabética. */
    val shelves: List<String>
        get() = shelfItems.map { it.shelf }.distinct()
            .sortedWith(compareBy<String> { it != DEFAULT_SHELF }.thenBy { it.lowercase() })

    /** Vídeos começados e não terminados, do mais recente para o mais antigo. */
    val continueWatching: List<Video>
        get() = videos.filter { Resume.startAt(it) > 0 }.sortedByDescending { it.watchedAt }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 2)
        .put("videos", JSONArray(videos.map { it.toJson() }))
        .put("playlists", JSONArray(playlists.map { it.toJson() }))
        .put("boxes", JSONArray(boxes.map { it.toJson() }))
        .put("syncs", JSONArray(syncs.map { it.toJson() }))

    companion object {
        private fun <T> JSONObject.list(key: String, f: (JSONObject) -> T): List<T> =
            optJSONArray(key)?.let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } } ?: emptyList()

        fun fromJson(o: JSONObject) = LibraryState(
            videos = o.list("videos", Video::fromJson),
            playlists = o.list("playlists", Playlist::fromJson),
            boxes = o.list("boxes", Box::fromJson),
            syncs = o.list("syncs", SyncFolder::fromJson),
        )
    }
}

/** Ordem dos DVDs dentro de cada prateleira. */
enum class SortOrder(val label: String) {
    MANUAL("Minha ordem (arrastar)"),
    TITLE("Título (A–Z)"),
    ADDED("Adicionados recentemente"),
    WATCHED("Assistidos recentemente"),
}

private val collator = java.text.Collator.getInstance(java.util.Locale("pt", "BR"))

fun <T : ShelfItem> List<T>.sortedBy(order: SortOrder): List<T> = when (order) {
    SortOrder.MANUAL -> sortedWith(compareBy<T> { it.order }.thenBy { it.addedAt })
    SortOrder.TITLE -> sortedWith(compareBy(collator) { it.title })
    SortOrder.ADDED -> sortedByDescending { it.addedAt }
    SortOrder.WATCHED -> sortedByDescending { it.watchedAt }
}

/** Regras de "continuar de onde parou". */
object Resume {
    /** Abaixo disso não vale a pena retomar (começa do início). */
    const val MIN_MS = 10_000L

    /** Dali em diante o vídeo conta como terminado (créditos finais). */
    const val END_FRACTION = 0.95

    fun progress(v: Video): Float =
        if (v.durationMs > 0) (v.positionMs.toFloat() / v.durationMs).coerceIn(0f, 1f) else 0f

    /** Onde o vídeo deve começar ao tocar em "Continuar". */
    fun startAt(v: Video): Long = if (!v.finished && v.positionMs >= MIN_MS) v.positionMs else 0L

    /** Atualiza o vídeo com a posição atual do player. */
    fun record(v: Video, positionMs: Long, durationMs: Long, now: Long): Video {
        val dur = if (durationMs > 0) durationMs else v.durationMs
        return when {
            dur > 0 && positionMs >= dur * END_FRACTION ->
                v.copy(positionMs = 0, durationMs = dur, finished = true, watchedAt = now)
            positionMs < MIN_MS -> v.copy(positionMs = 0, durationMs = dur, watchedAt = now)
            else -> v.copy(positionMs = positionMs, durationMs = dur, finished = false, watchedAt = now)
        }
    }
}

/** "Meu_filme.2019.mp4" → "Meu filme 2019". */
fun titleFromFileName(name: String): String {
    val base = name.substringBeforeLast('.', name).takeIf { it.isNotBlank() } ?: name
    return base.replace('_', ' ').replace('.', ' ').replace(Regex("\\s+"), " ").trim().ifBlank { name }
}

/** 5025000 → "1:23:45"; 754000 → "12:34". */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 5025000 → "1h 23min"; 754000 → "12min". */
fun formatDuration(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(0)
    return if (min >= 60) "${min / 60}h ${min % 60}min" else "${min}min"
}
