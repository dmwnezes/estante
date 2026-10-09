package com.dmwnezes.estante.party

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** O filme da sala, do jeito que o site precisa para tocar. */
data class RoomVideo(val fileId: String, val title: String, val apiKey: String, val subtitleId: String?, val mime: String) {
    fun toJson(): JSONObject = JSONObject().put("fileId", fileId).put("title", title).put("key", apiKey)
        .put("subtitleId", subtitleId ?: JSONObject.NULL).put("mime", mime)

    companion object {
        fun from(o: JSONObject?) = o?.let {
            RoomVideo(it.optString("fileId"), it.optString("title"), it.optString("key"), it.optString("subtitleId").takeIf { s -> s.isNotBlank() && s != "null" }, it.optString("mime"))
        }
    }
}

private val SERVER_TIME get() = JSONObject().put(".sv", "timestamp")

/**
 * Uma sala de "assistir junto" no Firebase:
 * salas/<código>/ host · video · state · people/<id> · chat/<id>
 */
class PartySession(private val db: PartyDb, val code: String, val myName: String) {

    val me: String = "a-" + UUID.randomUUID().toString().take(8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val root = "salas/$code"
    private var tree: Any? = null
    private var seq = 0L
    private var jobs = mutableListOf<Job>()

    @Volatile var offset = 0L; private set
    fun serverNow() = System.currentTimeMillis() + offset

    private val _people = MutableStateFlow<List<Person>>(emptyList())
    val people: StateFlow<List<Person>> = _people.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    /** Último comando de OUTRA pessoa (os meus não voltam para mim). */
    private val _remote = MutableStateFlow<PlayState?>(null)
    val remote: StateFlow<PlayState?> = _remote.asStateFlow()
    private val _host = MutableStateFlow<String?>(null)
    val host: StateFlow<String?> = _host.asStateFlow()
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Cria a sala com o filme parado no começo (ou em [startAt] segundos). */
    suspend fun create(video: RoomVideo, startAt: Double) {
        db.put(
            root,
            JSONObject()
                .put("host", me)
                .put("createdAt", SERVER_TIME)
                .put("video", video.toJson())
                .put("state", JSONObject().put("playing", false).put("position", startAt).put("at", SERVER_TIME).put("by", me).put("seq", 0)),
        )
    }

    fun start() {
        jobs += scope.launch {
            db.stream(root).collect { e ->
                tree = PartyDb.apply(tree, e)
                _connected.value = true
                publish()
            }
        }
        // Sinal de vida a cada 10 s; aproveita a ida e volta para acertar o relógio com o servidor.
        jobs += scope.launch {
            while (isActive) {
                runCatching {
                    val sent = System.currentTimeMillis()
                    val r = db.put("$root/people/$me", JSONObject().put("name", myName).put("lastSeen", SERVER_TIME).put("platform", "android")) as? JSONObject
                    val got = System.currentTimeMillis()
                    r?.optLong("lastSeen")?.takeIf { it > 0 }?.let { offset = PartySync.offset(sent, got, it) }
                    _error.value = null
                }.onFailure { _error.value = it.message }
                delay(10_000)
            }
        }
    }

    private fun publish() {
        val t = tree as? JSONObject ?: return
        _host.value = t.optString("host").ifBlank { null }
        t.optJSONObject("people")?.let { p ->
            _people.value = p.keys().asSequence().mapNotNull { id ->
                p.optJSONObject(id)?.let { Person(id, it.optString("name", "?"), it.optLong("lastSeen"), it.optString("platform")) }
            }.sortedBy { it.name.lowercase() }.toList()
        }
        t.optJSONObject("chat")?.let { c ->
            _messages.value = c.keys().asSequence().mapNotNull { id ->
                c.optJSONObject(id)?.let { ChatMessage(id, it.optString("name", "?"), it.optString("text"), it.optLong("at"), it.optString("by")) }
            }.sortedWith(compareBy({ it.at }, { it.id })).toList().takeLast(300)
        }
        t.optJSONObject("state")?.let { s ->
            val st = PlayState(s.optBoolean("playing"), s.optDouble("position", 0.0), s.optLong("at"), s.optString("by"), s.optLong("seq"))
            if (st.by != me && st != _remote.value) _remote.value = st
        }
    }

    /** Avisa a sala: play/pause/pulo feito aqui. */
    fun sendState(playing: Boolean, positionSec: Double) {
        seq++
        val body = JSONObject().put("playing", playing).put("position", positionSec).put("at", serverNow()).put("by", me).put("seq", seq)
        scope.launch { runCatching { db.put("$root/state", body) }.onFailure { _error.value = it.message } }
    }

    fun sendChat(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        scope.launch {
            runCatching { db.push("$root/chat", JSONObject().put("name", myName).put("text", t).put("at", SERVER_TIME).put("by", me)) }
                .onFailure { _error.value = it.message }
        }
    }

    /** Sai da sala (some da lista de quem está assistindo). */
    fun leave() {
        jobs.forEach { it.cancel() }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { db.delete("$root/people/$me") }
            scope.cancel()
        }
    }
}
