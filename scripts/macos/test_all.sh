#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
TIMESTAMP="$(date '+%Y%m%d-%H%M%S')"
ARTIFACT_ROOT="${ROOT}/artifacts/test_all/macos/${TIMESTAMP}"
APP_DATA_DIR="${ARTIFACT_ROOT}/app-data"
FIXTURES_DIR="${ROOT}/tests/test_files"
MANIFEST_PATH="${FIXTURES_DIR}/manifest.json"
REPORT_FILE="${ARTIFACT_ROOT}/summary.txt"
HEARTBEAT_SECS="${TM_FULL_HEARTBEAT_SECS:-15}"
BOX_WIDTH=78
VERBOSE=0
FAILURE_LOG_LINES=40
USE_COLOR=1
COLOR_RESET=""
COLOR_BOLD=""
COLOR_DIM=""
COLOR_BLUE=""
COLOR_CYAN=""
COLOR_GREEN=""
COLOR_YELLOW=""
COLOR_RED=""

declare -a STAGE_SUMMARY=()
declare -a SPINNER_FRAMES=('-' '\\' '|' '/')
STAGE_COUNTER=0
STAGE_TOTAL=8
CURRENT_STAGE=""

if [[ ! -x "${ROOT}/.venv/bin/python" && ! -x "${ROOT}/.venv/bin/python3" ]]; then
  STAGE_TOTAL=9
fi

tm_log() {
  printf "%s[TM-FULL]%s %s\n" "${COLOR_DIM}" "${COLOR_RESET}" "$*"
}

usage() {
  cat <<'EOF'
Usage: ./scripts/macos/test_all.sh [--verbose] [--no-color] [--help]
       Compatibility wrapper: ./scripts/test_all_macos.sh

Options:
  -v, --verbose   Stream detailed stage logs live in the terminal.
      --no-color  Disable ANSI colors for this run.
  -h, --help      Show this help message.

Default behavior:
  Runs in quiet mode with colored stage boxes, heartbeat updates, and a concise summary.
  Detailed command output stays in per-stage log files unless --verbose is used.
EOF
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -v|--verbose)
        VERBOSE=1
        shift
        ;;
      --no-color)
        USE_COLOR=0
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "[TM-FULL] Unknown option: $1" >&2
        usage >&2
        exit 2
        ;;
    esac
  done
}

init_colors() {
  if (( ! USE_COLOR )) || [[ -n "${NO_COLOR:-}" ]] || [[ "${TERM:-}" == "dumb" ]]; then
    return 0
  fi

  COLOR_RESET=$'\033[0m'
  COLOR_BOLD=$'\033[1m'
  COLOR_DIM=$'\033[2m'
  COLOR_BLUE=$'\033[34m'
  COLOR_CYAN=$'\033[36m'
  COLOR_GREEN=$'\033[32m'
  COLOR_YELLOW=$'\033[33m'
  COLOR_RED=$'\033[31m'
}

repeat_char() {
  local char="$1"
  local count="$2"
  local out=""
  while (( count > 0 )); do
    out="${out}${char}"
    count=$((count - 1))
  done
  printf '%s' "$out"
}

box_rule() {
  local color="${1:-}"
  printf '%s+%s+%s\n' \
    "${color}" \
    "$(repeat_char "-" $((BOX_WIDTH - 2)))" \
    "${COLOR_RESET}"
}

box_text() {
  local text="$1"
  local color="${2:-}"
  printf '%s| %-*.*s |%s\n' \
    "${color}" \
    $((BOX_WIDTH - 4)) $((BOX_WIDTH - 4)) "${text}" \
    "${COLOR_RESET}"
}

print_box() {
  local color="$1"
  shift
  box_rule "${color}"
  for line in "$@"; do
    box_text "$line" "${color}"
  done
  box_rule "${color}"
}

human_duration() {
  local total="$1"
  local hours minutes seconds
  hours=$((total / 3600))
  minutes=$(((total % 3600) / 60))
  seconds=$((total % 60))
  if (( hours > 0 )); then
    printf '%02dh %02dm %02ds' "$hours" "$minutes" "$seconds"
  elif (( minutes > 0 )); then
    printf '%02dm %02ds' "$minutes" "$seconds"
  else
    printf '%02ds' "$seconds"
  fi
}

