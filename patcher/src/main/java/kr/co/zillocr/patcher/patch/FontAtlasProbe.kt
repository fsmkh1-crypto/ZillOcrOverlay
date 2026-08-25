package kr.co.zillocr.patcher.patch

/**
 * Produces diagnostic grayscale previews under an explicitly unverified texture
 * hypothesis: the final 0x20000 bytes of a PAR section are a 512x512 indexed
 * 4bpp texture stored with the common PSP 16-byte x 8-row swizzle.
 *
 * The probe does not infer glyph indexes and never mutates retail data.
 */
object FontAtlasProbe {
    const val WIDTH = 512
    const val HEIGHT = 512
    private const val WIDTH_BYTES = WIDTH / 2
    private const val PAYLOAD_SIZE = WIDTH_BYTES * HEIGHT // 0x20000

    data class Preview(
        val sectionIndex: Int,
        val originalPixels: ByteArray,
        val englishPixels: ByteArray,
        val deltaPixels: ByteArray,
        val changedPixels: Int,
        val nonZeroOriginalPixels: Int,
        val nonZeroEnglishPixels: Int,
    )

    fun build(
        retailFont: ByteArray,
        englishFont: ByteArray,
        xorPatch: ByteArray,
        sections: List<ZillFontIsoAnalyzer.ParSection>,
    ): List<Preview> {
        require(retailFont.size == englishFont.size && retailFont.size == xorPatch.size)
        return sections.mapNotNull { section ->
            if (section.size < PAYLOAD_SIZE) return@mapNotNull null
            val payloadStart = section.endExclusive - PAYLOAD_SIZE
            val retailPayload = retailFont.copyOfRange(payloadStart, section.endExclusive)
            val englishPayload = englishFont.copyOfRange(payloadStart, section.endExclusive)
            val deltaPayload = xorPatch.copyOfRange(payloadStart, section.endExclusive)

            val retailLinear = unswizzle(retailPayload)
            val englishLinear = unswizzle(englishPayload)
            val deltaLinear = unswizzle(deltaPayload)

            val originalPixels = decode4bpp(retailLinear)
            val englishPixels = decode4bpp(englishLinear)
            val deltaPixels = decode4bppMask(deltaLinear)

            Preview(
                sectionIndex = section.index,
                originalPixels = originalPixels,
                englishPixels = englishPixels,
                deltaPixels = deltaPixels,
                changedPixels = deltaPixels.count { (it.toInt() and 0xff) != 0 },
                nonZeroOriginalPixels = originalPixels.count { (it.toInt() and 0xff) != 0 },
                nonZeroEnglishPixels = englishPixels.count { (it.toInt() and 0xff) != 0 },
            )
        }
    }

    /** Common PSP byte-level unswizzle for 16-byte-wide x 8-row blocks. */
    private fun unswizzle(input: ByteArray): ByteArray {
        require(input.size == PAYLOAD_SIZE)
        val rowBlocks = WIDTH_BYTES / 16
        val output = ByteArray(input.size)
        for (y in 0 until HEIGHT) {
            val blockY = y / 8
            val inY = y and 7
            for (xByte in 0 until WIDTH_BYTES) {
                val blockX = xByte / 16
                val inX = xByte and 15
                val source = ((blockY * rowBlocks + blockX) * 128) + (inY * 16) + inX
                output[y * WIDTH_BYTES + xByte] = input[source]
            }
        }
        return output
    }

    /**
     * Expand each 4bpp index to grayscale. Low nibble is shown first, which is
     * the usual indexed-4 PSP ordering. A wrong nibble order only swaps adjacent
     * pixels and does not invalidate the broad atlas-structure check.
     */
    private fun decode4bpp(input: ByteArray): ByteArray {
        val pixels = ByteArray(WIDTH * HEIGHT)
        var p = 0
        for (valueByte in input) {
            val value = valueByte.toInt() and 0xff
            pixels[p++] = ((value and 0x0f) * 17).toByte()
            pixels[p++] = (((value ushr 4) and 0x0f) * 17).toByte()
        }
        return pixels
    }

    private fun decode4bppMask(input: ByteArray): ByteArray {
        val pixels = ByteArray(WIDTH * HEIGHT)
        var p = 0
        for (valueByte in input) {
            val value = valueByte.toInt() and 0xff
            pixels[p++] = if ((value and 0x0f) != 0) 0xff.toByte() else 0
            pixels[p++] = if (((value ushr 4) and 0x0f) != 0) 0xff.toByte() else 0
        }
        return pixels
    }
}
