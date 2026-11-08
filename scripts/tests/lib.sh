#!/usr/bin/env bash
# scripts/tests/lib.sh -- shared helper for the shell property tests (Requirement 15.4).
#
# Source it from a test file:   . "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
#
# Provides:
#   SEED, ITER           SEED from env or random (printed on every failure); ITER defaults to 100.
#   rand_int LO HI       sets REPLY to a pseudo-random integer in [LO, HI] (deterministic for a SEED).
#   gen_version [stable|rc|any]    sets REPLY to a valid version, boundary values included.
#   gen_invalid_version  sets REPLY to a string that violates the VERSION grammar.
#   ref_cmp A B          prints -1, 0 or 1 (SemVer precedence for the restricted grammar).
#   ref_code V           prints the expected versionCode.
#   make_repo_copy       sets REPO_COPY to a fresh git repo holding tracked files + VERSION.
#   pass MSG / fail MSG  result helpers; fail prints the seed and exits 1.
#   finish               prints a summary; exits 1 if anything failed.
#
# Generators set REPLY (no command substitution) so the PRNG state advances in the caller.
# Only bash, git, cp, mktemp, od are needed. The real repository is never modified.

TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$TESTS_DIR/../.." && pwd)"

ITER="${ITER:-100}"
if [[ -z "${SEED:-}" ]]; then
  SEED="$(od -An -N4 -tu4 /dev/urandom | tr -d ' ')"
fi
[[ "$SEED" =~ ^[0-9]+$ ]] || { echo "SEED must be a non-negative integer, got '$SEED'" >&2; exit 2; }
_RS=$(( SEED % 2147483647 ))
(( _RS == 0 )) && _RS=1

PASS_COUNT=0
FAIL_COUNT=0
TEST_TMP="$(mktemp -d "${TMPDIR:-/tmp}/prop-test.XXXXXX")"
REPO_COPY=""
REPLY=""

cleanup() {
  local rc=$?
  [[ -n "${TEST_TMP:-}" && -d "$TEST_TMP" ]] && rm -rf "$TEST_TMP"
  if (( rc != 0 )); then
    echo "FAILED (SEED=$SEED ITER=$ITER) -- rerun with: SEED=$SEED $0" >&2
  fi
  return 0
}
trap cleanup EXIT

pass() {
  PASS_COUNT=$(( PASS_COUNT + 1 ))
  [[ -z "${VERBOSE:-}" ]] || echo "ok: $*"
  return 0
}

fail() {
  FAIL_COUNT=$(( FAIL_COUNT + 1 ))
  echo "FAIL: $* (SEED=$SEED)" >&2
  exit 1
}

finish() {
  echo "$PASS_COUNT passed, $FAIL_COUNT failed (SEED=$SEED ITER=$ITER)"
  (( FAIL_COUNT == 0 ))
}

# --- PRNG: Park-Miller minimal standard, state kept in the calling shell ---------------------
rand_int() {
  local lo=$1 hi=$2
  _RS=$(( (_RS * 48271) % 2147483647 ))
  REPLY=$(( lo + (_RS >> 3) % (hi - lo + 1) ))
}

_pick() { # _pick a b c ... -> REPLY is one of the arguments
  rand_int 1 $#
  eval "REPLY=\${$REPLY}"
}

# Limits: MAJOR <= 209, MINOR/PATCH <= 99, N in 1..98.
_gen_part() { # _gen_part MAX -> REPLY in 0..MAX, boundary-heavy
  local max=$1
  rand_int 0 5
  case $REPLY in
    0) REPLY=0 ;;
    1) REPLY=1 ;;
    2) REPLY=$max ;;
    *) rand_int 0 "$max" ;;
  esac
}

_gen_n() {
  rand_int 0 4
  case $REPLY in
    0) REPLY=1 ;;
    1) REPLY=98 ;;
    *) rand_int 1 98 ;;
  esac
}

gen_version() {
  local kind="${1:-any}" ma mi pa n=""
  _gen_part 209; ma=$REPLY
  _gen_part 99;  mi=$REPLY
  _gen_part 99;  pa=$REPLY
  if [[ $kind == any ]]; then
    rand_int 0 1
    if (( REPLY == 1 )); then kind=rc; else kind=stable; fi
  fi
  if [[ $kind == rc ]]; then
    _gen_n
    REPLY="$ma.$mi.$pa-rc.$REPLY"
  else
    REPLY="$ma.$mi.$pa"
  fi
}

