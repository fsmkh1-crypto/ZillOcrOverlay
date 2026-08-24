package kr.co.zillocr.overlay.translation

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kr.co.zillocr.overlay.data.AppContextHolder
import kr.co.zillocr.overlay.data.IgnoreListStore
import kr.co.zillocr.overlay.data.TranslationSettingsStore
import kr.co.zillocr.overlay.db.AppDatabase
import kr.co.zillocr.overlay.db.OcrAliasEntity
import kr.co.zillocr.overlay.db.SpeakerEntity
import kr.co.zillocr.overlay.db.TranslationEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TranslationCancelledException : IOException("translation cancelled")

class OpenAiTranslationProvider(
    context: Context,
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL
) : TranslationProvider {

    constructor(apiKey: String, model: String = DEFAULT_MODEL) :
        this(AppContextHolder.require(), apiKey, model)

    private val database = AppDatabase.get(context)
    private val translationDao = database.translationDao()
    private val glossaryDao = database.glossaryDao()
    private val speakerDao = database.speakerDao()
    private val overrideDao = database.translationOverrideDao()
    private val aliasDao = database.ocrAliasDao()
    private val styleDao = database.speakerStyleDao()

    private val connectionLock = Any()
    private val cancelled = AtomicBoolean(false)
    @Volatile private var activeCall: Call? = null

    @Volatile var lastSpeakerSource: String? = null
        private set
    @Volatile var lastSpeakerTarget: String? = null
        private set
    @Volatile var lastSpeakerExplicit: Boolean = false
        private set
    @Volatile var lastSpeakerWasCandidate: Boolean = false
        private set
    @Volatile var lastAliasedText: String = ""
        private set

    override fun translate(japaneseText: String, previousContext: List<String>): String =
        translateInternal(japaneseText, previousContext, bypassCache = false, consumeSticky = true)

    fun translateForced(japaneseText: String, previousContext: List<String>): String =
        translateInternal(japaneseText, previousContext, bypassCache = true, consumeSticky = false)

    fun cancelInFlight() {
        cancelled.set(true)
        synchronized(connectionLock) { activeCall?.cancel() }
    }

    private fun translateInternal(
        japaneseText: String,
        previousContext: List<String>,
        bypassCache: Boolean,
        consumeSticky: Boolean
    ): String {
        require(apiKey.isNotBlank()) { "OpenAI API 키가 없습니다" }
        throwIfCancelled()

        val selectedModel = model.ifBlank { DEFAULT_MODEL }
        val glossary = glossarySnapshot()
        val speakers = speakerSnapshot()
        val aliasedText = applyApprovedAlias(japaneseText)
        lastAliasedText = aliasedText

        lastSpeakerSource = null
        lastSpeakerTarget = null
        lastSpeakerExplicit = false
        lastSpeakerWasCandidate = false

        exactGlossaryMatch(aliasedText, glossary)?.let { return it }

        val currentParts = splitSpeakerAndDialogue(
            text = aliasedText,
            speakers = speakers,
            glossary = glossary,
            updateSticky = true,
            allowSticky = true,
            consumeSticky = consumeSticky
        )
        lastSpeakerSource = currentParts.speakerSource
        lastSpeakerTarget = currentParts.speakerTarget
        lastSpeakerExplicit = currentParts.explicitSpeaker

        overrideDao.find(aliasedText, selectedModel)?.let { override ->
            lastSpeakerSource = override.speakerSource ?: lastSpeakerSource
            return override.correctedText
        }

        val contextSensitive = isContextSensitive(currentParts.dialogue) ||
            currentParts.unknownSpeakerCandidate != null
        val memoryKey = buildMemoryKey(
            selectedModel,
            aliasedText,
            previousContext,
            contextSensitive,
            currentParts.speakerSource ?: currentParts.unknownSpeakerCandidate
        )

        if (!bypassCache) {
            synchronized(memoryCache) { memoryCache[memoryKey]?.let { return it } }
            if (!contextSensitive) {
                val now = System.currentTimeMillis()
                translationDao.find(aliasedText, selectedModel)?.let { cached ->
                    translationDao.touch(aliasedText, selectedModel, now)
                    synchronized(memoryCache) { memoryCache[memoryKey] = cached.translatedText }
                    return cached.translatedText
                }
            }
        }

        maybePublishAliasSuggestion(aliasedText, glossary)

        val relevantGlossary = glossary.asSequence()
            .filter { entry ->
                aliasedText.contains(entry.first) || previousContext.any { it.contains(entry.first) }
            }
            .take(MAX_GLOSSARY_TERMS)
            .toList()

        val styleNote = currentParts.speakerSource?.let { styleDao.find(it)?.styleNote }
        val correctedExamples = currentParts.speakerSource?.let { source ->
            overrideDao.recentForSpeaker(source, 2)
        }.orEmpty()

        val input = buildInput(
            currentParts = currentParts,
            previousContext = previousContext,
            glossary = relevantGlossary,
            fullGlossary = glossary,
            speakers = speakers,
            styleNote = styleNote,
            correctedExamples = correctedExamples.map { it.correctedText }
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
                val parsed = parseUnknownSpeakerResponse(response.text.trim(), currentParts.unknownSpeakerCandidate)
                val translated = parsed.dialogue.trim()
                if (translated.isBlank()) {
                    lastDetail = "번역 본문이 비어 있습니다"
                    return@repeat
                }

                if (parsed.suggestedSpeakerTarget != null && currentParts.unknownSpeakerCandidate != null) {
                    lastSpeakerSource = currentParts.unknownSpeakerCandidate
                    lastSpeakerTarget = parsed.suggestedSpeakerTarget
                    lastSpeakerExplicit = true
                    lastSpeakerWasCandidate = true
                    publishPendingSpeaker(
                        currentParts.unknownSpeakerCandidate,
                        parsed.suggestedSpeakerTarget
                    )
                    // 승인 전에는 DB에 저장하지 않지만, 현재 플레이 세션의 다음 몇 줄은 이어지도록만 기억한다.
                    rememberSpeaker(currentParts.unknownSpeakerCandidate, parsed.suggestedSpeakerTarget)
                }

                val savedAt = System.currentTimeMillis()
                if ((!contextSensitive || bypassCache) && currentParts.unknownSpeakerCandidate == null) {
                    translationDao.upsert(
                        TranslationEntity(
                            sourceText = aliasedText,
                            translatedText = translated,
                            model = selectedModel,
                            createdAt = savedAt,
                            lastUsedAt = savedAt,
                            useCount = 1
                        )
                    )
                }
                synchronized(memoryCache) { memoryCache[memoryKey] = translated }
                return translated
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

    private fun glossarySnapshot(): List<Pair<String, String>> = synchronized(glossaryLock) {
        glossaryCache ?: glossaryDao.all().map { it.sourceTerm to it.targetTerm }
            .also { glossaryCache = it }
    }

    private fun speakerSnapshot(): List<SpeakerEntity> = synchronized(speakerLock) {
        speakerCache ?: speakerDao.all().also { speakerCache = it }
    }

    private fun applyApprovedAlias(text: String): String {
        val lines = text.lineSequence().toList()
        if (lines.isEmpty()) return text
        var changed = false
        val replaced = lines.map { line ->
            val trimmed = line.trim()
            val alias = aliasDao.find(trimmed)
            if (alias != null) {
                changed = true
                line.replace(trimmed, alias.canonicalText)
            } else line
        }
        return if (changed) replaced.joinToString("\n") else text
    }

    private fun exactGlossaryMatch(text: String, glossary: List<Pair<String, String>>): String? {
        val normalized = text.trim()
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
        val suggestedSpeakerTarget: String?
    )

    private fun splitSpeakerAndDialogue(
        text: String,
        speakers: List<SpeakerEntity>,
        glossary: List<Pair<String, String>>,
        updateSticky: Boolean,
        allowSticky: Boolean,
        consumeSticky: Boolean
    ): DialogueParts {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val normalizedFirst = normalizeSpeakerLabel(lines.firstOrNull().orEmpty())

        if (lines.size >= 2 && normalizedFirst.isNotEmpty()) {
            speakers.firstOrNull { normalizeSpeakerLabel(it.sourceName) == normalizedFirst }?.let { registered ->
                if (updateSticky) rememberSpeaker(registered.sourceName, registered.targetName)
                return DialogueParts(
                    registered.sourceName,
                    registered.targetName,
                    lines.drop(1).joinToString("\n"),
                    true,
                    null
                )
            }

            glossary.firstOrNull { normalizeSpeakerLabel(it.first) == normalizedFirst }
                ?.takeIf { isStrongSpeakerCandidate(normalizedFirst) }
                ?.let { legacy ->
                    // 용어집 일치는 화자명 후보의 근거로만 사용한다.
                    // 사용자 승인 전에는 speaker DB에 영구 저장하지 않는다.
                    publishPendingSpeaker(legacy.first, legacy.second)
                    if (updateSticky) rememberSpeaker(legacy.first, legacy.second)
                    return DialogueParts(
                        legacy.first,
                        legacy.second,
                        lines.drop(1).joinToString("\n"),
                        true,
                        null
                    )
                }

            if (isStrongSpeakerCandidate(normalizedFirst)) {
                return DialogueParts(
                    null,
                    null,
                    lines.drop(1).joinToString("\n"),
                    true,
                    normalizedFirst
                )
            }
        }

        val sticky = if (allowSticky && !looksLikeNarration(text)) {
            currentStickySpeaker(consumeSticky)
        } else null
        return DialogueParts(sticky?.source, sticky?.target, text.trim(), false, null)
    }

    private fun isStrongSpeakerCandidate(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_SPEAKER_CHARS) return false
        if (text.any { it in SPEAKER_REJECT_PUNCTUATION }) return false
        val nonSpace = text.count { !it.isWhitespace() }
        val japaneseChars = text.count { isJapaneseWriting(it) }
        if (japaneseChars == 0) return false
        val katakana = text.count { it in KATAKANA_RANGE }
        val kanji = text.count { it in KANJI_RANGE }
        return katakana >= 2 || (kanji in 1..6 && japaneseChars == nonSpace)
    }

    private fun isJapaneseWriting(char: Char): Boolean =
        char in KATAKANA_RANGE || char in HIRAGANA_RANGE || char in KANJI_RANGE

    private fun looksLikeNarration(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return true
        return lines.size == 1 && lines.first().length >= NARRATION_SINGLE_LINE_CHARS
    }

    private fun normalizeSpeakerLabel(text: String): String = normalizeSpeakerCandidateSource(text)

    private fun rememberSpeaker(source: String, target: String) {
        synchronized(stickyLock) {
            stickySpeaker = SpeakerMemory(
                source,
                target,
                System.currentTimeMillis(),
                MAX_STICKY_IMPLICIT_USES
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
        speakers: List<SpeakerEntity>,
        styleNote: String?,
        correctedExamples: List<String>
    ): String = buildString {
        if (glossary.isNotEmpty()) {
            append("[용어집·표기 고정]\n")
            glossary.forEach { (source, target) -> append(source).append(" → ").append(target).append('\n') }
            append('\n')
        }

        if (!styleNote.isNullOrBlank()) {
            append("[승인된 화자 말투]\n").append(styleNote.trim()).append("\n\n")
        }
        if (correctedExamples.isNotEmpty()) {
            append("[사용자가 직접 수정한 이 화자의 번역 예시]\n")
            correctedExamples.forEach { append("- ").append(it).append('\n') }
            append("이 예시의 말투 경향만 참고하고 현재 원문의 의미는 바꾸지 않는다.\n\n")
        }

        val context = previousContext.takeLast(2)
        if (context.isNotEmpty()) {
            append("[직전 대사·문맥만 참조]\n")
            context.forEach { previous ->
                val parts = splitSpeakerAndDialogue(
                    previous,
                    speakers,
                    fullGlossary,
                    updateSticky = false,
                    allowSticky = false,
                    consumeSticky = false
                )
                if (parts.speakerSource != null) {
                    append("화자 ").append(parts.speakerSource)
                    parts.speakerTarget?.let { append("(").append(it).append(")") }
                    append(": ").append(parts.dialogue).append('\n')
                } else append(previous).append('\n')
            }
            append('\n')
        }

        currentParts.unknownSpeakerCandidate?.let { candidate ->
            append("[미등록 화자 후보]\n")
            append("화자 원문: ").append(candidate).append('\n')
            append("한국어 음역을 제안만 한다. 첫 줄에 [[SPEAKER:한국어 이름]] 형식으로 출력하고, 앱이 사용자 승인 전에는 저장하지 않는다.\n\n")
        }

        append("[현재 대사·이것만 번역]\n")
        if (currentParts.speakerSource != null) {
            append("화자: ").append(currentParts.speakerSource)
            currentParts.speakerTarget?.let { append("(").append(it).append(")") }
            if (!currentParts.explicitSpeaker) append(" [직전 화자 유지]")
            append('\n')
        }
        append("대사: ").append(currentParts.dialogue)
    }

    private fun parseUnknownSpeakerResponse(rawText: String, candidate: String?): ParsedUnknownSpeakerResponse {
        if (candidate == null) return ParsedUnknownSpeakerResponse(rawText, null)
        val match = SPEAKER_MARKER_REGEX.find(rawText)
            ?: return ParsedUnknownSpeakerResponse(rawText, null)
        val target = match.groupValues.getOrNull(1)?.trim().orEmpty()
        if (target.isBlank()) return ParsedUnknownSpeakerResponse(rawText, null)
        val dialogue = rawText.removeRange(match.range).trimStart('\r', '\n', ' ')
        return ParsedUnknownSpeakerResponse(dialogue, target)
    }

    private fun maybePublishAliasSuggestion(text: String, glossary: List<Pair<String, String>>) {
        val observed = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return
        if (observed.length !in 3..24 || aliasDao.find(observed) != null) return
        if (glossary.any { it.first == observed }) return

        var best: Pair<String, String>? = null
        var bestScore = 0.0
        glossary.asSequence()
            .filter { kotlin.math.abs(it.first.length - observed.length) <= 1 }
            .take(400)
            .forEach { entry ->
                val score = similarity(observed, entry.first)
                if (score > bestScore) {
                    bestScore = score
                    best = entry
                }
            }
        if (bestScore >= 0.86) {
            best?.let { publishPendingAlias(observed, it.first, it.second) }
        }
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val distance = levenshtein(a, b)
        return 1.0 - distance.toDouble() / maxOf(a.length, b.length)
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
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

    private fun requestTranslation(input: String, selectedModel: String, maxOutputTokens: Int): ApiResponse {
        throwIfCancelled()
        val requestBody = JSONObject().apply {
            put("model", selectedModel)
            if (selectedModel.startsWith("gpt-5.6-")) {
                put("input", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "developer")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", SYSTEM_INSTRUCTIONS)
                                put("prompt_cache_breakpoint", JSONObject().apply { put("mode", "explicit") })
                            })
                        })
                    })
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "input_text")
                                put("text", input)
                            })
                        })
                    })
                })
                put("prompt_cache_key", "$PROMPT_CACHE_KEY_PREFIX:$selectedModel")
                put("prompt_cache_options", JSONObject().apply {
                    put("mode", "explicit")
                    put("ttl", "30m")
                })
            } else {
                put("instructions", SYSTEM_INSTRUCTIONS)
                put("input", input)
            }
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

        val request = Request.Builder()
            .url(API_URL)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val client = if (selectedModel.contains("pro")) PRO_HTTP_CLIENT else HTTP_CLIENT
        val call = client.newCall(request)
        synchronized(connectionLock) {
            if (cancelled.get()) {
                call.cancel()
                throw TranslationCancelledException()
            }
            activeCall = call
        }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            call.execute().use { response ->
                throwIfCancelled()
                val responseText = response.body?.string().orEmpty()
                throwIfCancelled()
                if (!response.isSuccessful) {
                    val message = runCatching {
                        JSONObject(responseText).optJSONObject("error")?.optString("message", "")
                    }.getOrNull().orEmpty()
                    val friendly = when (response.code) {
                        401, 403 -> "OpenAI API 키 인증 또는 권한에 실패했습니다"
                        429 -> "OpenAI 요청 한도에 걸렸습니다. 잠시 후 다시 시도하세요"
                        in 500..599 -> "OpenAI 서버가 일시적으로 응답하지 않습니다 (HTTP ${response.code})"
                        else -> message.ifBlank { "OpenAI API HTTP ${response.code}" }
                    }
                    throw IllegalStateException(friendly)
                }
                val parsed = extractResponse(responseText)
                Log.d(
                    PERF_TAG,
                    "api ${SystemClock.elapsedRealtime() - startedAt}ms model=$selectedModel cacheRead=${parsed.cachedInputTokens} cacheWrite=${parsed.cacheWriteTokens}"
                )
                return parsed
            }
        } catch (io: IOException) {
            if (cancelled.get() || call.isCanceled()) throw TranslationCancelledException()
            throw io
        } finally {
            synchronized(connectionLock) {
                if (activeCall === call) activeCall = null
            }
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
        val usageDetails = root.optJSONObject("usage")?.optJSONObject("input_tokens_details")
        val cachedInputTokens = usageDetails?.optInt("cached_tokens", 0) ?: 0
        val cacheWriteTokens = usageDetails?.optInt("cache_write_tokens", 0) ?: 0
        val direct = root.optString("output_text", "")
        if (direct.isNotBlank()) return ApiResponse(direct.trim(), responseModel, status, incompleteReason, cachedInputTokens, cacheWriteTokens)

        val output = root.optJSONArray("output") ?: return ApiResponse("", responseModel, status, incompleteReason, cachedInputTokens, cacheWriteTokens)
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val block = content.optJSONObject(j) ?: continue
                if (block.optString("type") != "output_text") continue
                block.optString("text", "").takeIf { it.isNotBlank() }?.let { parts += it }
            }
        }
        return ApiResponse(parts.joinToString("\n").trim(), responseModel, status, incompleteReason, cachedInputTokens, cacheWriteTokens)
    }

    private data class ApiResponse(
        val text: String,
        val model: String,
        val status: String,
        val incompleteReason: String,
        val cachedInputTokens: Int,
        val cacheWriteTokens: Int
    )

    companion object {
        data class PendingSpeakerCandidate(
            val source: String,
            val suggestedTarget: String,
            val hitCount: Int = 1,
            val firstSeenAt: Long = System.currentTimeMillis()
        )
        data class PendingAliasCandidate(
            val observed: String,
            val canonical: String,
            val target: String,
            val hitCount: Int = 1,
            val firstSeenAt: Long = System.currentTimeMillis()
        )

        private const val API_URL = "https://api.openai.com/v1/responses"
        private const val DEFAULT_MODEL = TranslationSettingsStore.DEFAULT_MODEL
        private const val PROMPT_CACHE_KEY_PREFIX = "zill-rpg-core-v1"
        private const val PERF_TAG = "ZillPerf"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        private val PRO_HTTP_CLIENT = HTTP_CLIENT.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
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
            "일본 판타지 RPG 대사를 한국 상업 RPG처럼 자연스러운 한국어 대사체로 번역한다. 일본어 어순과 표현을 기계적으로 직역하지 말고 한국어에 맞게 문장을 재구성하되 원문의 의미·정보·강도는 보존한다. 용어집과 승인된 화자 말투를 반드시 따른다. 존댓말/반말, 위계, 거친 말투, 고풍체, 캐릭터 말버릇은 제공된 문맥에서 확인되는 범위에서 일관되게 유지한다. 원문에 없는 성별·신분·관계·감정·설정은 만들지 않는다. 미등록 화자 후보 지시가 있을 때만 SPEAKER 마커를 첫 줄에 출력하고 그 외에는 현재 대사의 한국어 번역문만 출력하며 설명·주석·따옴표·원문은 출력하지 않는다."

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
        private val candidateLock = Any()
        private val pendingSpeakerCandidates = LinkedHashMap<String, PendingSpeakerCandidate>()
        private val pendingAliasCandidates = LinkedHashMap<String, PendingAliasCandidate>()
        private const val MAX_PENDING_SPEAKERS = 50
        private const val MAX_PENDING_ALIASES = 100

        fun normalizeSpeakerCandidateSource(text: String): String =
            text.trim().trim(*SPEAKER_DECORATION_CHARS).trim()

        private fun publishPendingSpeaker(source: String, target: String) {
            val normalized = normalizeSpeakerCandidateSource(source)
            if (normalized.isBlank()) return
            val context = AppContextHolder.require()
            if (IgnoreListStore.isSpeakerIgnored(context, normalized)) return
            synchronized(candidateLock) {
                val current = pendingSpeakerCandidates[normalized]
                pendingSpeakerCandidates[normalized] = if (current == null) {
                    PendingSpeakerCandidate(normalized, target)
                } else {
                    current.copy(hitCount = current.hitCount + 1)
                }
                trimCandidateMap(pendingSpeakerCandidates, MAX_PENDING_SPEAKERS) { it.hitCount to it.firstSeenAt }
            }
        }

        private fun publishPendingAlias(observed: String, canonical: String, target: String) {
            val key = observed.trim()
            if (key.isBlank()) return
            val context = AppContextHolder.require()
            if (IgnoreListStore.isAliasIgnored(context, key)) return
            synchronized(candidateLock) {
                val current = pendingAliasCandidates[key]
                pendingAliasCandidates[key] = if (current == null) {
                    PendingAliasCandidate(key, canonical, target)
                } else {
                    current.copy(hitCount = current.hitCount + 1)
                }
                trimCandidateMap(pendingAliasCandidates, MAX_PENDING_ALIASES) { it.hitCount to it.firstSeenAt }
            }
        }

        private fun <T> trimCandidateMap(
            map: LinkedHashMap<String, T>,
            limit: Int,
            rank: (T) -> Pair<Int, Long>
        ) {
            while (map.size > limit) {
                val victim = map.entries.minWithOrNull(
                    compareBy<Map.Entry<String, T>> { rank(it.value).first }
                        .thenBy { rank(it.value).second }
                ) ?: return
                map.remove(victim.key)
            }
        }

        fun pendingSpeakerCandidates(): List<PendingSpeakerCandidate> = synchronized(candidateLock) {
            pendingSpeakerCandidates.values.sortedWith(compareByDescending<PendingSpeakerCandidate> { it.hitCount }.thenBy { it.firstSeenAt })
        }

        fun pendingAliasCandidates(): List<PendingAliasCandidate> = synchronized(candidateLock) {
            pendingAliasCandidates.values.sortedWith(compareByDescending<PendingAliasCandidate> { it.hitCount }.thenBy { it.firstSeenAt })
        }

        fun pendingCandidateCount(): Int = synchronized(candidateLock) {
            pendingSpeakerCandidates.size + pendingAliasCandidates.size
        }

        fun peekPendingSpeakerCandidate(): PendingSpeakerCandidate? = pendingSpeakerCandidates().firstOrNull()
        fun peekPendingAliasCandidate(): PendingAliasCandidate? = pendingAliasCandidates().firstOrNull()

        fun approveSpeakerCandidate(source: String, targetOverride: String? = null): PendingSpeakerCandidate? {
            val key = normalizeSpeakerCandidateSource(source)
            val candidate = synchronized(candidateLock) { pendingSpeakerCandidates.remove(key) } ?: return null
            val target = targetOverride?.trim().takeUnless { it.isNullOrBlank() } ?: candidate.suggestedTarget
            val db = AppDatabase.get(AppContextHolder.require())
            db.speakerDao().upsert(SpeakerEntity(candidate.source, target, System.currentTimeMillis()))
            synchronized(speakerLock) { speakerCache = null }
            return candidate.copy(suggestedTarget = target)
        }

        fun approveAliasCandidate(observed: String, canonicalOverride: String? = null): PendingAliasCandidate? {
            val key = observed.trim()
            val candidate = synchronized(candidateLock) { pendingAliasCandidates.remove(key) } ?: return null
            val canonical = canonicalOverride?.trim().takeUnless { it.isNullOrBlank() } ?: candidate.canonical
            val db = AppDatabase.get(AppContextHolder.require())
            db.ocrAliasDao().upsert(OcrAliasEntity(candidate.observed, canonical, System.currentTimeMillis()))
            synchronized(memoryCache) { memoryCache.clear() }
            return candidate.copy(canonical = canonical)
        }

        fun ignoreSpeakerCandidate(source: String): Boolean {
            val key = normalizeSpeakerCandidateSource(source)
            val removed = synchronized(candidateLock) { pendingSpeakerCandidates.remove(key) } ?: return false
            IgnoreListStore.ignoreSpeaker(AppContextHolder.require(), key)
            return removed.source.isNotBlank()
        }

        fun ignoreAliasCandidate(observed: String): Boolean {
            val key = observed.trim()
            val removed = synchronized(candidateLock) { pendingAliasCandidates.remove(key) } ?: return false
            IgnoreListStore.ignoreAlias(AppContextHolder.require(), key)
            return removed.observed.isNotBlank()
        }

        fun approvePendingSpeaker(): PendingSpeakerCandidate? =
            peekPendingSpeakerCandidate()?.let { approveSpeakerCandidate(it.source) }

        fun approvePendingAlias(): PendingAliasCandidate? =
            peekPendingAliasCandidate()?.let { approveAliasCandidate(it.observed) }

        fun clearPendingCandidates() {
            synchronized(candidateLock) {
                pendingSpeakerCandidates.clear()
                pendingAliasCandidates.clear()
            }
        }

        fun clearMemoryCache() {
            synchronized(memoryCache) { memoryCache.clear() }
            synchronized(glossaryLock) { glossaryCache = null }
            synchronized(speakerLock) { speakerCache = null }
            synchronized(stickyLock) { stickySpeaker = null }
        }

        fun clearDialogueContext() {
            synchronized(stickyLock) { stickySpeaker = null }
            clearPendingCandidates()
        }
    }
}
