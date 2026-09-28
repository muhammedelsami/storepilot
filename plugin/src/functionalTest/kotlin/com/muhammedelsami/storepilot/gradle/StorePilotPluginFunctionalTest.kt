package com.muhammedelsami.storepilot.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test

class StorePilotPluginFunctionalTest {

    @field:TempDir
    lateinit var projectDir: File

    @Test
    fun `plugin applies on the minimum supported Gradle version`() {
        runBuild(System.getProperty("storepilot.minimumGradleVersion"))
    }

    @Test
    fun `plugin applies on the current Gradle version`() {
        runBuild(gradleVersion = null)
    }

    // The Kotlin DSL build file checks that Gradle's embedded Kotlin compiler can read the plugin's classes.
    private fun runBuild(gradleVersion: String?) {
        projectDir.resolve("settings.gradle.kts").writeText("")
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.muhammedelsami.storepilot")
            }

            storepilot {
            }
            """.trimIndent(),
        )

        GradleRunner.create()
            .forwardOutput()
            .withPluginClasspath()
            .withProjectDir(projectDir)
            .withArguments("help", "--configuration-cache")
            .apply { if (gradleVersion != null) withGradleVersion(gradleVersion) }
            .build()
    }
}
