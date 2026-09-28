# StorePilot — Design Draft

> Status: **Draft / proposal.** Nothing here is implemented yet. This document fixes the
> user-facing surface (Gradle DSL, GitHub Action, CLI) before any code is written.

## 1. Goal

StorePilot manages an Android app's presence in app stores from the repository:

1. **Release upload** — push an `.aab`/`.apk` to a track with rollout and release notes.
2. **Store listing metadata** — title, descriptions, contact details, per locale, kept as files in git.
3. **Graphics** — screenshots, icon, feature graphic, per locale and device type.
4. **ASO support** — validate listing text and graphics against store rules, report locale coverage,
   and show the diff between the repository and the live listing before anything is pushed.

**v1 target store: Google Play only.** The architecture stays store-agnostic so that other Android
stores (Huawei AppGallery, Samsung Galaxy Store, …) can be added later as adapters without changing
the DSL shape, the CLI, or the action.

It has three entry points that share one engine:

| Entry point | Who uses it | Needs Gradle? |
|---|---|---|
| Gradle plugin | Android projects that build and publish in one step | Yes |
| CLI | Any CI, or a pipeline that already has the `.aab`/`.apk` | No |
| GitHub Action | GitHub Actions workflows | No (wraps the CLI) |

### Non-goals (v1)

- Building the app. StorePilot takes an artifact; it does not replace AGP.
- iOS / App Store Connect, and any non-Android platform.
- Stores other than Google Play (planned, not in v1).
- Keyword rank tracking, competitor analysis, or any ASO feature that needs third-party market data.
- Review replies, ratings, and store analytics.

## 2. Monorepo layout

```
storepilot/
├── action.yml            # GitHub Action entry point (must be at the root, see §9)
├── build-logic/          # Included build with the convention plugins used by every module
├── core/                 # Store-agnostic engine. Pure Kotlin/JVM. No Gradle, no Android deps.
│   ├── api/              #   Model + StoreProvider SPI
│   ├── engine/           #   Config loading, validation, diff, planning, execution, reporting
│   └── stores/
│       └── google-play/  #   Only adapter in v1
├── plugin/               # Gradle plugin. Maps the DSL + AGP variants onto core.
├── cli/                  # `storepilot` command. Reads storepilot.yml + flags, calls core.
├── action/               # Scripts used by the root action.yml (composite; downloads and runs the CLI).
├── samples/
│   ├── android-gradle/   # Android app that uses the plugin (via includeBuild)
│   └── github-workflow/  # Example workflows that use the action
└── docs/
```

Rules:

- `core` never depends on `plugin`, `cli`, or `action`. All three depend on `core`.
- A store adapter depends only on `core:api` and is found through Java `ServiceLoader`. A new store
  is a new module, not a change to the plugin, CLI, or action.
- Plugin, CLI, and action ship **with the same version number** from one tag. The version is set
  once in `gradle.properties`.
- Every Kotlin/JVM module applies a convention plugin from `build-logic/`: `storepilot.kotlin-jvm`, or
  `storepilot.embedded-kotlin` for code that runs inside Gradle (§9, decision 3).

## 3. Shared concepts

These names mean the same thing in the DSL, the YAML file, the CLI flags, and the action inputs.

| Concept | Meaning |
|---|---|
| **Store** | A target store. ID `google-play` in YAML/CLI/action, `googlePlay` in the DSL. |
| **Artifact** | The file to upload: `.aab` (default) or `.apk`. |
| **Track** | Where the release goes. Portable values `internal`, `testing`, `production`; any other value is passed to the store as-is (e.g. a custom Play closed-testing track name). |
| **Rollout** | Fraction of users, `0.0 < rollout <= 1.0`. `1.0` = full release. |
| **Release status** | `draft`, `inProgress`, `halted`, `completed`. Derived from the rollout when not set: below `1.0` is `inProgress`, `1.0` is `completed`. `inProgress` and `halted` need a rollout below `1.0`; `draft` and `completed` need `1.0`. |
| **Release notes** | Per-locale "what's new" text for one release. |
| **Listing** | Per-locale store page text + app-level details (contact email, website, default language). |
| **Graphics** | Per-locale images, grouped by portable image type (§3.2). |
| **Locale** | BCP-47 (`en-US`, `tr-TR`). Adapters convert to the store's codes. |
| **Capability** | What an adapter supports. Checked at validation time (§3.3). |

