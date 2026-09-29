# Releasing StorePilot

One version number covers the Gradle plugin, the core modules on Maven Central, the CLI, and the
GitHub Action. A release has two parts: automated steps started by a tag, and checks and publishing
steps done by hand, because Maven Central, the Plugin Portal, and the Marketplace cannot be undone.

## One-time setup

Repository secrets (Settings → Secrets and variables → Actions):

| Secret | From |
|---|---|
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | A user token of the [Central Portal](https://central.sonatype.com) account. Verify the `io.github.muhammedelsami` namespace there first. |
| `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` | An ASCII-armored GPG private key and its passphrase. Upload the public key to `keys.openpgp.org`, because Central checks signatures against public key servers. |
| `GRADLE_PUBLISH_KEY`, `GRADLE_PUBLISH_SECRET` | API keys of the [Gradle Plugin Portal](https://plugins.gradle.org) account (sign in with GitHub). |

GitHub Marketplace needs two-factor authentication on the account that publishes the release. The
`name` in `action.yml` must not match any Marketplace action, GitHub user, or organization; plain
"StorePilot" is an organization, so the action is "StorePilot Android Publisher". Marketplace reads
`action.yml` at the release tag, so a rename needs a new release.

## Releasing version X.Y.Z

1. **Release commit.** On a branch, set `version=X.Y.Z` in `gradle.properties` and `X.Y.Z` in
   `action/cli-version`, then merge it into `main`. The action of that commit downloads CLI X.Y.Z;
   this repository's CI runs the action with `version: source`, so it keeps working meanwhile.
2. **Tag.** `git tag vX.Y.Z` on the merged commit and `git push origin vX.Y.Z`. The **Release**
   workflow checks that the tag matches both files, builds and tests, creates a **draft** GitHub
   Release with `storepilot.jar`, `storepilot.jar.sha256`, and `storepilot-X.Y.Z.zip`, and uploads
   the core modules to Maven Central.
3. **Maven Central.** In the Central Portal, check the deployment (three artifacts:
   `storepilot-core-api`, `storepilot-core-engine`, `storepilot-google-play`) and publish it. It can
   take up to about 30 minutes until the artifacts can be downloaded.
4. **Gradle plugin.** Run the **Publish plugin** workflow with the tag `vX.Y.Z`. The first version of a
   plugin goes through a manual review by the Plugin Portal.
5. **GitHub Release.** Edit the draft release, tick *Publish this Action to the GitHub Marketplace*
   (the first time, accept the agreement and pick categories), and publish it. The **Major tag**
   workflow then moves `vX` (for example `v0`) to the release, which is what `@v0` users get.
6. **Next version.** Set `version=X.Y.(Z+1)-SNAPSHOT` in `gradle.properties` and `source` in
   `action/cli-version`, and merge that into `main`.

If a step fails before anything is public, delete the draft release and the tag, fix the problem, and
start again from step 1. After step 3, a version number cannot be reused; release the fix as the next
patch version.
