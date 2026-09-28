pluginManagement {
    includeBuild("build-logic")
}

plugins {
    // Downloads the JDK 17 toolchain when the machine does not have one, for example when the GitHub
    // Action builds the CLI from source on a runner with a newer JDK only.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "storepilot"

include(
    ":core:api",
    ":core:engine",
    ":core:stores:google-play",
    ":plugin",
    ":cli",
)
