package kr.co.zillocr.patcher.patch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * Heuristic visual matcher for locating physical 16x16 glyph cells without assuming
 * metrics.toml textual ordering. A caller-supplied Typeface is used for references;
 * PoC 0.9 supplies upstream's authenticated fs-tahoma-8px.otf rather than Android's
 * device-dependent sans font.
 *
 * ASCII matching is performed against the authenticated reconstructed-English atlas,
 * because that atlas was built from the same retained source font. Physical cell
 * positions are unchanged by the XOR patch, so a successful ASCII match establishes
 * the atlas coordinate system without relying on the retail glyph design.
 *
 * This remains a diagnostic aid. ASCII anchors must rank correctly before Japanese
 * candidates are trusted.
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

        return targets.associateWith { target ->
            val refs = referenceVariants(target, typeface)
            val matches = ArrayList<Match>(pages.size * 1024)
            pages.forEach { page ->
                for (ordinal in 0 until 1024) {
                    // English reconstruction is authenticated byte-for-byte and uses
                    // upstream's source typeface for the Latin repertoire. Cells left
                    // untouched by the English patch are identical to retail.
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
            matches.sortedByDescending { it.score }.take(topN)
        }
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
