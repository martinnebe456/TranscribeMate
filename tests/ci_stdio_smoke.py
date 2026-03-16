import json
import subprocess
import sys


def main() -> int:
    proc = subprocess.Popen(
        [sys.executable, "-m", "transcribemate.v2.backend.server", "--stdio"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )

    def rpc(method: str, params=None, request_id: str = "1"):
        payload = {"id": request_id, "method": method, "params": params or {}}
        assert proc.stdin is not None
        assert proc.stdout is not None
        proc.stdin.write(json.dumps(payload) + "\n")
        proc.stdin.flush()
        line = proc.stdout.readline()
        if not line:
            raise RuntimeError("No response from backend process.")
        return json.loads(line)

    try:
        ping = rpc("ping", request_id="ping-1")
        if not ping.get("ok"):
            raise RuntimeError(f"Ping failed: {ping}")

        shutdown = rpc("shutdown", request_id="shutdown-1")
        if not shutdown.get("ok"):
            raise RuntimeError(f"Shutdown failed: {shutdown}")

        proc.wait(timeout=10)
        if proc.returncode != 0:
            stderr = proc.stderr.read() if proc.stderr else ""
            raise RuntimeError(f"Backend exited with {proc.returncode}: {stderr}")

        print("Backend stdio smoke test passed.")
        return 0
    finally:
        if proc.poll() is None:
            proc.kill()
            proc.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
