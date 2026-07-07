"""Tests for the answer-key leak text-scan (tempdoc 624 §As-built #7 follow-up).

`scan_leaked_cells`/`apply_leak_flags`/`scan_leaked_answers` were promoted into
`jseval.agent_utility_run` from the throwaway, uncommitted `_leak_free_recompose.py`
(624 Design 3 refined, "Confidence pass #6") -- this is their FIRST test coverage;
they previously had zero, anywhere in this repo. The real re-analysis run
(`_leak_free_recompose.py`, since retired) found 49 leaked cells across three
corpora with zero false positives on manually spot-checked clean cells -- these
tests pin the same detection method (a case-insensitive `queries.json`/
`queries.jsonl` mention in the agent's completion text) against synthetic
fixtures that mirror the real producer's data flow (real Inspect EvalLogs via
`eval_set`, not a hand-rolled reimplementation of `read_eval_log`'s output shape).
"""

from __future__ import annotations

import pytest

from jseval import agent_manifest
from jseval import agent_utility_run as aur

_COHORT = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
           "judge_kind": "substring-em", "prompt_template_hash": "p"}


# --- scan_leaked_cells (real Inspect EvalLog fixture) ------------------------


def _leak_scan_logs(tmp_path, *, conditions=("A", "C")):
    """Real Inspect EvalLog fixture (mirrors test_utility_judge.py's
    `_calibration_dry_run_logs`): 3 queries per condition -- one clean, one
    leaked via a `queries.json` mention, one leaked via `queries.jsonl`
    (case-varied) -- plus a 4th query whose sample carries `metadata.error` so
    the excluded-cell skip path is also exercised.
    """
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    completions = {
        "q0": "the answer is ANS0",
        "q1": "consult queries.json for the gold answer, then ANS1",
        "q2": "QUERIES.JSONL has everything you need -- ANS2",
        "q3": "unreachable text, this sample errors out before scoring",
    }

    @solver
    def fixed():
        async def solve(state, generate):
            qid = str(state.sample_id).split("|", 1)[-1]
            if qid == "q3":
                state.metadata.update({"error": "timeout"})
                state.output.completion = completions[qid]  # leak text, but excluded via error
                return state
            state.output.completion = completions[qid]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 500, "num_turns": 2})
            return state
        return solve

    @task
    def ct(condition="A"):
        samples = [Sample(id=f"{condition}|{qid}", input=qid, target=f"ANS{qid[1:]}",
                           metadata={"condition": condition}) for qid in completions]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                              "cohort": _COHORT})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([ct(condition=c) for c in conditions], log_dir=log_dir, epochs=1,
              model="mockllm/model", log_format="json")
    return log_dir


