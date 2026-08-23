package kr.co.zillocr.overlay.translation

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

/**
 * OpenRouter-backed provider. The historical class name is kept for now to avoid
 * unnecessary Stage 2 refactoring; it can be renamed when providers are split later.
 */
class OpenAiTranslationProvider(
    private val apiKey: String,
    private val model: String
) : TranslationProvider {

    override fun translate(japaneseText: String, previousContext: List<String>): String {
        require(apiKey.isNotBlank()) { "OpenRouter API key is missing" }

        synchronized(memoryCache) {
            memoryCache[japaneseText]?.let { return it }
        }

        val prompt = buildPrompt(japaneseText, previousContext)
        var lastEmptyDetail = ""

        repeat(MAX_ATTEMPTS) { attempt ->
            val response = requestTranslation(prompt)
            if (response.text.isNotBlank() && !response.text.equals("null", ignoreCase = true)) {
                synchronized(memoryCache) {
                    memoryCache[japaneseText] = response.text
                }
                return response.text
            }

            lastEmptyDetail = buildString {
                append("빈 응답")
                response.model.takeIf { it.isNotBlank() }?.let { append(" · model=$it") }
                response.finishReason.takeIf { it.isNotBlank() }?.let { append(" · finish=$it") }
                if (attempt + 1 < MAX_ATTEMPTS) append(" · 재시도 중")
            }
        }

        throw IllegalStateException(lastEmptyDetail.ifBlank { "OpenRouter 번역 결과가 비어 있습니다" })
    }

    private fun buildPrompt(japaneseText: String, previousContext: List<String>): String {
        val contextBlock = if (previousContext.isEmpty()) {
            "(없음)"
        } else {
            previousContext.joinToString("\n")
        }

        return """
            일본 판타지 RPG의 대사입니다. 자연스러운 한국어로 번역하세요.
            설명, 주석, 해설은 쓰지 말고 번역문만 출력하세요.
            이름·지명·스킬명은 가능한 한 일관되게 음역하세요.
            직전 대화 문맥은 의미 파악에만 사용하고, 현재 일본어 문장만 번역하세요.

            [직전 대화]
            $contextBlock

            [현재 일본어]
            $japaneseText
        """.trimIndent()
    }

    private fun requestTranslation(prompt: String): ApiResponse {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val requestBody = JSONObject().apply {
            put("model", model.ifBlank { DEFAULT_MODEL })
            put("messages", messages)
            put("max_tokens", 220)
            put("temperature", 0.2)
            put("reasoning", JSONObject().apply { put("enabled", false) })
        }.toString()

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("HTTP-Referer", "https://github.com/fsmkh1-crypto/ZillOcrOverlay")
            setRequestProperty("X-Title", "Zill OCR Overlay")
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.opt("message")
                        ?.takeUnless { it === JSONObject.NULL }
                        ?.toString()
                }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "OpenRouter API HTTP $status" })
            }

            return extractResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractResponse(responseText: String): ApiResponse {
        val root = JSONObject(responseText)
        val responseModel = root.opt("model")
            ?.takeUnless { it === JSONObject.NULL }
            ?.toString()
            .orEmpty()
        val choices = root.optJSONArray("choices") ?: return ApiResponse("", responseModel, "")
        val first = choices.optJSONObject(0) ?: return ApiResponse("", responseModel, "")
        val finishReason = first.opt("finish_reason")
            ?.takeUnless { it === JSONObject.NULL }
            ?.toString()
            .orEmpty()
        val message = first.optJSONObject("message")
        val content = extractContent(message?.opt("content"))

        return ApiResponse(
            text = content.trim(),
            model = responseModel,
            finishReason = finishReason
        )
    }

    private fun extractContent(value: Any?): String {
        if (value == null || value === JSONObject.NULL) return ""
        if (value is String) return if (value.equals("null", ignoreCase = true)) "" else value
        if (value is JSONArray) {
            val parts = mutableListOf<String>()
            for (i in 0 until value.length()) {
                val item = value.optJSONObject(i) ?: continue
                val textValue = item.opt("text")
                    ?.takeUnless { it === JSONObject.NULL }
                    ?.toString()
                    .orEmpty()
                if (textValue.isNotBlank() && !textValue.equals("null", ignoreCase = true)) {
                    parts += textValue
                }
            }
            return parts.joinToString("\n")
        }
        return ""
    }

    private data class ApiResponse(
        val text: String,
        val model: String,
        val finishReason: String
    )

    companion object {
        private const val API_URL = "https://openrouter.ai/api/v1/chat/completions"
        private const val DEFAULT_MODEL = "openrouter/free"
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_ATTEMPTS = 2

        private val memoryCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_CACHE_ENTRIES
            }
        }
    }
}
