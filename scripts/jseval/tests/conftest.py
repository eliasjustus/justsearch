"""Shared test fixtures for jseval tests."""

from __future__ import annotations

import json
from pathlib import Path

import pytest


def _write_json(path: Path, doc) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(doc, indent=2, sort_keys=True, ensure_ascii=False),
        encoding="utf-8",
    )


def _write_ndjson(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        "\n".join(json.dumps(r, ensure_ascii=False) for r in records) + "\n",
        encoding="utf-8",
    )


class SyntheticRunDir:
    """Builder for a fake ``run_dir`` used by Layer-4 projection tests.

    Phase-3 projections read a fixed set of artifacts from a run
    directory; this builder lets each projection test focus on its
    own contract without re-implementing the file-layout boilerplate.
    Pass a ``tmp_path`` and chain the ``with_*`` methods; the built
    directory is just the fixture path, ready for a projection's
    ``produce(run_dir)`` call.
    """

    def __init__(self, run_dir: Path) -> None:
        self.run_dir = run_dir
        run_dir.mkdir(parents=True, exist_ok=True)

    def with_traces(self, spans: list[dict]) -> "SyntheticRunDir":
        _write_ndjson(self.run_dir / "traces.ndjson", spans)
        return self

    def with_metrics(self, records: list[dict]) -> "SyntheticRunDir":
        _write_ndjson(self.run_dir / "metrics.ndjson", records)
        return self

    def with_per_query(self, mode: str, entries: list[dict]) -> "SyntheticRunDir":
        _write_json(self.run_dir / f"{mode}_per_query.json", entries)
        return self

    def with_summary(self, summary: dict) -> "SyntheticRunDir":
        _write_json(self.run_dir / "summary.json", summary)
        return self

    def with_manifest(self, manifest: dict) -> "SyntheticRunDir":
        _write_json(self.run_dir / "manifest.json", manifest)
        return self

    def with_qrels(self, qrels: dict[str, dict[str, int]]) -> "SyntheticRunDir":
        _write_json(self.run_dir / "qrels.json", qrels)
        return self


@pytest.fixture
def synthetic_run_dir(tmp_path) -> SyntheticRunDir:
    """Create an empty :class:`SyntheticRunDir` rooted at ``tmp_path``."""
    return SyntheticRunDir(tmp_path / "run")


@pytest.fixture
def make_span():
    """Factory for synthetic span records (``traces.ndjson`` shape)."""

    def _factory(
        name: str,
        *,
        attrs: dict | None = None,
        events: list[dict] | None = None,
        duration_ms: float | None = None,
        trace_id: str | None = None,
        span_id: str | None = None,
        parent_span_id: str | None = None,
    ) -> dict:
        return {
            "name": name,
            "trace_id": trace_id or f"t-{name}",
            "span_id": span_id or f"s-{name}",
            "parent_span_id": parent_span_id,
            "attrs": attrs or {},
            "events": events or [],
            "duration_ms": duration_ms,
        }

    return _factory


@pytest.fixture(autouse=True)
def _isolate_pre716_legacy_roots(monkeypatch, tmp_path):
    """Point the tempdoc-716 legacy-root read fallback away from the real machine.

    `cohort_baselines.candidate_roots` consults env `JUSTSEARCH_DATA_DIR` and
    `_paths.DEFAULT_BACKEND_DATA_DIR` as read fallbacks for pre-716
    calibration state. Un-isolated, a developer machine's real
    `tmp/headless-eval-data` (or an exported JUSTSEARCH_DATA_DIR) could leak
    envelopes into tests that expect a miss. Tests that exercise the fallback
    re-point these explicitly (their monkeypatch writes win — same
    function-scoped monkeypatch, later write).
    """
    import jseval._paths as _paths

    monkeypatch.delenv("JUSTSEARCH_DATA_DIR", raising=False)
    monkeypatch.setattr(
        _paths, "DEFAULT_BACKEND_DATA_DIR", tmp_path / "no-legacy-root-in-tests",
    )


@pytest.fixture(autouse=True)
def _disable_shared_dataset_cache_by_default(monkeypatch):
    """Default every test to `dataset_cache`'s pre-709 (cache-disabled) behavior.

    `dataset_cache.cache_root()` defaults to a directory under the MAIN checkout when
    `JUSTSEARCH_DATASET_CACHE` is unset -- exactly what a real worktree run wants, but never
    what a test run wants: an un-opted-in test would otherwise write real cache-entry
    directories into the actual main checkout on disk merely by running the unit suite from
    a worktree. `tests/test_dataset_cache.py`'s own tests opt back in explicitly by setting
    `JUSTSEARCH_DATASET_CACHE` to a `tmp_path` (monkeypatch's last write for a given test wins
    over this autouse fixture, since both share the same function-scoped `monkeypatch`).
    """
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", "0")


@pytest.fixture
def clean_projection_registry():
    """Reset the projection registry around each test that opts in.

    Phase-3 projections self-register at module import; tests that
    need deterministic registry contents wrap their bodies with this
    fixture to reset + re-seed.
    """
    from jseval.projections.base import reset_registry_for_tests

    reset_registry_for_tests()
    yield
    reset_registry_for_tests()
