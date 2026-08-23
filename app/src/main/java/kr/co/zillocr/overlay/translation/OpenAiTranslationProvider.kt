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
        val glossary = glossarySnapshot()

        exactGlossaryMatch(japaneseText, glossary)?.let { return it }

        val contextSensitive = isContextSensitive(japaneseText)
        val memoryKey = buildMemoryKey(selectedModel, japaneseText, previousContext, contextSensitive)

        synchronized(memoryCache) { memoryCache[memoryKey]?.let { return it } }

        if (!contextSensitive) {
            val now = System.currentTimeMillis()
            translationDao.find(japaneseText)?.takeIf { it.model == selectedModel }?.let { cached ->
                translationDao.touch(japaneseText, now)
                synchronized(memoryCache) { memoryCache[memoryKey] = cached.translatedText }
                return cached.translatedText
            }
        }

        val relevantGlossary = glossary
            .asSequence()
            .filter { entry ->
                japaneseText.contains(entry.first) || previousContext.any { it.contains(entry.first) }
            }
            .take(MAX_GLOSSARY_TERMS)
            .toList()

        val input = buildInput(japaneseText, previousContext, relevantGlossary, glossary)
        var lastEmptyDetail = ""

        repeat(MAX_ATTEMPTS) { attempt ->
            val response = requestTranslation(input, selectedModel)
            if (response.text.isNotBlank() && !response.text.equals("null", ignoreCase = true)) {
                val translated = response.text.trim()
                val savedAt = System.currentTimeMillis()

                if (!contextSensitive) {
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
                }

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

    private fun exactGlossaryMatch(
        japaneseText: String,
        glossary: List<Pair<String, String>>
    ): String? {
        val normalized = japaneseText.trim()
        if (normalized.isEmpty() || normalized.contains('\n')) return null
        return glossary.firstOrNull { it.first == normalized }?.second
    }

    private data class DialogueParts(
        val speakerSource: String?,
        val speakerTarget: String?,
        val dialogue: String
    )

    private fun splitSpeakerAndDialogue(
        text: String,
        glossary: List<Pair<String, String>>
    ): DialogueParts {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) return DialogueParts(null, null, text.trim())

        val first = lines.first()
        val speakerTarget = glossary.firstOrNull { it.first == first }?.second
        val likelySpeaker = speakerTarget != null &&
            first.length <= MAX_SPEAKER_CHARS &&
            first.none { it in SPEAKER_REJECT_PUNCTUATION }

        return if (likelySpeaker) {
            DialogueParts(first, speakerTarget, lines.drop(1).joinToString("\n"))
        } else {
            DialogueParts(null, null, text.trim())
        }
    }

    private fun buildInput(
        japaneseText: String,
        previousContext: List<String>,
        glossary: List<Pair<String, String>>,
        fullGlossary: List<Pair<String, String>>
    ): String = buildString {
        if (glossary.isNotEmpty()) {
            append("[용어집·표기 고정]\n")
            glossary.forEach { (source, target) -> append(source).append(" → ").append(target).append('\n') }
            append('\n')
        }

        val context = previousContext.takeLast(2)
        if (context.isNotEmpty()) {
            append("[직전 대사·문맥만 참조]\n")
            context.forEach { previous ->
                val parts = splitSpeakerAndDialogue(previous, fullGlossary)
                if (parts.speakerSource != null) {
                    append("화자 ").append(parts.speakerSource)
                    parts.speakerTarget?.let { append("(").append(it).append(")") }
                    append(": ").append(parts.dialogue).append('\n')
                } else {
                    append(previous).append('\n')
                }
            }
            append('\n')
        }

        val current = splitSpeakerAndDialogue(japaneseText, fullGlossary)
        append("[현재 대사·이것만 번역]\n")
        if (current.speakerSource != null) {
            append("화자: ").append(current.speakerSource)
            current.speakerTarget?.let { append("(").append(it).append(")") }
            append('\n')
            append("대사: ").append(current.dialogue)
        } else {
            append(japaneseText)
        }
    }

    private fun isContextSensitive(japaneseText: String): Boolean {
        val compact = japaneseText
            .lineSequence()
            .drop(1)
            .joinToString("")
            .ifBlank { japaneseText }
            .filterNot { it.isWhitespace() || it in CACHE_IGNORED_PUNCTUATION }
        return compact.length <= CONTEXT_SENSITIVE_MAX_CHARS
    }

    private fun buildMemoryKey(
        selectedModel: String,
        japaneseText: String,
        previousContext: List<String>,
        contextSensitive: Boolean
    ): String {
        if (!contextSensitive) return "$selectedModel\u0000$japaneseText"
        val contextKey = previousContext.takeLast(2).joinToString("\u0001")
        return "$selectedModel\u0000$japaneseText\u0000$contextKey"
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
        private const val CONTEXT_SENSITIVE_MAX_CHARS = 12
        private const val MAX_SPEAKER_CHARS = 24
        private const val SPEAKER_REJECT_PUNCTUATION = "。！？!?、,:：;；「」『』()（）[]【】"
        private const val CACHE_IGNORED_PUNCTUATION = "、。,.!！?？:：;；'\"「」『』()（）[]【】<>＜＞・…―ー-~～"

        private const val SYSTEM_INSTRUCTIONS =
            "일본 판타지 RPG 대사를 한국 상업 RPG처럼 자연스러운 한국어 대사체로 번역한다. 일본어 어순과 표현을 기계적으로 직역하지 말고 한국어에 맞게 문장을 재구성하되, 원문의 의미·정보·강도는 보존한다. 용어집 표기는 반드시 따른다. 존댓말/반말, 위계, 거친 말투, 고풍체, 캐릭터 말버릇은 직전 문맥과 현재 화자 정보에서 확인되는 범위에서 일관되게 유지한다. 원문에 없는 성별·신분·관계·감정·설정은 만들지 않는다. 직전 대사와 화자명은 문맥에만 사용한다. 현재 대사의 번역문만 출력하고 화자명, 설명, 주석, 따옴표, 원문은 출력하지 않는다."

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
