# Credentials and security

StorePilot needs a Google Play service account to talk to the store. [Connect Google
Play](/guide/google-play-setup) shows how to create one. This page covers how StorePilot finds the
key and how it keeps it out of logs.

## How StorePilot finds the key

The first one that is set wins:

1. The key's JSON content: `serviceAccountJson` in the Gradle DSL, the
   `google-play-service-account-json` action input, or `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON`.
2. A key file: `serviceAccountFile` in the Gradle DSL or `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_FILE`.
3. [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials),
   for example from `gcloud auth application-default login` or `google-github-actions/auth`.

Only service account keys are accepted as JSON or file. Other credential types, such as Workload
Identity Federation, go through Application Default Credentials.

## Where the key never appears

- Command-line arguments: the action passes the key to the CLI through the environment.
- Logs, the job summary, and `--output json`.
- Error messages: a key that cannot be read is reported by its source, not its content.
- `storepilot.yml` and the output of `exportStorepilotConfig`.
- The Gradle build script: `serviceAccountJson` only accepts a `Provider<String>`, so a string literal
  does not compile.

In the action, lines of the key that are 16 characters or longer are also masked, in addition to
GitHub's own masking of secrets.

## Keyless authentication on GitHub Actions

With [Workload Identity Federation](https://github.com/google-github-actions/auth#preferred-direct-workload-identity-federation),
GitHub proves the workflow's identity to Google Cloud, and no key is stored at all.
`google-github-actions/auth` sets Application Default Credentials for the steps after it, and
StorePilot picks them up:

```yaml
permissions:
  contents: read
  id-token: write

steps:
  - uses: google-github-actions/auth@v3
    with:
      workload_identity_provider: projects/123456789/locations/global/workloadIdentityPools/github/providers/github
      service_account: play-publisher@example-project.iam.gserviceaccount.com

  - uses: muhammedelsami/storepilot@v0
    with:
      artifact: app/build/outputs/bundle/release/*.aab
      track: internal
```

The service account still has to be invited into the Play Console.

## Least privilege

Grant the service account only the Play Console permissions for the apps and actions you automate.
A workflow that only checks listings in pull requests needs no key: `listing-validate` works offline.
Pull requests from forks get no secrets on GitHub, so run store commands only for branches of your
own repository.

## The CLI download

The action downloads the CLI from the GitHub release of its version and compares it with the
`storepilot.jar.sha256` published next to it before running it.