gen_invalid_version() {
  local base ma mi pa
  gen_version stable; base=$REPLY
  rand_int 0 99; ma=$REPLY
  rand_int 0 99; mi=$REPLY
  rand_int 0 99; pa=$REPLY
  local t=$'\t' nl=$'\n'
  local -a bad=(
    "0$ma.$mi.$pa"            # leading zero in MAJOR (00, 01 ... even when ma is 0 -> "00")
    "$ma.0$mi.$pa"            # leading zero in MINOR
    "$ma.$mi.0$pa"            # leading zero in PATCH
    "$ma.$mi.$pa-rc.0$ma"     # leading zero in N
    "$ma.$mi.$pa-rc.0"        # N = 0
    "$base+build.$ma"         # build metadata
    "$base+$ma"
    "$base-beta.1"            # unsupported pre-release
    "$base-alpha"
    "$base-rc"                # rc without number
    "$base-rc.1.2"
    "$base-rc.1+b"
    "$ma.$mi.$pa-rc.99"       # N over limit
    "$ma.$mi.$pa-rc.$((100 + ma))"
    "$((210 + ma)).$mi.$pa"   # MAJOR over limit
    "$ma.$((100 + mi)).$pa"   # MINOR over limit
    "$ma.$mi.$((100 + pa))"   # PATCH over limit
    "$ma.$mi.99999999999999999999"
    ""                        # empty
    " "
    " $base"                  # whitespace
    "$base "
    "$base$t"
    "$base$nl"
    "$ma. $mi.$pa"
    "$ma .$mi.$pa"
    "v$base"                  # prefix
    "$ma.$mi"                 # missing component
    "$ma"
    "$ma.$mi.$pa.0"           # extra component
    "$ma.$mi.-1"
    "-$ma.$mi.$pa"
    "$ma..$pa"
    "a.b.c"
  )
  rand_int 0 $(( ${#bad[@]} - 1 ))
  REPLY="${bad[$REPLY]}"
}

# --- Reference implementations (deliberately not shaped like scripts/lib/version.sh) ----------
# They accept only valid versions; callers must not pass invalid input.
_ref_split() { # sets _M _m _p _N (empty N for stable)
  local re='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.([1-9][0-9]*))?$'
  [[ $1 =~ $re ]] || { echo "ref: invalid version '$1'" >&2; return 1; }
  _M=${BASH_REMATCH[1]} _m=${BASH_REMATCH[2]} _p=${BASH_REMATCH[3]} _N=${BASH_REMATCH[5]}
}

ref_code() {
  _ref_split "$1" || return 1
  # Digit-group layout: MMM mm pp nn  ->  M*10^6 + m*10^4 + p*10^2 + (N or 99)
  echo $(( 10#$_M * 1000000 + 10#$_m * 10000 + 10#$_p * 100 + 10#${_N:-99} ))
}

ref_cmp() {
  _ref_split "$1" || return 1
  local -a a=("$_M" "$_m" "$_p" "${_N:-}")
  _ref_split "$2" || return 1
  local -a b=("$_M" "$_m" "$_p" "${_N:-}")
  local i
  for i in 0 1 2; do
    if (( 10#${a[i]} < 10#${b[i]} )); then echo -1; return 0; fi
    if (( 10#${a[i]} > 10#${b[i]} )); then echo 1; return 0; fi
  done
  # Same core: a pre-release sorts below the release; two rc's order by N.
  local an=${a[3]:-} bn=${b[3]:-}
  if [[ -z $an && -z $bn ]]; then echo 0
  elif [[ -z $an ]]; then echo 1
  elif [[ -z $bn ]]; then echo -1
  elif (( 10#$an < 10#$bn )); then echo -1
  elif (( 10#$an > 10#$bn )); then echo 1
  else echo 0
  fi
}

# --- Throwaway repo copy -----------------------------------------------------------------------
# Copies tracked files plus VERSION (which may be untracked) into a fresh temp git repo.
# Call as a plain command (not in $(...)); the path is returned in REPO_COPY.
make_repo_copy() {
  local dest f
  dest="$(mktemp -d "$TEST_TMP/repo.XXXXXX")"
  while IFS= read -r -d '' f; do
    [[ -f $f ]] || continue
    mkdir -p "$dest/$(dirname "$f")"
    cp -p "$REPO_ROOT/$f" "$dest/$f"
  done < <(git -C "$REPO_ROOT" ls-files -z --cached --others --exclude-standard)
  if [[ -f $REPO_ROOT/VERSION ]]; then
    cp -p "$REPO_ROOT/VERSION" "$dest/VERSION"
  fi
  git -C "$dest" init -q
  git -C "$dest" add -A
  git -C "$dest" -c user.name=test -c user.email=test@example.invalid \
    -c commit.gpgsign=false commit -q -m "test snapshot" --no-verify
  REPO_COPY="$dest"
}
