# shellcheck shell=bash
# Version library: the only implementation of Version validation, Core_Version,
# Version_Code and comparison. Meant to be sourced (safe under set -euo pipefail).
#
# Grammar: MAJOR.MINOR.PATCH[-rc.N]
#   MAJOR 0|[1-9][0-9]* (<= 209), MINOR/PATCH same (<= 99), N [1-9][0-9]* (1..98)
#   No leading zeros, no +build, no other pre-release identifier.

_VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-rc\.([1-9][0-9]*))?$'

# valid_version <string>: exit 0 if the string is a valid Version.
valid_version() {
  [[ $# -eq 1 ]] || return 1
  local v=$1
  # Length guard keeps arithmetic below safe (no huge numbers).
  (( ${#v} <= 24 )) || return 1
  [[ $v =~ $_VERSION_RE ]] || return 1
  local major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]} n=${BASH_REMATCH[5]}
  # Each component has at most 10 digits here given the length guard; compare as base-10.
  (( 10#$major <= 209 && 10#$minor <= 99 && 10#$patch <= 99 )) || return 1
  if [[ -n $n ]]; then
    (( 10#$n >= 1 && 10#$n <= 98 )) || return 1
  fi
  return 0
}

# core_version <version>: print MAJOR.MINOR.PATCH.
core_version() {
  valid_version "${1-}" || return 1
  printf '%s\n' "${1%%-*}"
}

# is_prerelease <version>: exit 0 if the version has a -rc.N suffix.
is_prerelease() {
  valid_version "${1-}" || return 1
  [[ $1 == *-rc.* ]]
}

# version_code <version>: print the Android versionCode.
version_code() {
  valid_version "${1-}" || return 1
  local core=${1%%-*} n=99 major minor patch
  if [[ $1 == *-rc.* ]]; then n=${1##*-rc.}; fi
  IFS=. read -r major minor patch <<<"$core"
  printf '%d\n' $(( ((10#$major * 10000 + 10#$minor * 100 + 10#$patch) * 100) + 10#$n ))
}

# version_cmp <a> <b>: print -1, 0 or 1 by SemVer 2.0.0 precedence.
# A pre-release (rc.N) is lower than its stable version; rc.N compares numerically.
version_cmp() {
  valid_version "${1-}" || return 1
  valid_version "${2-}" || return 1
  local a=$1 b=$2 ca cb i
  local -a pa pb
  IFS=. read -r -a pa <<<"${a%%-*}"
  IFS=. read -r -a pb <<<"${b%%-*}"
  for i in 0 1 2; do
    if (( 10#${pa[i]} < 10#${pb[i]} )); then printf '%s\n' -1; return 0; fi
    if (( 10#${pa[i]} > 10#${pb[i]} )); then printf '%s\n' 1; return 0; fi
  done
  # Same core: stable ranks above any rc; rc ranks by N.
  ca=99; cb=99
  [[ $a == *-rc.* ]] && ca=${a##*-rc.}
  [[ $b == *-rc.* ]] && cb=${b##*-rc.}
  if (( 10#$ca < 10#$cb )); then printf '%s\n' -1
  elif (( 10#$ca > 10#$cb )); then printf '%s\n' 1
  else printf '%s\n' 0
  fi
  return 0
}
