package dev.mokouliszt.overlayai

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * MediaProjection から「いま」の1フレームだけ取得して PNG にする。
 *
 * 注意点:
 *  - projection は ProjectionRequestActivity が取得し、OverlayService が保持する。
 *  - 取得前にオーバーレイ(バブル/パネル)を一瞬隠すと、背後アプリの内容だけが写る。
 *  - FLAG_SECURE のアプリ(銀行系等)は黒塗りになる（OS仕様、回避不可）。
 *  - Android 14+ では projection セッションが単発化/再同意要求される場合がある。
 */
class ScreenCapture(
    private val context: Context,
    private val projection: MediaProjection,
) {
    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("WrongConstant")
    suspend fun captureOnce(): ByteArray = suspendCancellableCoroutine { cont ->
        val metrics = DisplayMetrics()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        var virtualDisplay: VirtualDisplay? = null

        fun cleanup() {
            runCatching { virtualDisplay?.release() }
            runCatching { reader.close() }
        }

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                val rowPadding = rowStride - pixelStride * width

                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, width, height)

                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                bmp.recycle()
                cropped.recycle()

                if (cont.isActive) cont.resume(out.toByteArray())
            } finally {
                image.close()
                cleanup()
            }
        }, handler)

        virtualDisplay = projection.createVirtualDisplay(
            "overlay-ai-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )

        cont.invokeOnCancellation { cleanup() }
    }
}
