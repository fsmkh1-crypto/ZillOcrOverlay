package kr.co.zillocr.patcher.patch

/** PoC 3.3 read-only trace: resolve the exact glyph resource identifiers through their loader. */
object GlyphArchiveOriginTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("glyph resource-id / loader trace v4")
        appendLine("PoC 3.3 · read-only · BOOT writes disabled")
        appendLine("decisive writer: owner initializer calls loader VA 0x1CE528 with output slots owner+0x380 and owner+0x384.")
        appendLine("resource identifiers: +0x380 <- VA 0x252550; +0x384 <- VA 0x252564.")
        appendLine()

        appendLine("=== recovered container header parser ===")
        appendLine("parser VA 0x1DDBA0 / file 0x1DDC20")
        appendLine("container+0x00=raw base; +0x04=raw+0x10 offset table; +0x08=aligned 0x20-byte descriptor table; +0x14=count(raw+8); +0x1C=type/version(raw+4).")
        appendLine("helper B(index) => rawBase + offsetTable[index], guarded by index < count. index=2 is the third sub-block.")
        dump(boot, 0x1DDC20, 0x1DDE18, setOf(0x1DDC20, 0x1DDCA4, 0x1DDCC0, 0x1DDCC8))
        appendLine()

        appendLine("=== exact owner writer bridge ===")
        appendLine("owner+0x380: loader(0x1CE528, id VA 0x252550, out owner+0x380)")
        appendLine("owner+0x384: loader(0x1CE528, id VA 0x252564, out owner+0x384)")
        dump(boot, 0x145828, 0x14588C, setOf(0x145840, 0x145858, 0x145868, 0x145884))
        appendLine()

        appendLine("=== static resource identifiers ===")
        probeStaticVa(boot, "owner+0x380 resource id", 0x252550)
        appendLine()
        probeStaticVa(boot, "owner+0x384 glyph resource id", 0x252564)
        appendLine()

        appendLine("=== resource loader VA 0x1CE528 / file 0x1CE5A8 ===")
        appendLine("The third call argument (a2) is the owner field address; trace stores/callbacks that reach it.")
        dump(boot, 0x1CE5A8, 0x1CE8F8, setOf(0x1CE5A8))
        appendLine()

        appendLine("=== resource factory VA 0x1CDE2C / file 0x1CDEAC ===")
        dump(boot, 0x1CDEAC, 0x1CE120, setOf(0x1CDEAC))
        appendLine()

        appendLine("=== narrow caller confirmation ===")
        appendLine("owner initializer VA 0x145784 callers: " +
            findJalCallers(boot, 0x145784).joinToString(" ") { "file=${hex(it)}" })
        appendLine("loader VA 0x1CE528 callers: " +
            findJalCallers(boot, 0x1CE528).joinToString(" ") { "file=${hex(it)}" })
        appendLine()

        appendLine("Decision rule: resolve VA 0x252564 through the loader to its ISO/PAA source, parse that raw container with the recovered header, then extract offsetTable[2] and validate glyph descriptors for 0/A/a. No patch writes are enabled.")
    }.trimEnd()

    private fun StringBuilder.scanOwnerResourceFields(data: ByteArray) {
        val hits = mutableListOf<Int>()
        var p = 0
        while (p + 3 < data.size) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val simm = (w and 0xffff).toShort().toInt()
            if (simm in setOf(0x380, 0x384) && op in setOf(0x20,0x21,0x23,0x24,0x25,0x28,0x29,0x2b)) hits += p
            p += 4
        }
        appendLine("direct field access count=${hits.size}")
        hits.forEachIndexed { i, off ->
            val w = u32(data, off)
            val op = (w ushr 26) and 0x3f
            val kind = if (op in setOf(0x28,0x29,0x2b)) "WRITE" else "READ"
            appendLine("  #${i+1} $kind file=${hex(off)} va=${hex(off-0x80)}  ${decode(w,off)}")
            dump(data, maxOf(0,off-0x50), minOf(data.size-4,off+0x50), setOf(off))
        }
    }

    private fun StringBuilder.scanGlobalOwnerMaterialization(data: ByteArray) {
        val hits = mutableListOf<Int>()
        var p = 0
        while (p + 7 < data.size) {
            val w = u32(data,p)
            val op = (w ushr 26) and 0x3f
            val rt = (w ushr 16) and 31
            if (op == 0x0f && (w and 0xffff) == 0x0001) {
                val n = u32(data,p+4)
                val nop = (n ushr 26) and 0x3f
                val nrs = (n ushr 21) and 31
                val nrt = (n ushr 16) and 31
                val simm = (n and 0xffff).toShort().toInt()
                if (nop in setOf(0x08,0x09) && nrs == rt && nrt == rt && 0x10000 + simm == 0x9280) hits += p
            }
            p += 4
        }
        appendLine("materialization count=${hits.size}")
        hits.forEachIndexed { i, off ->
            appendLine("  #${i+1} file=${hex(off)} va=${hex(off-0x80)}")
            dump(data,maxOf(0,off-0x50),minOf(data.size-4,off+0x70),setOf(off,off+4))
        }
    }

    private data class Target(val name: String, val va: Int, val file: Int)

    private fun inferFunctionStart(data: ByteArray, site: Int): Int? {
        var p = site and -4
        val min = maxOf(0, p - 0x1000)
        while (p >= min) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val rs = (w ushr 21) and 31
            val rt = (w ushr 16) and 31
            val simm = (w and 0xffff).toShort().toInt()
            if (op == 0x09 && rs == 29 && rt == 29 && simm < 0) return p
            p -= 4
        }
        return null
    }

    private fun inferFunctionExtent(data: ByteArray, start: Int): Int {
        var p = start + 4
        val limit = minOf(data.size - 4, start + 0x1000)
        var furthestBranch = start
        var returns = 0
        while (p <= limit) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val rs = (w ushr 21) and 31
            val fn = w and 63
            if (op in setOf(0x01,0x04,0x05,0x06,0x07,0x14,0x15,0x16,0x17)) {
                val simm = (w and 0xffff).toShort().toInt()
                val target = p + 4 + (simm shl 2)
                if (target > furthestBranch) furthestBranch = target
            }
            if (op == 0 && fn == 0x08 && rs == 31) {
                returns++
                if (returns >= 1 && p >= furthestBranch && p + 8 < data.size) return p + 4
            }
            p += 4
        }
        return limit
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

    private fun StringBuilder.scanAddressMaterialization(data: ByteArray, start: Int, end: Int) {
        var p = start and -4
        while (p <= end && p + 7 < data.size) {
            val w = u32(data, p)
            val op = (w ushr 26) and 0x3f
            val rt = (w ushr 16) and 31
            if (op == 0x0f) {
                val hi = w and 0xffff
                val n = u32(data, p + 4)
                val nop = (n ushr 26) and 0x3f
                val nrs = (n ushr 21) and 31
                val nrt = (n ushr 16) and 31
                if (nrs == rt && nrt == rt && nop in setOf(0x08,0x09,0x0d)) {
                    val lo = n and 0xffff
                    val value = when (nop) {
                        0x0d -> (hi shl 16) or lo
                        else -> (hi shl 16) + lo.toShort().toInt()
                    }
                    appendLine("  ${hex(p)}..${hex(p + 4)} ${regs[rt]} <= ${hex(value)}")
                    if (value in data.indices) {
                        printableStringAt(data, value)?.let { appendLine("      possible string/data: '$it'") }
                    }
                }
            }
            p += 4
        }
    }

    private fun StringBuilder.probeStaticVa(data: ByteArray, label: String, va: Int) {
        val file = va + 0x80
        appendLine("$label va=${hex(va)} file=${hex(file)}")
        if (file !in data.indices) {
            appendLine("  outside BOOT")
            return
        }
        val end = minOf(data.size, file + 0x80)
        var p = file
        while (p < end) {
            val rowEnd = minOf(end, p + 16)
            val bytes = (p until rowEnd).joinToString(" ") {
                (data[it].toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
            }.padEnd(47, ' ')
            val ascii = buildString {
                for (i in p until rowEnd) {
                    val c = data[i].toInt() and 0xff
                    append(if (c in 0x20..0x7e) c.toChar() else '.')
                }
            }
            appendLine("  ${hex(p)}  $bytes  $ascii")
            p += 16
        }
        printableStringAt(data, file)?.let { appendLine("  direct ASCII: '$it'") }
        appendLine("  BOOT VA pointer candidates:")
        var any = false
        p = file
        val pointerEnd = minOf(end, file + 0x60)
        while (p + 4 <= pointerEnd) {
            val targetVa = u32(data, p)
            val targetFile = targetVa + 0x80
            if (targetVa in 0x1000..0x03ffffff && targetFile in data.indices) {
                any = true
                val text = printableStringAt(data, targetFile)?.let { " ASCII='$it'" } ?: ""
                appendLine("    +${hex(p - file)} => va=${hex(targetVa)} file=${hex(targetFile)}$text")
            }
            p += 4
        }
        if (!any) appendLine("    none in first 0x60 bytes")
    }

    private fun printableStringAt(data: ByteArray, offset: Int): String? {
        if (offset !in data.indices) return null
        val out = StringBuilder()
        var p = offset
        while (p < data.size && out.length < 96) {
            val b = data[p].toInt() and 0xff
            if (b == 0) break
            if (b !in 0x20..0x7e) return null
            out.append(b.toChar())
            p++
        }
        return out.toString().takeIf { it.length >= 4 }
    }

    private fun StringBuilder.dump(data: ByteArray, startRaw: Int, endRaw: Int, marks: Set<Int>) {
        var p = startRaw and -4
        val end = minOf(endRaw and -4, data.size - 4)
        while (p <= end && p + 3 < data.size) {
            val w = u32(data, p)
            val m = if (p in marks) "  <TARGET>" else ""
            appendLine("    ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w,p)}$m")
            p += 4
        }
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
        fun bt(): String { val t = fileOff + 4 + (simm shl 2); return "file=${hex(t)} va=${hex(t-0x80)}" }
        return when (op) {
            0x00 -> when(fn) {
                0x00 -> if (w == 0) "nop" else "sll ${r(rd)}, ${r(rt)}, $sa"
                0x02 -> "srl ${r(rd)}, ${r(rt)}, $sa"
                0x03 -> "sra ${r(rd)}, ${r(rt)}, $sa"
                0x08 -> "jr ${r(rs)}"
                0x09 -> "jalr ${r(rd)}, ${r(rs)}"
                0x10 -> "mfhi ${r(rd)}"
                0x12 -> "mflo ${r(rd)}"
                0x18 -> "mult ${r(rs)}, ${r(rt)}"
                0x19 -> "multu ${r(rs)}, ${r(rt)}"
                0x1a -> "div ${r(rs)}, ${r(rt)}"
                0x1b -> "divu ${r(rs)}, ${r(rt)}"
                0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x24 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x26 -> "xor ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x27 -> "nor ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2a -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
            0x01 -> when(rt) {
                0x00 -> "bltz ${r(rs)}, ${bt()}"
                0x01 -> "bgez ${r(rs)}, ${bt()}"
                0x02 -> "bltzl ${r(rs)}, ${bt()}"
                0x03 -> "bgezl ${r(rs)}, ${bt()}"
                else -> "REGIMM rt=0x${rt.toString(16)} ${bt()}"
            }
            0x02,0x03 -> { val cv=fileOff-0x80; val tv=((cv+4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2); (if(op==0x03) "jal" else "j")+" va=${hex(tv)} file≈${hex(tv+0x80)}" }
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

    private fun hex(v: Int) = "0x${v.toUInt().toString(16).uppercase()}"
    private fun u32(data: ByteArray, o: Int): Int =
        (data[o].toInt() and 0xff) or ((data[o+1].toInt() and 0xff) shl 8) or
            ((data[o+2].toInt() and 0xff) shl 16) or ((data[o+3].toInt() and 0xff) shl 24)
}
