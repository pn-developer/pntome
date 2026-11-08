#!/usr/bin/env bash
# prepare-ciq-key.sh — decode the Connect IQ developer key for a release build.
#
# Environment:
#   DK_B64       base64 of developer_key.der (from the CIQ_DEVELOPER_KEY_B64 secret)
#   RUNNER_TEMP  destination directory (default: $TMPDIR or /tmp)
#
# Writes $RUNNER_TEMP/developer_key.der (mode 600) and appends CIQ_DEVKEY=<path> to
# $GITHUB_ENV when set. Fails when DK_B64 is empty. Never prints the key.
set -euo pipefail
umask 077

if [[ -z ${DK_B64:-} ]]; then
  echo "prepare-ciq-key.sh: CIQ_DEVELOPER_KEY_B64 is empty or not set; a store-grade .iq must be signed with the real developer key" >&2
  exit 1
fi

dest_dir=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$dest_dir"
key="$dest_dir/developer_key.der"

rm -f -- "$key"
if ! printf '%s' "$DK_B64" | base64 -d >"$key" 2>/dev/null || [[ ! -s $key ]]; then
  rm -f -- "$key"
  echo "prepare-ciq-key.sh: CIQ_DEVELOPER_KEY_B64 is not valid base64 or decodes to an empty file" >&2
  exit 1
fi
chmod 600 -- "$key"

if [[ -n ${GITHUB_ENV:-} ]]; then
  echo "CIQ_DEVKEY=$key" >>"$GITHUB_ENV"
fi
echo "prepare-ciq-key.sh: developer key written to $key"
