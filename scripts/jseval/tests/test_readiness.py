"""Tests for readiness.py — gate condition logic."""

from __future__ import annotations

from unittest.mock import MagicMock, patch

import pytest

from jseval.readiness import (
    _MAX_CONSECUTIVE_FETCH_FAILURES,
    _check_index_idle_conditions,
    _check_pipeline_complete_conditions,
    _check_search_conditions,
    _poll_until_stable,
)


def _good_snapshot(**overrides) -> dict:
    """A snapshot where all conditions pass."""
    base = {
        "indexState": "IDLE",
        "pendingJobs": 0,
        "pendingJobsCount": 0,
        "processingJobsCount": 0,
        "chunkVectorsReady": True,
        "embeddingCompatState": "OK",
        "embeddingCoveragePercent": 100.0,
        "spladeDocCount": 100,
        "spladePendingCount": 0,
        "spladeFailedCount": 0,
        "spladeCoveragePercent": 100.0,
        "pendingReadyJobsCount": 0,
        "pendingBackoffJobsCount": 0,
        "buildingIndexedDocuments": 0,
        "indexedDocuments": 500,
    }
    base.update(overrides)
    return base


# ---------------------------------------------------------------------------
# check_search_conditions
# ---------------------------------------------------------------------------

class TestCheckSearchConditions:
    def test_all_good(self):
        reasons = _check_search_conditions(
            _good_snapshot(), dense=True, splade=True, lambdamart=False,
            base_url="http://localhost",
        )
        assert reasons == []

    def test_index_not_idle(self):
        reasons = _check_search_conditions(
            _good_snapshot(indexState="INDEXING"),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_not_idle" in reasons

    def test_queue_not_quiescent_pending_jobs(self):
        reasons = _check_search_conditions(
            _good_snapshot(pendingJobs=5),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_queue_not_quiescent" in reasons

    def test_queue_not_quiescent_processing(self):
        reasons = _check_search_conditions(
            _good_snapshot(processingJobsCount=2),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert "index_queue_not_quiescent" in reasons

    def test_dense_not_ready_vectors(self):
        reasons = _check_search_conditions(
            _good_snapshot(chunkVectorsReady=False),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert "dense_requested_but_chunk_vectors_not_ready" in reasons

    def test_dense_not_ready_when_not_requested(self):
        reasons = _check_search_conditions(
            _good_snapshot(chunkVectorsReady=False),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("dense" in r for r in reasons)

    def test_embedding_compat_blocked(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState="INCOMPATIBLE"),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        blocked = [r for r in reasons if "embedding_compat_blocked" in r]
        assert len(blocked) == 1
        assert "INCOMPATIBLE" in blocked[0]

    # [-1d] NEW_INDEX_NO_FINGERPRINT is no longer accepted — it's transient.
    @pytest.mark.parametrize("state", ["", "OK", "COMPATIBLE", "FINGERPRINT_MATCH"])
    def test_embedding_compat_ok_values(self, state):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState=state),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_compat" in r for r in reasons)

    def test_embedding_compat_new_index_no_fingerprint_rejected(self):
        """NEW_INDEX_NO_FINGERPRINT is transient and should not be accepted."""
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCompatState="NEW_INDEX_NO_FINGERPRINT"),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert any("embedding_compat_blocked" in r for r in reasons)

    # [-1a] Doc-level embedding coverage check.
    def test_dense_embedding_coverage_low(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=30.0),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert "dense_requested_but_embedding_coverage_low" in reasons

    def test_dense_embedding_coverage_high(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=99.95),
            dense=True, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_coverage" in r for r in reasons)

    def test_dense_embedding_coverage_not_checked_when_disabled(self):
        reasons = _check_search_conditions(
            _good_snapshot(embeddingCoveragePercent=0.0),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("embedding_coverage" in r for r in reasons)

    def test_splade_not_ready_pending(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladePendingCount=5),
            dense=False, splade=True, lambdamart=False, base_url="",
        )
        assert "splade_requested_but_splade_features_not_ready" in reasons

    def test_splade_coverage_low(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladeCoveragePercent=95.0),
            dense=False, splade=True, lambdamart=False, base_url="",
        )
        assert "splade_requested_but_splade_features_not_ready" in reasons

    def test_splade_not_checked_when_disabled(self):
        reasons = _check_search_conditions(
            _good_snapshot(spladePendingCount=100),
            dense=False, splade=False, lambdamart=False, base_url="",
        )
        assert not any("splade" in r for r in reasons)


# ---------------------------------------------------------------------------
# check_index_idle_conditions
# ---------------------------------------------------------------------------

class TestCheckIndexIdleConditions:
    def test_all_good(self):
        reasons = _check_index_idle_conditions(_good_snapshot(), -1)
        assert reasons == []

    def test_pending_ready_jobs(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(pendingReadyJobsCount=3), -1,
        )
        assert "index_queue_not_quiescent" in reasons

    def test_pending_backoff_jobs(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(pendingBackoffJobsCount=1), -1,
        )
        assert "index_queue_not_quiescent" in reasons

    def test_still_building(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(buildingIndexedDocuments=10), -1,
        )
        assert "index_still_building" in reasons

    def test_doc_count_below_floor(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=50), 100,
        )
        floor_reasons = [r for r in reasons if "below_floor" in r]
        assert len(floor_reasons) == 1
        assert "50/100" in floor_reasons[0]

    def test_doc_count_floor_disabled(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=0), -1,
        )
        assert not any("below_floor" in r for r in reasons)

    def test_doc_count_meets_floor(self):
        reasons = _check_index_idle_conditions(
            _good_snapshot(indexedDocuments=100), 100,
        )
        assert not any("below_floor" in r for r in reasons)


