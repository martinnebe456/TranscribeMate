import argparse
import os
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import venv
import zipfile
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).parent
VENV = ROOT / ".venv"
ASSETS_DIR = ROOT / "assets"
LOG_FILE = ROOT / "bootstrap.log"

if sys.platform == "win32":
    PYTHON = VENV / "Scripts" / "python.exe"
else:
    PYTHON = VENV / "bin" / "python"

REQUIREMENTS = ROOT / "requirements.txt"
APP = ROOT / "app_gui.py"

# Windows-friendly FFmpeg bundle (kept out of git history)
FFMPEG_ZIP_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
FFMPEG_EXES = ("ffmpeg.exe", "ffprobe.exe", "ffplay.exe")


# -----------------------------
# Logging helpers
# -----------------------------
def init_log():
    LOG_FILE.write_text("", encoding="utf-8")
    log_info(f"Logging to: {LOG_FILE}")


def _log(level: str, message: str):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{timestamp}] {level} {message}"
    print(line)
    with LOG_FILE.open("a", encoding="utf-8") as fh:
        fh.write(line + "\n")


def log_step(message: str):
    _log("STEP", f"\u27a4 {message}")


def log_info(message: str):
    _log("INFO", message)


def log_success(message: str):
    _log("OK", f"\u2705 {message}")


def log_warn(message: str):
    _log("WARN", f"\u26a0\ufe0f {message}")


def log_fail(message: str):
    _log("FAIL", f"\u274c {message}")


def run(cmd, *, friendly_name: str | None = None, capture: bool = False, check: bool = True):
    label = friendly_name or " ".join(cmd)
    log_step(label)
    log_info(f"Command: {' '.join(cmd)}")
    try:
        if capture:
            result = subprocess.run(cmd, check=check, text=True, capture_output=True)
            if result.stdout:
                log_info(result.stdout.strip())
            if result.stderr:
                log_warn(result.stderr.strip())
            return result
        subprocess.check_call(cmd)
        return None
    except subprocess.CalledProcessError as exc:
        log_fail(f"Command failed (exit {exc.returncode}): {label}")
        raise


def in_venv():
    return sys.prefix != sys.base_prefix


def is_64bit() -> bool:
    return sys.maxsize > 2**32


def python_summary() -> str:
    ver = f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}"
    arch = "64-bit" if is_64bit() else "32-bit"
    return f"Python {ver} ({arch})"


def ensure_supported_python():
    """Fail fast on Python versions without binary wheels for key deps."""
    log_step("Checking Python version and architecture")
    log_info(python_summary())

    major, minor = sys.version_info[:2]
    if not is_64bit():
        log_fail("32-bit Python detected. Please install 64-bit Python 3.12.")
        log_info("Windows: winget install Python.Python.3.12")
        raise SystemExit(1)

    if major != 3 or minor < 10 or minor > 12:
        log_fail(f"Unsupported Python version: {major}.{minor}")
        log_info("Please install Python 3.12 (recommended) or 3.10/3.11 and try again.")
        log_info("Windows: winget install Python.Python.3.12")
        raise SystemExit(1)

    log_success("Python version is supported")


def create_venv():
    log_step("Creating virtual environment")
    venv.create(VENV, with_pip=True)
    log_success(f"Virtual environment created: {VENV}")


def print_pip_failure_help():
    log_warn("pip install failed. Common fixes:")
    log_info("1. Use Python 3.12: py -3.12 bootstrap.py")
    log_info("2. Delete the venv and try again: Remove-Item -Recurse -Force .venv")
    log_info("3. Restart the terminal after installing Python/FFmpeg")


def install_deps():
    log_step("Installing Python dependencies")
    try:
        run([str(PYTHON), "-m", "pip", "install", "--upgrade", "pip"], friendly_name="Upgrading pip")
        run([str(PYTHON), "-m", "pip", "install", "-r", str(REQUIREMENTS)], friendly_name="Installing requirements")
        log_success("Dependencies installed")
    except subprocess.CalledProcessError:
        print_pip_failure_help()
        raise


