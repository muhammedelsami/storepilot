// For modules whose code runs inside Gradle: the plugin and everything it loads (core, store adapters).

import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("storepilot.kotlin-jvm")
}

kotlin {
    compilerOptions {
        // The code runs on the Kotlin stdlib embedded in Gradle: 1.9.24 in Gradle 8.10, the minimum
        // supported version. Its Kotlin DSL compiler reads metadata up to language version 2.0.
        apiVersion = KotlinVersion.KOTLIN_1_9
        languageVersion = KotlinVersion.KOTLIN_2_0
        // Language version 2.0 is deprecated in Kotlin 2.3. Keep it until Gradle 8 support is dropped.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}
