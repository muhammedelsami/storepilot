// Store-agnostic model and the StoreProvider SPI. No Gradle or Android dependencies.

plugins {
    id("storepilot.embedded-kotlin")
    id("storepilot.published-library")
    `java-test-fixtures`
}

mavenPublishing {
    coordinates(artifactId = "storepilot-core-api")
    pom {
        name = "StorePilot core API"
        description = "Store-agnostic model and store adapter SPI of StorePilot, which publishes Android apps and store listings."
    }
}

// The test fixtures (the in-memory fake store) are for this repository's tests only.
(components["java"] as AdhocComponentWithVariants).apply {
    withVariantsFromConfiguration(configurations["testFixturesApiElements"]) { skip() }
    withVariantsFromConfiguration(configurations["testFixturesRuntimeElements"]) { skip() }
    configurations.findByName("testFixturesSourcesElements")?.let { withVariantsFromConfiguration(it) { skip() } }
}
