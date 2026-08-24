package kr.co.zillocr.patcher.patch

/** PoC 2.7 read-only trace: follow the selector result into the glyph descriptor consumer. */
object GlyphRuntimeBaseTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("runtime glyph descriptor trace v3")
        appendLine("2.6 decisive result: selector file 0x1FA028 has exactly two real callers. In the main path, selectorArg=a1=s4 is returned by file 0x1F9CF4, while a0=s5 is returned by file 0x1F9CA0. The selector result is immediately treated as a glyph descriptor: lbu +0x18, lh +0x0C, lh +0x0E.")
        appendLine("This pass stops chasing generic +0x14 stores and follows that returned descriptor into file 0x1F9B14, where page/UV/geometry fields should reveal the physical atlas mapping.")
        appendLine()

        appendLine("=== selector result consumption at caller #1 ===")
        dump(boot, 0x1FA15C, 0x1FA1F4, setOf(0x1FA170,0x1FA180,0x1FA1A8,0x1FA1B0,0x1FA1C0,0x1FA1D8,0x1FA1DC))

        appendLine()
        appendLine("=== glyph descriptor consumer FULL: file 0x1F9B14 ===")
        appendLine("call contract from 0x1FA1A8: a0=renderer state, a1=page/resource selected by descriptor[+0x18], a2=descriptor pointer")
        dump(boot, 0x1F9B14, 0x1F9C9C, setOf(0x1F9B14))

        appendLine()
        appendLine("=== direct callers of glyph descriptor consumer va=0x1F9A94 ===")
        val consumerCallers = findJalCallers(boot, 0x1F9A94)
        appendLine("caller count=${consumerCallers.size}")
        consumerCallers.take(16).forEachIndexed { i, off ->
            appendLine("  #${i+1} call file=${hex(off)} va=${hex(off-0x80)}")
            dump(boot, maxOf(0,off-0x34), minOf(boot.size-4,off+0x34), setOf(off))
        }

        appendLine()
        appendLine("=== selectorArg resolver FULL: file 0x1F9CF4 ===")
        appendLine("2.6 path: 0x1FA098 calls this with a0=global/singleton from 0x1F9CA0 and a1=renderer state; returned v0 becomes s4 and is passed as selector a1.")
        dump(boot, 0x1F9CF4, 0x1F9D58, setOf(0x1F9CF4))

        appendLine()
        appendLine("=== singleton/base provider: file 0x1F9CA0 ===")
        dump(boot, 0x1F9CA0, 0x1F9CF0, setOf(0x1F9CA0))

        appendLine()
        appendLine("=== direct callers of selectorArg resolver va=0x1F9C74 ===")
        val resolverCallers = findJalCallers(boot, 0x1F9C74)
        appendLine("caller count=${resolverCallers.size}")
        resolverCallers.take(24).forEachIndexed { i, off ->
            appendLine("  #${i+1} call file=${hex(off)} va=${hex(off-0x80)}")
            dump(boot, maxOf(0,off-0x40), minOf(boot.size-4,off+0x30), setOf(off))
        }

        appendLine()
        appendLine("=== keyed selector/lookup compact cross-check ===")
        dump(boot, 0x1FA028, 0x1FA054, setOf(0x1FA02C,0x1FA030,0x1FA038,0x1FA044,0x1FA048))
        dump(boot, 0x1F9F8C, 0x1FA024, setOf(0x1F9F9C,0x1F9FC4,0x1F9FD4,0x1F9FDC,0x1FA00C))

        appendLine()
        appendLine("Decision rule: if file 0x1F9B14 reads descriptor fields that directly determine texture page and U/V coordinates, derive slot=(page*1088)+(y/16*34)+(x/15) only after confirming coordinate units. Then validate against physical anchors 0->17, A->33, a->64 before touching kana/kanji. No writes are enabled.")
    }.trimEnd()

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
                0x1a -> "div ${r(rs)}, ${r(rt)}"
                0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x24 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x27 -> "nor ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2a -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
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
            0x31 -> "lwc1 f$rt, $simm(${r(rs)})"
            0x39 -> "swc1 f$rt, $simm(${r(rs)})"
            else -> "op=0x${op.toString(16).uppercase()} rs=${r(rs)} rt=${r(rt)} imm=0x${imm.toString(16).uppercase()}"
        }
    }

    private fun hex(v:Int)="0x${v.toUInt().toString(16).uppercase()}"
    private fun u32(data:ByteArray,o:Int):Int=(data[o].toInt() and 0xff) or ((data[o+1].toInt() and 0xff) shl 8) or ((data[o+2].toInt() and 0xff) shl 16) or ((data[o+3].toInt() and 0xff) shl 24)
}
