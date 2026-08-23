package kr.co.zillocr.overlay

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kr.co.zillocr.overlay.capture.ScreenOcrService
import kr.co.zillocr.overlay.data.TranslationSettingsStore

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var waitingForOverlayPermission = false

    private lateinit var translationEnabledCheck: CheckBox
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText

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
            saveTranslationSettings(showToast = false)
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

        val saved = TranslationSettingsStore.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "질올 실시간 번역 오버레이 · 0.2.3"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "PPSSPP의 일본어를 OCR한 뒤 OpenRouter 무료 모델로 한국어 번역합니다."
            textSize = 15f
            setPadding(0, dp(12), 0, dp(12))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        translationEnabledCheck = CheckBox(this).apply {
            text = "API 번역 사용"
            isChecked = saved.enabled
        }
        root.addView(translationEnabledCheck, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        apiKeyInput = EditText(this).apply {
            hint = "OpenRouter API 키 (sk-or-v1-...)"
            setText(saved.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        root.addView(apiKeyInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        modelInput = EditText(this).apply {
            hint = "모델 (기본: openrouter/free)"
            setText(saved.model)
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 1
        }
        root.addView(modelInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 설정 저장"
            setOnClickListener { saveTranslationSettings(showToast = true) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "OpenRouter API 키 발급 페이지"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys")))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "※ Android 14 이상에서는 앱 선택 대신 전체 디스플레이 캡처만 요청합니다. PPSSPP를 별도로 목록에서 고를 필요가 없습니다."
            textSize = 12f
            setPadding(0, dp(6), 0, dp(16))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 캡처 시작"
            setOnClickListener { beginCaptureFlow() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "캡처 중지"
            setOnClickListener {
                stopService(Intent(this@MainActivity, ScreenOcrService::class.java))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "사용: OpenRouter 키 입력·저장 → 시작 → 전체 화면 캡처 허용 → PPSSPP → ‘영역’ → 대화창 드래그"
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return root
    }

    private fun saveTranslationSettings(showToast: Boolean) {
        TranslationSettingsStore.save(
            context = this,
            enabled = translationEnabledCheck.isChecked,
            apiKey = apiKeyInput.text?.toString().orEmpty(),
            model = modelInput.text?.toString().orEmpty()
        )
        if (showToast) {
            Toast.makeText(this, "번역 설정을 저장했습니다", Toast.LENGTH_SHORT).show()
        }
    }

    private fun beginCaptureFlow() {
        saveTranslationSettings(showToast = false)
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
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            mediaProjectionManager.createScreenCaptureIntent(config)
        } else {
            mediaProjectionManager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }
}