def install_cuda_torch():
    log_step("Installing CUDA-enabled PyTorch (optional)")
    # Official cu121 wheels exist for Python <= 3.12 on Windows.
    if sys.version_info.major != 3 or sys.version_info.minor > 12:
        log_warn("Skipping CUDA install: compatible wheels are not available for this Python version.")
        return
    try:
        run(
            [
                str(PYTHON),
                "-m",
                "pip",
                "install",
                "--upgrade",
                "--force-reinstall",
                "torch",
                "torchvision",
                "torchaudio",
                "--index-url",
                "https://download.pytorch.org/whl/cu121",
            ],
            friendly_name="Installing CUDA PyTorch (cu121)",
        )
        log_success("CUDA-enabled PyTorch installed")
    except subprocess.CalledProcessError as exc:
        log_warn("CUDA Torch install failed. Falling back to CPU wheels already installed.")
        log_info(f"Error was: {exc}")


def _has_system_ffmpeg() -> bool:
    return bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


def _has_assets_ffmpeg() -> bool:
    return all((ASSETS_DIR / name).exists() for name in ("ffmpeg.exe", "ffprobe.exe"))


def _extract_ffmpeg_from_zip(zip_path: Path):
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        for exe_name in FFMPEG_EXES:
            member = next((n for n in names if n.endswith(f"/bin/{exe_name}")), None)
            if not member:
                raise RuntimeError(f"Could not find {exe_name} in FFmpeg zip.")
            target = ASSETS_DIR / exe_name
            with zf.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            log_success(f"Saved: {target}")


def _ffmpeg_version_cmd() -> list[str]:
    ffmpeg_path = ASSETS_DIR / "ffmpeg.exe"
    if ffmpeg_path.exists():
        return [str(ffmpeg_path), "-version"]
    return ["ffmpeg", "-version"]


def verify_ffmpeg():
    try:
        result = run(_ffmpeg_version_cmd(), friendly_name="Verifying ffmpeg", capture=True)
        if result and result.stdout:
            first_line = result.stdout.splitlines()[0].strip()
            log_success(f"ffmpeg detected: {first_line}")
    except Exception as exc:  # pragma: no cover - best-effort verification
        log_warn(f"Could not verify ffmpeg: {exc}")


def ensure_ffmpeg_assets(*, allow_download: bool):
    """Ensure FFmpeg binaries are available via PATH or local assets/."""
    log_step("Checking FFmpeg availability")

    if _has_system_ffmpeg():
        log_success("FFmpeg found in PATH")
        verify_ffmpeg()
        return

    if _has_assets_ffmpeg():
        log_success("FFmpeg found in assets/")
        verify_ffmpeg()
        return

    log_warn("FFmpeg not found in PATH or assets/")

    if not allow_download:
        log_warn("Skipping FFmpeg download because --no-ffmpeg-download was used")
        log_info("Install FFmpeg manually and add it to PATH: winget install Gyan.FFmpeg")
        return

    if sys.platform != "win32":
        log_fail("FFmpeg not found. Please install ffmpeg/ffprobe and add them to PATH.")
        raise SystemExit(1)

    log_step("Downloading FFmpeg bundle (Windows)")
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="tm_ffmpeg_") as tmp:
        tmp_dir = Path(tmp)
        zip_path = tmp_dir / "ffmpeg.zip"
        urllib.request.urlretrieve(FFMPEG_ZIP_URL, zip_path)
        _extract_ffmpeg_from_zip(zip_path)

    verify_ffmpeg()


def relaunch_in_venv(args):
    log_step("Relaunching inside .venv")
    cmd = [str(PYTHON), str(APP)]
    run(cmd, friendly_name="Launching app in venv")
    sys.exit(0)


def _doctor_check_venv():
    if VENV.exists():
        log_success(f"Virtual environment exists: {VENV}")
    else:
        log_warn("Virtual environment does not exist yet (.venv)")


