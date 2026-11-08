#!/usr/bin/env bash
# Property 3: Version code formula and bound.
# For every valid version: version_code = ((MAJOR*10000 + MINOR*100 + PATCH) * 100) + (N or 99),
# and the code stays within the bound. Validates: Requirements 4.5
#
# Note: the real maximum is 209999999 (209.99.99, stable). Requirement 4.5 writes 2099999999
# (one digit too many, a typo); both bounds are asserted.
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
. "$REPO_ROOT/scripts/lib/version.sh"

REAL_MAX=209999999
SPEC_MAX=2099999999

check_one() { # check_one VERSION MAJOR MINOR PATCH N
  local v=$1 ma=$2 mi=$3 pa=$4 n=$5 got want ref
  want=$(( (ma * 10000 + mi * 100 + pa) * 100 + n ))
  got="$(version_code "$v")" || fail "version_code rejected valid version '$v'"
  [[ $got == "$want" ]] || fail "version_code '$v': got $got, want $want"
  ref="$(ref_code "$v")"
  [[ $got == "$ref" ]] || fail "version_code '$v': got $got, reference $ref"
  (( got <= SPEC_MAX )) || fail "version_code '$v' = $got exceeds $SPEC_MAX"
  (( got <= REAL_MAX )) || fail "version_code '$v' = $got exceeds $REAL_MAX"
  (( got >= 1 )) || fail "version_code '$v' = $got is not positive"
  pass "$v -> $got"
}

# Exhaustive boundary sweep.
for ma in 0 1 209; do
  for mi in 0 1 99; do
    for pa in 0 1 99; do
      check_one "$ma.$mi.$pa" "$ma" "$mi" "$pa" 99
      for n in 1 98; do
        check_one "$ma.$mi.$pa-rc.$n" "$ma" "$mi" "$pa" "$n"
      done
    done
  done
done

# The extreme: largest valid version has exactly the real maximum code.
[[ "$(version_code 209.99.99)" == "$REAL_MAX" ]] || fail "209.99.99 should map to $REAL_MAX"
# Smallest valid version.
[[ "$(version_code 0.0.0-rc.1)" == "1" ]] || fail "0.0.0-rc.1 should map to 1"
# Known value from the migration task.
[[ "$(version_code 0.1.0)" == "10099" ]] || fail "0.1.0 should map to 10099"

# Generated cases.
for (( i = 0; i < ITER; i++ )); do
  gen_version any; v=$REPLY
  core=${v%%-*}
  IFS=. read -r ma mi pa <<<"$core"
  n=99
  [[ $v == *-rc.* ]] && n=${v##*-rc.}
  check_one "$v" "$ma" "$mi" "$pa" "$n"
done

finish
