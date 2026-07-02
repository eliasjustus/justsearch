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


def _attach_revision(record: dict | None, supersedes: str | None, revision_reason: str | None) -> None:
    """Attach a `revision` block to a composed record when a CLI caller passes BOTH
    --supersedes and --revision-reason (tempdoc 624 Design 1 CLI wiring, shared by
    utility-compose / utility-judge / utility-compose-cross-corpus).

    Requires both flags together, or neither -- a caller giving only one gets a
    clear `click.BadParameter`. Giving neither is a no-op: `record` is left exactly
    as `compose_utility`/`compose_utility_cross_corpus` produced it, no `revision`
    key at all, matching today's (pre-flag) behavior.

    ``record=None`` validates the both-or-neither contract WITHOUT attaching
    anything -- call this at the top of a command (before any expensive work:
    reading run files, replaying Inspect logs) so a mismatched pair fails fast
    instead of silently doing nothing. Call again with the real ``record`` once
    it exists to actually attach the block.

    `changed_fields` is always empty at the CLI level: the caller isn't expected to
    know exactly which fields changed when composing from the command line, so an
    empty list is a deliberate, honest default -- not a corner-cutting shortcut.
    """
    if bool(supersedes) != bool(revision_reason):
        raise click.BadParameter(
            "--supersedes and --revision-reason must be given together, or neither")
    if record is not None and supersedes and revision_reason:
        from .. import utility_comparison as uc

        record["revision"] = uc.build_revision(supersedes, revision_reason, changed_fields=[])
