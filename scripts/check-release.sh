#!/usr/bin/env bash
# check-release.sh -- offline repository invariants for the PNTome store release.
#
# Usage: scripts/check-release.sh [--release]
#   --release  stricter mode (used by later checks, e.g. remaining placeholders fail).
#
# Every check_* function appends lines to VIOLATIONS in the form
#   [check-name] file:line message
# All checks run; the script exits 1 at the end if any violation was collected.
# Needs only bash, git, grep, awk, sed. No network, no SDK.
set -euo pipefail

RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --release) RELEASE=1 ;;
    -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

ROOT="${CHECK_RELEASE_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$ROOT"

APP_PACKAGE="dev.pntome.app"
ANDROID_DIR="PNTome"

VIOLATIONS=()
WARNINGS=()
violation() { VIOLATIONS+=("$1"); }
warning() { WARNINGS+=("$1"); }

# ---------------------------------------------------------------------------
# Allowlist for the legacy brand (Property 1). Keep it small and explicit.
# ---------------------------------------------------------------------------
# Whole files/paths where the old name is allowed (shell glob, matched with [[ == ]]).
ALLOW_PATH_GLOBS=(
  # Historical specs written before the rename; kept as a record (design: Rename Mapping).
  ".kiro/specs/gsound-*"
  # This spec describes the rename itself, so it must name the old brand.
  ".kiro/specs/pntome-store-release/*"
  # The check and its test must spell the legacy pattern / inject it.
  "scripts/check-release.sh"
  "scripts/test-check-release.sh"
)

# Path|line-regex pairs: in these files only lines matching the (extended, case-insens.)
# regex may contain the old name.
ALLOW_LINE_RULES=(
  # Rename history and the "old app is a separate app" note.
  'CHANGELOG.md|renamed|old app|formerly|replaces the old'
  # README explains that the historical gsound-* specs predate the rename.
  'README.md|historical'
  # Explicit "replace old GsoundA/GsoundG builds" instructions.
  'PNTome/INSTALL.md|GsoundA( and |/)GsoundG'
  'PNTome/docs/manual-e2e-checklist.md|GsoundA( and |/)GsoundG'
  # Store texts that say what the old name was / must not appear.
  'store/garmin/images.md|old "Gsound" name'
  'store/garmin/listing.md|formerly Gsound'
)

# Tokens removed from every line/path before testing, anywhere in the repo:
#  - references to the historical spec ids (gsound-player, ...), which stay as records;
#  - the local checkout directory name pn_gsound (design: Rename Mapping, "Root dir").
ALLOW_TOKEN_REGEX='gsound-(android|audiobooks|garmin|hotspot|overview|player|playback-fixes)|pn_gsound'

LEGACY_REGEX='gsound'   # used with grep -i: covers Gsound, GsoundA, GsoundG, dev.gsound.*

list_files() {
  git ls-files --cached --others --exclude-standard | sort -u | while IFS= read -r f; do
    [[ -f "$f" ]] && printf '%s\n' "$f"
  done
}

path_allowed() {
  local f="$1" g
  for g in "${ALLOW_PATH_GLOBS[@]}"; do
    # shellcheck disable=SC2053
    [[ "$f" == $g ]] && return 0
  done
  return 1
}

strip_tokens() { sed -E "s/${ALLOW_TOKEN_REGEX}//Ig"; }

