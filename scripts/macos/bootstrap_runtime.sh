#!/usr/bin/env bash

set -euo pipefail

WITH_MODELS=0
INSTALL_DIARIZATION=0
DOWNLOAD_FFMPEG=0
BACKEND_ROOT=""
PROGRESS_FILE=""
LOG_FILE=""
DEV_BOOTSTRAP_MODE=0

DEV_FFMPEG_ZIP_URL="https://ffmpeg.martin-riedl.de/download/macos/arm64/1766430132_8.0.1/ffmpeg.zip"
DEV_FFMPEG_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/macos/arm64/1766430132_8.0.1/ffmpeg.zip.sha256"
DEV_FFPROBE_ZIP_URL="https://ffmpeg.martin-riedl.de/download/macos/arm64/1766430132_8.0.1/ffprobe.zip"
DEV_FFPROBE_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/macos/arm64/1766430132_8.0.1/ffprobe.zip.sha256"

show_help() {
  cat <<'EOF'
TranscribeMate macOS runtime bootstrap

Usage:
  ./scripts/macos/bootstrap_runtime.sh [options]

Options:
  --with-models
  --install-diarization
  --download-ffmpeg
  --backend-root <path>
  --progress-file <path>
  --log-file <path>
  --help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-models)
      WITH_MODELS=1
      shift
      ;;
    --install-diarization)
      INSTALL_DIARIZATION=1
      shift
      ;;
    --download-ffmpeg)
      DOWNLOAD_FFMPEG=1
      shift
      ;;
    --backend-root)
      BACKEND_ROOT="${2:-}"
      shift 2
      ;;
    --progress-file)
      PROGRESS_FILE="${2:-}"
      shift 2
      ;;
    --log-file)
      LOG_FILE="${2:-}"
      shift 2
      ;;
    --help|-h)
      show_help
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
detect_default_backend_root() {
  local repo_root
  repo_root="$(cd "$SCRIPT_DIR/../.." && pwd)"
  if [[ -d "${SCRIPT_DIR}/transcribemate" ]]; then
    printf '%s\n' "$SCRIPT_DIR"
  elif [[ -d "${repo_root}/transcribemate" ]]; then
    printf '%s\n' "$repo_root"
  else
    printf '%s\n' "$SCRIPT_DIR"
  fi
}

if [[ -z "$BACKEND_ROOT" ]]; then
  BACKEND_ROOT="$(detect_default_backend_root)"
fi
BACKEND_ROOT="$(cd "$BACKEND_ROOT" && pwd)"

APP_NAME="TranscribeMate"
APP_DATA_DIR="${TM_APP_DATA_DIR:-${HOME}/Library/Application Support/${APP_NAME}}"
RUNTIME_ROOT="${APP_DATA_DIR}/runtime"
PYTHON_ROOT="${RUNTIME_ROOT}/python"
ASSETS_DIR="${APP_DATA_DIR}/assets"
WHISPER_MODEL_DIR="${APP_DATA_DIR}/cache/whisper/models/large-v3"
TRANSLATION_MODEL_DIR="${APP_DATA_DIR}/cache/huggingface/hub/models--Helsinki-NLP--opus-mt-en-cs"
RUNTIME_READY_MARKER="${RUNTIME_ROOT}/runtime-ready.json"
MANIFEST_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/asset-manifest.env"
TMP_ROOT=""

if [[ -z "$LOG_FILE" ]]; then
  LOG_FILE="${APP_DATA_DIR}/runtime-bootstrap.log"
fi

timestamp_utc() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

log() {
  local level="$1"
  shift
  local message="$*"
  mkdir -p "$(dirname "$LOG_FILE")"
  printf '[%s] [%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$level" "$message" | tee -a "$LOG_FILE"
}

emit_progress() {
  local percent="$1"
  local message="$2"
  if [[ -n "$PROGRESS_FILE" ]]; then
    mkdir -p "$(dirname "$PROGRESS_FILE")"
    printf '%s|%s\n' "$percent" "$message" >"$PROGRESS_FILE"
  fi
  printf 'TM_PROGRESS|%s|%s\n' "$percent" "$message"
}

