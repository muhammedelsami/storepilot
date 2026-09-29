# GitHub Action

The action runs the StorePilot command-line tool in a workflow. It downloads the release that matches
the action version, checks its SHA-256, caches it, and writes the result to the job summary. It is a
composite action, so it runs on Linux, macOS, and Windows runners without Docker.

It needs Java 17 or later on the runner, which `actions/setup-java` provides.

```yaml
- uses: muhammedelsami/storepilot@v0
```

`@v0` follows the newest 0.x release. Pin `@v0.1.1` for an exact version.

## Publish when a version tag is pushed

```yaml
name: Publish

on:
  push:
    tags: ["v*"]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17

      - uses: gradle/actions/setup-gradle@v6

      - name: Build the release bundle
        run: ./gradlew :app:bundleRelease

      - name: Publish to Google Play
        uses: muhammedelsami/storepilot@v0
        with:
          artifact: app/build/outputs/bundle/release/*.aab
          metadata-dir: app/store
          track: production
          rollout: "0.1"
          with-listing: true
          google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

The package name comes from the bundle, so the workflow does not repeat it. `artifact` may be a
glob, but it must match exactly one file.

## Check the listing in pull requests

```yaml
name: Store listing

on:
  pull_request:
    paths: ["app/store/**"]
  push:
    branches: [main]
    paths: ["app/store/**"]

jobs:
  listing:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: actions/setup-java@v6
        with:
          distribution: temurin
          java-version: 17

      # No network and no key: fails the pull request when the listing breaks a rule.
      - name: Validate the listing
        uses: muhammedelsami/storepilot@v0
        with:
          command: listing-validate
          metadata-dir: app/store

      # Pull requests from forks get no secrets, so the diff only runs for branches of this repository.
      - name: Show what would change
        if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name == github.repository
        uses: muhammedelsami/storepilot@v0
        with:
          command: listing-diff
          metadata-dir: app/store
          package-name: com.example.notes
          google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}

      - name: Push the listing
        if: github.event_name == 'push'
        uses: muhammedelsami/storepilot@v0
        with:
          command: listing-push
          metadata-dir: app/store
          package-name: com.example.notes
          google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

## Job summary

Every command adds a Markdown report to the job summary. For a publish dry run it looks like this:

<div class="summary-preview">

#### StorePilot (dry run, nothing committed)

**google-play** · `com.example.notes` · not committed

- upload app-release.aab
- track production: new release, inProgress 10%, release notes: en-US, tr-TR; before: version code 41, completed
- listing en-US short-description: "Shows how StorePilot publishes an app from files." (was "An app that shows StorePilot.")
- listing tr-TR title: "StorePilot Örnek" (new)
- graphics en-US phone-screenshots: replace 2 images with 2 images

</div>

`listing-validate` adds the problems and the locale coverage table; `listing-diff` adds a table of
every compared item.

## Inputs

| Input | Default | Used by |
|---|---|---|
| `command` | `publish` | `publish`, `promote`, `halt`, `resume`, `listing-push`, `listing-diff`, `listing-validate` |
| `artifact` | | `publish` (required). Path or glob of the `.aab` or `.apk`. |
| `package-name` | from `storepilot.yml`, then from the artifact | every command that talks to the store |
| `store` | `google-play` | all |
| `config` | `storepilot.yml` if present | all |
| `metadata-dir` | `store` next to the config file | all |
| `track` | from the config, else `internal` | `publish`: release track. `promote`: target track. `halt`, `resume`: required. |
| `from-track` | | `promote` (required) |
| `rollout` | from the config, else `1.0` | `publish`, `promote` |
| `with-listing` | `false` | `publish`: push the listing in the same edit |
| `text-only` | `false` | `listing-push`: no graphics |
| `dry-run` | `false` | `publish`, `promote`, `halt`, `resume`, `listing-push` |
| `version` | the action's release | the CLI version to download |
| `google-play-service-account-json` | | every command that talks to the store, unless [keyless](/reference/credentials#keyless-authentication-on-github-actions) |

## Outputs

| Output | Contains |
|---|---|
| `result` | The command's JSON result, the same as `storepilot ... --output json`. |
| `listing-changed` | `true` or `false`, set by `listing-diff`. |

```yaml
- id: diff
  uses: muhammedelsami/storepilot@v0
  with:
    command: listing-diff
    metadata-dir: app/store
    package-name: com.example.notes
    google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}

- if: steps.diff.outputs.listing-changed == 'true'
  run: echo "The Play listing differs from app/store."
```

## Security

The key reaches the CLI only through an environment variable, never as a command-line argument. Lines
of the key are masked in the log in addition to GitHub's own masking of secrets. See
[Credentials and security](/reference/credentials) for keyless authentication with Workload Identity
Federation.

More complete workflows are in
[`samples/github-workflow`](https://github.com/muhammedelsami/storepilot/tree/main/samples/github-workflow).
