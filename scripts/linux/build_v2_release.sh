#!/usr/bin/env bash

set -euo pipefail

SKIP_FRONTEND_BUILD=0
SKIP_ARCHIVE_CREATION=0
APP_VERSION=""
DISTRO=""

PYTHON_ARCHIVE_URL="https://github.com/astral-sh/python-build-standalone/releases/download/20260303/cpython-3.12.13%2B20260303-x86_64-unknown-linux-gnu-install_only_stripped.tar.gz"
PYTHON_ARCHIVE_SHA256="de1ca5416aada18989ed046fad9a366d6aabbaf38af40fa7956ff7657f3e4014"
FFMPEG_ZIP_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffmpeg.zip"
FFMPEG_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffmpeg.zip.sha256"
FFPROBE_ZIP_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffprobe.zip"
FFPROBE_ZIP_SHA_URL="https://ffmpeg.martin-riedl.de/download/linux/amd64/1766430728_8.0.1/ffprobe.zip.sha256"

show_help() {
  cat <<'EOF'
TranscribeMate Linux x64 release build script.

Usage:
  ./scripts/linux/build_v2_release.sh --distro debian|arch [--skip-frontend-build] [--app-version <version>] [--skip-archive-creation] [--help]
  Compatibility wrapper: ./scripts/build_v2_release_linux.sh
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --distro)
      DISTRO="${2:-}"
      shift 2
      ;;
    --skip-frontend-build)
      SKIP_FRONTEND_BUILD=1
      shift
      ;;
    --app-version)
      APP_VERSION="${2:-}"
      shift 2
      ;;
    --skip-archive-creation)
      SKIP_ARCHIVE_CREATION=1
      shift
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

if [[ "$DISTRO" != "debian" && "$DISTRO" != "arch" ]]; then
  echo "[TM] --distro must be one of: debian, arch" >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
FRONTEND_DIR="${ROOT}/javafx-client"
DIST_DIR="${ROOT}/dist/linux"
DISTRO_DIST_DIR="${DIST_DIR}/${DISTRO}"
APP_IMAGE_DIR="${DISTRO_DIST_DIR}/TranscribeMate"
CACHE_DIR="${ROOT}/.build-cache/runtime-assets/linux-${DISTRO}-x64"
TARGET_DIR="${FRONTEND_DIR}/target"
INPUT_DIR="${TARGET_DIR}/jpackage-input"
BACKEND_PAYLOAD_DIR="${APP_IMAGE_DIR}/lib/app/backend"

