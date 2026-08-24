package kr.co.zillocr.patcher.patch

/**
 * Read-only scanner for the 32-byte keyed record layout recovered from the renderer.
 *
 * Recovered executable behavior:
 *   key     = u16(node + 0x00)
 *   child A = s32(node + 0x10)
 *   child B = s32(node + 0x14)
 *   node    = base + childIndex * 0x20
 *
 * This probe looks for known Shift-JIS keys sitting at the head of records whose
 * neighboring fields and child indices resemble the renderer's glyph descriptors.
 */
object GlyphRecordTreeProbe {
    private data class Target(val name: String, val key: Int)
    private data class Candidate(
        val source: String,
        val offset: Int,
        val target: Target,
        val score: Int,
        val neighborHits: Int,
        val fields: String,
        val raw: String,
    )

    private val targets = listOf(
        Target("0", 0x0030),
        Target("A", 0x0041),
        Target("a", 0x0061),
        Target("ア", 0x4183),
        Target("イ", 0x4383),
        Target("テ", 0x6583),
        Target("ム", 0x8083),
        Target("腑", 0x44E4),
        Target("躙", 0x57E7),
        Target("綺", 0x59E3),
    )

    fun report(boot: ByteArray, eboot: ByteArray): String = buildString {
        appendLine("32-byte keyed glyph-record probe v1")
        appendLine("recovered node layout: key=u16(+0x00), child indices=s32(+0x10/+0x14), stride=0x20")
        appendLine("goal: find executable-resident records for known characters and expose +2/+3/+4/+6/+8/+A/+C/+E/+18 fields that may encode atlas geometry.")

        val candidates = scan("BOOT.BIN", boot) + scan("EBOOT.BIN", eboot)
        val ranked = candidates.sortedWith(compareByDescending<Candidate> { it.score }.thenByDescending { it.neighborHits })

        appendLine("candidate records: ${ranked.size}")
        if (ranked.isEmpty()) {
            appendLine("  none passed the structural filter")
        } else {
            ranked.take(80).forEachIndexed { i, c ->
                appendLine("  #${i + 1} ${c.source}@${hex(c.offset)} ${c.target.name}/0x${c.target.key.toString(16).uppercase().padStart(4, '0')} score=${c.score} neighbor32=${c.neighborHits}")
                appendLine("      ${c.fields}")
                appendLine("      raw32=${c.raw}")
            }
        }

        appendLine()
        appendLine("best candidate per target:")
        for (t in targets) {
            val c = ranked.firstOrNull { it.target == t }
            if (c == null) appendLine("  ${t.name}/0x${t.key.toString(16).uppercase().padStart(4, '0')}: none")
            else appendLine("  ${t.name}/0x${t.key.toString(16).uppercase().padStart(4, '0')}: ${c.source}@${hex(c.offset)} score=${c.score} neighbor32=${c.neighborHits} ${c.fields}")
        }

        appendLine()
        appendLine("interpretation:")
        appendLine("  strong evidence = several known keys share one 32-byte residue/region, child fields are -1 or bounded indices, and geometry-looking fields are small/plausible.")
        appendLine("  if ASCII 0/A/a records are found, their geometry can be cross-checked against known physical ordinals 17/33/64 before any font write.")
        append("No writes are enabled.")
    }.trimEnd()

    private fun scan(source: String, data: ByteArray): List<Candidate> {
        val out = ArrayList<Candidate>()
        for (t in targets) {
            var o = 0
            while (o + 0x20 <= data.size) {
                if (u16(data, o) == t.key) {
                    val left = i32(data, o + 0x10)
                    val right = i32(data, o + 0x14)
                    val b2 = u8(data, o + 2)
                    val b3 = u8(data, o + 3)
                    val u4 = u16(data, o + 4)
                    val u6 = u16(data, o + 6)
                    val s8 = i16(data, o + 8)
                    val sA = i16(data, o + 0xA)
                    val sC = i16(data, o + 0xC)
                    val sE = i16(data, o + 0xE)
                    val b18 = u8(data, o + 0x18)

                    var score = 0
                    if (plausibleChild(left)) score += 2
                    if (plausibleChild(right)) score += 2
                    if (b2 <= 64) score += 1
                    if (b3 <= 64) score += 1
                    if (u4 <= 1024) score += 1
                    if (u6 <= 1024) score += 1
                    if (kotlin.math.abs(s8) <= 512) score += 1
                    if (kotlin.math.abs(sA) <= 512) score += 1
                    if (kotlin.math.abs(sC) <= 512) score += 1
                    if (kotlin.math.abs(sE) <= 512) score += 1
                    if (b18 <= 64) score += 1

                    val neighbors = neighborScore(data, o)
                    score += minOf(8, neighbors)
                    if (score >= 8) {
                        val fields = "+2=$b2 +3=$b3 +4=$u4 +6=$u6 +8=$s8 +A=$sA +C=$sC +E=$sE +10=$left +14=$right +18=$b18"
                        out += Candidate(source, o, t, score, neighbors, fields, hexBytes(data, o, 0x20))
                    }
                }
                o++
            }
        }
        return out
    }

    private fun neighborScore(data: ByteArray, center: Int): Int {
        var hits = 0
        for (delta in -8..8) {
            if (delta == 0) continue
            val o = center + delta * 0x20
            if (o < 0 || o + 0x20 > data.size) continue
            val key = u16(data, o)
            val left = i32(data, o + 0x10)
            val right = i32(data, o + 0x14)
            if (plausibleSjisKey(key) && plausibleChild(left) && plausibleChild(right)) hits++
        }
        return hits
    }

    private fun plausibleChild(v: Int): Boolean = v == -1 || v in 0..8191

    private fun plausibleSjisKey(key: Int): Boolean {
        if (key in 0x20..0x7E) return true
        val lead = key and 0xFF
        val trail = (key ushr 8) and 0xFF
        val leadOk = lead in 0x81..0x9F || lead in 0xE0..0xEF
        val trailOk = trail in 0x40..0xFC && trail != 0x7F
        return leadOk && trailOk
    }

    private fun u8(d: ByteArray, o: Int): Int = d[o].toInt() and 0xFF
    private fun u16(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8)
    private fun i16(d: ByteArray, o: Int): Int = u16(d, o).toShort().toInt()
    private fun i32(d: ByteArray, o: Int): Int = u8(d, o) or (u8(d, o + 1) shl 8) or (u8(d, o + 2) shl 16) or (u8(d, o + 3) shl 24)

    private fun hex(v: Int): String = "0x${v.toUInt().toString(16).uppercase()}"
    private fun hexBytes(d: ByteArray, o: Int, n: Int): String = (0 until n).joinToString(" ") { "%02X".format(u8(d, o + it)) }
}
