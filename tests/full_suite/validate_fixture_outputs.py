#!/usr/bin/env python3

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tests.full_suite.fixture_validation import validate_output_tree  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate generated transcript + summary outputs against fixture PDFs.")
    parser.add_argument("--output-root", required=True)
    parser.add_argument("--fixtures-dir", required=True)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--report-file", default="")
    args = parser.parse_args()

    report = validate_output_tree(
        output_root=Path(args.output_root),
        fixtures_dir=Path(args.fixtures_dir),
        manifest_path=Path(args.manifest),
    )
    payload = json.dumps(report, indent=2, ensure_ascii=False)
    if args.report_file:
        Path(args.report_file).write_text(payload + "\n", encoding="utf-8")
    print(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
