package com.dmwnezes.estante.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Um resultado da busca no TMDB (filme ou série). */
data class TmdbResult(
    val id: Int,
    val isSeries: Boolean,
    val title: String,
    val year: String?,
    val overview: String?,
    val posterPath: String?,
    val genreIds: List<Int>,
) {
    val posterUrl: String? get() = posterPath?.let { "${Tmdb.IMAGES}w500$it" }
    val thumbUrl: String? get() = posterPath?.let { "${Tmdb.IMAGES}w185$it" }
}

/**
 * Capas, sinopse, ano e gêneros pelo The Movie Database (themoviedb.org).
 * Precisa de uma chave grátis do TMDB, colada em Ajustes (chave v3 ou token de leitura v4).
 */
class Tmdb(context: Context, private val http: OkHttpClient) {

    companion object {
        const val API = "https://api.themoviedb.org/3"
        const val IMAGES = "https://image.tmdb.org/t/p/"

        /** Junk comum em nomes de arquivo que atrapalha a busca. */
        private val junk = Regex(
            """(?i)\b(1080p|720p|480p|2160p|4k|uhd|hdr|x264|x265|h\.?264|h\.?265|hevc|bluray|blu-ray|brrip|bdrip|webrip|web-dl|web|hdtv|dvdrip|aac|ac3|dts|5\.1|dublado|legendado|dual|audio|nacional|completo|rip|yify|rarbg)\b.*"""
        )
        private val yearRegex = Regex("""\b(19\d{2}|20\d{2})\b""")

        /** "O.Grande.Filme.2019.1080p.BluRay" → ("O Grande Filme", "2019"). */
        fun cleanQuery(title: String): Pair<String, String?> {
            var t = title.replace('_', ' ').replace('.', ' ')
            val year = yearRegex.find(t)?.value
            t = t.replace(junk, " ")
            if (year != null) t = t.substringBefore(year)
            t = t.replace(Regex("""[\[\(].*?[\]\)]"""), " ")
                .replace(Regex("""(?i)\bs\d{1,2}\s*e\d{1,3}\b.*"""), " ")
                .replace(Regex("""\s+"""), " ").trim().trim('-', ' ')
            return (t.ifBlank { title }) to year
        }

        fun parseResults(json: JSONObject): List<TmdbResult> {
            val arr = json.optJSONArray("results") ?: return emptyList()
            return (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("media_type", "movie") in setOf("movie", "tv") }
                .map { o ->
                    val tv = o.optString("media_type") == "tv"
                    val date = (if (tv) o.optString("first_air_date") else o.optString("release_date")).take(4)
                    val g = o.optJSONArray("genre_ids")
                    TmdbResult(
                        id = o.optInt("id"),
                        isSeries = tv,
                        title = (if (tv) o.optString("name") else o.optString("title")).ifBlank { o.optString("original_title") },
                        year = date.takeIf { it.length == 4 },
                        overview = o.optString("overview").ifBlank { null },
                        posterPath = o.optString("poster_path").takeIf { it.startsWith("/") },
                        genreIds = g?.let { a -> (0 until a.length()).map { a.getInt(it) } } ?: emptyList(),
                    )
                }
        }
    }

    private val prefs = context.applicationContext.getSharedPreferences("tmdb", Context.MODE_PRIVATE)
    private var genres: Map<Int, String>? = null

    var key: String
        get() = prefs.getString("key", "").orEmpty()
        set(v) { prefs.edit().putString("key", v.trim()).apply(); genres = null }

    val hasKey: Boolean get() = key.isNotBlank()

    /** Token v4 (longo, começa com "eyJ") vai no cabeçalho; chave v3 vai no endereço. */
    private fun request(path: String, params: Map<String, String>): Request {
        val b = "$API$path".toHttpUrl().newBuilder().addQueryParameter("language", "pt-BR")
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val k = key
        val bearer = k.length > 60
        if (!bearer) b.addQueryParameter("api_key", k)
        return Request.Builder().url(b.build()).apply { if (bearer) header("Authorization", "Bearer $k") }.build()
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject = withContext(Dispatchers.IO) {
        if (!hasKey) throw IOException("Sem chave do TMDB")
        http.newCall(request(path, params)).execute().use { resp ->
            if (resp.code == 401) throw IOException("A chave do TMDB não foi aceita. Confira em Ajustes.")
            if (!resp.isSuccessful) throw IOException("TMDB respondeu ${resp.code}")
            JSONObject(resp.body?.string().orEmpty())
        }
    }

    /** Busca filmes e séries pelo título do vídeo. [seriesFirst] para caixas de série. */
    suspend fun search(title: String, seriesFirst: Boolean = false): List<TmdbResult> {
        val (q, year) = cleanQuery(title)
        val all = parseResults(get("/search/multi", mapOf("query" to q, "include_adult" to "false")))
        return all.sortedWith(
            compareByDescending<TmdbResult> { it.posterPath != null }
                .thenByDescending { year != null && it.year == year }
                .thenByDescending { it.isSeries == seriesFirst }
        ).take(18)
    }

    /** Nomes dos gêneros em português (busca a lista uma vez). */
    suspend fun genreNames(ids: List<Int>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val map = genres ?: runCatching {
            val m = mutableMapOf<Int, String>()
            for (kind in listOf("movie", "tv")) {
                val arr = get("/genre/$kind/list").optJSONArray("genres") ?: continue
                for (i in 0 until arr.length()) arr.getJSONObject(i).let { m[it.optInt("id")] = it.optString("name") }
            }
            m.toMap()
        }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) genres = it }
        return ids.mapNotNull { map[it] }.distinct().take(3)
    }

    /** Testa a chave colada em Ajustes. */
    suspend fun check(): Boolean = runCatching { get("/configuration"); true }.getOrDefault(false)
}
