package kr.co.zillocr.patcher.patch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest

object ZillFontIsoAnalyzer {
    private const val FONT_MEMBER_INDEX = 13611
    private const val EXPECTED_FONT_SIZE = 525_424
    private const val EXPECTED_FONT_SHA256 = "0d3d6d2648870e87a01636cdfc7cc7af8100ea40b71e5ed05f82ac197606584a"
    private const val EXPECTED_ENGLISH_FONT_SHA256 = "0f11ca53076e072408fb3eb9ffa29446b02fb97642f4173b559691c463a2fdb8"
    private const val EXPECTED_FONT_NAME = "font/zillfont.par"

    data class Result(
        val paBinSize: Long,
        val paArcSize: Long,
        val memberIndex: Int,
        val memberName: String,
        val memberOffset: Long,
        val memberSize: Int,
        val sha256: String,
        val matchesRetailFont: Boolean,
        val firstBytesHex: String,
        val englishSha256: String? = null,
        val matchesEnglishFont: Boolean? = null,
        val changedBytes: Int? = null,
        val changedRuns: Int? = null,
        val longestRuns: List<Run> = emptyList(),
    ) {
        fun toReport(): String = buildString {
            appendLine("Zill O'll Infinite Plus Korean patch · font diagnostics")
            appendLine("ISO path: PSP_GAME/USRDIR/pa.bin + pa.arc")
            appendLine("PAA member index: $memberIndex")
            appendLine("PAA member name: $memberName")
            appendLine("PAA member offset: 0x${memberOffset.toString(16).uppercase()}")
            appendLine("PAA member size: $memberSize")
            appendLine("pa.bin size: $paBinSize")
            appendLine("pa.arc size: $paArcSize")
            appendLine("zillfont.par SHA-256: $sha256")
            appendLine("expected SHA-256: $EXPECTED_FONT_SHA256")
            appendLine("retail font match: ${if (matchesRetailFont) "YES" else "NO"}")
            appendLine("first 32 bytes: $firstBytesHex")
            if (englishSha256 != null) {
                appendLine()
                appendLine("upstream English font reconstruction")
                appendLine("English result SHA-256: $englishSha256")
                appendLine("expected result SHA-256: $EXPECTED_ENGLISH_FONT_SHA256")
                appendLine("English font match: ${if (matchesEnglishFont == true) "YES" else "NO"}")
                appendLine("changed bytes: $changedBytes / $memberSize")
                appendLine("non-zero XOR runs: $changedRuns")
                appendLine("longest changed runs:")
                longestRuns.forEach { run ->
                    appendLine("  0x${run.start.toString(16).uppercase()}..0x${run.endExclusive.toString(16).uppercase()} (${run.length} bytes)")
                }
            }
        }
    }

    data class Run(val start: Int, val endExclusive: Int) {
        val length: Int get() = endExclusive - start
    }

    fun analyze(channel: SeekableByteChannel, xorPatch: ByteArray? = null): Result {
        val iso = Iso9660Reader(channel)
        val paBin = iso.find("PSP_GAME/USRDIR/pa.bin")
        val paArc = iso.find("PSP_GAME/USRDIR/pa.arc")
        val index = iso.readEntry(paBin)
        val member = parseMember(index, FONT_MEMBER_INDEX)

        if (member.name != EXPECTED_FONT_NAME) error("unexpected PAA member $FONT_MEMBER_INDEX: ${member.name}")
        if (member.size != EXPECTED_FONT_SIZE) error("unexpected zillfont.par size ${member.size}; expected $EXPECTED_FONT_SIZE")
        if (member.offset + member.size > paArc.size) error("zillfont.par extends outside pa.arc")

        val font = iso.readEntryRange(paArc, member.offset, member.size)
        val sha = sha256(font)
        if (xorPatch != null && xorPatch.size != font.size) {
            error("XOR patch size ${xorPatch.size} does not match retail font ${font.size}")
        }

        val english = xorPatch?.let { patch ->
            ByteArray(font.size) { i -> (font[i].toInt() xor patch[i].toInt()).toByte() }
        }
        val runs = xorPatch?.let(::nonZeroRuns).orEmpty()
        val changed = xorPatch?.count { it.toInt() != 0 }
        val longest = runs.sortedByDescending { it.length }.take(20)
        val englishSha = english?.let(::sha256)

        return Result(
            paBinSize = paBin.size,
            paArcSize = paArc.size,
            memberIndex = FONT_MEMBER_INDEX,
            memberName = member.name,
            memberOffset = member.offset,
            memberSize = member.size,
            sha256 = sha,
            matchesRetailFont = sha == EXPECTED_FONT_SHA256,
            firstBytesHex = font.take(32).joinToString(" ") { "%02X".format(it.toInt() and 0xff) },
            englishSha256 = englishSha,
            matchesEnglishFont = englishSha?.let { it == EXPECTED_ENGLISH_FONT_SHA256 },
            changedBytes = changed,
            changedRuns = xorPatch?.let { runs.size },
            longestRuns = longest,
        )
    }

    private fun nonZeroRuns(data: ByteArray): List<Run> {
        val result = ArrayList<Run>()
        var start = -1
        for (i in data.indices) {
            val changed = data[i].toInt() != 0
            if (changed && start < 0) start = i
            if (!changed && start >= 0) {
                result += Run(start, i)
                start = -1
            }
        }
        if (start >= 0) result += Run(start, data.size)
        return result
    }

    private data class PaaMember(val name: String, val offset: Long, val size: Int)

    private fun parseMember(index: ByteArray, memberIndex: Int): PaaMember {
        if (index.size < 0x20 || !index.copyOfRange(0, 4).contentEquals(byteArrayOf('P'.code.toByte(), 'A'.code.toByte(), 'A'.code.toByte(), 0))) {
            error("pa.bin is not a PAA index")
        }
        val count = u32le(index, 8).toInt()
        val offsetTable = u32le(index, 16).toInt()
        if (memberIndex !in 0 until count) error("PAA member $memberIndex out of range; count=$count")
        val record = 0x20 + memberIndex * 0x10
        if (record + 0x10 > index.size) error("PAA member record outside pa.bin")
        val nameOffset = u32le(index, record).toInt()
        val size = u32le(index, record + 4).toInt()
        val offsetPosition = offsetTable + memberIndex * 4
        if (offsetPosition + 4 > index.size) error("PAA offset table outside pa.bin")
        val offset = u32le(index, offsetPosition)
        if (nameOffset !in index.indices) error("PAA name offset outside pa.bin")
        var end = nameOffset
        while (end < index.size && index[end].toInt() != 0) end++
        if (end == index.size) error("PAA member name is not NUL-terminated")
        val name = index.copyOfRange(nameOffset, end).toString(Charsets.US_ASCII)
        return PaaMember(name, offset, size)
    }

    private fun u32le(data: ByteArray, offset: Int): Long = ByteBuffer
        .wrap(data, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffffffffL

    private fun sha256(data: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
