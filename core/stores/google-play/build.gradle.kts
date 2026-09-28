// Google Play adapter. Depends only on core:api and is found through ServiceLoader.

plugins {
    id("storepilot.embedded-kotlin")
    id("storepilot.published")
}

dependencies {
    implementation(project(":core:api"))
    implementation(libs.google.androidpublisher)
    // Newer than the one androidpublisher asks for, so that every google-http-client module is 2.x.
    implementation(libs.google.api.client)
    implementation(libs.google.auth)
}
