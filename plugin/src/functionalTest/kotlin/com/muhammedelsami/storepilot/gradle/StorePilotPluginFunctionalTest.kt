package com.muhammedelsami.storepilot.gradle

import com.muhammedelsami.storepilot.api.StoreId
import com.muhammedelsami.storepilot.api.Track
import com.muhammedelsami.storepilot.engine.config.ConfigParser
import com.muhammedelsami.storepilot.engine.config.ListingConfig
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Builds without the Android plugin. They use the in-memory fake store, so nothing leaves the machine. */
class StorePilotPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    private val minimumGradle: String = System.getProperty("storepilot.minimumGradleVersion")

    @Test
    fun `publishes on the minimum supported Gradle version`() {
        publishWithConfigurationCache(minimumGradle)
    }

    @Test
    fun `publishes on the current Gradle version`() {
        publishWithConfigurationCache(gradleVersion = null)
    }

    private fun publishWithConfigurationCache(gradleVersion: String?) {
        writeBuild()
        File(projectDir, "app.aab").writeText("bundle")

        val first = run(gradleVersion, "publishArtifact")
        assertTrue("fake (com.example.app): committed" in first.output, first.output)
        assertTrue("track production: version code 1, inProgress 10%" in first.output, first.output)

        val second = run(gradleVersion, "publishArtifact")
        assertTrue("Reusing configuration cache." in second.output, second.output)
        assertTrue("fake (com.example.app): committed" in second.output, second.output)

        val dryRun = run(gradleVersion, "publishArtifact", "-Pstorepilot.dryRun=true")
        assertTrue("fake (com.example.app): dry run, nothing committed" in dryRun.output, dryRun.output)
    }

    @Test
    fun `validateListing is part of check and is up to date when nothing changed`() {
        writeBuild()
        writeFile("store/listing/en-US/title.txt", "A title that is much too long for the store\n")

        val failed = runner("check").buildAndFail()
        assertTrue("title has 43 characters; Fake Store allows 30." in failed.output, failed.output)

        writeFile("store/listing/en-US/title.txt", "Pilot\n")
        assertEquals(TaskOutcome.SUCCESS, runner("check").build().task(":validateListing")?.outcome)
        assertEquals(TaskOutcome.UP_TO_DATE, runner("check").build().task(":validateListing")?.outcome)
    }

    @Test
    fun `task options reach the engine`() {
        writeBuild()

        val result = runner("promoteRelease", "--from=testing", "--to=production").buildAndFail()

        assertTrue("Track 'testing' has no release to promote." in result.output, result.output)
    }

    @Test
    fun `exports the settings for the CLI`() {
        writeBuild(
            extra = """
                storepilot {
                    listing { replaceScreenshots = false }
                    aso { disable("empty-locale") }
                    googlePlay { inAppUpdatePriority = 3 }
                }
            """,
        )

        runner("exportStorepilotConfig").build()

        val config = ConfigParser.load(File(projectDir, "storepilot.yml").toPath())
        val store = config.stores.getValue(StoreId("fake"))
        assertEquals("store", config.metadataDir)
        assertEquals(ListingConfig(graphics = true, replaceScreenshots = false), config.listing)
        assertEquals(listOf("empty-locale"), config.aso.disable)
        assertEquals("com.example.app", store.packageName)
        assertEquals(Track.PRODUCTION, store.track)
        assertEquals(mapOf("inAppUpdatePriority" to "3"), store.options)
    }

    @Test
    fun `credentials only accept providers`() {
        writeBuild(extra = """storepilot { googlePlay { serviceAccountJson = "{ }" } }""")

        val result = runner("help").buildAndFail()

        assertTrue("Provider<String>" in result.output, result.output)
    }

    private fun writeBuild(extra: String = "") {
        writeFile("settings.gradle.kts", "")
        writeFile(
            "build.gradle.kts",
            """
            plugins {
                base
                id("com.muhammedelsami.storepilot")
            }

            storepilot {
                packageName = "com.example.app"
                artifact = layout.projectDirectory.file("app.aab")
                track = "production"
                rollout = 0.1
                googlePlay {
                    serviceAccountJson = providers.environmentVariable("NOT_SET_IN_TESTS")
                }
            }

            // The fake store from the core test fixtures is on the plugin classpath of these builds.
            tasks.withType<com.muhammedelsami.storepilot.gradle.StorePilotTask>().configureEach {
                store.set("fake")
            }
            """.trimIndent() + "\n" + extra.trimIndent(),
        )
    }

    private fun writeFile(path: String, text: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun runner(vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withProjectDir(projectDir)
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")

    private fun run(gradleVersion: String?, vararg arguments: String): BuildResult =
        runner(*arguments).apply { if (gradleVersion != null) withGradleVersion(gradleVersion) }.build()
}
