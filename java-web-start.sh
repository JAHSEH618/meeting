#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 0 ]; then
  printf 'Usage: ./java-web-start.sh\n' >&2
  exit 64
fi

cd "$(dirname "$0")"
exec ./start-web.sh
