"""Tests for ce_coverage.py — the eval-time cross-encoder-coverage validity guard.

Register F-052's hole: when the CE rerank RPC misses ``justsearch.rerank.deadline_ms`` the query
is delivered in pure fusion order and NOTHING a gate reads changes (``comparable: true``, empty
``comparability_reasons``, ``ann_proof PASS``, ``error_count 0``, ``cross_encoder`` still in the
observed legs). The only per-hit trace is ``judgeSignals.ce_score: null``, and the only signal
that separates a deadline drop from a legitimate deterministic skip is the reason the engine
recorded on the ``cross-encoder`` trace stage.
"""

from __future__ import annotations

from jseval.ce_coverage import (
    ce_coverage_verdict,
    ce_stage_of,
    combine_mode_verdicts,
    state_from_per_query_record,
    state_from_response,
)


def _record(*, ce: bool, reason: str | None = None, channel: bool = True) -> dict:
    """One ``{mode}_per_query.json`` entry: ``judgeSignals`` + the CE stage's status/reason.

    ``channel=False`` omits the CE stage fields entirely — the shape of an artifact written
    before those fields existed.
    """
    rec: dict = {
        "qid": "q",
        "judgeSignals": [
            {"docId": "d1", "fusion_score": 0.3, "ce_score": -0.27 if ce else None},
            {"docId": "d2", "fusion_score": 0.2, "ce_score": -0.31 if ce else None},
        ],
    }
    if channel:
        rec["crossEncoderStatus"] = "executed" if ce else "skipped"
        rec["crossEncoderReason"] = None if ce else reason
    return rec


def _verdict(records, *, ce_requested=True, **kw):
    return ce_coverage_verdict(
        [state_from_per_query_record(r) for r in records], ce_requested=ce_requested, **kw
    )


class TestCleanRun:
    """(a) Every query reranked — the shape a healthy run produces."""

    def test_full_coverage_is_ok(self):
        result = _verdict([_record(ce=True) for _ in range(200)])
        assert result.verdict == "ok"
        assert result.applied == 200
        assert result.eligible == 200
        assert result.silent_drops == 0
        assert result.coverage == 1.0

    def test_one_drop_in_two_hundred_is_within_tolerance(self):
        # 0.5% < the 2% tolerance: a single transient must not fail a run.
        records = [_record(ce=True) for _ in range(199)]
        records.append(_record(ce=False, reason="DEADLINE_EXCEEDED"))
        result = _verdict(records)
        assert result.verdict == "ok"
        assert result.silent_drops == 1


class TestContaminatedRun:
    """(b) The measured contamination: 102/200 queries deadline-dropped, delivered in fusion
    order, with the deadline recorded as the CE stage's reason."""

    def test_deadline_dropped_majority_is_degraded(self):
        records = (
            [_record(ce=True) for _ in range(98)]
            + [_record(ce=False, reason="DEADLINE_EXCEEDED") for _ in range(102)]
        )
        result = _verdict(records)
        assert result.verdict == "degraded-ce"
        assert result.silent_drops == 102
        assert result.applied == 98
        assert result.silent_drop_reason_counts == {"DEADLINE_EXCEEDED": 102}

    def test_ce_less_with_no_recorded_reason_is_a_silent_drop(self):
        # The reason channel is present (the stage was recorded) but the stage carried no reason.
        # Fail closed: unexplained is a drop, not a legitimate skip.
        records = (
            [_record(ce=True) for _ in range(90)]
            + [_record(ce=False, reason=None) for _ in range(10)]
        )
        result = _verdict(records)
        assert result.verdict == "degraded-ce"
        assert result.silent_drop_reason_counts == {"NO_REASON_RECORDED": 10}

    def test_unrecognized_reason_fails_closed(self):
        # A reason string this module has never seen (a new engine skip path) must count as a
        # drop, not be waved through by an allow-list miss.
        records = (
            [_record(ce=True) for _ in range(90)]
            + [_record(ce=False, reason="SOME_FUTURE_REASON") for _ in range(10)]
        )
        result = _verdict(records)
        assert result.verdict == "degraded-ce"
        assert result.silent_drop_reason_counts == {"SOME_FUTURE_REASON": 10}

    def test_rpc_failure_is_a_drop_too(self):
        records = (
            [_record(ce=True) for _ in range(90)]
            + [_record(ce=False, reason="RPC_FAILED") for _ in range(10)]
        )
        assert _verdict(records).verdict == "degraded-ce"

    def test_contamination_is_still_caught_when_no_leg_was_observed(self):
        # Precision: `ce_requested` must only ever stand the guard DOWN. A run where EVERY query
        # was deadline-dropped has no `cross_encoder` observed leg either — reading `observed` as
        # the applicability test would make total contamination look like "CE not requested".
        records = [_record(ce=False, reason="DEADLINE_EXCEEDED") for _ in range(50)]
        result = _verdict(records, ce_requested=False)
        assert result.verdict == "degraded-ce"
        assert result.coverage == 0.0


