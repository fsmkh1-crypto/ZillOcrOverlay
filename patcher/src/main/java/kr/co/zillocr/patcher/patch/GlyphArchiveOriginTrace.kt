package kr.co.zillocr.patcher.patch

/** Read-only probes for the virtual font resources and recovered PAR container format. */
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


    /**
     * Fallback used when BOOT resource IDs are not ISO9660 paths.  It emits the
     * inner virtual-resource lookup and the physical ISO archive/index inventory.
     */
    fun virtualResourceReport(
        boot: ByteArray,
        directZillFound: Boolean,
        directJillFound: Boolean,
        isoFiles: List<Iso9660Reader.LocatedEntry>,
    ): String = buildString {
        appendLine("glyph virtual-resource loader trace v6")
        appendLine("PoC 3.5 · read-only · ISO/BOOT writes disabled")
        appendLine()
        appendLine("=== corrected interpretation ===")
        appendLine("font/zillfont.par: direct ISO entry = ${if (directZillFound) "present" else "absent"}")
        appendLine("2d/font/jillbtn.par: direct ISO entry = ${if (directJillFound) "present" else "absent"}")
        appendLine("These strings are virtual resource IDs passed to the resource manager, not guaranteed ISO9660 paths.")
        appendLine("owner+0x380 and owner+0x384 writer tracing remains valid; only the previous physical-path assumption was wrong.")
        appendLine()
        appendLine("=== inner virtual-resource loader ===")
        appendLine("wrapper VA 0x1CE528 delegates to VA 0x1D0E50 while preserving resource ID and owner output slot.")
        appendLine("inner loader VA=0x1D0E50 / BOOT file=0x1D0ED0")
        appendLine("fixed range ends immediately before next wrapper VA=0x1D1058 / file=0x1D10D8.")
        dumpMips(boot, 0x1D0ED0, 0x1D10D8)
        appendLine()
        appendLine("direct JAL targets in inner loader:")
        val targets = jalTargets(boot, 0x1D0ED0, 0x1D10D8)
        if (targets.isEmpty()) appendLine("  none")
        else targets.forEach { target ->
            appendLine("  VA=${hex(target)} file≈${hex(target + 0x80)}")
        }
        appendLine()
        appendLine("=== ISO physical inventory ===")
        appendLine("regular files: ${isoFiles.size}")
        val extensionRows = isoFiles.groupBy { row ->
            row.path.substringAfterLast('.', "").lowercase().ifEmpty { "<none>" }
        }.map { (ext, rows) ->
            var bytes = 0L
            rows.forEach { bytes += it.entry.size }
            Triple(ext, rows.size, bytes)
        }.sortedWith(compareByDescending<Triple<String, Int, Long>> { it.third }.thenByDescending { it.second })
        appendLine("extensions by total bytes (top 30):")
        extensionRows.take(30).forEach { (ext, count, bytes) ->
            appendLine("  ${ext.padEnd(8)} count=${count.toString().padStart(4)} bytes=$bytes")
        }
        appendLine()
        appendLine("largest physical files (top 50):")
        isoFiles.sortedByDescending { it.entry.size }.take(50).forEach { row ->
            appendLine("  size=${row.entry.size.toString().padStart(10)} LBA=${row.entry.extentLba.toString().padStart(8)}  ${row.path}")
        }
        appendLine()
        val archiveWords = listOf("archive", "pack", "resource", "data", "font", "text", "index", "filelist")
        val archiveExt = setOf("bin", "dat", "arc", "pak", "pac", "pck", "cpk", "wad", "idx", "hed", "lst", "par")
        val likely = isoFiles.filter { row ->
            val lower = row.path.lowercase()
            val ext = lower.substringAfterLast('.', "")
            ext in archiveExt || archiveWords.any { it in lower }
        }.sortedWith(compareByDescending<Iso9660Reader.LocatedEntry> { it.entry.size }.thenBy { it.path })
        appendLine("named archive/index/resource candidates (top 100):")
        if (likely.isEmpty()) appendLine("  none")
        else likely.take(100).forEach { row ->
            appendLine("  size=${row.entry.size.toString().padStart(10)} LBA=${row.entry.extentLba.toString().padStart(8)}  ${row.path}")
        }
        appendLine()
        appendLine("=== next decision rule ===")
        appendLine("Use the JAL targets above to recover hash/path lookup, then map virtual ID 0x252564 to one physical file/LBA.")
        appendLine("Only after that mapping is proven should the recovered container parser extract offsetTable[2] and validate 0/A/a.")
        appendLine("No patch writes were performed.")
    }.trimEnd()


    data class ArchivePair(
        val indexPath: String,
        val index: ByteArray,
        val arcPath: String,
        val arcSize: Long,
        val readArc: (Long, Int) -> ByteArray,
    )

    private data class PaaRecord(
        val index: Int,
        val nameOffset: Int,
        val size: Long,
        val metaA: Long,
        val metaB: Long,
    )

    private data class PaaIndex(
        val count: Int,
        val tableStart: Int,
        val stringStart: Int,
        val auxiliaryCount: Int,
        val records: List<PaaRecord>,
    )

    /** PoC 3.7: decode the PAA record table, reverse-map names, and derive ARC member offsets. */
    fun archiveIndexReport(pairs: List<ArchivePair>): String = buildString {
        appendLine("glyph PAA record/ARC member resolver v8")
        appendLine("PoC 3.7 · read-only · ISO/BOOT writes disabled")
        appendLine("PAA layout hypothesis: header 0x20, records 0x10 bytes, record+0=name pointer, record+4=member size.")
        appendLine()

        val resources = listOf("font/zillfont.par", "2d/font/jillbtn.par")
        pairs.forEach { pair ->
            appendLine("=== ${pair.indexPath} -> ${pair.arcPath} ===")
            appendLine("index size=${pair.index.size} arc size=${pair.arcSize} (${hex(pair.arcSize)})")
            val paa = parsePaaIndex(pair.index)
            if (paa == null) {
                appendLine("PAA header/record table: invalid")
                appendLine("index head:")
                dump(pair.index, 0, minOf(pair.index.size, 0x100))
                appendLine()
                return@forEach
            }

            val calculatedEnd = paa.tableStart.toLong() + paa.count.toLong() * 0x10L
            appendLine("PAA header: count=${paa.count} tableStart=${hex(paa.tableStart)} stringStart=${hex(paa.stringStart)} auxiliaryCount=${paa.auxiliaryCount}")
            appendLine("record geometry: tableStart + count*0x10 = ${hex(calculatedEnd)} ; header stringStart = ${hex(paa.stringStart)} ; match=${calculatedEnd == paa.stringStart.toLong()}")

            val modes = listOf(
                "raw" to 1L,
                "align16" to 16L,
                "align0x800" to 0x800L,
            )
            val totals = modes.map { (name, alignment) ->
                var total = 0L
                paa.records.forEach { total += alignLong(it.size, alignment) }
                Triple(name, alignment, total)
            }
            appendLine("ARC packing totals from record+4:")
            totals.forEach { (name, _, total) ->
                appendLine("  ${name.padEnd(10)} total=${hex(total)} delta=${signedHex(total - pair.arcSize)}")
            }
            val chosen = totals.minByOrNull { kotlin.math.abs(it.third - pair.arcSize) }!!
            appendLine("selected packing: ${chosen.first} (smallest absolute end delta ${signedHex(chosen.third - pair.arcSize)})")
            appendLine()

            resources.forEach { resource ->
                appendLine("--- virtual ID: $resource ---")
                val stringHits = findBytes(pair.index, resource.toByteArray(Charsets.US_ASCII))
                appendLine("string pool hits: ${formatHits(stringHits)}")
                val records = paa.records.filter { record ->
                    stringHits.contains(record.nameOffset) || readAsciiAt(pair.index, record.nameOffset) == resource
                }
                if (records.isEmpty()) {
                    appendLine("record reverse references: none")
                    appendLine()
                    return@forEach
                }

                records.forEach { record ->
                    val recAt = paa.tableStart + record.index * 0x10
                    appendLine("record #${record.index} @ ${hex(recAt)} name=${hex(record.nameOffset)} size=${hex(record.size)} metaA=${hex(record.metaA)} metaB=${hex(record.metaB)}")
                    val neighborFrom = maxOf(0, record.index - 2)
                    val neighborTo = minOf(paa.records.size, record.index + 3)
                    appendLine("neighbor records:")
                    for (i in neighborFrom until neighborTo) {
                        val r = paa.records[i]
                        appendLine("  #${r.index} rec=${hex(paa.tableStart + r.index * 0x10)} name=${hex(r.nameOffset)} '${readAsciiAt(pair.index, r.nameOffset)}' size=${hex(r.size)} metaA=${hex(r.metaA)} metaB=${hex(r.metaB)}")
                    }

                    val offsets = modes.map { (name, alignment) ->
                        var offset = 0L
                        for (i in 0 until record.index) offset += alignLong(paa.records[i].size, alignment)
                        Triple(name, alignment, offset)
                    }
                    appendLine("candidate ARC member offsets:")
                    offsets.forEach { (name, _, offset) ->
                        appendLine("  ${name.padEnd(10)} offset=${hex(offset)} end=${hex(offset + record.size)} inRange=${offset >= 0 && offset + record.size <= pair.arcSize}")
                    }

                    val preferredOffset = offsets.first { it.first == chosen.first }.third
                    if (preferredOffset !in 0 until pair.arcSize) {
                        appendLine("selected ARC offset is out of range")
                        return@forEach
                    }

                    val headLength = minOf(0x4000L, pair.arcSize - preferredOffset).toInt()
                    val head = pair.readArc(preferredOffset, headLength)
                    appendLine("selected member head @ ARC ${hex(preferredOffset)}:")
                    dump(head, 0, minOf(head.size, 0x100))
                    val summary = probeContainerSummary(head, minOf(record.size, pair.arcSize - preferredOffset))
                    appendLine("container header at member base: ${summary ?: "none"}")

                    appendLine("nearby parser-compatible headers (±0x800, step 0x10):")
                    val nearby = mutableListOf<Pair<Long, String>>()
                    var delta = -0x800
                    while (delta <= 0x800 && nearby.size < 16) {
                        val candidate = preferredOffset + delta
                        if (candidate >= 0 && candidate + 0x100 <= pair.arcSize) {
                            val probe = pair.readArc(candidate, minOf(0x1000L, pair.arcSize - candidate).toInt())
                            probeContainerSummary(probe, pair.arcSize - candidate)?.let { nearby += candidate to it }
                        }
                        delta += 0x10
                    }
                    if (nearby.isEmpty()) appendLine("  none")
                    else nearby.forEach { (offset, text) -> appendLine("  ARC ${hex(offset)} -> $text") }

                    if (summary != null && record.size in 0x30L..0x2000000L && preferredOffset + record.size <= pair.arcSize) {
                        val member = pair.readArc(preferredOffset, record.size.toInt())
                        appendLine()
                        analyze("RESOLVED ARC MEMBER: $resource", "${pair.arcPath}@${hex(preferredOffset)}", member)
                    } else {
                        appendLine("full member parse skipped: base header unresolved, invalid size, or member >32 MiB")
                    }
                }
                appendLine()
            }
        }

        appendLine("=== decision rule ===")
        appendLine("A valid reverse record plus a packing total matching ARC size proves ID→record→ARC-member mapping.")
        appendLine("If the resolved member header is parser-compatible, sub-block[2] and 0/A/a descriptors are dumped immediately.")
        appendLine("If the member starts compressed/wrapped, use its exact record offset/size to recover only that wrapper next.")
        appendLine("No patch writes were performed.")
    }.trimEnd()

    private fun parsePaaIndex(data: ByteArray): PaaIndex? {
        if (data.size < 0x30 || data[0] != 0x50.toByte() || data[1] != 0x41.toByte() || data[2] != 0x41.toByte()) return null
        val countLong = readU32(data, 0x08, true)
        val tableStartLong = readU32(data, 0x0C, true)
        val stringStartLong = readU32(data, 0x10, true)
        val auxiliaryLong = readU32(data, 0x14, true)
        if (countLong !in 1L..0x100000L || tableStartLong > Int.MAX_VALUE || stringStartLong > data.size.toLong()) return null
        val count = countLong.toInt()
        val tableStart = tableStartLong.toInt()
        val stringStart = stringStartLong.toInt()
        if (tableStart < 0x18 || tableStart.toLong() + countLong * 0x10L != stringStartLong) return null
        if (stringStart > data.size) return null
        val records = ArrayList<PaaRecord>(count)
        for (i in 0 until count) {
            val at = tableStart + i * 0x10
            if (at + 0x10 > data.size) return null
            val nameLong = readU32(data, at, true)
            if (nameLong > Int.MAX_VALUE) return null
            records += PaaRecord(
                index = i,
                nameOffset = nameLong.toInt(),
                size = readU32(data, at + 4, true),
                metaA = readU32(data, at + 8, true),
                metaB = readU32(data, at + 12, true),
            )
        }
        return PaaIndex(count, tableStart, stringStart, auxiliaryLong.toInt(), records)
    }

    private fun readAsciiAt(data: ByteArray, offset: Int): String {
        if (offset !in data.indices) return "<out-of-range>"
        var end = offset
        while (end < data.size && end - offset < 192) {
            val c = data[end].toInt() and 0xff
            if (c == 0) break
            if (c !in 0x20..0x7e) return "<non-ascii>"
            end++
        }
        return data.copyOfRange(offset, end).toString(Charsets.US_ASCII)
    }

    private fun alignLong(value: Long, alignment: Long): Long =
        if (alignment <= 1L) value else (value + alignment - 1L) and -alignment

    private fun signedHex(value: Long): String =
        if (value < 0) "-0x${(-value).toString(16).uppercase()}" else "+0x${value.toString(16).uppercase()}"

    private fun filteredAsciiStrings(
        data: ByteArray,
        limit: Int,
        predicate: (String) -> Boolean,
    ): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var p = 0
        while (p < data.size && out.size < limit) {
            val start = p
            while (p < data.size && (data[p].toInt() and 0xff) in 0x20..0x7e) p++
            if (p - start >= 4) {
                val text = data.copyOfRange(start, minOf(p, start + 160)).toString(Charsets.US_ASCII)
                if (predicate(text)) out += start to text
            }
            p = maxOf(p + 1, start + 1)
        }
        return out
    }

    private fun findBytes(data: ByteArray, needle: ByteArray): List<Int> {
        if (needle.isEmpty() || needle.size > data.size) return emptyList()
        val out = mutableListOf<Int>()
        var p = 0
        while (p + needle.size <= data.size && out.size < 64) {
            var same = true
            for (i in needle.indices) {
                if (data[p + i] != needle[i]) {
                    same = false
                    break
                }
            }
            if (same) out += p
            p++
        }
        return out
    }

    private fun findU32(data: ByteArray, value: Long, little: Boolean): List<Int> {
        val out = mutableListOf<Int>()
        var p = 0
        while (p + 4 <= data.size && out.size < 64) {
            if (readU32(data, p, little) == (value and 0xffffffffL)) out += p
            p++
        }
        return out
    }

    private fun hashVariants(resource: String): List<Pair<String, Long>> {
        val forms = linkedMapOf(
            "raw" to resource,
            "lower" to resource.lowercase(),
            "backslash" to resource.replace('/', '\\'),
            "lower-backslash" to resource.lowercase().replace('/', '\\'),
        )
        val out = linkedMapOf<String, Long>()
        forms.forEach { (formName, text) ->
            val bytes = text.toByteArray(Charsets.US_ASCII)
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            out["crc32-$formName"] = crc
            var fnv = 0x811c9dc5L
            var djb = 5381L
            var sdbm = 0L
            var java31 = 0L
            bytes.forEach { byte ->
                val b = byte.toLong() and 0xff
                fnv = ((fnv xor b) * 0x01000193L) and 0xffffffffL
                djb = ((djb * 33L) + b) and 0xffffffffL
                sdbm = (b + (sdbm shl 6) + (sdbm shl 16) - sdbm) and 0xffffffffL
                java31 = (java31 * 31L + b) and 0xffffffffL
            }
            out["fnv1a-$formName"] = fnv
            out["djb2-$formName"] = djb
            out["sdbm-$formName"] = sdbm
            out["x31-$formName"] = java31
        }
        return out.entries.map { it.key to it.value }
    }

    private fun probeContainerSummary(probe: ByteArray, remaining: Long): String? {
        if (probe.size < 0x30 || remaining < 0x30) return null
        fun check(little: Boolean): String? {
            val type = readU32(probe, 4, little)
            val countLong = readU32(probe, 8, little)
            if (countLong !in 3L..0x4000L) return null
            val count = countLong.toInt()
            if (0x10L + countLong * 4L > remaining) return null
            val sample = minOf(count, 8)
            if (0x10 + sample * 4 > probe.size) return null
            val offsets = (0 until sample).map { readU32(probe, 0x10 + it * 4, little) }
            if (offsets.any { it >= remaining }) return null
            var score = 20
            score += offsets.zipWithNext().count { (a, b) -> b >= a }
            if (type <= 0x20) score += 6
            val descriptorEnd = align16(0x10 + count * 4).toLong() + countLong * 0x20L
            if (descriptorEnd <= remaining) score += 8
            if (offsets[2] >= descriptorEnd) score += 12
            return "score=$score endian=${if (little) "LE" else "BE"} type=${hex(type)} count=$count sub2=${hex(offsets[2])}"
        }
        val a = check(true)
        val b = check(false)
        return listOfNotNull(a, b).maxByOrNull {
            it.substringAfter("score=").substringBefore(' ').toIntOrNull() ?: 0
        }
    }

    private val mipsRegisters = arrayOf(
        "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra",
    )

    private fun StringBuilder.dumpMips(data: ByteArray, start: Int, end: Int) {
        if (start < 0 || start >= data.size) {
            appendLine("  <BOOT range unavailable: size=${hex(data.size)}>")
            return
        }
        val safeEnd = minOf(end, data.size)
        var file = start
        while (file + 4 <= safeEnd) {
            val word = readU32(data, file, true).toInt()
            val va = file - 0x80
            appendLine("  ${hex(file)}  ${word.toUInt().toString(16).uppercase().padStart(8, '0')}  ${decodeMips(word, va)}")
            file += 4
        }
        if (safeEnd < end) appendLine("  <truncated at BOOT EOF>")
    }

    private fun jalTargets(data: ByteArray, start: Int, end: Int): List<Int> {
        val result = linkedSetOf<Int>()
        var file = start.coerceAtLeast(0)
        val safeEnd = minOf(end, data.size)
        while (file + 4 <= safeEnd) {
            val word = readU32(data, file, true).toInt()
            if ((word ushr 26) == 3) {
                val va = file - 0x80
                result += (((va + 4) and 0xF0000000.toInt()) or ((word and 0x03FFFFFF) shl 2))
            }
            file += 4
        }
        return result.toList()
    }

    private fun decodeMips(word: Int, va: Int): String {
        val op = word ushr 26
        val rs = (word ushr 21) and 31
        val rt = (word ushr 16) and 31
        val rd = (word ushr 11) and 31
        val sa = (word ushr 6) and 31
        val fn = word and 63
        val imm = word and 0xffff
        val simm = imm.toShort().toInt()
        fun r(i: Int) = mipsRegisters[i]
        fun branchTarget() = va + 4 + (simm shl 2)
        return when (op) {
            0 -> when (fn) {
                0 -> if (word == 0) "nop" else "sll ${r(rd)}, ${r(rt)}, $sa"
                2 -> "srl ${r(rd)}, ${r(rt)}, $sa"
                3 -> "sra ${r(rd)}, ${r(rt)}, $sa"
                8 -> "jr ${r(rs)}"
                9 -> "jalr ${r(rd)}, ${r(rs)}"
                16 -> "mfhi ${r(rd)}"
                18 -> "mflo ${r(rd)}"
                24 -> "mult ${r(rs)}, ${r(rt)}"
                26 -> "div ${r(rs)}, ${r(rt)}"
                33 -> "addu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                35 -> "subu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                36 -> "and ${r(rd)}, ${r(rs)}, ${r(rt)}"
                37 -> "or ${r(rd)}, ${r(rs)}, ${r(rt)}"
                38 -> "xor ${r(rd)}, ${r(rs)}, ${r(rt)}"
                42 -> "slt ${r(rd)}, ${r(rs)}, ${r(rt)}"
                43 -> "sltu ${r(rd)}, ${r(rs)}, ${r(rt)}"
                else -> "SPECIAL fn=0x${fn.toString(16).uppercase()}"
            }
            1 -> when (rt) {
                0 -> "bltz ${r(rs)}, ${hex(branchTarget())}"
                1 -> "bgez ${r(rs)}, ${hex(branchTarget())}"
                else -> "REGIMM rt=$rt target=${hex(branchTarget())}"
            }
            2, 3 -> {
                val target = (((va + 4) and 0xF0000000.toInt()) or ((word and 0x03FFFFFF) shl 2))
                "${if (op == 3) "jal" else "j"} VA=${hex(target)} file≈${hex(target + 0x80)}"
            }
            4 -> "beq ${r(rs)}, ${r(rt)}, ${hex(branchTarget())}"
            5 -> "bne ${r(rs)}, ${r(rt)}, ${hex(branchTarget())}"
            6 -> "blez ${r(rs)}, ${hex(branchTarget())}"
            7 -> "bgtz ${r(rs)}, ${hex(branchTarget())}"
            9 -> "addiu ${r(rt)}, ${r(rs)}, $simm"
            10 -> "slti ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            11 -> "sltiu ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            12 -> "andi ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            13 -> "ori ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            14 -> "xori ${r(rt)}, ${r(rs)}, 0x${imm.toString(16).uppercase()}"
            15 -> "lui ${r(rt)}, 0x${imm.toString(16).uppercase()}"
            32 -> "lb ${r(rt)}, $simm(${r(rs)})"
            33 -> "lh ${r(rt)}, $simm(${r(rs)})"
            35 -> "lw ${r(rt)}, $simm(${r(rs)})"
            36 -> "lbu ${r(rt)}, $simm(${r(rs)})"
            37 -> "lhu ${r(rt)}, $simm(${r(rs)})"
            40 -> "sb ${r(rt)}, $simm(${r(rs)})"
            41 -> "sh ${r(rt)}, $simm(${r(rs)})"
            43 -> "sw ${r(rt)}, $simm(${r(rs)})"
            else -> "op=0x${op.toString(16).uppercase()} rs=${r(rs)} rt=${r(rt)} imm=0x${imm.toString(16).uppercase()}"
        }
    }

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
