import os
import sys
import subprocess
import venv
from pathlib import Path

ROOT = Path(__file__).parent
VENV = ROOT / ".venv"

if sys.platform == "win32":
    PYTHON = VENV / "Scripts" / "python.exe"
else:
    PYTHON = VENV / "bin" / "python"

REQUIREMENTS = ROOT / "requirements.txt"
APP = ROOT / "app_gui.py"

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
        print("[BOOTSTRAP] Skipping CUDA install: cu121 wheels are not built for this Python version."
              " Install Python 3.12 x64 + CUDA Toolkit if you need GPU, or install a compatible wheel manually.")
        return
    try:
        run([
            str(PYTHON), "-m", "pip", "install",
            "--upgrade",
            "--force-reinstall",
            "torch", "torchvision", "torchaudio",
            "--index-url", "https://download.pytorch.org/whl/cu121"
        ])
    except subprocess.CalledProcessError as exc:
        print("[BOOTSTRAP] CUDA Torch install failed. Falling back to already installed torch (likely CPU).")
        print(f"[BOOTSTRAP] Error was: {exc}")

def relaunch_in_venv():
    print("[BOOTSTRAP] Relaunching app inside .venv")
    run([str(PYTHON), str(APP)])
    sys.exit(0)

def main():
    if not VENV.exists():
        create_venv()
        install_deps()
        install_cuda_torch()
        relaunch_in_venv()

    if not in_venv():
        relaunch_in_venv()

    # jsme ve venv → spusť GUI
    run([sys.executable, str(APP)])

if __name__ == "__main__":
    main()
