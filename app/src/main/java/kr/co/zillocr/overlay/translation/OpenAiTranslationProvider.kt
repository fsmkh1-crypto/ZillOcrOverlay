package kr.co.zillocr.overlay.translation

import android.content.Context
import kr.co.zillocr.overlay.data.AppContextHolder
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.SpeakerEntity
import kr.co.zillocr.overlay.db.TranslationEntity
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TranslationCancelledException : IOException("translation cancelled")

class OpenAiTranslationProvider(
    context: Context,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : TranslationProvider {

    constructor(apiKey: String, model: String = DEFAULT_MODEL) : this(AppContextHolder.require(), apiKey, model)

    private val translationDao = AppDatabase.get(context).translationDao()
    private val glossaryDao = AppDatabase.get(context).glossaryDao()
    private val speakerDao = AppDatabase.get(context).speakerDao()

    private val connectionLock = Any()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeConnection: HttpURLConnection? = null

    override fun translate(japaneseText: String, previousContext: List<String>): String =
        translateInternal(japaneseText, previousContext, bypassCache = false)

    fun translateForced(japaneseText: String, previousContext: List<String>): String =
        translateInternal(japaneseText, previousContext, bypassCache = true)

    fun cancelInFlight() {
        cancelled.set(true)
        synchronized(connectionLock) {
            activeConnection?.disconnect()
        }
    }

    private fun translateInternal(
        japaneseText: String,
        previousContext: List<String>,
        bypassCache: Boolean
    ): String {
        require(apiKey.isNotBlank()) { "OpenAI API 키가 없습니다" }
        throwIfCancelled()

        val selectedModel = model.ifBlank { DEFAULT_MODEL }
        val glossary = glossarySnapshot()
        val speakers = speakerSnapshot()

        exactGlossaryMatch(japaneseText, glossary)?.let { return it }

        val currentParts = splitSpeakerAndDialogue(
            text = japaneseText,
            speakers = speakers,
            glossary = glossary,
            updateSticky = true,
            allowSticky = true
        )
        val contextSensitive = isContextSensitive(currentParts.dialogue) || currentParts.unknownSpeakerCandidate != null
        val memoryKey = buildMemoryKey(
            selectedModel = selectedModel,
            japaneseText = japaneseText,
            previousContext = previousContext,
            contextSensitive = contextSensitive,
            speakerSource = currentParts.speakerSource ?: currentParts.unknownSpeakerCandidate
        )

        if (!bypassCache) {
            synchronized(memoryCache) {
                memoryCache[memoryKey]?.let { return formatDisplay(currentParts.speakerTarget, it) }
            }

            if (!contextSensitive) {
                val now = System.currentTimeMillis()
                translationDao.find(japaneseText, selectedModel)?.let { cached ->
                    translationDao.touch(japaneseText, selectedModel, now)
                    synchronized(memoryCache) { memoryCache[memoryKey] = cached.translatedText }
                    return formatDisplay(currentParts.speakerTarget, cached.translatedText)
                }
            }
        }

        val relevantGlossary = glossary
            .asSequence()
            .filter { entry ->
                japaneseText.contains(entry.first) || previousContext.any { it.contains(entry.first) }
            }
            .take(MAX_GLOSSARY_TERMS)
            .toList()

        val input = buildInput(
            currentParts = currentParts,
            previousContext = previousContext,
            glossary = relevantGlossary,
            fullGlossary = glossary,
            speakers = speakers
        )

        var lastDetail = ""
        var maxTokens = DEFAULT_MAX_OUTPUT_TOKENS

        repeat(MAX_ATTEMPTS) { attempt ->
            throwIfCancelled()
            val response = requestTranslation(input, selectedModel, maxTokens)

            if (response.status == "incomplete") {
                lastDetail = buildString {
                    append("번역 응답이 잘렸습니다")
                    response.incompleteReason.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                }
                if (attempt + 1 < MAX_ATTEMPTS && response.incompleteReason == "max_output_tokens") {
                    maxTokens = RETRY_MAX_OUTPUT_TOKENS
                    return@repeat
                }
                throw IllegalStateException(lastDetail)
            }

            if (response.text.isNotBlank() && !response.text.equals("null", ignoreCase = true)) {
                val parsed = parseUnknownSpeakerResponse(
                    rawText = response.text.trim(),
                    candidate = currentParts.unknownSpeakerCandidate
                )
                val translated = parsed.dialogue.trim()
                if (translated.isBlank()) {
                    lastDetail = "번역 본문이 비어 있습니다"
                    return@repeat
                }

                val resolvedSpeakerTarget = when {
                    parsed.registeredSpeakerTarget != null -> parsed.registeredSpeakerTarget
                    else -> currentParts.speakerTarget
                }

                val savedAt = System.currentTimeMillis()
                if (!contextSensitive || bypassCache) {
                    translationDao.upsert(
                        TranslationEntity(
                            sourceText = japaneseText,
                            translatedText = translated,
                            model = selectedModel,
                            createdAt = savedAt,
                            lastUsedAt = savedAt,
                            useCount = 1
                        )
                    )
                }

                synchronized(memoryCache) { memoryCache[memoryKey] = translated }
                return formatDisplay(resolvedSpeakerTarget, translated)
            }

            lastDetail = buildString {
                append("빈 응답")
                response.model.takeIf { it.isNotBlank() }?.let { append(" · model=$it") }
                response.status.takeIf { it.isNotBlank() }?.let { append(" · status=$it") }
                if (attempt + 1 < MAX_ATTEMPTS) append(" · 재시도 중")
            }
        }

        throw IllegalStateException(lastDetail.ifBlank { "OpenAI 번역 결과가 비어 있습니다" })
    }

    private fun glossarySnapshot(): List<Pair<String, String>> {
        synchronized(glossaryLock) {
            glossaryCache?.let { return it }
            return glossaryDao.all()
                .map { it.sourceTerm to it.targetTerm }
                .also { glossaryCache = it }
        }
    }

    private fun speakerSnapshot(): List<SpeakerEntity> {
        synchronized(speakerLock) {
            speakerCache?.let { return it }
            return speakerDao.all().also { speakerCache = it }
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
        val dialogue: String,
        val explicitSpeaker: Boolean,
        val unknownSpeakerCandidate: String?
    )

    private data class SpeakerMemory(
        val source: String,
        val target: String,
        val updatedAt: Long,
        var implicitUsesLeft: Int
    )

    private data class ParsedUnknownSpeakerResponse(
        val dialogue: String,
        val registeredSpeakerTarget: String?
    )

    private fun splitSpeakerAndDialogue(
        text: String,
        speakers: List<SpeakerEntity>,
        glossary: List<Pair<String, String>>,
        updateSticky: Boolean,
        allowSticky: Boolean
    ): DialogueParts {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val rawFirst = lines.firstOrNull().orEmpty()
        val normalizedFirst = normalizeSpeakerLabel(rawFirst)

        if (lines.size >= 2 && normalizedFirst.isNotEmpty()) {
            val registered = speakers.firstOrNull {
                normalizeSpeakerLabel(it.sourceName) == normalizedFirst
            }
            if (registered != null) {
                if (updateSticky) rememberSpeaker(registered.sourceName, registered.targetName)
                return DialogueParts(
                    speakerSource = registered.sourceName,
                    speakerTarget = registered.targetName,
                    dialogue = lines.drop(1).joinToString("\n"),
                    explicitSpeaker = true,
                    unknownSpeakerCandidate = null
                )
            }

            val legacyEntry = glossary.firstOrNull {
                normalizeSpeakerLabel(it.first) == normalizedFirst
            }
            if (legacyEntry != null && isStrongSpeakerCandidate(normalizedFirst)) {
                promoteLegacySpeaker(legacyEntry.first, legacyEntry.second)
                if (updateSticky) rememberSpeaker(legacyEntry.first, legacyEntry.second)
                return DialogueParts(
                    speakerSource = legacyEntry.first,
                    speakerTarget = legacyEntry.second,
                    dialogue = lines.drop(1).joinToString("\n"),
                    explicitSpeaker = true,
                    unknownSpeakerCandidate = null
                )
            }

            if (isStrongSpeakerCandidate(normalizedFirst)) {
                return DialogueParts(
                    speakerSource = null,
                    speakerTarget = null,
                    dialogue = lines.drop(1).joinToString("\n"),
                    explicitSpeaker = true,
                    unknownSpeakerCandidate = normalizedFirst
                )
            }
        }

        val sticky = if (allowSticky && !looksLikeNarration(text)) currentStickySpeaker(consume = true) else null
        return DialogueParts(
            speakerSource = sticky?.source,
            speakerTarget = sticky?.target,
            dialogue = text.trim(),
            explicitSpeaker = false,
            unknownSpeakerCandidate = null
        )
    }

    private fun isStrongSpeakerCandidate(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_SPEAKER_CHARS) return false
        if (text.any { it in SPEAKER_REJECT_PUNCTUATION }) return false
        val japaneseChars = text.count { isJapaneseWriting(it) }
        if (japaneseChars == 0) return false
        val katakanaChars = text.count { it in KATAKANA_RANGE }
        val kanjiChars = text.count { it in KANJI_RANGE }
        return katakanaChars >= 2 || (kanjiChars in 1..6 && japaneseChars == text.count { !it.isWhitespace() })
    }

    private fun isJapaneseWriting(char: Char): Boolean =
        char in KATAKANA_RANGE || char in HIRAGANA_RANGE || char in KANJI_RANGE

    private fun looksLikeNarration(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return true
        val compact = lines.joinToString("")
        return lines.size == 1 && compact.length >= NARRATION_SINGLE_LINE_CHARS
    }

    private fun normalizeSpeakerLabel(text: String): String = text
        .trim()
        .trim(*SPEAKER_DECORATION_CHARS)
        .trim()

    private fun promoteLegacySpeaker(source: String, target: String) {
        speakerDao.upsert(SpeakerEntity(source, target, System.currentTimeMillis()))
        synchronized(speakerLock) { speakerCache = null }
    }

    private fun rememberSpeaker(source: String, target: String) {
        synchronized(stickyLock) {
            stickySpeaker = SpeakerMemory(
                source = source,
                target = target,
                updatedAt = System.currentTimeMillis(),
                implicitUsesLeft = MAX_STICKY_IMPLICIT_USES
            )
        }
    }

    private fun currentStickySpeaker(consume: Boolean): SpeakerMemory? = synchronized(stickyLock) {
        val current = stickySpeaker ?: return@synchronized null
        val expired = System.currentTimeMillis() - current.updatedAt > STICKY_SPEAKER_TTL_MS
        if (expired || current.implicitUsesLeft <= 0) {
            stickySpeaker = null
            return@synchronized null
        }
        if (consume) current.implicitUsesLeft -= 1
        current
    }

    private fun buildInput(
        currentParts: DialogueParts,
        previousContext: List<String>,
        glossary: List<Pair<String, String>>,
        fullGlossary: List<Pair<String, String>>,
        speakers: List<SpeakerEntity>
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
                val parts = splitSpeakerAndDialogue(
                    text = previous,
                    speakers = speakers,
                    glossary = fullGlossary,
                    updateSticky = false,
                    allowSticky = false
                )
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

        currentParts.unknownSpeakerCandidate?.let { candidate ->
            append("[미등록 화자]\n")
            append("화자 원문: ").append(candidate).append('\n')
            append("이 이름의 자연스러운 한국어 음역을 첫 줄에 [[SPEAKER:한국어 이름]] 형식으로 출력한다. ")
            append("그 다음 줄부터 현재 대사의 한국어 번역만 출력한다.\n\n")
        }

        append("[현재 대사·이것만 번역]\n")
        if (currentParts.speakerSource != null) {
            append("화자: ").append(currentParts.speakerSource)
            currentParts.speakerTarget?.let { append("(").append(it).append(")") }
            if (!currentParts.explicitSpeaker) append(" [직전 화자 유지]")
            append('\n')
            append("대사: ").append(currentParts.dialogue)
        } else {
            append(currentParts.dialogue)
        }
    }

    private fun parseUnknownSpeakerResponse(
        rawText: String,
        candidate: String?
    ): ParsedUnknownSpeakerResponse {
        if (candidate == null) return ParsedUnknownSpeakerResponse(rawText, null)
        val match = SPEAKER_MARKER_REGEX.find(rawText)
            ?: return ParsedUnknownSpeakerResponse(rawText, null)
        val target = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (target.isBlank()) return ParsedUnknownSpeakerResponse(rawText, null)

        val dialogue = rawText.removeRange(match.range).trimStart('\r', '\n', ' ')
        speakerDao.upsert(SpeakerEntity(candidate, target, System.currentTimeMillis()))
        synchronized(speakerLock) { speakerCache = null }
        rememberSpeaker(candidate, target)
        return ParsedUnknownSpeakerResponse(dialogue, target)
    }

    private fun isContextSensitive(dialogue: String): Boolean {
        val compact = dialogue.filterNot { it.isWhitespace() || it in CACHE_IGNORED_PUNCTUATION }
        return compact.length <= CONTEXT_SENSITIVE_MAX_CHARS
    }

    private fun buildMemoryKey(
        selectedModel: String,
        japaneseText: String,
        previousContext: List<String>,
        contextSensitive: Boolean,
        speakerSource: String?
    ): String {
        if (!contextSensitive) return "$selectedModel\u0000$japaneseText"
        val contextKey = previousContext.takeLast(2).joinToString("\u0001")
        return "$selectedModel\u0000${speakerSource.orEmpty()}\u0000$japaneseText\u0000$contextKey"
    }

    private fun formatDisplay(speakerTarget: String?, translated: String): String =
        if (speakerTarget.isNullOrBlank()) translated else "$speakerTarget\n$translated"

    private fun requestTranslation(
        input: String,
        selectedModel: String,
        maxOutputTokens: Int
    ): ApiResponse {
        throwIfCancelled()

        val requestBody = JSONObject().apply {
            put("model", selectedModel)
            put("instructions", SYSTEM_INSTRUCTIONS)
            put("input", input)
            put("store", false)
            put("max_output_tokens", maxOutputTokens)

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

        synchronized(connectionLock) {
            if (cancelled.get()) {
                connection.disconnect()
                throw TranslationCancelledException()
            }
            activeConnection = connection
        }

        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            throwIfCancelled()
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            throwIfCancelled()

            if (statusCode !in 200..299) {
                val message = runCatching {
                    JSONObject(responseText)
                        .optJSONObject("error")
                        ?.opt("message")
                        ?.takeUnless { it === JSONObject.NULL }
                        ?.toString()
                }.getOrNull().orEmpty()
                val friendly = when (statusCode) {
                    401 -> "OpenAI API 키 인증에 실패했습니다"
                    429 -> "OpenAI 요청 한도에 걸렸습니다. 잠시 후 다시 시도하세요"
                    else -> message.ifBlank { "OpenAI API HTTP $statusCode" }
                }
                throw IllegalStateException(friendly)
            }
            return extractResponse(responseText)
        } catch (io: IOException) {
            if (cancelled.get()) throw TranslationCancelledException()
            throw io
        } finally {
            synchronized(connectionLock) {
                if (activeConnection === connection) activeConnection = null
            }
            connection.disconnect()
        }
    }

    private fun throwIfCancelled() {
        if (cancelled.get()) throw TranslationCancelledException()
    }

    private fun extractResponse(responseText: String): ApiResponse {
        val root = JSONObject(responseText)
        val responseModel = root.optString("model", "")
        val status = root.optString("status", "")
        val incompleteReason = root.optJSONObject("incomplete_details")?.optString("reason", "").orEmpty()

        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) {
            return ApiResponse(direct.trim(), responseModel, status, incompleteReason)
        }

        val output = root.optJSONArray("output")
            ?: return ApiResponse("", responseModel, status, incompleteReason)
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
        return ApiResponse(parts.joinToString("\n").trim(), responseModel, status, incompleteReason)
    }

    private data class ApiResponse(
        val text: String,
        val model: String,
        val status: String,
        val incompleteReason: String
    )

    companion object {
        private const val API_URL = "https://api.openai.com/v1/responses"
        private const val DEFAULT_MODEL = TranslationSettingsStore.DEFAULT_MODEL
        private const val MAX_CACHE_ENTRIES = 256
        private const val MAX_GLOSSARY_TERMS = 48
        private const val MAX_ATTEMPTS = 2
        private const val DEFAULT_MAX_OUTPUT_TOKENS = 96
        private const val RETRY_MAX_OUTPUT_TOKENS = 160
        private const val CONTEXT_SENSITIVE_MAX_CHARS = 20
        private const val MAX_SPEAKER_CHARS = 24
        private const val STICKY_SPEAKER_TTL_MS = 90_000L
        private const val MAX_STICKY_IMPLICIT_USES = 3
        private const val NARRATION_SINGLE_LINE_CHARS = 30
        private val KATAKANA_RANGE = '\u30A0'..'\u30FF'
        private val HIRAGANA_RANGE = '\u3040'..'\u309F'
        private val KANJI_RANGE = '\u4E00'..'\u9FFF'
        private const val SPEAKER_REJECT_PUNCTUATION = "。！？!?、,:：;；「」『』()（）[]【】"
        private val SPEAKER_DECORATION_CHARS = charArrayOf(
            '「', '」', '『', '』', '(', ')', '（', '）', '[', ']', '【', '】',
            ':', '：', '・', ' ', '\t'
        )
        private const val CACHE_IGNORED_PUNCTUATION = "、。,.!！?？:：;；'\"「」『』()（）[]【】<>＜＞・…―ー-~～"
        private val SPEAKER_MARKER_REGEX = Regex("^\\s*\\[\\[SPEAKER:(.+?)]]\\s*", RegexOption.IGNORE_CASE)

        private const val SYSTEM_INSTRUCTIONS =
            "일본 판타지 RPG 대사를 한국 상업 RPG처럼 자연스러운 한국어 대사체로 번역한다. 일본어 어순과 표현을 기계적으로 직역하지 말고 한국어에 맞게 문장을 재구성하되, 원문의 의미·정보·강도는 보존한다. 용어집 표기는 반드시 따른다. 존댓말/반말, 위계, 거친 말투, 고풍체, 캐릭터 말버릇은 직전 문맥과 현재 화자 정보에서 확인되는 범위에서 일관되게 유지한다. 원문에 없는 성별·신분·관계·감정·설정은 만들지 않는다. 직전 대사와 화자명은 문맥에만 사용한다. 미등록 화자 지시가 있는 경우에만 지정된 SPEAKER 마커를 첫 줄에 출력하고, 그 외에는 현재 대사의 한국어 번역문만 출력하며 설명·주석·따옴표·원문은 출력하지 않는다."

        private val memoryCache = object : LinkedHashMap<String, String>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_CACHE_ENTRIES
        }

        private val glossaryLock = Any()
        @Volatile private var glossaryCache: List<Pair<String, String>>? = null

        private val speakerLock = Any()
        @Volatile private var speakerCache: List<SpeakerEntity>? = null

        private val stickyLock = Any()
        @Volatile private var stickySpeaker: SpeakerMemory? = null

        fun clearMemoryCache() {
            synchronized(memoryCache) { memoryCache.clear() }
            synchronized(glossaryLock) { glossaryCache = null }
            synchronized(speakerLock) { speakerCache = null }
            synchronized(stickyLock) { stickySpeaker = null }
        }

        fun clearDialogueContext() {
            synchronized(stickyLock) { stickySpeaker = null }
        }
    }
}
