#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 0 ]; then
  printf 'Usage: ./java-api-restart.sh\n' >&2
  exit 64
fi

cd "$(dirname "$0")"
exec ./deploy/meeting-api-control.sh restart compose
