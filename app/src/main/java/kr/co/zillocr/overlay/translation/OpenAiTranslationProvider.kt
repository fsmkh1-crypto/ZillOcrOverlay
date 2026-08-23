package kr.co.zillocr.overlay.translation

import android.content.Context
import kr.co.zillocr.overlay.data.AppContextHolder
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.TranslationEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

class OpenAiTranslationProvider(
    context: Context,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : TranslationProvider {

    constructor(apiKey: String, model: String = DEFAULT_MODEL) : this(AppContextHolder.require(), apiKey, model)

    private val database = AppDatabase.get(context)
    private val translationDao = database.translationDao()
    private val glossaryDao = database.glossaryDao()

    override fun translate(japaneseText: String, previousContext: List<String>): String {
        require(apiKey.isNotBlank()) { "OpenAI API 키가 없습니다" }

        synchronized(memoryCache) { memoryCache[japaneseText]?.let { return it } }

        val now = System.currentTimeMillis()
        translationDao.find(japaneseText)?.let { cached ->
            translationDao.touch(japaneseText, now)
            synchronized(memoryCache) { memoryCache[japaneseText] = cached.translatedText }
            return cached.translatedText
        }

        val relevantGlossary = glossaryDao.all()
            .filter { entry -> japaneseText.contains(entry.sourceTerm) || previousContext.any { it.contains(entry.sourceTerm) } }
            .take(MAX_GLOSSARY_TERMS)
            .associate { it.sourceTerm to it.targetTerm }

        val input = buildInput(japaneseText, previousContext, relevantGlossary)
        var lastEmptyDetail = ""

        repeat(MAX_ATTEMPTS) { attempt ->
            val response = requestTranslation(input)
            if (response.text.isNotBlank() && !response.text.equals("null", ignoreCase = true)) {
                val translated = response.text.trim()
                val savedAt = System.currentTimeMillis()
                translationDao.upsert(
                    TranslationEntity(
                        sourceText = japaneseText,
                        translatedText = translated,
                        model = response.model.ifBlank { DEFAULT_MODEL },
                        createdAt = savedAt,
                        lastUsedAt = savedAt,
                        useCount = 1
                    )
                )
                synchronized(memoryCache) { memoryCache[japaneseText] = translated }
                return translated
            }
            lastEmptyDetail = buildString {
                append("빈 응답")
                response.model.takeIf { it.isNotBlank() }?.let { append(" · model=$it") }
                response.status.takeIf { it.isNotBlank() }?.let { append(" · status=$it") }
                if (attempt + 1 < MAX_ATTEMPTS) append(" · 재시도 중")
            }
        }
        throw IllegalStateException(lastEmptyDetail.ifBlank { "OpenAI 번역 결과가 비어 있습니다" })
    }

    private fun buildInput(
        japaneseText: String,
        previousContext: List<String>,
        glossary: Map<String, String>
    ): String {
        val contextBlock = previousContext.takeLast(2).takeIf { it.isNotEmpty() }
            ?.joinToString("\n") ?: "(없음)"
        val glossaryBlock = glossary.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("\n") { "${it.key} → ${it.value}" } ?: "(없음)"

        return """
            [용어집]
            $glossaryBlock

            [직전 일본어 대사]
            $contextBlock

            [현재 일본어 대사]
            $japaneseText
        """.trimIndent()
    }

    private fun requestTranslation(input: String): ApiResponse {
        val selectedModel = model.ifBlank { DEFAULT_MODEL }
        val requestBody = JSONObject().apply {
            put("model", selectedModel)
            put("instructions", SYSTEM_INSTRUCTIONS)
            put("input", input)
            put("store", false)
            put("max_output_tokens", 180)
            put("reasoning", JSONObject().apply { put("effort", "none") })
            put("text", JSONObject().apply {
                put("verbosity", "low")
                put("format", JSONObject().apply { put("type", "text") })
            })
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
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }

            if (statusCode !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.opt("message")
                        ?.takeUnless { it === JSONObject.NULL }
                        ?.toString()
                }.getOrNull().orEmpty()
                throw IllegalStateException(message.ifBlank { "OpenAI API HTTP $statusCode" })
            }
            return extractResponse(responseText)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractResponse(responseText: String): ApiResponse {
        val root = JSONObject(responseText)
        val responseModel = root.optString("model", "")
        val status = root.optString("status", "")

        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) return ApiResponse(direct.trim(), responseModel, status)

        val output = root.optJSONArray("output") ?: return ApiResponse("", responseModel, status)
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") != "output_text") continue
                val text = block.optString("text", "")
                if (text.isNotBlank()) parts += text
            }
        }
        return ApiResponse(parts.joinToString("\n").trim(), responseModel, status)
    }

    private data class ApiResponse(val text: String, val model: String, val status: String)

    companion object {
        private const val API_URL = "https://api.openai.com/v1/responses"
        private const val DEFAULT_MODEL = "gpt-5.6-luna"
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_GLOSSARY_TERMS = 80
        private const val MAX_ATTEMPTS = 2

        private val SYSTEM_INSTRUCTIONS = """
            일본 판타지 RPG 대사를 자연스러운 한국어로 번역한다.
            현재 대사의 의미를 정확히 보존하고 원문의 존댓말/반말, 사회적 위계, 거친 말투, 고풍스러운 말투, 캐릭터의 말버릇과 분위기를 가능한 한 그대로 살린다.
            일본어에서 드러나지 않은 성별, 신분, 관계를 임의로 만들어내지 않는다.
            고유명사는 제공된 용어집을 우선한다.
            직전 대사는 문맥 파악에만 사용하고 현재 대사만 번역한다.
            설명, 주석, 원문 반복 없이 한국어 번역문만 출력한다.
        """.trimIndent()

        private val memoryCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }

        fun clearMemoryCache() {
            synchronized(memoryCache) { memoryCache.clear() }
        }
    }
}
