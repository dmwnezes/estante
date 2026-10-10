package com.dmwnezes.estante.party

import com.dmwnezes.estante.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONObject

/** O que o resultado da checagem do filme diz. */
sealed interface ShareCheck {
    data object Ok : ShareCheck
    data object NotShared : ShareCheck
    data object BadKey : ShareCheck
    /** Compartilhado, mas o Google bloqueou o download pelo link (limite de downloads do arquivo). */
    data class Blocked(val reason: String) : ShareCheck
    data class Failed(val message: String) : ShareCheck
}

/** Ajustes do "assistir junto": banco do Firebase, chave do Google e o seu nome na sala. */
object PartyConfig {
    private val prefs get() = AppGraph.prefs

    var dbUrl: String
        get() = prefs.getString("partyDb", "").orEmpty()
        set(v) { prefs.edit().putString("partyDb", v).apply() }

    var apiKey: String
        get() = prefs.getString("partyKey", "").orEmpty()
        set(v) { prefs.edit().putString("partyKey", v.trim()).apply() }

    var name: String
        get() = prefs.getString("partyName", "").orEmpty()
        set(v) { prefs.edit().putString("partyName", v.trim()).apply() }

    /** Foto de perfil da sala (chave de [Avatars]). */
    var avatar: String
        get() = prefs.getString("partyAvatar", "").orEmpty()
        set(v) { prefs.edit().putString("partyAvatar", v).apply() }

    val ready: Boolean get() = dbUrl.isNotBlank() && apiKey.isNotBlank()

    fun db() = PartyDb(dbUrl, AppGraph.http)

    /**
     * O iPhone dela não entra na sua conta do Google; ele lê o arquivo com a chave do site.
     * Isso só funciona se o filme (ou a pasta) estiver compartilhado como "qualquer pessoa com o link".
     * Usa o cliente SEM o seu login, para testar exatamente como o site vai ver.
     */
    suspend fun checkShared(fileId: String): ShareCheck = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("fields", "id").addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("key", apiKey).build()
            // Referer do site: a chave pode estar restrita a ele.
            val req = Request.Builder().url(url).header("Referer", PartySync.SITE).build()
            AppGraph.http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> ShareCheck.Ok
                    resp.code == 404 -> ShareCheck.NotShared
                    resp.code == 400 && body.contains("API key", true) -> ShareCheck.BadKey
                    resp.code == 403 && (body.contains("API key", true) || body.contains("referer", true) || body.contains("blocked", true)) -> ShareCheck.BadKey
                    resp.code == 403 -> ShareCheck.NotShared
                    else -> ShareCheck.Failed("Google respondeu ${resp.code}")
                }
            }
        }.getOrElse { ShareCheck.Failed(it.message ?: "sem internet") }
    }

    /**
     * Pede 1 byte do filme do jeito que o site pede. Descobre o bloqueio do Google
     * ("downloadQuotaExceeded") antes de a sala começar.
     */
    suspend fun checkDownload(fileId: String): ShareCheck = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId".toHttpUrl().newBuilder()
                .addQueryParameter("alt", "media").addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("key", apiKey).build()
            val req = Request.Builder().url(url).header("Referer", PartySync.SITE).header("Range", "bytes=0-0").build()
            AppGraph.http.newCall(req).execute().use { resp ->
                val body = if (resp.isSuccessful) "" else resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> ShareCheck.Ok
                    resp.code == 403 && body.contains("rateLimit", true) -> ShareCheck.Ok // passageiro
                    resp.code == 403 && (body.contains("quota", true) || body.contains("abusive", true)) ->
                        ShareCheck.Blocked(com.dmwnezes.estante.drive.DriveApiException.from(403, body).reason)
                    resp.code == 404 || resp.code == 403 -> ShareCheck.NotShared
                    else -> ShareCheck.Ok
                }
            }
        }.getOrElse { ShareCheck.Ok }
    }

    /** Cópias de filmes feitas para destravar a sala (vão para a lixeira quando a sala fecha). */
    var roomCopies: Set<String>
        get() = prefs.getStringSet("partyCopies", emptySet()).orEmpty()
        set(v) { prefs.edit().putStringSet("partyCopies", v).apply() }

    /** Testa se o banco aceita gravar e ler (regras certas). */
    suspend fun checkDb(): String? = runCatching {
        val db = db()
        db.put("salas/_teste", JSONObject().put("ok", true))
        val back = db.get("salas/_teste") as? JSONObject
        db.delete("salas/_teste")
        if (back?.optBoolean("ok") == true) null else "O banco não devolveu o teste."
    }.getOrElse { it.message ?: "Não consegui falar com o Firebase." }
}
