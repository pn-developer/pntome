#!/usr/bin/env bash
# Property 2: Core version
# For every valid Version, core_version prints MAJOR.MINOR.PATCH (the version without any
# -rc.N suffix); the result is a valid stable version, core_version is idempotent on it,
# and equals the input for stable versions. For invalid input it fails and prints nothing.
#
# **Validates: Requirements 4.4**
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
# shellcheck source=../lib/version.sh
. "$REPO_ROOT/scripts/lib/version.sh"

# Independent expectation: rebuild M.m.p from the regex groups.
expected_core() {
  local re='^([0-9]+)\.([0-9]+)\.([0-9]+)(-rc\.[0-9]+)?$'
  [[ $1 =~ $re ]] || return 1
  EXPECTED="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.${BASH_REMATCH[3]}"
}

for (( i = 0; i < ITER; i++ )); do
  # Valid versions: alternate stable / rc so both are well covered.
  if (( i % 2 == 0 )); then gen_version stable; else gen_version rc; fi
  v=$REPLY

  expected_core "$v" || fail "generator produced unparsable version '$v'"
  got="$(core_version "$v")" || fail "core_version rejected valid '$v'"
  [[ $got == "$EXPECTED" ]] || fail "core_version '$v': got '$got', expected '$EXPECTED'"

  valid_version "$got" || fail "core of '$v' is not a valid version: '$got'"
  is_prerelease "$got" && fail "core of '$v' is a pre-release: '$got'"

  again="$(core_version "$got")" || fail "core_version not applicable to its own result '$got'"
  [[ $again == "$got" ]] || fail "core_version not idempotent for '$v': '$got' -> '$again'"

  if (( i % 2 == 0 )) && [[ $got != "$v" ]]; then
    fail "core of stable '$v' differs from input: '$got'"
  fi
  pass "core $v -> $got"

  # Invalid versions: failure and empty stdout.
  gen_invalid_version
  bad=$REPLY
  if out="$(core_version "$bad" 2>/dev/null)"; then
    fail "core_version accepted invalid '$bad' (printed '$out')"
  fi
  [[ -z $out ]] || fail "core_version printed '$out' for invalid '$bad'"
  pass "core rejects invalid"
done

finish
