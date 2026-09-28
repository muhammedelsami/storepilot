// Shared setup for every Kotlin/JVM module in the build.

plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    // JDK 17 is the minimum supported runtime for the plugin, the CLI, and the action.
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
