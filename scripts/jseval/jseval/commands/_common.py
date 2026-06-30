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


def assert_run_capabilities(base_url, modes, *, cross_encoder=False, allow_degraded=False):
    """Tempdoc 644 Axis 2: fail closed when the intended cross-encoder is not realized.

    Derives the intended engine(s) from the run's modes + flags (scoped to the reranker — the
    documented silent-off trap with a startup-stable signal; see
    ``preflight.derive_intended_engines``), reads the realized set from ``/api/status``, prints
    any warnings, and ``sys.exit(1)`` on an un-overridden refusal. Shared by the run + corpus
    commands. A no-op when nothing is intended (e.g. a pure lexical leg run, or a fidelity run
    without a hybrid mode).
    """
    import sys

    from .. import preflight as _preflight

    intended = _preflight.derive_intended_engines(modes, cross_encoder=cross_encoder)
    if not intended:
        return
    verdict = _preflight.assert_capabilities(base_url, intended, allow_degraded=allow_degraded)
    for warning in verdict["warnings"]:
        click.echo(f"Capability warning: {warning}", err=True)
    if not verdict["ok"]:
        for refusal in verdict["refusals"]:
            click.echo(f"Capability refusal: {refusal}", err=True)
        sys.exit(1)


def _write_bench_output(result: dict, output_dir: str | None, filename: str) -> None:
    """Write benchmark result JSON if output_dir is specified."""
    if not output_dir:
        return
    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)
    path = out / filename
    path.write_text(json.dumps(result, indent=2, default=str), encoding="utf-8")
    click.echo(f"Written to {path}")
