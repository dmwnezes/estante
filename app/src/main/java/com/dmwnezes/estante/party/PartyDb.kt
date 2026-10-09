package com.dmwnezes.estante.party

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Duration

/** Um evento do stream do Firebase: "put" troca o que está em [path]; "patch" mescla. */
data class DbEvent(val type: String, val path: String, val data: Any?)

/**
 * Firebase Realtime Database pela API REST (sem SDK, sem google-services.json).
 * O mesmo jeito que o site usa no iPhone, então os dois falam a mesma língua.
 */
class PartyDb(baseUrl: String, private val http: OkHttpClient) {

    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()
    private val streamHttp = http.newBuilder().readTimeout(Duration.ZERO).build()

    private fun url(path: String) = "$base/${path.trim('/')}.json"

    private suspend fun send(method: String, path: String, body: Any?): Any? = withContext(Dispatchers.IO) {
        val rb = body?.let { (if (it is String) JSONObject.quote(it) else it.toString()).toRequestBody(json) }
        val req = Request.Builder().url(url(path)).method(method, rb).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 401 || resp.code == 403) throw IOException("O Firebase recusou (regras do banco). Confira as regras em Ajustes > Assistir junto.")
            if (!resp.isSuccessful) throw IOException("Firebase respondeu ${resp.code}")
            if (text.isBlank()) null else JSONTokener(text).nextValue()
        }
    }

    suspend fun get(path: String): Any? = send("GET", path, null)
    suspend fun put(path: String, value: Any?): Any? = send("PUT", path, value ?: JSONObject.NULL)
    suspend fun patch(path: String, value: JSONObject): Any? = send("PATCH", path, value)
    /** Acrescenta um filho com id automático (ordenado por tempo). Devolve o id. */
    suspend fun push(path: String, value: JSONObject): String? = (send("POST", path, value) as? JSONObject)?.optString("name")
    suspend fun delete(path: String) { send("DELETE", path, null) }

    /** Acompanha [path] ao vivo. Reconecta sozinho se a internet cair. */
    fun stream(path: String): Flow<DbEvent> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            var wait = 1000L
            while (isActive) {
                runCatching {
                    val req = Request.Builder().url(url(path)).header("Accept", "text/event-stream").build()
                    streamHttp.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("stream ${resp.code}")
                        wait = 1000L
                        val source = resp.body?.source() ?: throw IOException("sem corpo")
                        var event = ""
                        while (isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.startsWith("event:") -> event = line.substringAfter(':').trim()
                                line.startsWith("data:") -> {
                                    parseEvent(event, line.substringAfter(':').trim())?.let { trySend(it) }
                                    if (event == "cancel" || event == "auth_revoked") throw IOException(event)
                                }
                            }
                        }
                    }
                }
                if (!isActive) break
                delay(wait)
                wait = (wait * 2).coerceAtMost(15_000)
            }
        }
        awaitClose { job.cancel() }
    }.flowOn(Dispatchers.IO)

    companion object {
        /** Linha "data:" do stream → evento (put/patch); keep-alive vira nulo. */
        fun parseEvent(event: String, data: String): DbEvent? {
            if (event != "put" && event != "patch") return null
            val o = runCatching { JSONObject(data) }.getOrNull() ?: return null
            return DbEvent(event, o.optString("path", "/"), o.opt("data"))
        }

        /** Aplica um evento a uma cópia local da árvore e devolve a nova raiz. */
        fun apply(root: Any?, e: DbEvent): Any? = when (e.type) {
            "put" -> setAt(root, e.path, e.data)
            "patch" -> {
                var r = root
                val d = e.data as? JSONObject
                d?.keys()?.forEach { k -> r = setAt(r, e.path.trimEnd('/') + "/" + k, d.opt(k)) }
                r
            }
            else -> root
        }

        fun setAt(root: Any?, path: String, value: Any?): Any? {
            val parts = path.split('/').filter { it.isNotEmpty() }
            val v = if (value == JSONObject.NULL) null else value
            if (parts.isEmpty()) return v
            val top = (root as? JSONObject)?.let { JSONObject(it.toString()) } ?: JSONObject()
            var node = top
            for (p in parts.dropLast(1)) {
                val next = node.optJSONObject(p) ?: JSONObject().also { node.put(p, it) }
                node = next
            }
            if (v == null) node.remove(parts.last()) else node.put(parts.last(), v)
            return top
        }
    }
}
