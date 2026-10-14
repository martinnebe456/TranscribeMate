"""Shared cancellation exception for long-running pipeline operations."""


class OperationCancelledError(RuntimeError):
    """Raised when a cooperative stop request interrupts an operation."""


def check_cancelled(stop_flag) -> None:
    if stop_flag is not None and stop_flag.is_set():
        raise OperationCancelledError("Stopped by user.")
