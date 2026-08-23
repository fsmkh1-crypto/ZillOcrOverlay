package kr.co.zillocr.overlay

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
import kr.co.zillocr.overlay.translation.LocalModelTester
import java.io.File
import java.io.FileOutputStream

class LocalModelActivity : ComponentActivity() {

    private enum class ModelSlot(
        val label: String,
        val fileName: String,
        val legacyFallback: Boolean = false,
        val disableThinking: Boolean = false
    ) {
        TRANSLATEGEMMA_Q4("TranslateGemma 4B Q4_K_M", "translategemma-q4.gguf", true),
        TRANSLATEGEMMA_Q3("TranslateGemma 4B Q3_K_M", "translategemma-q3.gguf"),
        EXAONE_Q4("EXAONE 3.5 2.4B Q4_K_M", "exaone-2.4b-q4.gguf"),
        QWEN_Q4("Qwen3 1.7B Q4_K_M", "qwen3-1.7b-q4.gguf", disableThinking = true)
    }

    private lateinit var testInput: EditText
    private lateinit var resultView: TextView
    private val slotViews = mutableMapOf<ModelSlot, TextView>()
    private var pendingImportSlot: ModelSlot? = null

    private val modelPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = pendingImportSlot
        pendingImportSlot = null
        if (uri == null || slot == null) return@registerForActivityResult

