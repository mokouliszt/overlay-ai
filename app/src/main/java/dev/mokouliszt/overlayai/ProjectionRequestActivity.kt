package dev.mokouliszt.overlayai

import androidx.activity.ComponentActivity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * 画面キャプチャ同意を取る透明Activity。
 * 同意結果(resultCode + data Intent)を OverlayService に渡し、
 * サービス側が MediaProjection を生成・保持する。
 *
 * MediaProjection の同意は Activity からしか出せないため、
 * サービス常駐型アプリではこの「使い捨てActivity」パターンが定石。
 */
class ProjectionRequestActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val svc = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_PROJECTION_GRANTED
                putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(OverlayService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(svc)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}
