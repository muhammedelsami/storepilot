<script setup lang="ts">
import { VPButton } from 'vitepress/theme'

// Files of the store directory, and where each one lands on the Play listing. Coordinates are in the
// diagram's viewBox (600 x 440).
const tree = [
  { text: 'store/', y: 70 },
  { text: '├── details.yml', y: 100, route: { to: [382, 338] } },
  { text: '├── release-notes/', y: 130 },
  { text: '│   └── en-US.txt', y: 160, route: { to: [382, 286] } },
  { text: '└── listing/en-US/', y: 190 },
  { text: '    ├── title.txt', y: 220, route: { to: [382, 52] } },
  { text: '    ├── short-description.txt', y: 250, route: { to: [382, 120] } },
  { text: '    └── graphics/', y: 280 },
  { text: '        ├── icon.png', y: 310, route: { to: [382, 78] } },
  { text: '        └── phone-screenshots/', y: 340, route: { to: [382, 198] } },
]

const charWidth = 8.4
const routes = tree
  .filter((line) => line.route)
  .map((line, index) => {
    const from = [8 + line.text.length * charWidth + 10, line.y - 5]
    const [tx, ty] = line.route!.to
    return {
      from,
      to: [tx, ty],
      d: `M${from[0]} ${from[1]} C${from[0] + 70} ${from[1]}, ${tx - 70} ${ty}, ${tx} ${ty}`,
      delay: `${0.2 + index * 0.14}s`,
    }
  })
</script>

<template>
  <section class="hero" aria-labelledby="hero-title">
    <div class="hero-text">
      <h1 id="hero-title">Your Play Store listing, kept in your repository.</h1>
      <p class="hero-lead">
        StorePilot checks, compares, and publishes Android releases and Google Play listings from files
        you review in pull requests. Run it as a Gradle plugin, a GitHub Action, or a command-line tool.
      </p>
      <div class="hero-actions">
        <VPButton tag="a" size="big" theme="brand" text="Read the quick start" href="/guide/quick-start" />
        <VPButton tag="a" size="big" theme="alt" text="View on GitHub" href="https://github.com/muhammedelsami/storepilot" />
      </div>
      <p class="hero-note">
        Free and open source under the Apache License 2.0. Google Play today; more Android stores later.
      </p>
    </div>

    <figure class="hero-figure">
      <svg
        viewBox="0 0 600 440"
        role="img"
        aria-labelledby="hero-diagram-title"
        class="hero-diagram"
      >
        <title id="hero-diagram-title">
          Files in the store directory and the parts of the Google Play listing they become
        </title>

        <g class="tree">
          <text v-for="line in tree" :key="line.text" x="8" :y="line.y">{{ line.text }}</text>
        </g>

        <g class="phone">
          <rect x="372" y="16" width="212" height="408" rx="26" class="phone-frame" />
          <rect x="390" y="44" width="44" height="44" rx="10" class="app-icon" />
          <path d="M404 56 L424 66 L404 76 Z" class="app-icon-mark" />
          <text x="446" y="62" class="app-title">StorePilot Sample</text>
          <text x="446" y="80" class="app-meta">Tools</text>
          <text x="390" y="116" class="app-body">Shows how StorePilot publishes</text>
          <text x="390" y="132" class="app-body">an app from files.</text>
          <rect x="390" y="148" width="54" height="100" rx="6" class="screenshot" />
          <rect x="452" y="148" width="54" height="100" rx="6" class="screenshot" />
          <rect x="514" y="148" width="54" height="100" rx="6" class="screenshot" />
          <text x="390" y="278" class="app-heading">What's new</text>
          <text x="390" y="296" class="app-body">First release of the sample.</text>
          <text x="390" y="330" class="app-heading">Contact</text>
          <text x="390" y="347" class="app-meta">storepilot@example.com</text>
          <rect x="390" y="374" width="176" height="30" rx="15" class="install" />
          <text x="478" y="394" class="install-text">Install</text>
        </g>

        <g class="routes">
          <g v-for="route in routes" :key="route.d" :style="{ '--delay': route.delay }">
            <path :d="route.d" pathLength="1" class="route" />
            <circle :cx="route.from[0]" :cy="route.from[1]" r="3.5" class="waypoint" />
            <circle :cx="route.to[0]" :cy="route.to[1]" r="3.5" class="waypoint arrival" />
          </g>
        </g>
      </svg>
      <figcaption>
        Each file in <code>store/</code> becomes one part of the Play listing. StorePilot keeps them in sync.
      </figcaption>
    </figure>
  </section>
