# Configuration

The command line and the GitHub Action read `storepilot.yml`; the Gradle plugin reads the
`storepilot { }` block and can [export](/guide/gradle-plugin#share-settings-with-ci) it as
`storepilot.yml`. Both have the same settings.

## storepilot.yml

```yaml
version: 1                       # required
metadataDir: store               # relative to this file
track: internal
rollout: 1.0
onUnsupported: fail              # or warn
fallbackToDefaultLanguage: false

listing:
  graphics: true                 # false: push text only
  replaceScreenshots: true       # false: only add new images

aso:
  disable: []                    # lint rule IDs, see "Listing checks"
  warningsAsErrors: false

stores:
  google-play:
    packageName: com.example.notes
    track: production            # overrides the top-level value
    rollout: 0.1
    releaseStatus: inProgress    # draft, inProgress, halted, completed
    inAppUpdatePriority: 3       # 0 to 5
    changesNotSentForReview: false
```

Only `version` is required. Unknown keys are errors, reported with their line and column, so a typo
such as `trak:` does not go unnoticed.

## Order of precedence

From highest to lowest:

1. Command-line options and action inputs (`--track`, `track:`).
2. `STOREPILOT_*` environment variables.
3. `storepilot.yml`.
4. The defaults.

Within the environment and within the file, a store-specific value wins over a top-level one.
Environment variables that are set but empty count as not set, because the action passes inputs you
leave out as empty strings.

## Settings and environment variables

| Setting | In `storepilot.yml` | Environment variable | Default |
|---|---|---|---|
| Store directory | `metadataDir` | `STOREPILOT_METADATA_DIR` | `store` |
| Track | `track`, `stores.<id>.track` | `STOREPILOT_TRACK`, `STOREPILOT_GOOGLE_PLAY_TRACK` | `internal` |
| Rollout | `rollout`, `stores.<id>.rollout` | `STOREPILOT_ROLLOUT`, `STOREPILOT_GOOGLE_PLAY_ROLLOUT` | `1.0` |
| Release status | `stores.<id>.releaseStatus` | `STOREPILOT_GOOGLE_PLAY_RELEASE_STATUS` | from the rollout |
| Package name | `stores.<id>.packageName` | `STOREPILOT_GOOGLE_PLAY_PACKAGE_NAME` | from the artifact when publishing |
| Unsupported parts | `onUnsupported` | `STOREPILOT_ON_UNSUPPORTED` | `fail` |
| Language fallback | `fallbackToDefaultLanguage` | | `false` |
| Graphics, screenshots | `listing.*` | | `true`, `true` |
| Lint rules | `aso.*` | | all on, warnings |
| Key content | | `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` | |
| Key file | | `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` | |

Credentials never go in `storepilot.yml`; see [Credentials and security](/reference/credentials).

## Gradle DSL

| DSL | Same as |
|---|---|
| `metadataDir`, `track`, `rollout`, `onUnsupported`, `fallbackToDefaultLanguage` | the top-level keys |
| `listing { graphics; replaceScreenshots }` | `listing:` |
| `aso { disable(...); warningsAsErrors }` | `aso:` |
| `googlePlay { track; rollout; releaseStatus; inAppUpdatePriority; changesNotSentForReview }` | `stores.google-play:` |
| `packageName`, `artifact` | `packageName`, and the artifact for projects without the Android plugin |
| `artifactType`, `variants(...)` | Gradle only: what `publish<Variant>` uploads, and which variants get tasks |

The Gradle plugin reads no `STOREPILOT_*` variables except the two key variables. Use
`providers.environmentVariable(...)` in the DSL when a setting should come from the environment.
