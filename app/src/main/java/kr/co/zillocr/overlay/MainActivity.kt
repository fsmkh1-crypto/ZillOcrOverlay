package kr.co.zillocr.overlay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kr.co.zillocr.overlay.capture.ScreenOcrService
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.GlossaryEntity
import kr.co.zillocr.overlay.translation.OpenAiTranslationProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var waitingForOverlayPermission = false

    private lateinit var translationEnabledCheck: CheckBox
    private lateinit var apiKeyInput: EditText
    private lateinit var modelInput: EditText

    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val database by lazy { AppDatabase.get(this) }

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
        seedDefaultGlossary()

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

    override fun onDestroy() {
        dbExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val saved = TranslationSettingsStore.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(36), dp(24), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "질올 실시간 번역 오버레이 · 0.3.0"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "OCR → 영구 캐시 → 용어집 적용 → OpenRouter 번역 순서로 처리합니다."
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
            text = "용어집 관리"
            setOnClickListener { showGlossaryManager() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 기록 / 캐시 보기"
            setOnClickListener { showTranslationHistory() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 캐시 삭제"
            setOnClickListener { confirmClearCache() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "OpenRouter API 키 발급 페이지"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openrouter.ai/settings/keys")))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "※ 같은 일본어 원문은 앱을 종료해도 Room 캐시에서 재사용합니다. 용어집을 수정하면 해당 용어가 들어간 기존 캐시만 자동 무효화합니다."
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
            text = "사용: 키 입력·저장 → 시작 → 전체 화면 캡처 허용 → PPSSPP → ‘영역’ → 대화창 드래그"
            textSize = 14f
            setPadding(0, dp(18), 0, 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ScrollView(this).apply { addView(root) }
    }

    private fun seedDefaultGlossary() {
        dbExecutor.execute {
            val dao = database.glossaryDao()
            if (dao.count() == 0) {
                val now = System.currentTimeMillis()
                dao.upsert(GlossaryEntity("ロストール", "로스토르", now))
                dao.upsert(GlossaryEntity("ソウル", "소울", now))
                dao.upsert(GlossaryEntity("インフィニティア", "인피니티아", now))
            }
        }
    }

    private fun showGlossaryManager() {
        dbExecutor.execute {
            val entries = database.glossaryDao().all()
            val body = if (entries.isEmpty()) {
                "등록된 용어가 없습니다."
            } else {
                entries.joinToString("\n") { "${it.sourceTerm} → ${it.targetTerm}" }
            }
            runOnUiThread {
                AlertDialog.Builder(this)
                    .setTitle("용어집 · ${entries.size}개")
                    .setMessage(body)
                    .setPositiveButton("추가/수정") { _, _ -> showGlossaryEditDialog() }
                    .setNeutralButton("삭제") { _, _ -> showGlossaryDeleteDialog() }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        }
    }

    private fun showGlossaryEditDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val source = EditText(this).apply { hint = "일본어 원문 (예: ロストール)" }
        val target = EditText(this).apply { hint = "한국어 표기 (예: 로스토르)" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), 0)
            addView(source)
            addView(target)
        }

        AlertDialog.Builder(this)
            .setTitle("용어 추가 / 수정")
            .setView(box)
            .setPositiveButton("저장") { _, _ ->
                val sourceText = source.text.toString().trim()
                val targetText = target.text.toString().trim()
                if (sourceText.isBlank() || targetText.isBlank()) {
                    Toast.makeText(this, "원문과 번역어를 모두 입력하세요", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                dbExecutor.execute {
                    database.glossaryDao().upsert(
                        GlossaryEntity(sourceText, targetText, System.currentTimeMillis())
                    )
                    database.translationDao().invalidateContaining(sourceText)
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread {
                        Toast.makeText(this, "용어를 저장했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showGlossaryDeleteDialog() {
        val source = EditText(this).apply { hint = "삭제할 일본어 원문" }
        AlertDialog.Builder(this)
            .setTitle("용어 삭제")
            .setView(source)
            .setPositiveButton("삭제") { _, _ ->
                val sourceText = source.text.toString().trim()
                if (sourceText.isBlank()) return@setPositiveButton
                dbExecutor.execute {
                    database.glossaryDao().delete(sourceText)
                    database.translationDao().invalidateContaining(sourceText)
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread {
                        Toast.makeText(this, "용어를 삭제했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showTranslationHistory() {
        dbExecutor.execute {
            val dao = database.translationDao()
            val count = dao.count()
            val recent = dao.recent(30)
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
            val text = if (recent.isEmpty()) {
                "저장된 번역이 없습니다."
            } else {
                recent.joinToString("\n\n") { item ->
                    val time = dateFormat.format(Date(item.lastUsedAt))
                    "[$time · ${item.useCount}회]\n${item.sourceText}\n→ ${item.translatedText}"
                }
            }
            runOnUiThread {
                val textView = TextView(this).apply {
                    this.text = text
                    textSize = 14f
                    setPadding(32, 24, 32, 24)
                }
                AlertDialog.Builder(this)
                    .setTitle("번역 캐시 · 총 ${count}개")
                    .setView(ScrollView(this).apply { addView(textView) })
                    .setPositiveButton("닫기", null)
                    .show()
            }
        }
    }

    private fun confirmClearCache() {
        AlertDialog.Builder(this)
            .setTitle("번역 캐시 삭제")
            .setMessage("저장된 번역 기록을 모두 삭제할까요? 용어집은 유지됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                dbExecutor.execute {
                    database.translationDao().clear()
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread {
                        Toast.makeText(this, "번역 캐시를 삭제했습니다", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
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
