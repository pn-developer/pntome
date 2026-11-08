#!/usr/bin/env bash
# Property 4: Comparison follows SemVer precedence.
# version_cmp A B must equal the independent reference ref_cmp A B, be antisymmetric
# and reflexive, and order X.Y.Z-rc.N below X.Y.Z.
# **Validates: Requirements 4.6**
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=../lib/version.sh
. "$REPO_ROOT/scripts/lib/version.sh"

check_pair() {
  local a=$1 b=$2 got want rev
  want="$(ref_cmp "$a" "$b")"
  got="$(version_cmp "$a" "$b")" || fail "version_cmp '$a' '$b' exited non-zero"
  [[ $got == "$want" ]] || fail "version_cmp '$a' '$b' = '$got', expected '$want'"
  rev="$(version_cmp "$b" "$a")" || fail "version_cmp '$b' '$a' exited non-zero"
  [[ $rev == $(( -want )) ]] || fail "antisymmetry broken for '$a' '$b': got '$got' and '$rev'"
}

i=0
while (( i < ITER )); do
  gen_version any; a=$REPLY
  gen_version any; b=$REPLY
  check_pair "$a" "$b"

  # Reflexive.
  [[ "$(version_cmp "$a" "$a")" == 0 ]] || fail "version_cmp '$a' '$a' is not 0"

  # Same core, different pre-release state: close pairs hit the interesting branches.
  core="${a%%-*}"
  rand_int 1 98
  check_pair "$core-rc.$REPLY" "$core"
  [[ "$(version_cmp "$core-rc.$REPLY" "$core")" == -1 ]] || fail "'$core-rc.$REPLY' is not below '$core'"
  n1=$REPLY
  rand_int 1 98
  check_pair "$core-rc.$n1" "$core-rc.$REPLY"

  i=$(( i + 1 ))
  pass "pair $i"
done

# Fixed examples (numeric, not lexicographic, comparison).
[[ "$(version_cmp 1.2.3-rc.2 1.2.3-rc.10)" == -1 ]] || fail "rc.2 vs rc.10"
[[ "$(version_cmp 0.9.0 0.10.0)" == -1 ]] || fail "0.9.0 vs 0.10.0"
[[ "$(version_cmp 1.0.0 0.99.99)" == 1 ]] || fail "1.0.0 vs 0.99.99"
[[ "$(version_cmp 1.2.3 1.2.3)" == 0 ]] || fail "equal stable"

finish
