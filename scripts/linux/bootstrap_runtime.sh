#!/usr/bin/env bash

set -euo pipefail

APP_NAME="TranscribeMate"
WITH_MODELS=0
DOWNLOAD_FFMPEG=0
INSTALL_DIARIZATION=0
BACKEND_ROOT=""
PROGRESS_FILE=""
LOG_FILE=""

TORCH_CPU_INDEX_URL="https://download.pytorch.org/whl/cpu"
DEV_PYTHON_ARCHIVE_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20260303/cpython-3.12.13%2B20260303-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"
DEV_PYTHON_ARCHIVE_SHA256="c710dd6b63e4df92f4c5b7b29ccad4276226a024a9017d5018f15321c7854af4"
DEV_FFMPEG_ZIP_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffmpeg.zip"
DEV_FFMPEG_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffmpeg.zip.sha256"
DEV_FFPROBE_ZIP_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffprobe.zip"
DEV_FFPROBE_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffprobe.zip.sha256"

show_help() {
  cat <<'EOF'
TranscribeMate Linux runtime bootstrap.

Usage:
  ./scripts/linux/bootstrap_runtime.sh [options]

Options:
  --with-models
  --download-ffmpeg
  --install-diarization
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
    --download-ffmpeg)
      DOWNLOAD_FFMPEG=1
      shift
      ;;
    --install-diarization)
      INSTALL_DIARIZATION=1
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
      echo "[TM] Unknown argument: $1" >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "$BACKEND_ROOT" ]]; then
  candidate_root="$(cd "$SCRIPT_DIR/../.." && pwd)"
  if [[ -d "$candidate_root/transcribemate" ]]; then
    BACKEND_ROOT="$candidate_root"
  else
    BACKEND_ROOT="$SCRIPT_DIR"
  fi
fi
BACKEND_ROOT="$(cd "$BACKEND_ROOT" && pwd)"

APP_DATA_DIR="${TM_APP_DATA_DIR:-${XDG_DATA_HOME:-${HOME}/.local/share}/${APP_NAME}}"
RUNTIME_ROOT="${APP_DATA_DIR}/runtime"
ASSETS_DIR="${APP_DATA_DIR}/assets"
PYTHON_ROOT="${RUNTIME_ROOT}/python"
RUNTIME_READY_MARKER="${RUNTIME_ROOT}/runtime-ready.json"
WHISPER_MODEL_DIR="${APP_DATA_DIR}/cache/whisper/models/large-v3"
TRANSLATION_MODEL_DIR="${APP_DATA_DIR}/cache/huggingface/hub/models--Helsinki-NLP--opus-mt-en-cs"
MANIFEST_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/asset-manifest.env"

DEV_BOOTSTRAP_MODE=0
TMP_ROOT=""
RUNTIME_PYTHON=""
PYTHON_ARCHIVE_PATH=""
FFMPEG_ZIP_PATH=""
FFMPEG_ZIP_SHA_PATH=""
FFPROBE_ZIP_PATH=""
FFPROBE_ZIP_SHA_PATH=""

timestamp_utc() {
  date -u '+%Y-%m-%dT%H:%M:%SZ'
}

emit_progress() {
  local percent="$1"
  local message="$2"
  if [[ -n "$PROGRESS_FILE" ]]; then
    mkdir -p "$(dirname "$PROGRESS_FILE")"
    printf '%s|%s\n' "$percent" "$message" >"$PROGRESS_FILE"
  fi
}

log() {
  local level="$1"
  local message="$2"
  local line
  line="[$(timestamp_utc)] [$level] $message"
  echo "$line"
  if [[ -n "$LOG_FILE" ]]; then
    mkdir -p "$(dirname "$LOG_FILE")"
    printf '%s\n' "$line" >>"$LOG_FILE"
  fi
}

fail() {
  log "ERROR" "$1"
  emit_progress 100 "$1"
  exit 1
}

cleanup() {
  if [[ -n "$TMP_ROOT" && -d "$TMP_ROOT" ]]; then
    rm -rf "$TMP_ROOT"
  fi
}
trap cleanup EXIT

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || fail "Required command not found: ${name}"
}

require_file() {
  local path="$1"
  local description="$2"
  [[ -f "$path" ]] || fail "Missing ${description}: ${path}"
}

