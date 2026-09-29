import { defineConfig } from 'vitepress'

const repository = 'https://github.com/muhammedelsami/storepilot'

export default defineConfig({
  title: 'StorePilot',
  description:
    'Publish Android releases and Google Play store listings from files in your repository. Gradle plugin, CLI, and GitHub Action.',
  lang: 'en-US',
  base: '/storepilot/',
  cleanUrls: true,
  lastUpdated: true,
  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/storepilot/favicon.svg' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.googleapis.com' }],
    ['link', { rel: 'preconnect', href: 'https://fonts.gstatic.com', crossorigin: '' }],
    [
      'link',
      {
        rel: 'stylesheet',
        href: 'https://fonts.googleapis.com/css2?family=Instrument+Sans:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap',
      },
    ],
    ['meta', { name: 'theme-color', content: '#0F2A43' }],
    ['meta', { property: 'og:title', content: 'StorePilot' }],
    ['meta', { property: 'og:description', content: 'Your Play Store listing, kept in your repository.' }],
  ],
  themeConfig: {
    logo: { src: '/logo.svg', alt: '' },
    nav: [
      { text: 'Guide', link: '/guide/introduction', activeMatch: '/guide/' },
      { text: 'Reference', link: '/reference/configuration', activeMatch: '/reference/' },
      { text: 'Roadmap', link: '/roadmap' },
      {
        text: '0.1.1',
        items: [
          { text: 'Release notes', link: `${repository}/releases` },
          { text: 'Gradle Plugin Portal', link: 'https://plugins.gradle.org/plugin/io.github.muhammedelsami.storepilot' },
          { text: 'GitHub Marketplace', link: 'https://github.com/marketplace/actions/storepilot-android-publisher' },
          { text: 'Maven Central', link: 'https://central.sonatype.com/namespace/io.github.muhammedelsami.storepilot' },
        ],
      },
    ],
    sidebar: {
      '/': [
        {
          text: 'Start here',
          items: [
            { text: 'What StorePilot does', link: '/guide/introduction' },
            { text: 'Quick start', link: '/guide/quick-start' },
            { text: 'Connect Google Play', link: '/guide/google-play-setup' },
          ],
        },
        {
          text: 'Use it',
          items: [
            { text: 'Gradle plugin', link: '/guide/gradle-plugin' },
            { text: 'GitHub Action', link: '/guide/github-action' },
            { text: 'Command line', link: '/guide/cli' },
          ],
        },
        {
          text: 'Concepts',
          items: [
            { text: 'The store directory', link: '/guide/store-directory' },
            { text: 'Releases and tracks', link: '/guide/releases' },
            { text: 'Listing checks', link: '/guide/listing-checks' },
          ],
        },
        {
          text: 'Reference',
          items: [
            { text: 'Configuration', link: '/reference/configuration' },
            { text: 'Credentials and security', link: '/reference/credentials' },
            { text: 'Troubleshooting', link: '/reference/troubleshooting' },
          ],
        },
        { text: 'Roadmap', link: '/roadmap' },
      ],
    },
    socialLinks: [{ icon: 'github', link: repository }],
    editLink: {
      pattern: `${repository}/edit/main/website/:path`,
      text: 'Edit this page on GitHub',
    },
    search: { provider: 'local' },
    outline: { level: [2, 3] },
    footer: {
      message: 'Released under the Apache License 2.0.',
      copyright: 'StorePilot is not affiliated with Google. Google Play is a trademark of Google LLC.',
    },
  },
})
