package kr.co.zillocr.patcher.patch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

/** Exact on-device font container probe using upstream PAR/GIM semantics. */
object ExactGimIsoAnalyzer {
    private const val FONT_MEMBER_INDEX = 13611
    private const val EXPECTED_FONT_SIZE = 525_424
    private const val EXPECTED_FONT_NAME = "font/zillfont.par"
    private const val EXPECTED_FONT_SHA256 = "0d3d6d2648870e87a01636cdfc7cc7af8100ea40b71e5ed05f82ac197606584a"
    private const val EXPECTED_ENGLISH_SHA256 = "0f11ca53076e072408fb3eb9ffa29446b02fb97642f4173b559691c463a2fdb8"

    data class Result(
        val previews: List<GimFontProbe.Preview>,
        val report: String,
    )

    fun analyze(channel: SeekableByteChannel, xorPatch: ByteArray): Result {
        val iso = Iso9660Reader(channel)
        val paBin = iso.find("PSP_GAME/USRDIR/pa.bin")
        val paArc = iso.find("PSP_GAME/USRDIR/pa.arc")
        val index = iso.readEntry(paBin)
        val member = parseMember(index)
        require(member.name == EXPECTED_FONT_NAME) { "unexpected PAA member: ${member.name}" }
        require(member.size == EXPECTED_FONT_SIZE) { "unexpected zillfont.par size ${member.size}" }
        require(member.offset + member.size <= paArc.size) { "zillfont.par extends outside pa.arc" }

        val retail = iso.readEntryRange(paArc, member.offset, member.size)
        require(sha256(retail) == EXPECTED_FONT_SHA256) { "retail zillfont.par SHA-256 mismatch" }
        require(xorPatch.size == retail.size) { "font XOR size mismatch" }
        val english = ByteArray(retail.size) { i -> (retail[i].toInt() xor xorPatch[i].toInt()).toByte() }
        require(sha256(english) == EXPECTED_ENGLISH_SHA256) { "English zillfont.par SHA-256 mismatch" }

        val sections = parseParSections(retail, xorPatch)
        val previews = GimFontProbe.build(retail, english, sections)
        val report = buildString {
            appendLine("exact upstream-compatible PAR/GIM probe")
            appendLine("PAR children: ${sections.size}")
            sections.forEach { section ->
                appendLine("  ${section.index}: ${section.first32Hex.take(47)}  offset=0x${section.start.toString(16).uppercase()} size=${section.size}")
            }
            appendLine("GIM children parsed: ${previews.size}")
            previews.forEach { p ->
                val orderName = if (p.image.order == 1) "PSP-swizzled" else "linear"
                appendLine(
                    "  child ${p.sectionIndex}: ${p.image.width}x${p.image.height}, ${p.image.bits}bpp, " +
                        "order=${p.image.order}($orderName), pitch=${p.image.pitch}, storageHeight=${p.image.storageHeight}, " +
                        "palette=${p.palette.width}x${p.palette.height}, changedLogicalPixels=${p.changedLogicalPixels}"
                )
            }
            if (previews.isEmpty()) {
                appendLine("  no child matched upstream little-endian PSP GIM constraints")
            }
        }.trimEnd()
        return Result(previews, report)
    }

    private fun parseParSections(font: ByteArray, xorPatch: ByteArray): List<ZillFontIsoAnalyzer.ParSection> {
        require(font.size >= 32 && font[0] == 'P'.code.toByte() && font[1] == 'A'.code.toByte() && font[2] == 'R'.code.toByte() && font[3].toInt() == 0) {
            "zillfont.par is not a PAR container"
        }
        val count = u32(font, 8)
        require(count in 1..32) { "invalid PAR child count $count" }
        val namesBase = align16(16 + count * 4)
        require(namesBase + count * 32 <= font.size) { "truncated PAR name table" }
        val starts = (0 until count).map { u32(font, 16 + it * 4) }
        val minimumDataStart = namesBase + count * 32
        require(starts.first() >= minimumDataStart) { "first PAR child overlaps header/name table" }
        require(starts.zipWithNext().all { (a, b) -> b > a && b <= font.size }) { "invalid PAR child offsets" }

        return starts.mapIndexed { i, start ->
            val end = if (i + 1 < starts.size) starts[i + 1] else font.size
            val localPatch = xorPatch.copyOfRange(start, end)
            val runs = nonZeroRuns(localPatch)
            ZillFontIsoAnalyzer.ParSection(
                index = i,
                start = start,
                endExclusive = end,
                first32Hex = hex(font, start, minOf(32, end - start)),
                changedBytes = localPatch.count { it.toInt() != 0 },
                changedRuns = runs.size,
                longestRun = runs.maxOrNull() ?: 0,
            )
        }
    }

    private fun nonZeroRuns(data: ByteArray): List<Int> {
        val result = ArrayList<Int>()
        var start = -1
        for (i in data.indices) {
            val changed = data[i].toInt() != 0
            if (changed && start < 0) start = i
            if (!changed && start >= 0) {
                result += i - start
                start = -1
            }
        }
        if (start >= 0) result += data.size - start
        return result
    }

    private data class Member(val name: String, val offset: Long, val size: Int)

    private fun parseMember(index: ByteArray): Member {
        require(index.size >= 0x20 && index[0] == 'P'.code.toByte() && index[1] == 'A'.code.toByte() && index[2] == 'A'.code.toByte() && index[3].toInt() == 0) {
            "pa.bin is not a PAA index"
        }
        val count = u32(index, 8)
        val offsetTable = u32(index, 16)
        require(FONT_MEMBER_INDEX in 0 until count) { "PAA font member is out of range" }
        val record = 0x20 + FONT_MEMBER_INDEX * 0x10
        require(record + 0x10 <= index.size) { "PAA font record outside pa.bin" }
        val nameOffset = u32(index, record)
        val size = u32(index, record + 4)
        val offsetPos = offsetTable + FONT_MEMBER_INDEX * 4
        require(offsetPos + 4 <= index.size) { "PAA offset table outside pa.bin" }
        val offset = u32(index, offsetPos).toLong() and 0xffffffffL
        require(nameOffset in index.indices) { "PAA name offset outside pa.bin" }
        var end = nameOffset
        while (end < index.size && index[end].toInt() != 0) end++
        require(end < index.size) { "PAA member name is not NUL-terminated" }
        return Member(index.copyOfRange(nameOffset, end).toString(Charsets.US_ASCII), offset, size)
    }

    private fun hex(data: ByteArray, offset: Int, length: Int): String =
        data.copyOfRange(offset, offset + length).joinToString(" ") { "%02X".format(it.toInt() and 0xff) }

    private fun align16(value: Int): Int = (value + 15) and 15.inv()

    private fun u32(data: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(data, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

    private fun sha256(data: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
