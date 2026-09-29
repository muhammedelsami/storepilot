# Captured output

The terminal screenshots on the site are rendered from these files by `npm run screenshots`.

| File | Captured with |
|---|---|
| `validate-ok.txt` | `storepilot listing validate` in a copy of `samples/android-gradle/app`, with the CLI jar. |
| `validate-problems.txt` | The same, after lengthening the title, adding a trailing space, a 1080 × 2400 screenshot, and a screenshot with an alpha channel. |
| `gradle-tasks.txt` | `./gradlew :app:tasks --group=StorePilot` in `samples/android-gradle`. |
| `diff.txt`, `publish-dry-run.txt` | The CLI's commands run in a test against the in-memory fake store registered as `google-play`, with the sample's `store/` directory and a small live listing. The text is what the CLI prints; only the store is simulated. |

Refresh a capture by running the command again and replacing the file.
