"""Cohort baselines registry (tempdoc 400 §26.6 Decision 2).

Phase 3 generalizes the Phase-2 envelope sidecar layout into a
per-cohort directory holding multiple facet files:

- ``envelope.json`` — scalar metric calibration (Phase-2 schema;
  unchanged content, moved from ``non_determinism_envelopes/<hash>.json``).
- ``span_distributions.json`` — per-encoder span-duration distributions
  for LR4-g PSI / MMD drift detection (Phase-3 LR4-g).

One facet per file keeps shapes / consumers / freshness policies
independent. Future facets (per-query score distributions, per-mode
latency histograms) drop in under the same directory without schema
pollution.

Layout::

    <data_dir>/cohort_baselines/
        <cohort_hash>/
            envelope.json
            span_distributions.json   (optional)

Backward-compat shim:

The Phase-2 ``<data_dir>/non_determinism_envelopes/<cohort_hash>.json``
path is still *read* by :func:`jseval.calibrate.read_envelope` when
the new path is absent, so in-flight envelopes keep working without a
hard migration step. Write path always uses the new layout. The shim
can be removed in a later commit once all calibrated cohorts have been
re-written at least once.
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

log = logging.getLogger(__name__)

REGISTRY_SUBDIR = "cohort_baselines"
ENVELOPE_FILENAME = "envelope.json"
SPAN_DISTRIBUTIONS_FILENAME = "span_distributions.json"

LEGACY_ENVELOPE_SUBDIR = "non_determinism_envelopes"


def cohort_baselines_dir(data_dir: Path) -> Path:
    """Return ``<data_dir>/cohort_baselines/``."""
    return data_dir / REGISTRY_SUBDIR


def cohort_dir(data_dir: Path, cohort_hash: str) -> Path:
    """Return ``<data_dir>/cohort_baselines/<cohort_hash>/``."""
    return cohort_baselines_dir(data_dir) / cohort_hash


def envelope_path(data_dir: Path, cohort_hash: str) -> Path:
    """Return the envelope facet file for ``cohort_hash``."""
    return cohort_dir(data_dir, cohort_hash) / ENVELOPE_FILENAME


def span_distributions_path(data_dir: Path, cohort_hash: str) -> Path:
    """Return the span-distributions facet file for ``cohort_hash``."""
    return cohort_dir(data_dir, cohort_hash) / SPAN_DISTRIBUTIONS_FILENAME


def legacy_envelope_path(data_dir: Path, cohort_hash: str) -> Path:
    """Return the Phase-2 envelope sidecar path (backward-compat shim)."""
    return data_dir / LEGACY_ENVELOPE_SUBDIR / f"{cohort_hash}.json"


def ensure_cohort_dir(data_dir: Path, cohort_hash: str) -> Path:
    """Create the cohort directory if absent; return its path."""
    path = cohort_dir(data_dir, cohort_hash)
    path.mkdir(parents=True, exist_ok=True)
    return path


def candidate_roots(data_dir: Path) -> tuple[Path, ...]:
    """``data_dir`` first, then the pre-716 legacy roots, deduped.

    Tempdoc 716 migration shim: calibration state historically lived inside
    the backend's ``JUSTSEARCH_DATA_DIR`` (default ``<repo>/tmp/
    headless-eval-data``) rather than the jseval-owned data root. Readers
    consult those legacy roots when the primary misses so previously-written
    envelopes/baselines keep resolving with zero operator action. Writers
    must NOT use this — writes always target the primary root, so use
    naturally migrates state forward.
    """
    from ._paths import DEFAULT_BACKEND_DATA_DIR

    roots = [data_dir]
    env = os.environ.get("JUSTSEARCH_DATA_DIR")
    if env:
        roots.append(Path(env))
    roots.append(DEFAULT_BACKEND_DATA_DIR)
    out: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        key = os.path.normcase(os.path.normpath(str(root)))
        if key not in seen:
            seen.add(key)
            out.append(root)
    return tuple(out)


def warn_legacy_hit(path: Path) -> None:
    log.warning(
        "cohort artifact resolved from pre-716 legacy location %s — recalibrate "
        "(or move cohort_baselines/ into the jseval data root) to migrate; this "
        "read fallback will eventually be removed (tempdoc 716)", path,
    )


def resolve_envelope_path(data_dir: Path, cohort_hash: str) -> Path | None:
    """First existing envelope file for ``cohort_hash``, or None.

    Per root (primary ``data_dir``, then :func:`candidate_roots` legacy
    fallbacks with a deprecation WARN): the Phase-3 facet path, then the
    Phase-2 sidecar.
    """
    for i, root in enumerate(candidate_roots(data_dir)):
        for path in (envelope_path(root, cohort_hash),
                     legacy_envelope_path(root, cohort_hash)):
            if path.is_file():
                if i > 0:
                    warn_legacy_hit(path)
                return path
    return None


def resolve_span_distributions_path(data_dir: Path, cohort_hash: str) -> Path | None:
    """First existing span-distributions facet for ``cohort_hash``, or None.

    Same primary-then-legacy root order as :func:`resolve_envelope_path`.
    """
    for i, root in enumerate(candidate_roots(data_dir)):
        path = span_distributions_path(root, cohort_hash)
        if path.is_file():
            if i > 0:
                warn_legacy_hit(path)
            return path
    return None
