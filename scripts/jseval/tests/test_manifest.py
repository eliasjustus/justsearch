"""Tests for manifest.py — run manifest aggregation (tempdoc 400 LR1-a)."""

from __future__ import annotations

import json
from types import SimpleNamespace

from jseval.manifest import (
    _COMMIT_METADATA_IDENTITY_FIELDS,
    _RETIRED_VOLATILE_FIELDS,
    _VOLATILE_FIELDS,
    _compute_cohort_hash,
    _normalise_commit_metadata,
    _sha256_canonical,
    compute_manifest,
    write_manifest,
)


def _fake_meta(doc_count=100, query_count=50):
    return SimpleNamespace(
        doc_count=doc_count,
        query_count=query_count,
        name="scifact",
    )


def _fake_state_snapshots(*, ready=True):
    """Return a plausible set of state-endpoint responses."""
    if ready:
        return {
            "/api/status": {"schema_version": 1, "worker": {"core": {"indexHealthy": True}}},
            "/api/debug/state": {"worker": {"status": "IDLE", "doc_count": 100}},
            "/api/debug/commit-metadata": {
                "schema_fp": "a" * 64, "field_catalog_hash": "b" * 64,
                "index_fingerprint": "d" * 64,
            },
            "/api/debug/session-policies": {"configStatus": "ok", "runtime": {}, "models": {}},
            "/api/telemetry/health": {"state": "LIFECYCLE_STATE_READY", "reason_code": None},
            "/api/inference/status": {"mode": "offline", "available": False},
        }
    # Stubs for worker-not-ready shape
    return {
        "/api/status": {"schema_version": 1, "worker": {"core": {"indexHealthy": False}}},
        "/api/debug/state": {"worker": {"status": "UNAVAILABLE"}},
        "/api/debug/commit-metadata": {"_http_status": 503},
        "/api/debug/session-policies": {"configStatus": "worker-unreachable", "runtime": {}, "models": {}},
        "/api/telemetry/health": {"state": "LIFECYCLE_STATE_DEGRADED", "reason_code": "telemetry.metrics.stale"},
        "/api/inference/status": {"mode": "offline"},
    }


class TestCanonicalHash:
    def test_deterministic_across_key_order(self):
        a = {"x": 1, "y": 2, "z": {"inner": 3, "list": [1, 2]}}
        b = {"z": {"list": [1, 2], "inner": 3}, "y": 2, "x": 1}
        assert _sha256_canonical(a) == _sha256_canonical(b)

    def test_sensitive_to_value_change(self):
        a = {"x": 1}
        b = {"x": 2}
        assert _sha256_canonical(a) != _sha256_canonical(b)

    def test_empty_dict_has_stable_hash(self):
        # Empty dicts must hash to a known stable value so that "unavailable
        # endpoint" recorded as {} in the manifest is a cohort-stable input.
        assert _sha256_canonical({}) == _sha256_canonical({})


class TestNormaliseCommitMetadata:
    def test_retains_identity_fields(self):
        raw = {"schema_fp": "abc", "field_catalog_hash": "def"}
        assert _normalise_commit_metadata(raw) == raw

    def test_filters_out_runtime_fields(self):
        # Phase 2.0: commit_id (UUID) and commit_time (timestamp) vary per
        # Lucene commit; they must not enter the cohort hash. Other
        # version/build-state fields are also filtered.
        raw = {
            "schema_fp": "abc",
            "field_catalog_hash": "def",
            "commit_id": "54b20e83-dfe1-4335-91f4-1724160d7fcf",
            "commit_time": "2026-04-22T01:53:03.026646500Z",
            "grammar_ver": 2,
            "template_ver": 3,
            "prompt_pack_hash": "ghi",
            "vector_format": "f32",
            "splade_model_sha256": "jkl",
            "build_state": "clean",
            "grammar_on": True,
        }
        out = _normalise_commit_metadata(raw)
        assert out == {"schema_fp": "abc", "field_catalog_hash": "def"}
        assert "commit_id" not in out
        assert "commit_time" not in out

    def test_retains_all_seven_identity_fields_when_present(self):
        raw = {k: k + "_value" for k in _COMMIT_METADATA_IDENTITY_FIELDS}
        out = _normalise_commit_metadata(raw)
        assert set(out.keys()) == _COMMIT_METADATA_IDENTITY_FIELDS

    def test_strips_http_stub(self):
        assert _normalise_commit_metadata({"_http_status": 503}) == {}

    def test_strips_error_stub(self):
        assert _normalise_commit_metadata({"_error": "ConnectError"}) == {}

    def test_non_dict_input_returns_empty(self):
        assert _normalise_commit_metadata(None) == {}  # type: ignore[arg-type]
        assert _normalise_commit_metadata([]) == {}  # type: ignore[arg-type]


