# android-gradle

A minimal Android app that uses the StorePilot Gradle plugin. `settings.gradle.kts` builds the plugin
from this repository with `includeBuild("../..")`, so the sample always runs the current code.

The listing lives in `app/store/` (details, release notes, text in two languages, and a few generated
images). The sample has its own Gradle wrapper, because the newest Android Gradle plugin needs a newer
Gradle than the repository build.

Needs an Android SDK (`ANDROID_HOME` or `local.properties`) and JDK 17.

```
./gradlew :app:validateListing                     # check app/store/ against Google Play's rules, no network
./gradlew :app:tasks --group=StorePilot            # every StorePilot task
./gradlew :app:publishReleaseBundle --dry-run      # the task graph, without running it
```

Talking to Google Play needs a service account key of a Play Console app with this package name:

```
export PLAY_SERVICE_ACCOUNT_JSON="$(cat play-key.json)"
./gradlew :app:diffListing                         # read only
./gradlew :app:publishReleaseBundle -Pstorepilot.dryRun=true
```