fail() {
  log "ERROR" "$*"
  exit 1
}

require_file() {
  local path="$1"
  local description="$2"
  [[ -f "$path" ]] || fail "Missing ${description}: ${path}"
}

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || fail "Required command not found: ${name}"
}

download_file() {
  local url="$1"
  local destination="$2"
  mkdir -p "$(dirname "$destination")"
  curl -fsSL "$url" -o "$destination"
}

sha256_file() {
  shasum -a 256 "$1" | awk '{print $1}'
}

run_with_retry() {
  local retries="$1"
  local description="$2"
  shift 2
  local attempt=1
  while true; do
    if "$@"; then
      return 0
    fi
    if [[ "$attempt" -ge "$retries" ]]; then
      fail "${description} failed after ${attempt} attempts."
    fi
    log "WARN" "${description} failed on attempt ${attempt}; retrying."
    attempt=$((attempt + 1))
    sleep 2
  done
}

cleanup() {
  if [[ -n "$TMP_ROOT" && -d "$TMP_ROOT" ]]; then
    rm -rf "$TMP_ROOT"
  fi
}
trap cleanup EXIT

find_python_executable() {
  local search_root="$1"
  local candidate
  candidate="$(find "$search_root" -type f -path '*/bin/*' \( -name 'python3' -o -name 'python3.*' -o -name 'python' \) | head -n 1 || true)"
  if [[ -z "$candidate" ]]; then
    return 1
  fi
  printf '%s\n' "$candidate"
}

