package kr.co.zillocr.patcher.patch

/**
 * Read-only static trace for the runtime glyph-node array used by 0x1F9F8C.
 * The lookup receives nodeBase from object+0x14 and rootIndex from *(object+0x10)+0x0C.
 */
object GlyphRuntimeBaseTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("runtime glyph-node base origin trace v1")
        appendLine("known lookup contract: nodeBase = *(selectorArg+0x14); rootIndex = *(*(selectorArg+0x10)+0x0C); node = nodeBase + rootIndex*0x20")
        appendLine("2.4 correction: executable-wide 32-byte key-window scoring produced false positives (ASCII tables and Shift-JIS strings), so this pass follows only code that reads/writes object+0x10/+0x14 and 0x20-stride node pointers.")
        appendLine()

        appendLine("=== selector/lookup contract ===")
        dump(boot, 0x1FA028, 0x1FA054, setOf(0x1FA02C,0x1FA030,0x1FA038,0x1FA040,0x1FA048))
        appendLine()
        appendLine("=== nearby font/text subsystem: +0x10/+0x14 field accesses ===")
        val accesses = mutableListOf<Int>()
        var p = 0x1F8000
        val end = minOf(0x1FA800, boot.size - 4)
        while (p <= end) {
            val w = u32(boot,p)
            val op = (w ushr 26) and 0x3f
            val imm = (w and 0xffff).toShort().toInt()
            if ((op == 0x23 || op == 0x2b) && (imm == 0x10 || imm == 0x14)) accesses += p
            p += 4
        }
        appendLine("access count=${accesses.size}")
        accesses.forEachIndexed { i, off ->
            appendLine("  #${i+1} ${hex(off)} ${decode(u32(boot,off),off)}")
            dump(boot, maxOf(0,off-0x18), minOf(boot.size-4,off+0x20), setOf(off))
        }

        appendLine()
        appendLine("=== nearby 0x20-stride arithmetic / candidate node-base construction ===")
        val strideHits = mutableListOf<Int>()
        p = 0x1F8000
        while (p <= end) {
            val w = u32(boot,p)
            val op = (w ushr 26) and 0x3f
            val fn = w and 0x3f
            val sa = (w ushr 6) and 31
            if (op == 0 && fn == 0 && sa == 5) strideHits += p
            p += 4
        }
        appendLine("sll-by-5 count=${strideHits.size}")
        strideHits.forEachIndexed { i, off ->
            appendLine("  #${i+1} ${hex(off)} ${decode(u32(boot,off),off)}")
            dump(boot,maxOf(0,off-0x18),minOf(boot.size-4,off+0x20),setOf(off))
        }

        appendLine()
        appendLine("=== writes to +0x14 outside local subsystem (possible loader/constructor) ===")
        val writers = mutableListOf<Int>()
        p = 0
        while (p + 3 < boot.size) {
            val w = u32(boot,p)
            val op = (w ushr 26) and 0x3f
            val imm = (w and 0xffff).toShort().toInt()
            if (op == 0x2b && imm == 0x14) writers += p
            p += 4
        }
        appendLine("global sw ?,0x14(?) count=${writers.size}; showing nearest text/font-code candidates first")
        writers.sortedBy { distanceTo(it,0x1F9F00) }.take(48).forEachIndexed { i, off ->
            appendLine("  #${i+1} ${hex(off)} ${decode(u32(boot,off),off)}")
            dump(boot,maxOf(0,off-0x10),minOf(boot.size-4,off+0x18),setOf(off))
        }

        appendLine()
        appendLine("Interpretation: the decisive hit is a constructor/loader path that stores an allocated/decompressed/static pointer into object+0x14, ideally beside a count/root structure in object+0x10. Once found, the actual runtime node array can be reconstructed or its source data located without scanning unrelated literal strings. No writes are enabled.")
    }.trimEnd()

    private fun distanceTo(a:Int,b:Int):Int = if (a>=b) a-b else b-a

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
                0x08 -> "jr ${r(rs)}"
                0x18 -> "mult ${r(rs)}, ${r(rt)}"
                0x21 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x23 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x25 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                0x2b -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16)}"
            }
            0x03 -> { val cv=fileOff-0x80; val tv=((cv+4) and 0xF0000000.toInt()) or ((w and 0x03ffffff) shl 2); "jal va=${hex(tv)} file≈${hex(tv+0x80)}" }
            0x04 -> "beq ${r(rs)}, ${r(rt)}, ${bt()}"
            0x05 -> "bne ${r(rs)}, ${r(rt)}, ${bt()}"
            0x09 -> "addiu ${r(rt)}, ${r(rs)}, $simm"
            0x0c -> "andi ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            0x0d -> "ori ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
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
