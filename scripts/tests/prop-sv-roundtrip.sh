#!/usr/bin/env bash
# Feature: apache-license-ci-release, Property 6: Set then check round trip
# For any valid Version, after set-version.sh succeeds in a repo copy, every carrier holds the
# expected value and the version-related checks of check-release.sh report no violation.
# Validates: Requirements 5.1, 5.2, 5.6, 6.1, 6.2, 6.3, 7.2
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Add a bullet under "## [Unreleased]" so a stable release is allowed.
add_bullet() {
  local cl="$1/CHANGELOG.md" tmp="$1/CHANGELOG.md.new"
  awk '{ print } /^##[[:space:]]+\[Unreleased\][[:space:]]*$/ && !d { print ""; print "- property test entry"; d=1 }' \
    "$cl" >"$tmp" && mv "$tmp" "$cl"
}

# Checks that depend on the version carriers; all must stay silent after set-version.sh.
VERSION_CHECKS='^\[(version-file|versions|changelog|fdroid|store-text|tag)\]'

ran=0
for (( i = 0; i < ITER; i++ )); do
  gen_version any
  v=$REPLY
  stable=1
  case $v in *-rc.*) stable=0 ;; esac

  make_repo_copy
  repo=$REPO_COPY
  (( stable )) && add_bullet "$repo"

  out="$(cd "$repo" && bash scripts/set-version.sh "$v" --allow-downgrade 2>&1)"
  rc=$?
  if (( rc != 0 )); then
    # Refusal (e.g. a CHANGELOG section for this version already exists) is not this property.
    (( rc == 1 )) && continue
    fail "set-version.sh $v exited $rc: $out"
  fi

  exp_code="$(ref_code "$v")"
  core="${v%%-*}"

  # --- read each carrier independently of check-release.sh ---
  got="$(cat "$repo/VERSION")"
  [[ $got == "$v" ]] || fail "VERSION is '$got', expected '$v'"

  g="$repo/PNTome/app/build.gradle.kts"
  gn="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$g" | head -n1)"
  gc="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' "$g" | head -n1)"
  [[ $gn == "$v" ]] || fail "versionName is '$gn', expected '$v' (VERSION=$v)"
  [[ $gc == "$exp_code" ]] || fail "versionCode is '$gc', expected '$exp_code' (VERSION=$v)"

  sv="$(sed -nE 's/.*const SEMVER[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/p' "$repo/PNTomeGo/source/Version.mc" | head -n1)"
  [[ $sv == "$v" ]] || fail "SEMVER is '$sv', expected '$v'"

  mv_="$(tr '\n' ' ' <"$repo/PNTomeGo/manifest.xml" | grep -oE '<iq:application[^>]*>' | head -n1 \
        | sed -nE 's/.*[[:space:]]version="([^"]*)".*/\1/p')"
  [[ $mv_ == "$core" ]] || fail "manifest version is '$mv_', expected '$core' (VERSION=$v)"

  if (( stable )); then
    fd="$repo/fdroid/metadata/dev.pntome.app.yml"
    grep -qE "^CurrentVersion:[[:space:]]*\"?$v\"?[[:space:]]*$" "$fd" || fail "F-Droid CurrentVersion != $v"
    grep -qE "^CurrentVersionCode:[[:space:]]*${exp_code}[[:space:]]*$" "$fd" || fail "F-Droid CurrentVersionCode != $exp_code"
    grep -qF -- "## [$v]" "$repo/CHANGELOG.md" || fail "CHANGELOG.md has no '## [$v]' section"
    cf="$repo/PNTome/fastlane/metadata/android/en-US/changelogs/$exp_code.txt"
    [[ -f $cf ]] || fail "missing fastlane changelog $exp_code.txt for $v"
  fi
  grep -qE '^## \[Unreleased\]' "$repo/CHANGELOG.md" || fail "CHANGELOG.md lost '## [Unreleased]' for $v"

  # --- check-release.sh must report no version-related violation ---
  chk="$(cd "$repo" && CHECK_RELEASE_ROOT="$repo" bash scripts/check-release.sh --tag "v$v" 2>&1)"
  if grep -qE "$VERSION_CHECKS" <<<"$(grep -v '^WARNING' <<<"$chk")"; then
    fail "check-release.sh flags version checks after set-version.sh $v: $(grep -E "$VERSION_CHECKS" <<<"$chk" | head -n 3)"
  fi
  ran=$(( ran + 1 ))
  pass "round trip for $v"
done

(( ran >= ITER / 2 )) || fail "only $ran of $ITER iterations were usable"
finish