### 3.1 Metadata directory

All store content lives in files in the repository, one directory per app module:

```
store/
├── details.yml                    # app-level: default-language, contact-email, contact-website, contact-phone
├── release-notes/
│   ├── en-US.txt                  # default for every store
│   ├── tr-TR.txt
│   └── google-play/en-US.txt      # store-specific override (optional)
└── listing/
    ├── en-US/
    │   ├── title.txt
    │   ├── short-description.txt
    │   ├── full-description.txt
    │   ├── video-url.txt          # optional
    │   └── graphics/
    │       ├── icon.png
    │       ├── feature-graphic.png
    │       ├── phone-screenshots/01.png, 02.png, …
    │       ├── tablet-7-screenshots/…
    │       ├── tablet-10-screenshots/…
    │       ├── tv-screenshots/…
    │       └── wear-screenshots/…
    └── tr-TR/…
```

- A store-specific file wins over the default file for the same locale.
- Release notes are `<locale>.txt` files. Line endings are normalized and surrounding whitespace is
  removed. Hidden files are ignored; other file names are errors. A directory under `release-notes/`
  that is not a known store ID gets a warning.
- Screenshot order = file name sort order. Numbered names (`01.png`) are the convention.
- A locale directory may hold only some files. Missing fields fall back to the default language
  **only if** `fallbackToDefaultLanguage = true`; otherwise the field is left unchanged in the store.
- `storepilot listing pull` (§6) creates this tree from the live Play listing, so existing apps can
  start from what is already published.

### 3.2 Portable graphic types → Google Play

| StorePilot dir | Google Play image type |
|---|---|
| `icon.png` | `icon` |
| `feature-graphic.png` | `featureGraphic` |
| `phone-screenshots/` | `phoneScreenshots` |
| `tablet-7-screenshots/` | `sevenInchScreenshots` |
| `tablet-10-screenshots/` | `tenInchScreenshots` |
| `tv-screenshots/` | `tvScreenshots` |
| `tv-banner.png` | `tvBanner` |
| `wear-screenshots/` | `wearScreenshots` |

### 3.3 Capability gaps

When a future store cannot do what the config asks (e.g. staged rollout, a graphic type), StorePilot
**fails at validation** by default. `onUnsupported = warn` downgrades this to a warning and skips the
option for that store only. For v1 (Google Play only) this path exists but is rarely hit.

Some things are never skipped, whatever `onUnsupported` says: the artifact type, and a staged rollout.
Skipping a staged rollout would turn it into a full release, which cannot be undone.

## 4. ASO support (v1 scope)

Everything here runs locally or with read-only store calls. Nothing is pushed.

| Check | Output |
|---|---|
| **Text limits** | Error when a field is over the store limit. Play limits to encode (verify against current Play docs before implementation): title 30, short description 80, full description 4000, release notes 500 characters. |
| **Graphic rules** | Error for wrong format, size, aspect ratio, or count per type. Exact Play rules to be taken from Play docs at implementation time. |
| **Locale coverage** | Table: locale × field/graphic type, showing present / missing / fallback. |
| **Remote diff** | Per field and image: unchanged / changed / added / removed, compared with the live listing. Text diffs shown inline; images compared by hash. |
| **Lint (warnings)** | Title repeated in short description, trailing whitespace, empty locale directories, screenshots with mixed orientations in one type. The list stays small and each rule can be turned off. |

`validate` runs text limits, graphic rules, and lint. `diff` adds the remote comparison.
Both print a human table by default and JSON with `--output json`.

### 4.1 Machine translation (after the first release)

In scope (§9, decision 5), planned after the first public release (§10). Draft:

- `storepilot listing translate --from en-US --to de-DE,fr-FR [--fields ...] [--overwrite]` and a
  matching Gradle task `translateListing`.
