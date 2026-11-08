#!/usr/bin/env bash
# scripts/test-ci-scripts.sh -- tests for scripts/ci/*, make-checksums.sh, release-notes.sh
# and the PNTomeGo/build.sh commit stamping.
#
# 1. Runs every scripts/tests/prop-ci-*.sh.
# 2. Example cases. Everything runs in throwaway directories under $TMPDIR; the real
#    repository is never modified and no commit, tag or push is made in it.
#      - publish.sh with a fake `gh` on PATH (flag matrix)
#      - ciq-sdk.sh discovery with a fake HOME
#      - prepare-ciq-key.sh (empty secret, file mode 600)
#      - collect-watch.sh (copy and rename, stamp refusal)
#      - build.sh in a shallow clone (stamped commit) and the dirty-tree --export refusal,
#        using a stub monkeyc
# Needs bash, git, sha256sum, base64, stat.
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HERE="$REPO/scripts"
TMP="$(mktemp -d "${TMPDIR:-/tmp}/ci-scripts-test.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT

PASS=0
FAIL=0
FAILED=()
ok()  { PASS=$((PASS + 1)); echo "PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); FAILED+=("$1"); echo "FAIL  $1${2:+ -- $2}"; }

# ---- 1. property tests -------------------------------------------------------------------------
shopt -s nullglob
props=("$HERE"/tests/prop-ci-*.sh)
shopt -u nullglob
if ((${#props[@]} == 0)); then
  bad "property tests" "no scripts/tests/prop-ci-*.sh found"
fi
for t in "${props[@]}"; do
  name="$(basename "$t")"
  echo "== $name"
  if bash "$t"; then ok "$name"; else bad "$name"; fi
done

# ---- 2. publish.sh with a fake gh --------------------------------------------------------------
FAKEBIN="$TMP/fakebin"
mkdir -p "$FAKEBIN"
cat >"$FAKEBIN/gh" <<'EOF'
#!/usr/bin/env bash
# Fake gh: logs "create" invocations; "release view" succeeds only when FAKE_GH_EXISTS=1.
if [[ ${1-} == release && ${2-} == view ]]; then
  [[ ${FAKE_GH_EXISTS:-0} == 1 ]] && exit 0
  exit 1
fi
if [[ ${1-} == release && ${2-} == create ]]; then
  printf '%s\n' "$@" >"$FAKE_GH_LOG"
  exit 0
fi
echo "fake gh: unexpected: $*" >&2
exit 99
EOF
chmod +x "$FAKEBIN/gh"

CL="$TMP/CHANGELOG.md"
cat >"$CL" <<'EOF'
# Changelog

## [Unreleased]

- Upcoming thing

## [1.2.3] - 2025-01-01

- Stable thing
EOF

# publish_case NAME VERSION SIGNED(true|false) WATCH(true|false) EXISTS(0|1) EXPECT_RC FLAGS_PRESENT FLAGS_ABSENT
publish_case() {
  local name=$1 version=$2 signed=$3 watch=$4 exists=$5 expect_rc=$6 present=$7 absent=$8
  local d="$TMP/pub-$RANDOM$RANDOM" suffix=unsigned
  [[ $signed == true ]] && suffix=signed
  mkdir -p "$d/out"
  : >"$d/out/PNTome-$version-abc1234-$suffix.apk"
  : >"$d/out/PNTome-$version-abc1234-$suffix.aab"
  [[ $watch == true ]] && : >"$d/out/PNTomeGo-$version-abc1234.iq"
  local log="$d/gh.log" out rc
  out=$(PATH="$FAKEBIN:$PATH" FAKE_GH_LOG="$log" FAKE_GH_EXISTS="$exists" CHANGELOG_FILE="$CL" \
        VERSION="$version" COMMIT=abc1234 COMMIT_FULL=abc1234def5678abc1234def5678abc1234def56 \
        OUT_DIR="$d/out" bash "$HERE/ci/publish.sh" 2>&1)
  rc=$?
  if ((rc != expect_rc)); then bad "publish.sh: $name" "exit $rc, expected $expect_rc: $out"; return; fi
  if ((expect_rc != 0)); then
    [[ ! -e $log ]] || { bad "publish.sh: $name" "gh release create was called"; return; }
    ok "publish.sh: $name"
    return
  fi
  [[ -f $log ]] || { bad "publish.sh: $name" "gh release create not called"; return; }
  local f
  for f in --verify-tag $present; do
    grep -qxF -- "$f" "$log" || { bad "publish.sh: $name" "missing $f"; return; }
  done
  for f in $absent; do
    ! grep -qxF -- "$f" "$log" || { bad "publish.sh: $name" "unexpected $f"; return; }
  done
  grep -qxF "v$version" "$log" || { bad "publish.sh: $name" "tag v$version not passed"; return; }
  [[ -f $d/out/SHA256SUMS ]] || { bad "publish.sh: $name" "SHA256SUMS not created"; return; }
  grep -qxF "$d/out/SHA256SUMS" "$log" || { bad "publish.sh: $name" "SHA256SUMS not attached"; return; }
  if [[ $watch == true ]]; then
    grep -qxF "$d/out/PNTomeGo-$version-abc1234.iq" "$log" || { bad "publish.sh: $name" ".iq not attached"; return; }
  fi
  ok "publish.sh: $name"
}
publish_case "stable signed"            1.2.3      true  true  0 0 ""             "--draft --prerelease"
publish_case "stable unsigned -> draft" 1.2.3      false true  0 0 "--draft"      "--prerelease"
publish_case "rc signed -> prerelease"  1.2.3-rc.1 true  false 0 0 "--prerelease" "--draft"
publish_case "rc unsigned"              1.2.3-rc.1 false false 0 0 "--prerelease" "--draft"
publish_case "release already exists"   1.2.3      true  true  1 1 ""             ""

# ---- 3. ciq-sdk.sh discovery -------------------------------------------------------------------
mk_sdk() { # <dir>
  mkdir -p "$1/bin"
  printf '#!/bin/sh\nexit 0\n' >"$1/bin/monkeyc"
  chmod +x "$1/bin/monkeyc"
}
run_sdk() { # <home> [CIQ_SDK]; sets OUT, RC, GHENV
  local home=$1 envsdk=${2-}
  GHENV="$home/github_env"
  : >"$GHENV"
  if [[ -n $envsdk ]]; then
    OUT=$(env -u CIQ_SDK HOME="$home" CIQ_SDK="$envsdk" GITHUB_ENV="$GHENV" bash "$HERE/ci/ciq-sdk.sh" 2>"$home/stderr")
  else
    OUT=$(env -u CIQ_SDK HOME="$home" GITHUB_ENV="$GHENV" bash "$HERE/ci/ciq-sdk.sh" 2>"$home/stderr")
  fi
  RC=$?
}

H="$TMP/home-env"; mkdir -p "$H"; mk_sdk "$TMP/envsdk"
run_sdk "$H" "$TMP/envsdk"
if ((RC == 0)) && [[ $OUT == "$TMP/envsdk" ]] && grep -qxF "CIQ_SDK=$TMP/envsdk" "$GHENV"; then
  ok "ciq-sdk.sh: CIQ_SDK env"
else bad "ciq-sdk.sh: CIQ_SDK env" "rc=$RC out=$OUT"; fi

H="$TMP/home-cfg"; mkdir -p "$H/.Garmin/ConnectIQ"; mk_sdk "$TMP/cfgsdk"
mk_sdk "$H/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-9.9.9"
printf '%s\n' "$TMP/cfgsdk" >"$H/.Garmin/ConnectIQ/current-sdk.cfg"
run_sdk "$H"
if ((RC == 0)) && [[ $OUT == "$TMP/cfgsdk" ]] && grep -qxF "CIQ_SDK=$TMP/cfgsdk" "$GHENV"; then
  ok "ciq-sdk.sh: cfg file wins over Sdks dir"
else bad "ciq-sdk.sh: cfg file" "rc=$RC out=$OUT"; fi

H="$TMP/home-newest"
mk_sdk "$H/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-4.1.0-2022"
mk_sdk "$H/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-7.2.0-2024"
mk_sdk "$H/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-6.0.0-2023"
run_sdk "$H"
if ((RC == 0)) && [[ $OUT == "$H/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-7.2.0-2024" ]]; then
  ok "ciq-sdk.sh: newest Sdks dir"
else bad "ciq-sdk.sh: newest Sdks dir" "rc=$RC out=$OUT"; fi

H="$TMP/home-none"; mkdir -p "$H"
run_sdk "$H"
if ((RC == 1)) && [[ -z $OUT ]] && grep -q 'Searched' "$H/stderr" && grep -q 'docs/RELEASING.md' "$H/stderr" \
   && [[ ! -s $GHENV ]]; then
  ok "ciq-sdk.sh: none found -> exit 1 with explanation"
else bad "ciq-sdk.sh: none found" "rc=$RC out=$OUT"; fi

# ---- 4. prepare-ciq-key.sh ---------------------------------------------------------------------
d="$TMP/key-empty"; mkdir -p "$d"
out=$(env DK_B64="" RUNNER_TEMP="$d" GITHUB_ENV="$d/env" bash "$HERE/ci/prepare-ciq-key.sh" 2>&1); rc=$?
if ((rc == 1)) && [[ ! -e $d/developer_key.der && ! -e $d/env ]]; then ok "prepare-ciq-key.sh: empty secret fails, nothing written"
else bad "prepare-ciq-key.sh: empty secret" "rc=$rc"; fi

d="$TMP/key-ok"; mkdir -p "$d"
sentinel="KEYSENTINEL-$RANDOM-$RANDOM"
b64=$(printf '%s' "$sentinel" | base64 -w0)
out=$(env DK_B64="$b64" RUNNER_TEMP="$d" GITHUB_ENV="$d/env" bash "$HERE/ci/prepare-ciq-key.sh" 2>&1); rc=$?
mode=$(stat -c %a "$d/developer_key.der" 2>/dev/null || echo none)
if ((rc == 0)) && [[ $mode == 600 ]] && [[ $(<"$d/developer_key.der") == "$sentinel" ]] \
   && grep -qxF "CIQ_DEVKEY=$d/developer_key.der" "$d/env" \
   && ! grep -qF -e "$sentinel" -e "$b64" <<<"$out"; then
  ok "prepare-ciq-key.sh: key written with mode 600, secret not printed"
else bad "prepare-ciq-key.sh: key file" "rc=$rc mode=$mode"; fi

# ---- 5. collect-watch.sh -----------------------------------------------------------------------
d="$TMP/cw"; mkdir -p "$d/PNTomeGo/dist"
printf 'iq-bytes' >"$d/PNTomeGo/dist/PNTomeGo-1.2.3-rc.2.iq"
printf 'noise\nbuild.sh: version 1.2.3-rc.2 (abc1234)\nmore\n' >"$d/build.log"
out=$(REPO_ROOT="$d" VERSION=1.2.3-rc.2 COMMIT=abc1234 bash "$HERE/ci/collect-watch.sh" "$d/build.log" 2>&1); rc=$?
if ((rc == 0)) && [[ $(<"$d/out/watch/PNTomeGo-1.2.3-rc.2-abc1234.iq") == iq-bytes ]]; then
  ok "collect-watch.sh: copies and renames the .iq"
else bad "collect-watch.sh: copy and rename" "rc=$rc: $out"; fi

printf 'build.sh: version 1.2.3-rc.2 (abc1234-dirty)\n' >"$d/dirty.log"
rm -rf "$d/out"
out=$(REPO_ROOT="$d" VERSION=1.2.3-rc.2 COMMIT=abc1234 bash "$HERE/ci/collect-watch.sh" "$d/dirty.log" 2>&1); rc=$?
if ((rc == 1)) && [[ ! -e $d/out ]]; then ok "collect-watch.sh: -dirty stamp refused, nothing copied"
else bad "collect-watch.sh: dirty stamp" "rc=$rc"; fi

out=$(bash "$HERE/ci/collect-watch.sh" --check-only 1.2.3-rc.2 abc1234 "$d/build.log" 2>&1); rc=$?
((rc == 0)) && ok "collect-watch.sh: --check-only accepts a matching stamp" || bad "collect-watch.sh --check-only" "rc=$rc"

# ---- 6. build.sh: shallow clone and dirty-tree export -----------------------------------------
STUB_SDK="$TMP/stubsdk"
mkdir -p "$STUB_SDK/bin"
cat >"$STUB_SDK/bin/monkeyc" <<'EOF'
#!/usr/bin/env bash
out=""
while (( $# )); do
  if [[ $1 == -o ]]; then out=$2; shift; fi
  shift
done
[[ -n $out ]] || { echo "stub monkeyc: no -o" >&2; exit 1; }
mkdir -p "$(dirname "$out")"
: >"$out"
EOF
chmod +x "$STUB_SDK/bin/monkeyc"
printf 'dummy' >"$TMP/dummy_key.der"

ORIGIN="$TMP/origin"
mkdir -p "$ORIGIN"
while IFS= read -r -d '' f; do
  [[ -f "$REPO/$f" ]] || continue
  mkdir -p "$ORIGIN/$(dirname "$f")"
  cp -p "$REPO/$f" "$ORIGIN/$f"
done < <(git -C "$REPO" ls-files -z --cached --others --exclude-standard -- PNTomeGo)
GIT_ID=(-c user.name=test -c user.email=test@example.invalid -c commit.gpgsign=false)
git -C "$ORIGIN" init -q
git -C "$ORIGIN" add -A
git -C "$ORIGIN" "${GIT_ID[@]}" commit -q --no-verify -m "first"
printf '\n# second\n' >>"$ORIGIN/PNTomeGo/INSTALL.md"
git -C "$ORIGIN" "${GIT_ID[@]}" commit -q --no-verify -am "second"

SHALLOW="$TMP/shallow"
git clone -q --depth 1 "file://$ORIGIN" "$SHALLOW" 2>/dev/null
expect_commit=$(git -C "$SHALLOW" rev-parse --short HEAD)
[[ $(git -C "$SHALLOW" rev-parse --is-shallow-repository) == true ]] || bad "shallow clone" "clone is not shallow"

out=$(cd "$SHALLOW/PNTomeGo" && CIQ_SDK="$STUB_SDK" CIQ_DEVKEY="$TMP/dummy_key.der" ./build.sh 2>&1); rc=$?
stamp=$(sed -n 's/^build\.sh: version [^ ]* (\(.*\))$/\1/p' <<<"$out")
if ((rc == 0)) && [[ $stamp == "$expect_commit" ]]; then
  ok "build.sh: shallow clone stamps $(git -C "$SHALLOW" rev-parse --short HEAD) (no -dirty)"
else bad "build.sh: shallow clone stamp" "rc=$rc stamp='$stamp' expected '$expect_commit': $out"; fi
[[ -z $(git -C "$SHALLOW" status --porcelain) ]] && ok "build.sh: working tree clean after build (Version.mc restored)" \
  || bad "build.sh: tree dirty after build" "$(git -C "$SHALLOW" status --porcelain)"

out=$(cd "$SHALLOW/PNTomeGo" && CIQ_SDK="$STUB_SDK" CIQ_DEVKEY="$TMP/dummy_key.der" ./build.sh --export 2>&1); rc=$?
semver=$(sed -n 's/.*const SEMVER = "\([^"]*\)".*/\1/p' "$SHALLOW/PNTomeGo/source/Version.mc" | head -1)
stamp=$(sed -n 's/^build\.sh: version [^ ]* (\(.*\))$/\1/p' <<<"$out")
if ((rc == 0)) && [[ -f $SHALLOW/PNTomeGo/dist/PNTomeGo-$semver.iq ]] && [[ $stamp == "$expect_commit" ]]; then
  ok "build.sh --export: shallow clone exports dist/PNTomeGo-$semver.iq with clean stamp"
else bad "build.sh --export in shallow clone" "rc=$rc stamp='$stamp': $out"; fi

# Dirty tree: --export is refused and builds nothing.
DIRTY="$TMP/dirty"
git clone -q "file://$ORIGIN" "$DIRTY" 2>/dev/null
printf '\n# local edit\n' >>"$DIRTY/PNTomeGo/INSTALL.md"
out=$(cd "$DIRTY/PNTomeGo" && CIQ_SDK="$STUB_SDK" CIQ_DEVKEY="$TMP/dummy_key.der" ./build.sh --export 2>&1); rc=$?
if ((rc != 0)) && grep -q 'uncommitted changes' <<<"$out" && [[ ! -e $DIRTY/PNTomeGo/dist ]]; then
  ok "build.sh --export: dirty tree refused, nothing built"
else bad "build.sh --export dirty tree" "rc=$rc: $out"; fi

# A non-export build in the same dirty tree is allowed and stamped -dirty.
printf '\n// edit\n' >>"$DIRTY/PNTomeGo/source/Version.mc"
out=$(cd "$DIRTY/PNTomeGo" && CIQ_SDK="$STUB_SDK" CIQ_DEVKEY="$TMP/dummy_key.der" ./build.sh 2>&1); rc=$?
stamp=$(sed -n 's/^build\.sh: version [^ ]* (\(.*\))$/\1/p' <<<"$out")
if ((rc == 0)) && [[ $stamp == *-dirty ]]; then ok "build.sh: dirty non-export build is stamped -dirty"
else bad "build.sh dirty non-export stamp" "rc=$rc stamp='$stamp'"; fi

echo
echo "ci script tests: $PASS passed, $FAIL failed"
if ((FAIL)); then
  printf '  failed: %s\n' "${FAILED[@]}"
  exit 1
fi
