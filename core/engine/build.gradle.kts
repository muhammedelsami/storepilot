// Config loading, validation, diff, planning, execution, and reporting.

plugins {
    id("storepilot.embedded-kotlin")
    id("storepilot.published-library")
}

dependencies {
    api(project(":core:api"))
    implementation(libs.snakeyaml.engine)

    testImplementation(testFixtures(project(":core:api")))
}

mavenPublishing {
    coordinates(artifactId = "storepilot-core-engine")
    pom {
        name = "StorePilot engine"
        description = "Config, validation, listing diff, and release operations of StorePilot."
    }
}
