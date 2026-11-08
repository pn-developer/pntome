#!/usr/bin/env bash
# scripts/test-set-version.sh -- tests for scripts/set-version.sh.
#
# 1. Runs every scripts/tests/prop-sv-*.sh that exists.
# 2. Example cases (each in a throwaway copy of the repo under $TMPDIR; the real tree is
#    never modified): changelog rotation in several shapes, empty Unreleased abort,
#    fastlane changelog at 500 and 501 characters, pattern mismatch (exit 2, tree
#    unchanged), no git commit or tag created.
# Needs bash, git, sha256sum, sed, grep.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$REPO/scripts"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/set-version-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
FAILED=()

ok()  { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); FAILED+=("$1"); echo "FAIL  $1${2:+ -- $2}"; }

# ---- 1. property tests -------------------------------------------------------------------------
shopt -s nullglob
props=("$HERE"/tests/prop-sv-*.sh)
shopt -u nullglob
if ((${#props[@]} == 0)); then
  bad "property tests" "no scripts/tests/prop-sv-*.sh found"
fi
for t in "${props[@]}"; do
  name="$(basename "$t")"
  echo "== $name"
  if bash "$t"; then ok "$name"; else bad "$name"; fi
done

# ---- 2. example cases --------------------------------------------------------------------------
N=0
D=""
# new_copy: fresh git repo with tracked + untracked-not-ignored files and VERSION; sets D.
new_copy() {
  N=$((N + 1))
  D="$TMP/case$N"
  mkdir -p "$D"
  local f
  while IFS= read -r -d '' f; do
    [[ -f "$REPO/$f" ]] || continue
    mkdir -p "$D/$(dirname "$f")"
    cp -p "$REPO/$f" "$D/$f"
  done < <(git -C "$REPO" ls-files -z --cached --others --exclude-standard)
  [[ -f "$REPO/VERSION" ]] && cp -p "$REPO/VERSION" "$D/VERSION"
  git -C "$D" init -q
  git -C "$D" add -A
  git -C "$D" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
    commit -q --no-verify -m "snapshot"
}

# tree_hash DIR: content hash of all files except .git
tree_hash() {
  (cd "$1" && find . -path ./.git -prune -o -type f -print0 | sort -z | xargs -0 sha256sum | sha256sum)
}

# git_state DIR: commit count and tag list
git_state() {
  echo "commits=$(git -C "$1" rev-list --all --count) tags=[$(git -C "$1" tag | tr '\n' ' ')]"
}

# run_sv DIR args... ; sets RC and OUT
run_sv() {
  local dir=$1; shift
  OUT=$(cd "$dir" && bash scripts/set-version.sh "$@" 2>&1)
  RC=$?
}

FASTLANE_DIR=PNTome/fastlane/metadata/android/en-US/changelogs
VER=1.2.3
CODE=1020399
TODAY_RE='[0-9]{4}-[0-9]{2}-[0-9]{2}'

# write_changelog FILE SHAPE
write_changelog() {
  local f=$1 shape=$2
  case $shape in
    links) # bullets under a subsection, trailing link references
      cat >"$f" <<'EOF'
# Changelog

## [Unreleased]

### Added

- Alpha feature
- Beta feature

## [0.1.0] - 2025-01-01

- First

[Unreleased]: https://example.invalid/compare/v0.1.0...HEAD
[0.1.0]: https://example.invalid/releases/tag/v0.1.0
EOF
      ;;
    plain) # bullets directly under the heading, no link references, ends at EOF of Unreleased
      cat >"$f" <<'EOF'
# Changelog

## [0.1.0] - 2025-01-01

- First

## [Unreleased]

- Only change
EOF
      ;;
    star) # '*' bullets, no trailing newline at end of file
      printf '# Changelog\n\n## [Unreleased]\n\n* Star bullet\n\n## [0.1.0] - 2025-01-01\n\n- First' >"$f"
      ;;
  esac
}

# rotation_case SHAPE EXPECT_BULLET
rotation_case() {
  local shape=$1 bullet=$2 name="changelog rotation ($1)"
  new_copy
  write_changelog "$D/CHANGELOG.md" "$shape"
  git -C "$D" add -A
  run_sv "$D" "$VER"
  if ((RC != 0)); then bad "$name" "exit $RC: $OUT"; return; fi
  local cl="$D/CHANGELOG.md" fl="$D/$FASTLANE_DIR/$CODE.txt"
  if ! grep -qxF '## [Unreleased]' "$cl"; then bad "$name" "no [Unreleased] heading"; return; fi
  if ! grep -qE "^## \[$VER\] - $TODAY_RE\$" "$cl"; then bad "$name" "no dated [$VER] heading"; return; fi
  # Unreleased is now empty, the bullet lives under the new version heading.
  local after_unrel between
  after_unrel=$(sed -n '/^## \[Unreleased\]$/,/^## \[1\.2\.3\]/p' "$cl")
  if grep -qF -- "$bullet" <<<"$after_unrel"; then bad "$name" "bullet still under Unreleased"; return; fi
  if ! sed -n "/^## \[$VER\]/,\$p" "$cl" | grep -qF -- "$bullet"; then bad "$name" "bullet not under [$VER]"; return; fi
  # Unreleased appears before the new version, exactly once each.
  [[ $(grep -c '^## \[Unreleased\]$' "$cl") == 1 ]] || { bad "$name" "[Unreleased] count != 1"; return; }
  [[ $(grep -c "^## \[$VER\]" "$cl") == 1 ]] || { bad "$name" "[$VER] count != 1"; return; }
  # Older sections survive.
  grep -qF '## [0.1.0] - 2025-01-01' "$cl" || { bad "$name" "0.1.0 section lost"; return; }
  if [[ $shape == links ]]; then
    grep -qF '[0.1.0]: https://example.invalid/releases/tag/v0.1.0' "$cl" || { bad "$name" "link reference lost"; return; }
  fi
  [[ -f $fl ]] || { bad "$name" "fastlane file $CODE.txt missing"; return; }
  grep -qF -- "$bullet" "$fl" || { bad "$name" "fastlane lacks bullet"; return; }
  ok "$name"
}

