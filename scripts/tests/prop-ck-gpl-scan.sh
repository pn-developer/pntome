#!/usr/bin/env bash
# Feature: apache-license-ci-release, Property 14: GPL scan honours the allowlist
# For any tracked file and any GPL token (GPL, LGPL, GNU General Public, GPL- followed by a digit),
# check-release.sh reports a [license] violation naming the file if the file is outside the
# Allowlist, and reports none for that file if it is inside.
# Allowlist: .kiro/specs/**, PNTome/gradlew*, and the single CHANGELOG.md relicensing line.
# Validates: Requirements 2.7, 2.4, 3.5
#
# One pruned repo copy is reused; each iteration adds one probe file (or CHANGELOG line), runs the
# check, then removes it.
set -uo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

make_repo_copy
repo=$REPO_COPY

# Prune files irrelevant to the license scan to keep each run fast.
rm -rf "$repo/.kiro" "$repo/PNTome/app/src/test" "$repo/PNTome/app/src/androidTest" \
       "$repo/PNTome/app/schemas" "$repo/PNTomeGo/resources" "$repo/docs" "$repo/store"
find "$repo/PNTome/app/src/main/java" -type f \
  ! -name PNTomeAppId.kt ! -name SecretAuth.kt ! -name ConnectIqManager.kt -delete
git -C "$repo" add -A
git -C "$repo" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
  commit -q -m "pruned snapshot" --no-verify

run_check() { (cd "$repo" && CHECK_RELEASE_ROOT="$repo" bash scripts/check-release.sh 2>&1); }

# Baseline must have no GPL-mention violations, otherwise they cannot be attributed to a probe.
base_out="$(run_check)"
if grep -q 'GPL mention outside allowlist' <<<"$base_out"; then
  fail "baseline copy already has GPL violations: $(grep 'GPL mention outside allowlist' <<<"$base_out" | head -n 2)"
fi

tokens=("GPL" "LGPL" "GNU General Public" "GPL-")
outside_dirs=("docs" "src" "PNTome/app" "PNTomeGo/source" "store/text" "." "PNTome/gradle" "scripts/x")
outside_names=("notes.md" "a.txt" "Foo.kt" "gradlew_notes.txt" "README.md" "x.sh" "gradle.properties.txt")
inside_files=(".kiro/specs/feat/design.md" ".kiro/specs/requirements.md" ".kiro/specs/a/b/c/tasks.md"
              "PNTome/gradlew" "PNTome/gradlew.bat")
prefixes=("" "License: " "see the " "(" "# ")
suffixes=("" " license" "." ", really" ")" " v3")

for (( i = 0; i < ITER; i++ )); do
  rand_int 0 3; tok=${tokens[$REPLY]}
  if [[ $tok == "GPL-" ]]; then rand_int 1 3; tok="GPL-$REPLY"; fi
  rand_int 0 4; pre=${prefixes[$REPLY]}
  rand_int 0 5; suf=${suffixes[$REPLY]}
  line="${pre}${tok}${suf}"

  rand_int 0 3; mode=$REPLY   # 0,1 outside file; 2 inside file; 3 CHANGELOG
  case $mode in
    0|1)
      rand_int 0 7; d=${outside_dirs[$REPLY]}
      rand_int 0 6; n=${outside_names[$REPLY]}
      rel="$d/probe$i-$n"; rel="${rel#./}"; expect=violation ;;
    2)
      rand_int 0 4; rel=${inside_files[$REPLY]}; expect=none ;;
    3)
      rel="CHANGELOG.md"
      rand_int 0 1
      if (( REPLY == 0 )); then
        line="- License changed from $tok to Apache-2.0"; expect=none
      else
        expect=violation
      fi ;;
  esac

  mkdir -p "$repo/$(dirname "$rel")"
  if [[ $rel == CHANGELOG.md ]]; then
    printf '%s\n' "$line" >>"$repo/$rel"
  else
    printf 'first line\n%s\nlast line\n' "$line" >"$repo/$rel"
  fi

  out="$(run_check)"; rc=$?
  hits="$(grep -F "[license] $rel:" <<<"$out" | grep -F 'GPL mention' || true)"

  if [[ $expect == violation ]]; then
    [[ -n $hits ]] || fail "file '$rel' with '$line' outside the allowlist: no violation naming it in: $out"
    (( rc == 1 )) || fail "file '$rel' with '$line': exit $rc, wanted 1"
  else
    [[ -z $hits ]] || fail "allowlisted '$rel' with '$line' was reported: $hits"
  fi

  if git -C "$repo" ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
    git -C "$repo" checkout -q -- "$rel" || fail "git checkout failed"
  else
    rm -f "$repo/$rel"
  fi
  pass "$expect for $rel ($line)"
done

finish
