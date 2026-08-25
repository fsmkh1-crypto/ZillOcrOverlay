package kr.co.zillocr.patcher.patch

/**
 * Read-only executable probe for the game's CP932 -> glyph mapping path.
 *
 * v2 showed no convincing key/index record table. v3 therefore keeps the
 * negative-control data scan, but also searches MIPS code for clusters of
 * Shift-JIS range-check immediates. If the mapping is algorithmic rather than
 * table-driven, the decoder/renderer should contain several of these constants
 * close together.
 */
object BootGlyphTableProbe {
    data class LongRun(
        val file: String,
        val start: Int,
        val entries: Int,
        val hits: Int,
        val uniqueHits: Int,
    ) {
        val ratio: Double get() = hits.toDouble() / entries
    }

    data class SjisCodeCandidate(
        val file: String,
        val start: Int,
        val end: Int,
        val matches: Int,
        val distinctImmediates: Int,
        val details: List<String>,
    )

    data class Result(
        val bootSize: Int,
        val ebootSize: Int,
        val longRuns: List<LongRun>,
        val sjisCodeCandidates: List<SjisCodeCandidate>,
        val targetOccurrences: Map<String, List<String>>,
    ) {
        fun report(): String = buildString {
            appendLine("executable glyph-table probe v3")
            appendLine("BOOT.BIN size: $bootSize")
            appendLine("EBOOT.BIN size: $ebootSize")
            appendLine("v2 result carried forward: no convincing CP932 key/index record table was found")
            appendLine("strategy now: look for MIPS code regions containing clustered Shift-JIS range-check immediates")
            appendLine("Shift-JIS decoder code candidates:")
            if (sjisCodeCandidates.isEmpty()) {
                appendLine("  none")
            } else {
                sjisCodeCandidates.take(12).forEachIndexed { i, c ->
                    appendLine(
                        "  #${i + 1} ${c.file} 0x${c.start.toString(16).uppercase()}..0x${c.end.toString(16).uppercase()} " +
                            "matches=${c.matches} distinct=${c.distinctImmediates}"
                    )
                    c.details.take(12).forEach { appendLine("      $it") }
                }
            }
            appendLine("long contiguous metric-key windows (data negative controls):")
            if (longRuns.isEmpty()) appendLine("  none")
            longRuns.take(8).forEachIndexed { i, r ->
                appendLine(
                    "  #${i + 1} ${r.file} start=0x${r.start.toString(16).uppercase()} entries=${r.entries} " +
                        "hits=${r.hits} ratio=${"%.3f".format(r.ratio)} unique=${r.uniqueHits}"
                )
            }
            appendLine("target-key occurrences (u16 little-endian metric-key representation):")
            targetOccurrences.forEach { (label, values) ->
                appendLine("  $label: ${if (values.isEmpty()) "none" else values.take(12).joinToString("  ")}")
            }
            append(
                "Interpretation: v2 makes a flat key/index lookup table unlikely. A strong v3 code candidate has several distinct " +
                    "Shift-JIS boundary constants (81/9F/E0/FC and 40/7F/80/FC, or signed-subtract forms) in one small MIPS region. " +
                    "No writes are enabled."
            )
        }.trimEnd()
    }

    fun analyze(boot: ByteArray, eboot: ByteArray, metrics: List<UpstreamMetrics.Entry>): Result {
        val keySet = metrics.mapTo(HashSet(metrics.size * 2)) { it.key and 0xffff }
        val files = listOf("BOOT.BIN" to boot, "EBOOT.BIN" to eboot)

        val longRuns = files.flatMap { (name, data) -> scanLongMetricWindows(name, data, keySet) }
            .sortedWith(compareByDescending<LongRun> { it.ratio }.thenByDescending { it.uniqueHits })
            .take(12)

        val codeCandidates = files.flatMap { (name, data) -> scanSjisMipsCode(name, data) }
            .sortedWith(
                compareByDescending<SjisCodeCandidate> { it.distinctImmediates }
                    .thenByDescending { it.matches }
            )
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
        return Result(boot.size, eboot.size, longRuns, codeCandidates, occurrences)
    }

