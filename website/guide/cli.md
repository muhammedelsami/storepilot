# Command line

The `storepilot` command runs on any machine with Java 17 or later. Use it in CI systems other than
GitHub Actions, in scripts, or in pipelines that already have the `.aab` or `.apk`.

## Install

Each [GitHub release](https://github.com/muhammedelsami/storepilot/releases) has:

| File | Use |
|---|---|
| `storepilot-0.1.1.zip` | `bin/storepilot` (and `storepilot.bat`) plus the jar. Put `bin` on your `PATH`. |
| `storepilot.jar` | One file with everything: `java -jar storepilot.jar <command>`. |
| `storepilot.jar.sha256` | The jar's SHA-256, to check the download. |

```sh
curl -LO https://github.com/muhammedelsami/storepilot/releases/download/v0.1.1/storepilot.jar
curl -LO https://github.com/muhammedelsami/storepilot/releases/download/v0.1.1/storepilot.jar.sha256
shasum -a 256 -c storepilot.jar.sha256
java -jar storepilot.jar --version
```

## Commands

```
storepilot publish   --artifact <file> [--track <track>] [--rollout <fraction>] [--with-listing] [--dry-run]
storepilot promote   --from <track> --to <track> [--rollout <fraction>] [--dry-run]
storepilot halt      --track <track> [--dry-run]
storepilot resume    --track <track> [--dry-run]

storepilot listing validate
storepilot listing diff      [--exit-code]
storepilot listing push      [--text-only] [--dry-run]
storepilot listing pull      [--overwrite]
```

| Command | Does |
|---|---|
| `publish` | Uploads the bundle or APK and releases it on the track with its release notes. `--with-listing` pushes the listing in the same edit. |
| `promote` | Puts the newest release of `--from` on `--to`, with its release notes. `--rollout` defaults to `1.0`. |
| `halt`, `resume` | Stops or continues the staged rollout on a track. |
| `listing validate` | Checks the store directory. No network, no key, no package name. |
| `listing diff` | Compares the store directory with the live listing. Only reads. |
| `listing push` | Makes the live listing match the store directory. |
| `listing pull` | Writes the live listing into the store directory. |

Every command has `--help`.

## Options every command has

| Option | Default | Meaning |
|---|---|---|
| `--store` | `google-play` | The store to talk to. |
| `--config` | `storepilot.yml` in the working directory, if present | The settings file. |
| `--package` | from the environment, then `storepilot.yml`, then the artifact | The app's package name. |
| `--metadata-dir` | `store` next to the config file | The store directory. |
| `--output` | `text` | `json` prints a machine-readable result. |
| `--summary-file` | | Also appends a Markdown report to this file. |

Relative paths are resolved against the working directory.

## Settings

Values come from, in this order: command-line options, `STOREPILOT_*` environment variables,
`storepilot.yml`, and the defaults. A store-specific value wins over a top-level one. The
[configuration reference](/reference/configuration) lists every key and variable.

```yaml
# storepilot.yml
version: 1
metadataDir: app/store
track: internal

stores:
  google-play:
    packageName: com.example.notes
    track: production
    rollout: 0.1
```

## Exit codes

| Code | Meaning |
|---|---|
| `0` | Success. |
| `1` | A config or validation error: nothing was changed in the store. |
| `2` | The store returned an error. |
| `3` | `listing diff --exit-code` found differences. |

## JSON output

With `--output json`, standard output holds only the result, and logs and errors go to standard error.
A publish result has one entry per store:

```json
{
  "dryRun": false,
  "stores": [
    {
      "store": "google-play",
      "packageName": "com.example.notes",
      "status": "committed",
      "track": "production",
      "versionCodes": [42],
      "changes": [
        { "type": "upload", "artifact": "app-release.aab", "versionCode": 42 },
        {
          "type": "release",
          "track": "production",
          "release": { "versionCodes": [42], "status": "inProgress", "rollout": 0.1, "name": null, "releaseNotes": { "en-US": "Bug fixes." } },
          "before": [ { "versionCodes": [41], "status": "completed", "rollout": 1.0, "name": null, "releaseNotes": {} } ]
        }
      ],
      "warnings": []
    }
  ]
}
```

`listing diff --output json` has a top-level `listingChanged` flag; `listing validate --output json`
lists every problem with its severity, file, and lint rule.
