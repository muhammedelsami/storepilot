# StorePilot

Publish Android releases and Google Play store listings from files in your repository.

- **Release upload**: push an `.aab` or `.apk` to a track, with a staged rollout and release notes.
  Promote, halt, and resume releases.
- **Store listing as files**: titles, descriptions, contact details, screenshots, and graphics per
  language, reviewed in pull requests like code.
- **Checks before publishing**: text limits, image sizes and counts, and lint rules, without network
  access. A diff shows what would change in the store before anything is pushed.
- **Three entry points, one engine**: a Gradle plugin, a command-line tool, and a GitHub Action.

StorePilot 0.x supports Google Play. The design keeps room for other Android stores as adapters.

## Gradle plugin

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.muhammedelsami.storepilot") version "0.1.0"
}

storepilot {
    googlePlay {
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
    }
}
```

```
./gradlew validateListing              # check app/store/ against Google Play's rules, no network
./gradlew diffListing                  # compare app/store/ with the live listing
./gradlew publishReleaseBundle         # upload the release bundle to the internal track
./gradlew publishRelease               # upload the bundle and push the listing in one edit
```

Needs Gradle 8.10 or later, the Android Gradle plugin 8.5 or later, and JDK 17.
`samples/android-gradle` is a complete example.

## GitHub Action

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: 17

- uses: muhammedelsami/storepilot@v0
  with:
    artifact: app/build/outputs/bundle/release/*.aab
    metadata-dir: app/store
    track: production
    rollout: "0.1"
    google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

Commands: `publish`, `promote`, `halt`, `resume`, `listing-push`, `listing-diff`, and
`listing-validate`. The action writes a job summary, works without a key after
`google-github-actions/auth`, and runs on Linux, macOS, and Windows runners. See
`samples/github-workflow` for complete workflows and `action.yml` for every input.

## Command line

Download `storepilot-<version>.zip` (or `storepilot.jar`) from the
[releases](https://github.com/muhammedelsami/storepilot/releases) and run it with Java 17 or later:

```
storepilot publish --artifact app-release.aab --track production --rollout 0.1
storepilot listing validate
storepilot listing diff --exit-code
storepilot listing pull
```

Settings come from flags, `STOREPILOT_*` environment variables, and `storepilot.yml`, in that order.
Every command has `--output json` and `--dry-run` where it changes something.

## Metadata directory

```
store/
├── details.yml                        # default-language, contact-email, contact-website, contact-phone
├── release-notes/en-US.txt
└── listing/en-US/
    ├── title.txt
    ├── short-description.txt
    ├── full-description.txt
    └── graphics/
        ├── icon.png
        ├── feature-graphic.png
        └── phone-screenshots/01.png, 02.png, …
```

`storepilot listing pull` (or `./gradlew pullListing`) creates it from an app's live listing.

## Credentials

Create a service account in Google Cloud, give it access to the app in the Play Console, and pass its
JSON key through `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`, the Gradle DSL, or the action input.
Without a key, StorePilot uses Application Default Credentials. Keys never appear in logs or output.

## More

- `docs/design.md`: design, configuration reference, and decisions.
- `RELEASING.md`: how a release is made.

Licensed under the Apache License 2.0.
