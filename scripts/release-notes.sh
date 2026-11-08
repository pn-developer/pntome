#!/usr/bin/env bash
# release-notes.sh <version>
#   Prints the GitHub Release body for <version> on stdout.
#   Changelog body: "## [<version>]" section for stable versions,
#   "## [Unreleased]" section for -rc versions.
#
# Environment:
#   COMMIT_FULL   full commit SHA (default: unknown)
#   COMMIT        short commit (default: first 7 chars of COMMIT_FULL, else unknown)
#   SIGNED        "true" = signed build; anything else = unsigned
#   WATCH_BUILT   "true" = .iq built by CI; anything else = not built
#   CHANGELOG_FILE  changelog path (default: <repo root>/CHANGELOG.md)
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib/version.sh
. "$script_dir/lib/version.sh"

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <version>" >&2
  exit 1
fi
version=$1
if ! valid_version "$version"; then
  echo "release-notes.sh: invalid version: $version" >&2
  exit 1
fi

changelog=${CHANGELOG_FILE:-$script_dir/../CHANGELOG.md}
if [[ ! -f $changelog ]]; then
  echo "release-notes.sh: changelog not found: $changelog" >&2
  exit 1
fi

if is_prerelease "$version"; then
  heading="Unreleased"
else
  heading=$version
fi

# Body of the section whose heading starts with "## [<heading>]" up to the next "## " heading.
body=$(awk -v want="## [$heading]" '
  /^## / {
    if (in_sec) exit
    if (index($0, want) == 1) { in_sec = 1; found = 1; next }
  }
  in_sec && $0 !~ /^\[[^]]+\]: / { print }
  END { if (!found) exit 3 }
' "$changelog") || {
  echo "release-notes.sh: section '## [$heading]' not found in $changelog" >&2
  exit 1
}
# Trim leading and trailing blank lines.
body=$(printf '%s\n' "$body" | sed -e '/./,$!d' | sed -e ':a' -e '/^\n*$/{$d;N;ba' -e '}')

commit_full=${COMMIT_FULL:-unknown}
commit=${COMMIT:-}
if [[ -z $commit ]]; then
  if [[ $commit_full != unknown ]]; then commit=${commit_full:0:7}; else commit=unknown; fi
fi
code=$(version_code "$version")

if [[ ${SIGNED:-} == true ]]; then
  signing="signed"
else
  signing="UNSIGNED (secrets were not configured)"
fi
if [[ ${WATCH_BUILT:-} == true ]]; then
  ciq="included"
else
  ciq="not built by CI (attach manually, see docs/RELEASING.md)"
fi

printf '## PNTome %s\n\n' "$version"
if [[ -n $body ]]; then
  printf '%s\n\n' "$body"
fi
cat <<EOF
### Build
- Commit: $commit_full (short: $commit)
- Tag: v$version
- Android versionCode: $code
- Watch app shows: v$version ($commit)
- Signing: $signing
- Connect IQ package: $ciq

### Checksums
SHA256SUMS lists the SHA-256 of every asset. Verify with: sha256sum -c SHA256SUMS
EOF
