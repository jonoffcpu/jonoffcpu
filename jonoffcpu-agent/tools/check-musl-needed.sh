#!/bin/sh
# SPDX-License-Identifier: MIT
# Usage: check-musl-needed <library> [allowed extra NEEDED entry...]
#
# Fails unless the library links only against musl (plus the listed bundle
# siblings) and carries no glibc symbol versions.
set -eu

library=$1
shift
needed=$(readelf -d "$library" | awk '/\(NEEDED\)/ { gsub(/[][]/, "", $NF); print $NF }')
status=0
for entry in $needed; do
  case "$entry" in
    libc.musl-*.so.1|ld-musl-*.so.1|libc.so) continue ;;
  esac
  allowed=false
  for sibling in "$@"; do
    [ "$entry" = "$sibling" ] && allowed=true
  done
  if [ "$allowed" = false ]; then
    echo "$library must not depend on $entry" >&2
    status=1
  fi
done
if readelf -V "$library" | grep -q 'GLIBC_'; then
  echo "$library carries glibc symbol versions" >&2
  status=1
fi
[ "$status" -eq 0 ] || exit "$status"
echo "$library NEEDED: ${needed:-<none>}"
