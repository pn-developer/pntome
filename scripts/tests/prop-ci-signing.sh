#!/usr/bin/env bash
# Property 16: Signing inputs and secret hygiene.
# For any subset of the four signing secrets, scripts/ci/prepare-signing.sh reports signed output
# iff the subset is complete, unsigned for the empty subset, and for a proper non-empty subset exits
# non-zero naming exactly the missing secrets. No secret value appears in stdout or stderr.
# Validates: Requirements 10.1, 10.3, 10.4, 13.5
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/ci/prepare-signing.sh"
[[ -x $SCRIPT || -f $SCRIPT ]] || fail "missing $SCRIPT"

env_names=(KS_B64 KS_PASS KEY_ALIAS KEY_PASS)
secret_names=(ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD)

# Every subset is covered, repeated until ITER cases ran (at least 16).
rounds=$(( (ITER + 15) / 16 ))
case_no=0

for (( r = 0; r < rounds; r++ )); do
  for (( mask = 0; mask < 16; mask++ )); do
    case_no=$(( case_no + 1 ))
    rand_int 100000 999999999; uniq=$REPLY
    work="$TEST_TMP/case.$case_no"
    mkdir -p "$work/runner"
    : >"$work/out"

    # Unique sentinels. The keystore value must be valid base64, so it is the encoding of a sentinel.
    ks_plain="S3NT1NEL-keystore-bytes-$uniq-$case_no"
    sentinels=(
      "$(printf '%s' "$ks_plain" | base64 -w0)"
      "S3NT1NEL-storepass-$uniq-$case_no"
      "S3NT1NEL-alias-$uniq-$case_no"
      "S3NT1NEL-keypass-$uniq-$case_no"
    )

    env_args=(-u KS_B64 -u KS_PASS -u KEY_ALIAS -u KEY_PASS -u GITHUB_STEP_SUMMARY
      "RUNNER_TEMP=$work/runner" "GITHUB_OUTPUT=$work/out")
    expect_missing=()
    set_count=0
    for i in 0 1 2 3; do
      if (( (mask >> i) & 1 )); then
        env_args+=("${env_names[$i]}=${sentinels[$i]}")
        set_count=$(( set_count + 1 ))
      else
        expect_missing+=("${secret_names[$i]}")
      fi
    done

    env "${env_args[@]}" bash "$SCRIPT" >"$work/stdout" 2>"$work/stderr"
    rc=$?
    label="mask=$mask case=$case_no"

    # No secret value (or the decoded keystore text) in stdout, stderr or the outputs file.
    for f in stdout stderr out; do
      for s in "${sentinels[@]}" "$ks_plain"; do
        if grep -qF -- "$s" "$work/$f"; then
          fail "$label: secret value leaked into $f"
        fi
      done
    done

    outputs="$(cat "$work/out")"
    if (( set_count == 4 )); then
      (( rc == 0 )) || fail "$label: complete set exited $rc"
      grep -qxF 'signed=true' "$work/out" || fail "$label: expected signed=true, got: $outputs"
      ks="$work/runner/release.jks"
      grep -qxF "keystore_path=$ks" "$work/out" || fail "$label: keystore_path output wrong: $outputs"
      [[ -s $ks ]] || fail "$label: keystore file not written"
      [[ "$(stat -c %a "$ks")" == 600 ]] || fail "$label: keystore mode is not 600"
      [[ "$(cat "$ks")" == "$ks_plain" ]] || fail "$label: keystore content does not match decoded secret"
    elif (( set_count == 0 )); then
      (( rc == 0 )) || fail "$label: empty set exited $rc"
      grep -qxF 'signed=false' "$work/out" || fail "$label: expected signed=false, got: $outputs"
      grep -qxF 'signed=true' "$work/out" && fail "$label: signed=true for empty set"
      [[ ! -e $work/runner/release.jks ]] || fail "$label: keystore written for empty set"
    else
      (( rc != 0 )) || fail "$label: partial set exited 0"
      grep -q '^signed=' "$work/out" && fail "$label: partial set produced signed output: $outputs"
      [[ ! -e $work/runner/release.jks ]] || fail "$label: keystore written for partial set"
      line="$(grep -F 'Missing secrets:' "$work/stderr" | head -n1)"
      [[ -n $line ]] || fail "$label: stderr does not list missing secrets"
      for i in 0 1 2 3; do
        name="${secret_names[$i]}"
        if (( (mask >> i) & 1 )); then
          [[ $line != *"$name"* ]] || fail "$label: $name is set but reported missing"
        else
          [[ $line == *"$name"* ]] || fail "$label: $name is missing but not reported"
        fi
      done
    fi
    pass "$label"
  done
done

finish
