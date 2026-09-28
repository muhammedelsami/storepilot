pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
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