stage_title() {
  case "$1" in
    prepare_venv) printf 'Prepare Python Virtualenv' ;;
    environment_report) printf 'Environment Report' ;;
    sync_python_deps) printf 'Upgrade pip' ;;
    install_project_deps) printf 'Install Python Dependencies' ;;
    bootstrap_runtime_ffmpeg) printf 'Bootstrap Runtime FFmpeg' ;;
    pytest_fast) printf 'Backend Pytest Suite' ;;
    fixture_regression) printf 'Fixture Regression' ;;
    frontend_tests) printf 'Frontend Maven Tests' ;;
    macos_release_build) printf 'macOS Release Build' ;;
    verify_build_artifacts) printf 'Verify Release Artifacts' ;;
    *) printf '%s' "$1" ;;
  esac
}

stage_description() {
  case "$1" in
    prepare_venv) printf 'Creating the repository .venv because it is missing.' ;;
    environment_report) printf 'Printing the exact toolchain and machine details used by this run.' ;;
    sync_python_deps) printf 'Making sure pip is current before dependency sync.' ;;
    install_project_deps) printf 'Installing runtime and development Python dependencies into .venv.' ;;
    bootstrap_runtime_ffmpeg) printf 'Preparing isolated runtime assets and pinned FFmpeg under test app-data.' ;;
    pytest_fast) printf 'Running the fast backend/unit test suite.' ;;
    fixture_regression) printf 'Running real transcription, summary and FFmpeg regression over tests/test_files fixtures.' ;;
    frontend_tests) printf 'Running frontend Maven tests without GUI click automation.' ;;
    macos_release_build) printf 'Building the macOS .app and release ZIP.' ;;
    verify_build_artifacts) printf 'Checking the built .app, ZIP and checksum file.' ;;
    *) printf '' ;;
  esac
}

print_banner() {
  printf '\n'
  print_box "${COLOR_BOLD}${COLOR_BLUE}" \
    "TranscribeMate macOS full-suite" \
    "Artifacts: ${ARTIFACT_ROOT}" \
    "Mode: $( (( VERBOSE )) && printf 'verbose (live logs enabled)' || printf 'quiet (logs only on failure)' )" \
    "Heartbeat: every ${HEARTBEAT_SECS}s for quiet stages"
  printf '\n'
}

print_stage_header() {
  local name="$1"
  local logfile="$2"
  local title description
  title="$(stage_title "$name")"
  description="$(stage_description "$name")"
  printf '\n'
  if [[ -n "$description" ]]; then
    print_box "${COLOR_BOLD}${COLOR_BLUE}" \
      "[${STAGE_COUNTER}/${STAGE_TOTAL}] ${title}" \
      "${description}" \
      "log: ${logfile}"
  else
    print_box "${COLOR_BOLD}${COLOR_BLUE}" \
      "[${STAGE_COUNTER}/${STAGE_TOTAL}] ${title}" \
      "log: ${logfile}"
  fi
}

latest_tm_progress_hint() {
  local logfile="$1"
  if [[ ! -f "$logfile" ]]; then
    return 0
  fi
  local last_line pct message
  last_line="$(awk -F'|' '/^TM_PROGRESS\|[0-9]+\|/ { line=$0 } END { print line }' "$logfile")"
  if [[ -z "$last_line" ]]; then
    return 0
  fi
  pct="$(printf '%s\n' "$last_line" | awk -F'|' '{print $2}')"
  message="$(printf '%s\n' "$last_line" | awk -F'|' '{print $3}')"
  if [[ -n "$pct" || -n "$message" ]]; then
    printf 'progress %s%% - %s' "${pct:-?}" "${message:-working}"
  fi
}

fixture_progress_hint() {
  local artifact_dir="$1"
  local progress_file=""
  if [[ -f "${artifact_dir}/video-progress.json" ]]; then
    progress_file="${artifact_dir}/video-progress.json"
  elif [[ -f "${artifact_dir}/offline-progress.json" ]]; then
    progress_file="${artifact_dir}/offline-progress.json"
  fi
  if [[ -z "$progress_file" ]]; then
    return 0
  fi

  local step overall item_index total_items stage_label
  stage_label="$(basename "$progress_file" .json)"
  step="$(sed -n 's/.*"step"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$progress_file" | head -n 1)"
  overall="$(sed -n 's/.*"overall_pct"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' "$progress_file" | head -n 1)"
  item_index="$(sed -n 's/.*"item_index"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$progress_file" | head -n 1)"
  total_items="$(sed -n 's/.*"total_items"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$progress_file" | head -n 1)"

  printf '%s' "${stage_label}"
  if [[ -n "$step" ]]; then
    printf ' | step=%s' "$step"
  fi
  if [[ -n "$overall" ]]; then
    printf ' | overall=%s%%' "$overall"
  fi
  if [[ -n "$item_index" && -n "$total_items" ]]; then
    printf ' | item=%s/%s' "$item_index" "$total_items"
  fi
}