rotation_case links "Alpha feature"
rotation_case plain "Only change"
rotation_case star  "Star bullet"

# Empty Unreleased on a stable version: exit 1, nothing written.
new_copy
cat >"$D/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

### Added

## [0.1.0] - 2025-01-01

- First
EOF
git -C "$D" add -A
before=$(tree_hash "$D")
run_sv "$D" "$VER"
if ((RC == 1)) && [[ $(tree_hash "$D") == "$before" ]]; then
  ok "empty Unreleased aborts (exit 1, tree unchanged)"
else
  bad "empty Unreleased aborts" "exit $RC"
fi

# Pre-release with empty Unreleased is allowed (no rotation).
new_copy
cat >"$D/CHANGELOG.md" <<'EOF'
# Changelog

## [Unreleased]

## [0.1.0] - 2025-01-01

- First
EOF
git -C "$D" add -A
before_cl=$(sha256sum "$D/CHANGELOG.md")
run_sv "$D" "1.2.3-rc.1"
if ((RC == 0)) && [[ $(sha256sum "$D/CHANGELOG.md") == "$before_cl" ]] && [[ $(<"$D/VERSION") == "1.2.3-rc.1" ]]; then
  ok "pre-release with empty Unreleased leaves CHANGELOG untouched"
else
  bad "pre-release with empty Unreleased" "exit $RC: $OUT"
fi

# fastlane length boundary: bullet "- " + text; the file holds the bullet line.
fastlane_case() {
  local len=$1 expect=$2 name="fastlane changelog at $1 characters"
  new_copy
  local text
  text=$(printf 'a%.0s' $(seq 1 $((len - 2))))
  printf '# Changelog\n\n## [Unreleased]\n\n- %s\n\n## [0.1.0] - 2025-01-01\n\n- First\n' "$text" >"$D/CHANGELOG.md"
  git -C "$D" add -A
  run_sv "$D" "$VER"
  local fl="$D/$FASTLANE_DIR/$CODE.txt"
  if ((RC != 0)); then bad "$name" "exit $RC: $OUT"; return; fi
  if [[ $expect == written ]]; then
    if [[ -f $fl ]] && [[ $(wc -m <"$fl" | tr -d ' ') -eq $((len + 1)) ]]; then ok "$name (written)"
    else bad "$name" "expected a file of $len characters"; fi
  else
    if [[ ! -f $fl ]] && grep -q 'NOT written' <<<"$OUT"; then ok "$name (not written, message printed)"
    else bad "$name" "expected no file and a message"; fi
  fi
}
fastlane_case 500 written
fastlane_case 501 skipped

# Pattern mismatch: exit 2, tree unchanged. One case per carrier that must match exactly once.
mismatch_case() {
  local name=$1 file=$2 expr=$3
  new_copy
  sed -i -E "$expr" "$D/$file"
  git -C "$D" add -A
  local before; before=$(tree_hash "$D")
  run_sv "$D" "$VER"
  if ((RC == 2)) && [[ $(tree_hash "$D") == "$before" ]]; then ok "pattern mismatch: $name (exit 2, tree unchanged)"
  else bad "pattern mismatch: $name" "exit $RC"; fi
}
mismatch_case "versionCode missing" PNTome/app/build.gradle.kts 's/versionCode/versionCodeX/'
mismatch_case "SEMVER missing"      PNTomeGo/source/Version.mc   's/SEMVER/SEMVERX/'
mismatch_case "manifest version"    PNTomeGo/manifest.xml        's/^([[:space:]]+)version=/\1ver=/'
mismatch_case "F-Droid pattern"     fdroid/metadata/dev.pntome.app.yml 's/^CurrentVersionCode:/CurrentVC:/'

# Invalid version and downgrade refusals.
new_copy
before=$(tree_hash "$D")
run_sv "$D" "01.2.3"
if ((RC == 1)) && [[ $(tree_hash "$D") == "$before" ]]; then ok "invalid version refused (exit 1)"; else bad "invalid version refused" "exit $RC"; fi

# No commit and no tag after a successful run.
new_copy
write_changelog "$D/CHANGELOG.md" links
git -C "$D" add -A
git -C "$D" -c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false \
  commit -q --no-verify -m "changelog shape"
state_before=$(git_state "$D")
head_before=$(git -C "$D" rev-parse HEAD)
run_sv "$D" "$VER"
if ((RC == 0)) && [[ $(git_state "$D") == "$state_before" ]] && [[ $(git -C "$D" rev-parse HEAD) == "$head_before" ]]; then
  ok "no git commit or tag created"
else
  bad "no git commit or tag created" "exit $RC; $(git_state "$D")"
fi

# --dry-run writes nothing.
new_copy
write_changelog "$D/CHANGELOG.md" links
git -C "$D" add -A
before=$(tree_hash "$D")
run_sv "$D" "$VER" --dry-run
if ((RC == 0)) && [[ $(tree_hash "$D") == "$before" ]]; then ok "--dry-run writes nothing"; else bad "--dry-run writes nothing" "exit $RC"; fi

echo
echo "set-version tests: $PASS passed, $FAIL failed"
if ((FAIL)); then
  printf '  failed: %s\n' "${FAILED[@]}"
  exit 1
fi