class TestLegitimateSkips:
    """(c) CE-less queries whose skips the engine decided deterministically from the query shape
    — reproducible on every arm, so not contamination."""

    def test_deterministic_skips_are_not_drops(self):
        records = (
            [_record(ce=True) for _ in range(283)]
            + [_record(ce=False, reason="NAVIGATIONAL_QUERY") for _ in range(5)]
            + [_record(ce=False, reason="BELOW_MIN_THRESHOLD") for _ in range(4)]
            + [_record(ce=False, reason="FUSION_CONFIDENT") for _ in range(8)]
        )
        result = _verdict(records)
        assert result.verdict == "ok"
        assert result.silent_drops == 0
        assert result.legitimate_skips == 17
        assert result.legitimate_skip_reason_counts == {
            "BELOW_MIN_THRESHOLD": 4, "FUSION_CONFIDENT": 8, "NAVIGATIONAL_QUERY": 5,
        }
        # Coverage is honestly below 1.0 — the block reports what happened even on `ok`.
        assert result.coverage == 283 / 300

    def test_legitimate_skips_do_not_mask_a_concurrent_drop(self):
        # Precision: the pass in the test above must come from the reasons, not from the counting.
        records = (
            [_record(ce=True) for _ in range(283)]
            + [_record(ce=False, reason="FUSION_CONFIDENT") for _ in range(7)]
            + [_record(ce=False, reason="DEADLINE_EXCEEDED") for _ in range(10)]
        )
        result = _verdict(records)
        assert result.verdict == "degraded-ce"
        assert result.legitimate_skips == 7
        assert result.silent_drops == 10


class TestCeNotRequested:
    """(d) A run whose pipeline never asked for the cross-encoder must never be struck."""

    def test_pipeline_not_eligible_everywhere_is_not_applicable(self):
        records = [_record(ce=False, reason="PIPELINE_NOT_ELIGIBLE") for _ in range(200)]
        result = _verdict(records, ce_requested=False)
        assert result.verdict == "not-applicable"
        assert result.eligible == 0
        assert result.not_in_play == 200

    def test_reranker_disabled_everywhere_is_not_applicable(self):
        records = [_record(ce=False, reason="DISABLED") for _ in range(50)]
        assert _verdict(records, ce_requested=False).verdict == "not-applicable"

    def test_worker_model_not_loaded_everywhere_is_not_applicable(self):
        records = [_record(ce=False, reason="MODEL_NOT_LOADED") for _ in range(50)]
        assert _verdict(records, ce_requested=False).verdict == "not-applicable"

    def test_no_records_is_not_applicable(self):
        assert ce_coverage_verdict([], ce_requested=False).verdict == "not-applicable"

    def test_ce_off_queries_leave_the_eligible_denominator(self):
        # A mixed shape: the CE-off queries must not dilute the drop RATE of the eligible ones.
        records = (
            [_record(ce=False, reason="DISABLED") for _ in range(900)]
            + [_record(ce=True) for _ in range(95)]
            + [_record(ce=False, reason="DEADLINE_EXCEEDED") for _ in range(5)]
        )
        result = _verdict(records)
        assert result.eligible == 100  # not 1000
        assert result.verdict == "degraded-ce"  # 5% > 2%, not 0.5%


