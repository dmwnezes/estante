package com.dmwnezes.estante.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Deixa um arquivo do Drive como "qualquer pessoa com o link · leitor" — o que o site da sala
 * precisa para tocar o filme no celular de quem não está na sua conta.
 */
object DriveShare {

    /** Já está liberado ou acabou de ser liberado. */
    suspend fun makePublic(http: OkHttpClient, token: String, fileId: String) = withContext(Dispatchers.IO) {
        val url = "https://www.googleapis.com/drive/v3/files/$fileId/permissions?supportsAllDrives=true&sendNotificationEmail=false&fields=id"
        val body = JSONObject().put("role", "reader").put("type", "anyone").put("allowFileDiscovery", false).toString()
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw DriveApiException.from(resp.code, resp.body?.string().orEmpty())
        }
    }

    /**
     * Faz uma cópia do arquivo no Drive (mesma pasta). Cópia nova = limite de downloads novo:
     * é o jeito de destravar um filme que o Google bloqueou para quem assiste pelo link.
     */
    suspend fun copy(http: OkHttpClient, token: String, fileId: String, name: String?): String = withContext(Dispatchers.IO) {
        val slow = http.newBuilder().readTimeout(java.time.Duration.ofMinutes(4)).callTimeout(java.time.Duration.ofMinutes(5)).build()
        val body = JSONObject().apply { if (!name.isNullOrBlank()) put("name", name) }.toString()
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId/copy?supportsAllDrives=true&fields=id")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        slow.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DriveApiException.from(resp.code, text)
            JSONObject(text).getString("id")
        }
    }

    /** Manda para a lixeira (dá para recuperar por 30 dias). */
    suspend fun trash(http: OkHttpClient, token: String, fileId: String) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?supportsAllDrives=true&fields=id")
            .header("Authorization", "Bearer $token")
            .patch(JSONObject().put("trashed", true).toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw DriveApiException.from(resp.code, resp.body?.string().orEmpty())
        }
    }

    /** Explica em português por que o Google não deixou compartilhar. */
    fun explain(e: Throwable): String {
        val api = e as? DriveApiException ?: return e.message ?: "Não consegui falar com o Google."
        val policy = "A conta do Google em que o filme está não permite compartilhar com qualquer pessoa (regra da empresa/escola). Compartilhe por outra conta ou mova o filme para o seu Drive pessoal."
        return when {
            api.reason == "insufficientFilePermissions" ->
                "Você não tem permissão para compartilhar esse arquivo (ele foi compartilhado com você por outra pessoa). Peça para o dono deixar como “qualquer pessoa com o link”."
            api.reason == "publishOutNotPermitted" || api.reason == "cannotShareTeamDriveWithNonPermittedUser" -> policy
            api.code == 403 && (api.detail.contains("domain", true) || api.detail.contains("policy", true)) -> policy
            api.reason == "sharingRateLimitExceeded" || api.code == 429 ->
                "O Google pediu uma pausa nos compartilhamentos. Espere um pouco e tente de novo."
            api.needsReconnect -> "O Google não deu a permissão de compartilhar. Toque de novo e marque a caixa do Google Drive."
            else -> api.friendly
        }
    }
}
