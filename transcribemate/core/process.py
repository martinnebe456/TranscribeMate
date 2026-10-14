"""Subprocess execution helpers."""

from __future__ import annotations

import re
from queue import Queue, Empty
import subprocess
from threading import Thread
from typing import Callable, List, Optional

from .cancellation import check_cancelled

YTDLP_PCT = re.compile(r"(\d{1,3}\.\d)%")


def safe_run(cmd: List[str], cwd: Optional[str] = None, on_line: Optional[Callable[[str], None]] = None,
             stop_flag=None, env=None):
    check_cancelled(stop_flag)
    process = subprocess.Popen(
        cmd,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env=env,
    )
    lines: Queue[str | None] = Queue()

    def _read_output():
        try:
            for output_line in process.stdout:
                lines.put(output_line)
        finally:
            lines.put(None)

    reader = Thread(target=_read_output, daemon=True, name="tm-process-output")
    reader.start()
    output_finished = False
    try:
        while not output_finished:
            check_cancelled(stop_flag)
            try:
                line = lines.get(timeout=0.1)
            except Empty:
                continue
            if line is None:
                output_finished = True
            elif on_line:
                on_line(line)

        rc = process.wait()
        check_cancelled(stop_flag)
        if rc != 0:
            raise RuntimeError(f"Command failed (code {rc}): {' '.join(cmd)}")
    finally:
        if process.poll() is None:
            try:
                process.terminate()
            except ProcessLookupError:
                pass
            try:
                process.wait(timeout=1)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if process.stdout:
            process.stdout.close()
