// Renders captured StorePilot output (scripts/output/*.txt) as terminal screenshots in SVG, so the
// images on the site show what the tool really prints and stay sharp at any size.
// The captures come from the CLI and the Gradle plugin; see scripts/output/README.md.

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const outDir = join(root, 'public', 'screenshots')
mkdirSync(outDir, { recursive: true })

const shots = [
  { file: 'validate-ok', command: 'storepilot listing validate' },
  { file: 'validate-problems', command: 'storepilot listing validate' },
  { file: 'diff', command: 'storepilot listing diff --package com.example.notes' },
  {
    file: 'publish-dry-run',
    command: 'storepilot publish --artifact app-release.aab --track production --rollout 0.1 --with-listing --dry-run',
  },
  { file: 'gradle-tasks', command: './gradlew :app:tasks --group=StorePilot' },
]

// A dark terminal in the site's ink color, the same in light and dark mode.
const color = {
  background: '#0B2033',
  bar: '#10283F',
  text: '#DCE6EE',
  dim: '#8FA3B5',
  prompt: '#5FD39A',
  error: '#FF8A9B',
  warning: '#F2C46D',
  success: '#5FD39A',
}
const fontSize = 13
const charWidth = 7.83 // JetBrains Mono at 13 px
const lineHeight = 20
const padX = 20
const barHeight = 34

function escape(text) {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

// Browsers collapse spaces in an SVG shown with <img>, which breaks aligned columns. No-break spaces
// keep them.
function keepSpaces(text) {
  return escape(text).replace(/ /g, '\u00a0')
}

function lineColor(line) {
  if (/: error: /.test(line) || /^\S.*: \d+ errors?/.test(line) && !/: 0 errors/.test(line)) return color.error
  if (/: warning: /.test(line)) return color.warning
  if (/: 0 errors, 0 warnings$|: committed$/.test(line)) return color.success
  if (/^\s+before: |^-+$/.test(line)) return color.dim
  return color.text
}

function wrap(text, width) {
  if (text.length <= width) return [text]
  const lines = []
  let rest = text
  while (rest.length > width) {
    let cut = rest.lastIndexOf(' ', width)
    if (cut <= 0) cut = width
    lines.push(rest.slice(0, cut))
    rest = '    ' + rest.slice(cut).trimStart()
  }
  lines.push(rest)
  return lines
}

for (const shot of shots) {
  const output = readFileSync(join(root, 'scripts', 'output', `${shot.file}.txt`), 'utf8').replace(/\s+$/, '')
  const maxColumns = 112
  const commandLines = wrap(`$ ${shot.command}`, maxColumns)
  const outputLines = output.split('\n').flatMap((line) => wrap(line, maxColumns))
  const columns = Math.max(...commandLines.map((l) => l.length), ...outputLines.map((l) => l.length))
  const width = Math.ceil(columns * charWidth + padX * 2)
  const rows = commandLines.length + 1 + outputLines.length
  const height = barHeight + 18 + rows * lineHeight + 8

  const text = []
  let y = barHeight + 18 + fontSize
  for (const line of commandLines) {
    text.push(`<text x="${padX}" y="${y}" fill="${color.prompt}">${keepSpaces(line)}</text>`)
    y += lineHeight
  }
  y += lineHeight
  for (const line of outputLines) {
    text.push(`<text x="${padX}" y="${y}" fill="${lineColor(line)}">${keepSpaces(line)}</text>`)
    y += lineHeight
  }

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 ${width} ${height}" role="img" aria-label="${escape(`Output of ${shot.command}`)}">
  <rect width="${width}" height="${height}" rx="10" fill="${color.background}"/>
  <path d="M0 10a10 10 0 0 1 10-10h${width - 20}a10 10 0 0 1 10 10v${barHeight - 10}H0z" fill="${color.bar}"/>
  <text x="${width / 2}" y="21" fill="${color.dim}" font-family="'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace" font-size="12" text-anchor="middle">terminal</text>
  <g font-family="'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace" font-size="${fontSize}" xml:space="preserve" style="white-space: pre">
    ${text.join('\n    ')}
  </g>
</svg>
`
  writeFileSync(join(outDir, `${shot.file}.svg`), svg)
  console.log(`screenshots/${shot.file}.svg ${width}x${height}`)
}
