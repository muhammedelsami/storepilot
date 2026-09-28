package com.muhammedelsami.storepilot.engine.listing

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32

/** Image files that only have the headers ImageInspector reads. */
object TestImages {
    /** [seed] makes images of the same size differ. */
    fun png(width: Int, height: Int, alpha: Boolean = false, seed: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val header = ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(width)
                writeInt(height)
                writeByte(8)
                writeByte(if (alpha) 6 else 2)
                writeByte(0)
                writeByte(0)
                writeByte(0)
            }
        }.toByteArray()
        chunk(data, "IHDR", header)
        chunk(data, "IDAT", byteArrayOf(seed.toByte()))
        chunk(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    fun jpeg(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            // APP0 with a 2-byte payload, to check that segments are skipped.
            write(byteArrayOf(0xFF.toByte(), 0xE0.toByte(), 0x00, 0x04, 0x4A, 0x46))
            write(byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11, 0x08))
            writeShort(height)
            writeShort(width)
            write(ByteArray(10))
            write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        }
        return out.toByteArray()
    }

    private fun chunk(out: DataOutputStream, type: String, content: ByteArray) {
        out.writeInt(content.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(content)
        out.writeInt(CRC32().apply { update(typeBytes); update(content) }.value.toInt())
    }
}