# ---------------------------------------------------------------------------
# _check_pipeline_complete_conditions — stage-enabled gating
# ---------------------------------------------------------------------------

def _pipeline_good_snapshot(**overrides) -> dict:
    """All pipeline-completion conditions satisfied."""
    base = _good_snapshot(
        completedNerCount=500,
        pendingNerCount=0,
        chunkDocCount=200,
        chunkVectorCoveragePercent=100.0,
    )
    base.update(overrides)
    return base


class TestCheckPipelineCompleteConditions:
    def test_all_good_all_enabled(self):
        reasons = _check_pipeline_complete_conditions(_pipeline_good_snapshot(), -1)
        assert reasons == []

    def test_splade_disabled_flag_in_snapshot_skips_splade_check(self):
        # SPLADE coverage at 0% (as it would be if the stage never ran).
        # With spladeEnabled absent or True, the check fires.
        # With spladeEnabled=False on the snapshot, the check is skipped.
        snap = _pipeline_good_snapshot(spladeCoveragePercent=0.0)
        blocked = _check_pipeline_complete_conditions(snap, -1)
        assert "splade_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(spladeCoveragePercent=0.0, spladeEnabled=False), -1,
        )
        assert "splade_not_complete" not in ok

    def test_ner_disabled_flag_in_snapshot_skips_ner_check(self):
        # NER completion would flag when pending > 0 OR done == 0.
        blocked = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(completedNerCount=0, pendingNerCount=0), -1,
        )
        assert "ner_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                completedNerCount=0, pendingNerCount=0, nerEnabled=False,
            ),
            -1,
        )
        assert "ner_not_complete" not in ok

    def test_embedding_disabled_flag_skips_embed_and_chunk_checks(self):
        # Both embed coverage and chunk-vector coverage ride on embeddingEnabled.
        blocked = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                embeddingCoveragePercent=0.0, chunkVectorCoveragePercent=0.0,
            ),
            -1,
        )
        assert "embedding_not_complete" in blocked
        assert "chunk_vectors_not_complete" in blocked
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                embeddingCoveragePercent=0.0,
                chunkVectorCoveragePercent=0.0,
                embeddingEnabled=False,
            ),
            -1,
        )
        assert "embedding_not_complete" not in ok
        assert "chunk_vectors_not_complete" not in ok

    def test_all_stages_disabled_still_checks_index(self):
        # Disabling stages should not bypass the index_not_idle check —
        # the index itself must still reach IDLE regardless of enrichment.
        ok = _check_pipeline_complete_conditions(
            _pipeline_good_snapshot(
                indexState="BUILDING",
                embeddingEnabled=False,
                spladeEnabled=False,
                nerEnabled=False,
            ),
            -1,
        )
        assert "index_not_idle" in ok

    def test_missing_enabled_fields_default_to_true(self):
        # Backward compat: older backends that don't publish the enabled flags
        # should behave as they did pre-394-follow-up (all stages checked).
        snap = _pipeline_good_snapshot(spladeCoveragePercent=0.0)
        # Explicitly remove any enabled keys to simulate absent fields.
        snap.pop("spladeEnabled", None)
        snap.pop("embeddingEnabled", None)
        snap.pop("nerEnabled", None)
        reasons = _check_pipeline_complete_conditions(snap, -1)
        assert "splade_not_complete" in reasons


# ---------------------------------------------------------------------------
# _poll_until_stable — fail-fast and progress
# ---------------------------------------------------------------------------

class TestPollUntilStable:
    """Tests for [-1b] fail-fast on consecutive failures and workerRpcStale."""

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_fail_fast_on_consecutive_failures(self, mock_fetch, mock_sleep):
        """5 consecutive fetch failures should abort immediately."""
        mock_fetch.side_effect = ConnectionError("refused")

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert not result.passed
        assert any("backend_unreachable" in r for r in result.failure_reasons)
        assert mock_fetch.call_count == _MAX_CONSECUTIVE_FETCH_FAILURES

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_failure_counter_resets_on_success(self, mock_fetch, mock_sleep):
        """Interspersed successes should reset the failure counter."""
        # Fail 3 times, succeed once (with blocking reasons), fail 3 more,
        # succeed with pass conditions twice.
        blocking_snapshot = _good_snapshot(indexState="INDEXING")
        passing_snapshot = _good_snapshot()

        mock_fetch.side_effect = [
            ConnectionError("1"), ConnectionError("2"), ConnectionError("3"),
            blocking_snapshot,
            ConnectionError("4"), ConnectionError("5"), ConnectionError("6"),
            passing_snapshot, passing_snapshot,
        ]

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: ["index_not_idle"] if s.get("indexState") != "IDLE" else [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert result.passed

    @patch("jseval.readiness.time.sleep")
    @patch("jseval.readiness._fetch_status")
    def test_worker_rpc_stale_blocks_readiness(self, mock_fetch, mock_sleep):
        """workerRpcStale prevents readiness even if check_fn passes."""
        stale_snapshot = _good_snapshot(meta={"workerRpcStale": True})
        fresh_snapshot = _good_snapshot(meta={"workerRpcStale": False})

        mock_fetch.side_effect = [
            stale_snapshot, stale_snapshot,
            fresh_snapshot, fresh_snapshot,
        ]

        result = _poll_until_stable(
            "http://localhost:33221",
            check_fn=lambda s: [],
            timeout_sec=600,
            poll_interval_sec=1,
            stable_polls_required=2,
        )

        assert result.passed
        # Should have taken 4 polls (2 stale + 2 fresh/passing)
        assert mock_fetch.call_count == 4