class TestComputeManifest:
    def _baseline(self, **overrides):
        args = dict(
            dataset_name="scifact",
            meta=_fake_meta(),
            env_fingerprint={"cpu": "amd64"},
            models_snapshot={"embed_fingerprint": "f" * 64},
            eval_protocol={"gain_function": "linear"},
            state_snapshots=_fake_state_snapshots(),
            workflow_run_id=None,
        )
        args.update(overrides)
        return args

    def test_has_all_required_fields(self):
        m = compute_manifest(**self._baseline())
        required = {
            # Volatile — run identity
            "run_id", "workflow_run_id", "timestamp",
            # Cohort identity inputs
            "git_sha", "dataset", "doc_count", "query_count",
            "policy_hash", "commit_metadata", "corpus_identity",
            "model_fingerprints", "eval_protocol_hash",
            # Informational (volatile / excluded from cohort hash, Phase 2.0)
            "status_snapshot", "debug_state_snapshot",
            "inference_status_snapshot", "env_fingerprint",
            "telemetry_health_tag",
            # Derived identity
            "manifest_hash",
        }
        assert required <= set(m.keys())

    def test_embeds_commit_metadata_fields(self):
        m = compute_manifest(**self._baseline())
        # The 4 identity fields we seeded must round-trip into manifest.
        cm = m["commit_metadata"]
        assert cm["schema_fp"] == "a" * 64
        assert cm["field_catalog_hash"] == "b" * 64

    def test_telemetry_health_tag_surfaced(self):
        ready = compute_manifest(**self._baseline())
        assert ready["telemetry_health_tag"] == "LIFECYCLE_STATE_READY"

        degraded = compute_manifest(
            **self._baseline(state_snapshots=_fake_state_snapshots(ready=False)))
        assert degraded["telemetry_health_tag"] == "LIFECYCLE_STATE_DEGRADED"

    def test_worker_unreachable_produces_stable_manifest(self):
        # Commit-metadata 503 + session-policies worker-unreachable must NOT
        # fail manifest computation. The stub shape must hash deterministically.
        m1 = compute_manifest(
            **self._baseline(state_snapshots=_fake_state_snapshots(ready=False)))
        m2 = compute_manifest(
            **self._baseline(state_snapshots=_fake_state_snapshots(ready=False)))
        # Volatile fields differ, cohort hash does not.
        assert m1["manifest_hash"] == m2["manifest_hash"]
        # Commit metadata was unavailable — embedded as empty dict.
        assert m1["commit_metadata"] == {}

    def test_cohort_hash_stable_across_volatile_reruns(self):
        m1 = compute_manifest(**self._baseline())
        m2 = compute_manifest(**self._baseline())
        # run_id and timestamp differ between the two.
        assert m1["run_id"] != m2["run_id"]
        assert m1["timestamp"] != m2["timestamp"]
        # manifest_hash is invariant across re-runs with identical config.
        assert m1["manifest_hash"] == m2["manifest_hash"]

    def test_cohort_hash_changes_when_config_changes(self):
        baseline = compute_manifest(**self._baseline())

        # Different models snapshot → different cohort.
        changed_model = compute_manifest(
            **self._baseline(models_snapshot={"embed_fingerprint": "g" * 64}))
        assert baseline["manifest_hash"] != changed_model["manifest_hash"]

        # Different eval protocol → different cohort.
        changed_protocol = compute_manifest(
            **self._baseline(eval_protocol={"gain_function": "exponential"}))
        assert baseline["manifest_hash"] != changed_protocol["manifest_hash"]

        # Different dataset → different cohort.
        changed_dataset = compute_manifest(
            **self._baseline(dataset_name="nfcorpus"))
        assert baseline["manifest_hash"] != changed_dataset["manifest_hash"]

    def test_realized_engine_set_is_cohort_identity(self):
        # tempdoc 644: the realized engine SET is part of model_fingerprints, which is hashed into
        # manifest_hash — so a CE-on run and a CE-off (cross-encoder silently absent) run form
        # distinct cohorts and cannot be silently averaged.
        ce_on = compute_manifest(
            **self._baseline(models_snapshot={"realized_engines": ["dense", "reranker", "splade"]}))
        ce_off = compute_manifest(
            **self._baseline(models_snapshot={"realized_engines": ["dense", "splade"]}))
        assert ce_on["manifest_hash"] != ce_off["manifest_hash"]

    def test_cohort_hash_invariant_to_env_fingerprint(self):
        # Phase 2.0: env_fingerprint is documented as non-stable by design
        # (top-N processes, captured_at timestamp). It must not enter the
        # cohort hash. The manifest still records env_fingerprint for
        # informational purposes.
        baseline = compute_manifest(**self._baseline())
        changed_env = compute_manifest(
            **self._baseline(env_fingerprint={"cpu": "arm64"}))
        assert baseline["manifest_hash"] == changed_env["manifest_hash"]
        # ...but the raw fingerprint is retained on the manifest.
        assert baseline["env_fingerprint"] != changed_env["env_fingerprint"]

    def test_retired_envelope_fields_still_do_not_affect_cohort_hash(self):
        # Tempdoc 930 §18.1 row 7 stopped WRITING these two fields but they must stay
        # excluded from the hash: `corpus_certify` re-hashes archived run manifests that
        # carry them (corpus_certify.py:487-494) and compares against the recorded
        # manifest_hash. Promoting them into the hash would invalidate every 707
        # certificate on disk.
        bare = compute_manifest(**self._baseline())
        archived = dict(bare)
        archived["non_determinism_envelope"] = {"metrics": {"full": {}}}
        archived["envelope_staleness_days"] = 41
        assert _compute_cohort_hash(archived) == bare["manifest_hash"]

    def test_workflow_run_id_propagates(self):
        m = compute_manifest(**self._baseline(workflow_run_id="wf-123"))
        assert m["workflow_run_id"] == "wf-123"

    def test_workflow_run_id_does_not_affect_cohort_hash(self):
        # Phase 2.0: workflow_run_id identifies which workflow invoked the
        # run; runs with different workflow_run_id but identical config are
        # the same cohort for drift-detection purposes.
        a = compute_manifest(**self._baseline(workflow_run_id="wf-a"))
        b = compute_manifest(**self._baseline(workflow_run_id="wf-b"))
        # Raw workflow_run_id differs on the manifest...
        assert a["workflow_run_id"] != b["workflow_run_id"]
        # ...but workflow_run_id is in _VOLATILE_FIELDS so cohort identity
        # is invariant across workflow invocations with the same config.
        assert a["manifest_hash"] == b["manifest_hash"]


