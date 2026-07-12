#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

services=(
  java-api
  java-web
  py-worker-backend
  py-worker-web
)

actions=(
  start
  stop
  restart
)

failed=0

for service in "${services[@]}"; do
  for action in "${actions[@]}"; do
    script="${ROOT_DIR}/${service}-${action}.sh"
    if [ ! -f "${script}" ]; then
      printf 'missing script: %s\n' "${script}" >&2
      failed=1
      continue
    fi
    if [ ! -x "${script}" ]; then
      printf 'script is not executable: %s\n' "${script}" >&2
      failed=1
    fi
    if ! bash -n "${script}"; then
      printf 'script has invalid bash syntax: %s\n' "${script}" >&2
      failed=1
    fi
    if grep -Eq '"\$@"|\$\*' "${script}"; then
      printf 'script must not pass caller arguments through: %s\n' "${script}" >&2
      failed=1
    fi
  done
done

exit "${failed}"
