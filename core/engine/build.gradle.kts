// Config loading, validation, diff, planning, execution, and reporting.

plugins {
    id("storepilot.embedded-kotlin")
    id("storepilot.published")
}

dependencies {
    api(project(":core:api"))
    implementation(libs.snakeyaml.engine)

    testImplementation(testFixtures(project(":core:api")))
}