class TestVolatileFields:
    def test_known_volatile_fields_excluded_from_hash(self):
        manifest = {
            "run_id": "r1",
            "timestamp": "t1",
            "manifest_hash": "x",
            "env_fingerprint": {"captured_at": "2026-04-22T01:00:00Z"},
            "git_sha": "abc",
        }
        same_content_volatile_diff = {
            "run_id": "r2",
            "timestamp": "t2",
            "manifest_hash": "y",
            "env_fingerprint": {"captured_at": "2026-09-05T09:00:00Z"},
            "git_sha": "abc",
        }
        assert _compute_cohort_hash(manifest) == _compute_cohort_hash(
            same_content_volatile_diff)

    def test_volatile_set_documents_scope(self):
        # Locks the known volatile set so additions/removals are deliberate.
        # Phase 2.0 expanded the set to exclude runtime-state snapshots +
        # informational fields per tempdoc 400 §24.
        assert _VOLATILE_FIELDS == {
            "run_id",
            "timestamp",
            "workflow_run_id",
            "manifest_hash",
            "status_snapshot",
            "debug_state_snapshot",
            "inference_status_snapshot",
            "env_fingerprint",
            "telemetry_health_tag",
        }

    def test_retired_volatile_set_is_exclusion_only_and_locked(self):
        # These names are no longer emitted (930 §18.1 row 7) but must stay excluded so
        # archived manifests keep hashing to their recorded value. Locked separately from
        # _VOLATILE_FIELDS so a future writer cannot quietly re-adopt one of them.
        assert _RETIRED_VOLATILE_FIELDS == {
            "non_determinism_envelope",
            "envelope_staleness_days",
        }
        assert not (_VOLATILE_FIELDS & _RETIRED_VOLATILE_FIELDS)
        emitted = set(compute_manifest(
            dataset_name="scifact",
            meta=_fake_meta(),
            env_fingerprint={"cpu": "amd64"},
            models_snapshot={"embed_fingerprint": "f" * 64},
            eval_protocol={"gain_function": "linear"},
            state_snapshots=_fake_state_snapshots(),
        ))
        assert not (emitted & _RETIRED_VOLATILE_FIELDS)