class TestUnevaluableArtifacts:
    """(e) Back-compat: an archived run whose artifacts cannot answer the question stands the
    guard down loudly rather than reading as clean."""

    def test_pre_judge_signals_artifacts_are_unevaluable(self):
        records = [{"qid": "q", "ndcgAtK": 0.5} for _ in range(50)]
        result = _verdict(records)
        assert result.verdict == "unevaluable"
        assert "judgeSignals" in result.reasons[0]

    def test_pre_reason_channel_artifacts_are_unevaluable(self):
        # judgeSignals present (post-643) but no crossEncoderStatus/Reason: the CE-less queries
        # cannot be told apart from deterministic skips.
        records = (
            [_record(ce=True, channel=False) for _ in range(98)]
            + [_record(ce=False, channel=False) for _ in range(102)]
        )
        result = _verdict(records)
        assert result.verdict == "unevaluable"
        assert "crossEncoderStatus" in result.reasons[0]
        assert result.reasons[0].startswith("102/200")

    def test_one_unchannelled_ce_less_query_is_enough_to_stand_down(self):
        # Precision: the channel is load-bearing per CE-LESS query, not per record. A single
        # unexplainable query means the run's coverage cannot be established.
        records = (
            [_record(ce=True) for _ in range(199)] + [_record(ce=False, channel=False)]
        )
        assert _verdict(records).verdict == "unevaluable"

    def test_an_unchannelled_query_that_reranked_does_not_stand_the_guard_down(self):
        # Mirror image: a record with no CE stage but a delivered ce_score needs no explanation,
        # so it must not mask a sibling query's deadline drop.
        records = (
            [_record(ce=True, channel=False) for _ in range(90)]
            + [_record(ce=False, reason="DEADLINE_EXCEEDED") for _ in range(10)]
        )
        assert _verdict(records).verdict == "degraded-ce"

    def test_pre_reason_channel_with_full_coverage_is_still_ok(self):
        # Precision: `unevaluable` is about UNEXPLAINED queries. When every query reranked there
        # is nothing left to explain, so an old artifact is legitimately judgeable as clean.
        result = _verdict([_record(ce=True, channel=False) for _ in range(300)])
        assert result.verdict == "ok"
        assert result.coverage == 1.0


class TestStateFromResponse:
    """The live-path reader: raw search responses, as `retriever.retrieve` returns them."""

    def _response(self, *, ce_score, status, reason):
        return {
            "results": [{
                "id": "d1",
                "trace": [
                    {"id": "fusion", "rank": 1, "score": 0.3},
                    *([{"id": "cross-encoder", "rank": 1, "score": ce_score}]
                      if ce_score is not None else []),
                ],
            }],
            "searchTrace": {
                "stages": [
                    {"id": "fusion", "status": "executed"},
                    {"id": "cross-encoder", "status": status, "reason": reason},
                ],
            },
        }

    def test_executed_response_reads_as_applied(self):
        state = state_from_response(self._response(ce_score=-0.2, status="executed", reason=None))
        assert state.applied is True
        assert state.reason is None
        assert state.reason_channel is True

    def test_skipped_response_carries_the_reason(self):
        state = state_from_response(
            self._response(ce_score=None, status="skipped", reason="DEADLINE_EXCEEDED"))
        assert state.applied is False
        assert state.reason == "DEADLINE_EXCEEDED"

    def test_response_with_no_trace_has_no_reason_channel(self):
        state = state_from_response({"results": [{"id": "d1", "trace": []}]})
        assert state.applied is False
        assert state.reason_channel is False

    def test_ce_stage_of_returns_empty_without_a_trace(self):
        assert ce_stage_of({}) == {}

    def test_live_and_artifact_readers_agree(self):
        # The two adapters must classify the same query identically — otherwise the embedded
        # verdict and a post-hoc re-read of the artifact could disagree.
        resp = self._response(ce_score=None, status="skipped", reason="FUSION_CONFIDENT")
        live = state_from_response(resp)
        artifact = state_from_per_query_record({
            "judgeSignals": [{"docId": "d1", "ce_score": None}],
            "crossEncoderStatus": "skipped",
            "crossEncoderReason": "FUSION_CONFIDENT",
        })
        assert (live.applied, live.reason) == (artifact.applied, artifact.reason)