# Property 1: no legacy name outside the allowlist.
check_legacy_names() {
  local f stripped rule rpath rre line_re lineno text allowed
  while IFS= read -r f; do
    path_allowed "$f" && continue
    stripped=$(printf '%s' "$f" | strip_tokens)
    if printf '%s' "$stripped" | grep -qi -- "$LEGACY_REGEX"; then
      violation "[legacy-names] $f:0 legacy name in file path"
    fi
    # Binary files are skipped (-I). Skip files with no hit quickly.
    grep -qIi -- "$LEGACY_REGEX" "$f" 2>/dev/null || continue
    line_re=""
    for rule in "${ALLOW_LINE_RULES[@]}"; do
      rpath="${rule%%|*}"; rre="${rule#*|}"
      [[ "$rpath" == "$f" ]] && line_re="$rre"
    done
    while IFS= read -r hit; do
      lineno="${hit%%:*}"; text="${hit#*:}"
      stripped=$(printf '%s' "$text" | strip_tokens)
      printf '%s' "$stripped" | grep -qi -- "$LEGACY_REGEX" || continue
      if [[ -n "$line_re" ]] && printf '%s' "$text" | grep -qiE -- "$line_re"; then
        continue
      fi
      violation "[legacy-names] $f:$lineno legacy name: $(printf '%s' "$text" | sed -E 's/^[[:space:]]+//' | cut -c1-100)"
    done < <(grep -nIi -- "$LEGACY_REGEX" "$f" || true)
  done < <(list_files)
}

# Property 2: Kotlin package == directory below java/; proto java_package prefix.
check_package_matches_dir() {
  local f rel dir expected actual lineno
  while IFS= read -r f; do
    case "$f" in
      "$ANDROID_DIR"/app/src/*/java/*.kt) ;;
      *) continue ;;
    esac
    rel="${f#*/java/}"
    dir="$(dirname "$rel")"
    expected="${dir//\//.}"
    actual=$(grep -m1 -E '^[[:space:]]*package[[:space:]]+' "$f" | sed -E 's/^[[:space:]]*package[[:space:]]+([^[:space:];]+).*/\1/' || true)
    if [[ -z "$actual" ]]; then
      violation "[package] $f:1 no package declaration (expected $expected)"
    elif [[ "$actual" != "$expected" ]]; then
      lineno=$(grep -n -m1 -E '^[[:space:]]*package[[:space:]]+' "$f" | cut -d: -f1)
      violation "[package] $f:$lineno package '$actual' does not match directory '$expected'"
    fi
    case "$expected" in
      "$APP_PACKAGE"|"$APP_PACKAGE".*) ;;
      *) violation "[package] $f:1 directory package '$expected' is outside $APP_PACKAGE" ;;
    esac
  done < <(list_files)

  while IFS= read -r f; do
    case "$f" in *.proto) ;; *) continue ;; esac
    local hit
    hit=$(grep -n -m1 'java_package' "$f" || true)
    if [[ -z "$hit" ]]; then
      violation "[package] $f:1 .proto has no java_package option"
      continue
    fi
    lineno="${hit%%:*}"
    actual=$(printf '%s' "$hit" | sed -E 's/.*java_package[[:space:]]*=[[:space:]]*"([^"]*)".*/\1/')
    case "$actual" in
      "$APP_PACKAGE"|"$APP_PACKAGE".*) ;;
      *) violation "[package] $f:$lineno java_package '$actual' does not start with $APP_PACKAGE" ;;
    esac
  done < <(list_files)
}

# --- checks added by tasks 8.2 and 8.3 go below this line ------------------

# ---------------------------------------------------------------------------
# Helpers for the contract / version / F-Droid checks.
# ---------------------------------------------------------------------------
APP_ID_KT="$ANDROID_DIR/app/src/main/java/dev/pntome/app/net/garmin/PNTomeAppId.kt"
SECRET_KT="$ANDROID_DIR/app/src/main/java/dev/pntome/app/net/server/SecretAuth.kt"
CIQ_MGR_KT="$ANDROID_DIR/app/src/main/java/dev/pntome/app/net/garmin/ConnectIqManager.kt"
GRADLE_KTS="$ANDROID_DIR/app/build.gradle.kts"
WATCH_DIR="PNTomeGo"
WATCH_MANIFEST="$WATCH_DIR/manifest.xml"
WATCH_DOWNLOADER="$WATCH_DIR/source/sync/Downloader.mc"
WATCH_DEBUGLOG="$WATCH_DIR/source/DebugLog.mc"
WATCH_VERSION="$WATCH_DIR/source/Version.mc"
FDROID_YML="fdroid/metadata/$APP_PACKAGE.yml"

