#!/usr/bin/env python3
"""Placeholder renderer for Phase 10 golden diff reports.

See docs/meta/phases/phase-10.md for the HTML contract that CI expects.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path
import json
import html
from datetime import datetime


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Render a golden diff report for Phase 10 artifacts."
    )
    parser.add_argument(
        "--input",
        required=True,
        type=Path,
        help="Directory containing reports/phase10/goldens/<timestamp> artifacts.",
    )
    parser.add_argument(
        "--output",
        required=True,
        type=Path,
        help="Destination HTML file (e.g. reports/phase10/goldens/<timestamp>/diff-report.html).",
    )
    return parser.parse_args()


def load_json(path: Path) -> dict | None:
    if path.exists():
        return json.loads(path.read_text())
    return None


def gather_runs(input_dir: Path) -> list[dict]:
    summary = load_json(input_dir / "pr-matrix-summary.json")
    if summary and summary.get("runs"):
        return summary["runs"]
    runs = []
    for current in sorted(input_dir.rglob("current.json")):
        data = json.loads(current.read_text())
        runs.append(
            {
                "mode": data.get("mode", current.parent.name),
                "budget_profile": data.get("budget_profile"),
                "status": data.get("status", "unknown"),
                "artifact": current.relative_to(input_dir).as_posix(),
            }
        )
    return runs


def render(manifest: dict, runs: list[dict]) -> str:
    generated_at = manifest.get("generated_at") or datetime.utcnow().isoformat() + "Z"
    budget_profiles = ", ".join(manifest.get("budget_profiles", [])) or "n/a"
    rows = "\n".join(
        f"<tr><td>{html.escape(run['mode'] or '-')}</td>"
        f"<td>{html.escape(run.get('budget_profile') or '-')}</td>"
        f"<td>{html.escape(run.get('status') or '-')}</td>"
        f"<td><code>{html.escape(run.get('artifact') or '-')}</code></td></tr>"
        for run in runs
    )
    if not rows:
        rows = '<tr><td colspan="4">No runs recorded.</td></tr>'
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Phase 10 Golden Report</title>
  <style>
    body {{ font-family: system-ui, -apple-system, BlinkMacSystemFont, sans-serif; margin: 24px; }}
    table {{ border-collapse: collapse; margin-top: 16px; width: 100%; }}
    th, td {{ border: 1px solid #ccc; padding: 6px 10px; text-align: left; }}
    th {{ background: #f8f8f8; }}
  </style>
</head>
<body>
  <h1>Phase 10 Golden Report</h1>
  <p><strong>Generated:</strong> {html.escape(generated_at)}</p>
  <p><strong>Template ver:</strong> {html.escape(str(manifest.get("template_ver", "n/a")))}</p>
  <p><strong>SSOT manifest hash:</strong> {html.escape(manifest.get("ssot_manifest_hash", "n/a"))}</p>
  <p><strong>Budget profiles:</strong> {html.escape(budget_profiles)}</p>
  <table>
    <thead>
      <tr><th>Mode</th><th>Budget profile</th><th>Status</th><th>Artifact</th></tr>
    </thead>
    <tbody>
      {rows}
    </tbody>
  </table>
</body>
</html>
"""


def main() -> int:
    args = parse_args()
    if not args.input.exists():
        raise SystemExit(f"Input directory not found: {args.input}")
    manifest = load_json(args.input / "manifest.json") or {}
    runs = gather_runs(args.input)
    html_output = render(manifest, runs)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(html_output, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
