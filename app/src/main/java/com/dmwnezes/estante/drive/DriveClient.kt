package com.dmwnezes.estante.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

/** Um item de uma pasta do Drive: subpasta ou vídeo. */
data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val thumbnail: String? = null,
    val sizeBytes: Long = 0,
    val durationMs: Long = 0,
) {
    val isFolder: Boolean get() = mimeType == DriveQuery.FOLDER
}

/** Montagem das buscas no formato da API do Drive (testável sem internet). */
object DriveQuery {
    const val FOLDER = "application/vnd.google-apps.folder"
    private const val MEDIA = "(mimeType = '$FOLDER' or mimeType contains 'video/')"

    /** Aspas e barras dentro do texto precisam de escape. */
    fun escape(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'")

    fun children(folderId: String) = "'${escape(folderId)}' in parents and trashed = false and $MEDIA"

    fun sharedWithMe() = "sharedWithMe = true and trashed = false and $MEDIA"

    fun search(text: String) = "name contains '${escape(text.trim())}' and trashed = false and mimeType contains 'video/'"

    const val FIELDS = "nextPageToken,files(id,name,mimeType,thumbnailLink,size,videoMediaMetadata(durationMillis))"

    fun parseItems(json: JSONObject): List<DriveItem> {
        val files = json.optJSONArray("files") ?: return emptyList()
        return (0 until files.length()).map { i ->
            val f = files.getJSONObject(i)
            DriveItem(
                id = f.getString("id"),
                name = f.optString("name"),
                mimeType = f.optString("mimeType"),
                thumbnail = f.optString("thumbnailLink").ifBlank { null },
                sizeBytes = f.optString("size").toLongOrNull() ?: 0L,
                durationMs = f.optJSONObject("videoMediaMetadata")?.optString("durationMillis")?.toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * Coloca o token do Google em toda chamada ao Drive (inclusive no streaming do vídeo
 * e nas miniaturas). Se o Drive responder 401, renova o token e tenta uma vez de novo.
 */
class DriveTokenInterceptor(private val auth: DriveAuth) : Interceptor {
    private fun isGoogle(host: String) =
        host.endsWith("googleapis.com") || host.endsWith("googleusercontent.com") || host == "drive.google.com"

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!isGoogle(req.url.host)) return chain.proceed(req)
        val token = auth.tokenBlocking() ?: throw IOException("Google Drive desconectado. Entre de novo em Ajustes.")
        val first = chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
        if (first.code != 401) return first
        first.close()
        val again = auth.tokenBlocking(force = true) ?: throw IOException("Google Drive desconectado. Entre de novo em Ajustes.")
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $again").build())
    }
}

class DriveClient(private val http: OkHttpClient) {

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        fun streamUrl(fileId: String) = "$API/files/$fileId?alt=media&supportsAllDrives=true"
    }

    private suspend fun get(url: okhttp3.HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("Drive respondeu ${resp.code}")
            JSONObject(body)
        }
    }

    /** Lista tudo (passando por todas as páginas): pastas primeiro, depois por nome. */
    private suspend fun list(q: String, orderBy: String = "folder,name_natural"): List<DriveItem> {
        val out = mutableListOf<DriveItem>()
        var page: String? = null
        do {
            val url = "$API/files".toHttpUrl().newBuilder()
                .addQueryParameter("q", q)
                .addQueryParameter("fields", DriveQuery.FIELDS)
                .addQueryParameter("orderBy", orderBy)
                .addQueryParameter("pageSize", "200")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build()
            val json = get(url)
            out += DriveQuery.parseItems(json)
            page = json.optString("nextPageToken").ifBlank { null }
        } while (page != null && out.size < 2000)
        return out
    }

    suspend fun children(folderId: String = "root") = list(DriveQuery.children(folderId))
    suspend fun sharedWithMe() = list(DriveQuery.sharedWithMe())
    suspend fun search(text: String) = list(DriveQuery.search(text), orderBy = "name_natural")

    /** Todos os vídeos dentro da pasta e das subpastas (até [maxDepth] níveis). */
    suspend fun videosDeep(folderId: String, maxDepth: Int = 3): List<DriveItem> {
        val items = children(folderId)
        val videos = items.filterNot { it.isFolder }.toMutableList()
        if (maxDepth > 0) items.filter { it.isFolder }.forEach { videos += videosDeep(it.id, maxDepth - 1) }
        return videos
    }

    /** Dados de um arquivo (para buscar a miniatura de novo). */
    suspend fun file(id: String): DriveItem {
        val url = "$API/files/$id".toHttpUrl().newBuilder()
            .addQueryParameter("fields", "id,name,mimeType,thumbnailLink,size,videoMediaMetadata(durationMillis)")
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        return DriveQuery.parseItems(JSONObject().put("files", org.json.JSONArray().put(get(url)))).first()
    }

    /** E-mail e nome da conta conectada. */
    suspend fun about(): Pair<String?, String?> {
        val url = "$API/about".toHttpUrl().newBuilder().addQueryParameter("fields", "user(emailAddress,displayName)").build()
        val user = get(url).optJSONObject("user")
        return user?.optString("emailAddress") to user?.optString("displayName")
    }
}
