import os
import sys
import shutil
import subprocess
import tempfile
import urllib.request
import venv
import zipfile
from pathlib import Path

ROOT = Path(__file__).parent
VENV = ROOT / ".venv"
ASSETS_DIR = ROOT / "assets"

if sys.platform == "win32":
    PYTHON = VENV / "Scripts" / "python.exe"
else:
    PYTHON = VENV / "bin" / "python"

REQUIREMENTS = ROOT / "requirements.txt"
APP = ROOT / "app_gui.py"

# Windows-friendly FFmpeg bundle (kept out of git history)
FFMPEG_ZIP_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
FFMPEG_EXES = ("ffmpeg.exe", "ffprobe.exe", "ffplay.exe")


def run(cmd):
    print("[BOOTSTRAP]", " ".join(cmd))
    subprocess.check_call(cmd)


def in_venv():
    return sys.prefix != sys.base_prefix


def create_venv():
    print("[BOOTSTRAP] Creating virtual environment...")
    venv.create(VENV, with_pip=True)


def install_deps():
    print("[BOOTSTRAP] Installing dependencies...")
    run([str(PYTHON), "-m", "pip", "install", "--upgrade", "pip"])
    run([str(PYTHON), "-m", "pip", "install", "-r", str(REQUIREMENTS)])


def install_cuda_torch():
    print("[BOOTSTRAP] Installing CUDA-enabled PyTorch (cu121)...")
    # Official cu121 wheels exist for Python <= 3.12 on Windows.
    if sys.version_info.major != 3 or sys.version_info.minor > 12:
        print(
            "[BOOTSTRAP] Skipping CUDA install: cu121 wheels are not built for this Python version."
            " Install Python 3.12 x64 + CUDA Toolkit if you need GPU, or install a compatible wheel manually."
        )
        return
    try:
        run([
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
        ])
    except subprocess.CalledProcessError as exc:
        print("[BOOTSTRAP] CUDA Torch install failed. Falling back to already installed torch (likely CPU).")
        print(f"[BOOTSTRAP] Error was: {exc}")


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
            print(f"[BOOTSTRAP] Saved: {target}")


def ensure_ffmpeg_assets():
    """Ensure FFmpeg binaries are available via PATH or local assets/."""
    if _has_system_ffmpeg() or _has_assets_ffmpeg():
        return

    if sys.platform != "win32":
        print("[BOOTSTRAP] FFmpeg not found. Please install ffmpeg/ffprobe and add them to PATH.")
        return

    print("[BOOTSTRAP] FFmpeg not found in PATH or assets/. Downloading bundle...")
    ASSETS_DIR.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="tm_ffmpeg_") as tmp:
        tmp_dir = Path(tmp)
        zip_path = tmp_dir / "ffmpeg.zip"
        urllib.request.urlretrieve(FFMPEG_ZIP_URL, zip_path)
        _extract_ffmpeg_from_zip(zip_path)


def relaunch_in_venv():
    print("[BOOTSTRAP] Relaunching app inside .venv")
    run([str(PYTHON), str(APP)])
    sys.exit(0)


def main():
    if not VENV.exists():
        create_venv()
        install_deps()
        install_cuda_torch()
        ensure_ffmpeg_assets()
        relaunch_in_venv()

    if not in_venv():
        ensure_ffmpeg_assets()
        relaunch_in_venv()

    # we're inside venv → run GUI
    ensure_ffmpeg_assets()
    run([sys.executable, str(APP)])


if __name__ == "__main__":
    main()
