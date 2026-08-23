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
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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
    private lateinit var modelSpinner: Spinner
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
            text = "질올 실시간 번역 오버레이 · 0.5.0 alpha8"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "OCR → 승인 학습/화자/문맥 → 사용자 수정 → 캐시 → OpenAI → 오버레이"
            textSize = 14f
            setPadding(0, dp(10), 0, dp(10))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        translationEnabledCheck = CheckBox(this).apply {
            text = "OpenAI 번역 사용"
            isChecked = saved.enabled
        }
        root.addView(translationEnabledCheck, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        apiKeyInput = EditText(this).apply {
            hint = "OpenAI API 키"
            setText(saved.apiKey)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            maxLines = 1
        }
        root.addView(apiKeyInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "번역 모델"
            textSize = 13f
            setPadding(0, dp(8), 0, dp(3))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val options = TranslationSettingsStore.MODEL_OPTIONS
        modelSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options.map { it.label }
            )
            setSelection(options.indexOfFirst { it.id == saved.model }.coerceAtLeast(0))
        }
        root.addView(modelSpinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 설정 저장"
            setOnClickListener { saveTranslationSettings(true) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "용어집 관리 / 일괄 가져오기"
            setOnClickListener { startActivity(Intent(this@MainActivity, GlossaryManagerActivity::class.java)) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "화자 · 학습 관리"
            setOnClickListener { startActivity(Intent(this@MainActivity, LearningManagerActivity::class.java)) }
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
            text = "OpenAI API 키 페이지"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://platform.openai.com/api-keys"))) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "※ alpha8은 미등록 화자를 자동 저장하지 않습니다. AI는 한국어 이름 후보만 제안하고, 게임 오버레이의 ‘화+’를 눌러야 화자 사전에 영구 등록됩니다. OCR 유사 용어도 ‘용+’ 승인 후에만 alias로 저장됩니다."
            textSize = 12f
            setPadding(0, dp(8), 0, dp(8))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "※ ‘좋’은 현재 번역을 좋은 예로 기록하고, ‘수정’은 직접 고친 번역을 저장합니다. 직접 수정한 동일 대사는 AI/캐시보다 먼저 재사용하며, 같은 화자의 최근 수정 예시는 다음 번역의 말투 참고 자료로 사용합니다."
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "※ 번역창 자동 높이는 기본 ON입니다. 폭은 그대로 두고 번역 길이에 맞춰 아래로만 높이가 변합니다. 직접 ‘↘ 크기’로 조절하면 자동 높이가 OFF되고, ☰ 메뉴의 ‘자’ 버튼으로 다시 켤 수 있습니다."
            textSize = 12f
            setPadding(0, 0, 0, dp(8))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "※ 새 대사가 2회 안정화를 통과하면 이전 네트워크 요청을 취소합니다. ‘재’는 현재 대사를 캐시 무시하고 다시 번역하지만 sticky 화자 사용 횟수는 추가로 소모하지 않습니다."
            textSize = 12f
            setPadding(0, 0, 0, dp(14))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "번역 캡처 시작"
            setOnClickListener { beginCaptureFlow() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(Button(this).apply {
            text = "캡처 중지"
            setOnClickListener { stopService(Intent(this@MainActivity, ScreenOcrService::class.java)) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(TextView(this).apply {
            text = "사용: 키·모델 저장 → 시작 → 화면 캡처 허용 → PPSSPP → ☰ → 영역 → 화자명까지 포함해 대화창 지정"
            textSize = 14f
            setPadding(0, dp(16), 0, 0)
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return ScrollView(this).apply { addView(root) }
    }

    private fun seedDefaultGlossary() {
        dbExecutor.execute {
            val dao = database.glossaryDao()
            if (dao.count() == 0) {
                val now = System.currentTimeMillis()
                dao.upsert(GlossaryEntity("ロストール", "로스톨", now))
                dao.upsert(GlossaryEntity("ソウル", "소울", now))
                dao.upsert(GlossaryEntity("インフィニティア", "인피니티아", now))
            }
        }
    }

    private fun showTranslationHistory() {
        dbExecutor.execute {
            val dao = database.translationDao()
            val count = dao.count()
            val recent = dao.recent(30)
            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA)
            val text = if (recent.isEmpty()) "저장된 번역이 없습니다." else recent.joinToString("\n\n") { item ->
                val time = dateFormat.format(Date(item.lastUsedAt))
                "[$time · ${item.useCount}회 · ${item.model}]\n${item.sourceText}\n→ ${item.translatedText}"
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
            .setMessage("AI 번역 캐시만 삭제합니다. 사용자 수정 번역·용어집·화자·승인 alias는 유지됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                dbExecutor.execute {
                    database.translationDao().clear()
                    OpenAiTranslationProvider.clearMemoryCache()
                    runOnUiThread { Toast.makeText(this, "AI 번역 캐시를 삭제했습니다", Toast.LENGTH_SHORT).show() }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun selectedModel(): String = TranslationSettingsStore.MODEL_OPTIONS
        .getOrNull(modelSpinner.selectedItemPosition)?.id ?: TranslationSettingsStore.DEFAULT_MODEL

    private fun saveTranslationSettings(showToast: Boolean) {
        val model = selectedModel()
        TranslationSettingsStore.save(
            this,
            translationEnabledCheck.isChecked,
            apiKeyInput.text?.toString().orEmpty(),
            model
        )
        if (showToast) Toast.makeText(this, "번역 모델을 $model 로 저장했습니다", Toast.LENGTH_SHORT).show()
    }

    private fun beginCaptureFlow() {
        saveTranslationSettings(false)
        if (!Settings.canDrawOverlays(this)) {
            waitingForOverlayPermission = true
            overlayPermissionLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        launchProjectionConsent()
    }

    private fun launchProjectionConsent() {
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjectionManager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else mediaProjectionManager.createScreenCaptureIntent()
        projectionLauncher.launch(captureIntent)
    }
}
