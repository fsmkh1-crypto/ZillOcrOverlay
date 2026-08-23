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

    private data class ToneCase(val label: String, val japanese: String)

    private val toneCases = listOf(
        ToneCase("정중한 감사", "ありがとうございます。あなたのおかげで助かりました。"),
        ToneCase("정중한 명령/부탁", "恐れ入りますが、こちらで少々お待ちください。"),
        ToneCase("친한 반말", "お前、こんなところで何してるんだ？"),
        ToneCase("거친 적대어", "貴様……よくも俺の仲間を！ 絶対に許さんぞ！"),
        ToneCase("소년풍", "へへっ、そんなの楽勝だって！ 俺に任せとけよ。"),
        ToneCase("차분한 여성어", "そうね。無理をする必要はないわ。今日は休みましょう。"),
        ToneCase("고풍스러운 말투", "そなたの覚悟、しかと見届けた。ならば我も力を貸そう。"),
        ToneCase("상하관계 존대", "陛下、ご命令とあらば、この命に代えても成し遂げてみせます。")
    )

    private lateinit var testInput: EditText
    private lateinit var resultView: TextView
    private val slotViews = mutableMapOf<ModelSlot, TextView>()
    private var pendingImportSlot: ModelSlot? = null

    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
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
            text = "로컬 번역 모델 · 0.4.0 alpha4.2"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "실사용 후보의 속도뿐 아니라 말투 보존을 확인합니다. 번역 프롬프트는 존댓말/반말, 거친 말투, 캐릭터성, 사회적 위계, 고풍스러운 어조를 원문대로 유지하도록 강화했습니다. Qwen3는 /no_think를 사용하고 <think> 태그는 결과에서 자동 제거합니다."
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
                text = if (slot.disableThinking) "${slot.label} 3회 벤치 (/no_think)" else "${slot.label} 3회 벤치"
                setOnClickListener { runBenchmark(slot) }
            }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            if (slot == ModelSlot.EXAONE_Q4 || slot == ModelSlot.QWEN_Q4) {
                root.addView(Button(this).apply {
                    text = "${slot.label} 말투 8종 테스트"
                    setOnClickListener { runToneBenchmark(slot) }
                }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }

        testInput = EditText(this).apply {
            hint = "비교할 일본어 대사"
            setText("どの敵にも必ず弱点がある。それを見逃さず、的確に見破れれば、戦いをうまく運べるだろう。")
            minLines = 3
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(testInput, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        resultView = TextView(this).apply {
            text = "Qwen과 EXAONE의 ‘말투 8종 테스트’를 각각 실행해 번역체 차이를 비교하세요."
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
            resultView.text = "${slot.label}\n모델 로드 후 3회 벤치 중…"
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
                resultView.text = buildBenchmarkText(slot, modelFile, load, runs)
            } catch (e: OutOfMemoryError) {
                resultView.text = "${slot.label}\n메모리 부족(OOM)."
            } catch (e: Exception) {
                resultView.text = "${slot.label}\n벤치 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun runToneBenchmark(slot: ModelSlot) {
        val modelFile = slotFile(slot)
        if (!modelFile.exists() || modelFile.length() <= 0L) {
            Toast.makeText(this, "${slot.label} 모델 파일을 먼저 선택하세요", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            resultView.text = "${slot.label}\n말투 8종 번역 중…\n정중체·반말·거친 말투·여성어·고어체·상하관계를 확인합니다."
            try {
                val data = withContext(Dispatchers.IO) {
                    LocalModelTester.release()
                    val load = LocalModelTester.load(modelFile.absolutePath, 6)
                    val results = toneCases.map { case ->
                        case to LocalModelTester.translate(
                            modelPath = modelFile.absolutePath,
                            japaneseText = case.japanese,
                            threads = 6,
                            promptMode = LocalModelTester.PromptMode.COMPACT,
                            disableThinking = slot.disableThinking
                        )
                    }
                    Pair(load, results)
                }

                val avgMs = data.second.map { it.second.inferenceMs }.average()
                val avgTps = data.second.map { it.second.tokensPerSecond }.average()
                resultView.text = buildString {
                    append(slot.label)
                    if (slot.disableThinking) append(" · /no_think")
                    append("\n말투 보존 8종 테스트")
                    append("\n모델 로딩: ${String.format("%.2f초", data.first.elapsedMs / 1000.0)}")
                    append("\n평균 번역: ${String.format("%.2f초", avgMs / 1000.0)}")
                    append("\n평균 생성: ${String.format("%.2f tok/s", avgTps)}")
                    data.second.forEachIndexed { index, pair ->
                        append("\n\n[")
                        append(index + 1)
                        append("] ")
                        append(pair.first.label)
                        append(" · ")
                        append(String.format("%.2f초", pair.second.inferenceMs / 1000.0))
                        append("\nJP: ")
                        append(pair.first.japanese)
                        append("\nKO: ")
                        append(pair.second.text)
                    }
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "${slot.label}\n메모리 부족(OOM)."
            } catch (e: Exception) {
                resultView.text = "${slot.label}\n말투 테스트 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun buildBenchmarkText(
        slot: ModelSlot,
        modelFile: File,
        load: LocalModelTester.LoadResult,
        runs: List<LocalModelTester.Result>
    ): String {
        val avgInference = runs.map { it.inferenceMs }.average()
        val avgPrompt = runs.map { it.promptEvalMs }.average()
        val avgGenerate = runs.map { it.generateMs }.average()
        val avgTps = runs.map { it.tokensPerSecond }.average()
        return buildString {
            append(slot.label)
            if (slot.disableThinking) append(" · /no_think")
            append("\n파일 크기: ${formatBytes(modelFile.length())}")
            append("\n모델 로딩: ${String.format("%.2f초", load.elapsedMs / 1000.0)}")
            append("\n\n[3회 평균]")
            append("\n순수 번역: ${String.format("%.2f초", avgInference / 1000.0)}")
            append("\nprompt eval: ${String.format("%.0f ms", avgPrompt)}")
            append("\ngenerate: ${String.format("%.0f ms", avgGenerate)}")
            append("\n생성 속도: ${String.format("%.2f tok/s", avgTps)}")
            runs.forEachIndexed { index, r ->
                append("\n\n[${index + 1}회] ${String.format("%.2f초 / %.2f tok/s", r.inferenceMs / 1000.0, r.tokensPerSecond)}")
                append("\n${r.text}")
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
                "준비됨 · ${file.name} · ${formatBytes(file.length())}" + if (slot.disableThinking) " · /no_think" else ""
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
