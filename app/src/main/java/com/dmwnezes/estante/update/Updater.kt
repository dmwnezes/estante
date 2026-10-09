package com.dmwnezes.estante.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dmwnezes.estante.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/** Uma versão publicada no GitHub. */
data class Release(
    val tag: String,          // "v1.0.6"
    val number: Int,          // 6 (mesmo número do versionCode gerado no GitHub)
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
)

/**
 * Busca a versão mais nova nas Releases do GitHub, baixa o APK e abre o instalador do Android.
 */
class Updater(private val http: OkHttpClient) {

    companion object {
        private const val API = "https://api.github.com/repos/dmwnezes/estante/releases/latest"

        val currentNumber: Int get() = BuildConfig.VERSION_CODE
        val currentName: String get() = BuildConfig.VERSION_NAME

        /** Número da versão a partir da tag: "v1.0.12" → 12. */
        fun numberFromTag(tag: String): Int = tag.substringAfterLast('.').filter(Char::isDigit).toIntOrNull() ?: 0

        /** Tira linhas técnicas do texto da versão (assinaturas de commit etc.). */
        fun cleanNotes(body: String): String = body.lines()
            .filterNot { it.startsWith("Co-Authored-By") || it.startsWith("Claude-Session") || it.contains("Estante.apk") }
            .joinToString("\n").trim()
    }

    suspend fun latest(): Release = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Estante-app")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("GitHub respondeu ${resp.code}")
            val o = JSONObject(resp.body?.string().orEmpty())
            val assets = o.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: error("Versão sem APK")
            val tag = o.optString("tag_name")
            Release(tag, numberFromTag(tag), cleanNotes(o.optString("body")), apk.getString("browser_download_url"), apk.optLong("size"))
        }
    }

    fun isNewer(r: Release): Boolean = r.number > currentNumber

    /** Baixa o APK para a pasta temporária do app, informando o progresso (0–1). */
    suspend fun download(context: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Estante-${r.tag}.apk")
        val req = Request.Builder().url(r.apkUrl).header("User-Agent", "Estante-app").build()
        http.newBuilder().readTimeout(java.time.Duration.ofSeconds(60)).build().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Download falhou (${resp.code})")
            val body = resp.body ?: error("Download vazio")
            val total = body.contentLength().takeIf { it > 0 } ?: r.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        file
    }

    /** O Android exige que o usuário permita uma vez que a Estante instale atualizações. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.arquivos", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
