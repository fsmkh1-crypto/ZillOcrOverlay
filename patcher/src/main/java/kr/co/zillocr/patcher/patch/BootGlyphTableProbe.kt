package kr.co.zillocr.patcher.patch

/**
 * Read-only executable scanner for a CP932/glyph lookup table.
 *
 * The first probe found dense CP932 regions inside the fixed-string area. Those
 * are useful negative controls, but dense membership alone is not sufficient to
 * identify the renderer lookup. This version explicitly distinguishes string-like
 * blobs from record-like key/index tables and also searches much longer windows.
 */
object BootGlyphTableProbe {
    data class Candidate(
        val file: String,
        val stride: Int,
        val fieldOffset: Int,
        val start: Int,
        val windowEntries: Int,
        val hits: Int,
        val uniqueHits: Int,
        val adjacentMetricHits: Int,
        val adjacentGlyphRangeHits: Int,
        val adjacentUnique: Int,
        val monotonicIndexHits: Int,
    ) {
        val ratio: Double get() = hits.toDouble() / windowEntries
        val adjacentMetricRatio: Double get() = adjacentMetricHits.toDouble() / windowEntries
        val adjacentGlyphRangeRatio: Double get() = adjacentGlyphRangeHits.toDouble() / windowEntries
        val likelyText: Boolean get() = stride >= 4 && adjacentMetricRatio > 0.65
        val likelyRecordTable: Boolean get() =
            stride >= 4 && ratio > 0.85 && adjacentMetricRatio < 0.25 && adjacentGlyphRangeRatio > 0.75 && adjacentUnique > windowEntries / 2
    }

    data class LongRun(
        val file: String,
        val start: Int,
        val entries: Int,
        val hits: Int,
        val uniqueHits: Int,
    ) {
        val ratio: Double get() = hits.toDouble() / entries
    }

    data class Result(
        val bootSize: Int,
        val ebootSize: Int,
        val candidates: List<Candidate>,
        val longRuns: List<LongRun>,
        val targetOccurrences: Map<String, List<String>>,
    ) {
        fun report(): String = buildString {
            appendLine("executable glyph-table probe v2")
            appendLine("BOOT.BIN size: $bootSize")
            appendLine("EBOOT.BIN size: $ebootSize")
            appendLine("strategy: reject CP932 text blobs; prefer key/index records with adjacent values in glyph-index range 0..3071")
            appendLine("top record candidates:")
            val recordLike = candidates.filter { it.likelyRecordTable }
            if (recordLike.isEmpty()) appendLine("  none")
            recordLike.take(12).forEachIndexed { i, c -> appendLine(formatCandidate(i + 1, c)) }
            appendLine("top dense candidates (including negative-control text blobs):")
            if (candidates.isEmpty()) appendLine("  none")
            candidates.take(12).forEachIndexed { i, c -> appendLine(formatCandidate(i + 1, c)) }
            appendLine("long contiguous metric-key windows (stride=2; a real repertoire table should stay dense for hundreds/thousands of entries):")
            if (longRuns.isEmpty()) appendLine("  none")
            longRuns.take(10).forEachIndexed { i, r ->
                appendLine("  #${i + 1} ${r.file} start=0x${r.start.toString(16).uppercase()} entries=${r.entries} hits=${r.hits} ratio=${"%.3f".format(r.ratio)} unique=${r.uniqueHits}")
            }
            appendLine("target-key occurrences (u16 little-endian metric-key representation):")
            targetOccurrences.forEach { (label, values) ->
                appendLine("  $label: ${if (values.isEmpty()) "none" else values.take(20).joinToString("  ")}")
            }
            append("Interpretation: likelyText=true means the previous dense hit is probably packed game text, not a glyph table. A strong lookup candidate needs key density plus a non-text adjacent field that behaves like a 0..3071 glyph index. No writes are enabled.")
        }.trimEnd()

        private fun formatCandidate(rank: Int, c: Candidate): String =
            "  #$rank ${c.file} stride=${c.stride} field=${c.fieldOffset} start=0x${c.start.toString(16).uppercase()} " +
                "hits=${c.hits}/${c.windowEntries} ratio=${"%.3f".format(c.ratio)} unique=${c.uniqueHits} " +
                "adjMetric=${c.adjacentMetricHits}/${c.windowEntries} adjGlyph=${c.adjacentGlyphRangeHits}/${c.windowEntries} " +
                "adjUnique=${c.adjacentUnique} indexStep=${c.monotonicIndexHits} likelyText=${c.likelyText} likelyRecord=${c.likelyRecordTable}"
    }

