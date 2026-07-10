package dev.mokouliszt.overlayai

import kotlinx.coroutines.flow.Flow

/**
 * バックエンド差し替えのための境界。
 * - 推奨実装: [CodexBridgeClient]（codexapp / opencode server / 自作proxy 経由でサブスク枠を利用）
 * - 代替: 標準 OpenAI API キーを使う OpenAiApiClient（要APIキー・課金）
 * - 代替: Kotlin から直接 Codex backend を叩く DirectCodexClient（未文書・壊れやすい）
 */
interface AiClient {

    /**
     * 1ターン分を投げてトークンを逐次受け取る（ストリーミング）。
     * 会話履歴 [messages] は呼び出し側が保持する。永続化はしない（揮発仕様）。
     *
     * @param messages これまでの会話（最後がユーザの新規発話）
     * @param model    UI で選択中のモデル（例 "gpt-5.2-codex"）
     * @param effort   推論レベル
     * @param imagePng 画面キャプチャ。質問した瞬間の画面を添付したい時のみ非null
     * @return 応答トークンの Flow。完了で正常終了、失敗で例外を流す。
     */
    fun ask(
        messages: List<ChatMessage>,
        model: String,
        effort: ReasoningEffort,
        imagePng: ByteArray? = null,
    ): Flow<String>

    /** UI のモデル選択ボタンに出す候補。バックエンドが返せれば動的取得でもよい。 */
    fun availableModels(): List<String>
}

data class ChatMessage(
    val role: Role,
    val content: String,
)

enum class Role { USER, ASSISTANT, SYSTEM }

/** Codex backend の reasoning.effort に対応。xhigh/max は GPT-5.6 系のみ（UI 側で制限）。 */
enum class ReasoningEffort(val wire: String, val label: String) {
    MINIMAL("minimal", "最小"),
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    XHIGH("xhigh", "超高"),
    MAX("max", "最大");
}
