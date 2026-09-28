package com.muhammedelsami.storepilot.engine.listing

import com.muhammedelsami.storepilot.api.ImageFormat
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.io.path.readBytes

/** What validation and diff need to know about an image. [sha256] is lowercase hex. */
data class ImageInfo(
    val format: ImageFormat,
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
    val size: Long,
    val sha256: String,
)

/** Reads PNG and JPEG headers. Nothing is decoded, so no desktop image libraries are needed. */
object ImageInspector {
    fun inspect(path: Path): ImageInfo = inspect(path.readBytes())

    /** Throws [IllegalArgumentException] for other formats and broken headers. */
    fun inspect(bytes: ByteArray): ImageInfo {
        val header = when {
            bytes.startsWith(PNG_SIGNATURE) -> png(bytes)
            bytes.size > 2 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 -> jpeg(bytes)
            else -> throw IllegalArgumentException("Not a PNG or JPEG image.")
        }
        return header.copy(size = bytes.size.toLong(), sha256 = sha256(bytes))
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun png(bytes: ByteArray): ImageInfo {
        // The IHDR chunk comes first: length, type, width, height, bit depth, color type.
        require(bytes.size >= 33 && String(bytes, 12, 4, Charsets.US_ASCII) == "IHDR") { "Broken PNG header." }
        val colorType = bytes.u8(25)
        var alpha = colorType == 4 || colorType == 6
        var offset = 8
        while (!alpha && offset + 8 <= bytes.size) {
            val length = bytes.int(offset)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "tRNS") alpha = true
            if (type == "IDAT" || length < 0) break
            offset += 12 + length
        }
        return ImageInfo(ImageFormat.PNG, bytes.int(16), bytes.int(20), alpha, 0, "")
    }

    private fun jpeg(bytes: ByteArray): ImageInfo {
        var offset = 2
        while (offset + 4 <= bytes.size) {
            require(bytes.u8(offset) == 0xFF) { "Broken JPEG header." }
            val marker = bytes.u8(offset + 1)
            when {
                marker == 0xFF -> offset++
                marker == 0x01 || marker in 0xD0..0xD9 -> offset += 2
                else -> {
                    val length = (bytes.u8(offset + 2) shl 8) or bytes.u8(offset + 3)
                    if (marker in START_OF_FRAME && offset + 9 <= bytes.size) {
                        val height = (bytes.u8(offset + 5) shl 8) or bytes.u8(offset + 6)
                        val width = (bytes.u8(offset + 7) shl 8) or bytes.u8(offset + 8)
                        return ImageInfo(ImageFormat.JPEG, width, height, false, 0, "")
                    }
                    offset += 2 + length
                }
            }
        }
        throw IllegalArgumentException("JPEG image without a size.")
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    // SOF0 to SOF15, without DHT (C4), JPG (C8), and DAC (CC).
    private val START_OF_FRAME = (0xC0..0xCF).toSet() - setOf(0xC4, 0xC8, 0xCC)

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

    private fun ByteArray.int(index: Int): Int =
        (u8(index) shl 24) or (u8(index + 1) shl 16) or (u8(index + 2) shl 8) or u8(index + 3)
}
