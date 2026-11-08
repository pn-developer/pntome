#!/usr/bin/env bash
# Feature: apache-license-ci-release, Property 11: Drift is detected and reported
# For any version carrier (Gradle versionName, Gradle versionCode, SEMVER, manifest version) and
# any value different from its expected value, check-release.sh exits 1 and the [versions]
# message contains both the expected and the found value.
# Validates: Requirements 6.2, 6.8
#
# One repo copy is reused: each iteration mutates one carrier, runs the check, then
# `git checkout` restores the snapshot.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

make_repo_copy
repo=$REPO_COPY

# Prune files irrelevant to the version checks to keep each check-release.sh run fast, then
# re-snapshot so `git checkout` restores this pruned state.
rm -rf "$repo/.kiro" "$repo/PNTome/app/src/test" "$repo/PNTome/app/src/androidTest" \
       "$repo/PNTome/app/schemas" "$repo/PNTomeGo/resources" "$repo/docs" "$repo/store"
find "$repo/PNTome/app/src/main/java" -type f \
  ! -name PNTomeAppId.kt ! -name SecretAuth.kt ! -name ConnectIqManager.kt -delete
git -C "$repo" add -A
git -C "$repo" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
  commit -q -m "pruned snapshot" --no-verify

run_check() { (cd "$repo" && CHECK_RELEASE_ROOT="$repo" bash scripts/check-release.sh 2>&1); }

# Baseline must be free of [versions] violations, otherwise drift cannot be attributed.
base_out="$(run_check)"
if grep -qE '^\[(versions|version-file)\]' <<<"$base_out"; then
  fail "baseline copy already has version violations: $(grep -E '^\[(versions|version-file)\]' <<<"$base_out" | head -n 2)"
fi

ver="$(head -n1 "$repo/VERSION")"
exp_code="$(ref_code "$ver")"
exp_core="${ver%%-*}"

GRADLE="$repo/PNTome/app/build.gradle.kts"
SEMVER_MC="$repo/PNTomeGo/source/Version.mc"
MANIFEST="$repo/PNTomeGo/manifest.xml"

# gen_found KIND EXPECTED -> REPLY: a value different from EXPECTED, safe inside sed/awk/XML/Kotlin.
gen_found() {
  local kind=$1 exp=$2 tries=0
  while :; do
    case $kind in
      code)
        rand_int 0 2
        case $REPLY in
          0) rand_int 0 99999999 ;;
          1) rand_int 1 3; REPLY=$(( exp + REPLY - 2 )) ;;       # off by one/two around expected
          *) gen_version any; REPLY="$(ref_code "$REPLY")" ;;
        esac ;;
      *)
        rand_int 0 3
        case $REPLY in
          0) gen_version any ;;
          1) gen_version stable ;;
          2) gen_invalid_version; REPLY="${REPLY//[^0-9A-Za-z.+-]/x}"; [[ -n $REPLY ]] || REPLY=x ;;
          *) rand_int 0 9; REPLY="$REPLY.$REPLY.$REPLY" ;;
        esac ;;
    esac
    [[ $REPLY != "$exp" && -n $REPLY ]] && return 0
    (( ++tries > 50 )) && { REPLY="9.9.9"; [[ $kind == code ]] && REPLY=7; return 0; }
  done
}

carriers=(versionName versionCode semver manifest)
for (( i = 0; i < ITER; i++ )); do
  rand_int 0 3; c=${carriers[$REPLY]}
  case $c in
    versionName) exp=$ver ;;
    versionCode) exp=$exp_code ;;
    semver)      exp=$ver ;;
    manifest)    exp=$exp_core ;;
  esac
  gen_found "$([[ $c == versionCode ]] && echo code || echo str)" "$exp"
  found=$REPLY

  case $c in
    versionName)
      sed -E -i "s/^([[:space:]]*versionName[[:space:]]*=[[:space:]]*\")[^\"]*\"/\1${found}\"/" "$GRADLE" ;;
    versionCode)
      sed -E -i "s/^([[:space:]]*versionCode[[:space:]]*=[[:space:]]*)[0-9]+/\1${found}/" "$GRADLE" ;;
    semver)
      sed -E -i "s/(const SEMVER[[:space:]]*=[[:space:]]*\")[^\"]*\"/\1${found}\"/" "$SEMVER_MC" ;;
    manifest)
      awk -v nv="$found" '
        /<iq:application/ { t = 1 }
        t && !d && match($0, /[[:space:]]version="[^"]*"/) {
          s = substr($0, RSTART, RLENGTH); sub(/version="[^"]*"/, "version=\"" nv "\"", s)
          $0 = substr($0, 1, RSTART - 1) s substr($0, RSTART + RLENGTH); d = 1
        }
        { print }' "$MANIFEST" >"$MANIFEST.new" && mv "$MANIFEST.new" "$MANIFEST" ;;
  esac

  # Make sure the mutation really changed the tree (else the case is vacuous).
  if [[ -z "$(git -C "$repo" status --porcelain)" ]]; then
    fail "mutation of $c to '$found' changed nothing"
  fi

  out="$(run_check)"; rc=$?
  (( rc == 1 )) || fail "carrier $c = '$found' (expected '$exp'): exit $rc, wanted 1"
  line="$(grep -E '^\[versions\]' <<<"$out" | head -n1)"
  [[ -n $line ]] || fail "carrier $c = '$found': no [versions] message in: $out"
  [[ $line == *"$exp"* ]] || fail "carrier $c: message lacks expected '$exp': $line"
  [[ $line == *"$found"* ]] || fail "carrier $c: message lacks found '$found': $line"

  git -C "$repo" checkout -q -- . || fail "git checkout failed"
  pass "drift in $c ($found != $exp)"
done

finish
