#!/usr/bin/env bash
# collect-android.sh <signed: true|false>
#   Locates the release APK and AAB built by Gradle, renames them to
#     PNTome-<VERSION>-<COMMIT>-(signed|unsigned).(apk|aab)
#   and places them in <repo>/out/android/.
#
# Environment:
#   VERSION      version without "v" (required)
#   COMMIT       short commit (required)
#   REPO_ROOT    repository root (default: two levels above this script)
#   APKSIGNER    path to apksigner (default: PATH, then newest
#                $ANDROID_HOME|$ANDROID_SDK_ROOT/build-tools/*/apksigner)
#
# signed=true : the APK must verify with `apksigner verify --print-certs`; only the
#               certificate SHA-256 fingerprint is printed. Verification failure,
#               or an unsigned APK, fails the script.
# signed=false: the artifacts are copied with the -unsigned suffix.
set -euo pipefail

if [[ $# -ne 1 || ( $1 != true && $1 != false ) ]]; then
  echo "usage: $0 <true|false>" >&2
  exit 1
fi
signed=$1

version=${VERSION:-}
commit=${COMMIT:-}
if [[ -z $version || -z $commit ]]; then
  echo "collect-android.sh: VERSION and COMMIT must be set" >&2
  exit 1
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=${REPO_ROOT:-$(cd -- "$script_dir/../.." && pwd)}
outputs="$root/PNTome/app/build/outputs"
dest="$root/out/android"

suffix=unsigned
[[ $signed == true ]] && suffix=signed

find_one() { # <dir> <ext>
  local dir=$1 ext=$2 f
  local found=()
  shopt -s nullglob
  for f in "$dir"/*."$ext"; do found+=("$f"); done
  shopt -u nullglob
  if [[ ${#found[@]} -ne 1 ]]; then
    echo "collect-android.sh: expected exactly one .$ext in $dir, found ${#found[@]}" >&2
    return 1
  fi
  printf '%s\n' "${found[0]}"
}

apk=$(find_one "$outputs/apk/release" apk)
aab=$(find_one "$outputs/bundle/release" aab)

find_apksigner() {
  if [[ -n ${APKSIGNER:-} ]]; then printf '%s\n' "$APKSIGNER"; return; fi
  if command -v apksigner >/dev/null 2>&1; then command -v apksigner; return; fi
  local sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
  if [[ -n $sdk && -d $sdk/build-tools ]]; then
    local c
    c=$(ls -d "$sdk"/build-tools/*/apksigner 2>/dev/null | LC_ALL=C sort -V | tail -1 || true)
    [[ -n $c ]] && { printf '%s\n' "$c"; return; }
  fi
  return 1
}

if [[ $signed == true ]]; then
  signer=$(find_apksigner) || { echo "collect-android.sh: apksigner not found (set APKSIGNER or ANDROID_HOME)" >&2; exit 1; }
  if ! verify_out=$("$signer" verify --print-certs "$apk" 2>&1); then
    echo "collect-android.sh: apksigner verification FAILED for $(basename -- "$apk")" >&2
    printf '%s\n' "$verify_out" | head -n 5 >&2
    exit 1
  fi
  digest=$(printf '%s\n' "$verify_out" | sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' | head -1)
  if [[ -z $digest ]]; then
    echo "collect-android.sh: apksigner printed no certificate SHA-256 digest" >&2
    exit 1
  fi
  echo "collect-android.sh: APK signature verified; signer certificate SHA-256: $digest"
fi

mkdir -p -- "$dest"
apk_out="$dest/PNTome-$version-$commit-$suffix.apk"
aab_out="$dest/PNTome-$version-$commit-$suffix.aab"
cp -- "$apk" "$apk_out"
cp -- "$aab" "$aab_out"
echo "collect-android.sh: wrote $(basename -- "$apk_out") and $(basename -- "$aab_out") to $dest"
