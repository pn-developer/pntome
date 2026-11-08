#!/usr/bin/env bash
# Feature: apache-license-ci-release, Property 7: Set version is idempotent
# For any valid Version, running set-version.sh twice leaves the working tree identical to
# running it once.
# Validates: Requirements 5.11
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Hash of every file in the copy (excluding .git), with relative paths.
tree_hash() {
  (cd "$1" && find . -path ./.git -prune -o -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum)
}

# Add a bullet under "## [Unreleased]" so a stable release is allowed.
add_bullet() {
  local cl="$1/CHANGELOG.md" tmp="$1/CHANGELOG.md.new"
  awk '{ print } /^##[[:space:]]+\[Unreleased\][[:space:]]*$/ && !d { print ""; print "- property test entry"; d=1 }' \
    "$cl" >"$tmp" && mv "$tmp" "$cl"
}

ran=0
for (( i = 0; i < ITER; i++ )); do
  gen_version any
  v=$REPLY
  make_repo_copy
  repo=$REPO_COPY
  case $v in *-rc.*) ;; *) add_bullet "$repo" ;; esac

  out1="$(cd "$repo" && bash scripts/set-version.sh "$v" --allow-downgrade 2>&1)"
  rc1=$?
  if (( rc1 != 0 )); then
    # e.g. CHANGELOG already holds a section for this version; not a case for this property.
    (( rc1 == 1 )) && continue
    fail "first run of set-version.sh $v exited $rc1: $out1"
  fi
  h1="$(tree_hash "$repo")"

  out2="$(cd "$repo" && bash scripts/set-version.sh "$v" --allow-downgrade 2>&1)"
  rc2=$?
  (( rc2 == 0 )) || fail "second run of set-version.sh $v exited $rc2: $out2"
  h2="$(tree_hash "$repo")"

  if [[ $h1 != "$h2" ]]; then
    fail "tree changed on second run for $v: $(diff <(echo "$h1") <(echo "$h2") | head -n 5)"
  fi
  ran=$(( ran + 1 ))
  pass "idempotent for $v"
done

(( ran > 0 )) || fail "no iteration completed"
finish
