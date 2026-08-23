package kr.co.zillocr.overlay

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.translation.LocalModelTester
import java.io.File
import java.io.FileOutputStream

class LocalModelActivity : ComponentActivity() {

    private lateinit var modelPathView: TextView
    private lateinit var testInput: EditText
    private lateinit var resultView: TextView

    private var localModelPath: String = ""

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch {
            resultView.text = "모델을 앱 저장공간으로 복사 중입니다…\n(2~3GB 모델은 몇 분 걸릴 수 있습니다)"
            try {
                val copied = withContext(Dispatchers.IO) {
                    val modelDir = File(getExternalFilesDir(null), "models").apply { mkdirs() }
                    val target = File(modelDir, "local-model.gguf")
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "선택한 파일을 열 수 없습니다" }
                        FileOutputStream(target, false).use { output ->
                            input.copyTo(output, bufferSize = 1024 * 1024)
                        }
                    }
                    target
                }
                localModelPath = copied.absolutePath
                persistModelPath()
                modelPathView.text = "선택됨: ${copied.name}\n${formatBytes(copied.length())}\n${copied.absolutePath}"
                resultView.text = "모델 준비 완료. 아래 테스트 번역을 눌러 실제 구동을 확인하세요."
            } catch (e: Exception) {
                resultView.text = "모델 복사 실패: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        localModelPath = TranslationSettingsStore.load(this).localModelPath
        setContentView(buildContent())
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(28), dp(22), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "로컬 번역 모델 · 0.4.0 alpha"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "TranslateGemma 4B Q4_K_M 같은 GGUF 파일을 선택한 뒤, 실제 기기에서 로딩·번역 속도를 먼저 측정합니다. 이 알파에서는 게임 실시간 번역 전환 전 단계인 ‘로컬 모델 구동 검증’에 집중합니다."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        modelPathView = TextView(this).apply {
            text = if (localModelPath.isBlank()) {
                "선택된 GGUF 모델 없음"
            } else {
                val file = File(localModelPath)
                "선택됨: ${file.name}\n${formatBytes(file.length())}\n$localModelPath"
            }
            textSize = 13f
            setPadding(0, dp(8), 0, dp(12))
        }
        root.addView(modelPathView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "GGUF 모델 파일 선택"
            setOnClickListener {
                modelPicker.launch(arrayOf("application/octet-stream", "application/*"))
            }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        testInput = EditText(this).apply {
            hint = "테스트할 일본어 대사"
            setText("どの敵にも必ず弱点がある。それを見逃さず、的確に見破れれば、戦いをうまく運べるだろう。")
            minLines = 3
        }
        root.addView(testInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "로컬 모델 테스트 번역"
            setOnClickListener { runLocalTest() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "선택 모델 삭제"
            setOnClickListener { confirmDeleteModel() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        resultView = TextView(this).apply {
            text = "아직 테스트하지 않았습니다."
            textSize = 15f
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(resultView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ScrollView(this).apply { addView(root) }
    }

    private fun runLocalTest() {
        if (localModelPath.isBlank() || !File(localModelPath).exists()) {
            Toast.makeText(this, "먼저 GGUF 모델을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val source = testInput.text.toString().trim()
        if (source.isBlank()) return

        lifecycleScope.launch {
            resultView.text = "모델 로딩 + 번역 중…\n첫 실행은 오래 걸릴 수 있습니다."
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalModelTester.translate(localModelPath, source)
                }
                resultView.text = buildString {
                    append("번역 결과\n")
                    append(result.text)
                    append("\n\n총 시간: ")
                    append(String.format("%.2f초", result.elapsedMs / 1000.0))
                    append("\n생성 속도: ")
                    append(String.format("%.2f tok/s", result.tokensPerSecond))
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "메모리 부족(OOM). 더 작은 양자화 모델(Q3/Q2)이나 더 작은 모델이 필요합니다."
            } catch (e: Exception) {
                resultView.text = "로컬 모델 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun persistModelPath() {
        val current = TranslationSettingsStore.load(this)
        TranslationSettingsStore.save(
            context = this,
            enabled = current.enabled,
            apiKey = current.apiKey,
            model = current.model,
            engine = current.engine,
            localModelPath = localModelPath
        )
    }

    private fun confirmDeleteModel() {
        if (localModelPath.isBlank()) return
        AlertDialog.Builder(this)
            .setTitle("로컬 모델 삭제")
            .setMessage("앱 저장공간에 복사한 GGUF 모델을 삭제할까요?")
            .setPositiveButton("삭제") { _, _ ->
                runCatching { File(localModelPath).delete() }
                localModelPath = ""
                persistModelPath()
                modelPathView.text = "선택된 GGUF 모델 없음"
                resultView.text = "모델을 삭제했습니다."
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "크기 확인 불가"
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
