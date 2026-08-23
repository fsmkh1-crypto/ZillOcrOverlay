from pathlib import Path

ROOT = Path('.')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if old not in text:
        raise SystemExit(f'missing patch anchor: {label}')
    return text.replace(old, new, 1)

# 1) Provider: never persist glossary-derived speaker without approval,
# and expose the canonical/aliased source used by cache + override lookup.
p = ROOT / 'app/src/main/java/kr/co/zillocr/overlay/translation/OpenAiTranslationProvider.kt'
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    @Volatile var lastSpeakerWasCandidate: Boolean = false\n        private set\n',
    '    @Volatile var lastSpeakerWasCandidate: Boolean = false\n        private set\n    @Volatile var lastAliasedText: String = ""\n        private set\n',
    'lastAliasedText property',
)
s = replace_once(
    s,
    '        val aliasedText = applyApprovedAlias(japaneseText)\n\n        lastSpeakerSource = null\n',
    '        val aliasedText = applyApprovedAlias(japaneseText)\n        lastAliasedText = aliasedText\n\n        lastSpeakerSource = null\n',
    'lastAliasedText assignment',
)
s = replace_once(
    s,
    '''            glossary.firstOrNull { normalizeSpeakerLabel(it.first) == normalizedFirst }\n                ?.takeIf { isStrongSpeakerCandidate(normalizedFirst) }\n                ?.let { legacy ->\n                    promoteLegacySpeaker(legacy.first, legacy.second)\n                    if (updateSticky) rememberSpeaker(legacy.first, legacy.second)\n                    return DialogueParts(\n                        legacy.first,\n                        legacy.second,\n                        lines.drop(1).joinToString("\\n"),\n                        true,\n                        null\n                    )\n                }\n''',
    '''            glossary.firstOrNull { normalizeSpeakerLabel(it.first) == normalizedFirst }\n                ?.takeIf { isStrongSpeakerCandidate(normalizedFirst) }\n                ?.let { legacy ->\n                    // 용어집 일치는 화자명 후보의 근거로만 사용한다.\n                    // 사용자 승인 전에는 speaker DB에 영구 저장하지 않는다.\n                    publishPendingSpeaker(legacy.first, legacy.second)\n                    if (updateSticky) rememberSpeaker(legacy.first, legacy.second)\n                    return DialogueParts(\n                        legacy.first,\n                        legacy.second,\n                        lines.drop(1).joinToString("\\n"),\n                        true,\n                        null\n                    )\n                }\n''',
    'legacy speaker approval path',
)
s = replace_once(
    s,
    '''    private fun promoteLegacySpeaker(source: String, target: String) {\n        speakerDao.upsert(SpeakerEntity(source, target, System.currentTimeMillis()))\n        synchronized(speakerLock) { speakerCache = null }\n    }\n\n''',
    '',
    'remove auto promotion',
)
p.write_text(s, encoding='utf-8')

