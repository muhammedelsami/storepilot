// Google Play adapter. Depends only on core:api and is found through ServiceLoader.

plugins {
    id("storepilot.embedded-kotlin")
}

dependencies {
    implementation(project(":core:api"))
}
