#!/usr/bin/env bash
# Property 10: Refusals write nothing.
# For inputs that set-version.sh must refuse (invalid version, downgrade, empty Unreleased on a
# stable version, a carrier pattern that does not match exactly once) the script exits non-zero
# and the working tree is byte-identical afterwards: same `git status --porcelain` and same
# content hash of every file outside .git.
# **Validates: Requirements 5.9, 5.10**
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

make_repo_copy
R="$REPO_COPY"
[[ -f $R/scripts/set-version.sh ]] || fail "set-version.sh missing in the copy"

reset_copy() {
  git -C "$R" reset -q --hard HEAD
  git -C "$R" clean -fdxq
}

tree_state() { # prints porcelain status and a content hash of all files outside .git
  git -C "$R" status --porcelain
  ( cd "$R" && find . -path ./.git -prune -o -type f -print0 | LC_ALL=C sort -z \
      | xargs -0 sha256sum | sha256sum )
}

# Remove every bullet line from the [Unreleased] section of CHANGELOG.md.
empty_unreleased() {
  awk '
    /^##[[:space:]]+\[Unreleased\]/ { inu=1; print; next }
    inu && (/^##[[:space:]]/ || /^\[[^]]+\]:[[:space:]]/) { inu=0 }
    inu && /^[[:space:]]*[-*][[:space:]]+/ { next }
    { print }
  ' "$R/CHANGELOG.md" >"$R/CHANGELOG.md.new" && mv "$R/CHANGELOG.md.new" "$R/CHANGELOG.md"
}

CUR="$(head -n 1 "$R/VERSION" | tr -d '\r')"

for (( i = 0; i < ITER; i++ )); do
  reset_copy
  kind=$(( i % 4 ))
  args=()
  want_rc=1
  desc=""
  case $kind in
    0) # invalid version string
      gen_invalid_version; v=$REPLY
      args=("$v"); desc="invalid version '$v'"
      ;;
    1) # downgrade without --allow-downgrade
      gen_version any; x=$REPLY
      while :; do
        gen_version any; y=$REPLY
        c="$(ref_cmp "$y" "$x")"
        if [[ $c == -1 ]]; then break; fi
        if [[ $c == 1 ]]; then t=$x; x=$y; y=$t; break; fi
      done
      printf '%s\n' "$x" >"$R/VERSION"
      args=("$y"); desc="downgrade $x -> $y"
      ;;
    2) # stable version with an empty [Unreleased]
      while :; do
        gen_version stable; v=$REPLY
        [[ "$(ref_cmp "$v" "$CUR")" != -1 ]] && break
      done
      empty_unreleased
      args=("$v"); desc="empty Unreleased for $v"
      ;;
    3) # pattern mismatch: versionCode line removed from the Gradle file (exit 2)
      while :; do
        gen_version any; v=$REPLY
        [[ "$(ref_cmp "$v" "$CUR")" != -1 ]] && break
      done
      grep -v -E '^[[:space:]]*versionCode[[:space:]]*=' "$R/PNTome/app/build.gradle.kts" \
        >"$R/gradle.new" && mv "$R/gradle.new" "$R/PNTome/app/build.gradle.kts"
      args=("$v"); want_rc=2; desc="missing versionCode literal for $v"
      ;;
  esac

  before="$(tree_state)"
  out="$(cd "$R" && bash scripts/set-version.sh "${args[@]}" 2>&1)"
  rc=$?
  after="$(tree_state)"

  if (( rc != want_rc )); then
    fail "$desc: expected exit $want_rc, got $rc: $out"
  fi
  if [[ $before != "$after" ]]; then
    fail "$desc: working tree changed after refusal (exit $rc)"
  fi
  pass "$desc"
done

finish
