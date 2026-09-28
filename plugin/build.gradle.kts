import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("storepilot.kotlin-jvm")
    `java-gradle-plugin`
}

// Lowest Gradle version the plugin supports. The functional tests run against it and the current one.
val minimumGradleVersion = "8.10"

kotlin {
    compilerOptions {
        // The plugin runs on the Kotlin stdlib embedded in Gradle: 1.9.24 in Gradle 8.10. Its Kotlin DSL
        // compiler reads metadata up to language version 2.0.
        apiVersion = KotlinVersion.KOTLIN_1_9
        languageVersion = KotlinVersion.KOTLIN_2_0
        // Language version 2.0 is deprecated in Kotlin 2.3. Keep it until Gradle 8 support is dropped.
        freeCompilerArgs.add("-Xsuppress-version-warnings")
    }
}

dependencies {
    implementation(project(":core:engine"))
    runtimeOnly(project(":core:stores:google-play"))
}

gradlePlugin {
    plugins {
        create("storepilot") {
            id = "com.muhammedelsami.storepilot"
            implementationClass = "com.muhammedelsami.storepilot.gradle.StorePilotPlugin"
        }
    }
}

val functionalTestSourceSet = sourceSets.create("functionalTest")

configurations["functionalTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["functionalTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

val functionalTest by tasks.registering(Test::class) {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    systemProperty("storepilot.minimumGradleVersion", minimumGradleVersion)
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.check {
    dependsOn(functionalTest)
}