resolve_system_tool_path() {
  local name="$1"
  local candidate=""
  if command -v "$name" >/dev/null 2>&1; then
    candidate="$(command -v "$name")"
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  fi

  for candidate in "/opt/homebrew/bin/${name}" "/usr/local/bin/${name}" "/opt/local/bin/${name}"; do
    if [[ -f "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

ensure_dev_python() {
  emit_progress 5 "Preparing development Python runtime."

  local candidate=""
  for candidate in \
    "${BACKEND_ROOT}/.venv/bin/python3" \
    "${BACKEND_ROOT}/.venv/bin/python" \
    "/Library/Frameworks/Python.framework/Versions/3.12/bin/python3.12"
  do
    if [[ -x "$candidate" ]]; then
      RUNTIME_PYTHON="$candidate"
      log "INFO" "Using development Python interpreter: ${RUNTIME_PYTHON}"
      return 0
    fi
  done

  if command -v python3.12 >/dev/null 2>&1; then
    RUNTIME_PYTHON="$(command -v python3.12)"
    log "INFO" "Using development Python interpreter from PATH: ${RUNTIME_PYTHON}"
    return 0
  fi

  fail "No suitable development Python interpreter was found. Create .venv or install Python 3.12."
}

ensure_python_runtime() {
  emit_progress 5 "Preparing managed Python runtime."
  log "INFO" "Installing managed Python runtime from bundled assets."

  require_file "$PYTHON_ARCHIVE_PATH" "bundled Python archive"

  local extract_dir
  extract_dir="${TMP_ROOT}/python-extract"
  rm -rf "$extract_dir" "$PYTHON_ROOT"
  mkdir -p "$extract_dir" "$PYTHON_ROOT"

  local actual_sha
  actual_sha="$(sha256_file "$PYTHON_ARCHIVE_PATH")"
  if [[ "$actual_sha" != "$PYTHON_ARCHIVE_SHA256" ]]; then
    fail "Bundled Python archive SHA256 mismatch. expected=${PYTHON_ARCHIVE_SHA256} actual=${actual_sha}"
  fi

  tar -xzf "$PYTHON_ARCHIVE_PATH" -C "$extract_dir"

  local extracted_python
  extracted_python="$(find_python_executable "$extract_dir" || true)"
  [[ -n "$extracted_python" ]] || fail "Could not find Python executable in extracted archive."

  local prefix_dir
  prefix_dir="$(cd "$(dirname "$extracted_python")/.." && pwd)"
  cp -R "$prefix_dir"/. "$PYTHON_ROOT"/

  local installed_python
  installed_python="$(find_python_executable "$PYTHON_ROOT" || true)"
  [[ -n "$installed_python" ]] || fail "Managed runtime Python executable was not installed."

  mkdir -p "${PYTHON_ROOT}/bin"
  local installed_name
  installed_name="$(basename "$installed_python")"
  if [[ ! -e "${PYTHON_ROOT}/bin/python3" ]]; then
    ln -sf "$installed_name" "${PYTHON_ROOT}/bin/python3"
  fi
  if [[ ! -e "${PYTHON_ROOT}/bin/python" ]]; then
    ln -sf "python3" "${PYTHON_ROOT}/bin/python"
  fi
  chmod -R u+rwX "${PYTHON_ROOT}"

  RUNTIME_PYTHON="${PYTHON_ROOT}/bin/python3"
  [[ -x "$RUNTIME_PYTHON" ]] || RUNTIME_PYTHON="${PYTHON_ROOT}/bin/python"
  [[ -x "$RUNTIME_PYTHON" ]] || fail "Managed runtime Python is not executable."
  log "INFO" "Managed Python runtime ready: ${RUNTIME_PYTHON}"
}

register_backend_path() {
  emit_progress 12 "Registering backend import path."
  "$RUNTIME_PYTHON" - "$BACKEND_ROOT" <<'PY'
import pathlib
import sys
import sysconfig

backend_root = pathlib.Path(sys.argv[1]).resolve()
purelib = pathlib.Path(sysconfig.get_paths()["purelib"])
purelib.mkdir(parents=True, exist_ok=True)
(purelib / "transcribemate-backend.pth").write_text(str(backend_root) + "\n", encoding="utf-8")
print(f"Registered backend import path into {purelib}")
PY
}

install_runtime_dependencies() {
  emit_progress 20 "Installing backend dependencies."
  require_file "${BACKEND_ROOT}/requirements.txt" "requirements.txt"
  run_with_retry 2 "pip upgrade" "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --upgrade pip
  run_with_retry 2 "backend requirements install" \
    "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --prefer-binary -r "${BACKEND_ROOT}/requirements.txt"

  if [[ "$INSTALL_DIARIZATION" -eq 1 && -f "${BACKEND_ROOT}/requirements-diarization.txt" ]]; then
    emit_progress 50 "Installing diarization dependencies."
    run_with_retry 2 "diarization requirements install" \
      "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --prefer-binary -r "${BACKEND_ROOT}/requirements-diarization.txt"
  fi
}

install_models() {
  if [[ "$WITH_MODELS" -ne 1 ]]; then
    return 0
  fi

  emit_progress 70 "Downloading default AI models."
  "$RUNTIME_PYTHON" - "$BACKEND_ROOT" <<'PY'
import os
import sys
from pathlib import Path

backend_root = os.path.abspath(sys.argv[1])
if backend_root not in sys.path:
    sys.path.insert(0, backend_root)

from transcribemate.core.models import configure_model_environment
from transcribemate.core.models import huggingface_cache_dir, whisper_cache_dir
from faster_whisper import WhisperModel
from faster_whisper.utils import download_model as download_whisper_model
from huggingface_hub import snapshot_download
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer

configure_model_environment()

cache_root = Path(whisper_cache_dir())
target_model_dir = cache_root / "models" / "large-v3"
target_model_dir.mkdir(parents=True, exist_ok=True)
download_whisper_model(
    "large-v3",
    output_dir=str(target_model_dir),
    local_files_only=False,
    cache_dir=str(cache_root),
)
WhisperModel(str(target_model_dir), device="cpu", compute_type="int8", local_files_only=True)

translation_model_name = "Helsinki-NLP/opus-mt-en-cs"
hf_cache_root = Path(huggingface_cache_dir())
translation_model_dir = hf_cache_root / "hub" / "models--Helsinki-NLP--opus-mt-en-cs"
translation_model_dir.mkdir(parents=True, exist_ok=True)
snapshot_download(
    repo_id=translation_model_name,
    local_dir=str(translation_model_dir),
    local_files_only=False,
)
AutoTokenizer.from_pretrained(str(translation_model_dir), local_files_only=True)
try:
    AutoModelForSeq2SeqLM.from_pretrained(
        str(translation_model_dir),
        local_files_only=True,
        use_safetensors=True,
    )
except Exception:
    AutoModelForSeq2SeqLM.from_pretrained(str(translation_model_dir), local_files_only=True)

print("Model prefetch complete.")
PY

  [[ -d "$WHISPER_MODEL_DIR" ]] || fail "Whisper model directory missing after bootstrap: ${WHISPER_MODEL_DIR}"
  [[ -d "$TRANSLATION_MODEL_DIR" ]] || fail "Translation model directory missing after bootstrap: ${TRANSLATION_MODEL_DIR}"
  log "INFO" "Model prefetch validation passed."
}

install_ffmpeg_assets() {
  if [[ "$DOWNLOAD_FFMPEG" -ne 1 ]]; then
    return 0
  fi

  emit_progress 85 "Preparing FFmpeg tools."
  mkdir -p "$ASSETS_DIR"

  local system_ffmpeg system_ffprobe
  system_ffmpeg="$(resolve_system_tool_path ffmpeg || true)"
  system_ffprobe="$(resolve_system_tool_path ffprobe || true)"
  if [[ -n "$system_ffmpeg" && -n "$system_ffprobe" ]]; then
    cp "$system_ffmpeg" "${ASSETS_DIR}/ffmpeg"
    cp "$system_ffprobe" "${ASSETS_DIR}/ffprobe"
    chmod +x "${ASSETS_DIR}/ffmpeg" "${ASSETS_DIR}/ffprobe"
    log "INFO" "FFmpeg assets copied from system installation into ${ASSETS_DIR}"
    return 0
  fi

  local ffmpeg_zip_path ffmpeg_zip_sha_path ffprobe_zip_path ffprobe_zip_sha_path
  if [[ "$DEV_BOOTSTRAP_MODE" -eq 1 ]]; then
    require_command curl
    ffmpeg_zip_path="${TMP_ROOT}/ffmpeg.zip"
    ffmpeg_zip_sha_path="${TMP_ROOT}/ffmpeg.zip.sha256"
    ffprobe_zip_path="${TMP_ROOT}/ffprobe.zip"
    ffprobe_zip_sha_path="${TMP_ROOT}/ffprobe.zip.sha256"
    log "INFO" "Downloading pinned FFmpeg assets for development bootstrap fallback."
    run_with_retry 2 "ffmpeg download" download_file "$DEV_FFMPEG_ZIP_URL" "$ffmpeg_zip_path"
    run_with_retry 2 "ffmpeg checksum download" download_file "$DEV_FFMPEG_ZIP_SHA_URL" "$ffmpeg_zip_sha_path"
    run_with_retry 2 "ffprobe download" download_file "$DEV_FFPROBE_ZIP_URL" "$ffprobe_zip_path"
    run_with_retry 2 "ffprobe checksum download" download_file "$DEV_FFPROBE_ZIP_SHA_URL" "$ffprobe_zip_sha_path"
  else
    require_file "$FFMPEG_ZIP_PATH" "bundled FFmpeg archive"
    require_file "$FFMPEG_ZIP_SHA_PATH" "bundled FFmpeg checksum"
    require_file "$FFPROBE_ZIP_PATH" "bundled FFprobe archive"
    require_file "$FFPROBE_ZIP_SHA_PATH" "bundled FFprobe checksum"
    ffmpeg_zip_path="$FFMPEG_ZIP_PATH"
    ffmpeg_zip_sha_path="$FFMPEG_ZIP_SHA_PATH"
    ffprobe_zip_path="$FFPROBE_ZIP_PATH"
    ffprobe_zip_sha_path="$FFPROBE_ZIP_SHA_PATH"
  fi

  local expected_ffmpeg_sha expected_ffprobe_sha actual_ffmpeg_sha actual_ffprobe_sha
  expected_ffmpeg_sha="$(awk '{print $1}' "$ffmpeg_zip_sha_path" | head -n 1)"
  expected_ffprobe_sha="$(awk '{print $1}' "$ffprobe_zip_sha_path" | head -n 1)"
  actual_ffmpeg_sha="$(sha256_file "$ffmpeg_zip_path")"
  actual_ffprobe_sha="$(sha256_file "$ffprobe_zip_path")"

  [[ "$expected_ffmpeg_sha" == "$actual_ffmpeg_sha" ]] || fail "FFmpeg archive SHA256 mismatch."
  [[ "$expected_ffprobe_sha" == "$actual_ffprobe_sha" ]] || fail "FFprobe archive SHA256 mismatch."

  local ffmpeg_extract ffprobe_extract
  ffmpeg_extract="${TMP_ROOT}/ffmpeg-extract"
  ffprobe_extract="${TMP_ROOT}/ffprobe-extract"
  rm -rf "$ffmpeg_extract" "$ffprobe_extract"
  mkdir -p "$ffmpeg_extract" "$ffprobe_extract"

  unzip -q "$ffmpeg_zip_path" -d "$ffmpeg_extract"
  unzip -q "$ffprobe_zip_path" -d "$ffprobe_extract"

  local ffmpeg_found ffprobe_found
  ffmpeg_found="$(find "$ffmpeg_extract" -type f -name 'ffmpeg' | head -n 1 || true)"
  ffprobe_found="$(find "$ffprobe_extract" -type f -name 'ffprobe' | head -n 1 || true)"
  [[ -n "$ffmpeg_found" ]] || fail "ffmpeg binary was not found in bundled archive."
  [[ -n "$ffprobe_found" ]] || fail "ffprobe binary was not found in bundled archive."

  cp "$ffmpeg_found" "${ASSETS_DIR}/ffmpeg"
  cp "$ffprobe_found" "${ASSETS_DIR}/ffprobe"
  chmod +x "${ASSETS_DIR}/ffmpeg" "${ASSETS_DIR}/ffprobe"
  log "INFO" "FFmpeg assets installed into ${ASSETS_DIR}"
}

run_runtime_sanity_checks() {
  emit_progress 96 "Running runtime validation checks."
  "$RUNTIME_PYTHON" - "$BACKEND_ROOT" "$DOWNLOAD_FFMPEG" "$INSTALL_DIARIZATION" "${ASSETS_DIR}/ffmpeg" <<'PY'
import json
import os
import sys

backend_root = os.path.abspath(sys.argv[1])
require_ffmpeg = sys.argv[2] == "1"
require_diarization = sys.argv[3] == "1"
ffmpeg_path = os.path.abspath(sys.argv[4])

if backend_root not in sys.path:
    sys.path.insert(0, backend_root)

results = []

def run_check(name, fn, required=True):
    try:
        fn()
        results.append({"name": name, "ok": True, "required": required, "error": ""})
    except Exception as exc:
        results.append({"name": name, "ok": False, "required": required, "error": f"{type(exc).__name__}: {exc}"})

run_check("backend_server", lambda: __import__("transcribemate.v2.backend.server"), required=True)
run_check("torch", lambda: __import__("torch"), required=True)
run_check("yt_dlp", lambda: __import__("yt_dlp"), required=True)
run_check("faster_whisper", lambda: __import__("faster_whisper"), required=True)
run_check("transformers", lambda: __import__("transformers"), required=True)
run_check("edge_tts", lambda: __import__("edge_tts"), required=True)
if require_diarization:
    run_check("speechbrain", lambda: __import__("speechbrain"), required=True)

def _check_ffmpeg():
    if not os.path.isfile(ffmpeg_path):
        raise FileNotFoundError(ffmpeg_path)

run_check("ffmpeg_presence", _check_ffmpeg, required=require_ffmpeg)

print(json.dumps(results, ensure_ascii=False))
if any(not item["ok"] and item["required"] for item in results):
    sys.exit(1)
PY
}

write_runtime_ready_marker() {
  emit_progress 99 "Writing runtime readiness marker."
  "$RUNTIME_PYTHON" - "$APP_DATA_DIR" "$RUNTIME_ROOT" "$BACKEND_ROOT" "$RUNTIME_PYTHON" "$APP_VERSION" "$WHISPER_MODEL_DIR" "$TRANSLATION_MODEL_DIR" "${ASSETS_DIR}/ffmpeg" "${ASSETS_DIR}/ffprobe" <<'PY'
import json
import os
import platform
import sys
from datetime import datetime, timezone

app_data_dir = os.path.abspath(sys.argv[1])
runtime_root = os.path.abspath(sys.argv[2])
backend_root = os.path.abspath(sys.argv[3])
python_exe = os.path.abspath(sys.argv[4])
app_version = sys.argv[5]
whisper_model_dir = os.path.abspath(sys.argv[6])
translation_model_dir = os.path.abspath(sys.argv[7])
ffmpeg_path = os.path.abspath(sys.argv[8])
ffprobe_path = os.path.abspath(sys.argv[9])
marker_path = os.path.join(runtime_root, "runtime-ready.json")

payload = {
    "schema_version": 3,
    "bootstrap_complete": True,
    "ready_utc": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    "platform": "macos",
    "arch": platform.machine().lower(),
    "backend_root": backend_root,
    "python_exe": python_exe,
    "python_relpath": "runtime/python/bin/python3",
    "ffmpeg_relpath": "assets/ffmpeg",
    "ffprobe_relpath": "assets/ffprobe",
    "app_version": app_version,
    "torch_profile": "cpu",
    "components": {
        "app_data_dir": app_data_dir,
        "required_paths": [
            "runtime/python/bin/python3",
            "assets/ffmpeg",
            "assets/ffprobe",
            "cache/whisper/models/large-v3",
            "cache/huggingface/hub/models--Helsinki-NLP--opus-mt-en-cs",
        ],
        "ffmpeg": ffmpeg_path,
        "ffprobe": ffprobe_path,
    },
    "models": {
        "default_pack": ["large-v3", "Helsinki-NLP/opus-mt-en-cs"],
        "whisper_model_path": whisper_model_dir,
        "translation_model_path": translation_model_dir,
    },
}

os.makedirs(runtime_root, exist_ok=True)
with open(marker_path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
print(marker_path)
PY
}

require_command bash
require_command find
require_command shasum
require_command tar
require_command unzip

[[ -d "$BACKEND_ROOT" ]] || fail "Backend root does not exist: ${BACKEND_ROOT}"
if [[ -f "$MANIFEST_PATH" ]]; then
  # shellcheck disable=SC1090
  source "$MANIFEST_PATH"

  PYTHON_ARCHIVE_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/${PYTHON_ARCHIVE_FILE}"
  FFMPEG_ZIP_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/${FFMPEG_ZIP_FILE}"
  FFMPEG_ZIP_SHA_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/${FFMPEG_ZIP_SHA_FILE}"
  FFPROBE_ZIP_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/${FFPROBE_ZIP_FILE}"
  FFPROBE_ZIP_SHA_PATH="${BACKEND_ROOT}/runtime_assets/macos-arm64/${FFPROBE_ZIP_SHA_FILE}"
else
  DEV_BOOTSTRAP_MODE=1
fi

APP_VERSION=""
if [[ -f "${BACKEND_ROOT}/version.txt" ]]; then
  APP_VERSION="$(tr -d '\r\n' < "${BACKEND_ROOT}/version.txt")"
fi

mkdir -p "$APP_DATA_DIR" "$RUNTIME_ROOT" "$ASSETS_DIR"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/tm-runtime-bootstrap.XXXXXX")"

log "INFO" "Starting macOS runtime bootstrap."
emit_progress 1 "Preparing runtime bootstrap."

if [[ "$DEV_BOOTSTRAP_MODE" -eq 1 ]]; then
  log "WARN" "Bundled runtime manifest missing. Using development bootstrap fallback."
  ensure_dev_python
else
  ensure_python_runtime
  register_backend_path
  install_runtime_dependencies
fi
install_models
install_ffmpeg_assets
run_runtime_sanity_checks
if [[ "$DEV_BOOTSTRAP_MODE" -eq 1 ]]; then
  emit_progress 99 "Development bootstrap fallback completed."
  log "INFO" "Development bootstrap fallback completed."
else
  write_runtime_ready_marker
fi

emit_progress 100 "Runtime bootstrap completed successfully."
log "INFO" "Runtime bootstrap completed successfully."
