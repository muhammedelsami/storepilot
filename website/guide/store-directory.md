# The store directory

Everything StorePilot publishes, apart from the app itself, lives in one directory: `store/` next to
the module's build file for the Gradle plugin, or next to `storepilot.yml` for the command line.

```
store/
├── details.yml                        default language and contact details
├── release-notes/
│   ├── en-US.txt                      "What's new" for every store
│   ├── tr-TR.txt
│   └── google-play/en-US.txt          optional: wins for Google Play
└── listing/
    ├── en-US/
    │   ├── title.txt
    │   ├── short-description.txt
    │   ├── full-description.txt
    │   ├── video-url.txt              optional
    │   └── graphics/
    │       ├── icon.png
    │       ├── feature-graphic.png
    │       ├── tv-banner.png
    │       ├── phone-screenshots/01.png, 02.png, …
    │       ├── tablet-7-screenshots/
    │       ├── tablet-10-screenshots/
    │       ├── tv-screenshots/
    │       └── wear-screenshots/
    └── tr-TR/…
```

You do not need all of it. A directory with only `listing/en-US/title.txt` is valid; StorePilot manages
what is there and leaves everything else in the store alone.

`storepilot listing pull` (or `./gradlew pullListing`) writes `details.yml` and `listing/` from an
app's live listing. Release notes belong to a release, so they are not pulled.

## Languages

Directory and file names use BCP-47 language tags in their canonical form: `en-US`, `tr-TR`,
`zh-Hans-CN`. `en-us` and `en_US` are rejected with a message that names the correct form. Google
Play's legacy codes such as `iw-IL` are read as their modern form (`he-IL`) and written back the way
Play expects.

## details.yml

App-level details, all optional:

```yaml
default-language: en-US
contact-email: "support@example.com"
contact-website: "https://example.com"
contact-phone: "+90 555 000 00 00"
```

Values are read as written, so a phone number such as `0555` keeps its leading zero.

## Text files

`title.txt`, `short-description.txt`, `full-description.txt`, and `video-url.txt` hold one field each.

- Line breaks at the end of a file are removed, so editors that add a final newline are fine.
  Other whitespace is kept, and the `trailing-whitespace` lint rule points it out.
- `\r\n` line endings become `\n`.
- An empty file is ignored with a warning; the field keeps its value in the store.
- Any other file in a language directory is an error, which catches typos such as `titel.txt`.

## Release notes

`release-notes/<language>.txt` holds the "What's new" text of the next release in that language. A
file under `release-notes/google-play/` wins for Google Play, for text that differs between stores.
Google Play allows 500 characters per language.

## Graphics

| File or directory | Google Play image | Count |
|---|---|---|
| `icon.png` | App icon | 1, PNG only |
| `feature-graphic.png` or `.jpg` | Feature graphic | 1 |
| `tv-banner.png` or `.jpg` | TV banner | 1 |
| `phone-screenshots/` | Phone screenshots | 2 to 8 |
| `tablet-7-screenshots/` | 7-inch tablet screenshots | up to 8 |
| `tablet-10-screenshots/` | 10-inch tablet screenshots | up to 8 |
| `tv-screenshots/` | TV screenshots | up to 8 |
| `wear-screenshots/` | Wear OS screenshots | up to 8 |

Screenshots appear in file name order, so number them: `01.png`, `02.png`. StorePilot reads the
format from the file's content, not its extension. [Listing checks](/guide/listing-checks) lists the
size rules.

## Fallback to the default language

With `fallbackToDefaultLanguage: true`, a language directory that lacks a text field takes the text
of `default-language` from `details.yml`. This helps when you translate titles first and descriptions
later. Graphics do not fall back. The locale coverage table of `listing validate` marks fallback text
as `fallback`.
