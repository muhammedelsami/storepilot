#!/usr/bin/env bash
# Turns the action inputs into CLI arguments, runs the CLI, and sets the outputs and the job summary.
set -euo pipefail

# Every input is set by action.yml; the defaults only matter when the script runs on its own.
: "${INPUT_COMMAND:=publish}" "${INPUT_ARTIFACT:=}" "${INPUT_PACKAGE_NAME:=}" "${INPUT_STORE:=google-play}"
: "${INPUT_CONFIG:=}" "${INPUT_METADATA_DIR:=}" "${INPUT_TRACK:=}" "${INPUT_FROM_TRACK:=}" "${INPUT_ROLLOUT:=}"
: "${INPUT_WITH_LISTING:=false}" "${INPUT_TEXT_ONLY:=false}" "${INPUT_DRY_RUN:=false}"

fail() {
  echo "::error::$1"
  exit 1
}

is_true() {
  [[ "$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')" == "true" ]]
}

# Hide the key's lines in the log. GitHub already hides values from secrets; this also covers keys
# from other sources. Short lines such as "{" are left alone, because masking them hides every "{".
if [[ -n "${STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON:-}" ]]; then
  while IFS= read -r line; do
    if (( ${#line} >= 16 )); then echo "::add-mask::$line"; fi
  done <<< "$STOREPILOT_GOOGLE_PLAY_SERVICE_ACCOUNT_JSON"
fi

# The artifact input may be a glob; it must match exactly one file. "**" needs bash 4 or later.
resolve_artifact() {
  [[ -n "$INPUT_ARTIFACT" ]] || fail "The publish command needs the artifact input."
  shopt -s nullglob
  shopt -s globstar 2> /dev/null || true
  local IFS=
  local matches=($INPUT_ARTIFACT)
  shopt -u nullglob
  (( ${#matches[@]} == 1 )) || fail "The artifact input '$INPUT_ARTIFACT' matches ${#matches[@]} files; it must match one."
  artifact="${matches[0]}"
}

args=()
case "$INPUT_COMMAND" in
  publish)
    resolve_artifact
    args=(publish --artifact "$artifact")
    [[ -n "$INPUT_TRACK" ]] && args+=(--track "$INPUT_TRACK")
    [[ -n "$INPUT_ROLLOUT" ]] && args+=(--rollout "$INPUT_ROLLOUT")
    is_true "$INPUT_WITH_LISTING" && args+=(--with-listing)
    ;;
  promote)
    [[ -n "$INPUT_FROM_TRACK" && -n "$INPUT_TRACK" ]] || fail "The promote command needs the from-track and track inputs."
    args=(promote --from "$INPUT_FROM_TRACK" --to "$INPUT_TRACK")
    [[ -n "$INPUT_ROLLOUT" ]] && args+=(--rollout "$INPUT_ROLLOUT")
    ;;
  halt | resume)
    [[ -n "$INPUT_TRACK" ]] || fail "The $INPUT_COMMAND command needs the track input."
    args=("$INPUT_COMMAND" --track "$INPUT_TRACK")
    ;;
  listing-push)
    args=(listing push)
    is_true "$INPUT_TEXT_ONLY" && args+=(--text-only)
    ;;
  listing-diff)
    args=(listing diff --exit-code)
    ;;
  listing-validate)
    args=(listing validate)
    ;;
  *)
    fail "Unknown command '$INPUT_COMMAND'. Use publish, promote, halt, resume, listing-push, listing-diff, or listing-validate."
    ;;
esac

if is_true "$INPUT_DRY_RUN"; then
  case "$INPUT_COMMAND" in
    publish | promote | halt | resume | listing-push) args+=(--dry-run) ;;
    *) fail "The dry-run input only works with publish, promote, halt, resume, and listing-push." ;;
  esac
fi

args+=(--store "$INPUT_STORE" --output json)
[[ -n "$INPUT_CONFIG" ]] && args+=(--config "$INPUT_CONFIG")
[[ -n "$INPUT_PACKAGE_NAME" ]] && args+=(--package "$INPUT_PACKAGE_NAME")
[[ -n "$INPUT_METADATA_DIR" ]] && args+=(--metadata-dir "$INPUT_METADATA_DIR")
[[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && args+=(--summary-file "$GITHUB_STEP_SUMMARY")

echo "storepilot ${args[*]}"
set +e
result="$(java -jar "$STOREPILOT_CLI_JAR" "${args[@]}")"
code=$?
set -e
echo "$result"

delimiter="STOREPILOT_$RANDOM$RANDOM$RANDOM"
{
  echo "result<<$delimiter"
  echo "$result"
  echo "$delimiter"
} >> "$GITHUB_OUTPUT"

# listing diff --exit-code: 3 means the listing differs, which is not a failure here.
if [[ "$INPUT_COMMAND" == "listing-diff" ]]; then
  case "$code" in
    0) echo "listing-changed=false" >> "$GITHUB_OUTPUT" ;;
    3) echo "listing-changed=true" >> "$GITHUB_OUTPUT"; code=0 ;;
  esac
fi
exit "$code"
