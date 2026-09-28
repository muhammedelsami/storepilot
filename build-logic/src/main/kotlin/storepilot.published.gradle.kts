// Publishing for the modules users download. Milestone 7 adds the public repositories. For now the only
// target is a local repository that the plugin's functional tests resolve the plugin from, the same way
// a user's build does.

plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(rootDir.resolve("build/functional-test-repo"))
        }
    }
}

// The plugin module publishes through java-gradle-plugin. Libraries publish their java component.
pluginManager.withPlugin("java-library") {
    if (!pluginManager.hasPlugin("java-gradle-plugin")) {
        publishing.publications.register<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
