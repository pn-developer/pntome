#!/usr/bin/env bash
# Property 15: Action pinning predicate.
# For any `uses:` reference in a workflow file, the [workflows] check reports an action-pinning
# violation iff the reference is NOT (a local ./ path, or pinned to a 40-hex SHA, or actions/* or
# gradle/* at @v<N>). Only the `action "..."` [workflows] lines are asserted; the rest of the
# workflow is a minimal valid skeleton so unrelated [workflows] rules stay quiet.
# **Validates: Requirements 13.1, 13.6, 15.1**
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

make_repo_copy
# Speed: drop everything except the checker, its lib and the workflow under test.
find "$REPO_COPY" -mindepth 1 -maxdepth 1 ! -name .git ! -name scripts \
  -exec rm -rf {} +
find "$REPO_COPY/scripts" -mindepth 1 -maxdepth 1 ! -name check-release.sh ! -name lib \
  -exec rm -rf {} +
CHECK="$REPO_COPY/scripts/check-release.sh"
OUT="$TEST_TMP/out.txt"
WF_DIR="$REPO_COPY/.github/workflows"
mkdir -p "$WF_DIR"

HEX=0123456789abcdef
gen_hex() { # gen_hex LEN [upper] -> REPLY
  local len=$1 s="" i
  for ((i = 0; i < len; i++)); do
    rand_int 0 15
    s+=${HEX:REPLY:1}
  done
  [[ ${2:-} == upper ]] && s=${s^^}
  REPLY=$s
}

FIRST=(actions/checkout actions/setup-java actions/upload-artifact actions/cache gradle/actions/setup-gradle gradle/gradle-build-action)
THIRD=(softprops/action-gh-release r0adkll/sign-android-release some-org/some-action docker/build-push-action ACTIONS/checkout actionsx/checkout)

# gen_uses -> REPLY is the reference after "uses: "; EXPECT_OK is 1 when it is allowed.
gen_uses() {
  local first third n
  rand_int 0 $((${#FIRST[@]} - 1)); first=${FIRST[$REPLY]}
  rand_int 0 $((${#THIRD[@]} - 1)); third=${THIRD[$REPLY]}
  EXPECT_OK=0
  rand_int 0 17
  case $REPLY in
    0|1|2) rand_int 0 120; n=$REPLY; EXPECT_OK=1; REPLY="$first@v$n" ;;       # first-party major tag
    3) EXPECT_OK=1; gen_hex 40; REPLY="$third@$REPLY" ;;                        # third-party full SHA
    4) EXPECT_OK=1; gen_hex 40; REPLY="$first@$REPLY" ;;                        # first-party full SHA
    5) EXPECT_OK=1; REPLY="./.github/actions/local" ;;                          # local action
    6) rand_int 0 120; n=$REPLY; REPLY="$third@v$n" ;;                          # third-party major tag
    7) REPLY="$first@main" ;;                                                   # branch
    8) REPLY="$third@master" ;;
    9) rand_int 7 39; gen_hex "$REPLY"; REPLY="$first@$REPLY" ;;                # short SHA
    10) gen_hex 41; REPLY="$third@$REPLY" ;;                                    # SHA too long
    11) gen_hex 40 upper; REPLY="$first@$REPLY" ;;                              # uppercase SHA
    12) rand_int 0 20; n=$REPLY; REPLY="$first@v$n.$n.0" ;;                     # full semver, not major
    13) REPLY="$first@v" ;;                                                     # empty major
    14) REPLY="$first" ;;                                                       # no ref at all
    15) rand_int 0 20; n=$REPLY; REPLY="$first@v$n-beta" ;;                     # tag with suffix
    16) rand_int 0 20; n=$REPLY; REPLY="$first@$n" ;;                           # number without v
    17) gen_hex 39; REPLY="$third@${REPLY}g" ;;                                 # 40 chars, non-hex
  esac
}

for ((i = 0; i < ITER; i++)); do
  gen_uses; U=$REPLY; OK=$EXPECT_OK
  # Randomise surface syntax that must not change the verdict.
  rand_int 0 3; STYLE=$REPLY
  case $STYLE in
    0) USES_LINE="      - uses: $U" ;;
    1) USES_LINE="      - uses: \"$U\"" ;;
    2) USES_LINE="      - uses: $U # pinned by test" ;;
    3) USES_LINE="      - uses: '$U'" ;;
  esac
  cat >"$WF_DIR/ci.yml" <<EOF
name: ci
on: push
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 10
    steps:
$USES_LINE
      - run: echo hello
EOF
  RC=0
  CHECK_RELEASE_ROOT="$REPO_COPY" bash "$CHECK" >"$OUT" 2>"$OUT.err" || RC=$?
  LINES="$(grep -F '[workflows]' "$OUT" | grep -F 'action "' || true)"
  if ((OK)); then
    [[ -z $LINES ]] || fail "allowed ref '$U' (style $STYLE) was reported: $LINES"
  else
    [[ -n $LINES ]] || fail "disallowed ref '$U' (style $STYLE) was not reported"
    ((RC == 1)) || fail "disallowed ref '$U' reported but exit code is $RC"
    [[ $LINES == *"$WF_DIR/ci.yml"* || $LINES == *".github/workflows/ci.yml:"* ]] \
      || fail "violation lacks file:line for '$U': $LINES"
  fi
  pass "i=$i U=$U"
done

finish
