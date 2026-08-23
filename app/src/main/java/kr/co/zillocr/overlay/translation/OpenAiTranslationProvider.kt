package kr.co.zillocr.overlay.translation

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class OpenAiTranslationProvider(
    private val apiKey: String,
    private val model: String
) : TranslationProvider {

    override fun translate(japaneseText: String, previousContext: List<String>): String {
        require(apiKey.isNotBlank()) { "OpenAI API key is missing" }

        val contextBlock = if (previousContext.isEmpty()) {
            "(없음)"
        } else {
            previousContext.joinToString("\n")
        }

        val prompt = """
            일본 판타지 RPG의 대사입니다. 자연스러운 한국어로 번역하세요.
            설명, 주석, 해설은 쓰지 말고 번역문만 출력하세요.
            이름·지명·스킬명은 가능한 한 일관되게 음역하세요.
            직전 대화 문맥은 의미 파악에만 사용하고, 현재 일본어 문장만 번역하세요.

            [직전 대화]
            $contextBlock

            [현재 일본어]
            $japaneseText
        """.trimIndent()

        val requestBody = JSONObject().apply {
            put("model", model)
            put("input", prompt)
            put("max_output_tokens", 220)
            put("store", false)
        }.toString()

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
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
                        ?.optString("message")
                }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "OpenAI API HTTP $status" })
            }

            return extractOutputText(responseText)
                .trim()
                .ifBlank { throw IllegalStateException("번역 결과가 비어 있습니다") }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractOutputText(responseText: String): String {
        val root = JSONObject(responseText)
        val output = root.optJSONArray("output") ?: return ""
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j) ?: continue
                if (part.optString("type") == "output_text") {
                    val text = part.optString("text")
                    if (text.isNotBlank()) return text
                }
            }
        }
        return ""
    }

    companion object {
        private const val API_URL = "https://api.openai.com/v1/responses"
    }
}
