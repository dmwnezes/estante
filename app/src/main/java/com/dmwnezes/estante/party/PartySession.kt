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
class PartySession(private val db: PartyDb, val code: String, val myName: String, avatar: String = "") {
    /** Foto de perfil escolhida (chave de [Avatars]); pode trocar com a sala aberta. */
    @Volatile var myAvatar: String = avatar
        private set


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
    private val _typing = MutableStateFlow<Map<String, Pair<String, Long>>>(emptyMap())
    /** Quem avisou que está digitando: id → (nome, quando). */
    val typing: StateFlow<Map<String, Pair<String, Long>>> = _typing.asStateFlow()
    private val _issues = MutableStateFlow<List<Issue>>(emptyList())
    val issues: StateFlow<List<Issue>> = _issues.asStateFlow()
    private val _roomVideo = MutableStateFlow<RoomVideo?>(null)
    val roomVideo: StateFlow<RoomVideo?> = _roomVideo.asStateFlow()
    private val _reactions = MutableStateFlow<List<Reaction>>(emptyList())
    val reactions: StateFlow<List<Reaction>> = _reactions.asStateFlow()
    /** Nomes de todo mundo que passou pela sala (para o diário). */
    val everyone = java.util.concurrent.ConcurrentHashMap<String, String>()
    val startedAt = System.currentTimeMillis()
    private var lastTypingSent = 0L
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
                    val r = db.put("$root/people/$me", meJson()) as? JSONObject
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
        RoomVideo.from(t.optJSONObject("video"))?.let { if (it != _roomVideo.value) _roomVideo.value = it }
        _issues.value = t.optJSONObject("issues")?.let { o ->
            o.keys().asSequence().mapNotNull { id ->
                o.optJSONObject(id)?.let { Issue(id, it.optString("name", "?"), it.optInt("status"), it.optString("reason"), it.optString("fileId"), it.optLong("at")) }
            }.toList()
        } ?: emptyList()
        t.optJSONObject("people")?.let { p ->
            _people.value = p.keys().asSequence().mapNotNull { id ->
                p.optJSONObject(id)?.let { Person(id, it.optString("name", "?"), it.optLong("lastSeen"), it.optString("platform"), it.optString("avatar").takeIf { a -> a.isNotBlank() }) }
            }.sortedBy { it.name.lowercase() }.toList()
        }
        _people.value.forEach { everyone[it.id] = it.name }
        _typing.value = t.optJSONObject("typing")?.let { ty ->
            ty.keys().asSequence().filter { it != me }.mapNotNull { id -> ty.optJSONObject(id)?.let { id to (it.optString("name", "?") to it.optLong("at")) } }.toMap()
        } ?: emptyMap()
        t.optJSONObject("reactions")?.let { r ->
            _reactions.value = r.keys().asSequence().mapNotNull { id ->
                r.optJSONObject(id)?.let { Reaction(id, it.optString("e"), it.optString("name", "?"), it.optLong("at"), it.optString("by"), it.optBoolean("big")) }
            }.filter { it.emoji in REACTIONS }.sortedBy { it.at }.toList().takeLast(40)
        }
        t.optJSONObject("chat")?.let { c ->
            _messages.value = c.keys().asSequence().mapNotNull { id ->
                c.optJSONObject(id)?.let {
                    ChatMessage(id, it.optString("name", "?"), it.optString("text"), it.optLong("at"), it.optString("by"), it.optString("img").takeIf(ChatImages::isValid))
                }
            }.sortedWith(compareBy({ it.at }, { it.id })).toList().takeLast(300)
        }
        t.optJSONObject("state")?.let { s ->
            val st = PlayState(s.optBoolean("playing"), s.optDouble("position", 0.0), s.optLong("at"), s.optString("by"), s.optLong("seq"), s.optString("wait").takeIf { it.isNotBlank() })
            if (st.by != me && st != _remote.value) _remote.value = st
        }
    }

    /** Avisa a sala: play/pause/pulo feito aqui. */
    fun sendState(playing: Boolean, positionSec: Double, at: Long = serverNow(), countdown: Boolean = false, wait: String? = null): Long {
        seq++
        val body = JSONObject().put("playing", playing).put("position", positionSec).put("at", at).put("by", me).put("seq", seq)
        if (countdown) body.put("cd", true)
        if (wait != null) body.put("wait", wait)
        scope.launch { runCatching { db.put("$root/state", body) }.onFailure { _error.value = it.message } }
        return seq
    }

    private fun meJson() = JSONObject().put("name", myName).put("lastSeen", SERVER_TIME).put("platform", "android")
        .apply { if (myAvatar.isNotBlank()) put("avatar", myAvatar) }

    /** A rede do celular mudou: reconecta com a sala na hora. */
    fun networkChanged() = db.reconnectNow()

    /** Troca o arquivo que o site toca (cópia nova do filme) e limpa os avisos de problema. */
    suspend fun switchFile(fileId: String) {
        // "rev" muda sempre: mesmo voltando ao mesmo arquivo, o site recarrega.
        db.patch("$root/video", JSONObject().put("fileId", fileId).put("rev", SERVER_TIME))
        runCatching { db.delete("$root/issues") }
    }

    /** Troca a foto de perfil e avisa a sala na hora. */
    fun setAvatar(key: String) {
        myAvatar = key
        scope.launch { runCatching { db.put("$root/people/$me", meJson()) } }
    }

    fun sendChat(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        sendTyping(stopped = true)
        scope.launch {
            runCatching { db.push("$root/chat", JSONObject().put("name", myName).put("text", t).put("at", SERVER_TIME).put("by", me)) }
                .onFailure { _error.value = it.message }
        }
    }

    /** Avisa que está digitando (no máximo a cada 2,5 s). [stopped] apaga o aviso. */
    fun sendTyping(stopped: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!stopped && now - lastTypingSent < 2_500) return
        lastTypingSent = if (stopped) 0 else now
        scope.launch {
            runCatching {
                if (stopped) db.delete("$root/typing/$me")
                else db.put("$root/typing/$me", JSONObject().put("name", myName).put("at", SERVER_TIME))
            }
        }
    }

    fun sendReaction(emoji: String, big: Boolean = false) {
        if (emoji !in REACTIONS) return
        scope.launch {
            runCatching { db.push("$root/reactions", JSONObject().put("e", emoji).put("name", myName).put("by", me).put("at", SERVER_TIME).apply { if (big) put("big", true) }) }
                .onFailure { _error.value = it.message }
        }
    }

    /** Manda uma foto (já reduzida por [ChatImages.prepare]), com legenda opcional. */
    fun sendImage(dataUrl: String, caption: String = "") {
        if (!ChatImages.isValid(dataUrl)) return
        scope.launch {
            runCatching {
                db.push("$root/chat", JSONObject().put("name", myName).put("text", caption.trim().take(500)).put("img", dataUrl).put("at", SERVER_TIME).put("by", me))
            }.onFailure { _error.value = it.message }
        }
    }

    /** Sai da sala (some da lista de quem está assistindo). */
    fun leave() {
        jobs.forEach { it.cancel() }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { db.delete("$root/people/$me") }
            runCatching { db.delete("$root/typing/$me") }
            scope.cancel()
        }
    }
}
