package io.github.muhammedelsami.storepilot.engine.artifact

import io.github.muhammedelsami.storepilot.api.Artifact
import io.github.muhammedelsami.storepilot.api.ArtifactType
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Reads the package name from an Android App Bundle or APK, without Android tools. A bundle keeps its
 * manifest as an aapt2 protobuf `XmlNode`; an APK keeps it as Android binary XML.
 */
object ArtifactInspector {
    /** Throws [IllegalArgumentException] when the file is not a readable bundle or APK. */
    fun packageName(artifact: Artifact): String {
        val entryName = when (artifact.type) {
            ArtifactType.BUNDLE -> "base/manifest/AndroidManifest.xml"
            ArtifactType.APK -> "AndroidManifest.xml"
        }
        val manifest = try {
            ZipFile(artifact.path.toFile()).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: throw IllegalArgumentException("${artifact.path.fileName} has no $entryName.")
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (e: IOException) {
            throw IllegalArgumentException("${artifact.path.fileName} is not a readable ${artifact.type.extension} file: ${e.message}", e)
        }
        return try {
            when (artifact.type) {
                ArtifactType.BUNDLE -> ProtoManifest.packageName(manifest)
                ArtifactType.APK -> BinaryXmlManifest.packageName(manifest)
            }
        } catch (e: IndexOutOfBoundsException) {
            throw IllegalArgumentException("The manifest in ${artifact.path.fileName} is broken.", e)
        }
    }
}

/** The `package` attribute of the root element of an aapt2 protobuf manifest (Resources.proto). */
internal object ProtoManifest {
    // XmlNode.element = 1; XmlElement.attribute = 4; XmlAttribute.namespace_uri = 1, name = 2, value = 3.
    fun packageName(node: ByteArray): String {
        val element = fields(node).firstOrNull { it.number == 1 }?.bytes
            ?: throw IllegalArgumentException("The bundle manifest has no root element.")
        for (attribute in fields(element).filter { it.number == 4 }) {
            val values = fields(attribute.bytes!!)
            val namespace = values.firstOrNull { it.number == 1 }?.string.orEmpty()
            val name = values.firstOrNull { it.number == 2 }?.string
            if (namespace.isEmpty() && name == "package") {
                return values.firstOrNull { it.number == 3 }?.string
                    ?: throw IllegalArgumentException("The bundle manifest has an empty package attribute.")
            }
        }
        throw IllegalArgumentException("The bundle manifest has no package attribute.")
    }

    class Field(val number: Int, val bytes: ByteArray?) {
        val string: String?
            get() = bytes?.toString(Charsets.UTF_8)
    }

    /** The fields of one message. Only length-delimited values are kept. */
    fun fields(message: ByteArray): List<Field> {
        val fields = mutableListOf<Field>()
        var position = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = message[position++].toInt()
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "Broken varint in the bundle manifest." }
            }
        }
        while (position < message.size) {
            val key = varint()
            val number = (key ushr 3).toInt()
            when (val wireType = (key and 7).toInt()) {
                0 -> varint()
                1 -> position += 8
                2 -> {
                    val length = varint().toInt()
                    require(length >= 0 && position + length <= message.size) { "Broken length in the bundle manifest." }
                    fields += Field(number, message.copyOfRange(position, position + length))
                    position += length
                }
                5 -> position += 4
                else -> throw IllegalArgumentException("Unsupported protobuf wire type $wireType in the bundle manifest.")
            }
        }
        return fields
    }
}

/** The `package` attribute of the `manifest` element in Android binary XML. */
internal object BinaryXmlManifest {
    private const val XML_TYPE = 0x0003
    private const val STRING_POOL_TYPE = 0x0001
    private const val START_ELEMENT_TYPE = 0x0102
    private const val UTF8_FLAG = 0x100
    private const val STRING_VALUE_TYPE = 0x03

    fun packageName(bytes: ByteArray): String {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(buffer.u16(0) == XML_TYPE) { "The APK manifest is not binary XML." }
        var strings = emptyList<String>()
        var offset = buffer.u16(2)
        while (offset + 8 <= bytes.size) {
            val size = buffer.getInt(offset + 4)
            require(size > 0) { "Broken chunk in the APK manifest." }
            when (buffer.u16(offset)) {
                STRING_POOL_TYPE -> strings = stringPool(buffer, offset)
                START_ELEMENT_TYPE -> if (strings.getOrNull(buffer.getInt(offset + 20)) == "manifest") {
                    return packageAttribute(buffer, offset, strings)
                }
            }
            offset += size
        }
        throw IllegalArgumentException("The APK manifest has no manifest element.")
    }

    // A start element: chunk header (8), line (4), comment (4), then namespace, name, attributeStart,
    // attributeSize, and attributeCount. attributeStart counts from the namespace field.
    private fun packageAttribute(buffer: ByteBuffer, element: Int, strings: List<String>): String {
        val first = element + 16 + buffer.u16(element + 24)
        val size = buffer.u16(element + 26)
        repeat(buffer.u16(element + 28)) { index ->
            val attribute = first + index * size
            if (buffer.getInt(attribute) == -1 && strings.getOrNull(buffer.getInt(attribute + 4)) == "package") {
                val raw = buffer.getInt(attribute + 8)
                val data = buffer.getInt(attribute + 16)
                val value = if (raw >= 0) strings.getOrNull(raw) else strings.getOrNull(data).takeIf {
                    buffer.get(attribute + 15).toInt() == STRING_VALUE_TYPE
                }
                return value ?: throw IllegalArgumentException("The APK manifest has an unreadable package attribute.")
            }
        }
        throw IllegalArgumentException("The APK manifest has no package attribute.")
    }

    private fun stringPool(buffer: ByteBuffer, pool: Int): List<String> {
        val count = buffer.getInt(pool + 8)
        val utf8 = buffer.getInt(pool + 16) and UTF8_FLAG != 0
        val stringsStart = pool + buffer.getInt(pool + 20)
        val offsets = pool + buffer.u16(pool + 2)
        return List(count) { index ->
            val start = stringsStart + buffer.getInt(offsets + index * 4)
            if (utf8) utf8String(buffer, start) else utf16String(buffer, start)
        }
    }

    private fun utf16String(buffer: ByteBuffer, start: Int): String {
        var length = buffer.u16(start)
        var chars = start + 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7FFF) shl 16) or buffer.u16(start + 2)
            chars += 2
        }
        return String(CharArray(length) { buffer.getChar(chars + it * 2) })
    }

    private fun utf8String(buffer: ByteBuffer, start: Int): String {
        var position = start
        fun length(): Int {
            val first = buffer.get(position++).toInt() and 0xFF
            return if (first and 0x80 == 0) first else ((first and 0x7F) shl 8) or (buffer.get(position++).toInt() and 0xFF)
        }
        length() // length in characters
        val bytes = ByteArray(length()) { buffer.get(position + it) }
        return bytes.toString(Charsets.UTF_8)
    }

    private fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and 0xFFFF
}
