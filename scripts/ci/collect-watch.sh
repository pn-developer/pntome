#!/usr/bin/env bash
# collect-watch.sh — assert the commit stamped into the watch build, then collect the .iq.
#
# Usage:
#   collect-watch.sh [logfile]
#       Collect mode. Needs VERSION and COMMIT in the environment. logfile defaults to
#       $RUNNER_TEMP/build.log (the captured `build.sh --export` output). Asserts the stamp,
#       then copies PNTomeGo/dist/PNTomeGo-<VERSION>.iq to
#       out/watch/PNTomeGo-<VERSION>-<COMMIT>.iq.
#   collect-watch.sh --check-only <version> <commit> <logfile>
#       Only asserts the stamp; copies nothing.
#
# The stamp lines are "build.sh: version <SEMVER> (<COMMIT>)". At least one must exist and
# every one must equal the expected version and commit. A commit of "dev", a "-dirty"
# suffix or a different commit fails.
#
# Environment: REPO_ROOT (default: two levels above this script).
set -euo pipefail

check_stamp() { # <version> <commit> <logfile>
  local version=$1 commit=$2 log=$3
  [[ -f $log ]] || { echo "collect-watch.sh: build log not found: $log" >&2; return 1; }
  local re='^build\.sh: version ([^ ]+) \(([^)]*)\)[[:space:]]*$'
  local line n=0 bad=0 v c
  while IFS= read -r line || [[ -n $line ]]; do
    if [[ $line =~ $re ]]; then
      n=$((n + 1))
      v=${BASH_REMATCH[1]}
      c=${BASH_REMATCH[2]}
      if [[ $c == dev || $c == *-dirty ]]; then
        echo "collect-watch.sh: build is not stamped with a clean commit: version $v ($c)" >&2
        bad=1
      elif [[ $v != "$version" || $c != "$commit" ]]; then
        echo "collect-watch.sh: stamp mismatch: build says '$v ($c)', expected '$version ($commit)'" >&2
        bad=1
      fi
    fi
  done <"$log"
  if [[ $n -eq 0 ]]; then
    echo "collect-watch.sh: no 'build.sh: version <SEMVER> (<COMMIT>)' line in $log" >&2
    return 1
  fi
  [[ $bad -eq 0 ]] || return 1
  echo "collect-watch.sh: stamp OK: version $version ($commit)"
}

if [[ ${1-} == --check-only ]]; then
  if [[ $# -ne 4 ]]; then
    echo "usage: $0 --check-only <version> <commit> <logfile>" >&2
    exit 1
  fi
  check_stamp "$2" "$3" "$4"
  exit 0
fi

if [[ $# -gt 1 ]]; then
  echo "usage: $0 [logfile] | --check-only <version> <commit> <logfile>" >&2
  exit 1
fi
version=${VERSION:-}
commit=${COMMIT:-}
if [[ -z $version || -z $commit ]]; then
  echo "collect-watch.sh: VERSION and COMMIT must be set" >&2
  exit 1
fi
log=${1:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/build.log}

check_stamp "$version" "$commit" "$log"

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
root=${REPO_ROOT:-$(cd -- "$script_dir/../.." && pwd)}
src="$root/PNTomeGo/dist/PNTomeGo-$version.iq"
dest="$root/out/watch"
if [[ ! -f $src ]]; then
  echo "collect-watch.sh: exported package not found: $src" >&2
  exit 1
fi
mkdir -p -- "$dest"
cp -- "$src" "$dest/PNTomeGo-$version-$commit.iq"
echo "collect-watch.sh: wrote PNTomeGo-$version-$commit.iq to $dest"
