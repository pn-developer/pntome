#!/usr/bin/env bash
# Property 13: F-Droid tag pattern selects stable tags only.
# Validates: Requirements 6.9
#
# Extracts the regex from the "UpdateCheckMode: Tags <regex>" line of
# fdroid/metadata/dev.pntome.app.yml and applies it with grep -E to generated tags:
#   v<stable>            must match
#   v<rc> and other tags must not match
# fdroidserver matches the regex from the start of the tag, so the pattern is anchored with ^
# here (the metadata pattern itself keeps its trailing $ and is checked for it).
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

META="$REPO_ROOT/fdroid/metadata/dev.pntome.app.yml"
[[ -f $META ]] || fail "missing $META"

line="$(grep -E '^UpdateCheckMode:[[:space:]]+Tags[[:space:]]+' "$META" || true)"
[[ -n $line && $(printf '%s\n' "$line" | wc -l) -eq 1 ]] || fail "expected exactly one 'UpdateCheckMode: Tags <regex>' line"
pattern="$(printf '%s\n' "$line" | sed -E 's/^UpdateCheckMode:[[:space:]]+Tags[[:space:]]+//; s/[[:space:]]+$//')"
[[ -n $pattern ]] || fail "empty Tags pattern"
[[ $pattern == *'$' ]] || fail "Tags pattern '$pattern' must end with \$ so pre-release tags are excluded"

matches() { printf '%s\n' "$1" | grep -E -q -- "^($pattern)"; }

for ((i = 0; i < ITER; i++)); do
  gen_version stable; s=$REPLY
  matches "v$s" || fail "stable tag v$s must match '$pattern'"

  gen_version rc; r=$REPLY
  ! matches "v$r" || fail "rc tag v$r must not match '$pattern'"

  rand_int 0 209; a=$REPLY
  rand_int 0 99; b=$REPLY
  rand_int 0 99; c=$REPLY
  rand_int 1 98; n=$REPLY
  others=(
    "$s"                 # missing v prefix
    "V$s"                # wrong case
    "v$s-rc.$n"          # pre-release of a stable core
    "v$s-beta.$n"
    "v$s+build.$n"       # build metadata
    "v$s.$n"             # extra component
    "v$s "               # trailing space
    "v$a.$b"             # missing component
    "v$a"
    "v$a.$b.x"
    "release-$a.$b.$c"
    "latest"
    ""
  )
  rand_int 0 $(( ${#others[@]} - 1 ))
  o="${others[$REPLY]}"
  ! matches "$o" || fail "tag '$o' must not match '$pattern'"
done
pass "pattern '$pattern' selects stable tags only ($ITER iterations)"
finish
