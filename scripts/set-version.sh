#!/usr/bin/env bash
# scripts/set-version.sh <VERSION> [--dry-run] [--allow-downgrade]
#
# Propagates VERSION to every version carrier (design section 2):
#   always:  VERSION, PNTome/app/build.gradle.kts (versionName, versionCode),
#            PNTomeGo/source/Version.mc (SEMVER line only),
#            PNTomeGo/manifest.xml (application version = Core_Version)
#   stable:  fdroid/metadata/dev.pntome.app.yml (Builds entry, CurrentVersion,
#            CurrentVersionCode, commit), CHANGELOG.md rotation, fastlane changelog
#
# Exit codes: 0 ok, 1 usage/validation failure (bad version, downgrade, empty
# Unreleased on a stable version), 2 a target did not contain the expected literal
# exactly once. All edits are computed in memory first; files are written only when
# every edit succeeded. No git commit, no tag.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=lib/version.sh
. "$ROOT/scripts/lib/version.sh"

usage() {
  echo "usage: scripts/set-version.sh <VERSION> [--dry-run] [--allow-downgrade]" >&2
}

DRY_RUN=0
ALLOW_DOWNGRADE=0
NEW_VERSION=""
for arg in "$@"; do
  case $arg in
    --dry-run) DRY_RUN=1 ;;
    --allow-downgrade) ALLOW_DOWNGRADE=1 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "set-version: unknown option '$arg'" >&2; usage; exit 1 ;;
    *)
      if [[ -n $NEW_VERSION ]]; then usage; exit 1; fi
      NEW_VERSION=$arg
      ;;
  esac
done
[[ -n $NEW_VERSION ]] || { usage; exit 1; }

if ! valid_version "$NEW_VERSION"; then
  echo "set-version: '$NEW_VERSION' is not a valid version (MAJOR.MINOR.PATCH[-rc.N], see scripts/lib/version.sh)" >&2
  exit 1
fi

CORE="$(core_version "$NEW_VERSION")"
CODE="$(version_code "$NEW_VERSION")"
if is_prerelease "$NEW_VERSION"; then STABLE=0; else STABLE=1; fi

F_VERSION="VERSION"
F_GRADLE="PNTome/app/build.gradle.kts"
F_VERSIONMC="PNTomeGo/source/Version.mc"
F_MANIFEST="PNTomeGo/manifest.xml"
F_FDROID="fdroid/metadata/dev.pntome.app.yml"
F_CHANGELOG="CHANGELOG.md"
F_FASTLANE="PNTome/fastlane/metadata/android/en-US/changelogs/$CODE.txt"

# Downgrade guard (only when the current VERSION file is itself valid).
if [[ -f $ROOT/$F_VERSION ]]; then
  CURRENT="$(head -n 1 "$ROOT/$F_VERSION" | tr -d '\r')"
  if valid_version "$CURRENT" && [[ $ALLOW_DOWNGRADE -eq 0 ]]; then
    if [[ "$(version_cmp "$NEW_VERSION" "$CURRENT")" == "-1" ]]; then
      echo "set-version: $NEW_VERSION is lower than current $CURRENT (use --allow-downgrade to force)" >&2
      exit 1
    fi
  fi
fi

# ---- In-memory file buffers ------------------------------------------------------------------
declare -A BUF        # path -> content without the final newline
declare -A FINAL_NL   # path -> 1 if the original ended with a newline

die2() { echo "set-version: $*" >&2; exit 2; }

load() { # load <relpath>
  local f=$1 s
  [[ -n ${BUF[$f]+x} ]] && return 0
  [[ -f $ROOT/$f ]] || die2 "$f: file not found"
  s=$(cat "$ROOT/$f"; printf x)
  s=${s%x}
  if [[ $s == *$'\n' ]]; then FINAL_NL[$f]=1; s=${s%$'\n'}; else FINAL_NL[$f]=0; fi
  BUF[$f]=$s
}

