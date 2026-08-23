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
    private var selectedThreads: Int = 4

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { LocalModelTester.release() }
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
                resultView.text = "모델 준비 완료. 4스레드 또는 6스레드로 메모리 로드 후 비교하세요."
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
            text = "로컬 번역 모델 · 0.4.0 alpha3"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "이번 알파는 같은 TranslateGemma 4B Q4 모델로 ① 기존 프롬프트와 짧은 번역 프롬프트를 비교하고 ② CPU 4스레드/6스레드 속도를 비교합니다. 모델은 선택한 스레드 수로 한 번만 로드해 계속 재사용합니다."
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

        root.addView(Button(this).apply {
            text = "4스레드로 모델 메모리 로드"
            setOnClickListener { loadModelOnly(4) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "6스레드로 모델 메모리 로드"
            setOnClickListener { loadModelOnly(6) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        testInput = EditText(this).apply {
            hint = "테스트할 일본어 대사"
            setText("どの敵にも必ず弱点がある。それを見逃さず、的確に見破れれば、戦いをうまく運べるだろう。")
            minLines = 3
        }
        root.addView(testInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "기존 프롬프트로 번역"
            setOnClickListener { runLocalTest(LocalModelTester.PromptMode.BASELINE) }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "짧은 프롬프트로 번역"
            setOnClickListener { runLocalTest(LocalModelTester.PromptMode.COMPACT) }
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

    private fun loadModelOnly(threads: Int) {
        if (localModelPath.isBlank() || !File(localModelPath).exists()) {
            Toast.makeText(this, "먼저 GGUF 모델을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        selectedThreads = threads
        lifecycleScope.launch {
            resultView.text = "${threads}스레드 설정으로 모델을 메모리에 로드 중…"
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalModelTester.load(localModelPath, threads)
                }
                resultView.text = if (result.reused) {
                    "이미 ${threads}스레드 설정으로 로드되어 있습니다.\n이제 두 프롬프트 버튼을 각각 눌러 비교하세요."
                } else {
                    "모델 메모리 로드 완료\nCPU 스레드: ${threads}\n로딩 시간: ${String.format("%.2f초", result.elapsedMs / 1000.0)}\n이제 번역 버튼은 모델을 다시 읽지 않습니다."
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "모델 로딩 중 메모리 부족(OOM). 더 작은 양자화/모델이 필요합니다."
            } catch (e: Exception) {
                resultView.text = "로컬 모델 로드 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun runLocalTest(promptMode: LocalModelTester.PromptMode) {
        if (localModelPath.isBlank() || !File(localModelPath).exists()) {
            Toast.makeText(this, "먼저 GGUF 모델을 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val source = testInput.text.toString().trim()
        if (source.isBlank()) return

        lifecycleScope.launch {
            resultView.text = "${selectedThreads}스레드 · ${modeLabel(promptMode)} 번역 중…"
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalModelTester.translate(
                        modelPath = localModelPath,
                        japaneseText = source,
                        threads = selectedThreads,
                        promptMode = promptMode
                    )
                }
                resultView.text = buildString {
                    append("번역 결과\n")
                    append(result.text)
                    append("\n\n설정: ")
                    append(result.threads)
                    append("스레드 · ")
                    append(modeLabel(result.promptMode))
                    append("\n순수 번역 시간: ")
                    append(String.format("%.2f초", result.inferenceMs / 1000.0))
                    append("\n생성 속도: ")
                    append(String.format("%.2f tok/s", result.tokensPerSecond))
                    append("\nprompt eval: ")
                    append(result.promptEvalMs)
                    append(" ms")
                    append("\ngenerate: ")
                    append(result.generateMs)
                    append(" ms")
                    if (!result.reusedLoadedModel) {
                        append("\n이번 요청에는 모델 로딩 ")
                        append(String.format("%.2f초", result.loadMs / 1000.0))
                        append("가 포함됐습니다.")
                    } else {
                        append("\n모델 재사용: 예")
                    }
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "메모리 부족(OOM). 더 작은 양자화 모델(Q3/Q2)이나 더 작은 모델이 필요합니다."
            } catch (e: Exception) {
                resultView.text = "로컬 모델 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun modeLabel(mode: LocalModelTester.PromptMode): String = when (mode) {
        LocalModelTester.PromptMode.BASELINE -> "기존 프롬프트"
        LocalModelTester.PromptMode.COMPACT -> "짧은 프롬프트"
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
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { LocalModelTester.release() }
                    runCatching { File(localModelPath).delete() }
                    localModelPath = ""
                    persistModelPath()
                    modelPathView.text = "선택된 GGUF 모델 없음"
                    resultView.text = "모델을 삭제했습니다."
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) {
            LocalModelTester.release()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "크기 확인 불가"
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }
}
