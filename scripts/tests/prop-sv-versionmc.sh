#!/usr/bin/env bash
# Property 9: Version.mc edit is a single line
# For every valid Version, set-version.sh changes PNTomeGo/source/Version.mc on exactly
# one line (the SEMVER line), which then carries the new version; every other line,
# including COMMIT and the line count, is byte-identical to the original.
#
# set-version.sh runs only inside a throwaway copy of the repository.
#
# **Validates: Requirements 5.13**
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

MC="PNTomeGo/source/Version.mc"
SEMVER_RE='^([[:space:]]*const[[:space:]]+SEMVER[[:space:]]*=[[:space:]]*")[^"]*(";.*)$'

# Add a bullet under "## [Unreleased]" so a stable release has something to rotate.
add_bullet() { # add_bullet <repo>
  local f="$1/CHANGELOG.md" tmp line
  tmp="$(mktemp "$TEST_TMP/cl.XXXXXX")"
  while IFS= read -r line || [[ -n $line ]]; do
    printf '%s\n' "$line"
    if [[ $line =~ ^##[[:space:]]+\[Unreleased\][[:space:]]*$ ]]; then
      printf '\n- property test entry\n'
    fi
  done <"$f" >"$tmp"
  mv "$tmp" "$f"
}

make_repo_copy
BASE_COPY=$REPO_COPY
[[ -f $BASE_COPY/$MC ]] || fail "$MC missing in repo copy"
mapfile -t ORIG <"$BASE_COPY/$MC"

for (( i = 0; i < ITER; i++ )); do
  gen_version any
  v=$REPLY
  # 0.1.0 is already in CHANGELOG.md; skip it so a stable run does not hit the merge refusal.
  if [[ $v == 0.1.0 ]]; then v=0.1.1; fi

  # Fresh copy per case (cheap cp of the base copy).
  work="$(mktemp -d "$TEST_TMP/case.XXXXXX")"
  cp -a "$BASE_COPY/." "$work/"
  add_bullet "$work"

  out="$("$work/scripts/set-version.sh" "$v" --allow-downgrade 2>&1)" \
    || fail "set-version.sh $v failed: $out"

  mapfile -t NEW <"$work/$MC"
  (( ${#NEW[@]} == ${#ORIG[@]} )) \
    || fail "$v: line count changed ${#ORIG[@]} -> ${#NEW[@]}"

  changed=0
  for idx in "${!ORIG[@]}"; do
    if [[ ${ORIG[idx]} != "${NEW[idx]}" ]]; then
      changed=$(( changed + 1 ))
      [[ ${ORIG[idx]} =~ $SEMVER_RE ]] \
        || fail "$v: line $((idx + 1)) changed but is not the SEMVER line: '${ORIG[idx]}'"
      want="${BASH_REMATCH[1]}${v}${BASH_REMATCH[2]}"
      [[ ${NEW[idx]} == "$want" ]] \
        || fail "$v: SEMVER line is '${NEW[idx]}', expected '$want'"
    fi
  done
  (( changed == 1 )) || fail "$v: $changed lines changed in $MC, expected exactly 1"

  # A trailing-newline difference would not show in the array comparison.
  [[ "$(tail -c1 "$BASE_COPY/$MC" | od -An -tx1)" == "$(tail -c1 "$work/$MC" | od -An -tx1)" ]] \
    || fail "$v: final newline of $MC changed"

  grep -q '^[[:space:]]*const COMMIT = "dev";' "$work/$MC" \
    || fail "$v: COMMIT line altered"
  pass "Version.mc single-line edit for $v"
  rm -rf "$work"
done

finish