        lifecycleScope.launch {
            resultView.text = "${slot.label}\n모델을 앱 저장공간으로 복사 중입니다…"
            try {
                withContext(Dispatchers.IO) {
                    LocalModelTester.release()
                    val target = slotFile(slot, preferLegacy = false)
                    target.parentFile?.mkdirs()
                    contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "선택한 파일을 열 수 없습니다" }
                        FileOutputStream(target, false).use { output ->
                            input.copyTo(output, bufferSize = 1024 * 1024)
                        }
                    }
                }
                refreshSlotViews()
                resultView.text = "${slot.label}\n복사 완료. 이제 이 모델을 벤치마크할 수 있습니다."
            } catch (e: Exception) {
                resultView.text = "모델 복사 실패: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshSlotViews()
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(26), dp(20), dp(30))
        }

        root.addView(TextView(this).apply {
            text = "로컬 번역 모델 · 0.4.0 alpha4.1"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "4개 모델을 같은 조건으로 비교합니다. 6 CPU 스레드 · 짧은 프롬프트 · 최대 64 tokens를 사용하며, Qwen3만 /no_think를 강제로 넣어 생각 모드를 끕니다. 각 벤치는 모델을 한 번 로드한 뒤 같은 문장을 3회 번역해 평균을 냅니다."
            textSize = 14f
            setPadding(0, dp(10), 0, dp(14))
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        ModelSlot.entries.forEach { slot ->
            root.addView(TextView(this).apply {
                text = slot.label
                textSize = 18f
                setPadding(0, dp(12), 0, dp(3))
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            val status = TextView(this).apply { textSize = 13f }
            slotViews[slot] = status
            root.addView(status, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            root.addView(Button(this).apply {
                text = "${slot.label} 파일 선택/교체"
                setOnClickListener {
                    pendingImportSlot = slot
                    modelPicker.launch(arrayOf("application/octet-stream", "application/*"))
                }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            root.addView(Button(this).apply {
                text = if (slot.disableThinking) {
                    "${slot.label} 3회 벤치 (/no_think)"
                } else {
                    "${slot.label} 3회 벤치"
                }
                setOnClickListener { runBenchmark(slot) }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        testInput = EditText(this).apply {
            hint = "비교할 일본어 대사"
            setText("どの敵にも必ず弱点がある。それを見逃さず、的確に見破れれば、戦いをうまく運べるだろう。")
            minLines = 3
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(testInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        resultView = TextView(this).apply {
            text = "Qwen3 수정 확인은 Qwen3 3회 벤치만 다시 실행하면 됩니다."
            textSize = 15f
            setPadding(0, dp(18), 0, 0)
        }
        root.addView(resultView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        return ScrollView(this).apply { addView(root) }
    }

    private fun runBenchmark(slot: ModelSlot) {
        val modelFile = slotFile(slot)
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            Toast.makeText(this, "${slot.label} 모델 파일을 먼저 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }
        val source = testInput.text.toString().trim()
        if (source.isBlank()) return

        lifecycleScope.launch {
            resultView.text = "${slot.label}\n모델 로드 후 3회 벤치 중…" +
                if (slot.disableThinking) "\nQwen3 /no_think 적용 중" else ""
            try {
                val benchmark = withContext(Dispatchers.IO) {
                    LocalModelTester.release()
                    val load = LocalModelTester.load(modelFile.absolutePath, 6)
                    val runs = (1..3).map {
                        LocalModelTester.translate(
                            modelPath = modelFile.absolutePath,
                            japaneseText = source,
                            threads = 6,
                            promptMode = LocalModelTester.PromptMode.COMPACT,
                            disableThinking = slot.disableThinking
                        )
                    }
                    Pair(load, runs)
                }

                val load = benchmark.first
                val runs = benchmark.second
                val avgInference = runs.map { it.inferenceMs }.average()
                val avgPrompt = runs.map { it.promptEvalMs }.average()
                val avgGenerate = runs.map { it.generateMs }.average()
                val avgTps = runs.map { it.tokensPerSecond }.average()

                resultView.text = buildString {
                    append(slot.label)
                    if (slot.disableThinking) append(" · /no_think")
                    append("\n파일 크기: ")
                    append(formatBytes(modelFile.length()))
                    append("\n모델 로딩: ")
                    append(String.format("%.2f초", load.elapsedMs / 1000.0))
                    append("\n\n[3회 평균]")
                    append("\n순수 번역: ")
                    append(String.format("%.2f초", avgInference / 1000.0))
                    append("\nprompt eval: ")
                    append(String.format("%.0f ms", avgPrompt))
                    append("\ngenerate: ")
                    append(String.format("%.0f ms", avgGenerate))
                    append("\n생성 속도: ")
                    append(String.format("%.2f tok/s", avgTps))
                    runs.forEachIndexed { index, r ->
                        append("\n\n[")
                        append(index + 1)
                        append("회] ")
                        append(String.format("%.2f초 / %.2f tok/s", r.inferenceMs / 1000.0, r.tokensPerSecond))
                        append("\n")
                        append(r.text)
                    }
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "${slot.label}\n메모리 부족(OOM). 이 모델은 현재 설정에서 실사용 후보로 부적합할 수 있습니다."
            } catch (e: Exception) {
                resultView.text = "${slot.label}\n벤치 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun slotFile(slot: ModelSlot, preferLegacy: Boolean = true): File {
        val modelDir = File(getExternalFilesDir(null), "models").apply { mkdirs() }
        val dedicated = File(modelDir, slot.fileName)
        if (preferLegacy && slot.legacyFallback && !dedicated.exists()) {
            val legacy = File(modelDir, "local-model.gguf")
            if (legacy.exists() && legacy.length() > 0L) return legacy
        }
        return dedicated
    }

    private fun refreshSlotViews() {
        ModelSlot.entries.forEach { slot ->
            val file = slotFile(slot)
            slotViews[slot]?.text = if (file.exists() && file.length() > 0L) {
                "준비됨 · ${file.name} · ${formatBytes(file.length())}" +
                    if (slot.disableThinking) " · /no_think" else ""
            } else {
                "파일 없음"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(Dispatchers.IO) { LocalModelTester.release() }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "크기 확인 불가"
        return if (bytes >= 1024L * 1024L * 1024L) {
            String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
        } else {
            String.format("%.0f MB", bytes / 1024.0 / 1024.0)
        }
    }
}