# first_match FILE ERE -> first capture group ('' when file or match is missing)
first_match() {
  [[ -f "$1" ]] || return 0
  sed -nE "s/$2/\1/p" "$1" | head -n1
}

# Property 3: phone/watch contract constants agree (app id, secret header, watch log tag).
check_contract_constants() {
  local f
  for f in "$APP_ID_KT" "$SECRET_KT" "$CIQ_MGR_KT" "$WATCH_MANIFEST" "$WATCH_DOWNLOADER" "$WATCH_DEBUGLOG"; do
    [[ -f "$f" ]] || violation "[contract] $f:0 file not found"
  done

  # Connect IQ application id.
  local kt_id mf_id
  kt_id=$(first_match "$APP_ID_KT" '.*const val VALUE[^=]*=[[:space:]]*"([^"]*)".*')
  mf_id=$(first_match "$WATCH_MANIFEST" '.*<iq:application[^>]*[[:space:]]id="([^"]*)".*')
  if [[ -z "$mf_id" && -f "$WATCH_MANIFEST" ]]; then
    # id may sit on its own line inside a multi-line <iq:application> tag
    mf_id=$(awk '/<iq:application/{t=1} t&&/[[:space:]]id="/{match($0,/id="[^"]*"/); print substr($0,RSTART+4,RLENGTH-5); exit} t&&/>/{exit}' "$WATCH_MANIFEST")
  fi
  [[ -n "$kt_id" || ! -f "$APP_ID_KT" ]] || violation "[contract] $APP_ID_KT:0 cannot find PNTomeAppId.VALUE"
  [[ -n "$mf_id" || ! -f "$WATCH_MANIFEST" ]] || violation "[contract] $WATCH_MANIFEST:0 cannot find <iq:application id>"
  if [[ -n "$kt_id" && -n "$mf_id" && "$kt_id" != "$mf_id" ]]; then
    violation "[contract] $WATCH_MANIFEST:0 Connect IQ app id '$mf_id' != PNTomeAppId.VALUE '$kt_id' ($APP_ID_KT)"
  fi

  # Secret header: Kotlin constant vs every header key the watch Downloader sends.
  local kt_hdr mc_hdrs h
  kt_hdr=$(first_match "$SECRET_KT" '.*const val SECRET_HEADER[^=]*=[[:space:]]*"([^"]*)".*')
  [[ -n "$kt_hdr" || ! -f "$SECRET_KT" ]] || violation "[contract] $SECRET_KT:0 cannot find SECRET_HEADER"
  if [[ -f "$WATCH_DOWNLOADER" ]]; then
    mc_hdrs=$(grep -oE '"X-[A-Za-z0-9-]*"[[:space:]]*=>' "$WATCH_DOWNLOADER" | sed -E 's/^"([^"]*)".*/\1/' | sort -u || true)
    if [[ -z "$mc_hdrs" ]]; then
      violation "[contract] $WATCH_DOWNLOADER:0 no secret header (\"X-...\" => secret) found"
    else
      while IFS= read -r h; do
        [[ -n "$kt_hdr" && "$h" != "$kt_hdr" ]] &&
          violation "[contract] $WATCH_DOWNLOADER:0 secret header '$h' != SECRET_HEADER '$kt_hdr' ($SECRET_KT)"
      done <<<"$mc_hdrs"
    fi
  fi

  # Watch log tag: ConnectIqManager.WATCH_LOG_TAG vs the tag DebugLog documents (`PNTome.Watch`).
  local kt_tag mc_tag
  kt_tag=$(first_match "$CIQ_MGR_KT" '.*WATCH_LOG_TAG[^=]*=[[:space:]]*"([^"]*)".*')
  [[ -n "$kt_tag" || ! -f "$CIQ_MGR_KT" ]] || violation "[contract] $CIQ_MGR_KT:0 cannot find WATCH_LOG_TAG"
  if [[ -f "$WATCH_DEBUGLOG" ]]; then
    mc_tag=$(grep -oE 'tag `[^`]*`' "$WATCH_DEBUGLOG" | head -n1 | sed -E 's/tag `([^`]*)`/\1/' || true)
    if [[ -z "$mc_tag" ]]; then
      violation "[contract] $WATCH_DEBUGLOG:0 cannot find the documented phone log tag (tag \`...\`)"
    elif [[ -n "$kt_tag" && "$mc_tag" != "$kt_tag" ]]; then
      violation "[contract] $WATCH_DEBUGLOG:0 watch log tag '$mc_tag' != WATCH_LOG_TAG '$kt_tag' ($CIQ_MGR_KT)"
    fi
  fi
}

