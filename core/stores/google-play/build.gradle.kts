// Google Play adapter. Depends only on core:api and is found through ServiceLoader.

plugins {
    id("storepilot.kotlin-jvm")
}

dependencies {
    implementation(project(":core:api"))
}
