# Troubleshooting

Each entry starts with the message StorePilot prints.

## Credentials and access

### `No Google Play credentials. Set STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON or ...`

StorePilot found no key and no Application Default Credentials. Check that the variable or the action
input is set in the step that runs StorePilot; secrets are not visible to pull requests from forks.

### `Google Play could not start an edit for com.example.notes: 403 ...`

Google refused the request. The usual causes:

- The Google Play Android Developer API is not enabled in the service account's Cloud project.
- The service account was not invited in the Play Console, or not for this app.
- The account lacks the permission for the action, for example releasing to production.
- The invitation is new; permissions can take a little while to apply.

[Connect Google Play](/guide/google-play-setup) walks through each step.

### `Google Play could not start an edit for com.example.notes: 404 ...`

Play does not know the package name. Check `packageName`, and remember that the Play API cannot
create apps: the first release of a new app has to be uploaded in the Play Console.

### `Cannot read the Google Play service account key: ...`

The JSON is not a service account key, or the file is not valid JSON. Copy the whole downloaded file
into the secret, including the braces.

## Settings

### `No package name for store 'google-play'. Set 'stores.google-play.packageName' in storepilot.yml or ...`

Commands that talk to the store need the app's package name. Pass `--package`, set it in
`storepilot.yml`, or, for `publish`, let StorePilot read it from the artifact. When the message ends
with "Reading it from the artifact failed", the `.aab` or `.apk` is not a valid bundle or APK.

### `storepilot.yml:3:1: error: Unknown key 'trak'.`

A typo in the settings file. The position is line 3, column 1.

### `Language tag 'en-us' is not in canonical form, use 'en-US'`

Rename the directory or file to the form in the message.

## Listing checks

### `The long side can be at most 2 times the short side, got 1080 × 2400 px.`

Google Play rejects screenshots longer than twice their width. Crop 20:9 phone screenshots to
1080 × 2160 or 1080 × 1920.

### `title has 35 characters; Google Play allows 30.`

Shorten the text. Characters are counted the way Play counts them, so an emoji counts as one.

### `The image has an alpha channel; Google Play asks for phone-screenshots without one. [image-alpha]`

A warning, not an error. Export the image without transparency, or turn the rule off with
`aso.disable: [image-alpha]`.

## Releases

### `Google Play could not read track 'beta' for com.example.notes: 404 Track not found.`

The track name does not exist for this app. Use `internal`, `testing`, `production`, or the exact
name of a custom track from the Play Console.

### `Track 'testing' has no release to promote.`

`promote` takes the newest release that is not a draft from the source track, and there was none.

### `Expected one APK in ..., found 3.`

The variant builds APK splits. Publish the bundle (`publish<Variant>Bundle`) instead.

### `The selected variants have different application IDs (...)`

Listing tasks belong to one app. Set `storepilot.packageName`, or limit the variants with
`variants("prodRelease")`.

## GitHub Action

### `StorePilot needs Java 17 or later.`

Add `actions/setup-java` with Java 17 or later before the StorePilot step.

### `The artifact input 'app/build/**/*.aab' matches 0 files; it must match one.`

The bundle was not built in this job, or the path differs. List the directory in a step before
StorePilot to see what the build produced.

### `listing-diff` exits with success although the listing differs

That is intended: the action sets the `listing-changed` output instead of failing. Check it in a later
step, or run the CLI with `listing diff --exit-code` to fail on differences.

## Still stuck

[Open an issue](https://github.com/muhammedelsami/storepilot/issues) with the command, the full
message, and the StorePilot version (`storepilot --version`). Remove keys and other secrets first.
