#!/usr/bin/env bash
# Property 22: Watch version guard uses the core version
# For every valid Version used as SEMVER, the build.sh version guard passes if and only if
# core_version of SEMVER equals the manifest application version.
#
# build.sh runs only inside a throwaway copy of PNTomeGo/ under a temp dir, with a stub
# `monkeyc` (creates the -o output file, exits 0) found through CIQ_SDK and a dummy key.
# The real repository is never modified.
#
# **Validates: Requirements 14.6**
set -uo pipefail

# shellcheck source=lib.sh
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SRC="$REPO_ROOT/PNTomeGo"
for f in build.sh monkey.jungle manifest.xml source/Version.mc; do
  [[ -f $SRC/$f ]] || fail "PNTomeGo/$f missing"
done

# Stub SDK + dummy key (shared by all cases).
STUB_SDK="$TEST_TMP/sdk"
mkdir -p "$STUB_SDK/bin"
cat >"$STUB_SDK/bin/monkeyc" <<'EOF'
#!/usr/bin/env bash
# Stub monkeyc: create the file given with -o and succeed.
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
DUMMY_KEY="$TEST_TMP/dummy_key.der"
printf 'not-a-real-key' >"$DUMMY_KEY"

# manifest_version <semver> -> REPLY: a manifest version that differs from the core.
other_core() {
  local core=$1 ma mi pa
  IFS=. read -r ma mi pa <<<"$core"
  rand_int 0 2
  case $REPLY in
    0) ma=$(( ma == 209 ? 208 : ma + 1 )) ;;
    1) mi=$(( mi == 99 ? 98 : mi + 1 )) ;;
    *) pa=$(( pa == 99 ? 98 : pa + 1 )) ;;
  esac
  REPLY="$ma.$mi.$pa"
}

for (( i = 0; i < ITER; i++ )); do
  gen_version any
  v=$REPLY
  core="${v%%-*}"

  # Choose the manifest version: matching, or one of several mismatches.
  rand_int 0 3
  kind=$REPLY
  case $kind in
    0|1) mver=$core; expect=pass ;;
    2) other_core "$core"; mver=$REPLY; expect=fail ;;
    3)
      if [[ $v == *-rc.* ]]; then
        mver=$v            # manifest must carry the core only, not the rc suffix
      else
        other_core "$core"; mver=$REPLY
      fi
      expect=fail ;;
  esac

  work="$(mktemp -d "$TEST_TMP/case.XXXXXX")"
  mkdir -p "$work/PNTomeGo/source"
  cp -p "$SRC/build.sh" "$SRC/monkey.jungle" "$work/PNTomeGo/"
  cp -p "$SRC/manifest.xml" "$work/PNTomeGo/manifest.xml"
  cp -p "$SRC/source/Version.mc" "$work/PNTomeGo/source/Version.mc"
  sed -i -E "s/const SEMVER = \"[^\"]*\";/const SEMVER = \"$v\";/" "$work/PNTomeGo/source/Version.mc"
  sed -i -E "s/^([[:space:]]*version=\")[0-9]+\.[0-9][^\"]*(\")/\1$mver\2/" "$work/PNTomeGo/manifest.xml"
  grep -q "version=\"$mver\"" "$work/PNTomeGo/manifest.xml" || fail "could not set manifest version $mver"
  grep -q "const SEMVER = \"$v\";" "$work/PNTomeGo/source/Version.mc" || fail "could not set SEMVER $v"
  cp -p "$work/PNTomeGo/source/Version.mc" "$work/Version.mc.before"

  out="$(CIQ_SDK="$STUB_SDK" CIQ_DEVKEY="$DUMMY_KEY" "$work/PNTomeGo/build.sh" 2>&1)"
  rc=$?
  built=0
  [[ -f $work/PNTomeGo/build/PNTomeGo-fenix6spro.prg ]] && built=1

  if [[ $expect == pass ]]; then
    (( rc == 0 )) || fail "SEMVER=$v manifest=$mver: expected pass, rc=$rc: $out"
    (( built == 1 )) || fail "SEMVER=$v manifest=$mver: passed but no output built"
    grep -q "^build.sh: version $v (" <<<"$out" || fail "SEMVER=$v: missing 'version $v (' line: $out"
    cmp -s "$work/Version.mc.before" "$work/PNTomeGo/source/Version.mc" \
      || fail "SEMVER=$v: Version.mc not restored after build"
    pass "guard passes SEMVER=$v manifest=$mver"
  else
    (( rc != 0 )) || fail "SEMVER=$v manifest=$mver: expected guard failure, got rc=0"
    (( built == 0 )) || fail "SEMVER=$v manifest=$mver: guard failed but monkeyc ran"
    grep -q "version mismatch" <<<"$out" || fail "SEMVER=$v manifest=$mver: no mismatch message: $out"
    pass "guard rejects SEMVER=$v manifest=$mver"
  fi
  rm -rf "$work"
done

finish
