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
