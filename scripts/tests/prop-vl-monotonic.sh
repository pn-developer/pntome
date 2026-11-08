#!/usr/bin/env bash
# Property 5: Version code is monotonic.
# For valid versions A < B (SemVer precedence), version_code A < version_code B.
# Includes the pairs X.Y.Z-rc.98 < X.Y.Z and X.Y.Z < X.Y.(Z+1)-rc.1.
# **Validates: Requirements 7.1**
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$HERE/lib.sh"
# shellcheck source=../lib/version.sh
. "$REPO_ROOT/scripts/lib/version.sh"

# assert_lower A B: A must be below B in precedence and in version code.
assert_lower() {
  local a=$1 b=$2 ca cb
  [[ "$(version_cmp "$a" "$b")" == -1 ]] || fail "version_cmp '$a' '$b' is not -1"
  ca="$(version_code "$a")" || fail "version_code '$a' failed"
  cb="$(version_code "$b")" || fail "version_code '$b' failed"
  (( ca < cb )) || fail "version_code not monotonic: '$a' ($ca) !< '$b' ($cb)"
}

i=0
while (( i < ITER )); do
  # Random pair: whenever the reference says A < B (either order), codes must follow.
  gen_version any; a=$REPLY
  gen_version any; b=$REPLY
  case "$(ref_cmp "$a" "$b")" in
    -1) assert_lower "$a" "$b" ;;
    1)  assert_lower "$b" "$a" ;;
    0)  [[ "$(version_code "$a")" == "$(version_code "$b")" ]] || fail "equal versions '$a' '$b' differ in code" ;;
  esac

  # Pair 1: X.Y.Z-rc.98 < X.Y.Z (any valid core).
  gen_version stable; core=$REPLY
  assert_lower "$core-rc.98" "$core"
  rand_int 1 97
  assert_lower "$core-rc.$REPLY" "$core"

  # Pair 2: X.Y.Z < X.Y.(Z+1)-rc.1 (Z must leave room for Z+1 <= 99).
  IFS=. read -r x y z <<<"$core"
  if (( z < 99 )); then
    assert_lower "$core" "$x.$y.$(( z + 1 ))-rc.1"
  fi

  i=$(( i + 1 ))
  pass "case $i"
done

# Fixed examples and boundaries.
assert_lower 1.2.3-rc.98 1.2.3
assert_lower 1.2.3 1.2.4-rc.1
assert_lower 0.0.0-rc.1 0.0.0
assert_lower 0.1.0 0.1.1-rc.1
assert_lower 0.0.99 0.1.0-rc.1
assert_lower 0.99.99 1.0.0-rc.1
assert_lower 208.99.99 209.0.0-rc.1
assert_lower 209.99.98 209.99.99-rc.1

finish
