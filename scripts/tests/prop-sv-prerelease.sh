#!/usr/bin/env bash
# Property 8: Pre-release leaves stable-only files untouched
# For every valid pre-release version (X.Y.Z-rc.N), set-version.sh succeeds and changes
# nothing but the four always-edited carriers (VERSION, build.gradle.kts, Version.mc,
# manifest.xml): the F-Droid metadata, CHANGELOG.md and the fastlane changelogs stay
# byte-identical and no new file appears. Runs only in a throwaway copy of the repository.
#
# **Validates: Requirements 5.3, 6.4**
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

ALLOWED=(
  "VERSION"
  "PNTome/app/build.gradle.kts"
  "PNTomeGo/source/Version.mc"
  "PNTomeGo/manifest.xml"
)

make_repo_copy
repo=$REPO_COPY
[[ -x $repo/scripts/set-version.sh || -f $repo/scripts/set-version.sh ]] \
  || fail "set-version.sh missing from the repo copy"

for (( i = 0; i < ITER; i++ )); do
  gen_version rc
  v=$REPLY

  # Reset the copy to the snapshot commit.
  git -C "$repo" reset -q --hard HEAD || fail "reset failed"
  git -C "$repo" clean -fdq || fail "clean failed"

  # --allow-downgrade: the generated rc may be lower than the repo's current VERSION.
  if ! out="$(bash "$repo/scripts/set-version.sh" "$v" --allow-downgrade 2>&1)"; then
    fail "set-version.sh rejected pre-release '$v': $out"
  fi

  # Every changed or new path must be one of the allowed carriers.
  while IFS= read -r line; do
    [[ -n $line ]] || continue
    path=${line:3}
    ok=0
    for a in "${ALLOWED[@]}"; do [[ $path == "$a" ]] && ok=1; done
    (( ok )) || fail "pre-release '$v' touched '$path' (status '${line:0:2}')"
  done < <(git -C "$repo" status --porcelain --untracked-files=all)

  # Explicit byte-level checks on the stable-only files.
  if ! git -C "$repo" diff --quiet HEAD -- \
      fdroid CHANGELOG.md PNTome/fastlane; then
    fail "pre-release '$v' modified F-Droid metadata, CHANGELOG.md or fastlane changelogs"
  fi

  # The always-edited carriers did change to the new version.
  [[ "$(cat "$repo/VERSION")" == "$v" ]] || fail "VERSION is not '$v' after set-version"
  pass "pre-release $v leaves stable-only files untouched"
done

finish
