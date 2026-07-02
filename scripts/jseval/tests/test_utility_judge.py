"""Tests for the judge human-calibration machinery (tempdoc 624 §M.4 / §T.3).

`agreement_rate` in `judge_logs`'s stats block is a self-consistency statistic
(judge vs. the cheap EM fallback) -- these tests cover the machinery that
validates the judge against an INDEPENDENT signal instead: stratified sampling,
Cohen's kappa (+ bootstrap CI), and the additive `human_calibration` overlay
schema extension. The end-to-end dry run substitutes two deterministic
agent-substitute heuristics for real human raters (none are available in this
autonomous pipeline) -- see `test_dry_run_end_to_end` for the honesty check on
the emitted `rater_kind` field.
"""

from __future__ import annotations

import json

import pytest

from jseval import utility_judge as uj


# --- sample_for_calibration --------------------------------------------------


def _make_scores(n_total: int, disagreement_keys: set[str]) -> dict:
    """A synthetic scores dict: `disagreement_keys` get judge != em (a rescue);
    everything else is a plain EM auto-pass (judge=None, em=True)."""
    scores = {}
    for i in range(n_total):
        key = f"C|0|q{i:03d}"
        if key in disagreement_keys:
            scores[key] = {"em": False, "judge": True, "final": True}
        else:
            scores[key] = {"em": True, "judge": None, "final": True}
    return scores


class TestSampleForCalibration:
    def test_deterministic_given_seed(self):
        scores = _make_scores(100, {f"C|0|q{i:03d}" for i in range(5)})
        s1 = uj.sample_for_calibration(scores, n=20, seed=7)
        s2 = uj.sample_for_calibration(scores, n=20, seed=7)
        assert s1 == s2

    def test_different_seeds_can_differ(self):
        scores = _make_scores(100, {f"C|0|q{i:03d}" for i in range(5)})
        s1 = uj.sample_for_calibration(scores, n=20, seed=1)
        s2 = uj.sample_for_calibration(scores, n=20, seed=2)
        assert s1 != s2  # not a hard guarantee in general, but true for this input/n

    def test_oversamples_disagreement_relative_to_natural_frequency(self):
        # 5/100 = 5% natural disagreement rate. n=20 should draw far above 5% of
        # the sample from that stratum (all 5 disagreement keys are pulled in).
        dis_keys = {f"C|0|q{i:03d}" for i in range(5)}
        scores = _make_scores(100, dis_keys)
        sample = uj.sample_for_calibration(scores, n=20, seed=0, disagreement_frac=0.6)
        assert len(sample) == 20
        sampled_dis = [k for k in sample if k in dis_keys]
        # natural expectation at 5% would be ~1 of 20; oversampling pulls in all 5.
        assert len(sampled_dis) == 5
        assert (len(sampled_dis) / len(sample)) > (len(dis_keys) / len(scores))

    def test_n_larger_than_population_returns_everything(self):
        scores = _make_scores(10, {"C|0|q000"})
        sample = uj.sample_for_calibration(scores, n=200, seed=0)
        assert set(sample) == set(scores.keys())

    def test_empty_scores_returns_empty(self):
        assert uj.sample_for_calibration({}, n=20) == []

    def test_n_zero_or_negative_returns_empty(self):
        scores = _make_scores(10, set())
        assert uj.sample_for_calibration(scores, n=0) == []
        assert uj.sample_for_calibration(scores, n=-3) == []


# --- cohens_kappa / bootstrap_kappa_ci ---------------------------------------


class TestCohensKappa:
    def test_perfect_agreement_is_one(self):
        labels = [True, False, True, True, False, False, True, False]
        assert uj.cohens_kappa(labels, labels) == pytest.approx(1.0, abs=1e-9)

    def test_degenerate_all_same_label_does_not_divide_by_zero(self):
        labels = [True] * 10
        assert uj.cohens_kappa(labels, labels) == pytest.approx(1.0, abs=1e-9)

    def test_wikipedia_worked_example(self):
        # Classic Cohen's kappa worked example (50 subjects): both-yes=20,
        # A-yes/B-no=5, A-no/B-yes=10, both-no=15 -> kappa = 0.40.
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        assert len(labels_a) == len(labels_b) == 50
        kappa = uj.cohens_kappa(labels_a, labels_b)
        assert kappa == pytest.approx(0.4, abs=1e-9)

    def test_mismatched_lengths_raises(self):
        with pytest.raises(ValueError):
            uj.cohens_kappa([True, False], [True])

    def test_empty_raises(self):
        with pytest.raises(ValueError):
            uj.cohens_kappa([], [])


