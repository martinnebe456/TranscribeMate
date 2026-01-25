"""EXE-first runtime bootstrap for TranscribeMate."""

from __future__ import annotations

import argparse
import platform
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path

from ..core.models import configure_model_environment
from ..core.paths import FROZEN, ensure_assets_on_path, log_path, user_assets_dir

FFMPEG_ZIP_URL = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip"
FFMPEG_EXES = ("ffmpeg.exe", "ffprobe.exe", "ffplay.exe")

LOG_FILE = log_path()


def _log(level: str, message: str):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{timestamp}] {level} {message}"
    print(line)
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
    try:
        LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
        LOG_FILE.write_text("", encoding="utf-8")
    except PermissionError:
        fallback = Path(tempfile.gettempdir()) / "TranscribeMate"
        fallback.mkdir(parents=True, exist_ok=True)
        LOG_FILE = fallback / "bootstrap.log"
        LOG_FILE.write_text("", encoding="utf-8")
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
        log_info("Fix: reinstall the app or run the EXE again to complete setup")


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
        splash.geometry("440x160")
        splash.resizable(False, False)
        splash.attributes("-topmost", True)
        splash.protocol("WM_DELETE_WINDOW", lambda: None)

        frame = ttk.Frame(splash, padding=16)
        frame.pack(fill="both", expand=True)

        ttk.Label(frame, text="TranscribeMate", font=("Segoe UI", 14, "bold")).pack(anchor="w")
        message_var = tk.StringVar(value="Starting...")
        ttk.Label(frame, textvariable=message_var).pack(anchor="w", pady=(8, 8))

        bar = ttk.Progressbar(frame, mode="indeterminate")
        bar.pack(fill="x")
        bar.start(12)

        splash.update_idletasks()
        splash.update()
        return splash, message_var
    except Exception:
        return None, None


def update_startup_splash(splash, message_var, message: str):
    if not splash or not message_var:
        return
    try:
        message_var.set(message)
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


def parse_args(argv: list[str] | None = None):
    parser = argparse.ArgumentParser(description="Launch TranscribeMate")
    parser.add_argument("--doctor", action="store_true", help="Only run environment checks")
    parser.add_argument("--no-ffmpeg-download", action="store_true", help="Do not auto-download FFmpeg")
    parser.add_argument("--no-cuda", action="store_true", help="Compatibility flag (no effect in EXE mode)")
    return parser.parse_args(argv)


def launch_app(argv: list[str] | None = None):
    args = parse_args(argv)
    init_log()

    log_info(f"Platform: {platform.platform()}")
    log_info(f"Frozen: {FROZEN}")

    configure_model_environment()
    ensure_assets_on_path()

    if args.doctor:
        run_doctor()
        return

    allow_ffmpeg_download = not args.no_ffmpeg_download

    splash, splash_msg = create_startup_splash()
    app = None
    try:
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
