package kr.co.zillocr.overlay.patch

/**
 * Encodes Hangul through CP932-compatible surrogate glyphs for the native
 * Zill O'll Infinite Plus Korean patch.
 *
 * The game remains on its existing two-byte text path. The corresponding font
 * slots are redrawn as Hangul by the Korean font patch.
 */
object KoreanSurrogateCodec {
    data class Glyph(
        val hangul: Char,
        val surrogate: Char,
        val cp932Hex: String,
        val metricKey: String,
        val advance: Int = 12,
    )

    val pocGlyphs: List<Glyph> = listOf(
        Glyph('아', '腑', "E444", "0x44e4"),
        Glyph('이', '躙', "E757", "0x57e7"),
        Glyph('템', '綺', "E359", "0x59e3"),
    )

    private val encodeMap = pocGlyphs.associate { it.hangul to it.surrogate }
    private val decodeMap = pocGlyphs.associate { it.surrogate to it.hangul }

    fun encode(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(encodeMap[ch] ?: ch)
        }
    }

    fun decodeForDiagnostics(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(decodeMap[ch] ?: ch)
        }
    }

    fun unmappedHangul(text: String): Set<Char> = text
        .asSequence()
        .filter { it in '\uAC00'..'\uD7A3' && it !in encodeMap }
        .toCollection(linkedSetOf())
}
