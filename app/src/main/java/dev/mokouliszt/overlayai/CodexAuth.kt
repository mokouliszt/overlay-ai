package dev.mokouliszt.overlayai

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * 端末内で完結する Codex(ChatGPT) OAuth ログイン。
 *  - PKCE + localhost:1455 ループバック
 *  - ★ログイン処理はプロセス寿命スコープで実行（Activity破棄でも中断しない）
 *  - ★完了後はブラウザから intent:// でアプリを前面に戻す（BAL回避）
 *  - トークンは EncryptedSharedPreferences に暗号化保存
 */
class CodexAuth(context: Context) {

    companion object {
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val REDIRECT = "http://localhost:1455/auth/callback"
        private const val AUTHORIZE = "https://auth.openai.com/oauth/authorize"
        private const val TOKEN = "https://auth.openai.com/oauth/token"
        private const val PORT = 1455
        private const val PREFS = "codex_auth"
        const val SCHEME = "overlayai"
        const val PKG = "dev.mokouliszt.overlayai"
        // ブラウザからアプリを起こすためのディープリンク
        private const val RETURN_INTENT =
            "intent://auth-callback#Intent;scheme=$SCHEME;package=$PKG;end"

        // Activity から独立したプロセス寿命のスコープ
        private val loginScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // UI が購読する状態（複数Activityインスタンス間で共有）
        val loggedInFlow = MutableStateFlow(false)
        val errorFlow = MutableStateFlow<String?>(null)
    }

    private val appCtx = context.applicationContext
    private val http = Net.client(
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
    )

    private val prefs by lazy {
        val key = MasterKey.Builder(appCtx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appCtx, PREFS, key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init { loggedInFlow.value = isLoggedIn() }

    /** access があればログイン済みとみなす（即再起動でも維持されるように）。 */
    fun isLoggedIn(): Boolean = prefs.getString("access", null) != null

    /** ログイン開始。プロセススコープで走るので Activity が死んでも交換は完了する。 */
    fun startLogin(activity: Activity, onResult: (Boolean) -> Unit) {
        errorFlow.value = null
        loginScope.launch {
            val ok = try { runLogin(activity) }
                     catch (e: Exception) { errorFlow.value = "login: ${e.message}"; false }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private suspend fun runLogin(activity: Activity): Boolean {
        val verifier = randomUrlSafe(64)
        val challenge = s256(verifier)
        val state = randomUrlSafe(32)

        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT))
        }
        server.use { srv ->
            val codeDeferred = CompletableDeferred<String>()
            Thread {
                try {
                    while (!codeDeferred.isCompleted) {
                        val sock = srv.accept()
                        val line = sock.getInputStream().bufferedReader().readLine() ?: ""
                        val code = Regex("code=([^&\\s]+)").find(line)?.groupValues?.get(1)
                        val st = Regex("state=([^&\\s]+)").find(line)?.groupValues?.get(1)
                        val ok = code != null && st == state
                        // ★完了HTML：intent:// で自動的にアプリへ復帰（タップ用リンクも用意）
                        val body = if (ok)
                            "<!doctype html><meta charset=utf-8>" +
                            "<style>body{background:#1f1f1f;color:#fff;font-family:sans-serif;text-align:center;padding-top:38%}a{color:#b794ff;font-size:1.2em}</style>" +
                            "<script>setTimeout(function(){location.replace(\"$RETURN_INTENT\")},200)</script>" +
                            "<p>ログインが完了しました</p><p><a href=\"$RETURN_INTENT\">アプリに戻る</a></p>"
                        else "<!doctype html><meta charset=utf-8><p>failed</p>"
                        sock.getOutputStream().write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$body")
                                .toByteArray()
                        )
                        sock.close()
                        if (ok) codeDeferred.complete(code!!)
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true }.start()

            val url = Uri.parse(AUTHORIZE).buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("scope", "openid profile email offline_access")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("id_token_add_organizations", "true")
                .appendQueryParameter("codex_cli_simplified_flow", "true")
                .appendQueryParameter("state", state)
                .appendQueryParameter("originator", "codex_cli_rs")
                .build()

            withContext(Dispatchers.Main) {
                runCatching { CustomTabsIntent.Builder().build().launchUrl(activity, url) }
            }

            val code = try { withTimeout(300_000) { codeDeferred.await() } }
                       catch (_: Exception) { errorFlow.value = "timeout/cancelled"; return false }

            return exchangeCode(code, verifier)
        }
    }

    private fun exchangeCode(code: String, verifier: String): Boolean = postToken(
        FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT)
            .add("client_id", CLIENT_ID)
            .add("code_verifier", verifier)
            .build()
    )

    suspend fun accessToken(): String? = withContext(Dispatchers.IO) {
        val exp = prefs.getLong("exp", 0)
        if (System.currentTimeMillis() < exp - 60_000) prefs.getString("access", null)
        else if (refresh()) prefs.getString("access", null) else prefs.getString("access", null)
    }

    fun accountId(): String? = prefs.getString("account", null)

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        val rt = prefs.getString("refresh", null) ?: return@withContext false
        postToken(
            FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", CLIENT_ID)
                .add("refresh_token", rt)
                .add("scope", "openid profile email offline_access")
                .build()
        )
    }

    private fun postToken(body: FormBody): Boolean {
        return try {
            http.newCall(Request.Builder().url(TOKEN).post(body).build()).execute().use { res ->
                val text = res.body?.string() ?: ""
                if (!res.isSuccessful) { errorFlow.value = "token ${res.code}: ${text.take(200)}"; return false }
                val j = JSONObject(text)
                val access = j.optString("access_token", "")
                if (access.isEmpty()) { errorFlow.value = "no access_token in response"; return false }
                val id = j.optString("id_token", "")
                val refresh = j.optString("refresh_token", "")
                val expiresIn = j.optLong("expires_in", 3600)
                prefs.edit().apply {
                    putString("access", access)
                    if (id.isNotEmpty()) putString("id", id)
                    if (refresh.isNotEmpty()) putString("refresh", refresh)
                    putLong("exp", System.currentTimeMillis() + expiresIn * 1000)
                    extractAccountId(if (id.isNotEmpty()) id else access)?.let { putString("account", it) }
                }.apply()
                loggedInFlow.value = true
                true
            }
        } catch (e: Exception) { errorFlow.value = "token error: ${e.message}"; false }
    }

    fun logout() { prefs.edit().clear().apply(); loggedInFlow.value = false }

    private fun extractAccountId(jwt: String): String? {
        val claims = decodeJwt(jwt) ?: return null
        return claims.optJSONObject("https://api.openai.com/auth")?.optString("chatgpt_account_id")
    }

    private fun decodeJwt(jwt: String): JSONObject? = try {
        JSONObject(String(Base64.decode(jwt.split(".")[1], Base64.URL_SAFE or Base64.NO_WRAP)))
    } catch (_: Exception) { null }

    private fun randomUrlSafe(bytes: Int): String {
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun s256(verifier: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(d, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
