// Publishing for the modules users download: the core libraries go to Maven Central
// (storepilot.published-library), the Gradle plugin to the Gradle Plugin Portal. Every build can also
// publish to a local repository that the plugin's functional tests resolve the plugin from, the same
// way a user's build does.

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
    publications.withType<MavenPublication>().configureEach {
        pom {
            url = "https://github.com/muhammedelsami/storepilot"
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id = "muhammedelsami"
                    name = "Muhammed Elşami"
                    url = "https://github.com/muhammedelsami"
                }
            }
            scm {
                url = "https://github.com/muhammedelsami/storepilot"
                connection = "scm:git:https://github.com/muhammedelsami/storepilot.git"
                developerConnection = "scm:git:ssh://git@github.com/muhammedelsami/storepilot.git"
            }
        }
    }
}
