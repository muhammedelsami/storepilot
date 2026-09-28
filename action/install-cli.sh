#!/usr/bin/env bash
# Checks Java, then downloads the CLI jar of a release (checking its SHA-256) or builds it from source.
set -euo pipefail

if ! command -v java > /dev/null; then
  echo "::error::StorePilot needs Java 17 or later. Add actions/setup-java before this action."
  exit 1
fi
java_major="$(java -version 2>&1 | head -n 1 | sed -E 's/.*version "(1\.)?([0-9]+).*/\2/')"
if [[ ! "$java_major" =~ ^[0-9]+$ ]] || (( java_major < 17 )); then
  echo "::error::StorePilot needs Java 17 or later, found: $(java -version 2>&1 | head -n 1)"
  exit 1
fi

# From source, Gradle decides whether the jar is up to date.
if [[ "$STOREPILOT_CLI_VERSION" == "source" ]]; then
  echo "Building the StorePilot CLI from source."
  "$GITHUB_ACTION_PATH/gradlew" --project-dir "$GITHUB_ACTION_PATH" --quiet :cli:shadowJar
  exit 0
fi

if [[ -f "$STOREPILOT_CLI_JAR" ]]; then
  echo "Using the cached StorePilot CLI $STOREPILOT_CLI_VERSION."
  exit 0
fi

url="https://github.com/muhammedelsami/storepilot/releases/download/v$STOREPILOT_CLI_VERSION"
dir="$(dirname "$STOREPILOT_CLI_JAR")"
mkdir -p "$dir"
echo "Downloading the StorePilot CLI $STOREPILOT_CLI_VERSION."
curl --fail --silent --show-error --location --retry 3 --output "$dir/storepilot.jar.download" "$url/storepilot.jar"
expected="$(curl --fail --silent --show-error --location --retry 3 "$url/storepilot.jar.sha256" | cut -d ' ' -f 1)"
if command -v sha256sum > /dev/null; then
  actual="$(sha256sum "$dir/storepilot.jar.download" | cut -d ' ' -f 1)"
else
  actual="$(shasum -a 256 "$dir/storepilot.jar.download" | cut -d ' ' -f 1)"
fi
if [[ "$expected" != "$actual" ]]; then
  rm -f "$dir/storepilot.jar.download"
  echo "::error::The downloaded StorePilot CLI does not match its SHA-256 ($actual, expected $expected)."
  exit 1
fi
mv "$dir/storepilot.jar.download" "$STOREPILOT_CLI_JAR"
