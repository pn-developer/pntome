#!/usr/bin/env bash
# test-check-release.sh -- shell test for scripts/check-release.sh (Requirement 11.12).
#
# Copies the repo (tracked + untracked-not-ignored files) into a temp dir, turns it into a
# git repo, expects check-release.sh to pass, then injects ONE violation per check into a
# fresh copy and expects a failure that names the right [check] tag.
# Needs only bash, git, sed, grep. No network. Leaves the real repo untouched.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/check-release-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

BASE="$TMP/base"
mkdir -p "$BASE"
(
  cd "$REPO"
  # Files deleted from disk but still in the index are skipped by the -f test.
  git ls-files --cached --others --exclude-standard -z | while IFS= read -r -d '' f; do
    [[ -f "$f" ]] || continue
    mkdir -p "$BASE/$(dirname "$f")"
    cp -p "$f" "$BASE/$f"
  done
)
git -C "$BASE" init -q
git -C "$BASE" add -A

PASS=0
FAIL=0
FAILED=()
N=0

# run_case NAME EXPECT_TAGS|OK MUTATE_FUNC [check-release args...]
#   EXPECT_TAGS is one check name or several separated by spaces (all must be reported).
#   OK expects exit 0. If EXPECT_OUT is set, that fixed string must also appear in the output.
run_case() {
  local name="$1" tag="$2" mutate="$3"; shift 3
  local dir out rc=0
  N=$((N + 1))
  dir="$TMP/case$N"
  cp -a "$BASE" "$dir"
  (cd "$dir" && "$mutate")
  git -C "$dir" add -Af
  out=$(cd "$dir" && env -u CHECK_RELEASE_ROOT bash scripts/check-release.sh "$@" 2>&1) || rc=$?
  local ok=1
  if [[ "$tag" == OK ]]; then
    [[ $rc -eq 0 ]] || ok=0
  else
    [[ $rc -eq 1 ]] || ok=0
    local t
    for t in $tag; do grep -qF -- "[$t]" <<<"$out" || ok=0; done
  fi
  if [[ -n "${EXPECT_OUT:-}" ]]; then grep -qF -- "$EXPECT_OUT" <<<"$out" || ok=0; fi
  if ((ok)); then
    PASS=$((PASS + 1)); echo "PASS  $name"
  else
    FAIL=$((FAIL + 1)); FAILED+=("$name")
    echo "FAIL  $name (expected ${tag}, exit $rc)"
    printf '%s\n' "$out" | head -n 8 | sed 's/^/      /'
  fi
  rm -rf "$dir"
}