emit_stage_heartbeat() {
  local name="$1"
  local logfile="$2"
  local started="$3"
  local spinner_index=0
  local last_size=-1
  local current_size=0
  local hint=""
  local activity=""

  while true; do
    sleep "${HEARTBEAT_SECS}"
    if [[ -z "${CURRENT_STAGE}" || "${CURRENT_STAGE}" != "${name}" ]]; then
      return 0
    fi

    if [[ -f "$logfile" ]]; then
      current_size="$(stat -f '%z' "$logfile" 2>/dev/null || printf '0')"
    else
      current_size=0
    fi

    if [[ "$current_size" != "$last_size" ]]; then
      activity="streaming output"
      last_size="$current_size"
    else
      activity="waiting for next output"
    fi

    hint=""
    if [[ "$name" == "fixture_regression" ]]; then
      hint="$(fixture_progress_hint "${ARTIFACT_ROOT}/fixture_regression")"
    fi
    if [[ -z "$hint" ]]; then
      hint="$(latest_tm_progress_hint "$logfile")"
    fi

    printf '%s[TM-FULL]%s %s %s still running after %s%s%s' \
      "${COLOR_DIM}" "${COLOR_RESET}" \
      "${SPINNER_FRAMES[$((spinner_index % ${#SPINNER_FRAMES[@]}))]}" \
      "$name" \
      "${COLOR_BOLD}" "$(human_duration "$(( $(date +%s) - started ))")" "${COLOR_RESET}"
    if [[ -n "$hint" ]]; then
      printf ' | %s' "$hint"
    else
      printf ' | %s' "$activity"
    fi
    printf '\n'

    spinner_index=$((spinner_index + 1))
  done
}

on_interrupt() {
  printf '\n%s[TM-FULL]%s Interrupted during stage: %s%s%s\n' \
    "${COLOR_RED}" "${COLOR_RESET}" "${COLOR_BOLD}" "${CURRENT_STAGE:-startup}" "${COLOR_RESET}" >&2
  print_summary
  exit 130
}

trap on_interrupt INT TERM

print_summary() {
  {
    printf '+%s+\n' "$(repeat_char "-" $((BOX_WIDTH - 2)))"
    printf '| %-*s |\n' $((BOX_WIDTH - 4)) "TranscribeMate macOS full-suite summary"
    printf '| %-*s |\n' $((BOX_WIDTH - 4)) "Timestamp: ${TIMESTAMP}"
    printf '| %-*s |\n' $((BOX_WIDTH - 4)) "Artifacts: ${ARTIFACT_ROOT}"
    printf '+%s+\n' "$(repeat_char "-" $((BOX_WIDTH - 2)))"
    for line in "${STAGE_SUMMARY[@]:-}"; do
      echo "${line}"
    done
  } | tee "${REPORT_FILE}"
}

print_log_excerpt() {
  local logfile="$1"
  if [[ ! -f "$logfile" ]]; then
    return 0
  fi

  printf '\n%s' "${COLOR_YELLOW}"
  print_box "${COLOR_YELLOW}" \
    "Failure excerpt from: ${logfile}" \
    "Showing last ${FAILURE_LOG_LINES} lines. Re-run with --verbose for live detail."
  printf '%s' "${COLOR_RESET}"
  tail -n "${FAILURE_LOG_LINES}" "$logfile" || true
  printf '\n'
}

