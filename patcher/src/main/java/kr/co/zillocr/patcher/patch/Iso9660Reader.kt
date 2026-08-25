package kr.co.zillocr.patcher.patch

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.StandardCharsets

/** Minimal read-only ISO9660 reader for locating files inside a PSP ISO. */
class Iso9660Reader(private val channel: SeekableByteChannel) {
    data class Entry(
        val name: String,
        val extentLba: Long,
        val size: Long,
        val flags: Int,
    ) {
        val isDirectory: Boolean get() = flags and 0x02 != 0
        val isMultiExtent: Boolean get() = flags and 0x80 != 0
    }

    data class LocatedEntry(val path: String, val entry: Entry)

    companion object {
        const val SECTOR_SIZE = 2048
        private const val PVD_SECTOR = 16L
    }

    private val root: Entry by lazy { readPrimaryVolumeDescriptorRoot() }

    fun find(path: String): Entry {
        val parts = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        var current = root
        for ((index, part) in parts.withIndex()) {
            if (!current.isDirectory) error("${parts.take(index).joinToString("/") } is not a directory")
            val next = listDirectory(current)
                .firstOrNull { normalizeName(it.name).equals(normalizeName(part), ignoreCase = true) }
                ?: error("ISO path component not found: $part")
            if (next.isMultiExtent) error("multi-extent ISO files are not supported yet: ${next.name}")
            current = next
        }
        return current
    }

    /** Finds a resource independent of the game's platform-specific data-root prefix. */
    fun findBySuffix(pathSuffix: String): LocatedEntry? {
        val wanted = normalizePath(pathSuffix)
        fun walk(directory: Entry, prefix: String, depth: Int): LocatedEntry? {
            if (depth > 32) return null
            for (child in listDirectory(directory)) {
                val clean = normalizeName(child.name)
                val path = if (prefix.isEmpty()) clean else "$prefix/$clean"
                if (!child.isDirectory && normalizePath(path).endsWith(wanted)) {
                    if (child.isMultiExtent) error("multi-extent ISO files are not supported yet: $path")
                    return LocatedEntry(path, child)
                }
                if (child.isDirectory) {
                    walk(child, path, depth + 1)?.let { return it }
                }
            }
            return null
        }
        return walk(root, "", 0)
    }

    fun readEntry(entry: Entry): ByteArray {
        require(entry.size <= Int.MAX_VALUE) { "entry is too large to read into memory: ${entry.name}" }
        return readAt(entry.extentLba * SECTOR_SIZE, entry.size.toInt())
    }

    fun readEntryRange(entry: Entry, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length >= 0 && offset + length <= entry.size) {
            "range outside ISO entry ${entry.name}: offset=$offset length=$length size=${entry.size}"
        }
        return readAt(entry.extentLba * SECTOR_SIZE + offset, length)
    }

    fun listDirectory(directory: Entry): List<Entry> {
        require(directory.isDirectory) { "not a directory: ${directory.name}" }
        val data = readEntry(directory)
        val result = mutableListOf<Entry>()
        var pos = 0
        while (pos < data.size) {
            val recordLength = data[pos].toInt() and 0xff
            if (recordLength == 0) {
                pos = ((pos / SECTOR_SIZE) + 1) * SECTOR_SIZE
                continue
            }
            if (pos + recordLength > data.size || recordLength < 34) error("invalid ISO9660 directory record at offset $pos")
            val idLength = data[pos + 32].toInt() and 0xff
            if (33 + idLength > recordLength) error("invalid ISO9660 file identifier at offset $pos")
            val idBytes = data.copyOfRange(pos + 33, pos + 33 + idLength)
            val name = when {
                idLength == 1 && idBytes[0].toInt() == 0 -> "."
                idLength == 1 && idBytes[0].toInt() == 1 -> ".."
                else -> String(idBytes, StandardCharsets.US_ASCII)
            }
            if (name != "." && name != "..") {
                result += Entry(
                    name = name,
                    extentLba = u32le(data, pos + 2),
                    size = u32le(data, pos + 10),
                    flags = data[pos + 25].toInt() and 0xff,
                )
            }
            pos += recordLength
        }
        return result
    }

    private fun readPrimaryVolumeDescriptorRoot(): Entry {
        val pvd = readAt(PVD_SECTOR * SECTOR_SIZE, SECTOR_SIZE)
        if ((pvd[0].toInt() and 0xff) != 1 || String(pvd, 1, 5, StandardCharsets.US_ASCII) != "CD001") {
            error("not an ISO9660 primary volume descriptor")
        }
        val pos = 156
        val length = pvd[pos].toInt() and 0xff
        if (length < 34) error("invalid root directory record")
        return Entry(
            name = "/",
            extentLba = u32le(pvd, pos + 2),
            size = u32le(pvd, pos + 10),
            flags = pvd[pos + 25].toInt() and 0xff,
        )
    }

    private fun readAt(offset: Long, length: Int): ByteArray {
        val output = ByteArray(length)
        val buffer = ByteBuffer.wrap(output)
        channel.position(offset)
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer)
            if (read < 0) error("unexpected EOF at ISO offset $offset")
            if (read == 0) error("short zero-byte read at ISO offset $offset")
        }
        return output
    }

    private fun normalizeName(name: String): String = name.substringBefore(';').trimEnd('.')
    private fun normalizePath(path: String): String =
        path.replace('\\', '/').trim('/').lowercase()

    private fun u32le(data: ByteArray, offset: Int): Long = ByteBuffer
        .wrap(data, offset, 4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
        .toLong() and 0xffffffffL
}
