---
layout: page
sidebar: false
aside: false
title: StorePilot
titleTemplate: Android releases and Play Store listings from your repository
---

<div class="home-page">

<HomeHero />

<section class="home-section">

## Start from the tool you already use

<p class="lead">All three run the same engine and read the same <code>store/</code> directory, so a team can mix them: the Gradle plugin on a laptop, the action in CI.</p>

<div class="vp-doc home-code">

::: code-group

```kotlin [Gradle plugin]
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.muhammedelsami.storepilot") version "0.1.1"
}

storepilot {
    googlePlay {
        serviceAccountJson = providers.environmentVariable("PLAY_SERVICE_ACCOUNT_JSON")
    }
}

// ./gradlew validateListing       check app/store/, no network
// ./gradlew publishReleaseBundle  upload the release bundle
```

```yaml [GitHub Action]
# .github/workflows/publish.yml
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: 17

- uses: muhammedelsami/storepilot@v0
  with:
    artifact: app/build/outputs/bundle/release/*.aab
    metadata-dir: app/store
    track: production
    rollout: "0.1"
    google-play-service-account-json: ${{ secrets.PLAY_SERVICE_ACCOUNT_JSON }}
```

```sh [Command line]
# Java 17 or later. Download storepilot-0.1.1.zip from the GitHub release.
export STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON="$(cat play-key.json)"

storepilot listing validate
storepilot publish --artifact app-release.aab --track production --rollout 0.1
```

:::

</div>

</section>

<section class="home-section">
<div class="home-split">
<div>

## Catch listing problems in the pull request

<p class="lead">Play rejects a title that is one character too long or a screenshot with the wrong shape only after you press publish. StorePilot checks the files first, without a network connection.</p>

</div>
<ul class="home-checks">
  <li><strong>Text limits</strong> counted the way Play counts them: 30 characters for the title, 80 for the short description, 4000 for the full one, 500 for release notes.</li>
  <li><strong>Image rules</strong> for the icon, feature graphic, and every screenshot type: format, size, aspect ratio, and count.</li>
  <li><strong>Lint rules</strong> for the small mistakes reviewers miss, such as trailing spaces or the title repeated in the short description.</li>
  <li><strong>Locale coverage</strong>, so a language with a missing description stands out.</li>
</ul>
</div>

<div class="shot-frame"><img class="shot" src="/screenshots/validate-problems.svg" alt="Terminal output of storepilot listing validate with two errors, a title that is 35 characters long and a screenshot of 1080 by 2400 pixels, and two warnings, followed by a locale coverage table."></div>

</section>

<section class="home-section">

## A release, from files to the store

<p class="lead">The same four steps work with every entry point. Nothing reaches Google Play until the last one, and even that can run as a dry run first.</p>

<ol class="home-steps">
  <li>
    <div>
      <h3>Put the listing in the repository</h3>
      <p>Write <code>store/</code> by hand, or pull the live listing of an existing app with <code>storepilot listing pull</code>. Text, graphics, and release notes become files you can review.</p>
    </div>
  </li>
  <li>
    <div>
      <h3>Check it in every pull request</h3>
      <p><code>validateListing</code> runs as part of <code>./gradlew check</code>, and the action writes the result to the job summary.</p>
    </div>
  </li>
  <li>
    <div>
      <h3>See what would change</h3>
      <p><code>storepilot listing diff</code> compares every field and image with the live listing. <code>--exit-code</code> fails a job when the store drifted from the repository.</p>
    </div>
  </li>
  <li>
    <div>
      <h3>Publish</h3>
      <p>Upload the bundle, set the track and rollout, and push the listing in one edit, so they go live together or not at all.</p>
    </div>
  </li>
</ol>

<div class="shot-frame"><img class="shot" src="/screenshots/publish-dry-run.svg" alt="Terminal output of storepilot publish with --dry-run: the bundle upload, a new release on the production track at 10 percent, and the listing changes that would be made, with nothing committed."></div>

</section>

<section class="home-section">

## Works with

<dl class="home-facts">
  <div><dt>Gradle</dt><dd>8.10 or later</dd></div>
  <div><dt>Android Gradle plugin</dt><dd>8.5 to 9.x</dd></div>
  <div><dt>Java</dt><dd>17 or later</dd></div>
  <div><dt>GitHub runners</dt><dd>Linux, macOS, Windows</dd></div>
</dl>

</section>

<section class="home-section">

## Try it on your app

<p class="lead">The quick start takes you from an empty <code>store/</code> directory to a dry run against your real Play listing.</p>

<div class="home-closing">
  <a class="VPButton medium brand" href="/storepilot/guide/quick-start">Read the quick start</a>
  <a class="VPButton medium alt" href="/storepilot/guide/google-play-setup">Connect Google Play</a>
</div>

</section>

</div>