record_stage() {
  local name="$1"
  local status="$2"
  local duration="$3"
  STAGE_SUMMARY+=(" - ${name}: ${status} ($(human_duration "$duration"))")
}

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || {
    echo "[TM-FULL] Required command not found: ${name}" >&2
    exit 1
  }
}

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/javac" && -x "${JAVA_HOME}/bin/jpackage" ]]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi

  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    local detected
    detected="$(/usr/libexec/java_home -v 21+ 2>/dev/null || true)"
    if [[ -n "$detected" && -x "$detected/bin/java" && -x "$detected/bin/javac" && -x "$detected/bin/jpackage" ]]; then
      printf '%s\n' "$detected"
      return 0
    fi
  fi

  if command -v java >/dev/null 2>&1; then
    local java_path candidate
    java_path="$(command -v java)"
    candidate="$(cd "$(dirname "$java_path")/.." && pwd)"
    if [[ -x "$candidate/bin/java" && -x "$candidate/bin/javac" && -x "$candidate/bin/jpackage" ]]; then
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
  for candidate in /opt/homebrew/bin/mvn /usr/local/bin/mvn; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

resolve_base_python() {
  local candidate
  for candidate in python3.12 python3 python; do
    if ! command -v "$candidate" >/dev/null 2>&1; then
      continue
    fi
    if "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info >= (3, 12) else 1)' >/dev/null 2>&1; then
      command -v "$candidate"
      return 0
    fi
  done
  return 1
}

run_stage() {
  local name="$1"
  local workdir="$2"
  local logfile="$3"
  shift 3

  mkdir -p "$(dirname "$logfile")"
  : >"$logfile"

  STAGE_COUNTER=$((STAGE_COUNTER + 1))
  CURRENT_STAGE="$name"

  local started ended duration status cmd_pid heartbeat_pid tail_pid=""
  started="$(date +%s)"
  print_stage_header "$name" "$logfile"

  set +e
  (
    cd "$workdir"
    "$@"
  ) >>"$logfile" 2>&1 &
  cmd_pid=$!
  if (( VERBOSE )); then
    tail -n +1 -f "$logfile" &
    tail_pid=$!
  fi
  emit_stage_heartbeat "$name" "$logfile" "$started" &
  heartbeat_pid=$!

  wait "$cmd_pid"
  status=$?
  set -e

  if [[ -n "$tail_pid" ]]; then
    kill "$tail_pid" >/dev/null 2>&1 || true
    wait "$tail_pid" >/dev/null 2>&1 || true
  fi
  kill "$heartbeat_pid" >/dev/null 2>&1 || true
  wait "$heartbeat_pid" >/dev/null 2>&1 || true

  ended="$(date +%s)"
  duration=$((ended - started))
  CURRENT_STAGE=""

  if [[ "$status" -ne 0 ]]; then
    record_stage "$name" "FAILED" "$duration"
    printf '%s[TM-FULL]%s %sFAIL%s %s after %s (see %s)\n' \
      "${COLOR_RED}" "${COLOR_RESET}" "${COLOR_BOLD}" "${COLOR_RESET}" \
      "$name" "$(human_duration "$duration")" "$logfile" >&2
    if (( ! VERBOSE )); then
      print_log_excerpt "$logfile"
    fi
    print_summary
    exit "$status"
  fi

  record_stage "$name" "OK" "$duration"
  printf '%s[TM-FULL]%s %sOK%s %s finished in %s\n' \
    "${COLOR_GREEN}" "${COLOR_RESET}" "${COLOR_BOLD}" "${COLOR_RESET}" \
    "$name" "$(human_duration "$duration")"
}

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "[TM-FULL] This runner is macOS-only." >&2
  exit 1
fi

parse_args "$@"
init_colors

mkdir -p "${ARTIFACT_ROOT}"
print_banner

JAVA_HOME_RESOLVED="$(resolve_java_home || true)"
if [[ -z "${JAVA_HOME_RESOLVED}" ]]; then
  echo "[TM-FULL] Java JDK 21+ with jpackage was not found." >&2
  exit 1
fi
MVN_BIN="$(resolve_maven_executable || true)"
if [[ -z "${MVN_BIN}" ]]; then
  echo "[TM-FULL] Maven was not found." >&2
  exit 1
fi
BASE_PYTHON="$(resolve_base_python || true)"
if [[ -z "${BASE_PYTHON}" ]]; then
  echo "[TM-FULL] Python 3.12+ was not found." >&2
  exit 1
fi

export JAVA_HOME="${JAVA_HOME_RESOLVED}"
export TM_PROJECT_ROOT="${ROOT}"
export TM_APP_DATA_DIR="${APP_DATA_DIR}"
export PYTHONUTF8=1
export PYTHONIOENCODING=utf-8