</template>

<style scoped>
.hero {
  max-width: 1120px;
  margin: 0 auto;
  padding: 64px 24px 72px;
  display: grid;
  grid-template-columns: minmax(0, 5fr) minmax(0, 6fr);
  gap: 48px;
  align-items: center;
}

h1 {
  font-size: clamp(2.4rem, 5vw, 3.6rem);
  line-height: 1.04;
  letter-spacing: -0.03em;
  font-weight: 700;
  color: var(--vp-c-text-1);
  margin: 0 0 20px;
  max-width: 14ch;
}

.hero-lead {
  font-size: 1.15rem;
  line-height: 1.65;
  color: var(--vp-c-text-2);
  margin: 0 0 28px;
  max-width: 48ch;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 20px;
}

.hero-note {
  font-size: 0.9rem;
  color: var(--vp-c-text-3);
  margin: 0;
  max-width: 48ch;
}

.hero-figure {
  margin: 0;
}

.hero-diagram {
  width: 100%;
  height: auto;
  display: block;
}

figcaption {
  font-size: 0.875rem;
  color: var(--vp-c-text-3);
  margin-top: 8px;
}

.tree text {
  font-family: var(--vp-font-family-mono);
  font-size: 14px;
  fill: var(--vp-c-text-1);
  white-space: pre;
}

.phone-frame {
  fill: var(--vp-c-bg);
  stroke: var(--vp-c-divider);
  stroke-width: 1.5;
}

.app-icon {
  fill: #0f2a43;
}

.app-icon-mark {
  fill: #3ddc84;
}

.app-title {
  font-size: 14px;
  font-weight: 700;
  fill: var(--vp-c-text-1);
}

.app-heading {
  font-size: 12.5px;
  font-weight: 650;
  fill: var(--vp-c-text-1);
}

.app-meta {
  font-size: 11.5px;
  fill: var(--vp-c-text-3);
}

.app-body {
  font-size: 12px;
  fill: var(--vp-c-text-2);
}

.screenshot {
  fill: var(--vp-c-bg-soft);
  stroke: var(--vp-c-divider);
}

.install {
  fill: var(--sp-green);
}

.install-text {
  font-size: 12.5px;
  font-weight: 650;
  fill: #ffffff;
  text-anchor: middle;
}

.dark .install-text {
  fill: #0b2033;
}

.route {
  fill: none;
  stroke: var(--sp-route);
  stroke-width: 1.6;
  stroke-dasharray: 1;
  stroke-dashoffset: 1;
  animation: draw 0.9s ease-out forwards;
  animation-delay: var(--delay);
}

.waypoint {
  fill: var(--vp-c-bg);
  stroke: var(--sp-route);
  stroke-width: 1.6;
}

.arrival {
  opacity: 0;
  animation: arrive 0.3s ease-out forwards;
  animation-delay: calc(var(--delay) + 0.8s);
}

@keyframes draw {
  to {
    stroke-dashoffset: 0;
  }
}

@keyframes arrive {
  to {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .route {
    animation: none;
    stroke-dashoffset: 0;
  }

  .arrival {
    animation: none;
    opacity: 1;
  }
}

@media (max-width: 860px) {
  .hero {
    grid-template-columns: minmax(0, 1fr);
    padding: 40px 20px 56px;
    gap: 32px;
  }
}
</style>
