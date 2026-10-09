package com.dmwnezes.estante.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class DriveAccount(
    val connected: Boolean = false,
    val email: String? = null,
    val name: String? = null,
)

/**
 * Login do Google só com permissão de LEITURA do Drive (drive.readonly).
 * O token vale ~1 h; quando vence, o app pede outro em silêncio, sem mostrar tela.
 */
class DriveAuth(context: Context) {

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_LIFE_MS = 45 * 60 * 1000L
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("drive", Context.MODE_PRIVATE)
    private val client get() = Identity.getAuthorizationClient(app)

    private val _account = MutableStateFlow(
        DriveAccount(prefs.getBoolean("connected", false), prefs.getString("email", null), prefs.getString("name", null))
    )
    val account: StateFlow<DriveAccount> = _account.asStateFlow()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .build()

    /**
     * Começa o login. Se o Google precisar mostrar a tela de escolher conta/permitir,
     * devolve o PendingIntent para a tela abrir; senão já guarda o token e devolve null.
     */
    suspend fun begin(): PendingIntent? {
        val r = client.authorize(request()).await()
        if (r.hasResolution()) return r.pendingIntent
        onToken(r.accessToken)
        return null
    }

    /** Resultado da tela do Google. */
    fun finish(data: Intent?) {
        val r = client.getAuthorizationResultFromIntent(data)
        onToken(r.accessToken)
    }

    private fun onToken(t: String?) {
        if (t.isNullOrBlank()) error("O Google não devolveu permissão")
        token = t
        tokenAt = System.currentTimeMillis()
        prefs.edit().putBoolean("connected", true).apply()
        _account.value = _account.value.copy(connected = true)
    }

    fun setProfile(email: String?, name: String?) {
        prefs.edit().putString("email", email).putString("name", name).apply()
        _account.value = _account.value.copy(email = email, name = name)
    }

    /**
     * Token válido para chamar o Drive. Bloqueia a thread (usar fora da thread principal).
     * [force] descarta o atual (quando o Drive respondeu 401). Devolve null se a pessoa
     * precisar entrar de novo.
     */
    fun tokenBlocking(force: Boolean = false): String? {
        if (!_account.value.connected) return null
        val fresh = { token != null && System.currentTimeMillis() - tokenAt < TOKEN_LIFE_MS }
        if (!force && fresh()) return token
        synchronized(this) {
            if (!force && fresh()) return token
            if (force) token?.let { old -> runCatching { GoogleAuthUtil.clearToken(app, old) } }
            val r = runCatching { Tasks.await(client.authorize(request()), 30, TimeUnit.SECONDS) }.getOrNull() ?: return null
            if (r.hasResolution() || r.accessToken.isNullOrBlank()) return null
            token = r.accessToken
            tokenAt = System.currentTimeMillis()
            return token
        }
    }

    fun disconnect() {
        token?.let { old -> runCatching { GoogleAuthUtil.clearToken(app, old) } }
        token = null
        tokenAt = 0
        prefs.edit().clear().apply()
        _account.value = DriveAccount()
    }

    /** Mensagem amigável para os erros mais comuns do login. */
    fun explain(e: Throwable): String {
        val code = (e as? ApiException)?.statusCode
        return when (code) {
            CommonStatusCodes.DEVELOPER_ERROR ->
                "O Google ainda não reconhece o app. Falta registrar o pacote e a impressão digital (SHA-1) no Google Cloud — veja em Ajustes > Configurar o Google."
            CommonStatusCodes.NETWORK_ERROR -> "Sem internet. Confira a conexão e tente de novo."
            CommonStatusCodes.CANCELED, 16 -> "Login cancelado."
            else -> "Não consegui entrar no Google (${code ?: e.javaClass.simpleName}). Tente de novo."
        }
    }

    /** Impressão digital SHA-1 da chave do app, no formato que o Google Cloud pede. */
    fun signingSha1(): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching "?"
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrDefault("?")

    val packageName: String get() = app.packageName
}
