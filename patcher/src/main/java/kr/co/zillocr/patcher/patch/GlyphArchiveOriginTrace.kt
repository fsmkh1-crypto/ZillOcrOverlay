package kr.co.zillocr.patcher.patch

/** PoC 3.0 read-only trace: identify the loader/resource that supplies glyph metadata. */
object GlyphArchiveOriginTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("glyph metadata archive-origin trace v1")
        appendLine("2.9 result carried forward: file 0x1459BC is the only direct caller of metadata parser 0x1FA800. Its a2 comes from helper 0x1DDDE4; sibling helpers 0x1DDD9C and 0x1DDE1C are used in the same setup path.")
        appendLine("goal: classify those three helpers, enumerate their direct callers, and expose the exact 0x1458D8 setup function around every call so a file/member/resource identifier can be recovered.")
        appendLine("No writes are enabled.")
        appendLine()

        val targets = listOf(
            Target("resource helper A", 0x1DDD9C, 0x1DDE1C),
            Target("resource helper B / parser input producer", 0x1DDDE4, 0x1DDE64),
            Target("resource helper C", 0x1DDE1C, 0x1DDE9C),
            Target("glyph metadata parser", 0x1FA780, 0x1FA800),
            Target("setup function", 0x145858, 0x1458D8),
        )

        for (t in targets) {
            appendLine("=== ${t.name} ===")
            appendLine("entry va=${hex(t.va)} file=${hex(t.file)}")
            val fs = inferFunctionStart(boot, t.file) ?: t.file
            val fe = inferFunctionExtent(boot, fs)
            appendLine("inferred function ${hex(fs)}..${hex(fe)}")
            dump(boot, fs, fe, setOf(t.file))
            val callers = findJalCallers(boot, t.va)
            appendLine("direct callers=${callers.size}")
            callers.take(32).forEachIndexed { i, c ->
                appendLine("  #${i + 1} call file=${hex(c)} va=${hex(c - 0x80)}")
                dump(boot, maxOf(0, c - 0x70), minOf(boot.size - 4, c + 0x70), setOf(c))
            }
            appendLine()
        }

        appendLine("=== focused setup function 0x1458D8 ===")
        appendLine("This is the sole static bridge into the glyph metadata parser. Calls at 0x14592C/0x14593C/0x14598C/0x1459A4 feed values that ultimately reach 0x1459BC.")
        dump(boot, 0x1458D8, 0x145B20, setOf(0x14592C,0x14593C,0x14598C,0x1459A4,0x1459BC))
        appendLine()

        appendLine("=== immediate/string-address candidates near setup ===")
        scanAddressMaterialization(boot, 0x145780, 0x145B80)
        appendLine()

        appendLine("=== parent callers of setup va=0x145858 ===")
        findJalCallers(boot, 0x145858).take(24).forEachIndexed { i, c ->
            appendLine("  #${i + 1} file=${hex(c)} va=${hex(c - 0x80)}")
            dump(boot, maxOf(0, c - 0x90), minOf(boot.size - 4, c + 0x90), setOf(c))
        }
        appendLine()

        appendLine("Decision rule: success requires a stable resource identifier (filename, PAA member/index, resource type, or loader object) feeding helper 0x1DDDE4 and then parser 0x1FA800. Once identified, parse that metadata directly from ISO and validate descriptors for 0/A/a against known atlas anchors before kana/kanji.")
    }.trimEnd()

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
