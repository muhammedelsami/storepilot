plugins {
    id("storepilot.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core:engine"))
    implementation(libs.clikt)
    runtimeOnly(project(":core:stores:google-play"))

    testImplementation(testFixtures(project(":core:api")))
}

application {
    applicationName = "storepilot"
    mainClass = "com.muhammedelsami.storepilot.cli.MainKt"
}
