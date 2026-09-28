#!/usr/bin/env bash
# Decides which StorePilot CLI the action runs and where the jar lives.
# action/cli-version holds the version of an action release, or "source" on a branch.
set -euo pipefail

version="${INPUT_VERSION:-}"
if [[ -z "$version" ]]; then
  version="$(tr -d '[:space:]' < "$GITHUB_ACTION_PATH/action/cli-version")"
fi

if [[ "$version" == "source" ]]; then
  dir="$GITHUB_ACTION_PATH/cli/build/libs"
else
  dir="${RUNNER_TOOL_CACHE:-$HOME/.cache}/storepilot-cli/$version"
fi

{
  echo "version=$version"
  echo "dir=$dir"
  echo "jar=$dir/storepilot.jar"
} >> "$GITHUB_OUTPUT"
