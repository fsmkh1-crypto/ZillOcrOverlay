package kr.co.zillocr.overlay.translation

import android.content.Context
import kr.co.zillocr.overlay.data.AppContextHolder
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.TranslationEntity
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

    private val translationDao = AppDatabase.get(context).translationDao()
    private val glossaryDao = AppDatabase.get(context).glossaryDao()

    override fun translate(japaneseText: String, previousContext: List<String>): String {
        require(apiKey.isNotBlank()) { "OpenAI API 키가 없습니다" }
        val selectedModel = model.ifBlank { DEFAULT_MODEL }
        val memoryKey = "$selectedModel\u0000$japaneseText"

        synchronized(memoryCache) { memoryCache[memoryKey]?.let { return it } }

        val now = System.currentTimeMillis()
        translationDao.find(japaneseText)?.takeIf { it.model == selectedModel }?.let { cached ->
            translationDao.touch(japaneseText, now)
            synchronized(memoryCache) { memoryCache[memoryKey] = cached.translatedText }
            return cached.translatedText
        }

        val relevantGlossary = glossarySnapshot()
            .asSequence()
            .filter { entry ->
                japaneseText.contains(entry.first) || previousContext.any { it.contains(entry.first) }
            }
            .take(MAX_GLOSSARY_TERMS)
            .toList()

        val input = buildInput(japaneseText, previousContext, relevantGlossary)
        var lastEmptyDetail = ""

        repeat(MAX_ATTEMPTS) { attempt ->
            val response = requestTranslation(input, selectedModel)
            if (response.text.isNotBlank() && !response.text.equals("null", ignoreCase = true)) {
                val translated = response.text.trim()
                val savedAt = System.currentTimeMillis()
                translationDao.upsert(
                    TranslationEntity(
                        sourceText = japaneseText,
                        translatedText = translated,
                        model = response.model.ifBlank { selectedModel },
                        createdAt = savedAt,
                        lastUsedAt = savedAt,
                        useCount = 1
                    )
                )
                synchronized(memoryCache) { memoryCache[memoryKey] = translated }
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

    private fun glossarySnapshot(): List<Pair<String, String>> {
        synchronized(glossaryLock) {
            glossaryCache?.let { return it }
            return glossaryDao.all()
                .map { it.sourceTerm to it.targetTerm }
                .also { glossaryCache = it }
        }
    }

    private fun buildInput(
        japaneseText: String,
        previousContext: List<String>,
        glossary: List<Pair<String, String>>
    ): String = buildString {
        if (glossary.isNotEmpty()) {
            append("[용어집]\n")
            glossary.forEach { (source, target) -> append(source).append(" → ").append(target).append('\n') }
            append('\n')
        }
        val context = previousContext.takeLast(2)
        if (context.isNotEmpty()) {
            append("[직전 대사·문맥만 참조]\n")
            append(context.joinToString("\n"))
            append("\n\n")
        }
        append("[현재 대사·이것만 번역]\n")
        append(japaneseText)
    }

    private fun requestTranslation(input: String, selectedModel: String): ApiResponse {
        val requestBody = JSONObject().apply {
            put("model", selectedModel)
            put("instructions", SYSTEM_INSTRUCTIONS)
            put("input", input)
            put("store", false)
            put("max_output_tokens", 96)

            if (selectedModel.startsWith("gpt-5.6-") && !selectedModel.endsWith("-pro")) {
                put("reasoning", JSONObject().apply { put("effort", "none") })
                put("text", JSONObject().apply {
                    put("verbosity", "low")
                    put("format", JSONObject().apply { put("type", "text") })
                })
            } else if (selectedModel.startsWith("gpt-5")) {
                put("text", JSONObject().apply {
                    put("verbosity", "low")
                    put("format", JSONObject().apply { put("type", "text") })
                })
            }
        }.toString()

        val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = if (selectedModel.contains("pro")) 45_000 else 15_000
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
        private const val DEFAULT_MODEL = TranslationSettingsStore.DEFAULT_MODEL
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_GLOSSARY_TERMS = 48
        private const val MAX_ATTEMPTS = 2

        private const val SYSTEM_INSTRUCTIONS =
            "일본 판타지 RPG 대사를 자연스러운 한국어로 번역한다. 용어집 표기를 최우선으로 따른다. 원문의 의미와 정보량을 보존하고, 존댓말/반말, 사회적 위계, 고풍체, 거친 말투, 캐릭터 말버릇을 문맥상 확인되는 범위에서 유지한다. 원문에 없는 성별·신분·관계·감정·고유명사 정보를 임의로 만들지 않는다. 직전 대사는 현재 대사의 화자 관계와 말투를 판단하는 문맥으로만 사용하고 번역 결과에 포함하지 않는다. 설명, 주석, 따옴표, 원문 재출력 없이 현재 대사의 한국어 번역만 출력한다."

        private val memoryCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }

        private val glossaryLock = Any()
        @Volatile private var glossaryCache: List<Pair<String, String>>? = null

        fun clearMemoryCache() {
            synchronized(memoryCache) { memoryCache.clear() }
            synchronized(glossaryLock) { glossaryCache = null }
        }
    }
}
