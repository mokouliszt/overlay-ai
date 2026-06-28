package dev.mokouliszt.overlayai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ChatGPT サブスク枠を端末から直接利用するクライアント（Android完結）。
 * chatgpt.com/backend-api/codex/responses に Responses スキーマで投げる。
 *
 * Codex CLI 相当:
 *  - Web検索 (web_search) 常時ON
 *  - shell を function ツールとして定義し、workspace-write サンドボックス(workspaceDir)で実行
 *  - --full-auto 相当: ツール呼び出しを自動承認・自動実行
 *
 * 旧 local_shell ホストツールは廃止されたため function ツール方式を採用。
 * tools が弾かれた場合は段階フォールバック（shell→web_searchのみ→なし）で基本チャットを維持。
 */
class DirectCodexClient(
    private val auth: CodexAuth,
    private val workspaceProvider: () -> File,
    private val webSearch: Boolean = true,
    private val skillManifestProvider: () -> String = { "" },
    private val prepareSkills: () -> Unit = {},
) : AiClient {

    companion object {
        private const val URL = "https://chatgpt.com/backend-api/codex/responses"
        private const val MAX_ITERS = 6
        private const val INSTRUCTIONS =
            "You are a concise, helpful assistant embedded in a phone overlay. " +
            "When the user attaches a screenshot, answer about what is visible on it. " +
            "Use web_search for up-to-date info when helpful. " +
            "You have a `shell` function tool that runs `/bin/sh -c <command>` in --full-auto mode " +
            "inside a workspace-write sandbox (the current working directory). Create/edit files there " +
            "as needed; commands are auto-approved. Note the environment is a minimal Android shell " +
            "(toybox: ls, cat, echo, mkdir, sed, grep ... ; no python/node). Keep shell usage minimal."
    }

    private val http = Net.client(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS) // SSE
    )

    override fun availableModels(): List<String> =
        listOf("gpt-5.5", "gpt-5.4", "gpt-5.3-codex", "gpt-5-codex-mini", "o3")

    private class ShellCall(val callId: String, val command: String)
    private class Turn(val items: List<JSONObject>, val shellCalls: List<ShellCall>, val toolLevel: Int)

    override fun ask(
        messages: List<ChatMessage>,
        model: String,
        effort: ReasoningEffort,
        imagePng: ByteArray?,
    ): Flow<String> = flow {
        val token = auth.accessToken() ?: error("未ログイン。設定からChatGPTログインしてください")
        val account = auth.accountId() ?: error("account_id を取得できません。再ログインしてください")

        runCatching { prepareSkills() } // workspace に skills/ を反映

        val input = JSONArray()
        buildInitialInput(messages, imagePng, input)

        var level = 2 // 2: shell+web_search / 1: web_searchのみ / 0: ツールなし
        for (iter in 0 until MAX_ITERS) {
            val turn = streamTurn(input, model, effort, token, account, this, level)
            level = turn.toolLevel // 通った構成を以降も使う
            if (turn.shellCalls.isEmpty()) return@flow
            turn.items.forEach { input.put(it) } // reasoning / function_call をそのまま返す
            for (call in turn.shellCalls) {
                emit("\n```\n$ " + call.command + "\n```\n")
                val out = withContext(Dispatchers.IO) { execShell(call.command) }
                input.put(JSONObject().apply {
                    put("type", "function_call_output")
                    put("call_id", call.callId)
                    put("output", out)
                })
            }
        }
        emit("\n⚠ ツール実行が上限(${MAX_ITERS})に達しました")
    }.flowOn(Dispatchers.IO)

    /** 1ターン送信。tools非対応の400なら toolLevel を下げて再試行（ストリーム開始前なので安全）。 */
    private suspend fun streamTurn(
        input: JSONArray, model: String, effort: ReasoningEffort,
        token: String, account: String, out: FlowCollector<String>, toolLevel: Int,
    ): Turn {
        val payload = buildBody(input, model, effort, toolLevel).toString()
        val req = Request.Builder().url(URL)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token")
            .header("ChatGPT-Account-Id", account)
            .header("Accept", "text/event-stream")
            .header("User-Agent", "codex_cli_rs/0.0.0")
            .header("originator", "codex_cli_rs")
            .header("OpenAI-Beta", "responses=experimental")
            .header("session_id", UUID.randomUUID().toString())
            .build()

        val items = ArrayList<JSONObject>()
        val shellCalls = ArrayList<ShellCall>()

        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) error("認証エラー(401)。再ログインが必要です")
            if (resp.code == 400 && toolLevel > 0) {
                val body = resp.body?.string().orEmpty()
                if (body.contains("tool", ignoreCase = true)) {
                    // ツールが非対応 → 1段下げて再試行（チャットは維持）
                    return streamTurn(input, model, effort, token, account, out, toolLevel - 1)
                }
                error("upstream 400: ${body.take(300)}")
            }
            if (!resp.isSuccessful) error("upstream ${resp.code}: ${resp.body?.string()?.take(200)}")
            val source = resp.body?.source() ?: error("empty body")
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val ev = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (ev.optString("type")) {
                    "response.output_text.delta" ->
                        ev.optString("delta").let { if (it.isNotEmpty()) out.emit(it) }
                    "response.output_item.done" -> {
                        val item = ev.optJSONObject("item") ?: continue
                        items.add(item)
                        parseShellCall(item)?.let { shellCalls.add(it) }
                    }
                    "response.failed", "response.error", "error" ->
                        out.emit("\n⚠ " + (ev.optJSONObject("error")?.optString("message") ?: "upstream error"))
                }
            }
        }
        return Turn(items, shellCalls, toolLevel)
    }

    private fun parseShellCall(item: JSONObject): ShellCall? {
        if (item.optString("type") != "function_call") return null
        if (item.optString("name") != "shell") return null
        val callId = item.optString("call_id").ifEmpty { item.optString("id") }
        val args = runCatching { JSONObject(item.optString("arguments", "{}")) }.getOrNull()
        val cmd = args?.optString("command")?.takeIf { it.isNotBlank() } ?: return null
        return ShellCall(callId, cmd)
    }

    /** workspace-write サンドボックス内でコマンドを実行（full-auto）。 */
    private fun execShell(command: String): String {
        val ws = workspaceProvider().apply { mkdirs() }
        return try {
            val p = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(ws).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } } }
            reader.start()
            val ok = p.waitFor(20, TimeUnit.SECONDS)
            if (!ok) p.destroyForcibly()
            reader.join(500)
            val text = sb.toString()
            (if (text.length > 8000) text.take(8000) + "\n…(truncated)" else text) +
                (if (!ok) "\n(timeout)" else "")
        } catch (e: Exception) {
            "exec error: ${e.message}"
        }
    }

    // ---- リクエスト構築 ----

    private fun buildInitialInput(messages: List<ChatMessage>, imagePng: ByteArray?, input: JSONArray) {
        messages.forEachIndexed { i, m ->
            val isUser = m.role == Role.USER || m.role == Role.SYSTEM
            val content = JSONArray().apply {
                put(JSONObject().apply {
                    put("type", if (isUser) "input_text" else "output_text")
                    put("text", m.content)
                })
                if (i == messages.lastIndex && imagePng != null && isUser) {
                    put(JSONObject().apply {
                        put("type", "input_image")
                        put("image_url", "data:image/png;base64," + Base64.encodeToString(imagePng, Base64.NO_WRAP))
                    })
                }
            }
            input.put(JSONObject().apply {
                put("type", "message")
                put("role", m.role.name.lowercase())
                put("content", content)
            })
        }
    }

    private fun shellTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("name", "shell")
        put("description", "Run a shell command via /bin/sh -c in the workspace-write sandbox (cwd). Auto-approved.")
        put("parameters", JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("command", JSONObject().apply {
                    put("type", "string")
                    put("description", "The shell command line to execute")
                })
            })
            put("required", JSONArray().put("command"))
            put("additionalProperties", false)
        })
    }

    private fun webSearchTool(): JSONObject = JSONObject().put("type", "web_search")

    private fun buildBody(input: JSONArray, model: String, effort: ReasoningEffort, toolLevel: Int): JSONObject {
        val tools = JSONArray()
        if (toolLevel >= 2) tools.put(shellTool())          // 最初に外す候補
        if (toolLevel >= 1 && webSearch) tools.put(webSearchTool())
        return JSONObject().apply {
            put("model", model)
            put("instructions", INSTRUCTIONS + skillManifestProvider())
            put("input", input)
            if (tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
                put("parallel_tool_calls", false)
            }
            // store:false でツール継続するため、推論を暗号化込みでインライン返送できるようにする
            put("include", JSONArray().put("reasoning.encrypted_content"))
            put("store", false)
            put("stream", true)
            put("reasoning", JSONObject().put("effort", effort.wire))
        }
    }
}
