#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "$(uname -s)" in
  Darwin)
    TARGET="${SCRIPT_DIR}/macos/bootstrap_runtime.sh"
    ;;
  Linux)
    TARGET="${SCRIPT_DIR}/linux/bootstrap_runtime.sh"
    ;;
  *)
    echo "[TM] Unsupported host OS for bootstrap_runtime.sh: $(uname -s)" >&2
    exit 1
    ;;
esac

if [[ ! -f "${TARGET}" ]]; then
  echo "[TM] Platform bootstrap script is not available yet: ${TARGET}" >&2
  exit 1
fi

exec bash "${TARGET}" "$@"
