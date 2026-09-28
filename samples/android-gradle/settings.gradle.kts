// A standalone Android build that uses the StorePilot plugin from this repository.

pluginManagement {
    // Builds the plugin from the repository root, so the sample always uses the current code.
    includeBuild("../..")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "storepilot-sample"
include(":app")
