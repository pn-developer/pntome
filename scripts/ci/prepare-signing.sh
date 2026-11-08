#!/usr/bin/env bash
# prepare-signing.sh — decide whether the Android build is signed and decode the keystore.
#
# Inputs (environment, set from GitHub secrets via step-level env:):
#   KS_B64     base64 of the release keystore
#   KS_PASS    keystore password
#   KEY_ALIAS  key alias
#   KEY_PASS   key password
#   RUNNER_TEMP  directory for the decoded keystore (default: $TMPDIR or /tmp)
#
# Behaviour:
#   all four set  -> writes $RUNNER_TEMP/release.jks (mode 600), outputs signed=true, keystore_path=<file>
#   none set      -> outputs signed=false, keystore_path= (empty), emits a ::warning::
#   some set      -> exits 1 naming the missing secrets (never values)
# Outputs go to $GITHUB_OUTPUT when set. Secret values are never printed.
set -euo pipefail
umask 077

names=(KS_B64 KS_PASS KEY_ALIAS KEY_PASS)
secret_names=(ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD)

present=0
missing=()
for i in "${!names[@]}"; do
  n=${names[$i]}
  if [[ -n ${!n:-} ]]; then
    present=$((present + 1))
  else
    missing+=("${secret_names[$i]}")
  fi
done

emit() {
  if [[ -n ${GITHUB_OUTPUT:-} ]]; then
    printf '%s=%s\n' "$1" "$2" >>"$GITHUB_OUTPUT"
  fi
}

if [[ $present -eq 0 ]]; then
  echo "::warning title=Unsigned build::No Android signing secrets are configured; the artifacts will be unsigned."
  if [[ -n ${GITHUB_STEP_SUMMARY:-} ]]; then
    echo "**Unsigned build:** no Android signing secrets configured." >>"$GITHUB_STEP_SUMMARY"
  fi
  emit signed false
  emit keystore_path ""
  exit 0
fi

if [[ $present -ne 4 ]]; then
  echo "prepare-signing.sh: incomplete signing configuration. Missing secrets: ${missing[*]}" >&2
  echo "Set all four Android signing secrets, or none for an unsigned build." >&2
  exit 1
fi

dest_dir=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
mkdir -p -- "$dest_dir"
ks="$dest_dir/release.jks"

rm -f -- "$ks"
if ! printf '%s' "$KS_B64" | base64 -d >"$ks" 2>/dev/null || [[ ! -s $ks ]]; then
  rm -f -- "$ks"
  echo "prepare-signing.sh: ANDROID_KEYSTORE_B64 is not valid base64 or decodes to an empty file" >&2
  exit 1
fi
chmod 600 -- "$ks"

emit signed true
emit keystore_path "$ks"
echo "prepare-signing.sh: keystore written to $ks"
