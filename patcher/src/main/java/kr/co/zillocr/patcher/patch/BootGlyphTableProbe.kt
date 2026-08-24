package kr.co.zillocr.patcher.patch

/**
 * Read-only executable scanner for a CP932/glyph lookup table.
 *
 * It does not assume metrics order is atlas order. Instead it searches BOOT/EBOOT
 * for dense arrays whose 16-bit fields are members of the authenticated metrics
 * repertoire. This is intended to recover the game's own code->glyph mapping.
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
        val monotonicIndexHits: Int,
    ) {
        val ratio: Double get() = hits.toDouble() / windowEntries
    }

    data class Result(
        val bootSize: Int,
        val ebootSize: Int,
        val candidates: List<Candidate>,
        val targetOccurrences: Map<String, List<String>>,
    ) {
        fun report(): String = buildString {
            appendLine("executable glyph-table probe")
            appendLine("BOOT.BIN size: $bootSize")
            appendLine("EBOOT.BIN size: $ebootSize")
            appendLine("strategy: scan stride 2/4/8 u16 fields for dense membership in authenticated 2637-key metrics set")
            appendLine("top dense candidates:")
            if (candidates.isEmpty()) appendLine("  none")
            candidates.forEachIndexed { i, c ->
                appendLine(
                    "  #${i + 1} ${c.file} stride=${c.stride} field=${c.fieldOffset} " +
                        "start=0x${c.start.toString(16).uppercase()} hits=${c.hits}/${c.windowEntries} " +
                        "ratio=${"%.3f".format(c.ratio)} unique=${c.uniqueHits} indexStep=${c.monotonicIndexHits}"
                )
            }
            appendLine("target-key occurrences (u16 little-endian metric-key representation):")
            targetOccurrences.forEach { (label, values) ->
                appendLine("  $label: ${if (values.isEmpty()) "none" else values.take(20).joinToString("  ")}")
            }
            append("Interpretation: a candidate near 1.000 membership, especially stride=4 with a monotonic adjacent index field, is strong evidence of the retail lookup table. No font writes are enabled by this probe.")
        }.trimEnd()
    }

    fun analyze(boot: ByteArray, eboot: ByteArray, metrics: List<UpstreamMetrics.Entry>): Result {
        val keySet = metrics.mapTo(HashSet(metrics.size * 2)) { it.key and 0xffff }
        val files = listOf("BOOT.BIN" to boot, "EBOOT.BIN" to eboot)
        val candidates = mutableListOf<Candidate>()
        for ((name, data) in files) {
            for (stride in intArrayOf(2, 4, 8)) {
                for (field in 0 until stride step 2) {
                    candidates += scanDense(name, data, keySet, stride, field)
                }
            }
        }
        val best = candidates
            .sortedWith(compareByDescending<Candidate> { it.ratio }
                .thenByDescending { it.uniqueHits }
                .thenByDescending { it.monotonicIndexHits })
            .filterIndexed { index, c ->
                index < 24 && candidates.none { other ->
                    other !== c && other.file == c.file && other.stride == c.stride && other.fieldOffset == c.fieldOffset &&
                        kotlin.math.abs(other.start - c.start) < 256 &&
                        (other.ratio > c.ratio || (other.ratio == c.ratio && other.start < c.start))
                }
            }
            .take(16)

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
        return Result(boot.size, eboot.size, best, occurrences)
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
            var monotonic = 0
            var previousIndex: Int? = null
            for (i in 0 until window) {
                val pos = start + i * stride + fieldOffset
                val value = u16(data, pos)
                if (value in keySet) {
                    hits++
                    unique += value
                }
                if (stride >= 4) {
                    val adjacent = if (fieldOffset == 0) pos + 2 else pos - 2
                    if (adjacent >= 0 && adjacent + 1 < data.size) {
                        val idx = u16(data, adjacent)
                        val prev = previousIndex
                        if (prev != null && idx == prev + 1) monotonic++
                        previousIndex = idx
                    }
                }
            }
            if (hits >= 80) {
                out += Candidate(file, stride, fieldOffset, start, window, hits, unique.size, monotonic)
            }
            start += 2
        }
        return out
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
}
