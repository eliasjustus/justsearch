"""Unit tests for the agent-utility comparison record (tempdoc 624).

Pure-function tests over agent-manifest + summary-shaped dicts (mirrors
``test_release.py``'s inline-fixture style — NO live claude / dev stack needed).
Proves the cohort-identity, pairing, McNemar, and composer machinery.
"""

from __future__ import annotations

import pytest

from jseval import agent_manifest, compare_runs, utility_comparison

_TOOLS = [{"name": "justsearch_answer", "description": "RAG", "inputSchema": {}}]


def _mfst(**over):
    base = dict(
        corpus={"dataset": "mixed/multihop-rag", "signature": "sig-mh"},
        agent_model="haiku",
        agent_model_version="4.5",
        cli_version="2.1.183",
        mcp_tool_surface=_TOOLS,
        judge=agent_manifest.judge_identity(kind="substring-em"),
        prompt_template="Answer using only {corpus}. Q: {q}",
        condition="A",
        seed=0,
        search_config_cohort_key=None,
    )
    base.update(over)
    return agent_manifest.build_agent_manifest(**base)


def _summary(condition, per_query, *, search_key=None, **mfst_over):
    m = _mfst(condition=condition, search_config_cohort_key=search_key, **mfst_over)
    return {
        "manifest": m,
        "condition": condition,
        "agent_model": m["agent_model"],
        "corpus": m["corpus"],
        "per_query": per_query,
    }


# --- agent_cohort_key invariances (R1/R2) -----------------------------------

def test_cohort_key_invariant_across_range_axes():
    """Same harness, different corpus/model/condition/seed -> SAME cohort key.

    These are the axes a utility record ranges over; they must NOT split cohorts.
    """
    base = _mfst()
    assert agent_manifest.agent_cohort_key(base) == agent_manifest.agent_cohort_key(
        _mfst(corpus={"dataset": "beir/scifact", "signature": "x"}))
    assert agent_manifest.agent_cohort_key(base) == agent_manifest.agent_cohort_key(
        _mfst(agent_model="opus"))
    assert agent_manifest.agent_cohort_key(base) == agent_manifest.agent_cohort_key(
        _mfst(condition="C", search_config_cohort_key="search-XYZ"))
    assert agent_manifest.agent_cohort_key(base) == agent_manifest.agent_cohort_key(
        _mfst(seed=7))


def test_cohort_key_differs_on_harness_identity():
    """Judge / CLI / tool-surface / prompt change -> DIFFERENT cohort key."""
    base = _mfst()
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(cli_version="2.2.0"))
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(mcp_tool_surface=_TOOLS + [{"name": "z", "description": "", "inputSchema": {}}]))
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(judge=agent_manifest.judge_identity(
            kind="llm-judge", model="sonnet", version="4.6", prompt_hash="p")))
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(prompt_template="different"))


def test_pairing_key_pairs_A_and_C_excludes_condition_and_search_config():
    """A and C with same (corpus, model, seed) pair; differ only on condition (R2)."""
    a = _mfst(condition="A", search_config_cohort_key=None)
    c = _mfst(condition="C", search_config_cohort_key="search-XYZ")
    assert agent_manifest.pairing_key(a) == agent_manifest.pairing_key(c)
    # different seed -> different pair
    assert agent_manifest.pairing_key(a) != agent_manifest.pairing_key(_mfst(seed=1))


def test_tool_surface_hash_order_independent():
    h1 = agent_manifest.mcp_tool_surface_hash(
        [{"name": "b", "description": "", "inputSchema": {}},
         {"name": "a", "description": "", "inputSchema": {}}])
    h2 = agent_manifest.mcp_tool_surface_hash(
        [{"name": "a", "description": "", "inputSchema": {}},
         {"name": "b", "description": "", "inputSchema": {}}])
    assert h1 == h2
    assert h1 != agent_manifest.mcp_tool_surface_hash(None)  # arm A sentinel differs


# --- McNemar (R3) -----------------------------------------------------------

def test_mcnemar_counts_and_delta():
    a = {"q1": False, "q2": True, "q3": False, "q4": True}
    c = {"q1": True, "q2": True, "q3": True, "q4": False}
    r = compare_runs.mcnemar(a, c)
    assert r["accuracy_a"] == 0.5 and r["accuracy_b"] == 0.75
    assert r["accuracy_delta"] == 0.25
    assert r["n_b_only_correct"] == 2  # tool fixes q1, q3
    assert r["n_a_only_correct"] == 1  # tool breaks q4
    assert r["test"] == "exact-binomial"
    assert 0.0 <= r["p_value"] <= 1.0


def test_mcnemar_no_discordant():
    a = {"q1": True, "q2": False}
    r = compare_runs.mcnemar(a, dict(a))
    assert r["n_discordant"] == 0 and r["p_value"] == 1.0


# --- composer (the canonical record) ----------------------------------------

