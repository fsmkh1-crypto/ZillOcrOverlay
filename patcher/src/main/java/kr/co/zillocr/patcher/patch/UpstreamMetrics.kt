package kr.co.zillocr.patcher.patch

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads and authenticates the frozen upstream metrics repertoire. */
object UpstreamMetrics {
    private const val URL = "https://raw.githubusercontent.com/HK47196/zill/master/release/font/metrics.toml"
    private const val EXPECTED_GIT_BLOB_SHA1 = "cb332558ea2c9419241d6379899bbafab78d52b9"
    private val keyPattern = Regex("^\\\"0x([0-9a-fA-F]{4})\\\"\\s*=", RegexOption.MULTILINE)

    data class Entry(val key: Int, val cp932: ByteArray)

    fun downloadEntries(): List<Entry> {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZillKoreanPatcher/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("upstream metrics HTTP ${connection.responseCode}")
            }
            val data = connection.inputStream.use { it.readBytes() }
            val blob = gitBlobSha1(data)
            if (blob != EXPECTED_GIT_BLOB_SHA1) error("unexpected metrics git blob SHA-1: $blob")
            val text = data.toString(Charsets.UTF_8)
            val keys = keyPattern.findAll(text).map { it.groupValues[1].toInt(16) }.toList()
            require(keys.size == 2_637) { "metrics repertoire has ${keys.size} glyphs; expected 2637" }
            require(keys.distinct().size == keys.size) { "metrics repertoire contains duplicate keys" }
            return keys.map { key -> Entry(key, bytesForKey(key)) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Runtime/layout key is little-endian for two-byte CP932: E4 44 -> 0x44e4.
     * Convert the frozen metric key back to the actual encoded byte sequence.
     */
    fun bytesForKey(key: Int): ByteArray = if (key <= 0xff) {
        byteArrayOf(key.toByte())
    } else {
        byteArrayOf((key and 0xff).toByte(), ((key ushr 8) and 0xff).toByte())
    }

    fun sortedByEncodedBytes(entries: List<Entry>): List<Entry> = entries.sortedWith { a, b ->
        compareUnsignedBytes(a.cp932, b.cp932)
    }

    private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val av = a[i].toInt() and 0xff
            val bv = b[i].toInt() and 0xff
            if (av != bv) return@sortedWithComparator av - bv
        }
        return a.size - b.size
    }

    // Small helper only to make the lambda return label above explicit to Kotlin.
    private inline fun <T> sortedWithComparator(block: () -> T): T = block()

    private fun gitBlobSha1(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${data.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
