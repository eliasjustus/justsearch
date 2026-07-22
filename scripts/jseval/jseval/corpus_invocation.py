"""Always-on invocation recording for corpus-materialization commands (tempdoc 767 §R.4-1).

During the 707 full-cohort certification, the exact ``corpus-inject-real`` invocations that
materialized each cell "were not recorded anywhere" (§R.4 deviation 1) — the certifying agent had
to RECONSTRUCT them from recipes and verify byte-identity after the fact. This module closes that
gap: every materialization command (``corpus-inject-real`` and the ``corpus-fetch-*`` family)
appends a machine-readable invocation record next to its output, so the command that produced a
materialized corpus is legible from the artifact itself rather than reconstructed.

The record lives at ``<output-dir>/invocations.v1.jsonl`` — one JSON object per line, appended on
every invocation. The output dir is the materialized dataset dir (``datasets/mixed/<name>/``), so
the record travels with the artifact it describes. ``datasets/`` is gitignored (every corpus is
fetch-fresh-never-commit here), so the record is local runtime provenance, not a committed
artifact — appending accumulates the local materialization history for that cell.

Recording is purely additive observability: it never changes a command's behavior, outputs, or
digests, and a failure to write the record is a WARN, not an error (a materialization must not
fail because its provenance sidecar could not be written).

Digests are REUSED from what the command already computed (host-pool ``real_source_sha256``,
assembled digest, output ``corpus_signature``) — nothing multi-GB is re-hashed solely for the
record. Where a digest is not computed by the command (the ``corpus-fetch-*`` family fetches
fresh and records a seed+source recipe rather than an input-pool sha), it is simply absent and
``digests_note`` names what is not captured.
"""

from __future__ import annotations

import json
import logging
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

from jseval.manifest import _git_sha_full

log = logging.getLogger(__name__)

SCHEMA = "707-corpus-invocation.v1"
FILENAME = "invocations.v1.jsonl"


def _git_dirty() -> bool | None:
    """Best-effort working-tree dirtiness; None if git is unavailable.

    A materialization from a dirty tree is precisely the reproducibility concern this record
    exists to surface, so it is worth the one cheap `git status` — but never at the cost of the
    command, hence the swallow-and-return-None.
    """
    try:
        result = subprocess.run(
            ["git", "status", "--porcelain"],
            capture_output=True, text=True, timeout=5, check=False,
        )
        if result.returncode == 0:
            return bool(result.stdout.strip())
    except Exception as exc:  # pragma: no cover - defensive
        log.debug("git status failed: %s", exc)
    return None


def build_record(
    *,
    command: str,
    params: dict,
    seeds: list,
    input_digests: dict | None = None,
    output_digests: dict | None = None,
    digests_note: str | None = None,
    output_dir: str | Path,
) -> dict:
    """Assemble the invocation record (no IO) — the unit-testable core."""
    record = {
        "schema": SCHEMA,
        "command": command,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "git_sha": _git_sha_full(),
        "git_dirty": _git_dirty(),
        "cwd": str(Path.cwd()),
        # The active jseval package dir — disambiguates which worktree copy ran (the
        # cross-checkout hazard `_paths` guards against), recorded verbatim.
        "jseval_package_path": str(Path(__file__).resolve().parent),
        "argv": list(sys.argv),
        "params": _jsonable(params),
        "seeds": list(seeds),
        "input_digests": input_digests or {},
        "output_digests": output_digests or {},
        "output_dir": str(output_dir),
    }
    if digests_note:
        record["digests_note"] = digests_note
    return record


def _jsonable(value):
    """Coerce click param values (e.g. Path) to JSON-serializable forms."""
    if isinstance(value, dict):
        return {k: _jsonable(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_jsonable(v) for v in value]
    if isinstance(value, Path):
        return str(value)
    return value


def record_invocation(
    output_dir: str | Path,
    *,
    command: str,
    params: dict,
    seeds: list,
    input_digests: dict | None = None,
    output_digests: dict | None = None,
    digests_note: str | None = None,
) -> Path | None:
    """Append one invocation record to ``<output_dir>/invocations.v1.jsonl``.

    Returns the sidecar path on success, or None on any failure (logged as WARN). Never raises —
    a materialization must not fail because its provenance record could not be written.
    """
    try:
        out = Path(output_dir)
        out.mkdir(parents=True, exist_ok=True)
        record = build_record(
            command=command, params=params, seeds=seeds,
            input_digests=input_digests, output_digests=output_digests,
            digests_note=digests_note, output_dir=out,
        )
        sidecar = out / FILENAME
        # newline="\n": keep the sidecar LF-only for stable diffs/hashes across platforms,
        # matching the corpus-commitment/recipe writers in this tree.
        with sidecar.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
        return sidecar
    except Exception as exc:
        log.warning("failed to write corpus invocation record to %s: %s", output_dir, exc)
        return None
