# Quick start

This page takes an existing app from nothing to a dry run against its real Play listing. It takes
about ten minutes, most of it in the Google Cloud and Play consoles.

## Before you start

- An app that already exists in the Play Console. The Play API cannot create apps, and the first
  release of a new app has to be uploaded in the Play Console.
- A Google Play service account key. [Connect Google Play](/guide/google-play-setup) shows how to
  create one; keep the JSON file outside the repository.
- Java 17 or later.

## 1. Add StorePilot

::: code-group

```kotlin [Gradle plugin]
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.muhammedelsami.storepilot") version "0.1.1"
}

storepilot {
    googlePlay {
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
    }
}
```

```sh [Command line]
# Download storepilot-0.1.1.zip from
# https://github.com/muhammedelsami/storepilot/releases and unzip it.
unzip storepilot-0.1.1.zip
export PATH="$PWD/storepilot-shadow-0.1.1/bin:$PATH"
storepilot --version
```

:::

Then give StorePilot the key:

::: code-group

```sh [Gradle plugin]
export PLAY_SERVICE_ACCOUNT_JSON="$(cat ~/keys/play-publisher.json)"
```

```sh [Command line]
export STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$(cat ~/keys/play-publisher.json)"
```

:::

## 2. Pull your current listing

Start from what is live instead of typing it again. This writes `store/` with `details.yml`, the
text of every language, and all images.

::: code-group

```sh [Gradle plugin]
./gradlew :app:pullListing
```

```sh [Command line]
storepilot listing pull --package com.example.notes --metadata-dir app/store
```

:::

The Gradle plugin puts the directory next to the module's build file (`app/store/`) and takes the
package name from the release variant.

## 3. Check it

::: code-group

```sh [Gradle plugin]
./gradlew :app:validateListing
```

```sh [Command line]
storepilot listing validate --metadata-dir app/store
```

:::

<div class="shot-frame"><img class="shot" src="/screenshots/validate-ok.svg" alt="Output of storepilot listing validate: 0 errors, 0 warnings, and a locale coverage table for en-US and tr-TR."></div>

Listings that are live today can still break a rule that Play enforces for new changes, such as a
title that grew too long. Fix what the check reports before you push anything.

## 4. Change something and look at the diff

Edit `app/store/listing/en-US/short-description.txt`, then compare with the store:

::: code-group

```sh [Gradle plugin]
./gradlew :app:diffListing
```

```sh [Command line]
storepilot listing diff --package com.example.notes --metadata-dir app/store
```

:::

<div class="shot-frame"><img class="shot" src="/screenshots/diff.svg" alt="Output of storepilot listing diff: each details field, text field, and graphic type with its status, for example short-description changed and de-DE title remote only."></div>

`remote only` means the store has something the repository does not. StorePilot leaves it alone.

## 5. Dry run a release

Build the bundle, then let StorePilot plan the release without committing it:

::: code-group

```sh [Gradle plugin]
./gradlew :app:publishReleaseBundle -Pstorepilot.dryRun=true
```

```sh [Command line]
storepilot publish --artifact app/build/outputs/bundle/release/app-release.aab \
  --track internal --dry-run
```

:::

A dry run reads the tracks and the listing but discards the Play edit, so nothing changes in the
store. Remove the dry-run flag when the plan looks right.

## Where to go next

- Publish from CI: [GitHub Action](/guide/github-action).
- Staged rollouts, promotion, and halting: [Releases and tracks](/guide/releases).
- Every setting in one place: [Configuration](/reference/configuration).
