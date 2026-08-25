package kr.co.zillocr.patcher.patch

/** PoC 3.0 read-only trace: identify the loader/resource that supplies glyph metadata. */
object GlyphArchiveOriginTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("glyph metadata container-writer trace v3")
        appendLine("3.1 decisive result: helper B bounds-checks index against container+0x14, then returns *(container+0x00) + *(u32*)(*(container+0x04) + index*4). Therefore index=2 is the third offset-table sub-block in the container built from s0+0x384.")
        appendLine("helper C independently returns *(container+0x08) + index*0x20, confirming a parallel 0x20-byte entry-descriptor table.")
        appendLine("goal: recover the container header parser and every direct BOOT access that writes or reads owner+0x380/+0x384. No writes are enabled.")
        appendLine()

        appendLine("=== raw container parser core va=0x1DDBA0 / file=0x1DDC20 ===")
        appendLine("Fixed range ends immediately before wrapper helper A at file 0x1DDE1C.")
        dump(boot, 0x1DDC20, 0x1DDE18, setOf(0x1DDC20))
        appendLine()

        appendLine("=== owner initializer va=0x145784 / file=0x145804 ===")
        appendLine("This runs on the state-0 path before setup 0x145858 runs on the state-1 path.")
        dump(boot, 0x145804, 0x1458D4, setOf(0x145804))
        appendLine()

        appendLine("=== all direct owner field accesses at +0x380/+0x384 ===")
        scanOwnerResourceFields(boot)
        appendLine()

        appendLine("=== global font-owner address materialization (VA 0x9280) ===")
        scanGlobalOwnerMaterialization(boot)
        appendLine()

        appendLine("=== callers of owner initializer va=0x145784 ===")
        appendLine(findJalCallers(boot, 0x145784).joinToString(" ") { "file=${hex(it)}" })
        appendLine("=== callers of resource-load kickoff va=0x1CFE60 ===")
        appendLine(findJalCallers(boot, 0x1CFE60).joinToString(" ") { "file=${hex(it)}" })
        appendLine()

        appendLine("Decision rule: identify the exact store into owner+0x384 and its source pointer/resource ID. Then parse that source container directly and extract sub-block index 2 for glyph descriptor validation against 0/A/a.")
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