def _cell_pq(correct_a, correct_c):
    """Build 4-query per-arm per_query dicts; C is cheaper + fewer tokens."""
    a, c = {}, {}
    for i in range(4):
        q = f"q{i}"
        a[q] = {"correct": correct_a[i], "cost_usd": 0.13, "unique_tokens": 42000, "num_turns": 18}
        c[q] = {"correct": correct_c[i], "cost_usd": 0.08, "unique_tokens": 26000, "num_turns": 12}
    return a, c


def test_compose_builds_valid_record():
    a_pq, c_pq = _cell_pq([False, True, False, True], [True, True, True, False])
    summaries = [
        _summary("A", a_pq, search_key=None),
        _summary("C", c_pq, search_key="search-XYZ"),
    ]
    rec = utility_comparison.compose_utility(
        summaries, composed_at="2026-06-21T00:00:00Z",
        contamination_class="public-pre-cutoff", confidence_tier="C")

    assert rec["schema"] == "utility-comparison.v1"
    assert rec["cohort"]["search_config_cohort_key"] == "search-XYZ"
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["accuracy"]["delta"] == 0.25
    assert "mcnemar_p" in cell["accuracy"]
    # token-efficiency (the contamination-robust headline): C uses fewer unique tokens
    assert cell["tokens_unique"]["with_tool"]["median"] == 26000
    assert cell["tokens_unique"]["delta_mean"] < 0          # fewer tokens with tool
    assert cell["cost_usd"]["delta_mean"] < 0               # cheaper with tool
    assert rec["coverage"]["contamination_class"] == "public-pre-cutoff"
    assert "SUBSTITUTION" in rec["coverage"]["does_not_measure"]  # C-4 caveat present


def test_compose_refuses_mixed_harness_cohort():
    a_pq, c_pq = _cell_pq([True] * 4, [True] * 4)
    summaries = [
        _summary("A", a_pq),
        _summary("C", c_pq, cli_version="9.9.9", search_key="s"),  # different harness
    ]
    with pytest.raises(utility_comparison.UtilityComposeError, match="agent_cohort_key differs"):
        utility_comparison.compose_utility(summaries, composed_at="t")


def test_compose_refuses_mixed_search_config():
    a_pq, c_pq = _cell_pq([True] * 4, [True] * 4)
    summaries = [
        _summary("A", a_pq),
        _summary("C", c_pq, search_key="search-1"),
        _summary("C", dict(c_pq), search_key="search-2"),  # with-tool arms disagree
    ]
    with pytest.raises(utility_comparison.UtilityComposeError, match="multiple search configs"):
        utility_comparison.compose_utility(summaries, composed_at="t")


def test_compose_skips_cell_missing_an_arm():
    a_pq, _ = _cell_pq([True] * 4, [True] * 4)
    rec = utility_comparison.compose_utility(
        [_summary("A", a_pq)], composed_at="t")  # no with-tool arm
    assert rec["measured"] == {}


def test_seed_aggregation_envelope():
    summaries = []
    for seed in (0, 1, 2):
        # perturb per seed so the arms aren't degenerate-constant (realistic noise)
        a_pq, c_pq = _cell_pq([False, True, False, True], [True, True, True, True])
        for q in a_pq:
            a_pq[q] = {**a_pq[q], "cost_usd": 0.13 + 0.01 * seed}
            c_pq[q] = {**c_pq[q], "cost_usd": 0.08 + 0.005 * seed,
                       "unique_tokens": 26000 + 500 * seed}
        summaries.append(_summary("A", a_pq, seed=seed))
        summaries.append(_summary("C", c_pq, seed=seed, search_key="s"))
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert rec["seed_count"] == 3
    assert cell["accuracy"]["seed_envelope_with_tool"]["n"] == 3
    assert cell["n_paired_observations"] == 12  # 3 seeds x 4 queries


# --- Answer-key leak detection backstop (tempdoc 624 §As-built #7) ----------
#
# A real leak was found where an agent under a file-tool condition read the
# eval's own gold-answer file (queries.json) directly, producing a
# leaked-but-correct answer indistinguishable from a genuine one by any
# existing check. These tests prove the composer-level backstop: a flagged
# (seed, qid) observation is EXCLUDED from the paired statistics (never enters
# McNemar/bootstrap-CI) and surfaced separately in `leak_suspect_cells`, not
# silently dropped from the record and not silently counted as a win.