class TestBootstrapKappaCi:
    def test_ci_present_and_sane_on_partial_agreement_case(self):
        # Same Wikipedia worked example as above -- a genuine partial-agreement
        # case, so the CI should be a real (non-degenerate) interval.
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        lo, hi = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=1000, seed=42)
        assert lo < hi  # not degenerate
        assert -1.0 <= lo <= 1.0
        assert -1.0 <= hi <= 1.0

    def test_deterministic_given_seed(self):
        labels_a = [True] * 20 + [True] * 5 + [False] * 10 + [False] * 15
        labels_b = [True] * 20 + [False] * 5 + [True] * 10 + [False] * 15
        ci1 = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=500, seed=13)
        ci2 = uj.bootstrap_kappa_ci(labels_a, labels_b, n_resamples=500, seed=13)
        assert ci1 == ci2

    def test_single_item_returns_point_estimate_twice(self):
        lo, hi = uj.bootstrap_kappa_ci([True], [True])
        assert lo == hi == pytest.approx(1.0)

    def test_empty_raises(self):
        with pytest.raises(ValueError):
            uj.bootstrap_kappa_ci([], [])


# --- rater_majority_vote / rater_agreement_report ----------------------------


class TestRaterMajorityVote:
    def test_agreement_and_ties(self):
        raters = [
            [True, False, True, False],
            [True, False, False, True],
        ]
        assert uj.rater_majority_vote(raters) == [True, False, None, None]

    def test_empty_raters_list(self):
        assert uj.rater_majority_vote([]) == []


class TestRaterAgreementReport:
    def test_requires_at_least_two_raters(self):
        with pytest.raises(ValueError):
            uj.rater_agreement_report([True, False], [[True, False]])

    def test_three_raters_not_yet_implemented(self):
        # Krippendorff's alpha (3+) is a named-but-deferred generalization point
        # (tempdoc 624 §M.4/§T.3) -- must fail loudly, not silently degrade.
        raters = [[True, False], [True, False], [False, True]]
        with pytest.raises(NotImplementedError):
            uj.rater_agreement_report([True, False], raters)

    def test_basic_shape_and_bounds(self):
        judge = [True, True, False, False, True, False, True, False]
        rater_a = [True, True, False, True, True, False, True, False]
        rater_b = [True, False, False, False, True, False, True, True]
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=500, seed=1)
        assert report["n"] == 8
        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            block = report[block_name]
            assert -1.0 <= block["value"] <= 1.0
            assert block["ci_low"] <= block["ci_high"]

    def test_ties_are_dropped_and_counted(self):
        # rater_a/rater_b disagree on every item -> majority is always None.
        judge = [True, False, True]
        rater_a = [True, False, True]
        rater_b = [False, True, False]
        report = uj.rater_agreement_report(judge, [rater_a, rater_b], n_resamples=200, seed=1)
        assert report["n_dropped_ties"] == 3
        assert report["judge_vs_rater_agreement"]["value"] is None


# --- overlay schema extension (write_overlay round-trip) ---------------------


class TestOverlaySchemaExtension:
    def test_human_calibration_round_trips_without_clobbering_existing_fields(self, tmp_path):
        overlay = {
            "judge_identity": {"kind": "hybrid-em-llm", "model": "m"},
            "stats": {"em_auto_pass": 3, "judged_misses": 2, "agreement_rate": 0.9},
            "scores": {"C|0|q0": {"em": True, "judge": None, "final": True}},
            "human_calibration": {
                "rater_kind": "agent-substitute, NOT human",
                "n": 6,
                "sample_qids": ["C|0|q0"],
                "judge_vs_rater_agreement": {"value": 0.5, "ci_low": 0.1, "ci_high": 0.8},
                "rater_vs_rater_agreement": {"value": 0.7, "ci_low": 0.3, "ci_high": 0.95},
            },
        }
        path = uj.write_overlay(tmp_path.as_posix(), overlay)
        on_disk = json.loads((tmp_path / "judge-overlay.json").read_text(encoding="utf-8"))
        assert path.endswith("judge-overlay.json")
        assert on_disk == overlay
        # Existing fields untouched by the extension:
        assert on_disk["judge_identity"] == overlay["judge_identity"]
        assert on_disk["stats"] == overlay["stats"]
        assert on_disk["scores"] == overlay["scores"]
        # New field present + honest about what it is:
        assert on_disk["human_calibration"]["rater_kind"] == "agent-substitute, NOT human"

    def test_overlay_without_human_calibration_still_writes_fine(self, tmp_path):
        # additive means optional: an overlay with no calibration block at all
        # (the normal `judge_logs` output, unchanged) must still round-trip.
        overlay = {"judge_identity": {"kind": "substring-em"}, "stats": {}, "scores": {}}
        path = uj.write_overlay(tmp_path.as_posix(), overlay)
        on_disk = json.loads((tmp_path / "judge-overlay.json").read_text(encoding="utf-8"))
        assert "human_calibration" not in on_disk
        assert path.endswith("judge-overlay.json")


# --- agent-substitute heuristic raters ---------------------------------------


