package dev.mokouliszt.overlayai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/**
 * チャットパネル（WebView: React+shadcn）のホスト。
 *  - パネル全体を「少し長押し→ドラッグ」で移動、2本指ピンチで拡縮（画面内クランプ）
 *  - 入力時はIMEに隠れないようパネルを上へ寄せる（WindowInsets.ime）
 *  - 普段は窓を非フォーカス化（入力を奪わない）。入力欄タップ時のみフォーカス可能化
 *  - file:// の crossorigin モジュールを読めるよう allowUniversalAccessFromFileURLs を有効化
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
class OverlayWebHost(context: Context, private val bridge: OverlayBridge) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    private val dm = context.resources.displayMetrics
    private var screenW = dm.widthPixels
    private var screenH = dm.heightPixels
    private fun dp(v: Int) = (v * dm.density).toInt()
    private val minW = dp(220)
    private val minH = dp(280)

    private val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)

    private val web = WebView(themed).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        webViewClient = WebViewClient()
        addJavascriptInterface(JsBridge(), "Android")
        loadUrl("file:///android_asset/webui/index.html")
    }

    private val container = PanelLayout().apply { orientation = LinearLayout.VERTICAL }

    init {
        container.addView(
            web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
        )
        // IMEで入力欄が隠れないようにパネルを持ち上げる
        container.setOnApplyWindowInsetsListener { _, insets ->
            val ime = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            handleIme(ime)
            insets
        }
    }

    /** パネル全体：非操作領域は即ドラッグで移動、2本指ピンチで拡縮。
     *  「ドラッグ可否」は JS 側が触れた要素を見て setDragAllowed() で通知する。 */
    private inner class PanelLayout : LinearLayout(themed) {
        private val touchSlop = ViewConfiguration.get(themed).scaledTouchSlop
        @Volatile private var dragAllowed = false
        private var dragging = false
        private var pinching = false
        private var downRawX = 0f; private var downRawY = 0f
        private var startX = 0; private var startY = 0

        /** JS から: 直近の touchstart 位置がドラッグ可能領域だったか。 */
        fun allowDrag(b: Boolean) { dragAllowed = b }

        private val scaleDetector = ScaleGestureDetector(
            themed,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val p = lp ?: return true
                    // 指の配置で軸を分岐：横並び→横方向に拡縮、縦並び→縦方向に拡縮
                    val sx = d.currentSpanX; val sy = d.currentSpanY
                    val psx = d.previousSpanX; val psy = d.previousSpanY
                    if (sx >= sy) {
                        if (psx > 0f) p.width = (p.width * (sx / psx)).toInt().coerceIn(minW, screenW)
                    } else {
                        if (psy > 0f) p.height = (p.height * (sy / psy)).toInt().coerceIn(minH, screenH)
                    }
                    clampPos(p)
                    runCatching { wm?.updateViewLayout(this@PanelLayout, p) }
                    return true
                }
            }
        )

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pinching = false; dragging = false; dragAllowed = false
                    downRawX = ev.rawX; downRawY = ev.rawY
                    startX = lp?.x ?: 0; startY = lp?.y ?: 0
                }
                MotionEvent.ACTION_POINTER_DOWN -> { pinching = true; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (pinching) return true
                    // JS が「ドラッグ可」と判定した領域で、しきい値を超えて動いたら横取り
                    if (dragAllowed && !dragging &&
                        (abs(ev.rawX - downRawX) > touchSlop || abs(ev.rawY - downRawY) > touchSlop)
                    ) {
                        dragging = true
                        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        return true
                    }
                    if (dragging) return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pinching = false; dragging = false; dragAllowed = false
                }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (pinching || ev.pointerCount >= 2) {
                pinching = true
                scaleDetector.onTouchEvent(ev)
            } else if (dragging && ev.actionMasked == MotionEvent.ACTION_MOVE) {
                val p = lp
                if (p != null) {
                    p.x = startX + (ev.rawX - downRawX).toInt()
                    p.y = startY + (ev.rawY - downRawY).toInt()
                    clampPos(p)
                    runCatching { wm?.updateViewLayout(this, p) }
                }
            }
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                pinching = false; dragging = false; dragAllowed = false
            }
            return true
        }
    }

    private var preImeY: Int? = null
    private fun handleIme(ime: Int) {
        val p = lp ?: return
        val w = wm ?: return
        if (ime > 0) {
            val loc = IntArray(2); container.getLocationOnScreen(loc)
            val bottom = loc[1] + container.height
            val limit = screenH - ime
            if (bottom > limit) {
                if (preImeY == null) preImeY = p.y
                p.y = (p.y - (bottom - limit) - dp(8)).coerceAtLeast(0)
                runCatching { w.updateViewLayout(container, p) }
            }
        } else {
            preImeY?.let {
                p.y = it.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
                preImeY = null
                runCatching { w.updateViewLayout(container, p) }
            }
        }
    }

    private fun clampPos(p: WindowManager.LayoutParams) {
        p.x = p.x.coerceIn(0, (screenW - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenH - p.height).coerceAtLeast(0))
    }

    fun attach(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.wm = wm
        this.lp = params
        wm.addView(container, params)
    }

    fun detach(wm: WindowManager) {
        scope.cancel()
        runCatching { wm.removeView(container) }
        web.destroy()
    }

    /** 最小化：窓から外すだけ（WebView/状態は破棄しない）。 */
    fun hide(wm: WindowManager) {
        runCatching { wm.removeView(container) }
    }

    /** 再表示：保持していた lp で再アタッチ（チャット/WS内容はそのまま）。 */
    fun show(wm: WindowManager) {
        val p = lp ?: return
        clampPos(p)
        runCatching { wm.addView(container, p) }
    }

    fun rootVisible(visible: Boolean) {
        container.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /** 現在のパネル左上座標（折りたたみ時に黒丸をここへ移すため）。 */
    fun currentPos(): Pair<Int, Int>? {
        val p = lp ?: return null
        return p.x to p.y
    }

    /** 回転時：画面サイズを更新し、移動範囲・位置を新画面に合わせる。 */
    fun onConfigChanged(w: Int, h: Int) {
        screenW = w; screenH = h
        val p = lp ?: return
        p.width = p.width.coerceAtMost(w)
        p.height = p.height.coerceAtMost(h)
        clampPos(p)
        runCatching { wm?.updateViewLayout(container, p) }
    }

    private fun setFocusable(focusable: Boolean) {
        val p = lp ?: return
        val w = wm ?: return
        val base = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        p.flags = if (focusable) base else base or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        runCatching { w.updateViewLayout(container, p) }
    }

    private fun toJs(fn: String, arg: String) {
        val quoted = JSONObject.quote(arg)
        web.post { web.evaluateJavascript("window.$fn && window.$fn($quoted)", null) }
    }

    private inner class JsBridge {

        @JavascriptInterface
        fun getModels(): String = JSONArray(bridge.ai.availableModels()).toString()

        @JavascriptInterface
        fun collapse() { web.post { bridge.collapse() } }

        @JavascriptInterface
        fun closeOverlay() { web.post { bridge.closeOverlay() } }

        @JavascriptInterface
        fun requestKeyboard() { web.post { setFocusable(true) } }

        @JavascriptInterface
        fun dismissKeyboard() { web.post { setFocusable(false) } }

        @JavascriptInterface
        fun setScreenShare(on: Boolean) { if (on) web.post { bridge.requestProjection() } }

        /** JS から: 触れた要素がドラッグ可能領域か（ボタン/入力/スクロール領域でない）を通知。 */
        @JavascriptInterface
        fun setDragAllowed(allowed: Boolean) { container.allowDrag(allowed) }

        /** payload: {messages:[{role,content}], model, effort, attachScreen} */
        @JavascriptInterface
        fun sendMessage(payload: String) {
            val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return
            val model = obj.optString("model")
            val effortWire = obj.optString("effort", "medium")
            val effort = ReasoningEffort.values().firstOrNull { it.wire == effortWire } ?: ReasoningEffort.MEDIUM
            val attach = obj.optBoolean("attachScreen", false)
            val arr = obj.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                ChatMessage(
                    role = when (m.optString("role")) {
                        "assistant" -> Role.ASSISTANT
                        "system" -> Role.SYSTEM
                        else -> Role.USER
                    },
                    content = m.optString("content")
                )
            }
            scope.launch {
                val image = if (attach) bridge.captureScreen() else null
                bridge.ai.ask(messages, model, effort, image)
                    .catch { e -> toJs("__onError", e.message ?: "error") }
                    .collect { delta -> toJs("__onDelta", delta) }
                web.post { web.evaluateJavascript("window.__onDone && window.__onDone()", null) }
            }
        }
    }
}