# edit_line <relpath> <ERE with two groups> <value>
# Exactly one line must match; it becomes group1 + value + group2.
edit_line() {
  local f=$1 re=$2 value=$3 line out="" n=0 first=1 new
  load "$f"
  while IFS= read -r line || [[ -n $line ]]; do
    new=$line
    if [[ $line =~ $re ]]; then
      n=$((n + 1))
      new="${BASH_REMATCH[1]}${value}${BASH_REMATCH[2]}"
    fi
    if (( first )); then out=$new; first=0; else out+=$'\n'"$new"; fi
  done <<<"${BUF[$f]}"
  (( n == 1 )) || die2 "$f: expected exactly one line matching /$re/, found $n"
  BUF[$f]=$out
}

# ---- Edits that apply to every version -------------------------------------------------------
BUF[$F_VERSION]="$NEW_VERSION"
FINAL_NL[$F_VERSION]=1

edit_line "$F_GRADLE" '^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+(.*)$' "$CODE"
edit_line "$F_GRADLE" '^([[:space:]]*versionName[[:space:]]*=[[:space:]]*")[^"]*(".*)$' "$NEW_VERSION"
edit_line "$F_VERSIONMC" '^([[:space:]]*const[[:space:]]+SEMVER[[:space:]]*=[[:space:]]*")[^"]*(";.*)$' "$NEW_VERSION"
edit_line "$F_MANIFEST" '^([[:space:]]+version=")[^"]*(".*)$' "$CORE"