- Output goes into `store/` as normal files. Nothing is pushed to a store directly; the translated
  text is reviewed in a pull request like any other listing change.
- Existing files are not overwritten without `--overwrite`.
- Translated text goes through the same text limits as hand-written text. A translation that is too
  long is reported, not silently cut.
- Translation services are adapters behind a `TranslationProvider` SPI in `core:api`, found through
  `ServiceLoader`, the same way as stores. Credentials follow §8
  (`STOREPILOT_TRANSLATE_<PROVIDER>_<FIELD>`).

Open points: the first provider (Google Cloud Translation, DeepL, an LLM API); whether to record the
source text hash so that `validate` can warn about stale translations.

### 4.2 Screenshot generation (after the first release)

v1 uploads only the screenshots that exist in `store/`. Generating them is in scope (§9, decision 4)
and planned after the first public release (§10). Draft:

- A Gradle plugin task collects images produced by the app's screenshot tests and writes them into
  `store/listing/<locale>/graphics/<type>/` with the naming rules from §3.1.
- The code that needs Android or AGP lives in the plugin (or a separate plugin module). `core` stays
  free of Android dependencies.

Open points: the first source to support (Compose Preview Screenshot Testing, Roborazzi, Paparazzi,
instrumented tests); whether framing (device frame, background, caption text) is in scope.

## 5. Gradle DSL

### 5.1 Minimal setup

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("com.muhammedelsami.storepilot") version "0.1.0"
}

storepilot {
    googlePlay {
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
    }
}
```

```
./gradlew publishReleaseBundle        # upload .aab + release notes to "internal"
./gradlew publishListing              # push text + graphics from store/listing
```

Defaults: track `internal`, rollout `1.0`, metadata from `store/`, artifact = the variant's `.aab`,
package name = the variant's `applicationId`.

### 5.2 Full example

```kotlin
storepilot {
    metadataDir = layout.projectDirectory.dir("store")
    track = "testing"
    rollout = 0.2
    artifactType = ArtifactType.BUNDLE              // BUNDLE (default) or APK
    onUnsupported = OnUnsupported.FAIL              // FAIL (default) or WARN
    fallbackToDefaultLanguage = false

    // AGP variants that get tasks. Default: every non-debuggable variant.
    variants("prodRelease")

    listing {
        graphics = true                             // false = push text only
        replaceScreenshots = true                   // true: remote set = local set; false: only add
    }

    aso {
        disable("title-in-short-description")       // turn off one lint rule
        warningsAsErrors = false
    }

    googlePlay {
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
        // or: serviceAccountFile = layout.projectDirectory.file("play-sa.json")

        track = "production"
        rollout = 0.1
        releaseStatus = ReleaseStatus.IN_PROGRESS   // DRAFT, IN_PROGRESS, HALTED, COMPLETED
        inAppUpdatePriority = 3                     // 0..5
        changesNotSentForReview = false
    }
}
```

The store is configured with a top-level `googlePlay { }` block. When more stores arrive, each gets
its own top-level block (`huaweiAppGallery { }`); options set directly under `storepilot { }` are
defaults that every store block can override.

### 5.3 DSL rules

- Every value is a lazy `Property`/`Provider`, so `=` assignment works and nothing is read at
  configuration time. The plugin must be **configuration-cache** and **isolated-projects** compatible.
- Credentials are only accepted as `Provider<String>` or a file. A plain string literal for a secret
  field is rejected with an error that shows the `providers.environmentVariable(...)` form.
- Without AGP, the plugin still works: the user sets `artifact` and `packageName` explicitly and gets
  tasks without a variant name.

### 5.4 Tasks

Release:

| Task | Does |
|---|---|
| `publish<Variant>Bundle` / `publish<Variant>Apk` | Upload artifact + release notes to the track. |
| `promote<Variant>Release` | Move an existing release: `--from=testing --to=production --rollout=0.5`. |
| `halt<Variant>Release` / `resume<Variant>Release` | Stop or continue a staged rollout. |

Listing and ASO:

| Task | Does |
|---|---|
| `publishListing` | Push details, listing text, and graphics. |
| `publishListingText` | Push text and details only, no graphics. |
| `pullListing` | Write the live listing into `store/` (asks for `--overwrite` if files exist). |
| `validateListing` | Local checks from §4. No network. |
| `diffListing` | Local vs live listing (§4). Read-only network. |

Aggregate: `publish<Variant>` = bundle + listing in **one Play edit**, so they are committed together
or not at all.

Dry run: `-Pstorepilot.dryRun=true` builds the full plan, runs read-only calls, prints the changes, and
never commits the Play edit. We do not use a `--dry-run` task option, because Gradle's global
`--dry-run` (`-m`) already means "skip all task actions".

Publish tasks are never up-to-date and never cached (they have remote side effects).
`validateListing` is cacheable and is added to `check`.

## 6. CLI

```
storepilot publish  --artifact app-release.aab [--track production] [--rollout 0.1]
                    [--with-listing] [--config storepilot.yml] [--dry-run]
