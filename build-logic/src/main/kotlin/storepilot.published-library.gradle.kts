// A published library: its java component, in the repositories from storepilot.published.

plugins {
    `java-library`
    id("storepilot.published")
}

publishing {
    publications {
        register<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
