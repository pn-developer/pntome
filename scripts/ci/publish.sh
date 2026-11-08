#!/usr/bin/env bash
# publish.sh — create SHA256SUMS and release notes, then the GitHub Release via `gh`.
#
# Environment:
#   VERSION      version without "v" (required)
#   COMMIT       short commit (required)
#   COMMIT_FULL  full commit SHA (required)
#   PRERELEASE   "true" for -rc versions (default: derived from VERSION)
#   GH_TOKEN     token for gh (provided by the workflow)
#   OUT_DIR      staging directory with the downloaded assets (default: <repo>/out)
#   REPO_ROOT    repository root (default: two levels above this script)
#
# Assets: every regular file directly in OUT_DIR (android and watch artifacts merged),
# plus the generated SHA256SUMS. Signing state is derived from the file names:
# any "-unsigned." Android asset means unsigned.
#
# Release flags: --verify-tag always; --prerelease for pre-release versions;
# --draft for a stable version with unsigned artifacts. Fails when a release for the
# tag already exists.
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=${REPO_ROOT:-$(cd -- "$script_dir/../.." && pwd)}
# shellcheck source=../lib/version.sh
. "$root/scripts/lib/version.sh"

version=${VERSION:-}
commit=${COMMIT:-}
commit_full=${COMMIT_FULL:-}
if [[ -z $version || -z $commit || -z $commit_full ]]; then
  echo "publish.sh: VERSION, COMMIT and COMMIT_FULL must be set" >&2
  exit 1
fi
valid_version "$version" || { echo "publish.sh: invalid version: $version" >&2; exit 1; }

tag="v$version"
out=${OUT_DIR:-$root/out}
[[ -d $out ]] || { echo "publish.sh: staging directory not found: $out" >&2; exit 1; }

if is_prerelease "$version"; then prerelease=true; else prerelease=false; fi
if [[ -n ${PRERELEASE:-} && $PRERELEASE != "$prerelease" ]]; then
  echo "publish.sh: PRERELEASE=$PRERELEASE contradicts version $version" >&2
  exit 1
fi

shopt -s nullglob
unsigned=("$out"/*-unsigned.apk "$out"/*-unsigned.aab)
signed_files=("$out"/*-signed.apk "$out"/*-signed.aab)
iq=("$out"/*.iq)
shopt -u nullglob

if [[ ${#signed_files[@]} -eq 0 && ${#unsigned[@]} -eq 0 ]]; then
  echo "publish.sh: no Android assets found in $out" >&2
  exit 1
fi
signed=true
[[ ${#unsigned[@]} -gt 0 ]] && signed=false
watch_built=false
[[ ${#iq[@]} -gt 0 ]] && watch_built=true

# A release for this tag must not exist yet.
if gh release view "$tag" >/dev/null 2>&1; then
  echo "publish.sh: a release for $tag already exists; delete the release and tag and use a new patch version" >&2
  exit 1
fi

notes=$(mktemp "${TMPDIR:-/tmp}/release-notes.XXXXXX")
trap 'rm -f -- "$notes"' EXIT

COMMIT_FULL=$commit_full COMMIT=$commit SIGNED=$signed WATCH_BUILT=$watch_built \
  "$root/scripts/release-notes.sh" "$version" >"$notes"
"$root/scripts/make-checksums.sh" "$out"

assets=()
while IFS= read -r -d '' f; do
  assets+=("$f")
done < <(find "$out" -maxdepth 1 -type f -print0 | LC_ALL=C sort -z)

flags=(--verify-tag --title "PNTome $version" --notes-file "$notes")
[[ $prerelease == true ]] && flags+=(--prerelease)
if [[ $prerelease == false && $signed == false ]]; then
  flags+=(--draft)
fi

gh release create "$tag" "${assets[@]}" "${flags[@]}"
echo "publish.sh: created release $tag (prerelease=$prerelease, signed=$signed, watch=$watch_built, ${#assets[@]} assets)"
