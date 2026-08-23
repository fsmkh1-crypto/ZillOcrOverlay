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
                    // 같은 파일 경로를 덮어쓰기 전에 기존 mmap/네이티브 모델을 반드시 해제한다.
                    LocalModelTester.release()
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
                resultView.text = "모델 준비 완료. 먼저 단일 번역을 실행하거나, 연속 10회 벤치마크로 최초 로딩과 상주 후 성능을 비교하세요."
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
            text = "로컬 번역 모델 · 0.5.0 alpha"
            textSize = 23f
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "GGUF 모델을 한 번 로딩한 뒤 메모리에 유지하여 반복 번역 성능을 측정합니다. 10회 벤치마크는 cold load부터 시작해 최초 로딩 시간과 상주 후 번역 시간을 분리해 보여줍니다."
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
            text = "연속 10회 벤치마크"
            setOnClickListener { runRepeatedBenchmark() }
        }, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        root.addView(Button(this).apply {
            text = "모델 메모리 해제"
            setOnClickListener { releaseLoadedModel() }
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
        if (!validateModel()) return
        val source = testInput.text.toString().trim()
        if (source.isBlank()) return

        lifecycleScope.launch {
            val wasLoaded = LocalModelTester.isLoaded(localModelPath)
            resultView.text = if (wasLoaded) {
                "상주 모델로 번역 중…"
            } else {
                "모델 최초 로딩 + 번역 중…\n첫 실행은 오래 걸릴 수 있습니다."
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    LocalModelTester.translate(localModelPath, source)
                }
                resultView.text = buildString {
                    append("번역 결과\n")
                    append(result.text)
                    append("\n\n모델 상태: ")
                    append(if (result.reusedLoadedModel) "기존 상주 모델 재사용" else "이번 실행에서 최초 로딩")
                    append("\n모델 로딩: ")
                    append(formatMs(result.loadMs))
                    append("\n프롬프트 처리: ")
                    append(formatMs(result.promptEvalMs))
                    append("\n생성: ")
                    append(formatMs(result.generateMs))
                    append("\n번역 호출 총 시간: ")
                    append(formatMs(result.totalMs))
                    append("\n생성 토큰: ")
                    append(result.tokensGenerated)
                    append("\n생성 속도: ")
                    append(String.format("%.2f tok/s", result.tokensPerSecond))
                    append("\n\n※ 모델은 다음 번역을 위해 메모리에 유지됩니다.")
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "메모리 부족(OOM). 더 작은 양자화 모델(Q3/Q2)이나 더 작은 모델이 필요합니다."
            } catch (e: Exception) {
                resultView.text = "로컬 모델 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun runRepeatedBenchmark() {
        if (!validateModel()) return
        val source = testInput.text.toString().trim()
        if (source.isBlank()) return

        lifecycleScope.launch {
            resultView.text = "10회 벤치마크 진행 중…\n모델을 먼저 해제한 뒤 1회 로딩하고, 같은 모델을 유지한 상태로 10번 번역합니다."
            try {
                val benchmark = withContext(Dispatchers.IO) {
                    LocalModelTester.benchmark(localModelPath, source, repeatCount = 10)
                }
                resultView.text = buildString {
                    append("10회 연속 벤치마크 완료\n")
                    append("최초 모델 로딩: ${formatMs(benchmark.loadMs)}\n\n")

                    benchmark.runs.forEachIndexed { index, run ->
                        append(String.format(
                            "%02d회  총 %s · prompt %s · 생성 %s · %.2f tok/s · %d tok\n",
                            index + 1,
                            formatMs(run.totalMs),
                            formatMs(run.promptEvalMs),
                            formatMs(run.generateMs),
                            run.tokensPerSecond,
                            run.tokensGenerated
                        ))
                    }

                    val warmRuns = benchmark.runs.drop(1)
                    append("\n요약\n")
                    append("전체 10회 평균 호출: ${formatMs(benchmark.averageTotalMs)}\n")
                    append(String.format("전체 평균 생성 속도: %.2f tok/s\n", benchmark.averageTokensPerSecond))
                    if (warmRuns.isNotEmpty()) {
                        append("상주 후 2~10회 평균 호출: ${formatMs(warmRuns.map { it.totalMs }.average())}\n")
                        append("상주 후 평균 프롬프트 처리: ${formatMs(warmRuns.map { it.promptEvalMs }.average())}\n")
                        append("상주 후 평균 생성: ${formatMs(warmRuns.map { it.generateMs }.average())}\n")
                        append(String.format(
                            "상주 후 평균 생성 속도: %.2f tok/s\n",
                            warmRuns.map { it.tokensPerSecond }.average()
                        ))
                    }
                    append("\n※ TTFT(첫 토큰 시간)는 현재 사용 중인 llama-android 0.1.1 무료 API가 스트리밍 타임스탬프를 제공하지 않아 별도 측정하지 않습니다.")
                }
            } catch (e: OutOfMemoryError) {
                resultView.text = "메모리 부족(OOM). 10회 테스트 전 모델 로딩 단계에서 실패했습니다."
            } catch (e: Exception) {
                resultView.text = "벤치마크 오류: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun releaseLoadedModel() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { LocalModelTester.release() }
            resultView.text = "상주 중인 로컬 모델 메모리를 해제했습니다. 다음 번역 때 다시 로딩합니다."
        }
    }

    private fun validateModel(): Boolean {
        if (localModelPath.isBlank() || !File(localModelPath).exists()) {
            Toast.makeText(this, "먼저 GGUF 모델을 선택하세요", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
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
                    val deleted = withContext(Dispatchers.IO) {
                        LocalModelTester.release()
                        File(localModelPath).delete()
                    }
                    localModelPath = ""
                    persistModelPath()
                    modelPathView.text = "선택된 GGUF 모델 없음"
                    resultView.text = if (deleted) "모델을 삭제했습니다." else "모델 메모리는 해제했지만 파일 삭제에 실패했습니다."
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "크기 확인 불가"
        return String.format("%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
    }

    private fun formatMs(ms: Long): String = String.format("%.2f초", ms / 1000.0)

    private fun formatMs(ms: Double): String = String.format("%.2f초", ms / 1000.0)
}
