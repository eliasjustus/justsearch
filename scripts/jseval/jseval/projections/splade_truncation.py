"""SPLADE truncation-evidence projection (tempdoc 916 chunk sweep).

Normalizes the sidecar JSON the Worker writes from
``SpladeTruncationEvidence.snapshot(...)``
(``modules/worker-core/src/main/java/io/justsearch/indexerworker/splade/
SpladeTruncationEvidence.java``) into the snake_case record the eval side reads.

The sidecar only exists when an operator names a path: the Worker resolves it
from ``ConfigStore.global().get().ai().splade().evidencePath()``, whose config
default is ``null`` (``ResolvedConfigBuilder.java:1322``). The config key is
``justsearch.splade.evidence_path`` / env ``JUSTSEARCH_SPLADE_EVIDENCE_PATH``
(``EnvRegistry.java:609``). **Unset is the normal state**, so this projection
fails soft: no path, or a path that does not exist, yields an
empty-but-well-shaped document carrying a ``reason``. A projection that raised
there would fire on every run that never asked for the evidence.

Path discovery, in order:

1. ``summary.json`` -> ``env_overrides["JUSTSEARCH_SPLADE_EVIDENCE_PATH"]`` — the
   jseval ``--config`` ``env:`` block lands there (``jseval/run.py:583``), which
   is how a sweep driver sets it per arm.
2. the projection process's own environment.
3. ``<run_dir>/splade_truncation_evidence.json`` — the convention for a driver
   that parks the sidecar beside the run's other artifacts.

Source field names are matched verbatim against ``snapshot()``; the histograms
(``derivedWindowCountHistogram``, ``tokenCountBuckets``) are deliberately not
carried — this record is the small one the roll-up table needs.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path

from .base import Projection

log = logging.getLogger(__name__)

PROJECTION_NAME = "splade_truncation"
SCHEMA_VERSION = 1
EVIDENCE_ENV_VAR = "JUSTSEARCH_SPLADE_EVIDENCE_PATH"
DEFAULT_SIDECAR_NAME = "splade_truncation_evidence.json"

#: snapshot() field -> normalized field. Verbatim against SpladeTruncationEvidence.java.
_FIELD_MAP = (
    ("documentsEncoded", "documents_encoded"),
    ("documentsTruncated", "documents_truncated"),
    ("truncationRate", "truncation_rate"),
    ("maxObservedTokens", "max_observed_tokens"),
    ("meanObservedTokens", "mean_observed_tokens"),
    ("maxSequenceLength", "max_sequence_length"),
    ("derivedWindowOverlapTokens", "derived_window_overlap_tokens"),
    ("capturedAt", "captured_at"),
)


def empty(reason: str, source_path: str | None = None) -> dict:
    """The empty-but-well-shaped document. Every value key is present, as None."""
    doc: dict = {"available": False, "reason": reason, "source_path": source_path}
    for _, dest in _FIELD_MAP:
        doc[dest] = None
    return doc


def read_evidence(path: Path | str | None) -> dict | None:
    """Parse a sidecar into the normalized record.

    Returns ``None`` — soft, never raises — when ``path`` is None, absent, or
    not parseable as a JSON object. Callers that need a document instead of a
    sentinel use :func:`produce`.
    """
    if not path:
        return None
    p = Path(path)
    if not p.is_file():
        return None
    try:
        raw = json.loads(p.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        log.debug("splade_truncation: unreadable evidence at %s: %s", p, exc)
        return None
    if not isinstance(raw, dict):
        return None
    doc: dict = {"available": True, "reason": None, "source_path": str(p)}
    for src, dest in _FIELD_MAP:
        doc[dest] = raw.get(src)
    return doc


def resolve_evidence_path(run_dir: Path) -> tuple[str | None, str]:
    """Locate the sidecar. Returns ``(path_or_None, how)`` — ``how`` names the source."""
    summary = run_dir / "summary.json"
    if summary.is_file():
        try:
            doc = json.loads(summary.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            log.debug("splade_truncation: unreadable summary.json: %s", exc)
            doc = {}
        overrides = doc.get("env_overrides") if isinstance(doc, dict) else None
        if isinstance(overrides, dict) and overrides.get(EVIDENCE_ENV_VAR):
            return str(overrides[EVIDENCE_ENV_VAR]), "summary.env_overrides"
    from_env = os.environ.get(EVIDENCE_ENV_VAR)
    if from_env:
        return from_env, "process_env"
    sidecar = run_dir / DEFAULT_SIDECAR_NAME
    if sidecar.is_file():
        return str(sidecar), "run_dir_sidecar"
    return None, "unset"


def produce(run_dir: Path) -> dict:
    """Projection entrypoint. Never raises on a missing/disabled evidence path."""
    path, how = resolve_evidence_path(Path(run_dir))
    if path is None:
        return empty(
            "%s is unset and no %s sits in the run dir; SPLADE truncation "
            "evidence is opt-in and off by default" % (EVIDENCE_ENV_VAR, DEFAULT_SIDECAR_NAME),
        )
    record = read_evidence(path)
    if record is None:
        return empty("evidence path resolved from %s but is absent or unreadable" % how, path)
    record["resolved_from"] = how
    return record


PROJECTION = Projection(
    name=PROJECTION_NAME,
    schema_version=SCHEMA_VERSION,
    description="Normalized SPLADE truncation evidence (opt-in sidecar; empty when unset).",
    produce=produce,
)
