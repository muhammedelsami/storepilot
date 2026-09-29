package io.github.muhammedelsami.storepilot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Android application builds with the lowest supported and the newest AGP. The plugin comes from the
 * local test repository, next to AGP, as in a user's build. Skipped without an Android SDK, except on CI.
 */
class AndroidFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `registers variant tasks with the lowest supported AGP`() {
        checkVariantTasks(
            agpVersion = System.getProperty("storepilot.agpMin"),
            gradleVersion = System.getProperty("storepilot.minimumGradleVersion"),
            compileSdk = 34,
        )
    }

    @Test
    fun `registers variant tasks with the newest AGP`() {
        checkVariantTasks(
            agpVersion = System.getProperty("storepilot.agpLatest"),
            gradleVersion = System.getProperty("storepilot.newestGradleVersion"),
            compileSdk = 36,
        )
    }

    private fun checkVariantTasks(agpVersion: String, gradleVersion: String, compileSdk: Int) {
        val sdk = androidSdk()
        // CI runners have an SDK, so a missing one there is an error, not a reason to skip.
        check(sdk != null || System.getenv("CI") == null) { "No Android SDK found on CI; set ANDROID_HOME." }
        assumeTrue(sdk != null, "No Android SDK found; set ANDROID_HOME to run this test.")
        writeProject(agpVersion, sdk!!, compileSdk)

        val runner = GradleRunner.create()
            .forwardOutput()
            .withProjectDir(projectDir)
            .withGradleVersion(gradleVersion)
            .withArguments("tasks", "--group=StorePilot", "--configuration-cache", "--stacktrace")
        val output = runner.build().output

        for (task in listOf("publishReleaseBundle", "publishReleaseApk", "publishRelease", "promoteReleaseRelease", "haltReleaseRelease")) {
            assertTrue(task in output, "$task missing:\n$output")
        }
        assertFalse("publishDebugBundle" in output, output)
        assertFalse("publishArtifact" in output, output)

        // The publish task depends on the tasks that build and sign the bundle.
        val dryRun = runner.withArguments("publishReleaseBundle", "--dry-run", "--configuration-cache").build().output
        assertTrue(":signReleaseBundle SKIPPED" in dryRun, dryRun)
    }

    private fun writeProject(agpVersion: String, sdk: File, compileSdk: Int) {
        val repository = File(System.getProperty("storepilot.repository")).toURI()
        val version = System.getProperty("storepilot.version")
        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories {
                    maven("$repository")
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositories {
                    maven("$repository")
                    google()
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                id("com.android.application") version "$agpVersion"
                id("io.github.muhammedelsami.storepilot") version "$version"
            }

            android {
                namespace = "com.example.app"
                compileSdk = $compileSdk
                defaultConfig {
                    applicationId = "com.example.app"
                    minSdk = 24
                    versionCode = 1
                    versionName = "1.0"
                }
            }

            storepilot {
                googlePlay {
                    serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
                }
            }
            """.trimIndent(),
        )
        write("src/main/AndroidManifest.xml", "<manifest />\n")
        write("local.properties", "sdk.dir=${sdk.absolutePath.replace("\\", "\\\\")}\n")
    }

    private fun write(path: String, text: String) {
        File(projectDir, path).apply { parentFile.mkdirs() }.writeText(text)
    }

    private fun androidSdk(): File? {
        val home = System.getProperty("user.home")
        return listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "$home/Library/Android/sdk",
            "$home/Android/Sdk",
        ).map(::File).firstOrNull { it.isDirectory }
    }
}