require_command() {
  local name="$1"
  command -v "$name" >/dev/null 2>&1 || {
    echo "[TM] Required command not found: ${name}" >&2
    exit 1
  }
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

resolve_java_home() {
  if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" && -x "${JAVA_HOME}/bin/javac" && -x "${JAVA_HOME}/bin/jpackage" ]]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
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

resolve_app_version() {
  local requested="$1"
  local version_file="${ROOT}/version.txt"
  if [[ -n "$requested" ]]; then
    printf '%s\n' "$requested" >"$version_file"
    printf '%s\n' "$requested"
    return 0
  fi

  local today previous previous_prefix previous_seq sequence resolved
  today="$(date '+%y.%m.%d')"
  sequence=1
  if [[ -f "$version_file" ]]; then
    previous="$(tr -d '\r\n' < "$version_file")"
    if [[ "$previous" =~ ^([0-9]{2}\.[0-9]{2}\.[0-9]{2})\.([0-9]{3})$ ]]; then
      previous_prefix="${BASH_REMATCH[1]}"
      previous_seq="${BASH_REMATCH[2]}"
      if [[ "$previous_prefix" == "$today" ]]; then
        sequence=$((10#$previous_seq + 1))
      fi
    fi
  fi

  printf -v resolved '%s.%03d' "$today" "$sequence"
  printf '%s\n' "$resolved" >"$version_file"
  printf '%s\n' "$resolved"
}

derive_jpackage_version() {
  local release_version="$1"
  if [[ "$release_version" =~ ^([0-9]{2})\.([0-9]{2})\.([0-9]{2})\.([0-9]+)$ ]]; then
    local year month day build merged_patch
    year="${BASH_REMATCH[1]}"
    month="${BASH_REMATCH[2]}"
    day="${BASH_REMATCH[3]}"
    build="${BASH_REMATCH[4]}"
    merged_patch="${day}${build}"
    printf '%d.%d.%d\n' "$((10#$year))" "$((10#$month))" "$((10#$merged_patch))"
    return 0
  fi

  if [[ "$release_version" =~ ^[0-9]+(\.[0-9]+){0,2}$ ]]; then
    printf '%s\n' "$release_version"
    return 0
  fi

  echo "[TM] Unsupported --app-version format for Linux: ${release_version}" >&2
  exit 1
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

prepare_runtime_assets() {
  local bundle_dir="$1"
  local python_archive_cache="${CACHE_DIR}/python-runtime.tar.gz"
  local ffmpeg_zip_cache="${CACHE_DIR}/ffmpeg.zip"
  local ffmpeg_sha_cache="${CACHE_DIR}/ffmpeg.zip.sha256"
  local ffprobe_zip_cache="${CACHE_DIR}/ffprobe.zip"
  local ffprobe_sha_cache="${CACHE_DIR}/ffprobe.zip.sha256"

  mkdir -p "$CACHE_DIR" "$bundle_dir"

  if [[ ! -f "$python_archive_cache" || "$(sha256_file "$python_archive_cache")" != "$PYTHON_ARCHIVE_SHA256" ]]; then
    echo "[TM] Downloading pinned python-build-standalone runtime..."
    download_file "$PYTHON_ARCHIVE_URL" "$python_archive_cache"
  fi
  [[ "$(sha256_file "$python_archive_cache")" == "$PYTHON_ARCHIVE_SHA256" ]] || {
    echo "[TM] Python runtime archive SHA256 mismatch." >&2
    exit 1
  }

  echo "[TM] Downloading pinned FFmpeg assets for Linux x64..."
  download_file "$FFMPEG_ZIP_URL" "$ffmpeg_zip_cache"
  download_file "$FFMPEG_ZIP_SHA_URL" "$ffmpeg_sha_cache"
  download_file "$FFPROBE_ZIP_URL" "$ffprobe_zip_cache"
  download_file "$FFPROBE_ZIP_SHA_URL" "$ffprobe_sha_cache"

  [[ "$(sha256_file "$ffmpeg_zip_cache")" == "$(read_checksum_token "$ffmpeg_sha_cache")" ]] || {
    echo "[TM] FFmpeg archive SHA256 mismatch." >&2
    exit 1
  }
  [[ "$(sha256_file "$ffprobe_zip_cache")" == "$(read_checksum_token "$ffprobe_sha_cache")" ]] || {
    echo "[TM] FFprobe archive SHA256 mismatch." >&2
    exit 1
  }

  cp "$python_archive_cache" "${bundle_dir}/python-runtime.tar.gz"
  cp "$ffmpeg_zip_cache" "${bundle_dir}/ffmpeg.zip"
  cp "$ffmpeg_sha_cache" "${bundle_dir}/ffmpeg.zip.sha256"
  cp "$ffprobe_zip_cache" "${bundle_dir}/ffprobe.zip"
  cp "$ffprobe_sha_cache" "${bundle_dir}/ffprobe.zip.sha256"

  cat >"${bundle_dir}/asset-manifest.env" <<EOF
PYTHON_ARCHIVE_FILE=python-runtime.tar.gz
PYTHON_ARCHIVE_SHA256=${PYTHON_ARCHIVE_SHA256}
FFMPEG_ZIP_FILE=ffmpeg.zip
FFMPEG_ZIP_SHA_FILE=ffmpeg.zip.sha256
FFPROBE_ZIP_FILE=ffprobe.zip
FFPROBE_ZIP_SHA_FILE=ffprobe.zip.sha256
EOF
}

validate_bundled_backend() {
  local bundled_dir="$1"
  [[ -d "$bundled_dir" ]] || { echo "[TM] Missing bundled backend directory: ${bundled_dir}" >&2; exit 1; }
  [[ -f "${bundled_dir}/transcribemate/v2/backend/server.py" ]] || { echo "[TM] Missing backend server module." >&2; exit 1; }
  [[ -f "${bundled_dir}/requirements.txt" ]] || { echo "[TM] Missing requirements.txt in backend payload." >&2; exit 1; }
  [[ -f "${bundled_dir}/bootstrap_runtime.sh" ]] || { echo "[TM] Missing Linux bootstrap script in backend payload." >&2; exit 1; }
  [[ -f "${bundled_dir}/runtime_assets/linux-x64/asset-manifest.env" ]] || { echo "[TM] Missing Linux runtime asset manifest." >&2; exit 1; }
}

write_platform_checksums() {
  local checksum_path="${DIST_DIR}/checksums.txt"
  find "$DIST_DIR" -maxdepth 1 -type f -name '*.tar.gz' -print0 \
    | sort -z \
    | while IFS= read -r -d '' file; do
        hash="$(sha256_file "$file")"
        base="$(basename "$file")"
        printf '%s *%s\n' "$hash" "$base"
      done >"$checksum_path"
}

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "[TM] This script must be run on Linux." >&2
  exit 1
fi
if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "[TM] This script currently supports only x86_64 Linux hosts." >&2
  exit 1
fi

require_command curl
require_command find
require_command sha256sum
require_command tar
require_command unzip

if ! MVN_BIN="$(resolve_maven_executable)"; then
  echo "[TM] Maven (mvn) was not found." >&2
  exit 1
fi

if ! RESOLVED_JAVA_HOME="$(resolve_java_home)"; then
  cat >&2 <<'EOF'
[TM] Java JDK (21+) with jpackage was not found.
Install a JDK and run the script again.
EOF
  exit 1
fi

export JAVA_HOME="$RESOLVED_JAVA_HOME"
JPACKAGE_EXE="${JAVA_HOME}/bin/jpackage"
[[ -x "$JPACKAGE_EXE" ]] || { echo "[TM] jpackage not found under JAVA_HOME." >&2; exit 1; }

APP_VERSION="$(resolve_app_version "$APP_VERSION")"
JPACKAGE_VERSION="$(derive_jpackage_version "$APP_VERSION")"
ARCHIVE_NAME="TranscribeMate-${APP_VERSION}-linux-${DISTRO}-x64.tar.gz"
ARCHIVE_PATH="${DIST_DIR}/${ARCHIVE_NAME}"

echo "[TM] JAVA_HOME=${JAVA_HOME}"
echo "[TM] Release version=${APP_VERSION}"
echo "[TM] jpackage version=${JPACKAGE_VERSION}"
echo "[TM] Linux distro profile=${DISTRO}"

if [[ "$SKIP_FRONTEND_BUILD" -ne 1 ]]; then
  echo "[TM] Building frontend JAR and runtime dependencies..."
  (
    cd "$FRONTEND_DIR"
    "$MVN_BIN" -DskipTests clean package dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/dependency
  )
fi

[[ -d "$TARGET_DIR" ]] || { echo "[TM] Frontend target directory missing: ${TARGET_DIR}" >&2; exit 1; }
JAR_PATH="$(find "$TARGET_DIR" -maxdepth 1 -type f -name '*.jar' ! -name '*sources*' ! -name '*javadoc*' ! -name 'original-*' | head -n 1 || true)"
[[ -n "$JAR_PATH" ]] || { echo "[TM] No runnable frontend JAR found in ${TARGET_DIR}" >&2; exit 1; }
[[ -d "${TARGET_DIR}/dependency" ]] || { echo "[TM] Dependency directory missing: ${TARGET_DIR}/dependency" >&2; exit 1; }

rm -rf "$INPUT_DIR"
mkdir -p "$INPUT_DIR" "$DIST_DIR" "$DISTRO_DIST_DIR"
cp "$JAR_PATH" "$INPUT_DIR/"
find "${TARGET_DIR}/dependency" -maxdepth 1 -type f -name '*.jar' -exec cp {} "$INPUT_DIR/" \;

rm -rf "$APP_IMAGE_DIR"

echo "[TM] Creating Linux app image via jpackage..."
JPACKAGE_ARGS=(
  --type app-image
  --name TranscribeMate
  --dest "$DISTRO_DIST_DIR"
  --input "$INPUT_DIR"
  --main-jar "$(basename "$JAR_PATH")"
  --main-class com.transcribemate.v2.fx.Launcher
  --app-version "$JPACKAGE_VERSION"
  --vendor "Martin Nebehay"
)

if [[ -f "${ROOT}/icon.png" ]]; then
  JPACKAGE_ARGS+=(--icon "${ROOT}/icon.png")
fi

"$JPACKAGE_EXE" "${JPACKAGE_ARGS[@]}"
[[ -d "$APP_IMAGE_DIR" ]] || { echo "[TM] jpackage finished but app image was not found: ${APP_IMAGE_DIR}" >&2; exit 1; }

mkdir -p "$BACKEND_PAYLOAD_DIR"
cp -R "${ROOT}/transcribemate" "${BACKEND_PAYLOAD_DIR}/transcribemate"
cp "${ROOT}/requirements.txt" "${BACKEND_PAYLOAD_DIR}/requirements.txt"
if [[ -f "${ROOT}/requirements-diarization.txt" ]]; then
  cp "${ROOT}/requirements-diarization.txt" "${BACKEND_PAYLOAD_DIR}/requirements-diarization.txt"
fi
if [[ -f "${ROOT}/version.txt" ]]; then
  cp "${ROOT}/version.txt" "${BACKEND_PAYLOAD_DIR}/version.txt"
fi
cp "${ROOT}/scripts/linux/bootstrap_runtime.sh" "${BACKEND_PAYLOAD_DIR}/bootstrap_runtime.sh"

find "$BACKEND_PAYLOAD_DIR" -type d -name '__pycache__' -exec rm -rf {} +
find "$BACKEND_PAYLOAD_DIR" -type f \( -name '*.pyc' -o -name '*.pyo' \) -delete

prepare_runtime_assets "${BACKEND_PAYLOAD_DIR}/runtime_assets/linux-x64"
validate_bundled_backend "$BACKEND_PAYLOAD_DIR"

echo "[TM] Linux app image ready: ${APP_IMAGE_DIR}"

if [[ "$SKIP_ARCHIVE_CREATION" -ne 1 ]]; then
  rm -f "$ARCHIVE_PATH"
  echo "[TM] Creating TAR.GZ release package..."
  tar -C "$DISTRO_DIST_DIR" -czf "$ARCHIVE_PATH" "TranscribeMate"
  write_platform_checksums
  echo "[TM] TAR.GZ release ready: ${ARCHIVE_PATH}"
  echo "[TM] SHA256 checksums written to: ${DIST_DIR}/checksums.txt"
else
  echo "[TM] Archive creation skipped. App image is available at: ${APP_IMAGE_DIR}"
fi
