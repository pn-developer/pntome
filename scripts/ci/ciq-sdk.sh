#!/usr/bin/env bash
# ciq-sdk.sh — locate the Connect IQ SDK on a runner.
#
# Same discovery order as PNTomeGo/build.sh:
#   1. $CIQ_SDK
#   2. the directory named in ~/.Garmin/ConnectIQ/current-sdk.cfg
#   3. the newest ~/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-*
# On success prints the SDK directory on stdout and, when $GITHUB_ENV is set,
# appends "CIQ_SDK=<dir>" to it. On failure explains what was searched and the
# available options, and exits 1.
set -euo pipefail

home_dir=${HOME:-}
cfg="$home_dir/.Garmin/ConnectIQ/current-sdk.cfg"
sdks_glob="$home_dir/.Garmin/ConnectIQ/Sdks/connectiq-sdk-lin-*"

sdk=""
if [[ -n ${CIQ_SDK:-} ]]; then
  sdk=${CIQ_SDK%/}
elif [[ -f $cfg ]]; then
  p=$(tr -d '[:space:]' <"$cfg")
  if [[ -n $p && -d $p ]]; then
    sdk=${p%/}
  fi
fi
if [[ -z $sdk ]]; then
  # shellcheck disable=SC2086  # the glob is intentional
  sdk=$(ls -d $sdks_glob 2>/dev/null | LC_ALL=C sort | tail -1 || true)
  sdk=${sdk%/}
fi

fail_explain() {
  {
    echo "ciq-sdk.sh: $1"
    echo "Searched:"
    echo "  - \$CIQ_SDK (${CIQ_SDK:-not set})"
    echo "  - $cfg"
    echo "  - $sdks_glob"
    echo "Options (details in docs/RELEASING.md):"
    echo "  A. Run the watch job on a self-hosted runner that has the SDK installed"
    echo "     (set repository variable CIQ_RUNS_ON to its label)."
    echo "  B. Download the SDK on the runner. Not implemented: Garmin terms and"
    echo "     authentication for automated downloads are unverified."
    echo "  C. Do not build the watch app in CI: unset the repository variable CIQ_BUILD"
    echo "     and attach the .iq to the release manually."
  } >&2
  exit 1
}

[[ -n $sdk && -d $sdk ]] || fail_explain "Connect IQ SDK not found."
[[ -x $sdk/bin/monkeyc ]] || fail_explain "monkeyc is not executable under $sdk/bin."

echo "ciq-sdk.sh: using SDK $sdk" >&2
if [[ -n ${GITHUB_ENV:-} ]]; then
  echo "CIQ_SDK=$sdk" >>"$GITHUB_ENV"
fi
printf '%s\n' "$sdk"
