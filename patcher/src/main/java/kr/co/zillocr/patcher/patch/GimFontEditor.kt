package kr.co.zillocr.patcher.patch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal guarded writer for one 16x16 cell inside an authenticated zillfont GIM.
 *
 * This is deliberately an in-memory primitive: callers decide whether the result is
 * preview-only or later written into a copied ISO. It never writes the source ISO.
 */
object GimFontEditor {
    data class EditResult(
        val font: ByteArray,
        val transparentPaletteIndex: Int,
        val opaquePaletteIndex: Int,
    )

    private data class Surface(
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

    private data class Parsed(val image: Surface, val palette: Surface)

    fun replaceBinaryCell(
        font: ByteArray,
        section: ZillFontIsoAnalyzer.ParSection,
        ordinal: Int,
        mask: BooleanArray,
    ): EditResult {
        require(mask.size == 16 * 16) { "glyph mask must be 16x16" }
        require(section.start >= 0 && section.endExclusive <= font.size && section.start < section.endExclusive) {
            "PAR section outside font"
        }
        val child = font.copyOfRange(section.start, section.endExclusive)
        val parsed = parse(child)
        require(parsed.image.width == 512 && parsed.image.height == 512 && parsed.image.bits == 4) {
            "expected 512x512 4bpp font GIM"
        }
        require(ordinal in 0 until 32 * 32) { "cell ordinal outside 32x32 atlas" }

        val alphas = paletteAlphas(child, parsed.palette)
        require(alphas.isNotEmpty()) { "empty GIM palette" }
        val transparent = alphas.indices.minByOrNull { alphas[it] } ?: error("no transparent palette index")
        val opaque = alphas.indices.maxByOrNull { alphas[it] } ?: error("no opaque palette index")
        require(alphas[opaque] > alphas[transparent]) { "font palette has no alpha range" }

        val cellX = ordinal % 32
        val cellY = ordinal / 32
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val index = if (mask[y * 16 + x]) opaque else transparent
                writeIndex(child, parsed.image, cellX * 16 + x, cellY * 16 + y, index)
            }
        }

        val result = font.copyOf()
        child.copyInto(result, section.start)
        return EditResult(result, transparent, opaque)
    }

    private fun parse(data: ByteArray): Parsed {
        val magic = byteArrayOf(
            'M'.code.toByte(), 'I'.code.toByte(), 'G'.code.toByte(), '.'.code.toByte(),
            '0'.code.toByte(), '0'.code.toByte(), '.'.code.toByte(), '1'.code.toByte(),
            'P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), 0, 0, 0, 0, 0,
        )
        require(data.size >= 32 && data.copyOfRange(0, 16).contentEquals(magic)) { "PAR child is not PSP GIM" }
        val rootEnd = blockEnd(data, 16, 2, data.size)
        require(rootEnd == 16 + u32(data, 20)) { "invalid GIM root block" }
        val pictureStart = 16 + u32(data, 28)
        val pictureEnd = blockEnd(data, pictureStart, 3, rootEnd)
        require(pictureEnd == rootEnd) { "unsupported GIM picture layout" }
        val imageStart = pictureStart + u32(data, pictureStart + 12)
        val imageEnd = blockEnd(data, imageStart, 4, pictureEnd)
        val image = parseSurface(data, imageStart, imageEnd, palette = false)
        val paletteStart = imageStart + u32(data, imageStart + 8)
        require(paletteStart == imageEnd) { "invalid GIM palette location" }
        val paletteEnd = blockEnd(data, paletteStart, 5, pictureEnd)
        require(paletteEnd == pictureEnd) { "invalid GIM palette block" }
        val palette = parseSurface(data, paletteStart, paletteEnd, palette = true)
        require(palette.width * palette.height <= (1 shl image.bits)) { "palette too large for image bpp" }
        return Parsed(image, palette)
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
        require(descriptor >= start + 16 && descriptor + 52 <= end) { "truncated GIM descriptor" }
        val format = u16(data, descriptor + 4)
        val order = u16(data, descriptor + 6)
        val width = u16(data, descriptor + 8)
        val height = u16(data, descriptor + 10)
        val bits = u16(data, descriptor + 12)
        val pitchAlign = u16(data, descriptor + 14)
        val heightAlign = u16(data, descriptor + 16)
        require(width > 0 && height > 0 && pitchAlign > 0 && heightAlign > 0 && order in 0..1)
        if (!palette) {
            require((format == 4 && bits == 4) || (format == 5 && bits == 8)) { "unsupported image format $format/$bits" }
        } else {
            require((format == 1 || format == 2) && bits == 16) { "unsupported palette format $format/$bits" }
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
            descriptorSize >= 48 && indexStart >= descriptorSize &&
                descriptor + indexStart + 4 <= end && u32(data, descriptor + indexStart) == pixelsRel &&
                levelType == wantLevel && levelCount == 1 && frameType == 3 && frameCount == 1
        ) { "unsupported GIM mip/frame layout" }
        val rowBytes = (width * bits + 7) / 8
        val pitch = align(rowBytes, pitchAlign)
        val storageHeight = align(height, heightAlign)
        require(pixelsEndRel == pixelsRel + pitch * storageHeight)
        require(descriptor + pixelsRel >= descriptor && descriptor + pixelsEndRel <= end)
        if (order == 1) require(pitch % 16 == 0 && storageHeight % 8 == 0)
        return Surface(format, order, width, height, bits, pitch, storageHeight, descriptor + pixelsRel, descriptor + pixelsEndRel)
    }

    private fun paletteAlphas(data: ByteArray, palette: Surface): IntArray {
        val count = palette.width * palette.height
        return IntArray(count) { i ->
            val offset = storageOffset(palette, (i % palette.width) * 2, i / palette.width)
            val value = u16(data, offset)
            if (palette.format == 1) {
                if ((value and 0x8000) != 0) 255 else 0
            } else {
                ((value ushr 12) and 0x0f) * 17
            }
        }
    }

    private fun writeIndex(data: ByteArray, surface: Surface, x: Int, y: Int, index: Int) {
        require(x in 0 until surface.width && y in 0 until surface.height)
        if (surface.bits == 4) {
            require(index in 0..15)
            val offset = storageOffset(surface, x / 2, y)
            val old = data[offset].toInt() and 0xff
            val shift = 4 * (x and 1)
            val mask = 0x0f shl shift
            data[offset] = ((old and mask.inv()) or ((index and 0x0f) shl shift)).toByte()
        } else {
            require(index in 0..255)
            data[storageOffset(surface, x, y)] = index.toByte()
        }
    }

    private fun storageOffset(surface: Surface, xByte: Int, y: Int): Int {
        if (surface.order == 0) return surface.pixelsStart + y * surface.pitch + xByte
        return surface.pixelsStart +
            ((y / 8) * (surface.pitch / 16) + xByte / 16) * 128 +
            (y % 8) * 16 + xByte % 16
    }

    private fun align(value: Int, alignment: Int): Int = (value + alignment - 1) / alignment * alignment

    private fun u16(data: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(data, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun u32(data: ByteArray, offset: Int): Int = ByteBuffer
        .wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
}