def test_leak_suspect_query_excluded_from_paired_stats_and_surfaced():
    a_pq, c_pq = _cell_pq([False, True, False, True], [True, True, True, False])
    # q1 is leak-suspect on the with-tool arm — its favorable "correct=True"
    # must not count toward the with-tool accuracy or the paired n.
    c_pq["q1"] = {**c_pq["q1"], "leak_suspect": True}
    summaries = [
        _summary("A", a_pq, search_key=None),
        _summary("C", c_pq, search_key="search-XYZ"),
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["n_paired_observations"] == 3  # q1 excluded, 3 of 4 remain
    assert cell["leak_suspect_cells"] == [
        {"seed": 0, "qid": "q1", "baseline_leak_suspect": False, "with_tool_leak_suspect": True},
    ]


def test_no_leak_suspect_signal_no_false_positive():
    """A clean run (no `leak_suspect` key on any per_query entry, the shape
    every pre-existing summary already has) reports an empty
    `leak_suspect_cells` and keeps every observation paired — the backstop
    must not fire on ordinary, unflagged data."""
    a_pq, c_pq = _cell_pq([False, True, False, True], [True, True, True, False])
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["leak_suspect_cells"] == []
    assert cell["n_paired_observations"] == 4


def test_end_to_end_agent_result_leak_flag_reaches_composed_cell():
    """Full pipeline: agent_retrieval_eval.find_leak_suspect_tool_calls's verdict
    on a raw AgentResult-shaped record (as `_aggregate_agent` emits it) survives
    agent_utility_run._per_query_from_result's reshape and lands as an
    excluded+flagged observation in the composed cell."""
    from jseval import agent_utility_run as aur

    def _raw_results(corrects, leaked_qids=()):
        out = []
        for i, correct in enumerate(corrects):
            qid = f"q{i}"
            out.append({
                "query": qid, "correct": correct, "cost_usd": 0.10,
                "cache_creation_tokens": 4000, "num_turns": 5,
                "leak_suspect_tool_calls": (
                    [{"tool": "Read", "input": {"file_path": "/eval/queries.json"}}]
                    if qid in leaked_qids else []
                ),
            })
        return {"results": out}

    corpus = {"dataset": "mixed/multihop-rag", "signature": "sig-mh"}
    manifest_a = agent_manifest.build_agent_manifest(
        corpus=corpus, agent_model="haiku", agent_model_version=None,
        cli_version="2.1.183", mcp_tool_surface=None,
        judge=agent_manifest.judge_identity(kind="substring-em"),
        prompt_template="t", condition="A", seed=0, search_config_cohort_key=None)
    manifest_c = agent_manifest.build_agent_manifest(
        corpus=corpus, agent_model="haiku", agent_model_version=None,
        cli_version="2.1.183", mcp_tool_surface=None,
        judge=agent_manifest.judge_identity(kind="substring-em"),
        prompt_template="t", condition="C", seed=0, search_config_cohort_key="search-1")

    summary_a = {
        "manifest": manifest_a, "condition": "A", "agent_model": "haiku", "corpus": corpus,
        "per_query": aur._per_query_from_result(_raw_results([True, True])),
    }
    summary_c = {
        "manifest": manifest_c, "condition": "C", "agent_model": "haiku", "corpus": corpus,
        "per_query": aur._per_query_from_result(
            _raw_results([True, True], leaked_qids={"q0"})),
    }

    rec = utility_comparison.compose_utility([summary_a, summary_c], composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["n_paired_observations"] == 1
    assert cell["leak_suspect_cells"][0]["qid"] == "q0"
    assert cell["leak_suspect_cells"][0]["with_tool_leak_suspect"] is True


# --- Stratified capability-coverage (tempdoc 624 §T.4 / §M.8 item 7) --------

def test_stratified_breakdown_reveals_offsetting_substrata_signal():
    """Two corpus-signature strata within one cell (a corpus refresh between
    seeds, so seed 0's queries and seed 1's queries carry different corpus
    ``signature`` tags) with OPPOSITE accuracy deltas that cancel out in the
    pooled number — the exact 'a pooled delta can hide a real signal' case
    §T.4 motivates. The default ``qid -> corpus`` stratify map is derived
    automatically (no caller-supplied mapping)."""
    # Seed 0 / corpus signature "sig-old": the tool helps a lot (+0.5).
    a0 = {
        "q0": {"correct": False, "cost_usd": 0.10, "unique_tokens": 1000, "num_turns": 5},
        "q1": {"correct": True, "cost_usd": 0.10, "unique_tokens": 1000, "num_turns": 5},
        "q2": {"correct": False, "cost_usd": 0.10, "unique_tokens": 1000, "num_turns": 5},
        "q3": {"correct": True, "cost_usd": 0.10, "unique_tokens": 1000, "num_turns": 5},
    }
    c0 = {
        "q0": {"correct": True, "cost_usd": 0.05, "unique_tokens": 500, "num_turns": 5},
        "q1": {"correct": True, "cost_usd": 0.05, "unique_tokens": 500, "num_turns": 5},
        "q2": {"correct": True, "cost_usd": 0.05, "unique_tokens": 500, "num_turns": 5},
        "q3": {"correct": True, "cost_usd": 0.05, "unique_tokens": 500, "num_turns": 5},
    }
    # Seed 1 / corpus signature "sig-new": the tool HURTS (-0.5), no cost delta.
    a1 = {
        "r0": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r1": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r2": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r3": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
    }
    c1 = {
        "r0": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r1": {"correct": False, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r2": {"correct": True, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
        "r3": {"correct": False, "cost_usd": 0.20, "unique_tokens": 2000, "num_turns": 5},
    }
    sig_old = {"dataset": "mixed/multihop-rag", "signature": "sig-old"}
    sig_new = {"dataset": "mixed/multihop-rag", "signature": "sig-new"}

    summaries = [
        _summary("A", a0, seed=0, corpus=sig_old),
        _summary("C", c0, seed=0, corpus=sig_old, search_key="s"),
        _summary("A", a1, seed=1, corpus=sig_new),
        _summary("C", c1, seed=1, corpus=sig_new, search_key="s"),
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]

    # Pooled: the two strata offset exactly -> looks like NO effect at all.
    assert cell["accuracy"]["delta"] == 0.0
    assert cell["accuracy"]["mcnemar_p"] == 1.0
    assert cell["n_paired_observations"] == 8

    strata = cell["stratified"]["by_stratum"]
    old_label = "mixed/multihop-rag:sig-old"
    new_label = "mixed/multihop-rag:sig-new"
    assert set(strata) == {old_label, new_label}

    old, new = strata[old_label], strata[new_label]

    # Each stratum independently reveals the real, OPPOSITE-sign signal the
    # pooled delta hides — same shape (accuracy/tokens_unique/cost_usd/turns/
    # n_paired_observations) as the pooled block.
    assert old["accuracy"]["delta"] == 0.5
    assert new["accuracy"]["delta"] == -0.5
    assert old["n_paired_observations"] == 4
    assert new["n_paired_observations"] == 4

    # §M.8 item 7: each stratum's significance test is its OWN, computed on its
    # own n — NOT inherited/borrowed from the pooled result (pooled p=1.0 above;
    # both strata resolve to a different, independently-derived p-value).
    assert old["accuracy"]["mcnemar_p"] == 0.5
    assert new["accuracy"]["mcnemar_p"] == 0.5
    assert old["accuracy"]["mcnemar_p"] != cell["accuracy"]["mcnemar_p"]

    # Cost/token blocks are independently computed per stratum too (sig-old is
    # cheaper+fewer-tokens with the tool; sig-new has no cost/token difference
    # at all — the pooled cost/token deltas would average these together):
    assert old["cost_usd"]["delta_mean"] < 0
    assert new["cost_usd"]["delta_mean"] == 0.0
    assert old["tokens_unique"]["delta_mean"] < 0
    assert new["tokens_unique"]["delta_mean"] == 0.0


def test_no_stratify_field_when_cell_is_single_corpus():
    """Regression guard: when every summary in a cell shares ONE corpus
    identity (the common case — no corpus refresh mid-cell), no ``stratified``
    field is added anywhere on the composed cell. Byte-identical to the
    pre-stratification composed-cell shape (tempdoc 624 §T.4: additive-only,
    never a behavior change for existing callers)."""
    a_pq, c_pq = _cell_pq([False, True, False, True], [True, True, True, False])
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="search-XYZ")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert "stratified" not in cell
    assert "stratified" not in cell["arms"]["substitution_c"]


# --- Inspect-AI execution path (tempdoc 624 execution design) ----------------

def test_inspect_path_roundtrip(tmp_path):
    """eval_set (mock solver, no claude) -> eval_logs_to_summaries -> compose.

    Exercises the real Inspect read path: a deterministic mock solver echoes a
    per-sample answer, Inspect writes EvalLogs (with epochs=seeds), and the
    projection reads them back into the composer. Skipped if jseval[agent]
    (inspect-ai) isn't installed.
    """
    pytest.importorskip("inspect_ai")
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval import agent_utility_run as aur
    from jseval.agent_utility_inspect import substring_scorer

    @solver
    def mock_solver():
        async def solve(state, generate):
            md = state.metadata or {}
            state.output.completion = md.get("echo", "")
            state.metadata.update({"cost_usd": md.get("cost"),
                                   "unique_tokens": md.get("tokens"), "num_turns": 3})
            return state
        return solve

    cohort = {"model": "haiku", "cli_version": "2.1.183", "mcp_tool_surface_hash": "h",
              "judge_kind": "substring-em", "prompt_template_hash": "p"}

    # @task + distinct args (condition) → distinct eval_set tasks (mirrors production;
    # also what gives A2 config-change log segregation).
    @task
    def mock_task(condition="A", wrong_q0=False, cost=0.10, tok=4000):
        samples = [Sample(id=f"q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"echo": ("WRONG" if (wrong_q0 and i == 0) else f"ANS{i}"),
                                    "cost": cost, "tokens": tok})
                   for i in range(4)]
        return Task(dataset=samples, solver=mock_solver(), scorer=substring_scorer(),
                    metadata={"condition": condition, "model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                              "cohort": cohort})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([mock_task(condition="A", wrong_q0=True, cost=0.10, tok=4000),
              mock_task(condition="C", wrong_q0=False, cost=0.06, tok=2000)],
             log_dir=log_dir, epochs=2, model="mockllm/model", log_format="json")

    summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
    rec = utility_comparison.compose_utility(
        summaries, composed_at="t", contamination_class="private-synthetic")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["accuracy"]["with_tool"] == 1.0          # C right on all
    assert cell["accuracy"]["baseline"] == 0.75          # A wrong on q0 (3/4)
    assert cell["accuracy"]["delta"] == 0.25
    assert rec["seed_count"] == 2                         # epochs -> seeds
    assert cell["tokens_unique"]["delta_mean"] < 0       # C uses fewer tokens
    # A and C paired despite differing search-config (R2: excluded from pairing key)
    assert cell["n_paired_observations"] == 8            # 4 queries x 2 seeds


def test_inspect_path_has_no_leak_suspect_detection_today(tmp_path):
    """Documents a follow-up gap, not a bug: the Inspect-AI opt-in path's solver
    runs claude with `--output-format json` (agent_utility_inspect.claude_agent_solver),
    so it never parses individual tool_use blocks the way
    agent_retrieval_eval.run_agent_eval's `--output-format stream-json` does —
    there is no tool_calls list, and no MCP-retrieval-provenance signal either,
    for eval_logs_to_summaries to scan. Even a sample whose solver metadata
    happens to mention queries.json (simulating a leaked MCP response) is NOT
    flagged, because no such capture mechanism exists on this path today
    (tempdoc 624 §As-built #7 step 2). This test pins that absence explicitly
    so a future capture-mechanism addition changes this assertion on purpose,
    instead of the gap silently persisting unnoticed.
    """
    pytest.importorskip("inspect_ai")
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval import agent_utility_run as aur
    from jseval.agent_utility_inspect import substring_scorer

    @solver
    def mock_solver_with_suspicious_response():
        async def solve(state, generate):
            md = state.metadata or {}
            state.output.completion = md.get("echo", "")
            # Simulates an MCP tool response that happened to mention the
            # leaked file. Inspect's solver metadata has no field any
            # existing scan reads for this — so it cannot be detected today.
            state.metadata.update({
                "cost_usd": 0.05, "unique_tokens": 1000, "num_turns": 2,
                "mcp_response_preview": "...see queries.json for the answer...",
            })
            return state
        return solve

    cohort = {"model": "haiku", "cli_version": "2.1.183", "mcp_tool_surface_hash": "h",
              "judge_kind": "substring-em", "prompt_template_hash": "p"}

    @task
    def mock_task(condition="A"):
        samples = [Sample(id="q0", input="Q0", target="ANS0", metadata={"echo": "ANS0"})]
        return Task(dataset=samples, solver=mock_solver_with_suspicious_response(),
                    scorer=substring_scorer(),
                    metadata={"condition": condition, "model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                              "cohort": cohort})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([mock_task(condition="A"), mock_task(condition="C")],
             log_dir=log_dir, epochs=1, model="mockllm/model", log_format="json")

    summaries = aur.eval_logs_to_summaries(log_dir, search_config_cohort_key="sc")
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["leak_suspect_cells"] == []  # no detection mechanism exists on this path


# --- Run-governance: loss-accounting + paired comparability (tempdoc 624) ------

def test_paired_comparability_clean_and_asymmetric():
    from jseval.utility_governance import ArmLoss, paired_comparability
    full = {0: set(f"q{i}" for i in range(10)), 1: set(f"q{i}" for i in range(10))}
    A = ArmLoss("A", 2, 10, 20, set(), {k: set(v) for k, v in full.items()})
    C = ArmLoss("C", 2, 10, 20, set(), {k: set(v) for k, v in full.items()})
    v, m = paired_comparability({"A": A, "C": C})
    assert v.comparable is True and m["paired_n_retention"] == 1.0
    # C drops q5-q8 (asymmetric, high rate); A drops none
    okC = {0: {"q0", "q1", "q2", "q3", "q4"}, 1: {"q0", "q1", "q2", "q3", "q4", "q9"}}
    Cbad = ArmLoss("C", 2, 10, 11, {"q5", "q6", "q7", "q8"}, okC)
    v2, m2 = paired_comparability({"A": A, "C": Cbad})
    assert v2.comparable is False
    assert any("arm_C" in r for r in v2.reasons)        # per-arm exclusion rate
    assert m2["excluded_jaccard"] < 0.5                 # asymmetry caught


def test_governance_end_to_end(tmp_path):
    pytest.importorskip("inspect_ai")
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval import agent_utility_run as aur
    from jseval.agent_utility_inspect import substring_scorer
    from jseval.utility_governance import compute_loss_accounting, paired_comparability

    @solver
    def gmock():
        async def solve(state, generate):
            md = state.metadata or {}
            if md.get("force_error"):
                state.metadata["error"] = "forced timeout"
                return state
            state.output.completion = md.get("echo", "")
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 2000, "num_turns": 3})
            return state
        return solve

    cohort = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
              "judge_kind": "substring-em", "prompt_template_hash": "p"}

    @task
    def gtask(condition="A", err=False):
        samples = [Sample(id=f"q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"echo": f"ANS{i}", "force_error": (err and i < 3)})
                   for i in range(10)]
        return Task(dataset=samples, solver=gmock(), scorer=substring_scorer(),
                    metadata={"condition": condition, "model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "s"},
                              "cohort": cohort})

    log = (tmp_path / "g").as_posix()
    eval_set([gtask(condition="A", err=False), gtask(condition="C", err=True)],
             log_dir=log, epochs=2, model="mockllm/model", log_format="json")

    arms = compute_loss_accounting(log)
    assert arms["A"].n_excluded == 0
    assert arms["C"].n_excluded == 6                    # 3 queries x 2 epochs
    assert arms["C"].excluded_query_ids == {"q0", "q1", "q2"}
    verdict, metrics = paired_comparability(arms)
    assert verdict.comparable is False                  # C ~30% + asymmetric exclusion

    summaries = aur.eval_logs_to_summaries(log, search_config_cohort_key="sc")
    gov = {"comparable": verdict.comparable, "reasons": verdict.reasons, "metrics": metrics,
           "per_arm_loss": {c: {"n_excluded": l.n_excluded} for c, l in arms.items()}}
    rec = utility_comparison.compose_utility(
        summaries, composed_at="t", governance=gov, confidence_tier="A")
    assert rec["comparability"]["comparable"] is False
    assert rec["confidence_tier"] == "C"                # DERIVED, overrides the passed "A"


# --- Condition B separation + LLM-judge (tempdoc 624 C-4 / C-6) ---------------

_COHORT = {"model": "haiku", "cli_version": "v", "mcp_tool_surface_hash": "h",
           "judge_kind": "substring-em", "prompt_template_hash": "p"}


def _graded_logs(tmp_path, conds_through):
    """Build EvalLogs for given {condition: correct_through} via a graded mock solver."""
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    @solver
    def graded(correct_through=99):
        async def solve(state, generate):
            md = state.metadata or {}
            idx, tgt = md.get("idx", 0), md.get("tgt", "")
            state.output.completion = tgt if idx <= correct_through else "wrong"
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3})
            return state
        return solve

    @task
    def gt(condition="A", correct_through=99):
        samples = [Sample(id=f"q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"idx": i, "tgt": f"ANS{i}"}) for i in range(4)]
        return Task(dataset=samples, solver=graded(correct_through), scorer=substring_scorer(),
                    metadata={"condition": condition, "model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "s"},
                              "cohort": _COHORT})

    log = (tmp_path / "g").as_posix()
    eval_set([gt(condition=c, correct_through=ct) for c, ct in conds_through.items()],
             log_dir=log, epochs=1, model="mockllm/model", log_format="json")
    return log


def test_composer_separates_addition_b_and_substitution_c(tmp_path):
    pytest.importorskip("inspect_ai")
    from jseval import agent_utility_run as aur
    # A correct q0-1 (0.5); B correct q0-2 (0.75); C correct all (1.0).
    log = _graded_logs(tmp_path, {"A": 1, "B": 2, "C": 3})
    summaries = aur.eval_logs_to_summaries(log, search_config_cohort_key="sc")
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["primary_arm"] == "addition_b"                   # REALISTIC arm headlines, never C (C-4)
    assert cell["accuracy"]["delta"] == 0.25                     # top-level = A-vs-B (realistic)
    # the two with-tool arms are reported SEPARATELY, not pooled:
    assert cell["arms"]["substitution_c"]["accuracy"]["delta"] == 0.5
    assert cell["arms"]["addition_b"]["accuracy"]["delta"] == 0.25


def test_substitution_only_cell_is_flagged_not_headlined(tmp_path):
    pytest.importorskip("inspect_ai")
    from jseval import agent_utility_run as aur
    log = _graded_logs(tmp_path, {"A": 1, "C": 3})               # no B -> substitution-only
    summaries = aur.eval_logs_to_summaries(log, search_config_cohort_key="sc")
    cell = utility_comparison.compose_utility(summaries, composed_at="t")["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["primary_arm"] == "substitution_c"               # only C available
    assert "headline_caveat" in cell                             # ...but flagged as NOT a deployment headline


def test_judge_hybrid_overlay_dual_order(tmp_path, monkeypatch):
    pytest.importorskip("inspect_ai")
    from jseval import agent_utility_run as aur
    from jseval import utility_judge as uj

    # One C arm: q0 EM-correct, q1 EM-miss+judge-rescue, q2 EM-miss+judge-no, q3 EM-correct.
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval.agent_utility_inspect import substring_scorer

    answers = {"q0": "ANS0", "q1": "yellow RESCUE fruit", "q2": "totally wrong", "q3": "ANS3"}

    @solver
    def fixed():
        async def solve(state, generate):
            state.output.completion = answers[str(state.sample_id)]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3})
            return state
        return solve

    @task
    def ct():
        samples = [Sample(id=f"q{i}", input=f"Q{i}", target=f"ANS{i}") for i in range(4)]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"condition": "C", "model": "haiku",
                              "corpus": {"dataset": "d", "signature": "s"}, "cohort": _COHORT})

    log = (tmp_path / "j").as_posix()
    eval_set([ct()], log_dir=log, epochs=1, model="mockllm/model", log_format="json")

    class _Resp:
        def __init__(self, c): self._c = c
        def json(self): return {"choices": [{"message": {"content": self._c}}]}

    def fake_post(url, json=None, timeout=None):           # both dual-order calls agree
        user = json["messages"][1]["content"]
        return _Resp("YES" if "RESCUE" in user else "NO")

    monkeypatch.setattr(uj.httpx, "post", fake_post)
    monkeypatch.setattr(uj, "_probe_judge_model", lambda url: "local-judge-v1")

    overlay = uj.judge_logs(log, judge_url="http://x")
    assert overlay["judge_identity"]["kind"] == "hybrid-em-llm"
    assert overlay["stats"]["judge_flips"] == 1                 # q1 rescued
    assert overlay["stats"]["agreement_rate"] == 1.0
    assert overlay["scores"]["C|0|q1"]["final"] is True         # EM-miss -> judge YES
    assert overlay["scores"]["C|0|q2"]["final"] is False        # EM-miss -> judge NO

    summaries = aur.eval_logs_to_summaries(log, judge_overlay=overlay)
    pq = summaries[0]["per_query"]
    assert pq["q1"]["correct"] is True and pq["q2"]["correct"] is False
    assert summaries[0]["manifest"]["judge"]["kind"] == "hybrid-em-llm"


