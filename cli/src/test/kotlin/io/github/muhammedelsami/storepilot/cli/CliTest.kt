package io.github.muhammedelsami.storepilot.cli

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.github.muhammedelsami.storepilot.api.Release
import io.github.muhammedelsami.storepilot.api.ReleaseStatus
import io.github.muhammedelsami.storepilot.api.Rollout
import io.github.muhammedelsami.storepilot.api.Track
import io.github.muhammedelsami.storepilot.api.fake.FakeStoreProvider
import io.github.muhammedelsami.storepilot.engine.StoreRegistry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CliTest {
    @TempDir
    lateinit var dir: Path

    private val store = FakeStoreProvider()
    private val packageName = "com.example.app"

    @BeforeTest
    fun createBundle() {
        dir.resolve("app-release.aab").writeText("bundle")
    }

    @Test
    fun `prints the version`() {
        val result = run("--version")

        assertEquals(0, result.statusCode)
        assertTrue(result.stdout.startsWith("storepilot "))
    }

    @Test
    fun `publish uploads and prints what changed`() {
        dir.resolve("store/release-notes").createDirectories().resolve("en-US.txt").writeText("Bug fixes.")

        val result = run("publish", "--store", "fake", "--package", packageName, "--artifact", "app-release.aab", "--track", "production", "--rollout", "0.1")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            """
            fake (com.example.app): committed
              upload app-release.aab, version code 1
              track production: version code 1, inProgress 10%, release notes: en-US
                before: no releases

            """.trimIndent(),
            result.stdout,
        )
        assertEquals(ReleaseStatus.IN_PROGRESS, store.state.releases(packageName, Track.PRODUCTION).single().status)
    }

    @Test
    fun `publish reads storepilot yml and the environment`() {
        dir.resolve("storepilot.yml").writeText(
            """
            version: 1
            track: testing
            stores:
              fake:
                packageName: com.example.app
            """.trimIndent(),
        )

        val result = run("publish", "--store", "fake", "--artifact", "app-release.aab", env = mapOf("STOREPILOT_FAKE_ROLLOUT" to "0.5"))

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            listOf(Release(listOf(1), ReleaseStatus.IN_PROGRESS, Rollout(0.5))),
            store.state.releases(packageName, Track.TESTING),
        )
    }

    @Test
    fun `dry run with JSON output`() {
        val result = run("publish", "--store", "fake", "--package", packageName, "--artifact", "app-release.aab", "--dry-run", "--output", "json")

        assertEquals(0, result.statusCode, result.stderr)
        assertEquals(
            """
            {
              "dryRun": true,
              "stores": [
                {
                  "store": "fake",
                  "packageName": "com.example.app",
                  "status": "dryRun",
                  "track": "internal",
                  "versionCodes": [],
                  "changes": [
                    {
                      "type": "upload",
                      "artifact": "${dir.resolve("app-release.aab")}",
                      "versionCode": null
                    },
                    {
                      "type": "release",
                      "track": "internal",
                      "release": {
                        "versionCodes": [],
                        "status": "completed",
                        "rollout": 1.0,
                        "name": null,
                        "releaseNotes": {}
                      },
                      "before": []
                    }
                  ],
                  "warnings": []
                }
              ]
            }

            """.trimIndent(),
            result.stdout,
        )
        assertEquals(0, store.state.commits)
    }

    @Test
    fun `promote, halt, and resume`() {
        store.state.tracks[packageName] = mutableMapOf(Track.TESTING to listOf(Release(listOf(7), ReleaseStatus.COMPLETED)))

        assertEquals(0, run("promote", "--store", "fake", "--package", packageName, "--from", "testing", "--to", "production", "--rollout", "0.2").statusCode)
        assertEquals(0, run("halt", "--store", "fake", "--package", packageName, "--track", "production").statusCode)
        assertEquals(ReleaseStatus.HALTED, store.state.releases(packageName, Track.PRODUCTION).single().status)
        assertEquals(0, run("resume", "--store", "fake", "--package", packageName, "--track", "production").statusCode)
        assertEquals(ReleaseStatus.IN_PROGRESS, store.state.releases(packageName, Track.PRODUCTION).single().status)
    }

    @Test
    fun `config errors exit with 1`() {
        val result = run("publish", "--store", "fake", "--artifact", "app-release.aab")

        assertEquals(1, result.statusCode)
        assertTrue(
            result.stderr.startsWith(
                "error: No package name for store 'fake'. Set 'stores.fake.packageName' in storepilot.yml or " +
                    "STOREPILOT_FAKE_PACKAGE_NAME. Reading it from the artifact failed: app-release.aab is not a readable aab file",
            ),
            result.stderr,
        )
    }

    @Test
    fun `invalid flags exit with 1`() {
        val result = run("publish", "--store", "fake", "--package", packageName, "--artifact", "app-release.aab", "--rollout", "2")

        assertEquals(1, result.statusCode)
        assertTrue("Rollout must be greater than 0.0 and at most 1.0" in result.stderr, result.stderr)
    }

    @Test
    fun `store errors exit with 2`() {
        store.state.commitFailure = "The store is down."

        val result = run("publish", "--store", "fake", "--package", packageName, "--artifact", "app-release.aab")

        assertEquals(2, result.statusCode)
        assertEquals("error: The store is down.\n", result.stderr)
    }

    private fun run(vararg args: String, env: Map<String, String> = emptyMap()): CliktCommandTestResult =
        storePilotCli(CliEnvironment(env, dir) { StoreRegistry(listOf(store)) }).test(args.toList())
}
