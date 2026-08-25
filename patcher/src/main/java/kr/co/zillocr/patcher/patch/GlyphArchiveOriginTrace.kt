package kr.co.zillocr.patcher.patch

/** PoC 3.4 read-only probe for the two PAR resources recovered from BOOT. */
object GlyphArchiveOriginTrace {
    private data class Header(
        val base: Int,
        val little: Boolean,
        val type: Long,
        val count: Int,
        val descriptorBase: Int,
        val score: Int,
    )

    fun report(
        zillPath: String,
        zill: ByteArray,
        jillPath: String,
        jill: ByteArray,
    ): String = buildString {
        appendLine("glyph PAR direct container probe v5")
        appendLine("PoC 3.4 · read-only · ISO/BOOT writes disabled")
        appendLine("owner+0x380 => font/zillfont.par (primary text-font candidate)")
        appendLine("owner+0x384 => 2d/font/jillbtn.par (button/icon comparison candidate)")
        appendLine("Recovered parser: type=raw+4, count=raw+8, offsets=raw+0x10, descriptors=align16(offsets+count*4), descriptor stride=0x20.")
        appendLine()

        analyze("TEXT CANDIDATE", zillPath, zill)
        appendLine()
        analyze("BUTTON CANDIDATE", jillPath, jill)
        appendLine()

        appendLine("=== decision rule ===")
        appendLine("Prefer zillfont.par when its sub-block[2] has the denser/repeated 0/A/a descriptor pattern; treat jillbtn.par as control evidence.")
        appendLine("If neither file has a parser-compatible header at base 0, use the best embedded candidate listed below and trace the outer PAR archive entry once more.")
        appendLine("No patch writes were performed.")
    }.trimEnd()

    private fun StringBuilder.analyze(label: String, path: String, data: ByteArray) {
        appendLine("=== $label ===")
        appendLine("ISO path: $path")
        appendLine("file size: ${data.size} (${hex(data.size)})")
        appendLine("first bytes:")
        dump(data, 0, minOf(data.size, 0x80))

        val strings = asciiStrings(data, 32)
        appendLine("ASCII strings (max 32):")
        if (strings.isEmpty()) appendLine("  none")
        else strings.forEach { (offset, text) -> appendLine("  ${hex(offset)}  '$text'") }

        val candidates = findHeaders(data)
        if (candidates.isEmpty()) {
            appendLine("parser-compatible container header: none")
            return
        }

        appendLine("container candidates (best first):")
        candidates.take(8).forEachIndexed { index, h ->
            appendLine(
                "  #${index + 1} base=${hex(h.base)} endian=${if (h.little) "LE" else "BE"} " +
                    "type=${hex(h.type)} count=${h.count} descriptorBase=${hex(h.descriptorBase)} score=${h.score}"
            )
        }

        val h = candidates.first()
        appendLine("selected container: base=${hex(h.base)}, endian=${if (h.little) "LE" else "BE"}")
        appendLine("offset table:")
        val offsets = (0 until h.count).map { index ->
            val relative = readU32(data, h.base + 0x10 + index * 4, h.little)
            val absolute = if (relative <= Int.MAX_VALUE.toLong()) h.base + relative.toInt() else -1
            index to absolute
        }
        val shown = if (offsets.size <= 64) offsets else offsets.take(32) + offsets.takeLast(8)
        shown.forEach { (index, absolute) ->
            val relative = if (absolute >= h.base) absolute - h.base else -1
            appendLine("  [${index.toString().padStart(3)}] rel=${hex(relative)} abs=${hex(absolute)}")
        }
        if (shown.size < offsets.size) appendLine("  ... ${offsets.size - shown.size} entries omitted ...")

        if (h.count <= 2) {
            appendLine("index 2 unavailable: count=${h.count}")
            return
        }

        val descriptor2 = h.descriptorBase + 2 * 0x20
        appendLine("descriptor[2] @ ${hex(descriptor2)}:")
        dump(data, descriptor2, minOf(data.size, descriptor2 + 0x20))

        val subStart = offsets[2].second
        if (subStart !in data.indices) {
            appendLine("sub-block[2] invalid: ${hex(subStart)}")
            return
        }
        val subEnd = offsets.map { it.second }
            .filter { it > subStart && it <= data.size }
            .minOrNull() ?: data.size
        appendLine("sub-block[2]: start=${hex(subStart)} end=${hex(subEnd)} size=${hex(subEnd - subStart)}")
        appendLine("sub-block[2] head (max 0x200):")
        dump(data, subStart, minOf(subEnd, subStart + 0x200))

        appendLine("0/A/a scalar occurrences inside sub-block[2]:")
        listOf('0'.code, 'A'.code, 'a'.code).forEach { value ->
            val byteHits = findScalar(data, subStart, subEnd, value.toLong(), 1, h.little)
            val u16Hits = findScalar(data, subStart, subEnd, value.toLong(), 2, h.little)
            val u32Hits = findScalar(data, subStart, subEnd, value.toLong(), 4, h.little)
            appendLine(
                "  '${value.toChar()}' byte=${formatHits(byteHits)} " +
                    "u16=${formatHits(u16Hits)} u32=${formatHits(u32Hits)}"
            )
        }

        appendLine("fixed-record hypotheses from sub-block[2] (slot=ASCII-0x20):")
        val slots = listOf('0' to ('0'.code - 0x20), 'A' to ('A'.code - 0x20), 'a' to ('a'.code - 0x20))
        listOf(8, 12, 16, 20, 24, 32).forEach { stride ->
            val valid = slots.all { (_, slot) -> subStart + slot * stride + minOf(stride, 16) <= subEnd }
            if (valid) {
                appendLine("  stride=${hex(stride)}")
                slots.forEach { (char, slot) ->
                    val at = subStart + slot * stride
                    appendLine("    '$char' slot=$slot @ ${hex(at)}  ${inlineHex(data, at, minOf(16, stride))}")
                }
            }
        }
    }