def test_judge_degrades_to_em_when_endpoint_down(tmp_path, monkeypatch):
    pytest.importorskip("inspect_ai")
    from jseval import utility_judge as uj

    log = _graded_logs(tmp_path, {"C": 1})                     # q2,q3 are EM-misses

    def boom(*a, **k):
        raise RuntimeError("connection refused")

    monkeypatch.setattr(uj.httpx, "post", boom)
    monkeypatch.setattr(uj, "_probe_judge_model", lambda url: None)

    overlay = uj.judge_logs(log, judge_url="http://x")
    assert overlay["stats"]["degraded_to_em"] is True
    assert overlay["judge_identity"]["kind"] == "substring-em"  # honest: no LLM ran
    assert overlay["scores"]["C|0|q2"]["final"] is False        # falls back to EM


# --- Cross-corpus composition (tempdoc 624 §Cross-corpus) --------------------
#
# compose_utility groups cell_summaries by (corpus, agent_model) BEFORE ever
# calling _arm_comparison, so distinct dataset slugs (e.g. English/German/scan)
# always land in SEPARATE top-level records — the pooled cross-corpus view
# _arm_comparison's stratify_by already supports is unreachable through it.
# compose_utility_cross_corpus pools 3 synthetic corpora that DELIBERATELY
# reuse the SAME seed (0) and the SAME qids (q0..q3) — the two collisions a
# naive pool would hit — and proves the per-stratum breakdown is hand-
# computable and independent of the (offsetting-to-zero) pooled number.

