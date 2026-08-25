package kr.co.zillocr.patcher.patch

/** PoC 2.8 read-only trace: follow the resolved glyph owner into its creation/population path. */
object GlyphRuntimeBaseTrace {
    private val regs = arrayOf(
        "zero","at","v0","v1","a0","a1","a2","a3","t0","t1","t2","t3","t4","t5","t6","t7",
        "s0","s1","s2","s3","s4","s5","s6","s7","t8","t9","k0","k1","gp","sp","fp","ra"
    )

    fun report(boot: ByteArray): String = buildString {
        appendLine("runtime glyph owner/population trace v4")
        appendLine("2.7 result: the 0x20-byte lookup node is the glyph descriptor itself. +0x00 is the parser key, +0x10/+0x14 are BST child indices, and +0x18 selects a renderer resource. The consumer also reads +2/+3/+4/+6/+8/+A/+C/+E as geometry/advance fields.")
        appendLine("Important structural result: resolver 0x1F9CF4 returns the owner object consumed by selector 0x1FA028. owner+0x10 points to an identity/root structure (rootIndex at +0x0C), owner+0x14 is the 0x20-byte glyph-node base, and owner+0x08 links the global owner list.")
        appendLine("This pass follows the resolver's create-if-missing path around 0x1FA7xx..0x1FAC7C. We want the exact stores to owner+0x10/+0x14 and the loop/function that fills each 0x20-byte descriptor.")
        appendLine()

        appendLine("=== confirmed selector/owner contract ===")
        dump(boot, 0x1F9CF4, 0x1F9D58, setOf(0x1F9D10,0x1F9D14,0x1F9D20,0x1F9D28,0x1F9D38,0x1F9D40))
        dump(boot, 0x1FA028, 0x1FA054, setOf(0x1FA02C,0x1FA030,0x1FA038,0x1FA040,0x1FA044,0x1FA048))

        appendLine()
        appendLine("=== glyph descriptor field semantics from renderer ===")
        appendLine("+0x00 key; +0x02/+0x03 byte geometry; +0x04/+0x06 unsigned geometry; +0x08/+0x0A signed geometry; +0x0C/+0x0E signed advances; +0x10/+0x14 child indices; +0x18 page/resource index")
        dump(boot, 0x1F9B14, 0x1F9BB0, setOf(0x1F9B20,0x1F9B28,0x1F9B30,0x1F9B3C,0x1F9B74,0x1F9B7C,0x1F9B88,0x1F9B94,0x1F9BA4,0x1F9BAC))

        appendLine()
        appendLine("=== REAL owner create/populate path: resolver caller #6 ===")
        appendLine("0x1FA848 calls resolver with a1=(input object + 0x10). If resolver misses, the following continuation is the strongest owner creation candidate.")
        dump(boot, 0x1FA780, 0x1FAC7C, setOf(0x1FA848,0x1FAB0C))

        appendLine()
        appendLine("=== stores/loads with +0x08/+0x10/+0x14 in owner-create region ===")
        scanOwnerOffsets(boot, 0x1FA780, 0x1FAC7C)

        appendLine()
        appendLine("=== calls made by owner-create region ===")
        listJals(boot, 0x1FA780, 0x1FAC7C)

        appendLine()
        appendLine("=== owner-list remove/release wrapper ===")
        dump(boot, 0x1FAC84, 0x1FAD90, setOf(0x1FAC8C))

        appendLine()
        appendLine("=== helper after renderer-state initialization: file 0x1F9D5C ===")
        appendLine("This helper is called immediately after resolver setup in the renderer-state init path and may expose owner/root bookkeeping.")
        dump(boot, 0x1F9D5C, 0x1F9F80, setOf(0x1F9D5C))

        appendLine()
        appendLine("Decision rule: do not infer atlas ordinal from descriptor fields yet. First identify the code that writes owner+0x14 and fills node+0x00/+0x04/+0x06/+0x18. Once that construction formula is visible, validate its computed slots against ASCII physical anchors 0->17, A->33, a->64, then kana, then surrogate kanji. No writes are enabled.")
    }.trimEnd()

    private fun StringBuilder.scanOwnerOffsets(data: ByteArray, start: Int, end: Int) {
        var p = start and -4
        while (p <= end && p + 3 < data.size) {
            val w = u32(data,p)
            val op = (w ushr 26) and 0x3f
            val imm = w and 0xffff
            if (op in setOf(0x23,0x24,0x25,0x28,0x29,0x2b) && imm in setOf(0x0008,0x0010,0x0014,0x0018)) {
                appendLine("  ${hex(p)}  ${w.toUInt().toString(16).uppercase().padStart(8,'0')}  ${decode(w,p)}")
            }
            p += 4
        }
    }

    private fun StringBuilder.listJals(data: ByteArray, start: Int, end: Int) {
        var p = start and -4
        var n = 0
        while (p <= end && p + 3 < data.size) {
            val w = u32(data,p)
            if (((w ushr 26) and 0x3f) == 0x03) {
                appendLine("  ${hex(p)}  ${decode(w,p)}")
                n++
            }
            p += 4
        }
        appendLine("  direct jal count=$n")
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
