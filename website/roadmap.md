# Roadmap

StorePilot 0.1 covers Google Play releases, listings, graphics, and listing checks. The ideas below
are designed but not scheduled. Each needs decisions that are still open; the notes say which.
Comments and pull requests are welcome in the
[issue tracker](https://github.com/muhammedelsami/storepilot/issues).

## Machine translation

Fill in listing languages from a source language:

```sh
storepilot listing translate --from en-US --to de-DE,fr-FR,ja-JP
```

- Translations are written into `store/` as normal files, so they are reviewed in a pull request
  before anything reaches the store. StorePilot never pushes a translation directly.
- Only missing fields are translated; existing files change only with `--overwrite`.
- Translated text goes through the same limits as your own. A German title that grows past 30
  characters is reported, never cut.
- Translation services plug in the same way stores do. Services with a free monthly allowance, such as
  Google Cloud Translation or DeepL, cover a typical app many times over: a full listing is about
  4,600 characters per language.

Open points: which service comes first, and whether StorePilot records the source text so that
`validate` can warn when a translation is older than its source.

## Screenshot generation

Produce store screenshots from the app's own screenshot tests instead of taking them by hand:

```sh
./gradlew generateStoreScreenshots
```

- Collect the images of a screenshot testing tool, such as Roborazzi, Paparazzi, or Compose Preview
  Screenshot Testing, for each language and device type.
- Write them into `store/listing/<language>/graphics/<type>/` with the file names StorePilot expects.
- Make them fit Google Play's rules: no alpha channel, and at most twice as long as wide.
- Optionally add a device frame, a background, and a caption per language.

Open points: which tool comes first, and whether frames and captions are part of the first version.

## More Android stores

StorePilot's engine does not depend on Google Play; the Play support is one adapter. Adapters for
stores such as Huawei AppGallery or the Samsung Galaxy Store would add a `huaweiAppGallery { }`
block, inputs such as `huawei-client-id`, and their own listing rules, without changing how the
store directory, the commands, or the action work.

## Smaller items

- Change the percentage of a rollout that is already live.
- Declare and test support for Gradle's isolated projects.
