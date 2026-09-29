# Connect Google Play

StorePilot talks to the [Google Play Developer API](https://developers.google.com/android-publisher)
as a service account: a Google Cloud identity that you invite into your Play Console account with
only the permissions it needs.

You do this once per Play Console account. Google no longer requires linking a Cloud project in the
Play Console; inviting the service account is enough.

## 1. Enable the API

1. Open the [Google Cloud console](https://console.cloud.google.com/) and pick a project, or create
   one for publishing.
2. Open the [Google Play Android Developer API](https://console.cloud.google.com/apis/library/androidpublisher.googleapis.com)
   page and select **Enable**.

## 2. Create the service account

1. In the Cloud console, open **IAM & Admin → Service accounts** and select **Create service account**.
2. Give it a name such as `play-publisher`. It needs no Cloud roles.
3. Open the new account, go to **Keys → Add key → Create new key**, and choose **JSON**. The file
   downloads once; store it somewhere safe and never commit it.

::: tip No key file at all
If your organization blocks key creation, or you prefer not to store keys, the GitHub Action can use
[Workload Identity Federation](/reference/credentials#keyless-authentication-on-github-actions)
instead of a JSON key.
:::

## 3. Invite it into the Play Console

1. In the [Play Console](https://play.google.com/console), open **Users and permissions** and select
   **Invite new users**.
2. Enter the service account's email address (it ends in `iam.gserviceaccount.com`).
3. Under **App permissions**, add your app and grant what StorePilot needs:

| To | Grant |
|---|---|
| Upload to internal and testing tracks | Release apps to testing tracks |
| Release to production, halt, and resume | Release to production, exclude devices, and use Play App Signing |
| Pull, diff, and push the store listing | Manage store presence |

4. Select **Invite user**. If the first call fails with a permission error right after the
   invitation, wait a little and try again.

## 4. Give StorePilot the key

| Entry point | Where the key goes |
|---|---|
| Gradle plugin | `serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")` or `serviceAccountFile = ...` in `googlePlay { }` |
| GitHub Action | The `google-play-service-account-json` input, from a repository secret |
| Command line | `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON` (the content) or `STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_FILE` (a path) |

Without any of these, StorePilot uses [Application Default Credentials](https://cloud.google.com/docs/authentication/application-default-credentials).

## Check the connection

A read-only command is the quickest test:

```sh
storepilot listing diff --package com.example.notes
```

If it fails, [Troubleshooting](/reference/troubleshooting) lists the usual causes: the API is not
enabled, the account was not invited, or the package name is wrong.
