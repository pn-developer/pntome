#!/usr/bin/env bash
# make-checksums.sh <dir>
#   Writes <dir>/SHA256SUMS in `sha256sum` format for every regular file directly
#   inside <dir> (sorted by byte order, SHA256SUMS itself excluded).
#   Verify with: cd <dir> && sha256sum -c SHA256SUMS
set -euo pipefail

if [[ $# -ne 1 || -z ${1-} ]]; then
  echo "usage: $0 <dir>" >&2
  exit 1
fi
dir=$1
if [[ ! -d $dir ]]; then
  echo "make-checksums.sh: not a directory: $dir" >&2
  exit 1
fi

cd -- "$dir"

tmp=$(mktemp "${TMPDIR:-/tmp}/sha256sums.XXXXXX")
trap 'rm -f -- "$tmp"' EXIT

files=()
while IFS= read -r -d '' f; do
  files+=("$f")
done < <(find . -maxdepth 1 -type f ! -name SHA256SUMS -printf '%P\0' | LC_ALL=C sort -z)

: >"$tmp"
for f in "${files[@]}"; do
  # Hash "./$f" so a file named "-" is not read as stdin (also not by `sha256sum -c`).
  # sha256sum escapes backslashes/newlines itself. The "./" prefix is stripped from the
  # listing except for the name "-", which must keep it to stay a file path.
  if [[ $f == - ]]; then
    sha256sum -- "./$f" >>"$tmp"
  else
    sha256sum -- "./$f" | sed -E 's#^(\\?[0-9a-f]{64}  )\./#\1#' >>"$tmp"
  fi
done

cat -- "$tmp" >SHA256SUMS
echo "make-checksums.sh: wrote $dir/SHA256SUMS (${#files[@]} files)" >&2
