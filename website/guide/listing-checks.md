# Listing checks

`listing validate` (or `./gradlew validateListing`) checks the store directory without a network
connection or a key. Errors fail the command with exit code 1; warnings do not.

<div class="shot-frame"><img class="shot" src="/screenshots/validate-problems.svg" alt="Output of storepilot listing validate with two errors and two warnings: a 35-character title, a 1080 by 2400 screenshot, a line ending with whitespace, and a screenshot with an alpha channel."></div>

## Google Play rules

Checked against the Play Console Help on 2026-09-28. Text length is counted in Unicode characters,
the way Play counts it, so an emoji counts as one.

| Item | Rule |
|---|---|
| Title | 30 characters |
| Short description | 80 characters |
| Full description | 4000 characters |
| Release notes | 500 characters per language |
| Video URL | One YouTube video, without a playlist, a channel, or extra parameters such as a start time |
| App icon | PNG, 512 × 512 px, at most 1024 KB |
| Feature graphic | PNG or JPEG, 1024 × 500 px |
| TV banner | PNG or JPEG, 1280 × 720 px |
| Screenshots | PNG or JPEG; sides 320 to 3840 px (tablets up to 7680 px); the long side at most twice the short side; up to 8 per type; at least 2 for phones |
| Wear OS screenshots | Square, at least 384 px, up to 8 |

::: tip Tall phone screenshots
Many phones take 20:9 screenshots such as 1080 × 2400, which breaks the "at most twice as long" rule.
Crop them to 1080 × 2160 (18:9) or 1080 × 1920 (16:9).
:::

## Lint rules

Lint rules report things Play accepts but that are usually mistakes. Each one only warns.

| ID | Warns about |
|---|---|
| `title-in-short-description` | The short description contains the title. |
| `trailing-whitespace` | A line of a text field ends with spaces or tabs. |
| `empty-locale` | A language directory with no text and no graphics. |
| `mixed-orientation` | Portrait and landscape images in one screenshot type. |
| `image-alpha` | An image with an alpha channel where Play asks for none. Play may still accept it, so this is a warning. |

Turn rules off, or make every warning an error:

```yaml
# storepilot.yml
aso:
  disable: [title-in-short-description]
  warningsAsErrors: true
```

## Locale coverage

After the problems, `validate` prints a table of every language: `yes` for text written in that
language, `fallback` for text taken from the default language, `-` for missing text, and the number of
images per graphic type.

## Diff

`listing diff` compares the store directory with the live listing and marks every item:

| Status | Meaning | On push |
|---|---|---|
| `unchanged` | Same in both. | Nothing. |
| `changed` | Different text. | Updated. |
| `added` | In the repository, not in the store. | Added. |
| `removed` | An image in the store that the repository's set of that type does not have. | Deleted (only with `replaceScreenshots: true`). |
| `remote only` | In the store, not managed by the repository. | Left alone. |

Images are compared by SHA-256, so renaming a file changes nothing, and a new order replaces the
images of that type.

## Push rules

- Only what the repository has is pushed. A language, field, or graphic type that only the store has
  stays as it is.
- With `replaceScreenshots: true` (the default), the images of a type in the store become exactly the
  images in the repository, in file name order. With `false`, StorePilot only adds images the store
  does not have.
- `--text-only` (or `listing.graphics: false`) pushes details and text, no graphics.

## Unsupported parts

Every store supports different fields and graphic types. When a store cannot take something in the
directory, StorePilot stops with an error. `onUnsupported: warn` skips that part for that store and
warns instead. Two things are never skipped: the artifact type, and a staged rollout, because a full
release in its place could not be undone.
