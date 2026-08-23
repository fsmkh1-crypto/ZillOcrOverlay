package kr.co.zillocr.overlay.translation

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocalModelTester {
    data class LoadResult(
        val elapsedMs: Long,
        val reused: Boolean
    )

    data class Result(
        val text: String,
        val tokensPerSecond: Double,
        val inferenceMs: Long,
        val promptEvalMs: Long,
        val generateMs: Long,
        val loadMs: Long,
        val reusedLoadedModel: Boolean
    )

    private val mutex = Mutex()
    private var loadedModel: LlamaModel? = null
    private var loadedPath: String = ""

    suspend fun load(modelPath: String): LoadResult = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        if (loadedModel != null && loadedPath == modelPath) {
            return@withLock LoadResult(elapsedMs = 0L, reused = true)
        }

        releaseLocked()
        val started = System.currentTimeMillis()
        loadedModel = Llama.loadModel(
            modelPath = modelPath,
            config = LlamaConfig(
                contextSize = 1024,
                threads = 4,
                gpuLayers = 0,
                temperature = 0.0f,
                topP = 1.0f,
                topK = 1,
                seed = 0
            )
        )
        loadedPath = modelPath
        LoadResult(
            elapsedMs = System.currentTimeMillis() - started,
            reused = false
        )
    }

    suspend fun translate(modelPath: String, japaneseText: String): Result = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(japaneseText.isNotBlank()) { "테스트할 일본어 문장을 입력하세요" }

        var loadMs = 0L
        var reused = true
        if (loadedModel == null || loadedPath != modelPath) {
            releaseLocked()
            val loadStarted = System.currentTimeMillis()
            loadedModel = Llama.loadModel(
                modelPath = modelPath,
                config = LlamaConfig(
                    contextSize = 1024,
                    threads = 4,
                    gpuLayers = 0,
                    temperature = 0.0f,
                    topP = 1.0f,
                    topK = 1,
                    seed = 0
                )
            )
            loadedPath = modelPath
            loadMs = System.currentTimeMillis() - loadStarted
            reused = false
        }

        val model = requireNotNull(loadedModel)
        val inferenceStarted = System.currentTimeMillis()
        val completion = Llama.complete(
            model = model,
            prompt = japaneseText,
            systemPrompt = "Translate Japanese fantasy RPG dialogue into natural Korean. Output only the Korean translation. Do not explain.",
            maxTokens = 96
        )
        val inferenceMs = System.currentTimeMillis() - inferenceStarted

        Result(
            text = completion.text.trim(),
            tokensPerSecond = completion.tokensPerSecond.toDouble(),
            inferenceMs = inferenceMs,
            promptEvalMs = completion.promptEvalTimeMs,
            generateMs = completion.generateTimeMs,
            loadMs = loadMs,
            reusedLoadedModel = reused
        )
    }

    suspend fun release() = mutex.withLock {
        releaseLocked()
    }

    private fun releaseLocked() {
        loadedModel?.let { Llama.releaseModel(it) }
        loadedModel = null
        loadedPath = ""
    }
}