class TestCohortStabilityAgainstRuntimeState:
    """Regression coverage for the Phase 2.0 fix (tempdoc 400 §24).

    Pre-Phase-2.0 the manifest hashed raw endpoint payloads. Runtime state
    inside those payloads (uptime, queue depths, searcher generation, Lucene
    commit UUIDs/timestamps, env_fingerprint.captured_at, top-N process
    list) caused 3 identical ``--clean --pipeline`` smokes to produce 3
    different ``manifest_hash`` values, breaking the cohort concept that
    LR1-b depends on.

    These tests construct two state_snapshots that differ ONLY in fields
    that should be runtime state, and assert the cohort hash is invariant.
    """

    def _baseline(self, **overrides):
        args = dict(
            dataset_name="scifact",
            meta=_fake_meta(),
            env_fingerprint={"cpu": "amd64", "captured_at": "2026-04-22T01:00:00Z"},
            models_snapshot={"embed_fingerprint": "f" * 64},
            eval_protocol={"gain_function": "linear"},
            state_snapshots=_fake_state_snapshots(),
            workflow_run_id=None,
        )
        args.update(overrides)
        return args

    def test_status_uptime_does_not_affect_cohort(self):
        a_snap = _fake_state_snapshots()
        a_snap["/api/status"] = {
            "schema_version": 1,
            "uptimeMs": 1000,
            "worker": {"core": {"indexHealthy": True}, "queueDb": {"depth": 0}},
        }
        b_snap = _fake_state_snapshots()
        b_snap["/api/status"] = {
            "schema_version": 1,
            "uptimeMs": 9999999,
            "worker": {"core": {"indexHealthy": True}, "queueDb": {"depth": 42}},
        }
        a = compute_manifest(**self._baseline(state_snapshots=a_snap))
        b = compute_manifest(**self._baseline(state_snapshots=b_snap))
        assert a["manifest_hash"] == b["manifest_hash"]

    def test_debug_state_runtime_fields_do_not_affect_cohort(self):
        a_snap = _fake_state_snapshots()
        a_snap["/api/debug/state"] = {
            "worker": {"status": "IDLE", "doc_count": 100},
            "system": {"pid": 1234, "uptime_ms": 5000},
        }
        b_snap = _fake_state_snapshots()
        b_snap["/api/debug/state"] = {
            "worker": {"status": "BUSY", "doc_count": 9999},
            "system": {"pid": 5678, "uptime_ms": 99999},
        }
        a = compute_manifest(**self._baseline(state_snapshots=a_snap))
        b = compute_manifest(**self._baseline(state_snapshots=b_snap))
        assert a["manifest_hash"] == b["manifest_hash"]

    def test_commit_id_and_commit_time_do_not_affect_cohort(self):
        # Q1 root cause: every Lucene commit stamps a new UUID + timestamp
        # into /api/debug/commit-metadata. Pre-Phase-2.0 these destabilized
        # the cohort hash.
        a_snap = _fake_state_snapshots()
        a_snap["/api/debug/commit-metadata"] = {
            "schema_fp": "a" * 64,
            "field_catalog_hash": "b" * 64,
            "index_fingerprint": "d" * 64,
            "commit_id": "54b20e83-dfe1-4335-91f4-1724160d7fcf",
            "commit_time": "2026-04-22T01:53:03.026646500Z",
        }
        b_snap = _fake_state_snapshots()
        b_snap["/api/debug/commit-metadata"] = {
            "schema_fp": "a" * 64,
            "field_catalog_hash": "b" * 64,
            "index_fingerprint": "d" * 64,
            "commit_id": "15e5876c-b2c9-42c4-8f5f-16d72b3fbffb",
            "commit_time": "2026-04-22T01:57:03.229273100Z",
        }
        a = compute_manifest(**self._baseline(state_snapshots=a_snap))
        b = compute_manifest(**self._baseline(state_snapshots=b_snap))
        assert a["manifest_hash"] == b["manifest_hash"]

    def test_env_fingerprint_captured_at_does_not_affect_cohort(self):
        a = compute_manifest(
            **self._baseline(env_fingerprint={
                "cpu": "amd64",
                "captured_at": "2026-04-22T01:00:00Z",
                "top_processes": ["java", "node"],
            })
        )
        b = compute_manifest(
            **self._baseline(env_fingerprint={
                "cpu": "amd64",
                "captured_at": "2026-04-22T05:00:00Z",
                "top_processes": ["java", "python"],
            })
        )
        assert a["manifest_hash"] == b["manifest_hash"]

    def test_inference_status_runtime_fields_do_not_affect_cohort(self):
        a_snap = _fake_state_snapshots()
        a_snap["/api/inference/status"] = {"mode": "offline", "available": False}
        b_snap = _fake_state_snapshots()
        b_snap["/api/inference/status"] = {"mode": "online", "available": True}
        a = compute_manifest(**self._baseline(state_snapshots=a_snap))
        b = compute_manifest(**self._baseline(state_snapshots=b_snap))
        assert a["manifest_hash"] == b["manifest_hash"]

    def test_telemetry_health_tag_does_not_affect_cohort(self):
        ready = compute_manifest(
            **self._baseline(state_snapshots=_fake_state_snapshots(ready=True)))
        degraded = compute_manifest(
            **self._baseline(state_snapshots=_fake_state_snapshots(ready=False)))
        # Commit-metadata + policy snapshots differ in the ready=False case,
        # so reuse a variant that only changes /api/telemetry/health.
        custom_snap = _fake_state_snapshots()
        custom_snap["/api/telemetry/health"] = {
            "state": "LIFECYCLE_STATE_DEGRADED", "reason_code": "telemetry.metrics.stale",
        }
        custom = compute_manifest(**self._baseline(state_snapshots=custom_snap))
        assert ready["manifest_hash"] == custom["manifest_hash"]

    def test_informational_fields_retained_on_manifest(self):
        # Phase 2.0 excludes runtime-state payloads from the cohort hash but
        # retains them on the manifest document for operator inspection.
        snap = _fake_state_snapshots()
        snap["/api/status"] = {"schema_version": 1, "uptimeMs": 12345}
        m = compute_manifest(**self._baseline(state_snapshots=snap))
        assert m["status_snapshot"]["uptimeMs"] == 12345
        assert m["env_fingerprint"]["captured_at"] == "2026-04-22T01:00:00Z"
        assert m["telemetry_health_tag"] == "LIFECYCLE_STATE_READY"


