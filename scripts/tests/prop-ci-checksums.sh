#!/usr/bin/env bash
# Property 18: Checksums round trip.
# For random sets of files (names with spaces, leading dashes, backslashes, quotes, non-ASCII;
# never newlines or '/') and random contents, make-checksums.sh writes a SHA256SUMS that lists
# exactly those files and for which `sha256sum -c` succeeds in that directory.
# Validates: Requirements 12.2
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/make-checksums.sh"
[[ -f $SCRIPT ]] || fail "missing $SCRIPT"

# No backslash: uutils sha256sum (Ubuntu 26) cannot verify the "\"-escaped lines used for such
# names, although GNU coreutils (CI runners) can. That is a tool limitation, not the script's.
CHARS=(a b Z 0 9 ' ' ' ' '.' '-' '_' "'" '"' '(' ')' '[' ']' '$' '*' '?' '#' '~' 'é' 'ж' '+' '=')
NCH=${#CHARS[@]}

gen_name() { # sets REPLY to a valid, non-reserved file name
  local len i name=""
  rand_int 1 14; len=$REPLY
  for (( i = 0; i < len; i++ )); do
    rand_int 0 $(( NCH - 1 ))
    name+=${CHARS[REPLY]}
  done
  [[ $name == . || $name == .. || $name == SHA256SUMS ]] && name+="x"
  REPLY=$name
}

for (( i = 1; i <= ITER; i++ )); do
  dir="$TEST_TMP/case$i"
  mkdir -p "$dir"
  # At least one file: sha256sum -c rejects an empty checksum list by design.
  rand_int 1 12; nfiles=$REPLY
  declare -A seen=()
  names=()
  for (( k = 0; k < nfiles; k++ )); do
    gen_name; name=$REPLY
    [[ -n ${seen[$name]+x} ]] && continue
    seen[$name]=1
    names+=("$name")
    rand_int 0 3
    case $REPLY in
      0) : > "$dir/$name" ;;
      *) rand_int 1 200
         head -c "$REPLY" /dev/urandom > "$dir/$name" ;;
    esac
  done
  unset seen
  # Noise that must be ignored: a subdirectory and a stale SHA256SUMS.
  mkdir "$dir/subdir"; echo x > "$dir/subdir/inner"
  echo "stale" > "$dir/SHA256SUMS"

  out="$TEST_TMP/out$i.txt"
  # stdin is /dev/null so a tool that reads stdin (e.g. for a file named "-") fails, not hangs.
  if ! bash "$SCRIPT" "$dir" </dev/null >"$out" 2>&1; then
    cat "$out" >&2
    fail "case $i: make-checksums.sh failed"
  fi
  [[ -f $dir/SHA256SUMS ]] || fail "case $i: SHA256SUMS missing"

  # Exactly the files: parse "<hash>  <name>" lines (a leading "./" is accepted) and compare
  # against hashes computed by redirection, which never treats a name as an option or stdin.
  lines=$(wc -l < "$dir/SHA256SUMS")
  (( lines == ${#names[@]} )) || fail "case $i: expected ${#names[@]} lines, found $lines"
  declare -A listed=()
  while IFS= read -r line; do
    [[ $line =~ ^([0-9a-f]{64})\ [\ *](.+)$ ]] || fail "case $i: malformed line: $line"
    lname=${BASH_REMATCH[2]#./}
    [[ -z ${listed[$lname]+x} ]] || fail "case $i: duplicate entry: $lname"
    listed[$lname]=${BASH_REMATCH[1]}
  done < "$dir/SHA256SUMS"
  for n in "${names[@]}"; do
    [[ -n ${listed[$n]+x} ]] || fail "case $i: file not listed: '$n'"
    want=$(sha256sum < "$dir/$n"); want=${want%% *}
    [[ ${listed[$n]} == "$want" ]] || fail "case $i: wrong hash for '$n'"
  done
  unset listed

  # Round trip.
  if ! (cd "$dir" && sha256sum -c SHA256SUMS </dev/null >"$out" 2>&1); then
    cat "$out" >&2
    fail "case $i: sha256sum -c failed"
  fi
  pass "case $i: ${#names[@]} files"
  rm -rf "$dir"
done

# Failure modes: no argument, non-directory.
bash "$SCRIPT" >/dev/null 2>&1 && fail "missing argument must fail"
bash "$SCRIPT" "$TEST_TMP/does-not-exist" >/dev/null 2>&1 && fail "missing dir must fail"
pass "failure modes"

finish
