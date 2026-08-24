package kr.co.zillocr.patcher.patch

/** Read-only MIPS trace focused on the confirmed Shift-JIS decoder-like routine. */
object MipsSjisTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("targeted MIPS Shift-JIS trace v2")
        appendLine("ELF mapping: virtual address = file offset - 0x80")
        appendLine("confirmed behavior at 0x22655C..0x226600: validates Shift-JIS lead/trail bytes, combines lead<<8 | trail, stores the 16-bit code unit with sh, and returns 2 for a valid two-byte character.")
        appendLine("This is a decoder/validator stage, not yet the physical atlas-index calculation.")

        val center = 0x226580
        val fnStart = findFunctionStart(boot, center)
        val fnEnd = findFunctionEnd(boot, center)
        val fnVa = fnStart - 0x80
        appendLine("inferred decoder function: file=0x${hx(fnStart)}..0x${hx(fnEnd)} va=0x${hx(fnVa)}..0x${hx(fnEnd - 0x80)}")

        appendLine()
        appendLine("[decoder function]")
        dump(boot, fnStart, fnEnd.coerceAtMost(fnStart + 0x500)).forEach { appendLine(it) }

        val callers = findJalCallers(boot, fnVa)
        appendLine()
        appendLine("direct jal callers of inferred decoder entry (target va=0x${hx(fnVa)}): ${callers.size}")
        if (callers.isEmpty()) {
            appendLine("  none (routine may be entered through a wrapper, function pointer, or inferred start may need adjustment)")
        } else {
            callers.take(24).forEachIndexed { i, off ->
                appendLine("  #${i + 1} call file=0x${hx(off)} va=0x${hx(off - 0x80)}")
                val s = (off - 0x40).coerceAtLeast(0) and -4
                val e = (off + 0x70).coerceAtMost(boot.size - 4) and -4
                dump(boot, s, e).forEach { appendLine("    ${it.trimStart()}") }
            }
        }

        appendLine()
        appendLine("nearby direct-call targets from the decoder body:")
        val bodyCalls = mutableListOf<Pair<Int, Int>>()
        var p = fnStart
        while (p + 3 < boot.size && p <= fnEnd) {
            val w = u32(boot, p)
            if (((w ushr 26) and 0x3f) == 0x03) bodyCalls += p to jumpTargetVa(w, p)
            p += 4
        }
        if (bodyCalls.isEmpty()) appendLine("  none")
        bodyCalls.distinctBy { it.second }.forEach { (at, targetVa) ->
            appendLine("  jal @file=0x${hx(at)} -> va=0x${hx(targetVa)} file≈0x${hx(targetVa + 0x80)}")
        }

        appendLine()
        append("Interpretation: if callers pass a destination buffer that is later consumed by a renderer, trace that consumer next. The decoder itself preserves the Shift-JIS pair as a 16-bit value; it does not expose the atlas slot here.")
    }.trimEnd()

    private fun findFunctionStart(data: ByteArray, center: Int): Int {
        val min = (center - 0x500).coerceAtLeast(0) and -4
        var best = (center - 0x100).coerceAtLeast(0) and -4
        var off = center and -4
        while (off >= min) {
            val w = u32(data, off)
            val op = (w ushr 26) and 0x3f
            val rs = (w ushr 21) and 31
            val rt = (w ushr 16) and 31
            val imm = (w and 0xffff).toShort().toInt()
            if (op == 0x09 && rs == 29 && rt == 29 && imm < 0) {
                var sawRaSave = false
                var q = off
                while (q <= (off + 0x40).coerceAtMost(center) && q + 3 < data.size) {
                    val x = u32(data, q)
                    val xop = (x ushr 26) and 0x3f
                    val xrs = (x ushr 21) and 31
                    val xrt = (x ushr 16) and 31
                    if (xop == 0x2b && xrs == 29 && xrt == 31) sawRaSave = true
                    q += 4
                }
                if (sawRaSave) return off
                best = off
            }
            off -= 4
        }
        return best
    }

    private fun findFunctionEnd(data: ByteArray, center: Int): Int {
        var off = center and -4
        val max = (center + 0x800).coerceAtMost(data.size - 8) and -4
        while (off <= max) {
            if (u32(data, off) == 0x03E00008) return off + 4 // include delay slot
            off += 4
        }
        return max
    }

    private fun findJalCallers(data: ByteArray, targetVa: Int): List<Int> {
        val out = mutableListOf<Int>()
        var off = 0
        while (off + 3 < data.size) {
            val w = u32(data, off)
            if (((w ushr 26) and 0x3f) == 0x03 && jumpTargetVa(w, off) == targetVa) out += off
            off += 4
        }
        return out
    }

    private fun jumpTargetVa(w: Int, fileOff: Int): Int {
        val pcVa = fileOff - 0x80
        return ((pcVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
    }

    private fun dump(data: ByteArray, start: Int, end: Int): List<String> {
        val out = mutableListOf<String>()
        var off = start and -4
        val last = end.coerceAtMost(data.size - 4) and -4
        while (off <= last) {
            val w = u32(data, off)
            out += "  0x${hx(off).padStart(6,'0')}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w, off)}"
            off += 4
        }
        return out
    }

    private fun decode(w: Int, off: Int): String {
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
            val targetFile = off + 4 + (simm shl 2)
            return "file=0x${hx(targetFile)} va=0x${hx(targetFile - 0x80)}"
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
                0x20, 0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x22, 0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x24 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2a -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
            0x02 -> "j va=0x${hx(jumpTargetVa(w, off))}"
            0x03 -> "jal va=0x${hx(jumpTargetVa(w, off))} file≈0x${hx(jumpTargetVa(w, off) + 0x80)}"
            0x04 -> "beq ${r(rs)}, ${r(rt)}, ${bt()}"
            0x05 -> "bne ${r(rs)}, ${r(rt)}, ${bt()}"
            0x06 -> "blez ${r(rs)}, ${bt()}"
            0x07 -> "bgtz ${r(rs)}, ${bt()}"
            0x08, 0x09 -> "addiu ${r(rt)}, ${r(rs)}, $simm"
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

    private fun hx(v: Int): String = v.toUInt().toString(16).uppercase()

    private fun u32(data: ByteArray, o: Int): Int =
        (data[o].toInt() and 0xff) or
            ((data[o + 1].toInt() and 0xff) shl 8) or
            ((data[o + 2].toInt() and 0xff) shl 16) or
            ((data[o + 3].toInt() and 0xff) shl 24)
}