    /**
     * Search aligned MIPS words. We intentionally only inspect the immediate
     * field of opcodes that commonly implement comparisons/arithmetic/bit masks.
     */
    private fun scanSjisMipsCode(file: String, data: ByteArray): List<SjisCodeCandidate> {
        val interesting = setOf(
            0x0040, 0x007f, 0x0080, 0x0081, 0x009f, 0x00e0, 0x00fc,
            // Common signed forms after addiu/subtract-style normalization.
            0xff7f, 0xff61, 0xff60, 0xff20, 0xff04,
            // Typical range widths produced after normalization.
            0x001f, 0x003c, 0x003f, 0x00bc, 0x00bd,
        )
        val allowedOpcodes = setOf(
            0x08, // addi
            0x09, // addiu
            0x0a, // slti
            0x0b, // sltiu
            0x0c, // andi
            0x0d, // ori
            0x0e, // xori
            0x0f, // lui
            0x20, // lb
            0x24, // lbu
            0x21, // lh
            0x25, // lhu
        )
        data class Hit(val offset: Int, val word: Int, val imm: Int, val opcode: Int)
        val hits = mutableListOf<Hit>()
        var off = 0
        while (off + 3 < data.size) {
            val word = u32(data, off)
            val opcode = (word ushr 26) and 0x3f
            val imm = word and 0xffff
            if (opcode in allowedOpcodes && imm in interesting) {
                hits += Hit(off, word, imm, opcode)
            }
            off += 4
        }
        if (hits.isEmpty()) return emptyList()

        // Cluster hits whose total span fits within 0x100 bytes. This is small
        // enough to resemble one decoder/helper routine rather than random code.
        val out = mutableListOf<SjisCodeCandidate>()
        var left = 0
        for (right in hits.indices) {
            while (hits[right].offset - hits[left].offset > 0x100) left++
            val count = right - left + 1
            if (count < 4) continue
            val slice = hits.subList(left, right + 1)
            val distinct = slice.map { it.imm }.toSet().size
            if (distinct < 3) continue
            val start = (slice.first().offset - 0x20).coerceAtLeast(0) and -4
            val end = (slice.last().offset + 0x24).coerceAtMost(data.size) and -4
            val details = slice.map {
                "@0x${it.offset.toString(16).uppercase()} word=0x${it.word.toUInt().toString(16).uppercase().padStart(8, '0')} " +
                    "op=0x${it.opcode.toString(16).uppercase()} imm=0x${it.imm.toString(16).uppercase().padStart(4, '0')}"
            }
            out += SjisCodeCandidate(file, start, end, count, distinct, details)
        }

        return out.sortedWith(
            compareByDescending<SjisCodeCandidate> { it.distinctImmediates }
                .thenByDescending { it.matches }
        ).filterIndexed { index, c ->
            index < 80 && out.none { other ->
                other !== c && other.file == c.file && kotlin.math.abs(other.start - c.start) < 0x80 &&
                    (other.distinctImmediates > c.distinctImmediates ||
                        other.distinctImmediates == c.distinctImmediates && other.matches > c.matches)
            }
        }.take(20)
    }

    private fun scanLongMetricWindows(file: String, data: ByteArray, keySet: Set<Int>): List<LongRun> {
        val entries = 512
        val stepBytes = 0x20
        val out = mutableListOf<LongRun>()
        val byteLength = entries * 2
        if (data.size < byteLength) return out
        var start = 0
        while (start + byteLength <= data.size) {
            var hits = 0
            val unique = HashSet<Int>()
            var p = start
            repeat(entries) {
                val v = u16(data, p)
                if (v in keySet) {
                    hits++
                    unique += v
                }
                p += 2
            }
            if (hits >= 400) out += LongRun(file, start, entries, hits, unique.size)
            start += stepBytes
        }
        return out
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
}