download_file() {
  local url="$1"
  local destination="$2"
  mkdir -p "$(dirname "$destination")"
  curl -fsSL "$url" -o "$destination"
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

read_checksum_token() {
  awk '{print $1}' "$1" | head -n 1
}

run_with_retry() {
  local attempts="$1"
  local description="$2"
  shift 2
  local attempt=1
  while true; do
    if "$@"; then
      return 0
    fi
    if (( attempt >= attempts )); then
      fail "${description} failed after ${attempts} attempt(s)."
    fi
    log "WARN" "${description} failed (attempt ${attempt}/${attempts}). Retrying..."
    sleep $((attempt * 2))
    attempt=$((attempt + 1))
  done
}

find_python_executable() {
  local search_root="$1"
  find "$search_root" -type f -path '*/bin/*' \( -name 'python3' -o -name 'python3.*' -o -name 'python' \) | head -n 1 || true
}

resolve_system_tool_path() {
  local name="$1"
  if command -v "$name" >/dev/null 2>&1; then
    command -v "$name"
    return 0
  fi

  local candidate
  for candidate in "${HOME}/.local/bin/${name}" /usr/local/bin/"${name}" /usr/bin/"${name}"; do
    if [[ -x "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  return 1
}

ensure_dev_python() {
  emit_progress 8 "Preparing development Python fallback."
  local candidate="${BACKEND_ROOT}/.venv/bin/python3"
  if [[ ! -x "$candidate" ]]; then
    candidate="${BACKEND_ROOT}/.venv/bin/python"
  fi
  if [[ ! -x "$candidate" ]]; then
    candidate="$(resolve_system_tool_path python3 || true)"
  fi
  if [[ -z "$candidate" || ! -x "$candidate" ]]; then
    candidate="$(resolve_system_tool_path python || true)"
  fi
  [[ -n "$candidate" && -x "$candidate" ]] || fail "Development Python fallback was not found."
  RUNTIME_PYTHON="$candidate"
  log "INFO" "Using development Python fallback: ${RUNTIME_PYTHON}"
}

ensure_python_runtime() {
  emit_progress 10 "Installing managed Python runtime."
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
  extracted_python="$(find_python_executable "$extract_dir")"
  [[ -n "$extracted_python" ]] || fail "Could not find Python executable in extracted archive."

  local prefix_dir
  prefix_dir="$(cd "$(dirname "$extracted_python")/.." && pwd)"
  cp -R "$prefix_dir"/. "$PYTHON_ROOT"/

  local installed_python
  installed_python="$(find_python_executable "$PYTHON_ROOT")"
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

install_cpu_torch_profile() {
  emit_progress 36 "Installing CPU Torch runtime."
  run_with_retry 2 "CPU torch install" \
    "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --prefer-binary \
      --index-url "$TORCH_CPU_INDEX_URL" torch torchaudio
}

install_runtime_dependencies() {
  emit_progress 20 "Installing backend dependencies."
  require_file "${BACKEND_ROOT}/requirements.txt" "requirements.txt"
  run_with_retry 2 "pip upgrade" "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --upgrade pip
  run_with_retry 2 "backend requirements install" \
    "$RUNTIME_PYTHON" -m pip install --disable-pip-version-check --prefer-binary -r "${BACKEND_ROOT}/requirements.txt"
  install_cpu_torch_profile

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
  local system_ffmpeg system_ffprobe
  system_ffmpeg="$(resolve_system_tool_path ffmpeg || true)"
  system_ffprobe="$(resolve_system_tool_path ffprobe || true)"
  if [[ -n "$system_ffmpeg" && -n "$system_ffprobe" ]]; then
    cp "$system_ffmpeg" "${ASSETS_DIR}/ffmpeg"
    cp "$system_ffprobe" "${ASSETS_DIR}/ffprobe"
    chmod +x "${ASSETS_DIR}/ffmpeg" "${ASSETS_DIR}/ffprobe"
    log "INFO" "Using system FFmpeg tools."
    return 0
  fi

  local ffmpeg_zip_path ffmpeg_zip_sha_path ffprobe_zip_path ffprobe_zip_sha_path
  if [[ "$DEV_BOOTSTRAP_MODE" -eq 1 ]]; then
    ffmpeg_zip_path="${TMP_ROOT}/ffmpeg.zip"
    ffmpeg_zip_sha_path="${TMP_ROOT}/ffmpeg.zip.sha256"
    ffprobe_zip_path="${TMP_ROOT}/ffprobe.zip"
    ffprobe_zip_sha_path="${TMP_ROOT}/ffprobe.zip.sha256"
    run_with_retry 2 "ffmpeg download" download_file "$DEV_FFMPEG_ZIP_URL" "$ffmpeg_zip_path"
    run_with_retry 2 "ffmpeg checksum download" download_file "$DEV_FFMPEG_ZIP_SHA_URL" "$ffmpeg_zip_sha_path"
    run_with_retry 2 "ffprobe download" download_file "$DEV_FFPROBE_ZIP_URL" "$ffprobe_zip_path"
    run_with_retry 2 "ffprobe checksum download" download_file "$DEV_FFPROBE_ZIP_SHA_URL" "$ffprobe_zip_sha_path"
  else
    ffmpeg_zip_path="$FFMPEG_ZIP_PATH"
    ffmpeg_zip_sha_path="$FFMPEG_ZIP_SHA_PATH"
    ffprobe_zip_path="$FFPROBE_ZIP_PATH"
    ffprobe_zip_sha_path="$FFPROBE_ZIP_SHA_PATH"
    require_file "$ffmpeg_zip_path" "bundled FFmpeg archive"
    require_file "$ffmpeg_zip_sha_path" "bundled FFmpeg checksum"
    require_file "$ffprobe_zip_path" "bundled FFprobe archive"
    require_file "$ffprobe_zip_sha_path" "bundled FFprobe checksum"
  fi

  local expected_ffmpeg_sha expected_ffprobe_sha actual_ffmpeg_sha actual_ffprobe_sha
  expected_ffmpeg_sha="$(read_checksum_token "$ffmpeg_zip_sha_path")"
  expected_ffprobe_sha="$(read_checksum_token "$ffprobe_zip_sha_path")"
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
    run_check("torchaudio", lambda: __import__("torchaudio"), required=True)
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
  emit_progress 98 "Writing runtime-ready marker."
  "$RUNTIME_PYTHON" - "$APP_DATA_DIR" "$RUNTIME_ROOT" "$BACKEND_ROOT" "$RUNTIME_PYTHON" "$APP_VERSION" "$WHISPER_MODEL_DIR" "$TRANSLATION_MODEL_DIR" "${ASSETS_DIR}/ffmpeg" "${ASSETS_DIR}/ffprobe" <<'PY'
import json
import os
import sys

app_data_dir = os.path.abspath(sys.argv[1])
runtime_root = os.path.abspath(sys.argv[2])
backend_root = os.path.abspath(sys.argv[3])
runtime_python = os.path.abspath(sys.argv[4])
app_version = sys.argv[5]
whisper_model_dir = os.path.abspath(sys.argv[6])
translation_model_dir = os.path.abspath(sys.argv[7])
ffmpeg_path = os.path.abspath(sys.argv[8])
ffprobe_path = os.path.abspath(sys.argv[9])
marker_path = os.path.join(runtime_root, "runtime-ready.json")

payload = {
    "platform": "linux",
    "arch": "x64",
    "backend_root": backend_root,
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

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "[TM] This script must be run on Linux." >&2
  exit 1
fi
if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "[TM] This script currently supports only x86_64 Linux hosts." >&2
  exit 1
fi

require_command bash
require_command curl
require_command find
require_command sha256sum
require_command tar
require_command unzip

[[ -d "$BACKEND_ROOT" ]] || fail "Backend root does not exist: ${BACKEND_ROOT}"
if [[ -f "$MANIFEST_PATH" ]]; then
  # shellcheck disable=SC1090
  source "$MANIFEST_PATH"

  PYTHON_ARCHIVE_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/${PYTHON_ARCHIVE_FILE}"
  FFMPEG_ZIP_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/${FFMPEG_ZIP_FILE}"
  FFMPEG_ZIP_SHA_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/${FFMPEG_ZIP_SHA_FILE}"
  FFPROBE_ZIP_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/${FFPROBE_ZIP_FILE}"
  FFPROBE_ZIP_SHA_PATH="${BACKEND_ROOT}/runtime_assets/linux-x64/${FFPROBE_ZIP_SHA_FILE}"
else
  DEV_BOOTSTRAP_MODE=1
  PYTHON_ARCHIVE_SHA256="$DEV_PYTHON_ARCHIVE_SHA256"
fi

APP_VERSION=""
if [[ -f "${BACKEND_ROOT}/version.txt" ]]; then
  APP_VERSION="$(tr -d '\r\n' < "${BACKEND_ROOT}/version.txt")"
fi

mkdir -p "$APP_DATA_DIR" "$RUNTIME_ROOT" "$ASSETS_DIR"
TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/tm-linux-runtime.XXXXXX")"

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
