#!/usr/bin/env bash
# Property 12: Tag predicate.
# For any valid VERSION V and any tag ref T, `check-release.sh --tag T` reports a [tag] violation
# iff T != "v" + V. For an invalid/missing VERSION every tag is reported.
# Only the [tag] lines are asserted (other checks may legitimately fail in the copy because only
# VERSION is edited); the exit code is asserted for the mismatch case (must be 1).
# **Validates: Requirements 6.7**
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

make_repo_copy
# Speed: only the [tag] predicate is under test, so drop the bulk content that the other checks
# scan (each full check-release run would otherwise take seconds).
find "$REPO_COPY" -mindepth 1 -maxdepth 1 ! -name .git ! -name scripts ! -name VERSION \
  -exec rm -rf {} +
find "$REPO_COPY/scripts" -mindepth 1 -maxdepth 1 ! -name check-release.sh ! -name lib \
  -exec rm -rf {} +
CHECK="$REPO_COPY/scripts/check-release.sh"
OUT="$TEST_TMP/out.txt"

# run_tag TAG -> sets RC and TAG_LINES (the [tag] lines of stdout)
run_tag() {
  RC=0
  CHECK_RELEASE_ROOT="$REPO_COPY" bash "$CHECK" --tag "$1" >"$OUT" 2>"$OUT.err" || RC=$?
  TAG_LINES="$(grep -F '[tag]' "$OUT" || true)"
}

gen_tag() { # gen_tag V -> REPLY is a tag; sets EXPECT_MATCH (1 when equal to v+V)
  local v=$1 other
  EXPECT_MATCH=0
  rand_int 0 9
  case $REPLY in
    0|1|2) EXPECT_MATCH=1; REPLY="v$v" ;;
    3) REPLY="$v" ;;                              # missing prefix
    4) REPLY="refs/tags/v$v" ;;                   # full ref
    5) REPLY="V$v" ;;                             # wrong case
    6) REPLY="v$v " ;;                            # trailing space
    7) REPLY="" ;;                                # empty
    8) gen_version any; other=$REPLY
       if [[ $other == "$v" ]]; then other="$v.x"; fi
       REPLY="v$other" ;;                         # other version
    9) REPLY="v$v-rc.1" ;;                        # extra suffix (differs even if v is rc: -rc.1-rc.1)
  esac
}

for ((i = 0; i < ITER; i++)); do
  gen_version any; V=$REPLY
  printf '%s\n' "$V" >"$REPO_COPY/VERSION"
  gen_tag "$V"; T=$REPLY
  run_tag "$T"
  if ((EXPECT_MATCH)); then
    [[ -z $TAG_LINES ]] || fail "VERSION='$V' tag='$T' matches but got: $TAG_LINES"
  else
    [[ -n $TAG_LINES ]] || fail "VERSION='$V' tag='$T' differs but no [tag] violation"
    ((RC == 1)) || fail "VERSION='$V' tag='$T' differs but exit code is $RC"
    [[ $TAG_LINES == *"v$V"* ]] || fail "VERSION='$V' tag='$T': message lacks expected 'v$V': $TAG_LINES"
  fi
  pass "i=$i V=$V T=$T"
done

# Invalid VERSION: every tag (even one equal to "v"+content) is a violation.
for ((i = 0; i < 20; i++)); do
  gen_invalid_version; BAD=$REPLY
  printf '%s\n' "$BAD" >"$REPO_COPY/VERSION"
  run_tag "v$BAD"
  [[ -n $TAG_LINES ]] || fail "invalid VERSION='$BAD' accepted tag 'v$BAD'"
done
rm -f "$REPO_COPY/VERSION"
run_tag "v0.1.0"
[[ -n $TAG_LINES ]] || fail "missing VERSION file accepted a tag"

# Without --tag there is never a [tag] line.
gen_version any; V=$REPLY
printf '%s\n' "$V" >"$REPO_COPY/VERSION"
CHECK_RELEASE_ROOT="$REPO_COPY" bash "$CHECK" >"$OUT" 2>/dev/null || true
! grep -qF '[tag]' "$OUT" || fail "[tag] reported without --tag"

finish
