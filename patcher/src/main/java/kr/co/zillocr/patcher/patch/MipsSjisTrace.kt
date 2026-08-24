package kr.co.zillocr.patcher.patch

/** Targeted read-only disassembly around Shift-JIS decoding and known text-renderer sites. */
object MipsSjisTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("targeted renderer/glyph trace v3")
        appendLine("ELF mapping: virtual address = file offset - 0x80")
        appendLine("decoder finding carried forward: 0x22655C..0x226600 validates Shift-JIS and preserves lead<<8 | trail; it is not the atlas mapper.")
        appendLine("new strategy: pivot to upstream-authenticated renderer patch sites, recover their containing functions, direct callers and call targets, and look for the first consumer that turns a decoded code unit into glyph storage/index state.")

        val rendererSites = listOf(
            "profile glyph-bucket site" to 0x145E88,
            "profile main renderer stack site" to 0x1564C4,
            "profile biography renderer-source site" to 0x7165C,
        )
        for ((name, site) in rendererSites) {
            appendLine()
            appendLine("=== $name ===")
            appendRendererFunctionTrace(boot, site)
        }

        appendLine()
        appendLine("=== decoder callers retained for cross-check ===")
        val decoderEntryFile = 0x2264A8
        val decoderVa = decoderEntryFile - 0x80
        val callers = findJalCallers(boot, decoderVa)
        appendLine("decoder direct jal callers: ${callers.size}")
        callers.take(8).forEachIndexed { i, off ->
            appendLine("  #${i + 1} call file=${hex(off)} va=${hex(off - 0x80)}")
        }

        appendLine()
        append("Interpretation: the useful renderer candidate is the function whose body loads text/code units (lbu/lhu), then performs arithmetic/table access before storing per-glyph state or issuing a render call. The 0x145E88 site is known upstream to size a renderer glyph bucket; 0x1564C4 is inside the profile renderer's expanded scratch-frame path. No writes are enabled.")
    }.trimEnd()

    private fun StringBuilder.appendRendererFunctionTrace(boot: ByteArray, site: Int) {
        val start = inferFunctionStart(boot, site)
        val end = inferFunctionEnd(boot, site, start)
        appendLine("site file=${hex(site)} va=${hex(site - 0x80)}")
        appendLine("inferred function file=${hex(start)}..${hex(end)} va=${hex(start - 0x80)}..${hex(end - 0x80)}")

        val functionVa = start - 0x80
        val callers = findJalCallers(boot, functionVa)
        appendLine("direct jal callers of inferred entry: ${callers.size}")
        callers.take(8).forEachIndexed { i, call ->
            appendLine("  caller#${i + 1} file=${hex(call)} va=${hex(call - 0x80)}")
        }

        val callTargets = linkedMapOf<Int, MutableList<Int>>()
        var p = start
        while (p <= end && p + 3 < boot.size) {
            val w = u32(boot, p)
            if (((w ushr 26) and 0x3f) == 0x03) {
                val va = ((p - 0x80 + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                callTargets.getOrPut(va) { mutableListOf() } += p
            }
            p += 4
        }
        appendLine("direct call targets inside function: ${callTargets.size}")
        callTargets.entries.take(16).forEach { (va, calls) ->
            appendLine("  jal target va=${hex(va)} file≈${hex(va + 0x80)} from ${calls.joinToString { hex(it) }}")
        }

        val dumpStart = maxOf(start, site - 0x120)
        val dumpEnd = minOf(end, site + 0x180)
        appendLine("focused disassembly around site:")
        p = dumpStart and -4
        while (p <= dumpEnd && p + 3 < boot.size) {
            val w = u32(boot, p)
            val marker = if (p == site) "  <SITE>" else ""
            appendLine("  ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w, p)}$marker")
            p += 4
        }

        appendLine("nearby text/glyph-looking memory operations:")
        val memStart = maxOf(start, site - 0x300)
        val memEnd = minOf(end, site + 0x300)
        var shown = 0
        p = memStart and -4
        while (p <= memEnd && p + 3 < boot.size && shown < 48) {
            val w = u32(boot, p)
            val op = (w ushr 26) and 0x3f
            if (op in setOf(0x20,0x24,0x21,0x25,0x28,0x29,0x2b)) {
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
        return maxOf(0, (site - 0x180) and -4)
    }

    private fun inferFunctionEnd(data: ByteArray, site: Int, start: Int): Int {
        var p = maxOf(site, start) and -4
        val ceiling = minOf(data.size - 8, p + 0x1200)
        while (p <= ceiling) {
            if (u32(data, p) == 0x03E00008) return minOf(data.size - 4, p + 4)
            p += 4
        }
        return minOf(data.size - 4, site + 0x240)
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
                0x20, 0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x22, 0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x24 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2a -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
            0x02, 0x03 -> {
                val callerVa = fileOff - 0x80
                val targetVa = ((callerVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                (if (op == 0x03) "jal" else "j") + " va=${hex(targetVa)} file≈${hex(targetVa + 0x80)}"
            }
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

    private fun hex(v: Int): String = "0x${v.toUInt().toString(16).uppercase()}"

    private fun u32(data: ByteArray, o: Int): Int =
        (data[o].toInt() and 0xff) or
            ((data[o + 1].toInt() and 0xff) shl 8) or
            ((data[o + 2].toInt() and 0xff) shl 16) or
            ((data[o + 3].toInt() and 0xff) shl 24)
}