class TestWriteManifest:
    def test_writes_sorted_json(self, tmp_path):
        m = compute_manifest(
            dataset_name="scifact",
            meta=_fake_meta(),
            env_fingerprint=None,
            models_snapshot=None,
            eval_protocol={"gain_function": "linear"},
            state_snapshots=_fake_state_snapshots(),
        )
        path = write_manifest(m, tmp_path)
        assert path.exists()
        reloaded = json.loads(path.read_text(encoding="utf-8"))
        assert reloaded["manifest_hash"] == m["manifest_hash"]
        # sort_keys=True on write means reading and re-hashing the canonical
        # form gives the same manifest_hash.
        assert reloaded["dataset"] == "scifact"


class TestCorpusIdentitySeam:
    """719 boundary fix (2026-07-16): compute_manifest must carry the caller-computed
    corpus identity when the operator env override is unset — pre-fix the manifest was
    env-only, every production manifest had signature:null, and the scientific-evidence
    boundary (which binds manifest.corpus_identity.signature to the certified corpus)
    correctly rejected all backend-gate evidence."""

    def _manifest(self, monkeypatch, identity):
        monkeypatch.delenv("JUSTSEARCH_CORPUS_SIGNATURE", raising=False)
        monkeypatch.delenv("JUSTSEARCH_CORPUS_PROFILE_ID", raising=False)
        from types import SimpleNamespace
        return compute_manifest(
            dataset_name="mixed/x", meta=SimpleNamespace(doc_count=1, query_count=1),
            env_fingerprint=None, models_snapshot=None, eval_protocol={},
            state_snapshots={}, corpus_identity=identity)

    def test_computed_identity_fills_seam(self, monkeypatch):
        m = self._manifest(monkeypatch, {"profile_id": None, "signature": "a" * 64})
        assert m["corpus_identity"]["signature"] == "a" * 64

    def test_env_override_wins(self, monkeypatch):
        monkeypatch.setenv("JUSTSEARCH_CORPUS_SIGNATURE", "e" * 64)
        from types import SimpleNamespace
        m = compute_manifest(
            dataset_name="mixed/x", meta=SimpleNamespace(doc_count=1, query_count=1),
            env_fingerprint=None, models_snapshot=None, eval_protocol={},
            state_snapshots={}, corpus_identity={"profile_id": None, "signature": "a" * 64})
        assert m["corpus_identity"]["signature"] == "e" * 64

    def test_absent_identity_stays_null(self, monkeypatch):
        m = self._manifest(monkeypatch, None)
        assert m["corpus_identity"]["signature"] is None
