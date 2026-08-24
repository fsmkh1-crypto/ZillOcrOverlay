package kr.co.zillocr.patcher.patch

/** Read-only MIPS tracing aimed at the first code-unit -> glyph consumer. */
object MipsSjisTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("targeted renderer/glyph trace v6")
        appendLine("ELF mapping: virtual address = file offset - 0x80")
        appendLine("2.0 finding: file 0x220130 is only a tiny pointer/default helper, not a glyph mapper. The renderer state loop at 0x1FA058 is now the strongest path because it consumes text through 0x1F9A4C / 0x1F9A9C and turns the result into a glyph descriptor via 0x1FA028.")
        appendLine("strategy: trace the parser/classifier, text-advance, glyph-descriptor selector and its geometry helpers. Success is a code-unit-derived bounded value that selects font/texture/glyph state.")

        val targets = listOf(
            "text classifier/parser" to 0x1F9A4C,
            "text advance / code-unit consumer" to 0x1F9A9C,
            "glyph descriptor selector" to 0x1FA028,
            "glyph geometry/state helper" to 0x1F9B14,
            "glyph width helper" to 0x1F84E4,
            "glyph height helper" to 0x1F8528,
            "common renderer object helper A" to 0x1F9CA0,
            "common renderer object helper B" to 0x1F9CF4,
        )
        for ((name, entry) in targets) {
            appendLine()
            appendLine("=== $name ===")
            appendFunctionTrace(boot, entry)
        }

        appendLine()
        appendLine("cross-check anchors:")
        appendLine("  Shift-JIS decoder file 0x2264A8 preserves two-byte code unit as lead<<8 | trail")
        appendLine("  renderer state loop file 0x1FA058 reads text at s0 and calls file 0x1F9A4C then 0x1F9A9C")
        appendLine("  file 0x1FA170 calls glyph-descriptor selector file 0x1FA028 with parser result in a2")
        appendLine("  atlas geometry: 15x16 slots, 34 columns, 1088 slots/page")
        append("Interpretation: the key target is the first helper where the text/code-unit value survives into table/pointer arithmetic or a bounded glyph descriptor/index. Pure string advance, control-code classification, geometry, allocation and memcpy are negative results. No writes are enabled.")
    }.trimEnd()

    private fun StringBuilder.appendFunctionTrace(data: ByteArray, entry: Int) {
        val start = inferFunctionStart(data, entry)
        val end = inferFunctionEnd(data, entry, start)
        appendLine("entry file=${hex(entry)} va=${hex(entry - 0x80)}")
        appendLine("inferred function file=${hex(start)}..${hex(end)} va=${hex(start - 0x80)}..${hex(end - 0x80)}")
        val callers = findJalCallers(data, start - 0x80)
        appendLine("direct callers: ${callers.size}")
        callers.take(20).forEachIndexed { i, off -> appendLine("  caller#${i + 1} file=${hex(off)} va=${hex(off - 0x80)}") }

        val childCalls = linkedMapOf<Int, MutableList<Int>>()
        var p = start
        while (p <= end && p + 3 < data.size) {
            val w = u32(data, p)
            if (((w ushr 26) and 0x3f) == 0x03) {
                val callerVa = p - 0x80
                val targetVa = ((callerVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                childCalls.getOrPut(targetVa) { mutableListOf() } += p
            }
            p += 4
        }
        appendLine("child jal targets: ${childCalls.size}")
        childCalls.entries.take(28).forEach { (va, at) ->
            appendLine("  va=${hex(va)} file≈${hex(va + 0x80)} from ${at.joinToString { hex(it) }}")
        }

        appendLine("function disassembly:")
        p = start
        var lines = 0
        while (p <= end && p + 3 < data.size && lines < 360) {
            val w = u32(data, p)
            val marker = if (p == entry) "  <ENTRY>" else ""
            appendLine("  ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w, p)}$marker")
            p += 4
            lines++
        }
        if (p <= end) appendLine("  ... truncated ...")

        appendLine("character/index-looking operations:")
        p = start
        var shown = 0
        while (p <= end && p + 3 < data.size && shown < 180) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val fn = w and 0x3f
            val imm = w and 0xffff
            val interesting = op in setOf(0x20,0x21,0x23,0x24,0x25,0x28,0x29,0x2b,0x0a,0x0b,0x0c,0x0d,0x0e,0x0f) ||
                (op == 0 && fn in setOf(0x00,0x02,0x03,0x04,0x06,0x10,0x12,0x18,0x19,0x1a,0x1b,0x21,0x23,0x24,0x25,0x2a,0x2b)) ||
                imm in setOf(0x000f,0x0010,0x001f,0x0020,0x003f,0x0040,0x005e,0x007f,0x0080,0x0081,0x009f,0x00a0,0x00bc,0x00df,0x00e0,0x00fc,0x00fd,0x00ff,0x0100,0x0200,0x0400,0x0440,0x0800,0x0c00,0x1000)
            if (interesting) {
                appendLine("  ${hex(p)}  ${decode(w, p)}")
                shown++
            }
            p += 4
        }
    }

    private fun inferFunctionStart(data: ByteArray, site: Int): Int {
        var p = site and -4
        val floor = maxOf(0, p - 0x900)
        while (p >= floor) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val rs = (w ushr 21) and 31
            val rt = (w ushr 16) and 31
            val imm = (w and 0xffff).toShort().toInt()
            if (op == 0x09 && rs == 29 && rt == 29 && imm < 0) return p
            p -= 4
        }
        return site and -4
    }

    private fun inferFunctionEnd(data: ByteArray, site: Int, start: Int): Int {
        var p = maxOf(site, start) and -4
        val ceiling = minOf(data.size - 8, start + 0x1800)
        while (p <= ceiling) {
            if (u32(data, p) == 0x03E00008) return minOf(data.size - 4, p + 4)
            p += 4
        }
        return minOf(data.size - 4, start + 0x900)
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