# --- mutations (run inside the case copy) ---------------------------------
APP_ID_KT=PNTome/app/src/main/java/dev/pntome/app/net/garmin/PNTomeAppId.kt
m_none() { :; }
m_legacy() { echo '// ported from GsoundA' >>PNTome/app/build.gradle.kts; }
m_package() { sed -i -E 's/^package .*/package dev.pntome.app.wrong/' "$APP_ID_KT"; }
m_appid() { sed -i -E 's/^([[:space:]]*id=")[^"]*"/\1ffffffffffffffffffffffffffffffff"/' PNTomeGo/manifest.xml; }
m_header() { sed -i 's/X-PNTome-Secret/X-Other-Secret/g' PNTomeGo/source/sync/Downloader.mc; }
m_version() { sed -i -E 's/(const SEMVER[[:space:]]*=[[:space:]]*)"[^"]*"/\1"9.9.9"/' PNTomeGo/source/Version.mc; }
m_fdroid() { sed -i -E 's/^CurrentVersionCode:.*/CurrentVersionCode: 99/' fdroid/metadata/dev.pntome.app.yml; }
m_title() { printf '%s\n' 'This title is definitely longer than thirty characters' >PNTome/fastlane/metadata/android/en-US/title.txt; }
m_changelog() { rm -f PNTome/fastlane/metadata/android/en-US/changelogs/*.txt; }
m_permission() {
  sed -i '0,/<uses-permission /s//<uses-permission android:name="android.permission.READ_CONTACTS" \/>\n    <uses-permission /' \
    PNTome/app/src/main/AndroidManifest.xml
}
m_secret() { : >PNTome/release.jks; }
m_placeholder_all_fixed() { grep -rlIF 'example.invalid' --exclude-dir=.git --exclude-dir=.kiro --exclude=check-release.sh --exclude=test-check-release.sh . | xargs -r sed -i 's/example\.invalid/example.org/g'; }
m_placeholder_one() { m_placeholder_all_fixed; echo 'Contact: someone@example.invalid' >>PNTome/INSTALL.md; }

# --- mutations for the version, license and workflow checks (Requirement 15.2) ---
LICENSE_YML=fdroid/metadata/dev.pntome.app.yml
WF=.github/workflows/ci.yml
m_vfile() { printf '1.0\n' >VERSION; }
m_vcode() { sed -i -E 's/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\177/' "$GRADLE_KTS_T"; }
m_manifest() { sed -i -E 's/^([[:space:]]*version=")[^"]*"/\19.9.9"/' PNTomeGo/manifest.xml; }
m_gpl_license() { printf '\nGNU GENERAL PUBLIC LICENSE\n' >>LICENSE; }
m_no_notice() { rm -f NOTICE; }
m_fd_license() { sed -i -E 's/^License:.*/License: GPL-3.0-or-later/' "$LICENSE_YML"; }
m_gpl_mention() { printf '\nLicensed under the GNU General Public License.\n' >>PNTome/INSTALL.md; }
m_unpinned() { sed -i '0,/uses: actions\/checkout@v[0-9]*/s//uses: someone\/thing@v1/' "$WF"; }
m_prt() { sed -i 's/^on:$/on:\n  pull_request_target:/' "$WF"; }
m_no_perms() { sed -i '/^permissions:$/,/^  contents: read$/d' "$WF"; }
m_run_secret() { sed -i '0,/^    steps:$/s//    steps:\n      - run: echo ${{ secrets.TOKEN }}/' "$WF"; }
m_two() { m_vfile; m_no_notice; }
m_prerelease() { bash scripts/set-version.sh 0.2.0-rc.1 >/dev/null; }
GRADLE_KTS_T=PNTome/app/build.gradle.kts

run_case "pristine copy passes"                 OK            m_none
run_case "legacy name"                          legacy-names  m_legacy
run_case "wrong Kotlin package"                 package       m_package
run_case "differing Connect IQ app id"          contract      m_appid
run_case "differing secret header"              contract      m_header
run_case "version drift"                        versions      m_version
run_case "F-Droid drift"                        fdroid        m_fdroid
run_case "over-long store title"                store-text    m_title
run_case "missing changelog"                    store-text    m_changelog
run_case "undocumented permission"              permissions   m_permission
run_case "tracked .jks"                         secrets       m_secret
run_case "placeholders replaced, --release ok"  OK            m_placeholder_all_fixed --release
run_case "remaining placeholder, --release"     placeholders  m_placeholder_one --release
run_case "bad VERSION file"                     version-file  m_vfile
run_case "versionCode drift"                    versions      m_vcode
run_case "manifest version != core version"     versions      m_manifest
run_case "GPL text in LICENSE"                  license       m_gpl_license
run_case "missing NOTICE"                       license       m_no_notice
run_case "wrong F-Droid License"                license       m_fd_license
run_case "GPL mention outside allowlist"        license       m_gpl_mention
run_case "tag mismatch"                         tag           m_none --tag v9.9.9
run_case "tag matches"                          OK            m_none --tag v0.1.0
run_case "unpinned third-party action"          workflows     m_unpinned
run_case "pull_request_target"                  workflows     m_prt
run_case "missing top-level permissions"        workflows     m_no_perms
run_case "secret on a run: line"                workflows     m_run_secret
run_case "two violations at once"               "version-file license" m_two
EXPECT_OUT="WARNING [fdroid]" run_case "pre-release: only F-Droid warning" OK m_prerelease

# --- property tests for check-release.sh (slow: several seconds to minutes each) ---
if [[ "${CK_SKIP_PROPS:-0}" == 1 ]]; then
  echo
  echo "Skipping prop-ck-*.sh property tests (CK_SKIP_PROPS=1)"
else
  echo
  echo "Running scripts/tests/prop-ck-*.sh property tests (this is the slow part; set CK_SKIP_PROPS=1 to skip)"
  for p in "$REPO"/scripts/tests/prop-ck-*.sh; do
    [[ -f "$p" ]] || continue
    name="prop $(basename "$p")"
    start=$SECONDS
    echo "RUN   $name"
    if bash "$p" >"$TMP/prop.out" 2>&1; then
      PASS=$((PASS + 1)); echo "PASS  $name ($((SECONDS - start))s)"
    else
      FAIL=$((FAIL + 1)); FAILED+=("$name")
      echo "FAIL  $name ($((SECONDS - start))s)"
      tail -n 10 "$TMP/prop.out" | sed 's/^/      /'
    fi
  done
fi

echo
echo "check-release tests: $PASS passed, $FAIL failed"
if ((FAIL)); then
  printf '  failed: %s\n' "${FAILED[@]}"
  exit 1
fi
