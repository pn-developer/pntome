#!/usr/bin/env bash
# Property 19: Release notes select the right section.
# For a generated changelog (an [Unreleased] section plus several released sections, each with
# unique marker lines, and link reference lines at the end), release-notes.sh <version> prints
# the body of "## [<version>]" for a stable version and the body of "## [Unreleased]" for an rc
# version, and none of the marker lines of any other section. A stable version without a
# section fails.
# Validates: Requirements 12.3
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/release-notes.sh"
[[ -f $SCRIPT ]] || fail "missing $SCRIPT"

# Writes the changelog to $1; fills the global arrays SEC_NAME[] and SEC_MARKS[] (markers per
# section, newline separated) and SEC_BODY[] (exact body text, without surrounding blank lines).
build_changelog() {
  local file=$1 case_id=$2 target=$3 want_target=$4
  local -A seen=()
  local -a names=()
  local n k name j nb body_lines marks
  SEC_NAME=(); SEC_MARKS=(); SEC_BODY=()

  names+=("Unreleased")
  rand_int 1 5; n=$REPLY
  if [[ $want_target == yes ]]; then seen[$target]=1; names+=("$target"); fi
  for (( k = 0; k < n; k++ )); do
    gen_version stable; name=$REPLY
    [[ -n ${seen[$name]:-} ]] && continue
    seen[$name]=1
    names+=("$name")
  done
  # Shuffle-ish: rotate the released names by a random amount so the target is not always first.
  if (( ${#names[@]} > 2 )); then
    rand_int 0 $(( ${#names[@]} - 2 ))
    local rot=$REPLY
    local -a head=("${names[0]}") tail=("${names[@]:1}") rotated=()
    for (( k = 0; k < ${#tail[@]}; k++ )); do rotated+=("${tail[(k + rot) % ${#tail[@]}]}"); done
    names=("${head[@]}" "${rotated[@]}")
  fi

  {
    printf '# Changelog\n\nAll notable changes.\n\n'
    for (( k = 0; k < ${#names[@]}; k++ )); do
      name=${names[k]}
      if [[ $name == Unreleased ]]; then
        printf '## [Unreleased]\n\n'
      else
        rand_int 0 1
        if (( REPLY == 0 )); then printf '## [%s] - 2024-01-%02d\n\n' "$name" $(( k + 1 )); else printf '## [%s]\n\n' "$name"; fi
      fi
      body=""; marks=""
      rand_int 1 3; nb=$REPLY
      for (( j = 0; j < nb; j++ )); do
        local sub=Added
        rand_int 0 2; (( REPLY == 1 )) && sub=Changed; (( REPLY == 2 )) && sub=Fixed
        local mark="MARK-$case_id-$k-$j-$RANDOM$RANDOM"
        local chunk
        printf -v chunk '### %s\n- %s item\n- %s second item' "$sub" "$mark" "$mark"
        marks+="$mark"$'\n'
        if [[ -z $body ]]; then body=$chunk; else body+=$'\n\n'$chunk; fi
      done
      printf '%s\n\n' "$body"
      SEC_NAME+=("$name"); SEC_MARKS+=("$marks"); SEC_BODY+=("$body")
    done
    for name in "${names[@]}"; do
      printf '[%s]: https://example.invalid/compare/%s\n' "$name" "$name"
    done
  } > "$file"
}

for (( i = 1; i <= ITER; i++ )); do
  gen_version any; version=$REPLY
  if [[ $version == *-rc.* ]]; then
    want=Unreleased
    # Sometimes also include the rc's core version as a decoy released section.
    rand_int 0 1; core=${version%%-*}; decoy=no; (( REPLY == 1 )) && decoy=yes
    build_changelog "$TEST_TMP/CHANGELOG.md" "$i" "$core" "$decoy"
  else
    want=$version
    build_changelog "$TEST_TMP/CHANGELOG.md" "$i" "$version" yes
  fi

  idx=-1
  for (( k = 0; k < ${#SEC_NAME[@]}; k++ )); do
    [[ ${SEC_NAME[k]} == "$want" ]] && idx=$k
  done
  (( idx >= 0 )) || fail "case $i: generator bug, no section $want"

  out="$TEST_TMP/notes$i.txt"
  if ! CHANGELOG_FILE="$TEST_TMP/CHANGELOG.md" bash "$SCRIPT" "$version" >"$out" 2>"$TEST_TMP/err$i.txt"; then
    cat "$TEST_TMP/err$i.txt" >&2
    fail "case $i: release-notes.sh failed for $version"
  fi
  text=$(cat "$out")

  expected_prefix="## PNTome $version"$'\n\n'"${SEC_BODY[idx]}"$'\n\n'"### Build"
  [[ $text == "$expected_prefix"* ]] || {
    printf 'expected prefix:\n%s\n---actual:\n%s\n' "$expected_prefix" "$text" >&2
    fail "case $i: wrong body for $version (want section $want)"
  }
  # No marker of any other section, and no link reference lines.
  for (( k = 0; k < ${#SEC_NAME[@]}; k++ )); do
    (( k == idx )) && continue
    while IFS= read -r m; do
      [[ -z $m ]] && continue
      [[ $text != *"$m"* ]] || fail "case $i: marker $m of section ${SEC_NAME[k]} leaked into notes for $version"
    done <<< "${SEC_MARKS[k]}"
  done
  [[ $text != *"example.invalid"* ]] || fail "case $i: link reference line leaked into notes"
  pass "case $i: $version -> [$want]"
done

# A stable version with no section fails and prints nothing on stdout.
build_changelog "$TEST_TMP/CHANGELOG.md" neg 0.0.0 no
while :; do   # a valid stable version that is not a section of this changelog
  gen_version stable; missing=$REPLY
  found=no
  for name in "${SEC_NAME[@]}"; do [[ $name == "$missing" ]] && found=yes; done
  [[ $found == no ]] && break
done
if CHANGELOG_FILE="$TEST_TMP/CHANGELOG.md" bash "$SCRIPT" "$missing" >"$TEST_TMP/neg.out" 2>/dev/null; then
  fail "stable version without section must fail"
fi
[[ ! -s $TEST_TMP/neg.out ]] || fail "failed run must not print notes"
pass "missing section"

finish