def _xcorpus_summary(dataset, condition, per_query, *, search_key=None):
    return _summary(condition, per_query, search_key=search_key, seed=0,
                     corpus={"dataset": dataset, "signature": "v1"})


def _xcorpus_pq(correct_a, correct_c, cost_a=0.10, cost_c=0.05, tok_a=1000, tok_c=500):
    a, c = {}, {}
    for i in range(4):
        q = f"q{i}"
        a[q] = {"correct": correct_a[i], "cost_usd": cost_a, "unique_tokens": tok_a, "num_turns": 5}
        c[q] = {"correct": correct_c[i], "cost_usd": cost_c, "unique_tokens": tok_c, "num_turns": 5}
    return a, c


def _xcorpus_fixture():
    """3 corpora, SAME seed (0) + SAME qids (q0-q3) on purpose (collision probe).

    - en: tool HELPS (+0.5), cheaper + fewer tokens with tool.
    - de: tool HURTS (-0.5), no cost/token difference.
    - scan: NO effect (0.0), no cost/token difference.

    Pooled across all 3 (12 paired observations): the en (+0.5) and de (-0.5)
    deltas offset exactly, scan contributes no discordant pairs -> pooled
    delta = 0.0, mcnemar_p = 1.0 (the "hides a real per-corpus signal" case).
    """
    en_a, en_c = _xcorpus_pq([False, True, False, True], [True, True, True, True],
                             cost_a=0.10, cost_c=0.05, tok_a=1000, tok_c=500)
    de_a, de_c = _xcorpus_pq([True, True, True, True], [True, False, True, False],
                             cost_a=0.20, cost_c=0.20, tok_a=2000, tok_c=2000)
    scan_a, scan_c = _xcorpus_pq([True, True, True, True], [True, True, True, True],
                                 cost_a=0.15, cost_c=0.15, tok_a=1500, tok_c=1500)
    return [
        _xcorpus_summary("golden/battlefield-en-v1", "A", en_a),
        _xcorpus_summary("golden/battlefield-en-v1", "C", en_c, search_key="s"),
        _xcorpus_summary("golden/battlefield-de-v1", "A", de_a),
        _xcorpus_summary("golden/battlefield-de-v1", "C", de_c, search_key="s"),
        _xcorpus_summary("golden/synth-scan-v1", "A", scan_a),
        _xcorpus_summary("golden/synth-scan-v1", "C", scan_c, search_key="s"),
    ]


