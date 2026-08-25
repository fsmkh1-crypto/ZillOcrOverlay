package kr.co.zillocr.patcher.patch

/** PoC 2.9 read-only trace: follow the glyph-metadata parser back to its resource origin. */
object GlyphResourceOriginTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("glyph descriptor resource-origin trace v5")
        appendLine("2.8 decisive result: owner+0x14 is not a newly allocated glyph table. It is a pointer into the input metadata block parsed by file 0x1FA800. The parser walks count=lw(input+0x08) records with stride 0x20 and endian-fixes node fields in place.")
        appendLine("Node conversion pattern: +0/+4/+6/+8/+A/+C/+E use the 16-bit conversion helper; +0x10/+0x14 use the 32-bit conversion helper; +2/+3/+18 remain byte fields. This exactly matches the recovered glyph descriptor layout.")
        appendLine("Node base is input+0x20 when u16(input+4) is >= 0x201, otherwise input+u16(input+4). owner+0x10=input, owner+0x14=nodeBase.")
        appendLine("This pass traces every direct caller of parser entry file 0x1FA800 / va 0x1FA780, then one call level above each caller. The goal is to identify which loaded archive/resource supplies the metadata block so we can parse character->atlas mapping directly from the ISO.")
        appendLine()

        appendLine("=== parser contract / node-base construction ===")
        dump(boot, 0x1FA800, 0x1FA980, setOf(0x1FA800,0x1FA848,0x1FA890,0x1FA908,0x1FA920,0x1FA934,0x1FA948,0x1FA968))
        appendLine()
        appendLine("=== descriptor endian-fix loop and owner stores ===")
        dump(boot, 0x1FA980, 0x1FAAA0, setOf(0x1FA9AC,0x1FA9E4,0x1FA9EC,0x1FAA0C,0x1FAA94,0x1FAA98))

        val callers = findJalCallers(boot, 0x1FA780)
        appendLine()
        appendLine("=== DIRECT callers of glyph-metadata parser va=0x1FA780 ===")
        appendLine("caller count=${callers.size}")
        callers.take(32).forEachIndexed { i, off ->
            appendLine("  #${i+1} call file=${hex(off)} va=${hex(off-0x80)}")
            val start = maxOf(0, off - 0x90)
            val end = minOf(boot.size - 4, off + 0x90)
            dump(boot, start, end, setOf(off))

            val funcStart = inferFunctionStart(boot, off)
            if (funcStart != null) {
                val va = funcStart - 0x80
                val parents = findJalCallers(boot, va)
                appendLine("    inferred caller function start=${hex(funcStart)} va=${hex(va)} parentCalls=${parents.size}")
                parents.take(16).forEachIndexed { j, p ->
                    appendLine("      parent #${j+1} call file=${hex(p)} va=${hex(p-0x80)}")
                    dump(boot, maxOf(0,p-0x50), minOf(boot.size-4,p+0x50), setOf(p))
                }
            }
        }

        appendLine()
        appendLine("=== all references to parser-near helper va=0x1FA780 and owner insertion region ===")
        appendLine("Look for a caller that receives a pointer/size from an archive loader immediately before calling the parser. A filename, member index, or resource-type constant near that caller is the next static anchor.")
        appendLine("No writes are enabled.")
    }.trimEnd()

    private fun inferFunctionStart(data: ByteArray, site: Int): Int? {
        var p = site and -4
        val min = maxOf(0, p - 0x800)
        while (p >= min) {
            val w = u32(data,p)
            val op = (w ushr 26) and 0x3f
            val rs = (w ushr 21) and 31
            val rt = (w ushr 16) and 31
            val simm = (w and 0xffff).toShort().toInt()
            if (op == 0x09 && rs == 29 && rt == 29 && simm < 0) return p
            p -= 4
        }
        return null
    }

    private fun findJalCallers(data: ByteArray, targetVa: Int): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + 3 < data.size) {
            val w = u32(data,p)
            if (((w ushr 26) and 0x3f) == 0x03) {
                val callerVa = p - 0x80
                val va = ((callerVa + 4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2)
                if (va == targetVa) out += p
            }
            p += 4
        }
        return out
    }

    private fun StringBuilder.dump(data:ByteArray,startRaw:Int,endRaw:Int,marks:Set<Int>) {
        var p=startRaw and -4
        val end=minOf(endRaw and -4,data.size-4)
        while (p<=end && p+3<data.size) {
            val w=u32(data,p)
            val m=if (p in marks) "  <TARGET>" else ""
            appendLine("    ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w,p)}$m")
            p+=4
        }
    }

    private fun decode(w:Int,fileOff:Int):String {
        val op=(w ushr 26) and 0x3f
        val rs=(w ushr 21) and 31
        val rt=(w ushr 16) and 31
        val rd=(w ushr 11) and 31
        val sa=(w ushr 6) and 31
        val fn=w and 63
        val imm=w and 0xffff
        val simm=imm.toShort().toInt()
        fun r(i:Int)=regs[i]
        fun bt():String { val t=fileOff+4+(simm shl 2); return "file=${hex(t)} va=${hex(t-0x80)}" }
        return when(op) {
            0x00 -> when(fn) {
                0x00 -> if(w==0) "nop" else "sll ${r(rd)}, ${r(rt)}, $sa"
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
            0x01 -> "REGIMM rt=${r(rt)} ${bt()}"
            0x02,0x03 -> { val cv=fileOff-0x80; val tv=((cv+4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2); (if(op==0x03) "jal" else "j")+" va=${hex(tv)} file≈${hex(tv+0x80)}" }
            0x04 -> "beq ${r(rs)}, ${r(rt)}, ${bt()}"
            0x05 -> "bne ${r(rs)}, ${r(rt)}, ${bt()}"
            0x06 -> "blez ${r(rs)}, ${bt()}"
            0x07 -> "bgtz ${r(rs)}, ${bt()}"
            0x09 -> "addiu ${r(rt)}, ${r(rs)}, $simm"
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

    private fun hex(v:Int)="0x${v.toUInt().toString(16).uppercase()}"
    private fun u32(data:ByteArray,o:Int):Int=(data[o].toInt() and 0xff) or ((data[o+1].toInt() and 0xff) shl 8) or ((data[o+2].toInt() and 0xff) shl 16) or ((data[o+3].toInt() and 0xff) shl 24)
}