    fun analyze(boot: ByteArray, eboot: ByteArray, metrics: List<UpstreamMetrics.Entry>): Result {
        val keySet = metrics.mapTo(HashSet(metrics.size * 2)) { it.key and 0xffff }
        val files = listOf("BOOT.BIN" to boot, "EBOOT.BIN" to eboot)
        val candidates = mutableListOf<Candidate>()
        for ((name, data) in files) {
            for (stride in intArrayOf(4, 8)) {
                for (field in 0 until stride step 2) {
                    candidates += scanDense(name, data, keySet, stride, field)
                }
            }
        }
        val best = candidates
            .sortedWith(compareByDescending<Candidate> { it.likelyRecordTable }
                .thenByDescending { it.ratio }
                .thenByDescending { it.adjacentGlyphRangeRatio }
                .thenByDescending { it.uniqueHits }
                .thenBy { it.adjacentMetricRatio })
            .filterIndexed { index, c ->
                index < 80 && candidates.none { other ->
                    other !== c && other.file == c.file && other.stride == c.stride && other.fieldOffset == c.fieldOffset &&
                        kotlin.math.abs(other.start - c.start) < 256 &&
                        (other.likelyRecordTable && !c.likelyRecordTable ||
                            other.ratio > c.ratio || (other.ratio == c.ratio && other.start < c.start))
                }
            }
            .take(24)

        val longRuns = files.flatMap { (name, data) -> scanLongMetricWindows(name, data, keySet) }
            .sortedWith(compareByDescending<LongRun> { it.ratio }.thenByDescending { it.uniqueHits })
            .take(12)

        val targets = linkedMapOf(
            "0/0x0030" to 0x0030,
            "A/0x0041" to 0x0041,
            "a/0x0061" to 0x0061,
            "ア/0x4183" to 0x4183,
            "イ/0x4383" to 0x4383,
            "テ/0x6583" to 0x6583,
            "ム/0x8083" to 0x8083,
            "腑/0x44E4" to 0x44e4,
            "躙/0x57E7" to 0x57e7,
            "綺/0x59E3" to 0x59e3,
        )
        val occurrences = linkedMapOf<String, List<String>>()
        for ((label, key) in targets) {
            val found = mutableListOf<String>()
            for ((name, data) in files) {
                var off = 0
                while (off + 1 < data.size) {
                    if (u16(data, off) == key) found += "$name@0x${off.toString(16).uppercase()}"
                    off += 2
                }
            }
            occurrences[label] = found
        }
        return Result(boot.size, eboot.size, best, longRuns, occurrences)
    }

    private fun scanDense(
        file: String,
        data: ByteArray,
        keySet: Set<Int>,
        stride: Int,
        fieldOffset: Int,
    ): List<Candidate> {
        val window = 128
        val out = mutableListOf<Candidate>()
        val maxStart = data.size - (window - 1) * stride - fieldOffset - 2
        if (maxStart <= 0) return out
        var start = 0
        while (start <= maxStart) {
            var hits = 0
            val unique = HashSet<Int>()
            var adjacentMetricHits = 0
            var adjacentGlyphHits = 0
            val adjacentUnique = HashSet<Int>()
            var monotonic = 0
            var previousIndex: Int? = null
            for (i in 0 until window) {
                val pos = start + i * stride + fieldOffset
                val value = u16(data, pos)
                if (value in keySet) {
                    hits++
                    unique += value
                }
                val adjacent = when {
                    fieldOffset + 2 < stride -> pos + 2
                    fieldOffset >= 2 -> pos - 2
                    else -> -1
                }
                if (adjacent >= 0 && adjacent + 1 < data.size) {
                    val idx = u16(data, adjacent)
                    if (idx in keySet) adjacentMetricHits++
                    if (idx in 0..3071) adjacentGlyphHits++
                    adjacentUnique += idx
                    val prev = previousIndex
                    if (prev != null && idx == prev + 1) monotonic++
                    previousIndex = idx
                }
            }
            if (hits >= 80) {
                out += Candidate(
                    file, stride, fieldOffset, start, window, hits, unique.size,
                    adjacentMetricHits, adjacentGlyphHits, adjacentUnique.size, monotonic
                )
            }
            start += 2
        }
        return out
    }

    private fun scanLongMetricWindows(file: String, data: ByteArray, keySet: Set<Int>): List<LongRun> {
        val out = mutableListOf<LongRun>()
        for (window in intArrayOf(512, 1024, 2048)) {
            val byteSpan = window * 2
            if (data.size < byteSpan) continue
            var start = 0
            while (start + byteSpan <= data.size) {
                var hits = 0
                val unique = HashSet<Int>()
                var pos = start
                repeat(window) {
                    val value = u16(data, pos)
                    if (value in keySet) {
                        hits++
                        unique += value
                    }
                    pos += 2
                }
                if (hits >= window * 3 / 4) out += LongRun(file, start, window, hits, unique.size)
                start += 32
            }
        }
        return out
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}
