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

# run_case NAME EXPECT_TAG|OK MUTATE_FUNC [check-release args...]
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
    [[ $rc -eq 1 ]] && grep -qF -- "[$tag]" <<<"$out" || ok=0
  fi
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

echo
echo "check-release tests: $PASS passed, $FAIL failed"
if ((FAIL)); then
  printf '  failed: %s\n' "${FAILED[@]}"
  exit 1
fi
