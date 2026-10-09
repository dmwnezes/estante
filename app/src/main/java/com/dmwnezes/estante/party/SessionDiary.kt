package com.dmwnezes.estante.party

import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Uma mensagem guardada no diário (a foto vira arquivo no celular). */
data class DiaryMessage(val name: String, val text: String, val at: Long, val photo: String?, val mine: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("text", text).put("at", at).put("photo", photo ?: JSONObject.NULL).put("mine", mine)

    companion object {
        fun from(o: JSONObject) = DiaryMessage(
            o.optString("name"), o.optString("text"), o.optLong("at"),
            o.optString("photo").takeIf { !o.isNull("photo") && it.isNotBlank() }, o.optBoolean("mine"),
        )
    }
}

/** Uma sessão de "assistir junto": filme, quando, quem e o que foi conversado. */
data class DiaryEntry(
    val id: String,
    val videoId: String,
    val title: String,
    val cover: String?,
    val startedAt: Long,
    val endedAt: Long,
    val people: List<String>,
    val messages: List<DiaryMessage>,
) {
    val photos: List<String> get() = messages.mapNotNull { it.photo }
    val minutes: Long get() = ((endedAt - startedAt) / 60_000).coerceAtLeast(1)

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("videoId", videoId).put("title", title).put("cover", cover ?: JSONObject.NULL)
        .put("startedAt", startedAt).put("endedAt", endedAt)
        .put("people", JSONArray(people))
        .put("messages", JSONArray(messages.map { it.toJson() }))

    companion object {
        fun from(o: JSONObject): DiaryEntry {
            val p = o.optJSONArray("people") ?: JSONArray()
            val m = o.optJSONArray("messages") ?: JSONArray()
            return DiaryEntry(
                o.getString("id"), o.optString("videoId"), o.optString("title"),
                o.optString("cover").takeIf { !o.isNull("cover") && it.isNotBlank() },
                o.optLong("startedAt"), o.optLong("endedAt"),
                (0 until p.length()).map { p.getString(it) },
                (0 until m.length()).map { DiaryMessage.from(m.getJSONObject(it)) },
            )
        }
    }
}

/**
 * Diário das sessões de "assistir junto", guardado no celular (diario.json + fotos em diario/).
 * Cada sessão é salva quando você sai da sala, só se alguém mais entrou ou houve conversa.
 */
class SessionDiary(private val dir: File) {

    private val file = File(dir, "diario.json")
    private val photosDir = File(dir, "diario").apply { mkdirs() }
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<DiaryEntry>> = _entries.asStateFlow()

    private fun load(): List<DiaryEntry> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { DiaryEntry.from(a.getJSONObject(it)) }.sortedByDescending { it.startedAt }
    }.getOrDefault(emptyList())

    private fun persist(list: List<DiaryEntry>) {
        _entries.value = list.sortedByDescending { it.startedAt }
        runCatching {
            val tmp = File(dir, "diario.json.tmp")
            tmp.writeText(JSONArray(list.map { it.toJson() }).toString())
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    /** Guarda a sessão (substitui se já existir com o mesmo id). As fotos viram arquivos .jpg. */
    fun save(
        id: String, videoId: String, title: String, cover: String?, startedAt: Long, endedAt: Long,
        people: Collection<String>, messages: List<ChatMessage>, me: String,
    ): DiaryEntry? {
        val others = people.filter { it.isNotBlank() }.distinct()
        if (others.size <= 1 && messages.isEmpty()) return null
        val folder = File(photosDir, id).apply { mkdirs() }
        val msgs = messages.map { m ->
            val photo = m.image?.let { data ->
                val f = File(folder, "${m.id.replace(Regex("[^A-Za-z0-9_-]"), "_")}.jpg")
                if (!f.exists()) runCatching { f.writeBytes(Base64.decode(data.substringAfter(","), Base64.DEFAULT)) }
                f.takeIf { it.exists() }?.absolutePath
            }
            DiaryMessage(m.name, m.text, m.at, photo, m.by == me)
        }
        val entry = DiaryEntry(id, videoId, title, cover, startedAt, endedAt, others, msgs)
        persist(_entries.value.filterNot { it.id == id } + entry)
        return entry
    }

    fun delete(id: String) {
        File(photosDir, id).deleteRecursively()
        persist(_entries.value.filterNot { it.id == id })
    }

    fun forVideo(videoId: String): List<DiaryEntry> = _entries.value.filter { it.videoId == videoId }
}
