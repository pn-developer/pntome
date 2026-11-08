#!/usr/bin/env bash
# Property 17: Android artifact naming.
# For generated VERSION/COMMIT and a fake PNTome/app/build/outputs tree, collect-android.sh
# writes exactly PNTome-<VERSION>-<COMMIT>-(signed|unsigned).(apk|aab) to out/android/, with the
# content of the Gradle outputs, and nothing else. A missing or ambiguous input fails.
# Validates: Requirements 10.5
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

SCRIPT="$REPO_ROOT/scripts/ci/collect-android.sh"
[[ -f $SCRIPT ]] || fail "missing $SCRIPT"

HEX=0123456789abcdef
gen_commit() { # sets REPLY to 7..12 hex characters
  local len i c=""
  rand_int 7 12; len=$REPLY
  for (( i = 0; i < len; i++ )); do
    rand_int 0 15
    c+=${HEX:REPLY:1}
  done
  REPLY=$c
}

# Fake apksigner: succeeds and prints a digest line; the digest is passed via FAKE_DIGEST.
FAKEBIN="$TEST_TMP/bin"
mkdir -p "$FAKEBIN"
cat > "$FAKEBIN/apksigner" <<'EOF'
#!/usr/bin/env bash
echo "Verifies"
echo "Signer #1 certificate DN: CN=Test"
echo "Signer #1 certificate SHA-256 digest: ${FAKE_DIGEST:-00}"
EOF
chmod +x "$FAKEBIN/apksigner"

for (( i = 1; i <= ITER; i++ )); do
  gen_version any; version=$REPLY
  gen_commit; commit=$REPLY
  rand_int 0 1; signed=true; (( REPLY == 0 )) && signed=false
  suffix=unsigned; [[ $signed == true ]] && suffix=signed

  root="$TEST_TMP/case$i"
  apk_dir="$root/PNTome/app/build/outputs/apk/release"
  aab_dir="$root/PNTome/app/build/outputs/bundle/release"
  mkdir -p "$apk_dir" "$aab_dir"
  # Gradle-style source names, unrelated to the final names; extra non-matching files are noise.
  rand_int 0 1
  if (( REPLY == 0 )); then apk_src="$apk_dir/app-release.apk"; else apk_src="$apk_dir/app-release-unsigned.apk"; fi
  aab_src="$aab_dir/app-release.aab"
  printf 'apk-%s-%s-%s\n' "$i" "$version" "$commit" > "$apk_src"
  printf 'aab-%s-%s-%s\n' "$i" "$version" "$commit" > "$aab_src"
  echo '{}' > "$apk_dir/output-metadata.json"
  echo 'x' > "$apk_dir/baselineProfiles.txt"

  out="$TEST_TMP/out$i.txt"
  if ! VERSION=$version COMMIT=$commit REPO_ROOT=$root APKSIGNER="$FAKEBIN/apksigner" \
       FAKE_DIGEST=ab$i bash "$SCRIPT" "$signed" >"$out" 2>&1; then
    cat "$out" >&2
    fail "case $i: script failed for version=$version commit=$commit signed=$signed"
  fi

  dest="$root/out/android"
  want_apk="PNTome-$version-$commit-$suffix.apk"
  want_aab="PNTome-$version-$commit-$suffix.aab"
  [[ -f $dest/$want_apk ]] || fail "case $i: missing $want_apk"
  [[ -f $dest/$want_aab ]] || fail "case $i: missing $want_aab"
  cmp -s "$apk_src" "$dest/$want_apk" || fail "case $i: apk content differs"
  cmp -s "$aab_src" "$dest/$want_aab" || fail "case $i: aab content differs"
  count=$(find "$dest" -mindepth 1 | wc -l)
  (( count == 2 )) || fail "case $i: expected exactly 2 files in out/android, found $count"
  # No name carries the opposite suffix.
  other=signed; [[ $suffix == signed ]] && other=unsigned
  if compgen -G "$dest/*-$other.*" >/dev/null; then fail "case $i: file with $other suffix present"; fi
  pass "case $i: $want_apk"
  rm -rf "$root"
done

# Failure modes: no APK, two APKs, no AAB, missing VERSION/COMMIT, bad argument.
root="$TEST_TMP/neg"
mk() { rm -rf "$root"; mkdir -p "$root/PNTome/app/build/outputs/apk/release" "$root/PNTome/app/build/outputs/bundle/release"; }
run() { VERSION=${V-1.2.3} COMMIT=${C-abcdef0} REPO_ROOT=$root APKSIGNER="$FAKEBIN/apksigner" bash "$SCRIPT" "$@" >/dev/null 2>&1; }

mk; : > "$root/PNTome/app/build/outputs/bundle/release/a.aab"
run false && fail "no apk must fail"
mk; : > "$root/PNTome/app/build/outputs/apk/release/a.apk"
run false && fail "no aab must fail"
mk
: > "$root/PNTome/app/build/outputs/apk/release/a.apk"; : > "$root/PNTome/app/build/outputs/apk/release/b.apk"
: > "$root/PNTome/app/build/outputs/bundle/release/a.aab"
run false && fail "two apks must fail"
mk
: > "$root/PNTome/app/build/outputs/apk/release/a.apk"; : > "$root/PNTome/app/build/outputs/bundle/release/a.aab"
V= run false && fail "empty VERSION must fail"
C= run false && fail "empty COMMIT must fail"
run maybe && fail "bad argument must fail"
run false || fail "valid minimal tree must succeed"
pass "failure modes"

finish
