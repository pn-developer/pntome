#!/usr/bin/env bash
# Property 20: Release notes contain the build facts.
# For any valid version, commit, signing state and watch state, the output of release-notes.sh
# contains the full SHA, the short commit, the release tag, the versionCode, the string
# "v<version> (<short>)", the signing state, the Connect IQ state and the
# "sha256sum -c SHA256SUMS" instruction.
# Validates: Requirements 12.4, 14.2
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/release-notes.sh"
[[ -f $SCRIPT ]] || fail "missing $SCRIPT"

gen_hex() { # gen_hex LEN -> REPLY of LEN lowercase hex digits
  local len=$1 out="" i
  for (( i = 0; i < len; i++ )); do
    rand_int 0 15
    out+=$(printf '%x' "$REPLY")
  done
  REPLY=$out
}

has() { # has TEXT NEEDLE LABEL
  [[ $1 == *"$2"* ]] || {
    printf '%s\n' "$1" >&2
    fail "case $i: notes lack $3: '$2'"
  }
}

cl="$TEST_TMP/CHANGELOG.md"

for (( i = 1; i <= ITER; i++ )); do
  gen_version any; version=$REPLY
  gen_hex 40; full=$REPLY
  rand_int 0 1
  if (( REPLY == 0 )); then short=${full:0:7}; else gen_hex 7; short=$REPLY; fi
  rand_int 0 1; (( REPLY == 1 )) && signed=true || signed=false
  rand_int 0 1; (( REPLY == 1 )) && built=true || built=false

  {
    printf '# Changelog\n\n## [Unreleased]\n\n- unreleased item\n\n'
    printf '## [%s]\n\n- stable item\n\n' "${version%%-*}"
  } > "$cl"

  out=$(CHANGELOG_FILE="$cl" COMMIT_FULL="$full" COMMIT="$short" SIGNED="$signed" \
        WATCH_BUILT="$built" bash "$SCRIPT" "$version" 2>"$TEST_TMP/err") || {
    cat "$TEST_TMP/err" >&2
    fail "case $i: release-notes.sh failed for $version"
  }

  code=$(ref_code "$version")
  has "$out" "$full" "full SHA"
  has "$out" "(short: $short)" "short commit"
  has "$out" "Tag: v$version" "release tag"
  has "$out" "versionCode: $code" "versionCode"
  has "$out" "v$version ($short)" "watch version string"
  has "$out" "sha256sum -c SHA256SUMS" "checksum instruction"

  if [[ $signed == true ]]; then
    has "$out" "Signing: signed" "signing state"
    [[ $out != *UNSIGNED* ]] || fail "case $i: signed build reported UNSIGNED"
  else
    has "$out" "Signing: UNSIGNED" "signing state"
  fi
  if [[ $built == true ]]; then
    has "$out" "Connect IQ package: included" "Connect IQ state"
  else
    has "$out" "Connect IQ package: not built by CI" "Connect IQ state"
  fi
  pass "case $i: $version $short signed=$signed watch=$built"
done

finish
