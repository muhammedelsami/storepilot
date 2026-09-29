package io.github.muhammedelsami.storepilot.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.muhammedelsami.storepilot.api.ListingField
import io.github.muhammedelsami.storepilot.api.LocaleTag
import io.github.muhammedelsami.storepilot.api.fake.FakeStoreProvider
import io.github.muhammedelsami.storepilot.engine.StoreRegistry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListingCliTest {
    @TempDir
    lateinit var dir: Path

    private val store = FakeStoreProvider()
    private val packageName = "com.example.app"
    private val en = LocaleTag("en-US")

    @Test
    fun `validate needs no package name and prints the coverage`() {
        write("store/listing/en-US/title.txt", "Pilot\n")
        write("store/listing/tr-TR/title.txt", "Pilot TR\n")
        write("store/listing/tr-TR/short-description.txt", "Kısa\n")

        val result = run("listing", "validate", "--store", "fake")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            """
            fake: 0 errors, 0 warnings
              Locale coverage:
                locale  title  short-description  full-description  video-url
                en-US   yes    -                  -                 -
                tr-TR   yes    yes                -                 -

            """.trimIndent(),
            result.stdout,
        )
    }

    @Test
    fun `validate exits with 1 on errors`() {
        write("store/listing/en-US/title.txt", "x".repeat(31))

        val result = run("listing", "validate", "--store", "fake", "--output", "json")

        assertEquals(1, result.statusCode)
        assertTrue("\"errors\": 1" in result.stdout, result.stdout)
    }

    @Test
    fun `push, then diff finds nothing`() {
        write("store/listing/en-US/title.txt", "Pilot\n")

        val push = run("listing", "push", "--store", "fake", "--package", packageName)
        assertEquals(0, push.statusCode, push.stderr)
        assertEquals(mapOf(ListingField.TITLE to "Pilot"), store.state.listings[packageName]?.get(en))

        val diff = run("listing", "diff", "--store", "fake", "--package", packageName, "--exit-code")
        assertEquals(0, diff.statusCode, diff.stderr)
        assertEquals("fake (com.example.app): no changes\n  en-US title: unchanged\n", diff.stdout)
    }

    @Test
    fun `diff exits with 3 when asked and the listing differs`() {
        write("store/listing/en-US/title.txt", "Pilot\n")

        val result = run("listing", "diff", "--store", "fake", "--package", packageName, "--exit-code", "--output", "json")

        assertEquals(3, result.statusCode)
        assertTrue("\"listingChanged\": true" in result.stdout, result.stdout)
        assertTrue("\"status\": \"added\"" in result.stdout, result.stdout)
    }

    @Test
    fun `writes a Markdown summary`() {
        write("store/listing/en-US/title.txt", "Pilot\n")

        run("listing", "validate", "--store", "fake", "--summary-file", "summary.md")
        run("listing", "diff", "--store", "fake", "--package", packageName, "--summary-file", "summary.md")

        assertEquals(
            """
            ### StorePilot listing check

            **fake** · 0 error(s), 0 warning(s)

            | Locale | title | short-description | full-description | video-url |
            |---|---|---|---|---|
            | en-US | ✅ | – | – | – |

            ### StorePilot listing diff

            **fake** · `com.example.app` · changes

            | Locale | Item | Status |
            |---|---|---|
            | en-US | title | added |


            """.trimIndent(),
            dir.resolve("summary.md").readText(),
        )
    }

    @Test
    fun `pull writes the listing`() {
        store.state.listings[packageName] = mutableMapOf(en to mapOf(ListingField.TITLE to "Pilot"))

        val result = run("listing", "pull", "--store", "fake", "--package", packageName)

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals("Pilot\n", dir.resolve("store/listing/en-US/title.txt").readText())
    }

    @Test
    fun `publish with listing`() {
        write("store/listing/en-US/title.txt", "Pilot\n")
        dir.resolve("app.aab").writeText("bundle")

        val result = run("publish", "--store", "fake", "--package", packageName, "--artifact", "app.aab", "--with-listing")

        assertEquals(0, result.statusCode, result.stderr)
        assertTrue("  listing en-US title: \"Pilot\" (new)" in result.stdout, result.stdout)
        assertEquals(1, store.state.commits)
    }

    private fun write(path: String, text: String) {
        dir.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun run(vararg args: String): CliktCommandTestResult =
        storePilotCli(CliEnvironment(emptyMap(), dir) { StoreRegistry(listOf(store)) }).test(args.toList())
}