storepilot promote  --package com.example.app --from testing --to production [--rollout 0.5]
storepilot halt|resume --package com.example.app --track production

storepilot listing push     [--text-only] [--dry-run]
storepilot listing pull     [--overwrite]
storepilot listing diff
storepilot listing validate
```

- `--store` exists on every command and defaults to `google-play`.
- Distributed as a fat JAR + launcher script on GitHub Releases; later Homebrew / SDKMAN.
- `--output json` prints a machine-readable result (used by the action).
- Exit codes: `0` success, `1` config or validation error, `2` store API error,
  `3` `listing diff` found changes (only with `--exit-code`, for "fail if listing drifted" CI jobs).

### Shared config file: `storepilot.yml`

The CLI and the action read it. The Gradle plugin does not need it; `./gradlew exportStorepilotConfig`
generates it from the DSL so all entry points stay in sync.

```yaml
version: 1
metadataDir: store
track: testing
rollout: 0.2

stores:
  google-play:
    packageName: com.example.app
    track: production
    rollout: 0.1
    releaseStatus: inProgress
    # credentials are never stored here; see §8
```

Precedence (highest first): CLI flag / action input → environment variable → `storepilot.yml` → defaults.
In the environment and in the file, a store-specific value wins over a top-level one. Relative paths in
the file are resolved against the file's directory.

| Setting | Top level (file / env) | Per store (file / env) | Default |
|---|---|---|---|
| Metadata directory | `metadataDir` / `STOREPILOT_METADATA_DIR` | — | `store` |
| Track | `track` / `STOREPILOT_TRACK` | `track` / `STOREPILOT_<STORE_ID>_TRACK` | `internal` |
| Rollout | `rollout` / `STOREPILOT_ROLLOUT` | `rollout` / `STOREPILOT_<STORE_ID>_ROLLOUT` | `1.0` |
| Release status | — | `releaseStatus` / `STOREPILOT_<STORE_ID>_RELEASE_STATUS` | from rollout |
| Package name | — | `packageName` / `STOREPILOT_<STORE_ID>_PACKAGE_NAME` | required |
| Capability gaps | `onUnsupported` / `STOREPILOT_ON_UNSUPPORTED` | — | `fail` |

Empty environment variables count as not set, because the action passes unset inputs as empty
strings. Other keys under `stores.<id>` are store-specific options that the adapter checks (for Google
Play: `inAppUpdatePriority`, `changesNotSentForReview`).

## 7. GitHub Action

### 7.1 Upload a release

```yaml
- uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: 17 }

- uses: muhammedelsami/storepilot@v1
  with:
    artifact: app/build/outputs/bundle/release/app-release.aab
    google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

### 7.2 Listing workflow: check on PR, push on main

