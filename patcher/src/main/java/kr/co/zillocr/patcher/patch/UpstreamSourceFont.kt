package kr.co.zillocr.patcher.patch

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads and authenticates HK47196/zill's retained fs-tahoma source font. */
object UpstreamSourceFont {
    private const val URL = "https://raw.githubusercontent.com/HK47196/zill/master/release/font/fs-tahoma-8px.otf"
    const val EXPECTED_SIZE = 175_020
    // Git object id from the authenticated upstream master tree. We verify the
    // downloaded bytes using Git's blob hash: SHA1("blob <size>\\0" + content).
    const val EXPECTED_GIT_BLOB_SHA1 = "3d7a318d95eb61077320ce1f5dfe66ccf5c65c4e"

    @Volatile
    private var authenticatedBytes: ByteArray? = null

    fun download(): ByteArray {
        val connection = (URL(URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ZillKoreanPatcher/1.0")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("upstream source font HTTP ${connection.responseCode}")
            }
            val data = connection.inputStream.use { it.readBytes() }
            if (data.size != EXPECTED_SIZE) error("unexpected source font size ${data.size}")
            val blob = gitBlobSha1(data)
            if (blob != EXPECTED_GIT_BLOB_SHA1) {
                error("unexpected source font git blob SHA-1: $blob")
            }
            authenticatedBytes = data.copyOf()
            return data
        } finally {
            connection.disconnect()
        }
    }

    /** Returns the last authenticated font bytes for deterministic cmap probing. */
    fun authenticatedSnapshot(): ByteArray? = authenticatedBytes?.copyOf()

    private fun gitBlobSha1(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${data.size}\u0000".toByteArray(Charsets.UTF_8))
        digest.update(data)
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
