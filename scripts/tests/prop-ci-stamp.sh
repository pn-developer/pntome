#!/usr/bin/env bash
# Property 21: Commit stamp assertion.
# For a generated expected VERSION/COMMIT and a generated build log, `collect-watch.sh --check-only`
# accepts the log iff it holds at least one "build.sh: version <V> (<C>)" line, and every such
# line equals the expected version and commit exactly (no "dev", no "-dirty", no other commit).
# Noise lines (including look-alikes that are not stamp lines) never change the verdict.
# Validates: Requirements 14.4, 14.3
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/ci/collect-watch.sh"
[[ -f $SCRIPT ]] || fail "missing $SCRIPT"

HEX=0123456789abcdef
gen_commit() { # sets REPLY to 7..12 hex characters
  local len i c=""
  rand_int 7 12; len=$REPLY
  for (( i = 0; i < len; i++ )); do
    rand_int 0 15
    c+=${HEX:REPLY:1}
  done
  REPLY=$c
}

# Noise: none of these is a stamp line (the stamp regex is anchored at line start).
gen_noise() { # sets REPLY to one noise line
  local v=$1 c=$2
  local -a noise=(
    "Compiling PNTomeGo..."
    "BUILD SUCCESSFUL"
    "WARNING: something about version $v"
    ""
    "  build.sh: version $v (dev)"
    "note: build.sh: version $v ($c-dirty)"
    "version $v ($c)"
    "build.sh: SDK /opt/connectiq"
    "build.sh: exporting $v"
    "build.sh: version"
    "COMMIT = \"dev\""
    "wrote dist/PNTomeGo-$v.iq"
  )
  rand_int 0 $(( ${#noise[@]} - 1 ))
  REPLY="${noise[$REPLY]}"
}

# Stamp line kinds: 0 good, 1 dev, 2 dirty, 3 other commit, 4 other version.
gen_stamp() { # gen_stamp KIND V C -> REPLY
  local kind=$1 v=$2 c=$3 other ov
  case $kind in
    0) REPLY="build.sh: version $v ($c)" ;;
    1) REPLY="build.sh: version $v (dev)" ;;
    2) REPLY="build.sh: version $v ($c-dirty)" ;;
    3)
      gen_commit; other=$REPLY
      [[ $other != "$c" ]] || other="${c}0"
      REPLY="build.sh: version $v ($other)"
      ;;
    4)
      gen_version any; ov=$REPLY
      [[ $ov != "$v" ]] || ov="$v.x"
      REPLY="build.sh: version $ov ($c)"
      ;;
  esac
}

for (( i = 1; i <= ITER; i++ )); do
  gen_version any; version=$REPLY
  gen_commit; commit=$REPLY

  rand_int 0 3; nstamps=$REPLY   # 0 stamp lines is a valid (rejected) case
  rand_int 0 6; nlines=$(( nstamps + REPLY ))
  want=0
  (( nstamps > 0 )) && want=1

  # Decide the position of each stamp among the noise lines.
  log="$TEST_TMP/build$i.log"
  : > "$log"
  placed=0
  for (( k = 0; k < nlines; k++ )); do
    remaining_slots=$(( nlines - k ))
    need=$(( nstamps - placed ))
    put=0
    if (( need > 0 )); then
      if (( need >= remaining_slots )); then put=1
      else rand_int 0 1; put=$REPLY; fi
    fi
    if (( put )); then
      # Mostly good stamps, so that all-good logs (accept) are well represented.
      rand_int 0 9
      if (( REPLY < 6 )); then kind=0; else rand_int 1 4; kind=$REPLY; fi
      (( kind == 0 )) || want=0
      gen_stamp "$kind" "$version" "$commit"
      printf '%s\n' "$REPLY" >> "$log"
      placed=$(( placed + 1 ))
    else
      gen_noise "$version" "$commit"
      printf '%s\n' "$REPLY" >> "$log"
    fi
  done

  # Sometimes drop the final newline (a stamp as the last, unterminated line is still a line).
  rand_int 0 3
  if (( REPLY == 0 )) && [[ -s $log ]]; then
    printf '%s' "$(cat "$log")" > "$log.tmp" && mv "$log.tmp" "$log"
  fi

  got=0
  bash "$SCRIPT" --check-only "$version" "$commit" "$log" >/dev/null 2>&1 && got=1
  if (( got != want )); then
    cat "$log" >&2
    fail "case $i: version=$version commit=$commit expected accept=$want got=$got"
  fi
  pass "case $i: accept=$want"
done

# Deterministic anchors.
good="$TEST_TMP/good.log"; printf 'x\nbuild.sh: version 1.2.3-rc.2 (abc1234)\ny\n' > "$good"
bash "$SCRIPT" --check-only 1.2.3-rc.2 abc1234 "$good" >/dev/null 2>&1 || fail "good log must be accepted"
bash "$SCRIPT" --check-only 1.2.3 abc1234 "$good" >/dev/null 2>&1 && fail "different version must be rejected"
bash "$SCRIPT" --check-only 1.2.3-rc.2 abc1235 "$good" >/dev/null 2>&1 && fail "different commit must be rejected"
bash "$SCRIPT" --check-only 1.2.3-rc.2 abc1234 "$TEST_TMP/absent.log" >/dev/null 2>&1 && fail "missing log must be rejected"
bash "$SCRIPT" --check-only 1.2.3-rc.2 abc1234 >/dev/null 2>&1 && fail "missing argument must be rejected"
pass "anchors"

finish
