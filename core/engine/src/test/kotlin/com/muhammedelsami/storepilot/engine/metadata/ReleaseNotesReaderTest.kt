package com.muhammedelsami.storepilot.engine.metadata

import com.muhammedelsami.storepilot.api.LocaleTag
import com.muhammedelsami.storepilot.api.Problem.Severity.ERROR
import com.muhammedelsami.storepilot.api.Problem.Severity.WARNING
import com.muhammedelsami.storepilot.api.StoreId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseNotesReaderTest {
    @TempDir
    lateinit var metadataDir: Path

    private val googlePlay = StoreId("google-play")
    private val otherStore = StoreId("other-store")
    private val reader = ReleaseNotesReader(setOf(googlePlay, otherStore))

    @Test
    fun `store-specific files win over default files`() {
        write("release-notes/en-US.txt", "Bug fixes.\r\nFaster start.\n")
        write("release-notes/tr-TR.txt", "Hata düzeltmeleri.\n")
        write("release-notes/google-play/en-US.txt", "Play notes.")
        write("release-notes/other-store/tr-TR.txt", "Other notes.")

        val result = reader.read(metadataDir, googlePlay)

        assertEquals(
            mapOf(LocaleTag("en-US") to "Play notes.", LocaleTag("tr-TR") to "Hata düzeltmeleri."),
            result.notes,
        )
        assertEquals(emptyList(), result.problems)
    }

    @Test
    fun `line endings are normalized and text is trimmed`() {
        write("release-notes/en-US.txt", "\nBug fixes.\r\nFaster start.\r\n\r\n")

        assertEquals("Bug fixes.\nFaster start.", reader.read(metadataDir, googlePlay).notes[LocaleTag("en-US")])
    }

    @Test
    fun `no release notes directory means no notes`() {
        val result = reader.read(metadataDir, googlePlay)

        assertTrue(result.notes.isEmpty())
        assertTrue(result.problems.isEmpty())
    }

    @Test
    fun `reports bad file names, empty files, and unknown store directories`() {
        write("release-notes/.DS_Store", "")
        write("release-notes/en-US.md", "text")
        write("release-notes/en_US.txt", "text")
        write("release-notes/de-DE.txt", "  \n")
        write("release-notes/googleplay/en-US.txt", "text")

        val problems = reader.read(metadataDir, googlePlay).problems.map {
            it.severity to metadataDir.relativize(Path.of(it.source!!)).invariantSeparatorsPathString
        }

        assertEquals(
            listOf(
                WARNING to "release-notes/de-DE.txt",
                ERROR to "release-notes/en-US.md",
                ERROR to "release-notes/en_US.txt",
                WARNING to "release-notes/googleplay",
            ),
            problems,
        )
    }

    private fun write(path: String, text: String) {
        val file = metadataDir.resolve(path)
        file.parent.createDirectories()
        file.writeText(text)
    }
}