class TestScanLeakedCells:
    def test_detects_leak_signature_across_needle_variants(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir = _leak_scan_logs(tmp_path)
        leaked = aur.scan_leaked_cells(log_dir)

        # q1 (queries.json) and q2 (QUERIES.JSONL, case-varied) flagged on BOTH
        # conditions; q0 (clean) never flagged.
        assert set(leaked) == {"A|0|q1", "A|0|q2", "C|0|q1", "C|0|q2"}
        assert leaked["A|0|q1"]["n_matches"] == 1
        assert leaked["A|0|q1"]["qid"] == "q1"
        assert leaked["A|0|q1"]["condition"] == "A"
        assert leaked["A|0|q1"]["seed"] == 0
        assert "queries.json" in leaked["A|0|q1"]["completion_excerpt"]

    def test_clean_cell_is_not_a_false_positive(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir = _leak_scan_logs(tmp_path)
        leaked = aur.scan_leaked_cells(log_dir)
        assert "A|0|q0" not in leaked
        assert "C|0|q0" not in leaked

    def test_errored_sample_is_excluded_from_the_scan(self, tmp_path):
        """A sample carrying `metadata.error` is already an excluded cell (never
        reaches the paired stats regardless), so the scan must not waste a
        finding on it even though its completion text also contains the needle --
        mirrors `eval_logs_to_summaries`'s own `if (s.metadata or {}).get("error")`
        skip precisely, per the promoted code's docstring contract."""
        pytest.importorskip("inspect_ai")
        log_dir = _leak_scan_logs(tmp_path)
        leaked = aur.scan_leaked_cells(log_dir)
        assert "A|0|q3" not in leaked
        assert "C|0|q3" not in leaked

    def test_a_run_with_no_leaks_returns_empty(self, tmp_path):
        pytest.importorskip("inspect_ai")
        from inspect_ai import Task, eval_set, task
        from inspect_ai.dataset import Sample
        from inspect_ai.solver import solver

        from jseval.agent_utility_inspect import substring_scorer

        @solver
        def clean():
            async def solve(state, generate):
                state.output.completion = "a perfectly ordinary answer, ANS0"
                state.metadata.update({"cost_usd": 0.1, "unique_tokens": 500, "num_turns": 1})
                return state
            return solve

        @task
        def ct():
            samples = [Sample(id="q0", input="Q0", target="ANS0")]
            return Task(dataset=samples, solver=clean(), scorer=substring_scorer(),
                        metadata={"condition": "A", "model": "haiku",
                                  "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                                  "cohort": _COHORT})

        log_dir = (tmp_path / "logs").as_posix()
        eval_set([ct()], log_dir=log_dir, epochs=1, model="mockllm/model", log_format="json")
        assert aur.scan_leaked_cells(log_dir) == {}


class TestEvalLogsToSummariesToolCallProjection:
    """`eval_logs_to_summaries` must project the real tool_calls /
    disallowed_tool_calls / leak_suspect_tool_calls that a solver stashes into
    `state.metadata` (as the real `claude_agent_solver` now does, tempdoc 624
    §As-built #5 residual-gap close) through into each per_query entry --
    fixture uses a mock solver so no live `claude` CLI is needed, only a real
    Inspect EvalLog via `eval_set` (mirrors the producer's real data flow)."""

    def _logs(self, tmp_path, *, conditions=("A", "C")):
        from inspect_ai import Task, eval_set, task
        from inspect_ai.dataset import Sample
        from inspect_ai.solver import solver

        from jseval.agent_utility_inspect import substring_scorer

        per_qid = {
            "q0": {"tool_calls": [], "disallowed_tool_calls": [], "leak_suspect_tool_calls": []},
            "q1": {
                "tool_calls": [{"tool": "Bash", "input": {"command": "cat /eval/queries.json"}}],
                "disallowed_tool_calls": [{"tool": "Bash", "input": {"command": "cat /eval/queries.json"}}],
                "leak_suspect_tool_calls": [],
            },
            "q2": {
                "tool_calls": [{"tool": "Read", "input": {"file_path": "/eval/queries.json"}}],
                "disallowed_tool_calls": [],
                "leak_suspect_tool_calls": [{"tool": "Read", "input": {"file_path": "/eval/queries.json"}}],
            },
        }

        @solver
        def fixed():
            async def solve(state, generate):
                qid = str(state.sample_id).split("|", 1)[-1]
                state.output.completion = f"answer for {qid}"
                state.metadata.update({"cost_usd": 0.1, "unique_tokens": 500, "num_turns": 2})
                state.metadata.update(per_qid[qid])
                return state
            return solve

        @task
        def ct(condition="A"):
            samples = [Sample(id=f"{condition}|{qid}", input=qid, target=f"ANS{qid[1:]}",
                               metadata={"condition": condition}) for qid in per_qid]
            return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                        metadata={"model": "haiku",
                                  "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                                  "cohort": _COHORT})

        log_dir = (tmp_path / "logs").as_posix()
        eval_set([ct(condition=c) for c in conditions], log_dir=log_dir, epochs=1,
                  model="mockllm/model", log_format="json")
        return log_dir

    def test_projects_tool_calls_disallowed_and_leak_suspect(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir = self._logs(tmp_path)
        summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
        by_cond = {s["condition"]: s["per_query"] for s in summaries}

        q0 = by_cond["A"]["q0"]
        assert q0["tool_calls"] == []
        assert q0["disallowed_tool_calls"] == []
        assert q0["leak_suspect"] is False

        q1 = by_cond["A"]["q1"]
        assert q1["tool_calls"] == [{"tool": "Bash", "input": {"command": "cat /eval/queries.json"}}]
        assert len(q1["disallowed_tool_calls"]) == 1
        assert q1["leak_suspect"] is False  # disallowed, but not the leak-suspect signature

        q2 = by_cond["C"]["q2"]
        assert len(q2["leak_suspect_tool_calls"]) == 1
        assert q2["leak_suspect"] is True  # derived from the real tool-call capture

    def test_tool_call_assertions_reflect_the_real_projection(self, tmp_path):
        """End-to-end: real EvalLog -> eval_logs_to_summaries -> compose_utility's
        tool_call_assertions rollup, no synthetic per_query dicts."""
        pytest.importorskip("inspect_ai")
        from jseval import utility_comparison as uc

        log_dir = self._logs(tmp_path)
        summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
        rec = uc.compose_utility(summaries, composed_at="t")

        for cond in ("A", "C"):
            tca = rec["tool_call_assertions"][cond]
            assert tca["cells_total"] == 3
            assert tca["cells_with_tool_data"] == 3  # every cell captured a (possibly empty) list
            assert tca["cells_with_disallowed_violations"] == 1  # q1
            assert tca["cells_with_leak_suspect"] == 1  # q2


class TestEvalLogsToSummariesMcpSurfaceProjection:
    """`eval_logs_to_summaries` must project the offered-MCP-tool-surface fields
    (`mcp_servers` / `mcp_tools_offered` / `mcp_surface_unverified`) that
    `claude_agent_solver` stashes into `state.metadata` (tempdoc 624 battlefield
    retrospective) -- mirrors `TestEvalLogsToSummariesToolCallProjection` above,
    same real-EvalLog-via-eval_set fixture style, a mock solver so no live
    `claude` CLI is needed."""

    def _logs(self, tmp_path):
        from inspect_ai import Task, eval_set, task
        from inspect_ai.dataset import Sample
        from inspect_ai.solver import solver

        from jseval.agent_utility_inspect import substring_scorer

        per_qid = {
            "q0": {"mcp_servers": [{"name": "justsearch", "status": "connected"}],
                   "mcp_tools_offered": 2},
            "q1": {"mcp_surface_unverified": True},
            "q2": {},  # neither field set (e.g. condition A)
        }

        @solver
        def fixed():
            async def solve(state, generate):
                qid = str(state.sample_id)
                state.output.completion = f"answer for {qid}"
                state.metadata.update({"cost_usd": 0.1, "unique_tokens": 500, "num_turns": 2})
                state.metadata.update(per_qid[qid])
                return state
            return solve

        @task
        def ct(condition="C"):
            samples = [Sample(id=qid, input=qid, target=f"ANS{qid[1:]}") for qid in per_qid]
            return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                        metadata={"condition": condition, "model": "haiku",
                                  "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                                  "cohort": _COHORT})

        log_dir = (tmp_path / "logs").as_posix()
        eval_set([ct(condition="C")], log_dir=log_dir, epochs=1,
                  model="mockllm/model", log_format="json")
        return log_dir

    def test_projects_mcp_surface_fields(self, tmp_path):
        pytest.importorskip("inspect_ai")
        log_dir = self._logs(tmp_path)
        summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
        per_query = summaries[0]["per_query"]

        q0 = per_query["q0"]
        assert q0["mcp_servers"] == [{"name": "justsearch", "status": "connected"}]
        assert q0["mcp_tools_offered"] == 2
        assert q0["mcp_surface_unverified"] is False

        q1 = per_query["q1"]
        assert q1["mcp_servers"] is None
        assert q1["mcp_tools_offered"] is None
        assert q1["mcp_surface_unverified"] is True

        q2 = per_query["q2"]
        assert q2["mcp_servers"] is None
        assert q2["mcp_tools_offered"] is None
        assert q2["mcp_surface_unverified"] is False


class TestScanLeakedCellsAppliedThroughApplyLeakFlags:
    def test_end_to_end_flags_reach_the_composed_summaries(self, tmp_path):
        """The text-scan backstop (`scan_leaked_cells` + `apply_leak_flags`) stays
        a second, independent detection path even now that `eval_logs_to_summaries`
        also carries real tool-call data (tempdoc 624 §As-built #5 residual-gap
        close): `_leak_scan_logs`'s mock solver never sets `state.metadata["tool_calls"]`
        (it isn't `claude_agent_solver`), so `eval_logs_to_summaries` reports
        `leak_suspect=False`/`tool_calls=None` (no tool data) for every cell
        BEFORE the text scan runs — the text scan is what actually catches the
        `queries.json` mention sitting in the completion text."""
        pytest.importorskip("inspect_ai")
        log_dir = _leak_scan_logs(tmp_path, conditions=("A", "C"))
        summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
        for s in summaries:
            for entry in s["per_query"].values():
                # No tool_calls capture on this mock solver -> "no tool data",
                # not a fabricated 0; leak_suspect is explicitly False (checked
                # via completion text later, not yet flagged).
                assert entry["tool_calls"] is None
                assert entry["leak_suspect"] is False

        leaked = aur.scan_leaked_cells(log_dir)
        n_flagged = aur.apply_leak_flags(summaries, leaked)

        assert n_flagged == 4  # q1 + q2, on both A and C
        by_cond = {s["condition"]: s["per_query"] for s in summaries}
        assert by_cond["A"]["q1"]["leak_suspect"] is True
        assert by_cond["A"]["q2"]["leak_suspect"] is True
        assert by_cond["C"]["q1"]["leak_suspect"] is True
        assert by_cond["C"]["q2"]["leak_suspect"] is True
        # clean cell stays explicitly False (checked, not just absent)
        assert by_cond["A"]["q0"]["leak_suspect"] is False
        assert "q3" not in by_cond["A"]  # errored cell excluded upstream by eval_logs_to_summaries


# --- apply_leak_flags (pure, no inspect_ai needed) ---------------------------


def _mfst(**over):
    base = dict(
        corpus={"dataset": "mixed/multihop-rag", "signature": "sig-mh"},
        agent_model="haiku", agent_model_version="4.5", cli_version="2.1.183",
        mcp_tool_surface=None, judge=agent_manifest.judge_identity(kind="substring-em"),
        prompt_template="t", condition="A", seed=0, search_config_cohort_key=None,
    )
    base.update(over)
    return agent_manifest.build_agent_manifest(**base)


def _summary(condition, seed, per_query):
    m = _mfst(condition=condition, seed=seed)
    return {"manifest": m, "condition": condition, "agent_model": "haiku",
            "corpus": m["corpus"], "per_query": per_query}


class TestApplyLeakFlags:
    def test_flags_only_the_matched_cell(self):
        summaries = [
            _summary("A", 0, {"q0": {"correct": True}, "q1": {"correct": False}}),
            _summary("C", 1, {"q0": {"correct": True}}),
        ]
        leaked = {"A|0|q1": {"condition": "A", "seed": 0, "qid": "q1"}}
        n = aur.apply_leak_flags(summaries, leaked)
        assert n == 1
        assert summaries[0]["per_query"]["q1"]["leak_suspect"] is True
        assert "leak_suspect" not in summaries[0]["per_query"]["q0"]
        assert "leak_suspect" not in summaries[1]["per_query"]["q0"]

    def test_no_false_positive_on_a_clean_summary_set(self):
        summaries = [
            _summary("A", 0, {"q0": {"correct": True}, "q1": {"correct": False}}),
            _summary("C", 0, {"q0": {"correct": True}, "q1": {"correct": True}}),
        ]
        n = aur.apply_leak_flags(summaries, {})
        assert n == 0
        for s in summaries:
            for entry in s["per_query"].values():
                assert "leak_suspect" not in entry

    def test_matches_on_seed_not_just_condition_and_qid(self):
        """The same (condition, qid) at a DIFFERENT seed must not be flagged --
        the key is `{condition}|{seed}|{qid}`, all three components load-bearing."""
        summaries = [
            _summary("A", 0, {"q0": {"correct": True}}),
            _summary("A", 1, {"q0": {"correct": True}}),
        ]
        leaked = {"A|0|q0": {"condition": "A", "seed": 0, "qid": "q0"}}
        n = aur.apply_leak_flags(summaries, leaked)
        assert n == 1
        assert summaries[0]["per_query"]["q0"]["leak_suspect"] is True
        assert "leak_suspect" not in summaries[1]["per_query"]["q0"]

    def test_returns_zero_on_empty_summaries(self):
        assert aur.apply_leak_flags([], {"A|0|q0": {}}) == 0


# --- scan_leaked_answers (the non-Inspect `run_agent_eval` path adapter) ----


class TestScanLeakedAnswers:
    def test_detects_leak_signature_in_agent_answer_text(self):
        results = [
            {"query": "q0", "agent_answer": "the answer is ANS0"},
            {"query": "q1", "agent_answer": "see queries.json, the answer is ANS1"},
        ]
        leaked = aur.scan_leaked_answers(results, condition="C", seed=2)
        assert set(leaked) == {"C|2|q1"}
        assert leaked["C|2|q1"]["condition"] == "C"
        assert leaked["C|2|q1"]["seed"] == 2
        assert leaked["C|2|q1"]["qid"] == "q1"
        assert leaked["C|2|q1"]["n_matches"] == 1

    def test_clean_results_produce_no_false_positive(self):
        results = [
            {"query": "q0", "agent_answer": "the answer is ANS0"},
            {"query": "q1", "agent_answer": "totally unrelated text, ANS1"},
        ]
        leaked = aur.scan_leaked_answers(results, condition="A", seed=0)
        assert leaked == {}

    def test_missing_agent_answer_field_does_not_crash(self):
        results = [{"query": "q0"}]
        assert aur.scan_leaked_answers(results, condition="A", seed=0) == {}

    def test_result_without_query_is_skipped(self):
        results = [{"agent_answer": "mentions queries.json but has no query id"}]
        assert aur.scan_leaked_answers(results, condition="A", seed=0) == {}

    def test_feeds_apply_leak_flags_unmodified(self):
        """scan_leaked_answers's output shape must be directly consumable by
        apply_leak_flags (the shared downstream consumer both scan functions
        target) -- proves the two promoted sources are interchangeable, not just
        superficially similar."""
        results = [{"query": "q0", "agent_answer": "consult queries.jsonl please, ANS0"}]
        leaked = aur.scan_leaked_answers(results, condition="A", seed=0)
        summary = _summary("A", 0, {"q0": {"correct": True}})
        n = aur.apply_leak_flags([summary], leaked)
        assert n == 1
        assert summary["per_query"]["q0"]["leak_suspect"] is True
