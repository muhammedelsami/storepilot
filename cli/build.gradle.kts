plugins {
    id("storepilot.kotlin-jvm")
    application
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":core:engine"))
    implementation(libs.clikt)
    runtimeOnly(project(":core:stores:google-play"))

    testImplementation(testFixtures(project(":core:api")))
}

application {
    applicationName = "storepilot"
    mainClass = "io.github.muhammedelsami.storepilot.cli.MainKt"
}

// build/libs/storepilot.jar: the CLI with every dependency, run with `java -jar`. The GitHub Action
// downloads this file from a release, or builds it when it runs from source.
tasks.shadowJar {
    archiveFileName = "storepilot.jar"
    // Store adapters are found through META-INF/services.
    mergeServiceFiles()
}
