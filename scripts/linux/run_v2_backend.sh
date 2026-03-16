#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
candidate_root="$(cd "$script_dir/../.." && pwd)"
if [[ -d "$candidate_root/transcribemate" ]]; then
  root="$candidate_root"
else
  root="$script_dir"
fi

python_bin="$root/.venv/bin/python3"
if [[ ! -x "$python_bin" ]]; then
  python_bin="$root/.venv/bin/python"
fi
if [[ ! -x "$python_bin" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    python_bin="python3"
  else
    python_bin="python"
  fi
fi

export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

echo "[TM] Backend command: $python_bin -m transcribemate.v2.backend.server --stdio"
cd "$root"
exec "$python_bin" -m transcribemate.v2.backend.server --stdio
