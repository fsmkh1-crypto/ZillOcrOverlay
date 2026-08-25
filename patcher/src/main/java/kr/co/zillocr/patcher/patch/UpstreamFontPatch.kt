package kr.co.zillocr.patcher.patch

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

/** Downloads and authenticates HK47196/zill's public frozen font XOR patch. */
object UpstreamFontPatch {
    private const val URL = "https://raw.githubusercontent.com/HK47196/zill/master/release/font/font-zillfont.zpatch"
    const val EXPECTED_COMPRESSED_SHA256 = "fcc46f805a970050d61b16ea00458731f1d56737fb04b0e04080f76c21465d89"
    const val EXPECTED_XOR_SHA256 = "7a48a683e523c07f641b9a70396555ce16d69ecccccc6fc6edbea50edd622aac"
    const val EXPECTED_EXPANDED_SIZE = 525_424

    data class Patch(val compressed: ByteArray, val xor: ByteArray)

    fun download(): Patch {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZillKoreanPatcher/0.2")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("upstream zpatch HTTP ${connection.responseCode}")
            }
            val compressed = connection.inputStream.use { it.readBytes() }
            val compressedSha = sha256(compressed)
            if (compressedSha != EXPECTED_COMPRESSED_SHA256) {
                error("unexpected zpatch SHA-256: $compressedSha")
            }
            val xor = InflaterInputStream(compressed.inputStream()).use { it.readBytes() }
            if (xor.size != EXPECTED_EXPANDED_SIZE) {
                error("unexpected expanded XOR size ${xor.size}")
            }
            val xorSha = sha256(xor)
            if (xorSha != EXPECTED_XOR_SHA256) {
                error("unexpected XOR SHA-256: $xorSha")
            }
            return Patch(compressed, xor)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(data: ByteArray): String = MessageDigest
        .getInstance("SHA-256")
        .digest(data)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
