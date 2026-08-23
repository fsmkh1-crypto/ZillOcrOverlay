package kr.co.zillocr.overlay.translation

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LocalModelTester {
    enum class PromptMode {
        BASELINE,
        COMPACT
    }

    data class LoadResult(
        val elapsedMs: Long,
        val reused: Boolean,
        val threads: Int
    )

    data class Result(
        val text: String,
        val tokensPerSecond: Double,
        val inferenceMs: Long,
        val promptEvalMs: Long,
        val generateMs: Long,
        val loadMs: Long,
        val reusedLoadedModel: Boolean,
        val threads: Int,
        val promptMode: PromptMode,
        val thinkingDisabled: Boolean
    )

    private val mutex = Mutex()
    private var loadedModel: LlamaModel? = null
    private var loadedPath: String = ""
    private var loadedThreads: Int = 0

    suspend fun load(modelPath: String, threads: Int): LoadResult = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(threads in 1..8) { "스레드 수가 올바르지 않습니다" }

        if (loadedModel != null && loadedPath == modelPath && loadedThreads == threads) {
            return@withLock LoadResult(elapsedMs = 0L, reused = true, threads = threads)
        }

        releaseLocked()
        val started = System.currentTimeMillis()
        loadedModel = Llama.loadModel(
            modelPath = modelPath,
            config = configFor(threads)
        )
        loadedPath = modelPath
        loadedThreads = threads
        LoadResult(
            elapsedMs = System.currentTimeMillis() - started,
            reused = false,
            threads = threads
        )
    }

    suspend fun translate(
        modelPath: String,
        japaneseText: String,
        threads: Int,
        promptMode: PromptMode,
        disableThinking: Boolean = false
    ): Result = mutex.withLock {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(japaneseText.isNotBlank()) { "테스트할 일본어 문장을 입력하세요" }

        var loadMs = 0L
        var reused = true
        if (loadedModel == null || loadedPath != modelPath || loadedThreads != threads) {
            releaseLocked()
            val loadStarted = System.currentTimeMillis()
            loadedModel = Llama.loadModel(
                modelPath = modelPath,
                config = configFor(threads)
            )
            loadedPath = modelPath
            loadedThreads = threads
            loadMs = System.currentTimeMillis() - loadStarted
            reused = false
        }

        val model = requireNotNull(loadedModel)
        val prompt: String
        val systemPrompt: String
        when (promptMode) {
            PromptMode.BASELINE -> {
                prompt = if (disableThinking) "$japaneseText\n/no_think" else japaneseText
                systemPrompt = "Translate Japanese fantasy RPG dialogue into natural Korean. Preserve the original politeness level, roughness, character voice, social register, and archaic tone. Output only Korean. Do not explain."
            }
            PromptMode.COMPACT -> {
                val core = "Translate Japanese RPG dialogue to Korean. Preserve politeness, casual/rough speech, character voice and archaic tone. Korean only.\n$japaneseText"
                prompt = if (disableThinking) "$core\n/no_think" else core
                systemPrompt = ""
            }
        }

        val inferenceStarted = System.currentTimeMillis()
        val completion = Llama.complete(
            model = model,
            prompt = prompt,
            systemPrompt = systemPrompt,
            maxTokens = 64
        )
        val inferenceMs = System.currentTimeMillis() - inferenceStarted

        Result(
            text = cleanOutput(completion.text),
            tokensPerSecond = completion.tokensPerSecond.toDouble(),
            inferenceMs = inferenceMs,
            promptEvalMs = completion.promptEvalTimeMs,
            generateMs = completion.generateTimeMs,
            loadMs = loadMs,
            reusedLoadedModel = reused,
            threads = threads,
            promptMode = promptMode,
            thinkingDisabled = disableThinking
        )
    }

    suspend fun release() = mutex.withLock { releaseLocked() }

    private fun configFor(threads: Int) = LlamaConfig(
        contextSize = 1024,
        threads = threads,
        gpuLayers = 0,
        temperature = 0.0f,
        topP = 1.0f,
        topK = 1,
        seed = 0
    )

    private fun cleanOutput(raw: String): String {
        return raw
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .replace("<think>", "")
            .replace("</think>", "")
            .trim()
    }

    private fun releaseLocked() {
        loadedModel?.let { Llama.releaseModel(it) }
        loadedModel = null
        loadedPath = ""
        loadedThreads = 0
    }
}
