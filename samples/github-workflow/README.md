# github-workflow

Example workflows that use the StorePilot action. Copy them into `.github/workflows/` of an Android
app repository and adjust the paths.

| File | Does |
|---|---|
| `publish.yml` | On a `v*` tag: builds the release bundle and publishes it to production at 10%, with the listing in the same edit. |
| `listing.yml` | On a pull request that changes `store/`: validates the listing and writes the diff to the job summary. On `main`: pushes the listing. |
| `publish-keyless.yml` | Like `publish.yml`, but authenticates with Workload Identity Federation instead of a key. |

The workflows expect:

- the listing in `app/store/` (see `docs/design.md` §3.1),
- a `PLAY_SERVICE_ACCOUNT_JSON` secret with a service account key that has access to the app in the
  Play Console, or, for the keyless workflow, a Workload Identity pool and a service account.

The package name comes from the bundle, so the workflows do not repeat it.
