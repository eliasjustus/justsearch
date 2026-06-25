"""Projection base + registry (tempdoc 400 LR4-a).

A *projection* is a pure function from a run directory's artifacts
(``traces.ndjson``, ``metrics.ndjson``, ``{mode}_per_query.json``,
``summary.json``, ``manifest.json``) to a derived JSON document. The
registry lets ``jseval run`` invoke every registered projection at
end-of-run without knowing their individual names or schemas.

Contract (locked by this commit):

- A projection has a ``name`` (unique; used as the output filename
  ``<run_dir>/projections/<name>.json``) and a ``schema_version``
  (bumped on breaking output-shape changes so downstream consumers
  can detect drift).
- The produce function signature is ``produce(run_dir: Path) ->
  dict`` — pure, side-effect-free.
- Missing inputs are not errors: a projection reads the artifacts
  it wants and returns an empty-but-well-shaped result when inputs
  are absent. This matches the contract_violations projection shipped
  in tempdoc 400 Phase 1 (LR6-c).
- ``schema_version`` is embedded in the output under the key
  ``schema_version`` so consumers can version-dispatch without
  loading the registry.

The registry is module-global (single-writer, single-reader per
interpreter; `jseval` is not multi-processed). ``register`` is
idempotent on same-name re-registration — the last definition wins.
This allows projections defined in sibling modules to self-register
at import time without worrying about import order.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Mapping

log = logging.getLogger(__name__)

ProduceFn = Callable[[Path], dict]


@dataclass(frozen=True)
class Projection:
    """Declarative registration unit.

    Attributes
    ----------
    name
        Unique projection name. Used as the output filename stem
        (``<run_dir>/projections/<name>.json``).
    schema_version
        Version of the emitted JSON shape. Increment on any breaking
        output change. Embedded in the projection output under
        ``schema_version``.
    produce
        Pure function ``(run_dir: Path) -> dict``. Reads artifacts
        from ``run_dir``; returns the projection document. Should
        never raise on missing artifacts — return an empty-but-
        well-shaped result instead.
    description
        One-line human-readable description. Logged on registration.
    """

    name: str
    schema_version: int
    produce: ProduceFn
    description: str = ""


_REGISTRY: dict[str, Projection] = {}


def register(projection: Projection) -> Projection:
    """Register ``projection`` in the global registry.

    Idempotent on the same name: the last registration wins. Returns
    the projection so the call can be used as a module-level
    expression (pattern: ``_P = register(Projection(...))``).
    """
    if projection.name in _REGISTRY:
        log.debug("replacing projection %r in registry", projection.name)
    _REGISTRY[projection.name] = projection
    return projection


def registry() -> Mapping[str, Projection]:
    """Return a read-only view of the registry."""
    return dict(_REGISTRY)


def _projections_dir(run_dir: Path) -> Path:
    return run_dir / "projections"


def _write_projection_output(
    run_dir: Path, projection: Projection, result: dict,
) -> Path:
    """Write ``result`` to ``<run_dir>/projections/<name>.json``.

    Stamps ``schema_version`` + ``projection_name`` into the output
    document for schema-dispatch reliability downstream.
    """
    out_dir = _projections_dir(run_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    document = {
        "projection_name": projection.name,
        "schema_version": projection.schema_version,
        **result,
    }
    path = out_dir / f"{projection.name}.json"
    path.write_text(
        json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )
    return path


def run_all(
    run_dir: Path, *, skip: frozenset[str] = frozenset(),
) -> dict[str, dict]:
    """Invoke every registered projection against ``run_dir``.

    Returns a mapping ``{name: {status, path}}`` summarizing what
    was produced. ``status`` is ``"ok"`` on success, ``"error"`` on
    exception (the exception message is captured under ``error``),
    or ``"skipped"`` when ``name`` is in the ``skip`` set.
    Individual projection failures do not abort the batch — other
    projections still run.

    **Silent-failure-mode fix (tempdoc 400 post-implementation-critique
    C-1.2.1 / CC-4.1).** Historically an exception caught here only
    went to the Worker log and a buried summary field; operators
    missed first-time regressions. Now every projection exception
    also writes a synthetic ``contract.violation`` event into
    ``<run_dir>/projections/_errors.ndjson`` with
    ``contract.tempdoc="400 §27.3"`` + ``contract.tier="@RuntimeContract"``
    + ``contract.description=<projection name>: <exception class>``.
    The :mod:`jseval.projections.contract_violations` projection reads
    this file alongside ``traces.ndjson`` so projection failures
    surface through the same aggregate the nightly gate already
    checks.

    Writes side-effect: one ``<run_dir>/projections/<name>.json``
    file per projection whose ``produce`` returned without raising
    (plus an ``_errors.ndjson`` with one line per failed projection).
    """
    summary: dict[str, dict] = {}
    error_records: list[dict] = []
    for name, projection in _REGISTRY.items():
        if name in skip:
            summary[name] = {"status": "skipped"}
            continue
        try:
            result = projection.produce(run_dir)
            path = _write_projection_output(run_dir, projection, result)
            summary[name] = {"status": "ok", "path": str(path)}
        except Exception as exc:  # noqa: BLE001 - projections quarantined
            log.warning("projection %r failed: %s", name, exc, exc_info=True)
            summary[name] = {"status": "error", "error": str(exc)}
            error_records.append({
                "name": "contract.violation",
                "attrs": {
                    "contract.tempdoc": "400 §27.3",
                    "contract.tier": "@RuntimeContract",
                    "contract.description":
                        f"projection {name} failed: {type(exc).__name__}: {exc}",
                    "contract.projection": name,
                    "contract.exception_class": type(exc).__name__,
                },
            })
    if error_records:
        _append_error_ndjson(run_dir, error_records)
    return summary


def _append_error_ndjson(run_dir: Path, events: list[dict]) -> Path:
    """Write projection-failure ``contract.violation`` events.

    The file shape matches ``traces.ndjson`` minimally: each line is
    a JSON object with ``name``, ``trace_id``, ``span_id``,
    ``events``. The ``contract_violations`` projection treats this
    as an additional source via ``_iter_spans(...)`` merge.
    """
    out_dir = _projections_dir(run_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "_errors.ndjson"
    with path.open("a", encoding="utf-8") as f:
        for ev in events:
            span = {
                "name": "projection.failure",
                "trace_id": "projection-failure",
                "span_id": ev["attrs"]["contract.projection"],
                "events": [ev],
            }
            f.write(json.dumps(span, ensure_ascii=False) + "\n")
    return path


def reset_registry_for_tests() -> None:
    """Clear the registry. Test-only hook."""
    _REGISTRY.clear()
