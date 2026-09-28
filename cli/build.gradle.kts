plugins {
    id("storepilot.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core:engine"))
    runtimeOnly(project(":core:stores:google-play"))
}

application {
    applicationName = "storepilot"
    mainClass = "com.muhammedelsami.storepilot.cli.MainKt"
}