# Property 4: Android versionName == Version.mc SEMVER == Garmin manifest version.
check_versions() {
  local a w m
  a=$(first_match "$GRADLE_KTS" '^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*')
  w=$(first_match "$WATCH_VERSION" '.*const SEMVER[[:space:]]*=[[:space:]]*"([^"]*)".*')
  m=$(awk '/<iq:application/{t=1} t&&/[[:space:]]version="/{match($0,/[[:space:]]version="[^"]*"/); s=substr($0,RSTART,RLENGTH); sub(/^[^"]*"/,"",s); sub(/"$/,"",s); print s; exit} t&&/>/{exit}' "$WATCH_MANIFEST" 2>/dev/null || true)
  [[ -n "$a" ]] || violation "[versions] $GRADLE_KTS:0 cannot find versionName"
  [[ -n "$w" ]] || violation "[versions] $WATCH_VERSION:0 cannot find SEMVER"
  [[ -n "$m" ]] || violation "[versions] $WATCH_MANIFEST:0 cannot find application version"
  if [[ -n "$a" && -n "$w" && -n "$m" && ( "$a" != "$w" || "$a" != "$m" ) ]]; then
    violation "[versions] version mismatch: android versionName='$a' ($GRADLE_KTS), Version.mc SEMVER='$w' ($WATCH_VERSION), manifest version='$m' ($WATCH_MANIFEST)"
  fi
}

