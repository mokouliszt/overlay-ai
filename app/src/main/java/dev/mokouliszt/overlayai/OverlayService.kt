package dev.mokouliszt.overlayai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * オーバーレイ常駐サービス。
 *  - 黒丸FAB(+/−)の表示・ドラッグ・ゴミ箱への投棄で自滅
 *  - タップで縦長チャットパネル(Compose)を開閉
 *  - MediaProjection を保持し、要求時に1フレーム取得
 *
 * チャット内容は OverlayComposeHost 内の state にだけ存在し、
 * サービス停止(=バブル削除/アプリ終了)で破棄される（揮発・履歴なし）。
 */
class OverlayService : Service(), OverlayBridge {

    companion object {
        const val ACTION_START = "start"
        const val ACTION_PROJECTION_GRANTED = "projection_granted"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "overlay_ai"
        private const val NOTIF_ID = 1001
        const val PREFS = "overlay_cfg"
        const val KEY_RESET_WS = "reset_workspace"

        /** 実アプリ(MainActivity)から稼働中か判定するため。 */
        @JvmField @Volatile
        var isRunning = false
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var wm: WindowManager

    private lateinit var bubble: View
    private lateinit var bubbleLabel: TextView
    private lateinit var bubbleParams: WindowManager.LayoutParams

    private var trash: View? = null
    private var trashCircle: View? = null
    private var trashParams: WindowManager.LayoutParams? = null

    private var host: OverlayWebHost? = null
    private var panelShown = false

    private var projection: MediaProjection? = null
    private var capture: ScreenCapture? = null

    private lateinit var workspaceDir: File
    private val skills by lazy { SkillManager(filesDir) }

    // ---- バックエンド：端末完結。Web検索常時ON＋local_shellをworkspaceで実行 ----
    override val ai: AiClient by lazy {
        DirectCodexClient(
            CodexAuth(applicationContext),
            workspaceProvider = { workspaceDir },
            webSearch = true,
            skillManifestProvider = { skills.manifest() },
            prepareSkills = { skills.copyInto(workspaceDir) },
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        setupWorkspace()
        try {
            // 起動時は specialUse 型で前景化（mediaProjection はまだ許可が無いので使えない）
            startForegroundCompat(initialFgsType())
            addBubble()
            isRunning = true
        } catch (e: Exception) {
            Toast.makeText(this, "\u30aa\u30fc\u30d0\u30fc\u30ec\u30a4\u8d77\u52d5\u5931\u6557: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    /** チャット開始時にワークスペースを用意。トグルがONなら前回内容をリセット。 */
    private fun setupWorkspace() {
        workspaceDir = File(filesDir, "workspace")
        val reset = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RESET_WS, true)
        if (reset && workspaceDir.exists()) workspaceDir.deleteRecursively()
        workspaceDir.mkdirs()
    }

    private fun initialFgsType(): Int =
        if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION

    private fun startForegroundCompat(type: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(), type)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PROJECTION_GRANTED -> setupProjection(intent)
        }
        return START_STICKY
    }

    /** 回転時：画面外に出た黒丸/パネルを新しい画面サイズ内へ戻し、移動範囲も更新する。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bubble.post {
            val (w, h) = screenSize()
            if (::bubbleParams.isInitialized) {
                bubbleParams.x = bubbleParams.x.coerceIn(0, (w - bubbleParams.width).coerceAtLeast(0))
                bubbleParams.y = bubbleParams.y.coerceIn(0, (h - bubbleParams.height).coerceAtLeast(0))
                runCatching { wm.updateViewLayout(bubble, bubbleParams) }
            }
            host?.onConfigChanged(w, h)
        }
    }

    // ---------------- バブル ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun screenSize(): Pair<Int, Int> {
        val m = resources.displayMetrics
        return m.widthPixels to m.heightPixels
    }

    private fun addBubble() {
        val size = dp(56)
        bubbleLabel = TextView(this).apply {
            text = "+"
            setTextColor(Color.WHITE)
            textSize = 28f
            gravity = Gravity.CENTER
        }
        bubble = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
            }
            addView(bubbleLabel, FrameLayout.LayoutParams(MATCH, MATCH))
        }

        bubbleParams = WindowManager.LayoutParams(
            size, size,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val (sw, sh) = screenSize()
            x = dp(16).coerceIn(0, (sw - size).coerceAtLeast(0))
            y = dp(120).coerceIn(0, (sh - size).coerceAtLeast(0))
        }
        bubble.setOnTouchListener(DragHandler())
        wm.addView(bubble, bubbleParams)
    }

    /** ドラッグ / タップ判定 + ゴミ箱投棄。 */
    private inner class DragHandler : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var dragging = false
        private val slop = ViewConfiguration.get(this@OverlayService).scaledTouchSlop

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x; startY = bubbleParams.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        showTrash()
                    }
                    if (dragging) {
                        val (sw, sh) = screenSize()
                        bubbleParams.x = (startX + dx.toInt()).coerceIn(0, (sw - bubbleParams.width).coerceAtLeast(0))
                        bubbleParams.y = (startY + dy.toInt()).coerceIn(0, (sh - bubbleParams.height).coerceAtLeast(0))
                        wm.updateViewLayout(bubble, bubbleParams)
                        highlightTrash(overTrash())
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (overTrash()) { stopSelf(); return true }
                        hideTrash()
                    } else {
                        togglePanel()
                    }
                }
            }
            return true
        }
    }

    // ---------------- ゴミ箱ターゲット ----------------

    private fun showTrash() {
        if (trash != null) return
        val win = dp(96)        // 窓は大きめ（拡大しても角で切れないように）
        val circleSize = dp(64) // 実際に見える赤丸
        val label = TextView(this).apply {
            text = "\uD83D\uDDD1" // 🗑
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E53935"))
            }
            addView(label, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        val outer = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
            addView(circle, FrameLayout.LayoutParams(circleSize, circleSize, Gravity.CENTER))
        }
        val p = WindowManager.LayoutParams(
            win, win, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
        }
        trash = outer; trashCircle = circle; trashParams = p
        wm.addView(outer, p)
    }

    private fun hideTrash() {
        trash?.let { runCatching { wm.removeView(it) } }
        trash = null; trashCircle = null; trashParams = null
    }

    private fun highlightTrash(over: Boolean) {
        val bg = trashCircle?.background as? GradientDrawable ?: return
        bg.setColor(Color.parseColor(if (over) "#B71C1C" else "#E53935"))
        trashCircle?.scaleX = if (over) 1.25f else 1f
        trashCircle?.scaleY = if (over) 1.25f else 1f
    }

    private fun overTrash(): Boolean {
        val c = trashCircle ?: return false
        val tl = IntArray(2); c.getLocationOnScreen(tl)
        val rect = Rect(tl[0], tl[1], tl[0] + c.width, tl[1] + c.height)
        val bl = IntArray(2); bubble.getLocationOnScreen(bl)
        val cx = bl[0] + bubble.width / 2
        val cy = bl[1] + bubble.height / 2
        return rect.contains(cx, cy)
    }

    // ---------------- パネル開閉 ----------------

    private fun togglePanel() {
        if (panelShown) collapse() else expand()
    }

    private fun expand() {
        if (panelShown) return
        try {
            if (host == null) {
                val (sw, sh) = screenSize()
                val pw = minOf(dp(300), sw - dp(8))
                val ph = minOf(dp(460), sh - dp(8))
                val px = bubbleParams.x.coerceIn(0, (sw - pw).coerceAtLeast(0))
                val py = bubbleParams.y.coerceIn(0, (sh - ph).coerceAtLeast(0))
                val p = WindowManager.LayoutParams(
                    pw, ph, overlayType(),
                    // NO_LIMITS は付けない（付けるとIMEリサイズが効かず入力欄が隠れる）
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = px; y = py
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                }
                host = OverlayWebHost(this, this).also { it.attach(wm, p) }
            } else {
                host!!.show(wm) // 最小化していたパネルを再表示（チャット/WS内容は保持）
            }
            bubble.visibility = View.GONE
            panelShown = true
        } catch (e: Exception) {
            Toast.makeText(this, "\u30d1\u30cd\u30eb\u8868\u793a\u5931\u6557: ${e.message}", Toast.LENGTH_LONG).show()
            runCatching { host?.detach(wm) }; host = null
            bubble.visibility = View.VISIBLE
            panelShown = false
        }
    }

    /** ヘッダの − ：最小化（黒丸に戻す）。チャット/ワークスペースは保持。 */
    override fun collapse() {
        host?.currentPos()?.let { (x, y) ->
            val (sw, sh) = screenSize()
            bubbleParams.x = x.coerceIn(0, (sw - bubbleParams.width).coerceAtLeast(0))
            bubbleParams.y = y.coerceIn(0, (sh - bubbleParams.height).coerceAtLeast(0))
            runCatching { wm.updateViewLayout(bubble, bubbleParams) }
        }
        host?.hide(wm) // インスタンス保持（破棄しない）→ 再表示で状態が残る
        panelShown = false
        bubble.visibility = View.VISIBLE
    }

    /** ヘッダの × ：オーバーレイ自体を終了（チャットも破棄）。 */
    override fun closeOverlay() { stopSelf() }

    // ---------------- 画面キャプチャ ----------------

    private fun setupProjection(intent: Intent) {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return
        try {
            // Android 14+: getMediaProjection の前に mediaProjection 型FGSへ昇格が必要
            if (Build.VERSION.SDK_INT >= 34) {
                startForegroundCompat(
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            }
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mpm.getMediaProjection(code, data).also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() { projection = null; capture = null }
                }, null)
                capture = ScreenCapture(this, mp)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "\u753b\u9762\u30ad\u30e3\u30d7\u30c1\u30e3\u8a31\u53ef\u5931\u6557: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 「画面送信」ON時に先に許可を取得しておく。 */
    override fun requestProjection() {
        if (capture != null) return
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    /**
     * 「いまの画面」を取得。オーバーレイを一瞬隠して背後アプリだけ写す。
     * projection 未許可なら ProjectionRequestActivity を起動して null を返す（次回から有効）。
     */
    override suspend fun captureScreen(): ByteArray? {
        val c = capture ?: run {
            startActivity(Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            return null
        }
        // 撮影のため一瞬隠す。元の表示状態を覚えておき、後で必ずそこへ戻す。
        // （パネル表示中は bubble が GONE のため、無条件 VISIBLE だと＋が復活してしまう）
        val prevBubble = bubble.visibility
        bubble.visibility = View.INVISIBLE
        host?.rootVisible(false)
        delay(80) // 1フレーム描画を待つ
        return try {
            withContext(Dispatchers.IO) { c.captureOnce() }
        } finally {
            bubble.visibility = prevBubble
            host?.rootVisible(true)
        }
    }

    // ---------------- 後始末 ----------------

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        host?.detach(wm)
        hideTrash()
        runCatching { wm.removeView(bubble) }
        projection?.stop()
        super.onDestroy()
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Overlay AI", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Overlay AI")
            .setContentText("オーバーレイ稼働中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }
}

private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT

/** Compose パネルがサービス機能を呼ぶための境界。 */
interface OverlayBridge {
    val ai: AiClient
    suspend fun captureScreen(): ByteArray?
    fun collapse()
    fun closeOverlay()
    fun requestProjection()
}
