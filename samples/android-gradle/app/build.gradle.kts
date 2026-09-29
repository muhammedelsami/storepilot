plugins {
    id("com.android.application") version "9.4.1"
    id("io.github.muhammedelsami.storepilot")
}

android {
    namespace = "io.github.muhammedelsami.storepilot.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.muhammedelsami.storepilot.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

storepilot {
    // store/ next to this file is the default metadata directory.
    track = "internal"

    aso {
        warningsAsErrors = true
    }

    googlePlay {
        // Never write the key into the build script.
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
    }
}