# Property 5: F-Droid metadata agrees with Gradle and its UpdateCheckData resolves.
check_fdroid_metadata() {
  local f="$FDROID_YML" k
  if [[ ! -f "$f" ]]; then violation "[fdroid] $f:0 file not found"; return; fi

  for k in Categories License AuthorName WebSite SourceCode IssueTracker Changelog AutoName RepoType Repo \
           Builds AutoUpdateMode UpdateCheckMode UpdateCheckData CurrentVersion CurrentVersionCode; do
    grep -qE "^$k:" "$f" || violation "[fdroid] $f:0 missing required key '$k'"
  done

  local g_name g_code y_name y_code y_cur y_curcode
  g_name=$(first_match "$GRADLE_KTS" '^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]*)".*')
  g_code=$(first_match "$GRADLE_KTS" '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*')
  y_name=$(first_match "$f" '^[[:space:]]+-?[[:space:]]*versionName:[[:space:]]*"?([^"[:space:]]+)"?.*')
  y_code=$(first_match "$f" '^[[:space:]]+-?[[:space:]]*versionCode:[[:space:]]*([0-9]+).*')
  y_cur=$(first_match "$f" '^CurrentVersion:[[:space:]]*"?([^"[:space:]]+)"?.*')
  y_curcode=$(first_match "$f" '^CurrentVersionCode:[[:space:]]*([0-9]+).*')
  [[ -n "$g_name" && -n "$g_code" ]] || violation "[fdroid] $GRADLE_KTS:0 cannot find versionName/versionCode literals"

  local pair
  for pair in "Builds.versionName|$y_name|$g_name" "Builds.versionCode|$y_code|$g_code" \
              "CurrentVersion|$y_cur|$g_name" "CurrentVersionCode|$y_curcode|$g_code"; do
    IFS='|' read -r k y g <<<"$pair"
    if [[ -z "$y" ]]; then violation "[fdroid] $f:0 cannot read $k"
    elif [[ -n "$g" && "$y" != "$g" ]]; then violation "[fdroid] $f:0 $k '$y' != Gradle '$g' ($GRADLE_KTS)"
    fi
  done

  # UpdateCheckData: <file>|<versionCode regex>|<.|other file>|<versionName regex>
  local ucd ufile uvc_re uvf uvn_re
  ucd=$(first_match "$f" '^UpdateCheckData:[[:space:]]*(.*)$')
  if [[ -z "$ucd" ]]; then return; fi
  IFS='|' read -r ufile uvc_re uvf uvn_re <<<"$ucd"
  if [[ -z "$ufile" || -z "$uvc_re" || -z "$uvf" || -z "$uvn_re" ]]; then
    violation "[fdroid] $f:0 UpdateCheckData '$ucd' is not in file|regex|.|regex form"; return
  fi
  [[ "$uvf" == "." ]] && uvf="$ufile"
  if [[ ! -f "$ufile" ]]; then violation "[fdroid] $f:0 UpdateCheckData file '$ufile' not found"; return; fi
  if [[ ! -f "$uvf" ]]; then violation "[fdroid] $f:0 UpdateCheckData version file '$uvf' not found"; return; fi
  local got_code got_name
  got_code=$(apply_ucd_regex "$ufile" "$uvc_re")
  got_name=$(apply_ucd_regex "$uvf" "$uvn_re")
  if [[ -z "$got_code" ]]; then violation "[fdroid] $f:0 UpdateCheckData versionCode regex '$uvc_re' does not match $ufile"
  elif [[ -n "$g_code" && "$got_code" != "$g_code" ]]; then violation "[fdroid] $f:0 UpdateCheckData versionCode '$got_code' != Gradle '$g_code'"; fi
  if [[ -z "$got_name" ]]; then violation "[fdroid] $f:0 UpdateCheckData versionName regex '$uvn_re' does not match $uvf"
  elif [[ -n "$g_name" && "$got_name" != "$g_name" ]]; then violation "[fdroid] $f:0 UpdateCheckData versionName '$got_name' != Gradle '$g_name'"; fi
}

# apply_ucd_regex FILE PCRE -> first capture group of the first matching line.
# Translates the few PCRE classes F-Droid regexes use (\s \d) to POSIX ERE.
apply_ucd_regex() {
  local re="${2//\\s/[[:space:]]}"
  re="${re//\\d/[0-9]}"
  sed -nE "s/.*${re}.*/\1/p" "$1" | head -n1
}


