import sys

import pytest

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