def test_cross_corpus_pooled_offsets_but_strata_reveal_signal():
    rec = utility_comparison.compose_utility_cross_corpus(
        _xcorpus_fixture(), composed_at="t", contamination_class="private-synthetic")

    assert rec["schema"] == "utility-comparison-cross-corpus.v1"
    assert rec["corpora"] == [
        "golden/battlefield-de-v1:v1", "golden/battlefield-en-v1:v1", "golden/synth-scan-v1:v1",
    ]
    assert set(rec["measured"]) == {"haiku"}
    cell = rec["measured"]["haiku"]

    # Pooled: en (+0.5) and de (-0.5) offset exactly; scan contributes 0 -> hides the signal.
    assert cell["n_paired_observations"] == 12
    assert cell["accuracy"]["delta"] == 0.0
    assert cell["accuracy"]["mcnemar_p"] == 1.0

    strata = cell["stratified"]["by_stratum"]
    en_label = "golden/battlefield-en-v1:v1"
    de_label = "golden/battlefield-de-v1:v1"
    scan_label = "golden/synth-scan-v1:v1"
    assert set(strata) == {en_label, de_label, scan_label}

    en, de, scan = strata[en_label], strata[de_label], strata[scan_label]

    # Each stratum's McNemar is independently computed on ITS OWN n=4 (never
    # inherited from the pooled p=1.0 above — §M.8 item 7).
    assert en["n_paired_observations"] == 4
    assert de["n_paired_observations"] == 4
    assert scan["n_paired_observations"] == 4
    assert en["accuracy"]["delta"] == 0.5
    assert de["accuracy"]["delta"] == -0.5
    assert scan["accuracy"]["delta"] == 0.0
    assert en["accuracy"]["mcnemar_p"] == 0.5
    assert de["accuracy"]["mcnemar_p"] == 0.5
    assert scan["accuracy"]["mcnemar_p"] == 1.0
    assert en["accuracy"]["mcnemar_p"] != cell["accuracy"]["mcnemar_p"]

    # Cost/token blocks are independently computed per stratum too (en is
    # cheaper+fewer-tokens with the tool; de/scan have no cost/token delta —
    # the pooled block would average these together and hide en's signal).
    assert en["cost_usd"]["delta_mean"] < 0
    assert en["tokens_unique"]["delta_mean"] < 0
    assert de["cost_usd"]["delta_mean"] == 0.0
    assert scan["cost_usd"]["delta_mean"] == 0.0


