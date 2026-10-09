package com.dmwnezes.estante.data

import org.json.JSONArray
import org.json.JSONObject

/** De onde o vídeo vem. */
enum class Source { DRIVE, LOCAL }

const val DEFAULT_SHELF = "Minha estante"

/**
 * Um "DVD" da estante.
 * [ref] é o id do arquivo no Google Drive ou o endereço (content://) do vídeo no celular.
 * [cover] é o caminho da imagem da capa salva dentro do app (ou nulo para a capa gerada).
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
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("source", source.name).put("ref", ref).put("title", title)
        .put("cover", cover ?: JSONObject.NULL).put("shelf", shelf)
        .put("positionMs", positionMs).put("durationMs", durationMs).put("finished", finished)
        .put("addedAt", addedAt).put("watchedAt", watchedAt).put("sizeBytes", sizeBytes)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            id = o.getString("id"),
            source = runCatching { Source.valueOf(o.optString("source")) }.getOrDefault(Source.DRIVE),
            ref = o.getString("ref"),
            title = o.optString("title"),
            cover = o.optString("cover").takeIf { !o.isNull("cover") && it.isNotBlank() },
            shelf = o.optString("shelf").ifBlank { DEFAULT_SHELF },
            positionMs = o.optLong("positionMs"),
            durationMs = o.optLong("durationMs"),
            finished = o.optBoolean("finished"),
            addedAt = o.optLong("addedAt"),
            watchedAt = o.optLong("watchedAt"),
            sizeBytes = o.optLong("sizeBytes"),
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
        fun fromJson(o: JSONObject): Playlist {
            val ids = o.optJSONArray("videoIds") ?: JSONArray()
            return Playlist(
                id = o.getString("id"),
                name = o.optString("name"),
                videoIds = (0 until ids.length()).map { ids.getString(it) },
                createdAt = o.optLong("createdAt"),
            )
        }
    }
}

data class LibraryState(
    val videos: List<Video> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    fun video(id: String): Video? = videos.firstOrNull { it.id == id }
    fun playlist(id: String): Playlist? = playlists.firstOrNull { it.id == id }
    fun videosOf(p: Playlist): List<Video> = p.videoIds.mapNotNull(::video)

    /** Nomes das prateleiras: "Minha estante" primeiro, depois em ordem alfabética. */
    val shelves: List<String>
        get() = videos.map { it.shelf }.distinct()
            .sortedWith(compareBy<String> { it != DEFAULT_SHELF }.thenBy { it.lowercase() })

    /** Vídeos começados e não terminados, do mais recente para o mais antigo. */
    val continueWatching: List<Video>
        get() = videos.filter { Resume.startAt(it) > 0 }.sortedByDescending { it.watchedAt }

    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("videos", JSONArray(videos.map { it.toJson() }))
        .put("playlists", JSONArray(playlists.map { it.toJson() }))

    companion object {
        fun fromJson(o: JSONObject): LibraryState {
            val v = o.optJSONArray("videos") ?: JSONArray()
            val p = o.optJSONArray("playlists") ?: JSONArray()
            return LibraryState(
                videos = (0 until v.length()).map { Video.fromJson(v.getJSONObject(it)) },
                playlists = (0 until p.length()).map { Playlist.fromJson(p.getJSONObject(it)) },
            )
        }
    }
}

/** Ordem dos DVDs dentro de cada prateleira. */
enum class SortOrder(val label: String) {
    TITLE("Título (A–Z)"),
    ADDED("Adicionados recentemente"),
    WATCHED("Assistidos recentemente"),
}

fun List<Video>.sortedBy(order: SortOrder): List<Video> = when (order) {
    SortOrder.TITLE -> sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("pt", "BR"))) { it.title })
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
