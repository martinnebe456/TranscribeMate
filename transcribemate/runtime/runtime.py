"""EXE-first runtime bootstrap for TranscribeMate."""

from __future__ import annotations

import argparse
import queue
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

from ..core.config import load_config
from ..core.models import configure_model_environment
from ..core.paths import (
    FROZEN,
    ensure_assets_on_path,
    ensure_site_packages_on_path,
    legacy_log_path,
    log_path,
    project_root,
    user_assets_dir,
    user_site_packages_dir,
)

FFMPEG_ZIP_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
FFMPEG_EXES = ("ffmpeg.exe", "ffprobe.exe", "ffplay.exe")
TORCH_CUDA_EXTRA_INDEX = "https://download.pytorch.org/whl/cu121"
TORCH_PYPI_INDEX = "https://pypi.org/simple"
# Pin to a known-good version that matches the previous installer behavior.
TORCH_VERSION = "2.5.1"
TORCH_CUDA_VERSION = "2.5.1+cu121"

LOG_FILE = log_path()
_SPLASH_UPDATES: "queue.Queue[str]" = queue.Queue()


def _log(level: str, message: str):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{timestamp}] {level} {message}"
    try:
        print(line)
    except Exception:
        try:
            sys.stdout.buffer.write((line + "\n").encode("utf-8", errors="replace"))
        except Exception:
            pass
    try:
        with LOG_FILE.open("a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    except Exception:
        pass


def log_step(message: str):
    _log("STEP", f"➤ {message}")


def log_info(message: str):
    _log("INFO", message)


def log_success(message: str):
    _log("OK", f"✅ {message}")


def log_warn(message: str):
    _log("WARN", f"⚠️ {message}")


def log_fail(message: str):
    _log("FAIL", f"❌ {message}")


def init_log():
    global LOG_FILE
    session_header = f"--- Session start {datetime.now().strftime('%Y-%m-%d %H:%M:%S')} ---"
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        legacy = legacy_log_path()
        if legacy.exists() and not LOG_FILE.exists():
            try:
                shutil.copy2(legacy, LOG_FILE)
            except Exception:
                pass
        with LOG_FILE.open("a", encoding="utf-8") as fh:
            fh.write("\n" + session_header + "\n")
    except PermissionError:
        fallback = Path(tempfile.gettempdir()) / "TranscribeMate"
        fallback.mkdir(parents=True, exist_ok=True)
        LOG_FILE = fallback / "runtime.log"
        with LOG_FILE.open("a", encoding="utf-8") as fh:
            fh.write("\n" + session_header + "\n")
    log_info(f"Logging to: {LOG_FILE}")


def run_capture(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    cmd_str = [str(part) for part in cmd]
    log_info(f"Command: {' '.join(cmd_str)}")
    return subprocess.run(cmd_str, text=True, capture_output=True, check=False)


def _has_system_ffmpeg() -> bool:
    return bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


def _has_assets_ffmpeg() -> bool:
    assets = user_assets_dir()
    return all((assets / name).exists() for name in ("ffmpeg.exe", "ffprobe.exe"))


def _extract_ffmpeg_from_zip(zip_path: Path):
    assets = user_assets_dir()
    with zipfile.ZipFile(zip_path) as zf:
        names = zf.namelist()
        for exe_name in FFMPEG_EXES:
            member = next((n for n in names if n.endswith(f"/bin/{exe_name}")), None)
            if not member:
                raise RuntimeError(f"Could not find {exe_name} in FFmpeg zip.")
            target = assets / exe_name
            with zf.open(member) as src, target.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            log_success(f"Saved: {target}")


def _ffmpeg_version_cmd() -> list[str]:
    assets = user_assets_dir()
    ffmpeg_local = assets / "ffmpeg.exe"
    if ffmpeg_local.exists():
        return [str(ffmpeg_local), "-version"]
    return ["ffmpeg", "-version"]


def verify_ffmpeg():
    try:
        result = run_capture(_ffmpeg_version_cmd())
        if result.stdout:
            first_line = result.stdout.splitlines()[0].strip()
            log_success(f"ffmpeg detected: {first_line}")
        if result.returncode != 0 and result.stderr:
            log_warn(result.stderr.strip())
    except Exception as exc:  # pragma: no cover - best effort
        log_warn(f"Could not verify ffmpeg: {exc}")


def ensure_ffmpeg_assets(*, allow_download: bool, splash=None, splash_msg=None):
    log_step("Checking FFmpeg availability")

    if _has_system_ffmpeg():
        log_success("FFmpeg found in PATH")
        verify_ffmpeg()
        return

    if _has_assets_ffmpeg():
        log_success("FFmpeg found in user assets")
        verify_ffmpeg()
        return

    log_warn("FFmpeg not found in PATH or assets/")

    if not allow_download:
        log_warn("Skipping FFmpeg download because --no-ffmpeg-download was used")
        return

    if sys.platform != "win32":
        raise SystemExit("FFmpeg not found. Please install ffmpeg/ffprobe and add them to PATH.")

    update_startup_splash(splash, splash_msg, "Downloading FFmpeg bundle...")
    assets = user_assets_dir()
    assets.mkdir(parents=True, exist_ok=True)

    with tempfile.TemporaryDirectory(prefix="tm_ffmpeg_") as tmp:
        tmp_dir = Path(tmp)
        zip_path = tmp_dir / "ffmpeg.zip"
        urllib.request.urlretrieve(FFMPEG_ZIP_URL, zip_path)
        _extract_ffmpeg_from_zip(zip_path)

    ensure_assets_on_path()
    verify_ffmpeg()


def _doctor_check_ffmpeg():
    if _has_system_ffmpeg() or _has_assets_ffmpeg():
        log_success("FFmpeg available")
        verify_ffmpeg()
    else:
        log_fail("FFmpeg not found (PATH or assets/)")
        log_info("Fix: run the EXE once without --doctor to download FFmpeg automatically")


def _doctor_check_torch():
    try:
        import torch

        log_success(f"Torch version: {torch.__version__}")
        if torch.cuda.is_available():
            log_success(f"CUDA available: {torch.cuda.get_device_name(0)}")
        else:
            log_warn("CUDA not available (CPU mode will be used)")
    except Exception as exc:
        log_fail(f"Torch not ready: {exc}")
        log_info("Fix: run the EXE once without --doctor (auto-setup) or use --install-gpu")


def run_doctor():
    log_step("Running doctor checks")
    _doctor_check_ffmpeg()
    _doctor_check_torch()
    log_success("Doctor finished")


def create_startup_splash():
    if not FROZEN:
        return None, None
    try:
        import tkinter as tk
        from tkinter import ttk

        splash = tk.Tk()
        splash.title("TranscribeMate")
        splash.geometry("520x180")
        splash.resizable(False, False)
        splash.attributes("-topmost", True)
        splash.protocol("WM_DELETE_WINDOW", lambda: None)

        frame = ttk.Frame(splash, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="TranscribeMate", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        message_var = tk.StringVar(value="Starting...")
        ttk.Label(frame, textvariable=message_var, wraplength=480).pack(anchor="w", pady=(8, 8))

        bar = ttk.Progressbar(frame, mode="indeterminate")
        bar.pack(fill="x")
        bar.start(12)

        splash.update_idletasks()
        splash.update()
        return splash, message_var
    except Exception:
        return None, None


def _drain_splash_updates():
    try:
        while True:
            _SPLASH_UPDATES.get_nowait()
    except queue.Empty:
        pass


def queue_startup_splash(message: str):
    if not message:
        return
    try:
        _SPLASH_UPDATES.put_nowait(message)
    except Exception:
        pass


def update_startup_splash(splash, message_var, message: str):
    if not splash or not message_var:
        return
    try:
        _drain_splash_updates()
        message_var.set(message)
        splash.update_idletasks()
        splash.update()
    except Exception:
        pass


def pump_startup_splash(splash, message_var=None):
    if not splash:
        return
    try:
        latest = None
        if message_var:
            try:
                while True:
                    latest = _SPLASH_UPDATES.get_nowait()
            except queue.Empty:
                pass
            if latest:
                message_var.set(latest)
        splash.update_idletasks()
        splash.update()
    except Exception:
        pass


def close_startup_splash(splash):
    if not splash:
        return
    try:
        splash.destroy()
    except Exception:
        pass


def show_error_dialog(message: str):
    if not FROZEN:
        return
    try:
        import tkinter as tk
        from tkinter import messagebox

        root = tk.Tk()
        root.withdraw()
        messagebox.showerror("TranscribeMate", message)
        root.destroy()
    except Exception:
        pass


def _pip_main(args: list[str], *, splash=None, splash_msg=None) -> int:
    try:
        from pip._internal.cli.main import main as pip_main
    except Exception as exc:  # pragma: no cover - environment issue
        log_fail(f"pip is not available in the bundled app: {exc}")
        return 1

    class _LogRedirect:
        def __init__(self):
            self._last_ui_update = 0.0

        def write(self, data: str):
            text = data.strip()
            if not text:
                return None
            # Avoid duplicating our own structured log lines when print() is redirected.
            if text.startswith("[") and any(tag in text for tag in (" INFO ", " OK ", " WARN ", " FAIL ", " STEP ")):
                return None
            timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            line = f"[{timestamp}] PIP {text}"
            try:
                with LOG_FILE.open("a", encoding="utf-8") as fh:
                    fh.write(line + "\n")
            except Exception:
                pass

            if splash and splash_msg:
                now = time.time()
                if now - self._last_ui_update >= 0.12:
                    ui_text = text
                    if len(ui_text) > 120:
                        ui_text = ui_text[:117] + "..."
                    queue_startup_splash(f"pip: {ui_text}")
                    self._last_ui_update = now
            return None

        def flush(self):
            return None

    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout = _LogRedirect()
    sys.stderr = _LogRedirect()
    try:
        return int(pip_main(args))
    finally:
        sys.stdout = old_out
        sys.stderr = old_err


def _has_nvidia_gpu() -> bool:
    smi = shutil.which("nvidia-smi")
    if not smi and sys.platform == "win32":
        candidates = [
            Path("C:/Windows/System32/nvidia-smi.exe"),
            Path("C:/Program Files/NVIDIA Corporation/NVSMI/nvidia-smi.exe"),
        ]
        for candidate in candidates:
            if candidate.exists():
                smi = str(candidate)
                break
    if not smi:
        return False
    try:
        result = subprocess.run([smi, "-L"], capture_output=True, text=True, check=False)
        return result.returncode == 0 and bool(result.stdout.strip())
    except Exception:
        return False


def _remove_tree(path: Path, label: str):
    try:
        if path.is_dir():
            shutil.rmtree(path, ignore_errors=False)
        elif path.exists():
            path.unlink(missing_ok=True)
        else:
            return
        log_info(f"Removed stale {label}: {path}")
    except Exception as exc:
        log_warn(f"Could not remove stale {label} at {path}: {exc}")


def _cleanup_stale_torch_metadata():
    """Remove stale torch/torchaudio/torchvision bundles from older builds."""
    internal_dir = project_root() / "_internal"
    metadata_patterns = (
        "torch-*.dist-info",
        "torchaudio-*.dist-info",
        "torchvision-*.dist-info",
    )
    package_names = (
        "torch",
        "torchgen",
        "functorch",
        "torchaudio",
        "torchvision",
    )
    if internal_dir.exists():
        for pattern in metadata_patterns:
            for entry in internal_dir.glob(pattern):
                _remove_tree(entry, "torch metadata")
        for name in package_names:
            for entry in internal_dir.glob(name):
                _remove_tree(entry, "bundled torch package")

    site_dir = user_site_packages_dir()
    cleanup_patterns = (
        "torchaudio*",
        "torchvision*",
    )
    for pattern in cleanup_patterns:
        for entry in site_dir.glob(pattern):
            _remove_tree(entry, "unused torch package")


def _torch_version_from_metadata(site_dir: Path) -> str:
    versions: list[str] = []
    for dist in site_dir.glob("torch-*.dist-info"):
        name = dist.name
        if not name.startswith("torch-") or not name.endswith(".dist-info"):
            continue
        versions.append(name[len("torch-") : -len(".dist-info")])
    if not versions:
        return ""
    versions.sort()
    return versions[-1]


def _torch_version_from_package(site_dir: Path) -> str:
    version_file = site_dir / "torch" / "version.py"
    if not version_file.exists():
        return ""
    try:
        data = version_file.read_text(encoding="utf-8", errors="ignore")
        match = re.search(r'__version__\s*=\s*[\'\"]([^\'\"]+)[\'\"]', data)
        if match:
            return match.group(1).strip()
    except Exception as exc:
        log_warn(f"Could not read torch version from package: {exc}")
    return ""


def _torch_version_detected(site_dir: Path) -> str:
    return _torch_version_from_metadata(site_dir) or _torch_version_from_package(site_dir)


def _torch_metadata_ready(site_dir: Path, desired_version: str) -> bool:
    version = _torch_version_detected(site_dir)
    torch_dir = site_dir / "torch"
    return torch_dir.exists() and bool(version) and version == desired_version


def _cleanup_user_torch_install(site_dir: Path):
    """Remove old torch installs from the per-user site-packages before reinstalling."""
    for pattern in ("torch", "torchgen", "functorch", "torch-*.dist-info", "functorch-*.dist-info"):
        for entry in site_dir.glob(pattern):
            _remove_tree(entry, "torch install")


def _torch_status(*, verbose: bool = False) -> tuple[bool, bool, str]:
    try:
        import torch

        cuda_ready = bool(torch.cuda.is_available())
        version = getattr(torch, "__version__", "unknown")
        return True, cuda_ready, str(version)
    except Exception as exc:
        if verbose:
            log_fail(f"Torch import failed: {type(exc).__name__}: {exc}")
            try:
                site_dir = user_site_packages_dir()
                torch_dir = site_dir / "torch"
                log_info(f"Torch directory present: {torch_dir.exists()} ({torch_dir})")
            except Exception:
                pass
            tb_lines = traceback.format_exc().splitlines()
            for line in tb_lines[:12]:
                log_info(f"TRACE {line}")
        return False, False, ""


def _run_with_splash(message: str, splash, splash_msg, fn) -> bool:
    if not splash:
        try:
            fn()
            return True
        except Exception as exc:
            log_fail(str(exc))
            return False

    error: list[Exception] = []

    def runner():
        try:
            fn()
        except Exception as exc:  # pragma: no cover - best effort
            error.append(exc)

    update_startup_splash(splash, splash_msg, message)
    thread = threading.Thread(target=runner, daemon=True)
    thread.start()
    while thread.is_alive():
        pump_startup_splash(splash, splash_msg)
        time.sleep(0.2)
    update_startup_splash(splash, splash_msg, "Finalizing...")

    if error:
        log_fail(str(error[0]))
        return False
    return True


def install_torch(*, prefer_gpu: bool, splash=None, splash_msg=None) -> bool:
    ensure_site_packages_on_path()
    site_dir = user_site_packages_dir()
    log_step("Installing PyTorch")
    log_info(f"Target site-packages: {site_dir}")

    has_gpu = _has_nvidia_gpu()
    want_gpu = prefer_gpu and has_gpu
    if prefer_gpu and not has_gpu:
        log_warn("NVIDIA GPU not detected via nvidia-smi. Falling back to CPU torch.")

    desired_version = TORCH_CUDA_VERSION if want_gpu else TORCH_VERSION
    if _torch_metadata_ready(site_dir, desired_version):
        log_success(f"Torch already installed: {desired_version} (metadata)")
        return True

    existing_version = _torch_version_detected(site_dir)
    if existing_version:
        log_info(f"Existing torch version detected: {existing_version} -> reinstalling {desired_version}")

    base_args_common = [
        "install",
        "--upgrade",
        "--force-reinstall",
        "--no-warn-script-location",
        "--prefer-binary",
        "--target",
        str(site_dir),
    ]

    def install_gpu():
        log_step("Installing torch with CUDA support (cu121)")
        _cleanup_user_torch_install(site_dir)
        args = base_args_common + [
            "--index-url",
            TORCH_CUDA_EXTRA_INDEX,
            "--extra-index-url",
            TORCH_PYPI_INDEX,
            f"torch=={TORCH_CUDA_VERSION}",
        ]
        code = _pip_main(args, splash=splash, splash_msg=splash_msg)
        if code != 0:
            raise RuntimeError(f"GPU torch install failed with exit code {code}")

    def install_cpu():
        log_step("Installing CPU torch")
        _cleanup_user_torch_install(site_dir)
        args = base_args_common + [
            "--index-url",
            TORCH_PYPI_INDEX,
            f"torch=={TORCH_VERSION}",
        ]
        code = _pip_main(args, splash=splash, splash_msg=splash_msg)
        if code != 0:
            raise RuntimeError(f"CPU torch install failed with exit code {code}")

    gpu_ok = False
    if want_gpu:
        gpu_ok = _run_with_splash(
            "Installing GPU dependencies (this can take several minutes)...",
            splash,
            splash_msg,
            install_gpu,
        )
        installed_version = _torch_version_detected(site_dir)
        if not gpu_ok or installed_version != TORCH_CUDA_VERSION:
            log_warn("GPU torch installation did not produce the expected CUDA build. Trying CPU build instead.")
            gpu_ok = False
        else:
            gpu_ok = True

    if not gpu_ok:
        ok = _run_with_splash(
            "Installing CPU dependencies (fallback)...",
            splash,
            splash_msg,
            install_cpu,
        )
        if not ok:
            log_fail("Torch installation did not complete successfully.")
            return False

    ensure_site_packages_on_path()
    installed, cuda_ready, version = _torch_status(verbose=True)
    if installed:
        status = "CUDA ready" if cuda_ready else "CPU ready"
        log_success(f"Torch installed: {version} ({status})")
        return True

    log_fail("Torch installation did not complete successfully.")
    log_info(f"Try closing the app and deleting: {site_dir / 'torch'}")
    return False


def ensure_torch_ready(*, splash=None, splash_msg=None) -> bool:
    """Ensure torch is installed and CUDA-ready when requested."""
    log_step("Checking PyTorch availability")

    cfg = load_config()
    gpu_requested = bool(getattr(cfg, "use_gpu", True))
    has_gpu = _has_nvidia_gpu()
    prefer_gpu = gpu_requested and has_gpu

    if gpu_requested and not has_gpu:
        log_warn("GPU is enabled in settings but no NVIDIA GPU was detected (nvidia-smi).")

    site_dir = user_site_packages_dir()
    desired_version = TORCH_CUDA_VERSION if prefer_gpu else TORCH_VERSION
    detected_version = _torch_version_detected(site_dir)
    if detected_version:
        log_info(f"Detected torch version: {detected_version}")
    needs_install = not _torch_metadata_ready(site_dir, desired_version)

    if needs_install:
        update_startup_splash(
            splash,
            splash_msg,
            "Preparing PyTorch (first run may take several minutes)...",
        )
        ok = install_torch(prefer_gpu=prefer_gpu, splash=splash, splash_msg=splash_msg)
        return ok

    installed, cuda_ready, version = _torch_status(verbose=True)
    if installed:
        if prefer_gpu and not cuda_ready:
            log_warn("CUDA build is installed but CUDA is not available. The app will run on CPU.")
        status = "CUDA ready" if cuda_ready else "CPU ready"
        log_success(f"Torch ready: {version} ({status})")
        return True

    log_fail("Torch appears installed but could not be imported.")
    log_info(f"Fix: close the app and delete: {site_dir / 'torch'}")
    return False


def parse_args(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description="Launch TranscribeMate")
    parser.add_argument("--doctor", action="store_true", help="Only run environment checks")
    parser.add_argument("--no-ffmpeg-download", action="store_true", help="Do not auto-download FFmpeg")
    parser.add_argument("--no-cuda", action="store_true", help="Compatibility flag (no effect in EXE mode)")
    parser.add_argument("--install-gpu", action="store_true", help="Install/upgrade torch with CUDA support")
    parser.add_argument("--install-cpu", action="store_true", help=argparse.SUPPRESS)
    return parser.parse_args(argv)


def launch_app(argv: list[str] | None = None):
    args = parse_args(argv)
    init_log()

    log_info(f"Platform: {platform.platform()}")
    log_info(f"Frozen: {FROZEN}")

    configure_model_environment()
    ensure_assets_on_path()
    ensure_site_packages_on_path()
    _cleanup_stale_torch_metadata()

    if args.install_gpu or args.install_cpu:
        splash, splash_msg = create_startup_splash()
        try:
            message = (
                "Preparing GPU dependency setup..." if args.install_gpu else "Preparing dependency setup..."
            )
            update_startup_splash(splash, splash_msg, message)
            ok = install_torch(prefer_gpu=args.install_gpu, splash=splash, splash_msg=splash_msg)
            update_startup_splash(splash, splash_msg, "Dependency setup complete." if ok else "Dependency setup failed.")
            time.sleep(0.6)
        finally:
            close_startup_splash(splash)

        if not ok:
            show_error_dialog("GPU setup failed. Please run the installer again or check runtime.log.")
        return

    if args.doctor:
        run_doctor()
        return

    allow_ffmpeg_download = not args.no_ffmpeg_download

    splash, splash_msg = create_startup_splash()
    app = None
    try:
        update_startup_splash(splash, splash_msg, "Checking PyTorch dependencies...")
        torch_ok = ensure_torch_ready(splash=splash, splash_msg=splash_msg)
        if not torch_ok:
            update_startup_splash(
                splash,
                splash_msg,
                "PyTorch setup failed. Launching anyway (GPU/translation may be limited)...",
            )
            time.sleep(0.8)

        update_startup_splash(splash, splash_msg, "Preparing FFmpeg (first run may take a moment)...")
        ensure_ffmpeg_assets(allow_download=allow_ffmpeg_download, splash=splash, splash_msg=splash_msg)
        update_startup_splash(splash, splash_msg, "Loading application...")
        from ..ui import App

        update_startup_splash(splash, splash_msg, "Opening window...")
        close_startup_splash(splash)
        splash = None
        app = App()
    finally:
        close_startup_splash(splash)

    if app:
        log_step("Launching TranscribeMate GUI")
        app.mainloop()


__all__ = ["launch_app"]
