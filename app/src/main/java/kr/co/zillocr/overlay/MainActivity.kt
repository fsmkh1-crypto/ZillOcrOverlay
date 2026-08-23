package kr.co.zillocr.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kr.co.zillocr.overlay.capture.ScreenOcrService

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var waitingForOverlayPermission = false

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            launchProjectionConsent()
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenOcrService::class.java).apply {
                action = ScreenOcrService.ACTION_START
                putExtra(ScreenOcrService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenOcrService.EXTRA_PROJECTION_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        setContentView(buildContentView())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForOverlayPermission && Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = false
            launchProjectionConsent()
        }
    }

    private fun buildContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(48), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "질올 OCR 오버레이 · 1단계"
            textSize = 24f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "1) 시작을 누르고 화면 캡처를 허용합니다.\n" +
                "2) PPSSPP로 전환합니다.\n" +
                "3) 화면 오른쪽의 ‘영역’ 버튼을 누른 뒤 일본어 대사 영역을 드래그합니다.\n" +
                "4) 인식된 일본어가 화면 위 자막으로 표시됩니다."
            textSize = 16f
            setPadding(0, dp(20), 0, dp(24))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "OCR 캡처 시작"
            setOnClickListener { beginCaptureFlow() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "OCR 캡처 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenOcrService::class.java))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "현재 프로토타입은 번역을 하지 않습니다. 일본어 OCR 결과만 표시합니다."
            textSize = 14f
            setPadding(0, dp(24), 0, 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return root
    }

    private fun beginCaptureFlow() {
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
            return
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }
}
