"""Subprocess execution helpers."""

from __future__ import annotations

import re
import subprocess
from typing import Callable, List, Optional

YTDLP_PCT = re.compile(r"(\d{1,3}\.\d)%")


def safe_run(cmd: List[str], cwd: Optional[str] = None, on_line: Optional[Callable[[str], None]] = None,
             stop_flag=None, env=None):
    process = subprocess.Popen(
        cmd,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=env,
    )
    try:
        while True:
            if stop_flag and stop_flag.is_set():
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                raise RuntimeError("Stopped by user.")

            line = process.stdout.readline()
            if not line and process.poll() is not None:
                break
            if line and on_line:
                on_line(line)

        rc = process.wait()
        if rc != 0:
            raise RuntimeError(f"Command failed (code {rc}): {' '.join(cmd)}")
    finally:
        if process.stdout:
            process.stdout.close()
        if process.poll() is None:
            process.terminate()
