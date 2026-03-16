#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
candidate_root="$(cd "$script_dir/../.." && pwd)"
if [[ -d "$candidate_root/javafx-client" ]]; then
  root="$candidate_root"
else
  root="$script_dir"
fi
frontend="$root/javafx-client"

if [[ ! -d "$frontend" ]]; then
  echo "[TM] javafx-client folder not found." >&2
  exit 1
fi

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/javac" ]]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi

  if command -v java >/dev/null 2>&1 && command -v javac >/dev/null 2>&1; then
    local java_path candidate
    java_path="$(command -v java)"
    candidate="$(cd "$(dirname "$java_path")/.." && pwd)"
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  return 1
}

resolve_maven_executable() {
  if command -v mvn >/dev/null 2>&1; then
    command -v mvn
    return 0
  fi

  local candidate
  for candidate in /usr/bin/mvn /usr/local/bin/mvn; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

if ! resolved_java_home="$(resolve_java_home)"; then
  cat >&2 <<'EOF'
[TM] Java JDK (21+) was not found.
Install a JDK and try again.
EOF
  exit 1
fi

if ! mvn_bin="$(resolve_maven_executable)"; then
  cat >&2 <<'EOF'
[TM] Maven (mvn) was not found in PATH.
Install Maven or use a Maven wrapper.
EOF
  exit 1
fi

export JAVA_HOME="$resolved_java_home"
export TM_PROJECT_ROOT="$root"
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

echo "[TM] JAVA_HOME=$JAVA_HOME"
echo "[TM] TM_PROJECT_ROOT=$TM_PROJECT_ROOT"
cd "$frontend"
exec "$mvn_bin" javafx:run
