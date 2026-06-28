package dev.mokouliszt.overlayai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Codex Bridge（codexapp / opencode server / 自作proxy）に対するクライアント。
 *
 * ブリッジ側の想定契約（自分で薄く実装するか codexapp をこの形に寄せる）:
 *   POST {baseUrl}/chat
 *   body: { "model": "...", "effort": "medium",
 *           "messages": [ {"role":"user","content":"...","image":"<base64 png>?"} ] }
 *   resp: text/event-stream  ("data: {\"delta\":\"...\"}\n\n" を連続、最後に "data: [DONE]")
 *
 * ※ ブリッジ側で codex login 済みの OAuth トークンを保持し、サブスク枠へ中継する。
 *   端末からトークンを持たないので、企業ネット下の端末でも秘匿が楽。
 */
class CodexBridgeClient(
    private val baseUrl: String,
    private val authToken: String? = null, // ブリッジ自体のアクセス制御用（任意）
) : AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // SSE のため無制限
        .build()

    // ブリッジの /models と揃える。起動後に GET /models で動的取得してもよい。
    override fun availableModels(): List<String> = listOf(
        "gpt-5.5",
        "gpt-5.4",
        "gpt-5.3-codex",
        "gpt-5-codex-mini",
        "o3",
    )

    override fun ask(
        messages: List<ChatMessage>,
        model: String,
        effort: ReasoningEffort,
        imagePng: ByteArray?,
    ): Flow<String> = flow {
        val payload = JSONObject().apply {
            put("model", model)
            put("effort", effort.wire)
            put("messages", JSONArray().apply {
                messages.forEachIndexed { i, m ->
                    put(JSONObject().apply {
                        put("role", m.role.name.lowercase())
                        put("content", m.content)
                        // 画像は最後のユーザ発話にだけ添付
                        if (i == messages.lastIndex && imagePng != null) {
                            put("image", Base64.encodeToString(imagePng, Base64.NO_WRAP))
                        }
                    })
                }
            })
        }

        val req = Request.Builder()
            .url("$baseUrl/chat")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .apply { authToken?.let { header("Authorization", "Bearer $it") } }
            .build()

        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("bridge HTTP ${resp.code}")
            val source = resp.body?.source() ?: error("empty body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isEmpty()) continue
                val delta = runCatching { JSONObject(data).optString("delta") }.getOrDefault("")
                if (delta.isNotEmpty()) emit(delta)
            }
        }
    }.flowOn(Dispatchers.IO)
}
