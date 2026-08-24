package kr.co.zillocr.patcher.patch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Physical glyph locator that first tests a deterministic OpenType cmap/glyph-ID
 * hypothesis against the three known ASCII cells. Only when one constant glyph-ID
 * offset explains 0/A/a do non-ASCII targets receive physical-cell predictions.
 *
 * If that deterministic test fails, the old bitmap matcher remains available for
 * ASCII diagnostics only. Non-ASCII visual fallback is deliberately disabled so an
 * Android fallback font can never be mistaken for the authenticated upstream OTF.
 */
object FontGlyphMatcher {
    data class Match(
        val target: String,
        val sectionIndex: Int,
        val ordinal: Int,
        val cellX: Int,
        val cellY: Int,
        val score: Double,
    )

    private val knownAsciiAbsoluteCells = mapOf(
        "0" to 16,
        "A" to 31,
        "a" to 60,
    )

    fun rank(
        previews: List<GimFontProbe.Preview>,
        targets: List<String>,
        topN: Int = 8,
        typeface: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL),
    ): Map<String, List<Match>> {
        val pages = previews.filter {
            it.sectionIndex in 0..2 && it.image.width == 512 && it.image.height == 512
        }
        if (pages.isEmpty()) return emptyMap()

        deterministicCmapMatches(pages, targets)?.let { return it }

        // Deterministic cmap relation failed. Keep bitmap matching only as an ASCII
        // diagnostic. Japanese/kanji targets stay empty instead of silently using
        // Android's fallback CJK font and producing false physical-cell confidence.
        return targets.associateWith { target ->
            if (!isAscii(target)) return@associateWith emptyList()
            visualRank(pages, target, topN, typeface)
        }
    }

    private fun deterministicCmapMatches(
        pages: List<GimFontProbe.Preview>,
        targets: List<String>,
    ): Map<String, List<Match>>? {
        val font = UpstreamSourceFont.authenticatedSnapshot() ?: return null
        val allTargets = (knownAsciiAbsoluteCells.keys + targets).distinct()
        val mappings = OpenTypeCmapProbe.map(font, allTargets).associateBy { it.text }
        val offsets = ArrayList<Int>()
        for ((text, cell) in knownAsciiAbsoluteCells) {
            val gid = mappings[text]?.glyphId ?: return null
            offsets += cell - gid
        }
        if (offsets.distinct().size != 1) return null
        val offset = offsets.first()
        val pageBySection = pages.associateBy { it.sectionIndex }
        return targets.associateWith { target ->
            val gid = mappings[target]?.glyphId ?: return@associateWith emptyList()
            val absolute = gid + offset
            if (absolute !in 0 until 3 * 1024) return@associateWith emptyList()
            val section = absolute / 1024
            if (pageBySection[section] == null) return@associateWith emptyList()
            val ordinal = absolute % 1024
            listOf(
                Match(
                    target = target,
                    sectionIndex = section,
                    ordinal = ordinal,
                    cellX = ordinal % 32,
                    cellY = ordinal / 32,
                    score = 1.0,
                )
            )
        }
    }

    private fun isAscii(target: String): Boolean =
        target.codePointCount(0, target.length) == 1 && target.codePointAt(0) in 0x20..0x7e

    private fun visualRank(
        pages: List<GimFontProbe.Preview>,
        target: String,
        topN: Int,
        typeface: Typeface,
    ): List<Match> {
        val refs = referenceVariants(target, typeface)
        val matches = ArrayList<Match>(pages.size * 1024)
        pages.forEach { page ->
            for (ordinal in 0 until 1024) {
                val cell = normalizedAtlasCell(page.englishArgb, ordinal) ?: continue
                var best = 0.0
                refs.forEach { ref -> best = max(best, similarity(cell, ref)) }
                matches += Match(
                    target = target,
                    sectionIndex = page.sectionIndex,
                    ordinal = ordinal,
                    cellX = ordinal % 32,
                    cellY = ordinal / 32,
                    score = best,
                )
            }
        }
        return matches.sortedByDescending { it.score }.take(topN)
    }

    private fun referenceVariants(target: String, typeface: Typeface): List<BooleanArray> {
        val out = ArrayList<BooleanArray>()
        for (size in listOf(28f, 32f, 36f, 40f, 44f, 48f)) {
            for (xOffset in listOf(1f, 3f, 5f)) {
                for (baselineAdjust in listOf(-2f, 0f, 2f)) {
                    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    val paint = Paint().apply {
                        isAntiAlias = false
                        isSubpixelText = false
                        color = 0xffffffff.toInt()
                        textSize = size
                        this.typeface = typeface
                    }
                    val fm = paint.fontMetrics
                    canvas.drawText(target, xOffset, xOffset - fm.top + baselineAdjust, paint)
                    val mask = BooleanArray(64 * 64)
                    val pixels = IntArray(64 * 64)
                    bmp.getPixels(pixels, 0, 64, 0, 0, 64, 64)
                    for (i in pixels.indices) mask[i] = ((pixels[i] ushr 24) and 0xff) > 0
                    normalize(mask, 64, 64)?.let(out::add)
                    bmp.recycle()
                }
            }
        }
        return out.distinctBy { it.contentHashCode() }
    }

    private fun normalizedAtlasCell(argb: IntArray, ordinal: Int): BooleanArray? {
        val x0 = (ordinal % 32) * 16
        val y0 = (ordinal / 32) * 16
        val mask = BooleanArray(16 * 16)
        var p = 0
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val a = (argb[(y0 + y) * 512 + x0 + x] ushr 24) and 0xff
                mask[p++] = a > 0
            }
        }
        return normalize(mask, 16, 16)
    }

    private fun normalize(mask: BooleanArray, width: Int, height: Int): BooleanArray? {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (!mask[y * width + x]) continue
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
            }
        }
        if (maxX < minX || maxY < minY) return null
        val srcW = maxX - minX + 1
        val srcH = maxY - minY + 1
        val scale = min(14.0 / srcW, 14.0 / srcH)
        val dstW = max(1, (srcW * scale).toInt())
        val dstH = max(1, (srcH * scale).toInt())
        val offX = (16 - dstW) / 2
        val offY = (16 - dstH) / 2
        val out = BooleanArray(16 * 16)
        for (dy in 0 until dstH) {
            val sy = minY + min(srcH - 1, (dy / scale).toInt())
            for (dx in 0 until dstW) {
                val sx = minX + min(srcW - 1, (dx / scale).toInt())
                if (mask[sy * width + sx]) out[(offY + dy) * 16 + offX + dx] = true
            }
        }
        return out
    }

    private fun similarity(a: BooleanArray, b: BooleanArray): Double {
        var aCount = 0
        var bCount = 0
        var intersection = 0
        val ar = IntArray(16)
        val br = IntArray(16)
        val ac = IntArray(16)
        val bc = IntArray(16)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val i = y * 16 + x
                if (a[i]) {
                    aCount++
                    ar[y]++
                    ac[x]++
                }
                if (b[i]) {
                    bCount++
                    br[y]++
                    bc[x]++
                }
                if (a[i] && b[i]) intersection++
            }
        }
        if (aCount == 0 || bCount == 0) return 0.0
        val dice = 2.0 * intersection / (aCount + bCount)
        var rowDiff = 0
        var colDiff = 0
        for (i in 0 until 16) {
            rowDiff += kotlin.math.abs(ar[i] - br[i])
            colDiff += kotlin.math.abs(ac[i] - bc[i])
        }
        val projection = 1.0 - (rowDiff + colDiff).toDouble() / max(1, 2 * (aCount + bCount))
        return 0.75 * dice + 0.25 * projection.coerceIn(0.0, 1.0)
    }
}
