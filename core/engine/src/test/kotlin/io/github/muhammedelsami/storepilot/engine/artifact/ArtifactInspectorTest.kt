package io.github.muhammedelsami.storepilot.engine.artifact

import io.github.muhammedelsami.storepilot.api.Artifact
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The manifests in src/test/resources/artifacts come from a bundle and an APK built by AGP 9.4.1. */
class ArtifactInspectorTest {
    @TempDir
    lateinit var dir: Path

    private val sampleId = "io.github.muhammedelsami.storepilot.sample"

    @Test
    fun `reads the package name of a bundle`() {
        val bundle = zip("app.aab", "base/manifest/AndroidManifest.xml" to resource("bundle-manifest.pb"))

        assertEquals(sampleId, ArtifactInspector.packageName(Artifact.of(bundle)))
    }

    @Test
    fun `reads the package name of an APK`() {
        val apk = zip("app.apk", "AndroidManifest.xml" to resource("apk-manifest.bin"))

        assertEquals(sampleId, ArtifactInspector.packageName(Artifact.of(apk)))
    }

    @Test
    fun `reports files that are not bundles`() {
        val notZip = dir.resolve("app.aab").also { it.writeText("not a zip") }
        val noManifest = zip("other.aab", "base/dex/classes.dex" to ByteArray(4))

        assertFailsWith<IllegalArgumentException> { ArtifactInspector.packageName(Artifact.of(notZip)) }
        val e = assertFailsWith<IllegalArgumentException> { ArtifactInspector.packageName(Artifact.of(noManifest)) }
        assertEquals("other.aab has no base/manifest/AndroidManifest.xml.", e.message)
    }

    @Test
    fun `reports a manifest in the wrong format`() {
        val apk = zip("app.apk", "AndroidManifest.xml" to "<manifest package=\"x\" />".toByteArray())

        val e = assertFailsWith<IllegalArgumentException> { ArtifactInspector.packageName(Artifact.of(apk)) }
        assertEquals("The APK manifest is not binary XML.", e.message)
    }

    private fun resource(name: String): ByteArray =
        javaClass.getResourceAsStream("/artifacts/$name")!!.use { it.readBytes() }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): Path {
        val file = dir.resolve(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, bytes) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }
}
