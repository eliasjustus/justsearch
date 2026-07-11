"""Tests for cohort_baselines registry helper (tempdoc 400 §26.6 D2)."""

from __future__ import annotations

import json
import logging

import jseval._paths as _paths
from jseval import cohort_baselines as cb
from jseval.calibrate import read_envelope, write_envelope


class TestPaths:
    def test_registry_dir_under_data_dir(self, tmp_path):
        assert cb.cohort_baselines_dir(tmp_path) == tmp_path / "cohort_baselines"

    def test_cohort_dir_name(self, tmp_path):
        assert cb.cohort_dir(tmp_path, "h") == tmp_path / "cohort_baselines" / "h"

    def test_envelope_facet_filename(self, tmp_path):
        assert cb.envelope_path(tmp_path, "h") == \
            tmp_path / "cohort_baselines" / "h" / "envelope.json"

    def test_span_distributions_facet_filename(self, tmp_path):
        assert cb.span_distributions_path(tmp_path, "h") == \
            tmp_path / "cohort_baselines" / "h" / "span_distributions.json"

    def test_legacy_envelope_path(self, tmp_path):
        assert cb.legacy_envelope_path(tmp_path, "h") == \
            tmp_path / "non_determinism_envelopes" / "h.json"

    def test_ensure_cohort_dir_creates_tree(self, tmp_path):
        path = cb.ensure_cohort_dir(tmp_path, "c")
        assert path.is_dir()
        assert path == tmp_path / "cohort_baselines" / "c"


class TestRoundTripViaNewLayout:
    def test_write_envelope_lands_in_new_layout(self, tmp_path):
        env = {"cohort_hash": "c1", "schema_version": 1,
               "calibrated_at": "2026-04-22T00:00:00Z",
               "metrics": {"full": {"nDCG@10": {
                   "mean": 0.7, "stdev": 0.001, "n": 5}}}}
        path = write_envelope(tmp_path, "c1", env)
        assert path == cb.envelope_path(tmp_path, "c1")

    def test_read_envelope_finds_new_layout(self, tmp_path):
        env = {"cohort_hash": "c2", "metrics": {}}
        cb.ensure_cohort_dir(tmp_path, "c2")
        cb.envelope_path(tmp_path, "c2").write_text(json.dumps(env),
                                                    encoding="utf-8")
        assert read_envelope(tmp_path, "c2") == env


class TestLegacyShim:
    def test_read_envelope_falls_back_to_legacy_sidecar(self, tmp_path):
        env = {"cohort_hash": "c3", "metrics": {}}
        legacy = cb.legacy_envelope_path(tmp_path, "c3")
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_text(json.dumps(env), encoding="utf-8")
        assert read_envelope(tmp_path, "c3") == env

    def test_new_layout_preferred_when_both_present(self, tmp_path):
        legacy = cb.legacy_envelope_path(tmp_path, "c4")
        legacy.parent.mkdir(parents=True, exist_ok=True)
        legacy.write_text(json.dumps({"which": "legacy"}), encoding="utf-8")
        cb.ensure_cohort_dir(tmp_path, "c4")
        cb.envelope_path(tmp_path, "c4").write_text(
            json.dumps({"which": "new"}), encoding="utf-8")
        assert read_envelope(tmp_path, "c4") == {"which": "new"}

    def test_both_missing_returns_none(self, tmp_path):
        assert read_envelope(tmp_path, "never-calibrated") is None


class TestPre716LegacyRootFallback:
    """Tempdoc 716 migration shim: envelopes/baselines written pre-716 lived
    inside the backend data dir; readers must resolve them from there with a
    one-line deprecation WARN — zero operator action required."""

    def _write_envelope_at(self, root, cohort_hash, doc):
        path = cb.envelope_path(root, cohort_hash)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(doc), encoding="utf-8")
        return path

    def test_old_location_only_resolves_and_warns(self, tmp_path, monkeypatch, caplog):
        legacy_root = tmp_path / "old-backend-data"
        monkeypatch.setattr(_paths, "DEFAULT_BACKEND_DATA_DIR", legacy_root)
        doc = {"cohort_hash": "m1", "metrics": {}}
        self._write_envelope_at(legacy_root, "m1", doc)
        primary = tmp_path / "jseval-root"  # does not even exist
        with caplog.at_level(logging.WARNING, logger="jseval.cohort_baselines"):
            assert read_envelope(primary, "m1") == doc
        assert any("pre-716 legacy location" in r.message for r in caplog.records)

    def test_env_data_dir_consulted_as_legacy_root(self, tmp_path, monkeypatch, caplog):
        env_root = tmp_path / "env-backend-data"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(env_root))
        doc = {"cohort_hash": "m2", "metrics": {}}
        self._write_envelope_at(env_root, "m2", doc)
        with caplog.at_level(logging.WARNING, logger="jseval.cohort_baselines"):
            assert read_envelope(tmp_path / "jseval-root", "m2") == doc
        assert any("pre-716 legacy location" in r.message for r in caplog.records)

    def test_primary_hit_does_not_warn(self, tmp_path, monkeypatch, caplog):
        legacy_root = tmp_path / "old-backend-data"
        monkeypatch.setattr(_paths, "DEFAULT_BACKEND_DATA_DIR", legacy_root)
        self._write_envelope_at(legacy_root, "m3", {"which": "legacy"})
        primary = tmp_path / "jseval-root"
        self._write_envelope_at(primary, "m3", {"which": "primary"})
        with caplog.at_level(logging.WARNING, logger="jseval.cohort_baselines"):
            assert read_envelope(primary, "m3") == {"which": "primary"}
        assert not caplog.records

    def test_span_distributions_fallback_resolves_and_warns(
            self, tmp_path, monkeypatch, caplog):
        legacy_root = tmp_path / "old-backend-data"
        monkeypatch.setattr(_paths, "DEFAULT_BACKEND_DATA_DIR", legacy_root)
        span = cb.span_distributions_path(legacy_root, "m4")
        span.parent.mkdir(parents=True, exist_ok=True)
        span.write_text(json.dumps({"encoders": {}}), encoding="utf-8")
        with caplog.at_level(logging.WARNING, logger="jseval.cohort_baselines"):
            got = cb.resolve_span_distributions_path(tmp_path / "jseval-root", "m4")
        assert got == span
        assert any("pre-716 legacy location" in r.message for r in caplog.records)

    def test_candidate_roots_primary_first_and_deduped(self, tmp_path, monkeypatch):
        root = tmp_path / "same-root"
        monkeypatch.setenv("JUSTSEARCH_DATA_DIR", str(root))
        monkeypatch.setattr(_paths, "DEFAULT_BACKEND_DATA_DIR", root)
        # Primary == env == backend default: must collapse to one entry.
        assert cb.candidate_roots(root) == (root,)
        other = tmp_path / "other"
        roots = cb.candidate_roots(other)
        assert roots[0] == other and root in roots and len(roots) == 2


class TestFacetSeparation:
    def test_envelope_and_span_distributions_coexist(self, tmp_path):
        # Envelope + span_distributions must not collide in the same
        # cohort directory (§26.6 Decision 2 motivation).
        env_path = cb.envelope_path(tmp_path, "c5")
        span_path = cb.span_distributions_path(tmp_path, "c5")
        env_path.parent.mkdir(parents=True, exist_ok=True)
        env_path.write_text(json.dumps({"k": "env"}), encoding="utf-8")
        span_path.write_text(json.dumps({"k": "span"}), encoding="utf-8")
        assert json.loads(env_path.read_text())["k"] == "env"
        assert json.loads(span_path.read_text())["k"] == "span"