    private fun findHeaders(data: ByteArray): List<Header> {
        if (data.size < 0x30) return emptyList()
        val found = mutableListOf<Header>()
        var base = 0
        while (base + 0x30 <= data.size) {
            headerAt(data, base, true)?.let { found += it }
            headerAt(data, base, false)?.let { found += it }
            base += 4
        }
        return found
            .distinctBy { Triple(it.base, it.little, it.count) }
            .sortedWith(compareByDescending<Header> { it.score }.thenBy { it.base })
            .take(16)
    }

    private fun headerAt(data: ByteArray, base: Int, little: Boolean): Header? {
        val remaining = data.size - base
        if (remaining < 0x30) return null
        val type = readU32(data, base + 4, little)
        val countLong = readU32(data, base + 8, little)
        val maxCount = minOf(0x4000, (remaining - 0x10) / 4)
        if (countLong !in 3L..maxCount.toLong()) return null
        val count = countLong.toInt()
        val descriptorBase = base + align16(0x10 + count * 4)
        val descriptorEnd = descriptorBase.toLong() + count.toLong() * 0x20L
        if (descriptorEnd > data.size.toLong()) return null

        val sampleCount = minOf(count, 8)
        val relative = (0 until sampleCount).map { readU32(data, base + 0x10 + it * 4, little) }
        if (relative[2] >= remaining.toLong()) return null
        val valid = relative.count { it in 0L until remaining.toLong() }
        if (valid < minOf(3, sampleCount)) return null
        val nondecreasing = relative.zipWithNext().count { (a, b) -> b >= a }
        val afterTables = relative.count { it >= descriptorEnd - base.toLong() && it < remaining }

        var score = 20
        if (base == 0) score += 12
        if (type <= 0x20) score += 6
        score += valid * 2
        score += nondecreasing
        score += afterTables * 2
        if (relative[2] >= descriptorEnd - base.toLong()) score += 8
        return Header(base, little, type, count, descriptorBase, score)
    }

    private fun findScalar(
        data: ByteArray,
        start: Int,
        end: Int,
        value: Long,
        width: Int,
        little: Boolean,
    ): List<Int> {
        val hits = mutableListOf<Int>()
        var p = start
        while (p + width <= end) {
            val actual = when (width) {
                1 -> (data[p].toInt() and 0xff).toLong()
                2 -> readU16(data, p, little).toLong()
                else -> readU32(data, p, little)
            }
            if (actual == value) {
                hits += p
                if (hits.size == 12) break
            }
            p++
        }
        return hits
    }

    private fun formatHits(hits: List<Int>): String =
        if (hits.isEmpty()) "none" else hits.joinToString(",") { hex(it) }

    private fun asciiStrings(data: ByteArray, limit: Int): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var p = 0
        while (p < data.size && out.size < limit) {
            val start = p
            while (p < data.size && (data[p].toInt() and 0xff) in 0x20..0x7e) p++
            if (p - start >= 4) {
                out += start to data.copyOfRange(start, minOf(p, start + 96)).toString(Charsets.US_ASCII)
            }
            p = maxOf(p + 1, start + 1)
        }
        return out
    }

    private fun StringBuilder.dump(data: ByteArray, startRaw: Int, endRaw: Int) {
        var p = startRaw.coerceIn(0, data.size)
        val end = endRaw.coerceIn(p, data.size)
        if (p >= end) {
            appendLine("  <empty/out of range>")
            return
        }
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
    }

    private fun inlineHex(data: ByteArray, start: Int, length: Int): String {
        if (start !in data.indices) return "<out of range>"
        val end = minOf(data.size, start + length)
        return (start until end).joinToString(" ") {
            (data[it].toInt() and 0xff).toString(16).uppercase().padStart(2, '0')
        }
    }

    private fun align16(value: Int): Int = (value + 15) and -16

    private fun readU16(data: ByteArray, offset: Int, little: Boolean): Int {
        val a = data[offset].toInt() and 0xff
        val b = data[offset + 1].toInt() and 0xff
        return if (little) a or (b shl 8) else (a shl 8) or b
    }

    private fun readU32(data: ByteArray, offset: Int, little: Boolean): Long {
        val a = data[offset].toLong() and 0xff
        val b = data[offset + 1].toLong() and 0xff
        val c = data[offset + 2].toLong() and 0xff
        val d = data[offset + 3].toLong() and 0xff
        val value = if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
        else (a shl 24) or (b shl 16) or (c shl 8) or d
        return value and 0xffffffffL
    }

    private fun hex(value: Int): String =
        if (value < 0) "-1" else "0x${value.toString(16).uppercase()}"

    private fun hex(value: Long): String = "0x${value.toString(16).uppercase()}"
}
