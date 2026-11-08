#!/usr/bin/env bash
# Property 1: Version grammar
# For any string, valid_version accepts it iff it matches MAJOR.MINOR.PATCH or
# MAJOR.MINOR.PATCH-rc.N with no leading zeros, MAJOR <= 209, MINOR/PATCH <= 99,
# N in 1..98, and no build metadata or other pre-release identifier.
# **Validates: Requirements 4.2, 4.3**
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=../lib/version.sh
. "$REPO_ROOT/scripts/lib/version.sh"

# Independent oracle (character-level, no regex): returns 0 if the string is a valid Version.
_num_ok() { # _num_ok STR MIN MAX -> canonical decimal in range
  local s=$1 min=$2 max=$3
  [[ -n $s ]] || return 1
  [[ $s != *[!0-9]* ]] || return 1
  (( ${#s} <= 1 )) || [[ ${s:0:1} != 0 ]] || return 1
  (( ${#s} <= 6 )) || return 1
  (( s >= min && s <= max ))
}

oracle() {
  local s=$1 core rest="" n="" a b c extra
  [[ $s != *[[:space:]]* ]] || return 1
  [[ $s != *+* ]] || return 1
  core=$s
  if [[ $s == *-* ]]; then
    core=${s%%-*}
    rest=${s#*-}
    [[ $rest == rc.* ]] || return 1
    n=${rest#rc.}
    _num_ok "$n" 1 98 || return 1
  fi
  IFS=. read -r a b c extra <<<"$core"
  [[ -z ${extra:-} ]] || return 1
  [[ $core == "$a.$b.$c" ]] || return 1
  _num_ok "$a" 0 209 || return 1
  _num_ok "$b" 0 99 || return 1
  _num_ok "$c" 0 99 || return 1
}

check() { # check STRING EXPECT(yes|no|oracle)
  local s=$1 expect=$2 got=no want
  if valid_version "$s"; then got=yes; fi
  if [[ $expect == oracle ]]; then
    want=no; if oracle "$s"; then want=yes; fi
  else
    want=$expect
  fi
  [[ $got == "$want" ]] || fail "valid_version '${s//$'\n'/\\n}': got $got, expected $want"
  pass "$s"
}

# Generated valid versions are accepted.
for ((i = 0; i < ITER; i++)); do
  gen_version any; check "$REPLY" yes
done

# Generated invalid versions are rejected (and the oracle agrees they are invalid).
for ((i = 0; i < ITER; i++)); do
  gen_invalid_version; s=$REPLY
  if oracle "$s"; then fail "generator produced a valid string: '$s'"; fi
  check "$s" no
done

# Random strings over a grammar-heavy alphabet: library and oracle must agree.
alphabet=(0 1 2 5 9 . . - + r c ' ' a)
for ((i = 0; i < ITER * 3; i++)); do
  rand_int 0 14; len=$REPLY
  s=""
  for ((j = 0; j < len; j++)); do
    rand_int 0 $(( ${#alphabet[@]} - 1 )); s+="${alphabet[$REPLY]}"
  done
  check "$s" oracle
done

# Boundary sweep: limits are inclusive, one past is rejected.
for v in 0.0.0 209.99.99 209.99.99-rc.98 0.0.0-rc.1 1.0.0-rc.98; do check "$v" yes; done
for v in 210.0.0 0.100.0 0.0.100 0.0.0-rc.99 0.0.0-rc.0 01.0.0 0.0.0+b 0.0.0-beta.1 ''; do
  check "$v" no
done

finish
