#!/usr/bin/env bash
# scripts/test-version-lib.sh -- runs every scripts/tests/prop-vl-*.sh (version library
# property tests). Exits non-zero if any of them fails. Extra environment (SEED, ITER)
# is passed through to the tests.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
shopt -s nullglob
tests=("$HERE"/tests/prop-vl-*.sh)

if ((${#tests[@]} == 0)); then
  echo "test-version-lib: no scripts/tests/prop-vl-*.sh found" >&2
  exit 1
fi

failed=()
for t in "${tests[@]}"; do
  name="$(basename "$t")"
  echo "== $name"
  if bash "$t"; then
    echo "PASS  $name"
  else
    echo "FAIL  $name"
    failed+=("$name")
  fi
done

echo
echo "version library tests: $((${#tests[@]} - ${#failed[@]})) passed, ${#failed[@]} failed"
if ((${#failed[@]})); then
  printf '  failed: %s\n' "${failed[@]}"
  exit 1
fi
