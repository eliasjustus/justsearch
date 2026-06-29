"""Shared constants + cross-group helpers for jseval CLI commands (tempdoc 645)."""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path

import click

log = logging.getLogger(__name__)


# Two distinct base URLs — NOT a dedupe target. `_DEFAULT_BASE_URL` honors
# JUSTSEARCH_API_PORT (connecting to an already-running main API), while
# `_DEFAULT_BASE_URL_EVAL` is hardcoded 33221 because the eval backend
# (`backend.start_backend`, `backend.py:19,37`) binds 33221 hardcoded,
# ignoring the env var. Merging either direction reintroduces the
# tempdoc-635-D3 port-divergence bug, so they stay separate by design.
_DEFAULT_BASE_URL = f"http://127.0.0.1:{os.environ.get('JUSTSEARCH_API_PORT', '33221')}"

_DEFAULT_BASE_URL_EVAL = "http://127.0.0.1:33221"


def _write_bench_output(result: dict, output_dir: str | None, filename: str) -> None:
    """Write benchmark result JSON if output_dir is specified."""
    if not output_dir:
        return
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / filename
    path.write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    click.echo(f"Written to {path}")