```yaml
on:
  pull_request: { paths: ["store/**"] }
  push:         { branches: [main], paths: ["store/**"] }

jobs:
  listing:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: muhammedelsami/storepilot@v1
        with:
          command: ${{ github.event_name == 'pull_request' && 'listing-diff' || 'listing-push' }}
          package-name: com.example.app
          google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

On a PR, `listing-diff` writes the validation result and the diff to the job summary, so reviewers see
exactly what will change on the Play page.

### 7.3 `action.yml` surface (draft)

| Input | Required | Default | Notes |
|---|---|---|---|
| `command` | no | `publish` | `publish`, `promote`, `listing-push`, `listing-diff`, `listing-validate`. |
| `artifact` | for `publish` | — | Path or glob; glob must match exactly one file. |
| `package-name` | no | from config / artifact | |
| `config` | no | `storepilot.yml` if present | |
| `metadata-dir` | no | `store` | |
| `track`, `rollout` | no | from config | |
| `with-listing` | no | `false` | `publish` also pushes the listing in the same edit. |
| `dry-run` | no | `false` | |
| `version` | no | action tag | CLI version to download. |
| `google-play-service-account-json` | no* | — | *Not needed with keyless auth (below). |

| Output | Notes |
|---|---|
| `result` | JSON: per store `status`, `track`, `versionCode`, `changes`. |
| `listing-changed` | `true`/`false`, set by `listing-diff`. |

Behaviour:

- **Composite action**: checks Java is available, downloads the CLI (cached with `actions/cache`),
  runs it with `--output json`. No Docker, so it runs on Linux, macOS, and Windows runners.
- Credential inputs reach the CLI through environment variables, never as arguments, and are masked.
- Writes a job summary: release table for `publish`, coverage + diff tables for listing commands.
- Keyless Google auth: if `google-github-actions/auth` ran earlier in the job, StorePilot uses
  Application Default Credentials and the JSON input is not needed.
- Future stores add inputs named `<store>-<field>` (e.g. `huawei-client-id`).

## 8. Credentials

| Store | DSL | Env var (CLI/action) |
|---|---|---|
| Google Play | `serviceAccountJson` or `serviceAccountFile`, or ADC | `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` |

Pattern for future stores: `STOREPILOT_<STORE_ID>_<FIELD>`, upper snake case.
Secrets never appear in logs, the job summary, `--output json`, or Gradle build scans.

## 9. Decisions

Decided on 2026-09-28.

1. **Action location.** `action.yml` is at the repo root and calls scripts in `action/`, so users
   write `uses: muhammedelsami/storepilot@v1`.
2. **Plugin ID.** `com.muhammedelsami.storepilot`.
3. **Minimum versions.** Gradle 8.10+, AGP 8.5+, JDK 17.
   - All modules compile to JVM 17 bytecode.
   - Code that runs inside Gradle uses the Kotlin stdlib that Gradle embeds (Kotlin 1.9.24 in
     Gradle 8.10). The `plugin` module and everything it loads (`core:*`, store adapters) apply the
     `storepilot.embedded-kotlin` convention: Kotlin API version 1.9, language version 2.0. The
     plugin's functional tests run against Gradle 8.10 and the current Gradle in CI.
   - For the same reason, these modules use Java libraries (for example `snakeyaml-engine` for
     `storepilot.yml`) and no Kotlin libraries built for a newer stdlib. Running `core` in a Gradle
     worker with process isolation stays possible if that becomes too limiting (milestone 5).
   - Still to check in milestone 5: the AGP 9 variant API.
4. **Screenshots.** Upload-only in v1. Generation is in scope later (§4.2).
5. **Machine translation.** In scope later (§4.1).

## 10. Milestones

1. Restructure repo into the layout in §2 (empty modules, build wiring, CI). No features.
2. `core:api` + `core:engine` with a fake store adapter and tests.
3. Google Play adapter: edits, bundle upload, tracks, release notes. CLI `publish`/`promote`.
4. Listing: `pull`, `push`, `validate`, `diff` in core + CLI.
5. Gradle plugin on top of core; `samples/android-gradle`.
6. GitHub Action + `samples/github-workflow`.
7. First public release (Plugin Portal, GitHub Release, Marketplace).
8. Machine translation (§4.1).
9. Screenshot generation (§4.2).
10. Second store adapter.
