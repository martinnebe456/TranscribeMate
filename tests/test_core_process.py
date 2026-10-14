import subprocess
import sys
from threading import Event, Thread
from time import monotonic

import pytest

from transcribemate.core.cancellation import OperationCancelledError
from transcribemate.core.process import safe_run


def test_safe_run_captures_stdout():
    lines = []

    def on_line(line: str):
        lines.append(line.strip())

    safe_run([sys.executable, "-c", "print('hi')"], on_line=on_line)
    assert "hi" in lines


def test_safe_run_raises_on_nonzero_exit():
    with pytest.raises(RuntimeError):
        safe_run([sys.executable, "-c", "import sys; sys.exit(2)"])


def test_safe_run_cancels_and_reaps_silent_child(monkeypatch):
    stop_flag = Event()
    processes = []
    original_popen = subprocess.Popen

    def capture_popen(*args, **kwargs):
        process = original_popen(*args, **kwargs)
        processes.append(process)
        return process

    monkeypatch.setattr("transcribemate.core.process.subprocess.Popen", capture_popen)
    outcome = []

    def run_silent_child():
        try:
            safe_run([sys.executable, "-c", "import time; time.sleep(30)"], stop_flag=stop_flag)
        except Exception as exc:  # captured for assertion in the test thread
            outcome.append(exc)

    worker = Thread(target=run_silent_child)
    worker.start()
    deadline = monotonic() + 2
    while not processes and monotonic() < deadline:
        Event().wait(0.01)
    assert processes, "child process did not start"

    stop_flag.set()
    worker.join(timeout=2)

    assert not worker.is_alive(), "silent subprocess blocked cancellation"
    assert len(outcome) == 1 and isinstance(outcome[0], OperationCancelledError)
    assert processes[0].poll() is not None, "cancelled child was not reaped"
