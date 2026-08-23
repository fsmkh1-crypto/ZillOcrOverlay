package kr.co.zillocr.overlay.translation

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocalModelTester {
    private const val SYSTEM_PROMPT =
        "Translate Japanese fantasy RPG dialogue into natural Korean. Output only the Korean translation. Do not explain."

    private val mutex = Mutex()
    private var loadedModel: LlamaModel? = null
    private var loadedModelPath: String? = null

    data class Result(
        val text: String,
        val loadMs: Long,
        val promptEvalMs: Long,
        val generateMs: Long,
        val totalMs: Long,
        val tokensGenerated: Int,
        val tokensPerSecond: Double,
        val reusedLoadedModel: Boolean
    )

    data class BenchmarkResult(
        val loadMs: Long,
        val runs: List<Result>
    ) {
        val averagePromptEvalMs: Double
            get() = runs.map { it.promptEvalMs }.average()
        val averageGenerateMs: Double
            get() = runs.map { it.generateMs }.average()
        val averageTotalMs: Double
            get() = runs.map { it.totalMs }.average()
        val averageTokensPerSecond: Double
            get() = runs.map { it.tokensPerSecond }.average()
    }

    suspend fun translate(modelPath: String, japaneseText: String): Result = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(japaneseText.isNotBlank()) { "테스트할 일본어 문장을 입력하세요" }

        val load = ensureLoadedLocked(modelPath)
        completeLocked(
            model = load.model,
            japaneseText = japaneseText,
            loadMs = load.loadMs,
            reusedLoadedModel = load.reused
        )
    }

    suspend fun benchmark(
        modelPath: String,
        japaneseText: String,
        repeatCount: Int = 10
    ): BenchmarkResult = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(japaneseText.isNotBlank()) { "테스트할 일본어 문장을 입력하세요" }
        require(repeatCount > 0) { "반복 횟수는 1 이상이어야 합니다" }

        // 벤치마크는 항상 cold load부터 시작해 최초 로딩과 상주 후 반복 성능을 분리한다.
        releaseLocked()
        val load = ensureLoadedLocked(modelPath)
        val runs = ArrayList<Result>(repeatCount)

        repeat(repeatCount) { index ->
            runs += completeLocked(
                model = load.model,
                japaneseText = japaneseText,
                loadMs = if (index == 0) load.loadMs else 0L,
                reusedLoadedModel = index > 0
            )
        }

        BenchmarkResult(loadMs = load.loadMs, runs = runs)
    }

    suspend fun release() = mutex.withLock {
        releaseLocked()
    }

    fun isLoaded(modelPath: String? = null): Boolean {
        val model = loadedModel
        if (model == null || !model.isLoaded) return false
        return modelPath == null || modelPath == loadedModelPath
    }

    private data class LoadResult(
        val model: LlamaModel,
        val loadMs: Long,
        val reused: Boolean
    )

    private suspend fun ensureLoadedLocked(modelPath: String): LoadResult {
        val existing = loadedModel
        if (existing != null && existing.isLoaded && loadedModelPath == modelPath) {
            return LoadResult(existing, loadMs = 0L, reused = true)
        }

        releaseLocked()
        val started = System.currentTimeMillis()
        val model = Llama.loadModel(
            modelPath = modelPath,
            config = LlamaConfig(
                contextSize = 1024,
                threads = 4
            )
        )
        val elapsed = System.currentTimeMillis() - started
        loadedModel = model
        loadedModelPath = modelPath
        return LoadResult(model, loadMs = elapsed, reused = false)
    }

    private suspend fun completeLocked(
        model: LlamaModel,
        japaneseText: String,
        loadMs: Long,
        reusedLoadedModel: Boolean
    ): Result {
        val started = System.currentTimeMillis()
        val completion = Llama.complete(
            model = model,
            prompt = japaneseText,
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = 128
        )
        val totalMs = System.currentTimeMillis() - started

        return Result(
            text = completion.text.trim(),
            loadMs = loadMs,
            promptEvalMs = completion.promptEvalTimeMs,
            generateMs = completion.generateTimeMs,
            totalMs = totalMs,
            tokensGenerated = completion.tokensGenerated,
            tokensPerSecond = completion.tokensPerSecond.toDouble(),
            reusedLoadedModel = reusedLoadedModel
        )
    }

    private fun releaseLocked() {
        loadedModel?.let { model ->
            if (model.isLoaded) Llama.releaseModel(model)
        }
        loadedModel = null
        loadedModelPath = null
    }
}