class TestRaterSubstituteHeuristics:
    def test_token_overlap_true_on_match(self):
        assert uj._rater_substitute_token_overlap("Q", "ANS0", "the answer is ANS0") is True

    def test_token_overlap_false_on_miss(self):
        assert uj._rater_substitute_token_overlap("Q", "ANS0", "totally unrelated") is False

    def test_containment_true_on_match(self):
        assert uj._rater_substitute_containment("Q", "ANS0", "the answer is ans0 here") is True

    def test_containment_false_on_miss(self):
        assert uj._rater_substitute_containment("Q", "ANS0", "totally unrelated") is False

    def test_two_heuristics_are_not_the_same_function(self):
        # Distinct decision rules is a design requirement (not the same check
        # under two names) -- construct an input where they diverge.
        # Reference has one short (<=2 char) token and one long token; candidate
        # contains the short token as a whole word but not the long anchor word.
        reference = "ok reallylongdistinctiveword"
        candidate = "ok but nothing else matches"
        overlap = uj._rater_substitute_token_overlap("Q", reference, candidate)
        contains = uj._rater_substitute_containment("Q", reference, candidate)
        assert (overlap, contains) == (True, False)


# --- end-to-end mechanism-proving dry run ------------------------------------


_COHORT = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
           "judge_kind": "substring-em", "prompt_template_hash": "p"}


def _calibration_dry_run_logs(tmp_path):
    """8-query EvalLog fixture: 2 EM-passes, 3 judge-rescued EM-misses (the
    disagreement stratum), 3 judge-confirmed EM-misses (agreement stratum)."""
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    answers = {
        "q0": "the answer is ANS0", "q1": "the answer is ANS1",
        "q2": "yellow RESCUE fruit", "q3": "yellow RESCUE fruit", "q4": "yellow RESCUE fruit",
        "q5": "totally wrong", "q6": "still wrong", "q7": "nope wrong",
    }

    @solver
    def fixed():
        async def solve(state, generate):
            state.output.completion = answers[str(state.sample_id)]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3})
            return state
        return solve

    @task
    def ct():
        samples = [Sample(id=f"q{i}", input=f"Q{i}", target=f"ANS{i}") for i in range(8)]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"condition": "C", "model": "haiku",
                              "corpus": {"dataset": "d", "signature": "s"}, "cohort": _COHORT})

    log = (tmp_path / "j").as_posix()
    eval_set([ct()], log_dir=log, epochs=1, model="mockllm/model", log_format="json")
    return log


def _fake_judge_post(url, json=None, timeout=None):
    """Both dual-order calls agree: YES iff the candidate contains RESCUE."""
    class _Resp:
        def __init__(self, c):
            self._c = c

        def json(self):
            return {"choices": [{"message": {"content": self._c}}]}

    user = json["messages"][1]["content"]
    return _Resp("YES" if "RESCUE" in user else "NO")


class TestCalibrationDryRunEndToEnd:
    def test_dry_run_end_to_end(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)

        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        overlay = uj.judge_logs(log, judge_url="http://x")
        assert overlay["stats"]["judge_flips"] == 3       # q2,q3,q4 rescued
        assert overlay["stats"]["judge_disagreements"] == 0

        uj.attach_human_calibration(overlay, log, n=6, seed=0)
        calib = overlay["human_calibration"]

        # The honesty check this whole feature exists for:
        assert calib["rater_kind"] == "agent-substitute, NOT human"
        assert calib["n"] == 6
        assert len(calib["sample_qids"]) == 6
        # All 3 disagreement (rescue) items are pulled into a 6-item sample
        # (default disagreement_frac=0.6 -> target 3.6, capped at the 3 that exist).
        rescued_keys = {f"C|0|q{i}" for i in (2, 3, 4)}
        assert rescued_keys.issubset(set(calib["sample_qids"]))

        for block_name in ("judge_vs_rater_agreement", "rater_vs_rater_agreement"):
            block = calib[block_name]
            assert block["value"] is not None
            assert -1.0 <= block["value"] <= 1.0
            assert block["ci_low"] <= block["ci_high"]

        # Round-trips through write_overlay/JSON like every other overlay field.
        path = uj.write_overlay(log, overlay)
        on_disk = json.loads(open(path, encoding="utf-8").read())
        assert on_disk["human_calibration"]["rater_kind"] == "agent-substitute, NOT human"
        assert on_disk["human_calibration"] == calib
        assert on_disk["scores"] == overlay["scores"]  # existing field untouched

    def test_dry_run_degrades_gracefully_with_too_small_a_sample(self, tmp_path, monkeypatch):
        pytest.importorskip("inspect_ai")
        log = _calibration_dry_run_logs(tmp_path)
        monkeypatch.setattr(uj.httpx, "post", _fake_judge_post)
        monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

        overlay = uj.judge_logs(log, judge_url="http://x")
        result = uj.run_calibration_dry_run(log, overlay, n=1, seed=0)
        assert result["rater_kind"] == "agent-substitute, NOT human"
        assert result["n"] <= 1
        assert result["judge_vs_rater_agreement"]["value"] is None
        assert "note" in result