# ---------------------------------------------------------------------------
# Store-text, permission, secret and placeholder checks (tasks 8.3).
# ---------------------------------------------------------------------------
FASTLANE_DIR="$ANDROID_DIR/fastlane/metadata/android"
MANIFEST_XML="$ANDROID_DIR/app/src/main/AndroidManifest.xml"
PERMISSIONS_MD="store/play/permissions.md"
# char_count FILE -> number of characters (not bytes), ignoring one trailing newline.
char_count() {
  local s
  s=$(cat "$1"; printf x)   # sentinel keeps trailing newlines
  s="${s%x}"
  s="${s%$'\n'}"
  s="${s%$'\r'}"
  local LC_ALL=C.UTF-8
  printf '%s' "${#s}"
}
# Property 6: store text within limits; changelog exists for the current versionCode.
check_store_text_limits() {
  local dir loc spec name limit file n code
  code=$(first_match "$GRADLE_KTS" '^[[:space:]]*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*')
  [[ -n "$code" ]] || violation "[store-text] $GRADLE_KTS:0 cannot find versionCode"
  local found=0
  for dir in "$FASTLANE_DIR"/*/; do
    [[ -d "$dir" ]] || continue
    found=1
    loc="${dir%/}"
    for spec in title.txt:30 short_description.txt:80 full_description.txt:4000; do
      name="${spec%%:*}"; limit="${spec##*:}"; file="$loc/$name"
      if [[ ! -f "$file" ]]; then violation "[store-text] $file:0 file not found"; continue; fi
      n=$(char_count "$file")
      ((n <= limit)) || violation "[store-text] $file:1 $n characters exceeds limit $limit"
    done
    if [[ -n "$code" ]]; then
      file="$loc/changelogs/$code.txt"
      if [[ ! -f "$file" ]]; then
        violation "[store-text] $file:0 changelog for versionCode $code not found"
      else
        n=$(char_count "$file")
        ((n <= 500)) || violation "[store-text] $file:1 $n characters exceeds limit 500"
      fi
    fi
  done
  ((found)) || violation "[store-text] $FASTLANE_DIR:0 no locale directories found"
}
# Property 7: every uses-permission and foregroundServiceType is justified in permissions.md.
check_permissions_documented() {
  local item ln
  if [[ ! -f "$MANIFEST_XML" ]]; then violation "[permissions] $MANIFEST_XML:0 file not found"; return; fi
  if [[ ! -f "$PERMISSIONS_MD" ]]; then violation "[permissions] $PERMISSIONS_MD:0 file not found"; return; fi
  while IFS= read -r item; do
    [[ -n "$item" ]] || continue
    if ! grep -qF -- "$item" "$PERMISSIONS_MD"; then
      ln=$(grep -nF -- "\"$item\"" "$MANIFEST_XML" | head -n1 | cut -d: -f1)
      violation "[permissions] $MANIFEST_XML:${ln:-0} '$item' is not documented in $PERMISSIONS_MD"
    fi
  done < <({
    tr '\n' ' ' <"$MANIFEST_XML" | grep -oE '<uses-permission[[:space:]][^>]*>' |
      sed -nE 's/.*android:name="([^"]*)".*/\1/p'
    tr '\n' ' ' <"$MANIFEST_XML" | grep -oE 'android:foregroundServiceType="[^"]*"' |
      sed -E 's/.*="([^"]*)"/\1/' | tr '|' '\n'
  } | sort -u)
}
# Property 8: no signing material or local config tracked by git.
check_no_secrets_tracked() {
  local f base
  while IFS= read -r f; do
    base="${f##*/}"
    case "$base" in
      *.jks|*.keystore|*.der|keystore.properties|local.properties)
        violation "[secrets] $f:0 secret/local file is tracked by git" ;;
    esac
  done < <(git ls-files)
}
# Property 9: example.invalid placeholders (error with --release, warning otherwise).
check_placeholders() {
  local f hit msg
  while IFS= read -r f; do
    case "$f" in
      .kiro/specs/*|scripts/check-release.sh|scripts/test-check-release.sh) continue ;;
    esac
    while IFS= read -r hit; do
      msg="[placeholders] $f:${hit%%:*} placeholder: $(printf '%s' "${hit#*:}" | sed -E 's/^[[:space:]]+//' | cut -c1-100)"
      if ((RELEASE)); then violation "$msg"; else warning "$msg"; fi
    done < <(grep -nIF -- 'example.invalid' "$f" || true)
  done < <(list_files)
}
# ---------------------------------------------------------------------------
check_legacy_names
check_package_matches_dir
check_contract_constants
check_versions
check_fdroid_metadata
check_store_text_limits
check_permissions_documented
check_no_secrets_tracked
check_placeholders

for w in "${WARNINGS[@]+"${WARNINGS[@]}"}"; do echo "WARNING $w" >&2; done
if ((${#VIOLATIONS[@]})); then
  printf '%s\n' "${VIOLATIONS[@]}"
  echo "check-release: ${#VIOLATIONS[@]} violation(s)" >&2
  exit 1
fi
echo "check-release: OK$([[ $RELEASE -eq 1 ]] && echo ' (--release)')"
