package kr.co.zillocr.patcher.patch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Exact read-only PAR/GIM probe ported from HK47196/zill's textureoverride parser.
 *
 * Unlike FontAtlasProbe this does not guess 512x512/4bpp/tail payload geometry.
 * It parses the actual little-endian PSP GIM descriptor, dimensions, pixel order,
 * pitch/storage height and palette, then decodes logical pixels through the same
 * PSP storage-offset formula used by upstream.
 */
object GimFontProbe {
    private val GIM_MAGIC = byteArrayOf(
        'M'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte(), '.'.code.toByte(),
        '0'.code.toByte(), '0'.code.toByte(), '.'.code.toByte(), '1'.code.toByte(),
        'P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 0, 0, 0, 0, 0,
    )

    data class Surface(
        val format: Int,
        val order: Int,
        val width: Int,
        val height: Int,
        val bits: Int,
        val pitch: Int,
        val storageHeight: Int,
        val pixelsStart: Int,
        val pixelsEnd: Int,
    )

    data class Preview(
        val sectionIndex: Int,
        val sectionName: String,
        val image: Surface,
        val palette: Surface,
        val retailArgb: IntArray,
        val englishArgb: IntArray,
        val deltaMask: ByteArray,
        val changedLogicalPixels: Int,
    )

    fun build(
        retailFont: ByteArray,
        englishFont: ByteArray,
        sections: List<ZillFontIsoAnalyzer.ParSection>,
    ): List<Preview> {
        require(retailFont.size == englishFont.size)
        return sections.mapNotNull { section ->
            val retailChild = retailFont.copyOfRange(section.start, section.endExclusive)
            val englishChild = englishFont.copyOfRange(section.start, section.endExclusive)
            val parsed = try {
                parseGim(retailChild)
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            val parsedEnglish = try {
                parseGim(englishChild)
            } catch (_: Throwable) {
                return@mapNotNull null
            }
            require(parsed.image.geometryKey() == parsedEnglish.image.geometryKey()) {
                "English GIM geometry changed in PAR child ${section.index}"
            }
            require(parsed.palette.geometryKey() == parsedEnglish.palette.geometryKey()) {
                "English GIM palette geometry changed in PAR child ${section.index}"
            }

            val retailPalette = decodePalette(retailChild, parsed.palette)
            val englishPalette = decodePalette(englishChild, parsedEnglish.palette)
            val pixelCount = parsed.image.width * parsed.image.height
            val retailArgb = IntArray(pixelCount)
            val englishArgb = IntArray(pixelCount)
            val deltaMask = ByteArray(pixelCount)
            var changed = 0
            var p = 0
            for (y in 0 until parsed.image.height) {
                for (x in 0 until parsed.image.width) {
                    val ri = readIndex(retailChild, parsed.image, x, y)
                    val ei = readIndex(englishChild, parsedEnglish.image, x, y)
                    require(ri in retailPalette.indices) { "retail pixel references palette index $ri" }
                    require(ei in englishPalette.indices) { "English pixel references palette index $ei" }
                    retailArgb[p] = retailPalette[ri]
                    englishArgb[p] = englishPalette[ei]
                    if (ri != ei || retailPalette[ri] != englishPalette[ei]) {
                        deltaMask[p] = 0xff.toByte()
                        changed++
                    }
                    p++
                }
            }

            Preview(
                sectionIndex = section.index,
                sectionName = section.name,
                image = parsed.image,
                palette = parsed.palette,
                retailArgb = retailArgb,
                englishArgb = englishArgb,
                deltaMask = deltaMask,
                changedLogicalPixels = changed,
            )
        }
    }

    private data class ParsedGim(val image: Surface, val palette: Surface)

    private fun parseGim(data: ByteArray): ParsedGim {
        require(data.size >= 32 && data.copyOfRange(0, 16).contentEquals(GIM_MAGIC)) {
            "PAR child is not a little-endian PSP GIM"
        }
        val rootEnd = blockEnd(data, 16, 2, data.size)
        require(rootEnd == 16 + u32(data, 20)) { "invalid GIM root block" }
        val pictureStart = 16 + u32(data, 28)
        val pictureEnd = blockEnd(data, pictureStart, 3, rootEnd)
        require(pictureEnd == rootEnd) { "unsupported GIM picture layout" }
        val imageStart = pictureStart + u32(data, pictureStart + 12)
        val imageEnd = blockEnd(data, imageStart, 4, pictureEnd)
        val image = parseSurface(data, imageStart, imageEnd, false)
        val paletteStart = imageStart + u32(data, imageStart + 8)
        require(paletteStart == imageEnd) { "invalid GIM palette location" }
        val paletteEnd = blockEnd(data, paletteStart, 5, pictureEnd)
        require(paletteEnd == pictureEnd) { "invalid GIM palette block" }
        val palette = parseSurface(data, paletteStart, paletteEnd, true)
        require(palette.width * palette.height <= (1 shl image.bits)) { "GIM palette has too many entries" }
        return ParsedGim(image, palette)
    }

    private fun blockEnd(data: ByteArray, start: Int, kind: Int, limit: Int): Int {
        require(start >= 0 && start + 16 <= limit && start + 16 <= data.size) { "truncated GIM block" }
        require(u32(data, start) == kind) { "unexpected GIM block type" }
        val size = u32(data, start + 4)
        require(size >= 16 && start + size >= start && start + size <= limit) { "GIM block extends past container" }
        return start + size
    }

    private fun parseSurface(data: ByteArray, start: Int, end: Int, palette: Boolean): Surface {
        val descriptor = start + u32(data, start + 12)
        require(descriptor >= start + 16 && descriptor + 52 <= end) { "truncated GIM surface descriptor" }
        val format = u16(data, descriptor + 4)
        val order = u16(data, descriptor + 6)
        val width = u16(data, descriptor + 8)
        val height = u16(data, descriptor + 10)
        val bits = u16(data, descriptor + 12)
        val pitchAlign = u16(data, descriptor + 14)
        val heightAlign = u16(data, descriptor + 16)
        require(width > 0 && height > 0 && pitchAlign > 0 && heightAlign > 0 && order in 0..1) {
            "invalid GIM surface dimensions/alignment/order"
        }
        if (!palette) {
            require((format == 4 && bits == 4) || (format == 5 && bits == 8)) {
                "unsupported GIM image format $format/${bits}bpp"
            }
        } else {
            require((format == 1 || format == 2) && bits == 16) {
                "unsupported GIM palette format $format/${bits}bpp"
            }
        }

        val descriptorSize = u32(data, descriptor)
        val indexStart = u32(data, descriptor + 24)
        val pixelsRel = u32(data, descriptor + 28)
        val pixelsEndRel = u32(data, descriptor + 32)
        val levelType = u16(data, descriptor + 40)
        val levelCount = u16(data, descriptor + 42)
        val frameType = u16(data, descriptor + 44)
        val frameCount = u16(data, descriptor + 46)
        val wantLevel = if (palette) 2 else 1
        require(
            descriptorSize >= 48 &&
                indexStart >= descriptorSize &&
                descriptor + indexStart + 4 <= end &&
                u32(data, descriptor + indexStart) == pixelsRel &&
                levelType == wantLevel && levelCount == 1 && frameType == 3 && frameCount == 1
        ) { "unsupported GIM mipmap/frame layout" }

        val rowBytes = (width * bits + 7) / 8
        val pitch = align(rowBytes, pitchAlign)
        val storageHeight = align(height, heightAlign)
        require(
            pixelsEndRel == pixelsRel + pitch * storageHeight &&
                descriptor + pixelsRel >= descriptor &&
                descriptor + pixelsEndRel <= end
        ) { "invalid GIM pixel payload bounds" }
        if (order == 1) {
            require(pitch % 16 == 0 && storageHeight % 8 == 0) { "invalid PSP-swizzled GIM alignment" }
        }
        return Surface(
            format = format,
            order = order,
            width = width,
            height = height,
            bits = bits,
            pitch = pitch,
            storageHeight = storageHeight,
            pixelsStart = descriptor + pixelsRel,
            pixelsEnd = descriptor + pixelsEndRel,
        )
    }

    private fun readIndex(data: ByteArray, s: Surface, x: Int, y: Int): Int {
        if (s.bits == 4) {
            val value = data[storageOffset(s, x / 2, y)].toInt() and 0xff
            return (value ushr (4 * (x and 1))) and 15
        }
        return data[storageOffset(s, x, y)].toInt() and 0xff
    }

    private fun storageOffset(s: Surface, xByte: Int, y: Int): Int {
        if (s.order == 0) return s.pixelsStart + y * s.pitch + xByte
        return s.pixelsStart + ((y / 8) * (s.pitch / 16) + xByte / 16) * 128 + (y % 8) * 16 + xByte % 16
    }

    private fun decodePalette(data: ByteArray, s: Surface): IntArray {
        val count = s.width * s.height
        return IntArray(count) { i ->
            val offset = storageOffset(s, (i % s.width) * 2, i / s.width)
            val value = u16(data, offset)
            if (s.format == 1) {
                val r = expand(value and 31, 5)
                val g = expand((value ushr 5) and 31, 5)
                val b = expand((value ushr 10) and 31, 5)
                val a = if ((value and 0x8000) != 0) 255 else 0
                argb(a, r, g, b)
            } else {
                val r = (value and 15) * 17
                val g = ((value ushr 4) and 15) * 17
                val b = ((value ushr 8) and 15) * 17
                val a = ((value ushr 12) and 15) * 17
                argb(a, r, g, b)
            }
        }
    }

    private fun Surface.geometryKey(): List<Int> = listOf(format, order, width, height, bits, pitch, storageHeight, pixelsStart, pixelsEnd)

    private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    private fun expand(value: Int, bits: Int): Int = ((value shl (8 - bits)) or (value ushr (2 * bits - 8))) and 0xff

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        ((a and 0xff) shl 24) or ((r and 0xff) shl 16) or ((g and 0xff) shl 8) or (b and 0xff)

    private fun u16(data: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(data, offset, 2)
        .order(ByteOrder.LITTLE_ENDIAN)
        .short.toInt() and 0xffff

    private fun u32(data: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(data, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
}
