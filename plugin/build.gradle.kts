plugins {
    id("storepilot.embedded-kotlin")
    id("storepilot.published")
    `java-gradle-plugin`
}

// Lowest Gradle version the plugin supports. The functional tests run against it and the current one.
val minimumGradleVersion = "8.10"

// Newest Gradle, for the test with the newest AGP (AGP 9.4 needs Gradle 9.6 or later).
val newestGradleVersion = "9.8.0"

dependencies {
    implementation(project(":core:engine"))
    runtimeOnly(project(":core:stores:google-play"))
    compileOnly(libs.agp.api)

    testImplementation(testFixtures(project(":core:api")))
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

// The in-memory fake store, so that builds under test can publish without a network.
val functionalTestStores = configurations.create("functionalTestStores")
dependencies {
    functionalTestStores(testFixtures(project(":core:api")))
}
tasks.pluginUnderTestMetadata {
    pluginClasspath.from(functionalTestStores)
}

// Android builds under test resolve the plugin from this repository, next to the Android plugin.
val publishedModules = listOf(":core:api", ":core:engine", ":core:stores:google-play", ":plugin")

val functionalTest = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTestSourceSet.output.classesDirs
    classpath = functionalTestSourceSet.runtimeClasspath
    systemProperty("storepilot.minimumGradleVersion", minimumGradleVersion)
    systemProperty("storepilot.newestGradleVersion", newestGradleVersion)
    systemProperty("storepilot.version", project.version.toString())
    systemProperty("storepilot.repository", rootDir.resolve("build/functional-test-repo").absolutePath)
    systemProperty("storepilot.agpMin", libs.versions.agp.min.get())
    systemProperty("storepilot.agpLatest", libs.versions.agp.latest.get())
    dependsOn(publishedModules.map { "$it:publishAllPublicationsToFunctionalTestRepository" })
}

gradlePlugin.testSourceSets.add(functionalTestSourceSet)

tasks.check {
    dependsOn(functionalTest)
}
