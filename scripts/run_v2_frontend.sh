#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "$(uname -s)" in
  Darwin)
    TARGET="${SCRIPT_DIR}/macos/run_v2_frontend.sh"
    ;;
  Linux)
    TARGET="${SCRIPT_DIR}/linux/run_v2_frontend.sh"
    ;;
  *)
    echo "[TM] Unsupported host OS for run_v2_frontend.sh: $(uname -s)" >&2
    exit 1
    ;;
esac

if [[ ! -f "${TARGET}" ]]; then
  echo "[TM] Platform frontend run script is not available yet: ${TARGET}" >&2
  exit 1
fi

exec bash "${TARGET}" "$@"