def _doctor_check_ffmpeg():
    if _has_system_ffmpeg():
        log_success("FFmpeg available in PATH")
        verify_ffmpeg()
    elif _has_assets_ffmpeg():
        log_success("FFmpeg available in assets/")
        verify_ffmpeg()
    else:
        log_fail("FFmpeg not found (PATH or assets/)")
        log_info("Fix: winget install Gyan.FFmpeg")


def _doctor_check_torch_in_venv():
    if not PYTHON.exists():
        log_warn("Cannot check torch/GPU because .venv Python is missing")
        return

    script = (
        "import json\n"
        "out = {}\n"
        "try:\n"
        "    import torch\n"
        "    out['torch'] = torch.__version__\n"
        "    out['cuda_available'] = bool(torch.cuda.is_available())\n"
        "    out['cuda_device'] = torch.cuda.get_device_name(0) if out['cuda_available'] else ''\n"
        "except Exception as e:\n"
        "    out['error'] = str(e)\n"
        "print(json.dumps(out))\n"
    )

    try:
        result = subprocess.run([str(PYTHON), "-c", script], text=True, capture_output=True, check=False)
        if result.returncode != 0:
            log_fail("Torch check failed inside .venv")
            if result.stderr:
                log_warn(result.stderr.strip())
            return

        payload = (result.stdout or "").strip()
        if not payload:
            log_warn("Torch check produced no output")
            return

        import json

        data = json.loads(payload)
        if "error" in data:
            log_fail(f"Torch not ready in .venv: {data['error']}")
            log_info("Fix: run bootstrap.py once to install dependencies")
            return

        log_success(f"Torch version: {data.get('torch', 'unknown')}")
        if data.get("cuda_available"):
            log_success(f"CUDA available: {data.get('cuda_device', 'GPU detected')}")
        else:
            log_warn("CUDA not available (CPU mode will be used)")
    except Exception as exc:  # pragma: no cover - best effort
        log_warn(f"Could not run torch doctor check: {exc}")


def run_doctor():
    log_step("Running doctor checks")
    ensure_supported_python()
    _doctor_check_venv()
    _doctor_check_ffmpeg()
    _doctor_check_torch_in_venv()
    log_success("Doctor finished")


def parse_args():
    parser = argparse.ArgumentParser(description="Bootstrap TranscribeMate")
    parser.add_argument("--doctor", action="store_true", help="Only run environment checks")
    parser.add_argument("--no-cuda", action="store_true", help="Skip CUDA PyTorch installation")
    parser.add_argument(
        "--no-ffmpeg-download",
        action="store_true",
        help="Do not auto-download FFmpeg even if missing",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    init_log()

    log_info(f"Platform: {platform.platform()}")
    log_info(f"Working directory: {ROOT}")

    if args.doctor:
        run_doctor()
        return

    ensure_supported_python()

    allow_ffmpeg_download = not args.no_ffmpeg_download

    if not VENV.exists():
        create_venv()
        install_deps()
        if args.no_cuda:
            log_warn("Skipping CUDA installation due to --no-cuda")
        else:
            install_cuda_torch()
        ensure_ffmpeg_assets(allow_download=allow_ffmpeg_download)
        relaunch_in_venv(args)

    if not in_venv():
        ensure_ffmpeg_assets(allow_download=allow_ffmpeg_download)
        relaunch_in_venv(args)

    # we're inside venv -> run GUI
    ensure_ffmpeg_assets(allow_download=allow_ffmpeg_download)
    run([sys.executable, str(APP)], friendly_name="Launching TranscribeMate GUI")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except subprocess.CalledProcessError:
        # run() already logged the error details
        sys.exit(1)
    except Exception as exc:  # pragma: no cover - safety net
        log_fail(f"Unexpected error: {exc}")
        log_info("See bootstrap.log for details.")
        sys.exit(1)
