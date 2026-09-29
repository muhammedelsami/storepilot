# Releases and tracks

A release puts one or more version codes on a track, with a rollout, a status, and release notes.
StorePilot does all of this in one Play edit, so a failed step leaves the store untouched.

## Tracks

| Name in StorePilot | Google Play track |
|---|---|
| `internal` | Internal testing (the default) |
| `testing` | Closed testing (`alpha`), so a testing release is never public by accident |
| `production` | Production |
| anything else | Passed to Play as-is: `beta` (open testing), a custom closed-testing track, `wear:production` |

## Rollout and status

`rollout` is the share of users who get the release: above `0.0` and at most `1.0`.

| Rollout | Status StorePilot sets |
|---|---|
| `1.0` (default) | `completed`: every user gets it. |
| below `1.0`, for example `0.1` | `inProgress`: a staged rollout to 10% of users. |

Set `releaseStatus` to choose the status yourself: `draft` (not served; needs rollout `1.0`, the
percentage is chosen when the draft is started), `inProgress`, `halted`, or `completed`. StorePilot
refuses combinations Play would reject, such as `inProgress` at `1.0`.

When a release is set, Play's track rules apply: a completed release replaces the track, a staged or
halted release keeps the previous completed release available to everyone else, and a draft only
replaces the previous draft.

## Publish

::: code-group

```sh [Gradle plugin]
./gradlew :app:publishReleaseBundle
```

```sh [Command line]
storepilot publish --artifact app-release.aab --track production --rollout 0.1
```

```yaml [GitHub Action]
- uses: muhammedelsami/storepilot@v0
  with:
    artifact: app/build/outputs/bundle/release/*.aab
    track: production
    rollout: "0.1"
    google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

:::

The release notes come from `release-notes/` in the store directory. Add `--with-listing` (or use the
`publish<Variant>` task) to push the listing in the same edit.

## Promote

Moves the newest release of one track to another, keeping its release notes and name. Drafts are
skipped.

::: code-group

```sh [Gradle plugin]
./gradlew :app:promoteReleaseRelease --from=testing --to=production --rollout=0.2
```

```sh [Command line]
storepilot promote --package com.example.notes --from testing --to production --rollout 0.2
```

:::

## Halt and resume

Stops a staged rollout, or continues a halted one, on a track:

::: code-group

```sh [Gradle plugin]
./gradlew :app:haltReleaseRelease --track=production
./gradlew :app:resumeReleaseRelease --track=production
```

```sh [Command line]
storepilot halt   --package com.example.notes --track production
storepilot resume --package com.example.notes --track production
```

:::

::: warning Changing the percentage of a live rollout
StorePilot 0.1 cannot yet raise the rollout of a release that is already in progress (for example
from 10% to 50%). Do that in the Play Console for now; it is on the [roadmap](/roadmap).
:::

## Google Play options

| Option | Effect |
|---|---|
| `inAppUpdatePriority` (0 to 5) | The [in-app update](https://developer.android.com/guide/playcore/in-app-updates) priority of a newly uploaded release. Play does not allow changing it after the rollout starts, so StorePilot only sets it on releases uploaded in the same edit. |
| `changesNotSentForReview` | Commits the edit without sending the changes for review, for apps whose changes Play requires to be sent from the Play Console. |

Releases read from Play keep every field StorePilot does not manage, such as country targeting, when
they are halted, resumed, or promoted.
