package kr.co.zillocr.patcher.patch

/**
 * Minimal read-only OpenType cmap parser used to test whether zillfont atlas cells
 * follow the authenticated source font's glyph ID order. This is intentionally
 * independent of Android text rasterization.
 */
object OpenTypeCmapProbe {
    data class Mapping(
        val text: String,
        val codePoint: Int,
        val glyphId: Int?,
    )

    fun map(font: ByteArray, targets: List<String>): List<Mapping> {
        val cmapOffset = findTable(font, "cmap") ?: return targets.map {
            Mapping(it, it.codePointAt(0), null)
        }
        val subtable = chooseCmapSubtable(font, cmapOffset)
        return targets.map { text ->
            val cp = text.codePointAt(0)
            Mapping(text, cp, subtable?.glyphId(cp))
        }
    }

    private interface CmapSubtable {
        fun glyphId(codePoint: Int): Int?
    }

    private fun findTable(data: ByteArray, tag: String): Int? {
        require(data.size >= 12) { "truncated OpenType header" }
        val numTables = u16(data, 4)
        require(data.size >= 12 + numTables * 16) { "truncated OpenType table directory" }
        for (i in 0 until numTables) {
            val off = 12 + i * 16
            val actual = String(data, off, 4, Charsets.US_ASCII)
            if (actual == tag) {
                val tableOffset = u32(data, off + 8)
                val length = u32(data, off + 12)
                require(tableOffset >= 0 && length >= 0 && tableOffset + length <= data.size) {
                    "OpenType table $tag extends past file"
                }
                return tableOffset
            }
        }
        return null
    }

    private fun chooseCmapSubtable(data: ByteArray, cmap: Int): CmapSubtable? {
        require(cmap + 4 <= data.size) { "truncated cmap header" }
        val count = u16(data, cmap + 2)
        require(cmap + 4 + count * 8 <= data.size) { "truncated cmap encoding records" }
        data class Candidate(val priority: Int, val offset: Int, val format: Int)
        val candidates = ArrayList<Candidate>()
        for (i in 0 until count) {
            val rec = cmap + 4 + i * 8
            val platform = u16(data, rec)
            val encoding = u16(data, rec + 2)
            val rel = u32(data, rec + 4)
            val sub = cmap + rel
            if (sub + 2 > data.size) continue
            val format = u16(data, sub)
            val priority = when {
                format == 12 && platform == 3 && encoding == 10 -> 0
                format == 12 && platform == 0 -> 1
                format == 4 && platform == 3 && encoding in 1..10 -> 2
                format == 4 && platform == 0 -> 3
                else -> 100
            }
            if (priority < 100) candidates += Candidate(priority, sub, format)
        }
        val chosen = candidates.minByOrNull { it.priority } ?: return null
        return when (chosen.format) {
            4 -> Format4(data, chosen.offset)
            12 -> Format12(data, chosen.offset)
            else -> null
        }
    }

    private class Format12(private val data: ByteArray, private val start: Int) : CmapSubtable {
        private val groups: Int
        init {
            require(start + 16 <= data.size) { "truncated cmap format 12" }
            val length = u32(data, start + 4)
            require(length >= 16 && start + length <= data.size) { "invalid cmap format 12 length" }
            groups = u32(data, start + 12)
            require(start + 16 + groups * 12 <= start + length) { "truncated cmap format 12 groups" }
        }
        override fun glyphId(codePoint: Int): Int? {
            var lo = 0
            var hi = groups - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val off = start + 16 + mid * 12
                val first = u32(data, off)
                val last = u32(data, off + 4)
                when {
                    codePoint < first -> hi = mid - 1
                    codePoint > last -> lo = mid + 1
                    else -> return u32(data, off + 8) + (codePoint - first)
                }
            }
            return null
        }
    }

    private class Format4(private val data: ByteArray, private val start: Int) : CmapSubtable {
        private val length: Int
        private val segCount: Int
        private val endCodes: Int
        private val startCodes: Int
        private val idDeltas: Int
        private val idRangeOffsets: Int

        init {
            require(start + 16 <= data.size) { "truncated cmap format 4" }
            length = u16(data, start + 2)
            require(length >= 16 && start + length <= data.size) { "invalid cmap format 4 length" }
            segCount = u16(data, start + 6) / 2
            require(segCount > 0) { "empty cmap format 4" }
            endCodes = start + 14
            startCodes = endCodes + segCount * 2 + 2
            idDeltas = startCodes + segCount * 2
            idRangeOffsets = idDeltas + segCount * 2
            require(idRangeOffsets + segCount * 2 <= start + length) { "truncated cmap format 4 arrays" }
        }

        override fun glyphId(codePoint: Int): Int? {
            if (codePoint !in 0..0xffff) return null
            for (i in 0 until segCount) {
                val end = u16(data, endCodes + i * 2)
                if (codePoint > end) continue
                val begin = u16(data, startCodes + i * 2)
                if (codePoint < begin) return null
                val delta = s16(data, idDeltas + i * 2)
                val range = u16(data, idRangeOffsets + i * 2)
                if (range == 0) return (codePoint + delta) and 0xffff
                val rangeWord = idRangeOffsets + i * 2
                val glyphPos = rangeWord + range + 2 * (codePoint - begin)
                if (glyphPos + 2 > start + length) return null
                val raw = u16(data, glyphPos)
                if (raw == 0) return null
                return (raw + delta) and 0xffff
            }
            return null
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun s16(data: ByteArray, offset: Int): Int {
        val v = u16(data, offset)
        return if (v and 0x8000 != 0) v - 0x10000 else v
    }

    private fun u32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)
}
