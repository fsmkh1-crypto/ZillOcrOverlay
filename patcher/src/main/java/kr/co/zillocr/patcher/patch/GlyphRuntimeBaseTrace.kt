package kr.co.zillocr.patcher.patch

/** Read-only trace focused on the actual selector callers and the object that owns nodeBase/rootIndex. */
object GlyphRuntimeBaseTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("runtime glyph-node base origin trace v2")
        appendLine("2.5 result: broad +0x10/+0x14 access scanning is too noisy. The useful facts are the selector contract and the allocator-result stores around 0x1F93xx/0x1F97xx.")
        appendLine("This pass traces the selector's real direct callers, then dumps the candidate allocation/population functions that write pointers to +0x14.")
        appendLine()

        appendLine("=== keyed selector contract ===")
        dump(boot, 0x1FA028, 0x1FA054, setOf(0x1FA02C,0x1FA030,0x1FA038,0x1FA040,0x1FA044,0x1FA048))

        appendLine()
        appendLine("=== DIRECT callers of selector entry file=0x1FA028 / va=0x1F9FA8 ===")
        val selectorCallers = findJalCallers(boot, 0x1F9FA8)
        appendLine("caller count=${selectorCallers.size}")
        selectorCallers.take(32).forEachIndexed { i, off ->
            appendLine("  #${i+1} call file=${hex(off)} va=${hex(off-0x80)}")
            dump(boot, maxOf(0,off-0x50), minOf(boot.size-4,off+0x40), setOf(off))
        }

        appendLine()
        appendLine("=== selector-neighbor full path ===")
        appendLine("focus: function beginning at 0x1FA058 should reveal where selectorArg comes from and how returned record fields are consumed")
        dump(boot, 0x1FA058, 0x1FA2B0, setOf(0x1FA058))

        appendLine()
        appendLine("=== candidate +0x14 allocation/population path A ===")
        appendLine("notable stores: 0x1F9388 / 0x1F93B0 save allocator results to object+0x14")
        dump(boot, 0x1F925C, 0x1F93D4, setOf(0x1F9388,0x1F93B0))

        appendLine()
        appendLine("=== candidate +0x14 allocation/population path B ===")
        appendLine("notable stores: 0x1F9718 / 0x1F976C save allocator results to object+0x14 and immediately copy data into them")
        dump(boot, 0x1F94DC, 0x1F98BC, setOf(0x1F9718,0x1F9724,0x1F976C,0x1F977C))

        appendLine()
        appendLine("=== callers of candidate path A/B function entries ===")
        val candidateEntries = listOf(0x1F91DC, 0x1F945C) // file offsets - 0x80
        candidateEntries.forEach { va ->
            val callers = findJalCallers(boot, va)
            appendLine("target va=${hex(va)} file≈${hex(va+0x80)} callers=${callers.size}")
            callers.take(16).forEach { off ->
                appendLine("  call file=${hex(off)} va=${hex(off-0x80)}")
                dump(boot,maxOf(0,off-0x30),minOf(boot.size-4,off+0x28),setOf(off))
            }
        }

        appendLine()
        appendLine("Decision rule: the right owner object must flow into file 0x1FA028 as a1, with +0x10 pointing to a structure whose +0x0C is a valid node index and +0x14 pointing to 0x20-byte keyed nodes. A candidate that only allocates textures/buffers but never reaches that selector is rejected. No writes are enabled.")
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
                0x12 -> "mflo ${r(rd)}"
                0x18 -> "mult ${r(rs)}, ${r(rt)}"
                0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
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