# ---- Stable-only edits -----------------------------------------------------------------------
NOTE_FASTLANE=""
if (( STABLE )); then
  edit_line "$F_FDROID" '^([[:space:]]*-[[:space:]]+versionName:[[:space:]]*)[^[:space:]#]+(.*)$' "$NEW_VERSION"
  edit_line "$F_FDROID" '^([[:space:]]+versionCode:[[:space:]]*)[0-9]+(.*)$' "$CODE"
  edit_line "$F_FDROID" '^([[:space:]]+commit:[[:space:]]*)[^[:space:]#]+(.*)$' "v$NEW_VERSION"
  edit_line "$F_FDROID" '^(CurrentVersion:[[:space:]]*)[^[:space:]#]+(.*)$' "$NEW_VERSION"
  edit_line "$F_FDROID" '^(CurrentVersionCode:[[:space:]]*)[0-9]+(.*)$' "$CODE"

  # CHANGELOG rotation.
  load "$F_CHANGELOG"
  mapfile -t CL <<<"${BUF[$F_CHANGELOG]}"
  unrel_idx=-1; unrel_n=0; ver_idx=-1; ver_n=0
  esc_ver=${NEW_VERSION//./\\.}
  for i in "${!CL[@]}"; do
    if [[ ${CL[i]} =~ ^##[[:space:]]+\[Unreleased\][[:space:]]*$ ]]; then unrel_idx=$i; unrel_n=$((unrel_n + 1)); fi
    if [[ ${CL[i]} =~ ^##[[:space:]]+\[${esc_ver}\]([[:space:]]|$) ]]; then ver_idx=$i; ver_n=$((ver_n + 1)); fi
  done
  (( unrel_n == 1 )) || die2 "$F_CHANGELOG: expected exactly one '## [Unreleased]' heading, found $unrel_n"
  (( ver_n <= 1 )) || die2 "$F_CHANGELOG: found $ver_n '## [$NEW_VERSION]' headings"

  # Bullets of the Unreleased section (up to the next '## ' heading or a link-reference line).
  section_end() { # section_end <start-idx> -> prints index of first line after the section
    local j
    for (( j = $1 + 1; j < ${#CL[@]}; j++ )); do
      if [[ ${CL[j]} =~ ^##[[:space:]] || ${CL[j]} =~ ^\[[^]]+\]:[[:space:]] ]]; then break; fi
    done
    printf '%s' "$j"
  }
  unrel_end=$(section_end "$unrel_idx")
  bullets=0
  for (( i = unrel_idx + 1; i < unrel_end; i++ )); do
    [[ ${CL[i]} =~ ^[[:space:]]*[-*][[:space:]]+[^[:space:]] ]] && bullets=$((bullets + 1))
  done

  if (( ver_n == 1 )); then
    if (( bullets > 0 )); then
      echo "set-version: $F_CHANGELOG already has a '## [$NEW_VERSION]' section and '## [Unreleased]' has bullets; merge them by hand" >&2
      exit 1
    fi
    sec_start=$ver_idx   # already rotated (second run): keep the file as it is
  else
    if (( bullets == 0 )); then
      echo "set-version: '## [Unreleased]' in $F_CHANGELOG has no bullet; nothing to release as $NEW_VERSION" >&2
      exit 1
    fi
    today="$(date -u +%Y-%m-%d)"
    newcl=()
    for i in "${!CL[@]}"; do
      if (( i == unrel_idx )); then
        newcl+=("## [Unreleased]" "" "## [$NEW_VERSION] - $today")
      else
        newcl+=("${CL[i]}")
      fi
    done
    BUF[$F_CHANGELOG]="$(printf '%s\n' "${newcl[@]}")"
    BUF[$F_CHANGELOG]=${BUF[$F_CHANGELOG]%$'\n'}
    CL=("${newcl[@]}")
    sec_start=$((unrel_idx + 2))
  fi

  # Fastlane changelog from the bullets of the release section.
  sec_end=$(section_end "$sec_start")
  fl=""
  for (( i = sec_start + 1; i < sec_end; i++ )); do
    if [[ ${CL[i]} =~ ^[[:space:]]*[-*][[:space:]]+(.+)$ ]]; then
      fl+="- ${BASH_REMATCH[1]}"$'\n'
    fi
  done
  nchars=$( LC_ALL=C.UTF-8; t=${fl%$'\n'}; printf '%s' "${#t}" )
  if [[ -z $fl ]]; then
    NOTE_FASTLANE="no bullets found for $F_FASTLANE; write it by hand (max 500 characters)"
  elif (( nchars > 500 )); then
    NOTE_FASTLANE="generated changelog is $nchars characters (limit 500); $F_FASTLANE was NOT written, write it by hand"
  else
    BUF[$F_FASTLANE]=${fl%$'\n'}
    FINAL_NL[$F_FASTLANE]=1
  fi
fi

# ---- Write phase -----------------------------------------------------------------------------
declare -a TOUCHED=()
for f in "${!BUF[@]}"; do
  content=${BUF[$f]}
  [[ ${FINAL_NL[$f]:-1} == 1 ]] && content+=$'\n'
  if [[ -f $ROOT/$f ]]; then
    cur=$(cat "$ROOT/$f"; printf x); cur=${cur%x}
    [[ $cur == "$content" ]] && continue
  fi
  TOUCHED+=("$f")
  if (( ! DRY_RUN )); then
    mkdir -p "$(dirname "$ROOT/$f")"
    printf '%s' "$content" >"$ROOT/$f"
  fi
done

if (( DRY_RUN )); then
  echo "set-version: dry run for $NEW_VERSION (versionCode $CODE, manifest $CORE); no file written"
  (( ${#TOUCHED[@]} )) && printf 'would change: %s\n' "${TOUCHED[@]}" | sort
else
  echo "set-version: $NEW_VERSION (versionCode $CODE, manifest $CORE)"
  (( ${#TOUCHED[@]} )) && printf 'changed: %s\n' "${TOUCHED[@]}" | sort
  (( ${#TOUCHED[@]} )) || echo "no changes (already at $NEW_VERSION)"
fi
[[ -z $NOTE_FASTLANE ]] || echo "NOTE: $NOTE_FASTLANE" >&2

cat <<EOF

Next steps (this script does not commit or tag):
  1. scripts/check-release.sh
  2. git add -A && git commit -m "release: v$NEW_VERSION"
  3. git tag -a v$NEW_VERSION -m "v$NEW_VERSION"
  4. git push origin main v$NEW_VERSION
EOF