require_command curl
require_command ditto
require_command find
require_command shasum
require_command unzip

if [[ ! -x "${ROOT}/.venv/bin/python" && ! -x "${ROOT}/.venv/bin/python3" ]]; then
  run_stage "prepare_venv" "${ROOT}" "${ARTIFACT_ROOT}/01_prepare_venv.log" \
    "${BASE_PYTHON}" -m venv "${ROOT}/.venv"
fi

PYTHON_BIN="${ROOT}/.venv/bin/python3"
if [[ ! -x "${PYTHON_BIN}" ]]; then
  PYTHON_BIN="${ROOT}/.venv/bin/python"
fi
if [[ ! -x "${PYTHON_BIN}" ]]; then
  echo "[TM-FULL] Repository .venv python was not found after setup." >&2
  exit 1
fi

run_stage "environment_report" "${ROOT}" "${ARTIFACT_ROOT}/00_environment.log" \
  bash -lc '
    echo "repo_root=${TM_PROJECT_ROOT}"
    echo "artifact_root='"${ARTIFACT_ROOT}"'"
    echo "app_data_dir=${TM_APP_DATA_DIR}"
    echo "java_home=${JAVA_HOME}"
    uname -a
    sw_vers
    "'"${PYTHON_BIN}"'" --version
    "'"${MVN_BIN}"'" -v
    java -version
    javac -version
    jpackage --version
  '

run_stage "sync_python_deps" "${ROOT}" "${ARTIFACT_ROOT}/02_sync_python_deps.log" \
  "${PYTHON_BIN}" -m pip install --upgrade pip

run_stage "install_project_deps" "${ROOT}" "${ARTIFACT_ROOT}/03_install_project_deps.log" \
  "${PYTHON_BIN}" -m pip install -r requirements.txt -r requirements-dev.txt

run_stage "bootstrap_runtime_ffmpeg" "${ROOT}" "${ARTIFACT_ROOT}/04_bootstrap_runtime.log" \
  "${ROOT}/scripts/macos/bootstrap_runtime.sh" --download-ffmpeg --backend-root "${ROOT}" --log-file "${ARTIFACT_ROOT}/runtime-bootstrap.log"

run_stage "pytest_fast" "${ROOT}" "${ARTIFACT_ROOT}/05_pytest_fast.log" \
  "${PYTHON_BIN}" -m pytest -q

run_stage "fixture_regression" "${ROOT}" "${ARTIFACT_ROOT}/06_fixture_regression.log" \
  "${PYTHON_BIN}" tests/full_suite/run_fixture_regression.py \
    --fixtures-dir "${FIXTURES_DIR}" \
    --manifest "${MANIFEST_PATH}" \
    --artifact-dir "${ARTIFACT_ROOT}/fixture_regression" \
    --app-data-dir "${APP_DATA_DIR}"

run_stage "frontend_tests" "${ROOT}/javafx-client" "${ARTIFACT_ROOT}/07_frontend_tests.log" \
  "${MVN_BIN}" -B test

run_stage "macos_release_build" "${ROOT}" "${ARTIFACT_ROOT}/08_macos_release_build.log" \
  "${ROOT}/scripts/macos/build_v2_release.sh"

run_stage "verify_build_artifacts" "${ROOT}" "${ARTIFACT_ROOT}/09_verify_build_artifacts.log" \
  bash -lc '
    set -euo pipefail
    version="$(tr -d "\r\n" < version.txt)"
    zip_path="dist/macos/TranscribeMate-${version}-macos-arm64.zip"
    checksum_path="dist/macos/checksums.txt"
    app_path="dist/macos/TranscribeMate.app"
    [[ -d "${app_path}" ]]
    [[ -f "${zip_path}" ]]
    [[ -f "${checksum_path}" ]]
    actual="$(shasum -a 256 "${zip_path}" | awk "{print \$1}")"
    expected="$(awk "{print \$1}" "${checksum_path}" | head -n 1)"
    [[ -n "${expected}" ]]
    [[ "${actual}" == "${expected}" ]]
    echo "version=${version}"
    echo "zip_path=${zip_path}"
    echo "checksum=${actual}"
  '

print_summary
echo "[TM-FULL] Full macOS suite completed successfully."
