package kr.co.zillocr.overlay.translation

import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig

object LocalModelTester {
    data class Result(
        val text: String,
        val tokensPerSecond: Double,
        val elapsedMs: Long
    )

    suspend fun translate(modelPath: String, japaneseText: String): Result {
        require(modelPath.isNotBlank()) { "로컬 GGUF 모델을 먼저 선택하세요" }
        require(japaneseText.isNotBlank()) { "테스트할 일본어 문장을 입력하세요" }

        val started = System.currentTimeMillis()
        val model = Llama.loadModel(
            modelPath = modelPath,
            config = LlamaConfig(
                contextSize = 1024,
                threads = 4
            )
        )

        return try {
            val completion = Llama.complete(
                model = model,
                prompt = japaneseText,
                systemPrompt = "Translate Japanese fantasy RPG dialogue into natural Korean. Output only the Korean translation. Do not explain.",
                maxTokens = 128
            )
            Result(
                text = completion.text.trim(),
                tokensPerSecond = completion.tokensPerSecond,
                elapsedMs = System.currentTimeMillis() - started
            )
        } finally {
            Llama.releaseModel(model)
        }
    }
}