class TestArtifactRoundTrip:
    """Writer → artifact → reader → verdict, closed end to end.

    The real 2026-08-19 campaign artifacts predate the CE reason channel, so they can only prove
    the reader (see test_ce_coverage_real_artifacts.py). This closes the other half: what the
    per-query WRITER emits today must be judgeable, and must reach the same verdict the run
    embedded — otherwise the archived artifact and its summary could disagree.
    """

    def _mode_result(self, responses):
        from types import SimpleNamespace
        return {
            "per_query_metrics": {f"q{i}": {} for i in range(len(responses))},
            "raw_responses": [{**r, "query_id": f"q{i}"} for i, r in enumerate(responses)],
            "scored_docs": [
                SimpleNamespace(query_id=f"q{i}", doc_id="d1", score=1.0)
                for i in range(len(responses))
            ],
        }

    def _response(self, *, ce_score, status, reason):
        return {
            "results": [{
                "fields": {"filename": "d1.txt"},
                "trace": [
                    {"id": "fusion", "rank": 1, "score": 0.3},
                    *([{"id": "cross-encoder", "rank": 1, "score": ce_score}]
                      if ce_score is not None else []),
                ],
            }],
            "searchTrace": {"stages": [
                {"id": "fusion", "status": "executed"},
                {"id": "cross-encoder", "status": status, "reason": reason},
            ]},
        }

    def test_written_entries_carry_the_reason_channel_and_verdict_degraded(self):
        from jseval.artifacts import _build_per_query_entries
        from jseval.ce_coverage import states_from_per_query_records

        responses = (
            [self._response(ce_score=-0.2, status="executed", reason=None) for _ in range(98)]
            + [self._response(ce_score=None, status="skipped", reason="DEADLINE_EXCEEDED")
               for _ in range(102)]
        )
        entries = _build_per_query_entries("hybrid", self._mode_result(responses), qrels={})
        assert len(entries) == 200
        assert {e["crossEncoderStatus"] for e in entries} == {"executed", "skipped"}
        assert {e["crossEncoderReason"] for e in entries} == {None, "DEADLINE_EXCEEDED"}

        result = ce_coverage_verdict(
            states_from_per_query_records(entries), ce_requested=True)
        assert result.verdict == "degraded-ce"
        assert (result.applied, result.silent_drops) == (98, 102)

    def test_written_entries_of_a_clean_run_verdict_ok(self):
        from jseval.artifacts import _build_per_query_entries
        from jseval.ce_coverage import states_from_per_query_records

        responses = (
            [self._response(ce_score=-0.2, status="executed", reason=None) for _ in range(283)]
            + [self._response(ce_score=None, status="skipped", reason="FUSION_CONFIDENT")
               for _ in range(17)]
        )
        entries = _build_per_query_entries("hybrid", self._mode_result(responses), qrels={})
        result = ce_coverage_verdict(
            states_from_per_query_records(entries), ce_requested=True)
        assert result.verdict == "ok"
        assert result.legitimate_skips == 17


class TestCombineModeVerdicts:
    def _result(self, verdict):
        return _verdict(
            {
                "ok": [_record(ce=True)],
                "degraded-ce": [_record(ce=False, reason="DEADLINE_EXCEEDED")],
                "not-applicable": [_record(ce=False, reason="DISABLED")],
                "unevaluable": [_record(ce=False, channel=False)],
            }[verdict],
            ce_requested=verdict not in ("not-applicable",),
        )

    def test_degraded_outranks_everything(self):
        verdict, reasons = combine_mode_verdicts({
            "hybrid": self._result("degraded-ce"),
            "lexical": self._result("not-applicable"),
            "vector": self._result("ok"),
        })
        assert verdict == "degraded-ce"
        assert len(reasons) == 3

    def test_unevaluable_outranks_a_clean_sibling(self):
        verdict, _ = combine_mode_verdicts({
            "hybrid": self._result("unevaluable"), "lexical": self._result("ok"),
        })
        assert verdict == "unevaluable"

    def test_ok_outranks_not_applicable(self):
        verdict, _ = combine_mode_verdicts({
            "hybrid": self._result("ok"), "lexical": self._result("not-applicable"),
        })
        assert verdict == "ok"

    def test_all_not_applicable_stays_not_applicable(self):
        verdict, _ = combine_mode_verdicts({"lexical": self._result("not-applicable")})
        assert verdict == "not-applicable"

    def test_no_modes_is_not_applicable(self):
        verdict, reasons = combine_mode_verdicts({})
        assert verdict == "not-applicable"
        assert reasons