# 2/3/4) Service: save corrections/feedback under the aliased source actually
# used by the provider, bind feedback to the displayed result, and refresh
# auto-height state immediately after a real resize gesture.
p = ROOT / 'app/src/main/java/kr/co/zillocr/overlay/capture/ScreenOcrService.kt'
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '    @Volatile private var lastRawTranslation = ""\n    @Volatile private var lastResultSpeakerSource: String? = null\n',
    '    @Volatile private var lastRawTranslation = ""\n    @Volatile private var lastCanonicalSourceText = ""\n    @Volatile private var lastResultRequest: TranslationRequest? = null\n    @Volatile private var lastResultSpeakerSource: String? = null\n',
    'displayed result state',
)
s = replace_once(
    s,
    '''                lastRawTranslation = translated\n                lastResultSpeakerSource = speakerSource\n                lastResultSpeakerTarget = speakerTarget\n                mainHandler.post {\n                    if (lastRecognizedText == request.text) {\n                        showResultOverlay(formatTranslatedDisplay(translated, speakerTarget, explicit, candidate))\n                        showPendingLearningHintIfNeeded()\n                    }\n                }\n''',
    '''                mainHandler.post {\n                    if (lastRecognizedText == request.text) {\n                        lastRawTranslation = translated\n                        lastCanonicalSourceText = provider.lastAliasedText.ifBlank { request.text }\n                        lastResultRequest = request\n                        lastResultSpeakerSource = speakerSource\n                        lastResultSpeakerTarget = speakerTarget\n                        showResultOverlay(formatTranslatedDisplay(translated, speakerTarget, explicit, candidate))\n                        showPendingLearningHintIfNeeded()\n                    }\n                }\n''',
    'commit only displayed translation state',
)
s = replace_once(
    s,
    '''    private fun recordPositiveFeedback() {\n        val request = lastTranslationRequest ?: return\n        if (lastRawTranslation.isBlank()) return\n        val speaker = lastResultSpeakerSource\n''',
    '''    private fun recordPositiveFeedback() {\n        val request = lastResultRequest ?: return\n        if (lastRawTranslation.isBlank()) return\n        val sourceText = lastCanonicalSourceText.ifBlank { request.text }\n        val speaker = lastResultSpeakerSource\n''',
    'positive feedback displayed request',
)
s = replace_once(
    s,
    '                    sourceText = request.text,\n                    model = request.model,\n                    rating = 1,\n',
    '                    sourceText = sourceText,\n                    model = request.model,\n                    rating = 1,\n',
    'positive feedback canonical source',
)
s = replace_once(
    s,
    '    private fun showCorrectionDialog() {\n        val request = lastTranslationRequest ?: return\n',
    '    private fun showCorrectionDialog() {\n        val request = lastResultRequest ?: return\n',
    'correction displayed request',
)
s = replace_once(
    s,
    '''    private fun saveCorrection(request: TranslationRequest, corrected: String) {\n        val speakerSource = lastResultSpeakerSource\n        learningExecutor.execute {\n            val db = AppDatabase.get(this)\n            val now = System.currentTimeMillis()\n            db.translationOverrideDao().upsert(\n                TranslationOverrideEntity(request.text, request.model, corrected, speakerSource, now)\n            )\n            db.feedbackDao().insert(\n                FeedbackEntity(\n                    sourceText = request.text,\n''',
    '''    private fun saveCorrection(request: TranslationRequest, corrected: String) {\n        val speakerSource = lastResultSpeakerSource\n        val sourceText = lastCanonicalSourceText.ifBlank { request.text }\n        learningExecutor.execute {\n            val db = AppDatabase.get(this)\n            val now = System.currentTimeMillis()\n            db.translationOverrideDao().upsert(\n                TranslationOverrideEntity(sourceText, request.model, corrected, speakerSource, now)\n            )\n            db.feedbackDao().insert(\n                FeedbackEntity(\n                    sourceText = sourceText,\n''',
    'correction canonical source',
)
s = replace_once(
    s,
    '            db.translationDao().invalidateContaining(request.text)\n',
    '            db.translationDao().invalidateContaining(sourceText)\n',
    'canonical cache invalidation',
)
s = replace_once(
    s,
    '                            Toast.makeText(this, "직접 크기 조절 · 자동 높이 OFF", Toast.LENGTH_SHORT).show()\n',
    '                            Toast.makeText(this, "직접 크기 조절 · 자동 높이 OFF", Toast.LENGTH_SHORT).show()\n                            updateControlStateLabels()\n',
    'resize state label refresh',
)
s = replace_once(
    s,
    '''                lastTranslationRequest = null\n                lastRawTranslation = ""\n''',
    '''                lastTranslationRequest = null\n                lastResultRequest = null\n                lastRawTranslation = ""\n                lastCanonicalSourceText = ""\n''',
    'region reset result state',
)
p.write_text(s, encoding='utf-8')

# Hotfix build identity so a future device install upgrades alpha8 cleanly.
p = ROOT / 'app/build.gradle.kts'
s = p.read_text(encoding='utf-8')
s = replace_once(s, 'versionCode = 21', 'versionCode = 22', 'versionCode')
s = replace_once(s, 'versionName = "0.5.0-alpha8"', 'versionName = "0.5.0-alpha8.1"', 'versionName')
p.write_text(s, encoding='utf-8')

p = ROOT / 'app/src/main/java/kr/co/zillocr/overlay/MainActivity.kt'
s = p.read_text(encoding='utf-8')
s = replace_once(
    s,
    '질올 실시간 번역 오버레이 · 0.5.0 alpha8',
    '질올 실시간 번역 오버레이 · 0.5.0 alpha8.1',
    'visible version label',
)
p.write_text(s, encoding='utf-8')

print('alpha8 review hotfix applied')