def test_cross_corpus_no_seed_or_qid_collision():
    """The 3 fixture corpora deliberately reuse seed=0 and qids q0-q3 — proving
    pooling does NOT silently drop 2/3 of the data (the naive
    ``a_by_seed[seed][0]`` bug) or misattribute one corpus's q0 to another's
    stratum (the naive flat qid->label bug)."""
    rec = utility_comparison.compose_utility_cross_corpus(
        _xcorpus_fixture(), composed_at="t")
    cell = rec["measured"]["haiku"]
    # 3 corpora x 4 queries x 1 (shared) seed = 12 -- not 4 (would be the
    # index-0-only bug) and not fewer due to qid overwrite.
    assert cell["n_paired_observations"] == 12
    assert rec["seed_count"] == 1                      # one REAL seed (0), reported honestly


def test_cross_corpus_requires_two_or_more_corpora():
    single = [s for s in _xcorpus_fixture() if s["corpus"]["dataset"] == "golden/battlefield-en-v1"]
    with pytest.raises(utility_comparison.UtilityComposeError, match="2\\+ distinct corpora"):
        utility_comparison.compose_utility_cross_corpus(single, composed_at="t")


def test_cross_corpus_refuses_mixed_harness_cohort():
    fixture = _xcorpus_fixture()
    fixture[2] = _summary("A", fixture[2]["per_query"], seed=0,
                          corpus=fixture[2]["corpus"], cli_version="9.9.9")  # different harness
    with pytest.raises(utility_comparison.UtilityComposeError, match="agent_cohort_key differs"):
        utility_comparison.compose_utility_cross_corpus(fixture, composed_at="t")


def test_cross_corpus_governance_derives_tier_from_least_comparable_input():
    gov = {"comparable": False, "reasons": ["logs-de: asymmetric_exclusion"], "metrics": {},
           "per_arm_loss": {}}
    rec = utility_comparison.compose_utility_cross_corpus(
        _xcorpus_fixture(), composed_at="t", confidence_tier="A", governance=gov)
    assert rec["comparability"]["comparable"] is False
    assert rec["confidence_tier"] == "C"                # DERIVED, overrides the passed "A"
