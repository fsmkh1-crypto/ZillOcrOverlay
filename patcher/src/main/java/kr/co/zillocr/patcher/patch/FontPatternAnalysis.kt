package kr.co.zillocr.patcher.patch

/**
 * Conservative pattern analysis over the authenticated upstream font XOR delta.
 * It does not assume that PAR sections are glyph pages or that a numeric metrics
 * key is a physical glyph index. The goal is only to surface repeat structure
 * worth testing next.
 */
object FontPatternAnalysis {
    private val candidateShifts = intArrayOf(8, 12, 16, 20, 24, 32, 40, 48, 64, 80, 96, 128, 160, 192, 256, 320, 384, 512)

    fun report(xorPatch: ByteArray, sections: List<ZillFontIsoAnalyzer.ParSection>): String {
        if (sections.size < 2) return "cross-section pattern analysis: insufficient PAR sections"

        val relativeChanged = sections.map { section ->
            val limit = section.size
            buildSet {
                for (relative in 0 until limit) {
                    if (xorPatch[section.start + relative].toInt() != 0) add(relative)
                }
            }
        }

        return buildString {
            appendLine("cross-section XOR pattern analysis")
            appendLine("pairwise same-relative-offset overlap (intersection / union, Jaccard):")
            for (i in sections.indices) {
                for (j in i + 1 until sections.size) {
                    val a = relativeChanged[i]
                    val b = relativeChanged[j]
                    val intersection = if (a.size <= b.size) a.count { it in b } else b.count { it in a }
                    val union = a.size + b.size - intersection
                    val jaccard = if (union == 0) 0.0 else intersection.toDouble() / union.toDouble()
                    appendLine("  s$i vs s$j: $intersection / $union (${String.format("%.4f", jaccard)})")
                }
            }

            val common = relativeChanged.drop(1).fold(relativeChanged.first().toMutableSet()) { acc, set ->
                acc.apply { retainAll(set) }
            }
            appendLine("same relative changed byte in all ${sections.size} sections: ${common.size}")
            if (common.isNotEmpty()) {
                appendLine("  first offsets: " + common.sorted().take(32).joinToString(" ") { "0x${it.toString(16).uppercase()}" })
            }

            appendLine("within-section shifted-change overlap (hits / changed bytes):")
            sections.forEachIndexed { index, section ->
                val changed = relativeChanged[index]
                val scores = candidateShifts.toList().mapNotNull { shift: Int ->
                    if (shift >= section.size || changed.isEmpty()) return@mapNotNull null
                    val hits = changed.count { relative -> relative + shift in changed }
                    Triple(shift, hits, hits.toDouble() / changed.size.toDouble())
                }.sortedByDescending { it.third }.take(8)
                appendLine("  section $index: " + scores.joinToString("  ") { (shift, hits, ratio) ->
                    "0x${shift.toString(16).uppercase()}:$hits/${changed.size}(${String.format("%.3f", ratio)})"
                })
            }
        }.trimEnd()
    }
}
