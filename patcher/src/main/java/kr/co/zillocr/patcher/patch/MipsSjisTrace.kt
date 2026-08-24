package kr.co.zillocr.patcher.patch

/** Read-only MIPS tracing focused on the complete code-unit -> glyph lookup path. */
object MipsSjisTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("targeted renderer/glyph trace v8")
        appendLine("ELF mapping: virtual address = file offset - 0x80")
        appendLine("2.2 finding: file 0x1F9A34 is the Shift-JIS lead-byte classifier. More importantly, file 0x1F9F8C compares a 16-bit parser value against lhu 0(node), so it is a real keyed glyph/descriptor lookup path rather than generic renderer state.")
        appendLine("critical correction: the old function-end heuristic stopped at the first early jr ra. Both the multibyte parser and lookup have branch targets beyond that early return. This pass dumps the full continuation ranges explicitly.")
        appendLine()

        appendLine("=== multibyte parser FULL continuation ===")
        appendRange(boot, 0x1F9A34, 0x1F9B10, setOf(0x1F9A34, 0x1F9A4C, 0x1F9A78, 0x1F9A9C, 0x1F9AEC))

        appendLine()
        appendLine("=== keyed glyph lookup FULL continuation - strongest target ===")
        appendRange(boot, 0x1F9F8C, 0x1FA028, setOf(0x1F9F8C, 0x1F9FD4, 0x1FA008))

        appendLine()
        appendLine("=== glyph selector caller and argument setup ===")
        appendRange(boot, 0x1FA028, 0x1FA058, setOf(0x1FA028, 0x1FA044))

        appendLine()
        appendLine("=== direct callers of keyed lookup entry va=0x1F9F0C ===")
        findJalCallers(boot, 0x1F9F0C).take(24).forEachIndexed { i, off ->
            appendLine("  #${i + 1} call file=${hex(off)} va=${hex(off - 0x80)}")
            appendRange(boot, maxOf(0, off - 0x28), minOf(boot.size - 4, off + 0x34), setOf(off))
        }

        appendLine()
        appendLine("cross-check anchors:")
        appendLine("  Shift-JIS lead classifier: file 0x1F9A34")
        appendLine("  parser wrapper/result path: file 0x1F9A4C")
        appendLine("  keyed lookup: file 0x1F9F8C, key = a3 & 0xFFFF, node key = lhu 0(node)")
        appendLine("  selector: file 0x1FA028 passes the parser-derived 16-bit value into keyed lookup")
        appendLine("  atlas geometry: 15x16 slots, 34 columns, 1088 slots/page")
        append("Interpretation: if the full lookup continuation follows child pointers and returns a record whose fields encode page/x/y or ordinal, we have crossed the central mapping barrier. No writes are enabled.")
    }.trimEnd()

    private fun StringBuilder.appendRange(data: ByteArray, startRaw: Int, endRaw: Int, marks: Set<Int>) {
        var p = startRaw and -4
        val end = minOf(endRaw and -4, data.size - 4)
        while (p <= end && p + 3 < data.size) {
            val w = u32(data, p)
            val marker = if (p in marks) "  <TARGET>" else ""
            appendLine("  ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w, p)}$marker")
            p += 4
        }
    }

    private fun findJalCallers(data: ByteArray, targetVa: Int): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + 3 < data.size) {
            val w = u32(data, p)
            if (((w ushr 26) and 0x3f) == 0x03) {
                val callerVa = p - 0x80
                val va = ((callerVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                if (va == targetVa) out += p
            }
            p += 4
        }
        return out
    }

    private fun decode(w: Int, fileOff: Int): String {
        val op = (w ushr 26) and 0x3f
        val rs = (w ushr 21) and 31
        val rt = (w ushr 16) and 31
        val rd = (w ushr 11) and 31
        val sa = (w ushr 6) and 31
        val fn = w and 63
        val imm = w and 0xffff
        val simm = imm.toShort().toInt()
        fun r(i: Int) = regs[i]
        fun bt(): String {
            val targetFile = fileOff + 4 + (simm shl 2)
            return "file=${hex(targetFile)} va=${hex(targetFile - 0x80)}"
        }
        return when (op) {
            0x00 -> when (fn) {
                0x00 -> if (w == 0) "nop" else "sll ${r(rd)}, ${r(rt)}, $sa"
                0x02 -> "srl ${r(rd)}, ${r(rt)}, $sa"
                0x03 -> "sra ${r(rd)}, ${r(rt)}, $sa"
                0x04 -> "sllv ${r(rd)}, ${r(rt)}, ${r(rs)}"
                0x06 -> "srlv ${r(rd)}, ${r(rt)}, ${r(rs)}"
                0x08 -> "jr ${r(rs)}"
                0x09 -> "jalr ${r(rd)}, ${r(rs)}"
                0x10 -> "mfhi ${r(rd)}"
                0x12 -> "mflo ${r(rd)}"
                0x18 -> "mult ${r(rs)}, ${r(rt)}"
                0x19 -> "multu ${r(rs)}, ${r(rt)}"
                0x1a -> "div ${r(rs)}, ${r(rt)}"
                0x1b -> "divu ${r(rs)}, ${r(rt)}"
                0x20,0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x22,0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x24 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2a -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
            0x01 -> when (rt) {
                0x00 -> "bltz ${r(rs)}, ${bt()}"
                0x01 -> "bgez ${r(rs)}, ${bt()}"
                0x02 -> "bltzl ${r(rs)}, ${bt()}"
                0x03 -> "bgezl ${r(rs)}, ${bt()}"
                else -> "REGIMM rt=0x${rt.toString(16)} ${bt()}"
            }
            0x02,0x03 -> {
                val callerVa = fileOff - 0x80
                val targetVa = ((callerVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                (if (op == 0x03) "jal" else "j") + " va=${hex(targetVa)} file≈${hex(targetVa + 0x80)}"
            }
            0x04 -> "beq ${r(rs)}, ${r(rt)}, ${bt()}"
            0x05 -> "bne ${r(rs)}, ${r(rt)}, ${bt()}"
            0x06 -> "blez ${r(rs)}, ${bt()}"
            0x07 -> "bgtz ${r(rs)}, ${bt()}"
            0x08,0x09 -> "addiu ${r(rt)}, ${r(rs)}, $simm"
            0x0a -> "slti ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0b -> "sltiu ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0c -> "andi ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0d -> "ori ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0e -> "xori ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0f -> "lui ${r(rt)}, 0x${imm.toString(16).uppercase()}"
            0x14 -> "beql ${r(rs)}, ${r(rt)}, ${bt()}"
            0x15 -> "bnel ${r(rs)}, ${r(rt)}, ${bt()}"
            0x16 -> "blezl ${r(rs)}, ${bt()}"
            0x17 -> "bgtzl ${r(rs)}, ${bt()}"
            0x20 -> "lb ${r(rt)}, $simm(${r(rs)})"
            0x21 -> "lh ${r(rt)}, $simm(${r(rs)})"
            0x23 -> "lw ${r(rt)}, $simm(${r(rs)})"
            0x24 -> "lbu ${r(rt)}, $simm(${r(rs)})"
            0x25 -> "lhu ${r(rt)}, $simm(${r(rs)})"
            0x28 -> "sb ${r(rt)}, $simm(${r(rs)})"
            0x29 -> "sh ${r(rt)}, $simm(${r(rs)})"
            0x2b -> "sw ${r(rt)}, $simm(${r(rs)})"
            else -> "op=0x${op.toString(16).uppercase()} rs=${r(rs)} rt=${r(rt)} imm=0x${imm.toString(16).uppercase()}"
        }
    }

    private fun hex(v: Int): String = "0x${v.toUInt().toString(16).uppercase()}"
    private fun u32(data: ByteArray, o: Int): Int =
        (data[o].toInt() and 0xff) or
            ((data[o + 1].toInt() and 0xff) shl 8) or
            ((data[o + 2].toInt() and 0xff) shl 16) or
            ((data[o + 3].toInt() and 0xff) shl 24)
}
