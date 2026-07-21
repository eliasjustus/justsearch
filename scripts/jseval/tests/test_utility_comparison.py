"""Unit tests for the agent-utility comparison record (tempdoc 624).

Pure-function tests over agent-manifest + summary-shaped dicts (mirrors
``test_release.py``'s inline-fixture style — NO live claude / dev stack needed).
Proves the cohort-identity, pairing, McNemar, and composer machinery.
"""

from __future__ import annotations

import json
from pathlib import Path

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


def test_cohort_key_differs_on_exposure_mode_and_instructions():
    """tempdoc 725 increment 2: exposure_mode / instructions_sha256 join
    agent_cohort_key's key_surface -- a config or MCP-server-instructions
    change must split the cohort, same bar as CLI/tool-surface/judge/prompt
    above."""
    base = _mfst()
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(exposure_mode="deferred"))
    assert agent_manifest.agent_cohort_key(base) != agent_manifest.agent_cohort_key(
        _mfst(instructions_sha256="d" * 64))
    assert agent_manifest.agent_cohort_key(
        _mfst(exposure_mode="deferred")
    ) != agent_manifest.agent_cohort_key(_mfst(exposure_mode="eager"))
    # A manifest that never captured exposure identity at all (both None, the
    # pre-725 shape) must hash IDENTICALLY to before this field existed --
    # excluded, not added as a bare `null` (digest-preservation requirement).
    assert agent_manifest.agent_cohort_key(base) == agent_manifest.agent_cohort_key(
        _mfst(exposure_mode=None, instructions_sha256=None))


def test_pairing_key_pairs_A_and_C_excludes_condition_and_search_config():
    """A and C with same (corpus, model, seed) pair; differ only on condition (R2)."""
    a = _mfst(condition="A", search_config_cohort_key=None)
    c = _mfst(condition="C", search_config_cohort_key="search-XYZ")
    assert agent_manifest.pairing_key(a) == agent_manifest.pairing_key(c)
    # different seed -> different pair
    assert agent_manifest.pairing_key(a) != agent_manifest.pairing_key(_mfst(seed=1))


def test_pairing_key_still_pairs_A_and_C_when_exposure_identity_is_shared():
    """tempdoc 725 increment 2: exposure_mode/instructions_sha256 join
    agent_cohort_key's key_surface, and pairing_key's first element already IS
    agent_cohort_key -- so pairing_key is NOT given exposure as a SEPARATE
    explicit tuple element (that would be redundant), and two manifests whose
    exposure identity genuinely DIFFERS correctly fail to pair (they are not
    one harness cohort at all, exercised by compose_utility's coarser check).
    What must keep working is the real scenario: a coherent record where BOTH
    arms share the SAME captured exposure identity (as they must, since it is
    a whole-campaign property) -- condition and search_config_cohort_key still
    correctly drop out of the pair."""
    a = _mfst(condition="A", search_config_cohort_key=None,
              exposure_mode="deferred", instructions_sha256="d" * 64)
    c = _mfst(condition="C", search_config_cohort_key="search-XYZ",
              exposure_mode="deferred", instructions_sha256="d" * 64)
    assert agent_manifest.pairing_key(a) == agent_manifest.pairing_key(c)


def test_pairing_key_uses_resolved_provider_model_not_alias():
    base = _mfst(agent_model="haiku", agent_model_version="claude-haiku-4-5-20251001")
    changed = _mfst(agent_model="haiku", agent_model_version="claude-haiku-4-5-20260701")
    assert agent_manifest.pairing_key(base) != agent_manifest.pairing_key(changed)


def test_tool_surface_hash_order_independent():
    h1 = agent_manifest.mcp_tool_surface_hash(
        [{"name": "b", "description": "", "inputSchema": {}},
         {"name": "a", "description": "", "inputSchema": {}}])
    h2 = agent_manifest.mcp_tool_surface_hash(
        [{"name": "a", "description": "", "inputSchema": {}},
         {"name": "b", "description": "", "inputSchema": {}}])
    assert h1 == h2
    assert h1 != agent_manifest.mcp_tool_surface_hash(None)  # arm A sentinel differs


def test_tool_surface_hash_covers_output_schema_and_annotations():
    base = [{"name": "search", "inputSchema": {"type": "object"},
             "outputSchema": {"type": "string"},
             "annotations": {"readOnlyHint": True}}]
    changed_output = [{**base[0], "outputSchema": {"type": "integer"}}]
    changed_annotations = [{**base[0], "annotations": {"readOnlyHint": False}}]
    assert agent_manifest.mcp_tool_surface_hash(base) != agent_manifest.mcp_tool_surface_hash(
        changed_output
    )
    assert agent_manifest.mcp_tool_surface_hash(base) != agent_manifest.mcp_tool_surface_hash(
        changed_annotations
    )


def test_configured_alpha_changes_bootstrap_interval():
    run_a = {"per_query_metrics": {str(i): {"m": 0.0} for i in range(8)}}
    values = [0.0, 0.0, 0.1, 0.2, 0.4, 0.8, 1.2, 2.0]
    run_b = {"per_query_metrics": {str(i): {"m": value} for i, value in enumerate(values)}}
    qrels = {str(i): {} for i in range(8)}
    wide = compare_runs.compare(run_a, run_b, qrels, metrics=["m"], alpha=0.01)
    narrow = compare_runs.compare(run_a, run_b, qrels, metrics=["m"], alpha=0.20)
    assert wide["m"]["ci"][0] <= narrow["m"]["ci"][0]
    assert wide["m"]["ci"][1] >= narrow["m"]["ci"][1]
    assert wide["m"]["confidence_level"] == 0.99


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


# --- tool_call_assertions (tempdoc 624 §As-built #5 residual-gap close) -----
#
# The additive coverage block that lets a consumer tell "0 violations observed
# across N cells with real tool data" from "no tool data captured" -- the
# credibility bar (tempdoc 624 §M.8 item 2) is a per-cell EMPIRICAL check, not
# a config-trust assumption, and this is the record-level rollup of it.

def _pq_entry(*, correct=True, tool_calls=None, disallowed=None, leak_tool_calls=None,
              leak_suspect=None, mcp_tools_offered=None, mcp_surface_unverified=None,
              mcp_tools_deferred=None, toolsearch_targets=None, tool_call_sequence=None):
    entry = {"correct": correct, "cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3}
    if tool_calls is not None or disallowed is not None or leak_tool_calls is not None:
        entry["tool_calls"] = tool_calls
        entry["disallowed_tool_calls"] = disallowed
        entry["leak_suspect_tool_calls"] = leak_tool_calls
    if leak_suspect is not None:
        entry["leak_suspect"] = leak_suspect
    if mcp_tools_offered is not None:
        entry["mcp_tools_offered"] = mcp_tools_offered
    if mcp_surface_unverified is not None:
        entry["mcp_surface_unverified"] = mcp_surface_unverified
    if mcp_tools_deferred is not None:
        entry["mcp_tools_deferred"] = mcp_tools_deferred
    if toolsearch_targets is not None:
        entry["toolsearch_targets"] = toolsearch_targets
    if tool_call_sequence is not None:
        entry["tool_call_sequence"] = tool_call_sequence
    return entry


def test_tool_call_assertions_reports_clean_when_zero_violations_across_checked_cells():
    a_pq = {f"q{i}": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[]) for i in range(4)}
    c_pq = {f"q{i}": _pq_entry(tool_calls=[{"tool": "justsearch_answer", "input": {}}],
                               disallowed=[], leak_tool_calls=[]) for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    tca = rec["tool_call_assertions"]
    assert tca["A"] == {"cells_total": 4, "cells_with_tool_data": 4,
                        "cells_with_disallowed_violations": 0, "cells_with_leak_suspect": 0,
                        "cells_with_mcp_surface_verified": 0, "cells_mcp_surface_unverified": 0}
    assert tca["C"] == {"cells_total": 4, "cells_with_tool_data": 4,
                        "cells_with_disallowed_violations": 0, "cells_with_leak_suspect": 0,
                        "cells_with_mcp_surface_verified": 0, "cells_mcp_surface_unverified": 0}


def test_tool_call_assertions_counts_disallowed_violations():
    c_pq = {
        "q0": _pq_entry(tool_calls=[{"tool": "Bash", "input": {}}],
                        disallowed=[{"tool": "Bash", "input": {}}], leak_tool_calls=[]),
        "q1": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[]),
        "q2": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[]),
        "q3": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[]),
    }
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    assert rec["tool_call_assertions"]["C"]["cells_with_disallowed_violations"] == 1
    assert rec["tool_call_assertions"]["C"]["cells_with_tool_data"] == 4


def test_tool_call_assertions_distinguishes_no_data_from_verified_clean():
    """A `None` tool_calls list (no capture) must NOT count toward
    cells_with_tool_data, even though the cell is otherwise a normal paired
    observation -- the tri-state distinction the whole field exists for."""
    a_pq = {
        "q0": _pq_entry(tool_calls=None, disallowed=None, leak_tool_calls=None),  # no data
        "q1": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[]),        # checked, clean
        "q2": _pq_entry(),  # no tool_calls key at all (older-shaped summary)
        "q3": _pq_entry(),
    }
    c_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    a = rec["tool_call_assertions"]["A"]
    assert a["cells_total"] == 4
    assert a["cells_with_tool_data"] == 1  # only q1 carried a real (possibly empty) list
    assert a["cells_with_disallowed_violations"] == 0


def test_tool_call_assertions_degrades_gracefully_for_summaries_without_tool_call_keys():
    """The OLDEST summary shape (pre-tempdoc-624-§As-built-#5, or the classic
    run_agent_eval path's raw dict before this fix): per_query entries carry
    NO tool_calls/disallowed_tool_calls/leak_suspect_tool_calls keys at all.
    Must not crash and must read as "no tool data" everywhere, never a
    fabricated 0-violations-means-clean signal."""
    a_pq = {f"q{i}": {"correct": True, "cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3}
            for i in range(3)}
    c_pq = {f"q{i}": {"correct": True, "cost_usd": 0.05, "unique_tokens": 500, "num_turns": 2}
            for i in range(3)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    for cond in ("A", "C"):
        tca = rec["tool_call_assertions"][cond]
        assert tca["cells_total"] == 3
        assert tca["cells_with_tool_data"] == 0
        assert tca["cells_with_disallowed_violations"] == 0
        assert tca["cells_with_leak_suspect"] == 0
    # the paired comparison itself must still work -- absent tool-call keys
    # must not break the existing accuracy/cost/token pairing.
    assert rec["measured"]["mixed/multihop-rag"]["haiku"]["n_paired_observations"] == 3


def test_tool_call_assertions_counts_leak_suspect_from_bool_flag_even_without_tool_data():
    """`leak_suspect` can be set by the text-scan backstop
    (`agent_utility_run.apply_leak_flags`) on a cell that never captured real
    tool_calls data at all -- the leak-suspect count must still pick it up
    (unlike the disallowed-violations count, which needs real tool data)."""
    a_pq = {
        "q0": _pq_entry(tool_calls=None, disallowed=None, leak_tool_calls=None, leak_suspect=True),
        "q1": _pq_entry(tool_calls=None, disallowed=None, leak_tool_calls=None, leak_suspect=False),
    }
    c_pq = {"q0": _pq_entry(), "q1": _pq_entry()}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    a = rec["tool_call_assertions"]["A"]
    assert a["cells_with_leak_suspect"] == 1
    assert a["cells_with_tool_data"] == 0  # still no real tool_calls captured


def test_tool_call_assertions_grouped_per_condition_not_merged():
    a_pq = {"q0": _pq_entry(tool_calls=[], disallowed=[], leak_tool_calls=[])}
    c_pq = {"q0": _pq_entry(tool_calls=[{"tool": "Bash", "input": {}}],
                            disallowed=[{"tool": "Bash", "input": {}}], leak_tool_calls=[])}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    assert set(rec["tool_call_assertions"]) == {"A", "C"}
    assert rec["tool_call_assertions"]["A"]["cells_with_disallowed_violations"] == 0
    assert rec["tool_call_assertions"]["C"]["cells_with_disallowed_violations"] == 1


def test_tool_call_assertions_counts_mcp_surface_verified_and_unverified():
    """tempdoc 624 battlefield retrospective: a cell offering >=1 mcp__justsearch
    tool counts as verified; a cell whose init event never parsed counts as
    unverified (an unknown, not a fabricated clean 0); a cell offering 0 tools
    should never reach here at all (it was excluded upstream as `error`'d) --
    this test only exercises the composer-side counting of what DOES arrive."""
    c_pq = {
        "q0": _pq_entry(mcp_tools_offered=2),                       # verified
        "q1": _pq_entry(mcp_tools_offered=1),                       # verified
        "q2": _pq_entry(mcp_surface_unverified=True),               # unverified
        "q3": _pq_entry(),                                          # neither (condition A-like)
    }
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    c = rec["tool_call_assertions"]["C"]
    assert c["cells_with_mcp_surface_verified"] == 2
    assert c["cells_mcp_surface_unverified"] == 1
    a = rec["tool_call_assertions"]["A"]
    assert a["cells_with_mcp_surface_verified"] == 0
    assert a["cells_mcp_surface_unverified"] == 0


# --- tool_surfacing_mode cohort stamp (tempdoc 624 §M.8 amendment, Step 0
# item 4): eager vs. deferred MCP tool exposure is CLI-version-dependent and
# mediates adoption, so it must be cohort identity, not per-cell metadata.

def test_tool_surfacing_mode_eager_when_no_cell_deferred():
    c_pq = {f"q{i}": _pq_entry(mcp_tools_deferred=False) for i in range(4)}
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["tool_surfacing_mode"] == "eager"


def test_tool_surfacing_mode_deferred_when_every_cell_deferred():
    c_pq = {f"q{i}": _pq_entry(mcp_tools_deferred=True) for i in range(4)}
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["tool_surfacing_mode"] == "deferred"


def test_tool_surfacing_mode_mixed_when_cells_disagree():
    c_pq = {
        "q0": _pq_entry(mcp_tools_deferred=True),
        "q1": _pq_entry(mcp_tools_deferred=False),
        "q2": _pq_entry(mcp_tools_deferred=True),
        "q3": _pq_entry(mcp_tools_deferred=False),
    }
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["tool_surfacing_mode"] == "mixed"


def test_tool_surfacing_mode_none_when_no_cell_carries_the_field():
    """An older summary shape (pre-Step-0) with no mcp_tools_deferred key at all
    must read as unknown, never a fabricated default."""
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    c_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["tool_surfacing_mode"] is None


def test_tool_surfacing_mode_ignores_condition_a_cells():
    """Condition A never carries a real mcp_config, so any mcp_tools_deferred
    value on an A cell must not participate in the with-tool-only vote."""
    a_pq = {f"q{i}": _pq_entry(mcp_tools_deferred=True) for i in range(4)}  # should be ignored
    c_pq = {f"q{i}": _pq_entry(mcp_tools_deferred=False) for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["tool_surfacing_mode"] == "eager"


# --- executor provenance stamp (tempdoc 624 §M.8 amendment, Step 0 item 6) --

def test_executor_stamped_when_all_summaries_agree_legacy():
    a_pq = {f"q{i}": _pq_entry() for i in range(2)}
    c_pq = {f"q{i}": _pq_entry() for i in range(2)}
    summaries = [
        {**_summary("A", a_pq), "executor": "legacy-agent-eval"},
        {**_summary("C", c_pq, search_key="s"), "executor": "legacy-agent-eval"},
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["executor"] == "legacy-agent-eval"


def test_executor_stamped_when_all_summaries_agree_inspect():
    a_pq = {f"q{i}": _pq_entry() for i in range(2)}
    c_pq = {f"q{i}": _pq_entry() for i in range(2)}
    summaries = [
        {**_summary("A", a_pq), "executor": "inspect-ai"},
        {**_summary("C", c_pq, search_key="s"), "executor": "inspect-ai"},
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["cohort"]["executor"] == "inspect-ai"


def test_executor_null_when_summaries_disagree_or_unmarked():
    """Cannot tell them apart -> honest null, never a guessed majority."""
    a_pq = {f"q{i}": _pq_entry() for i in range(2)}
    c_pq = {f"q{i}": _pq_entry() for i in range(2)}
    mixed = [
        {**_summary("A", a_pq), "executor": "legacy-agent-eval"},
        {**_summary("C", c_pq, search_key="s"), "executor": "inspect-ai"},
    ]
    rec = utility_comparison.compose_utility(mixed, composed_at="t")
    assert rec["cohort"]["executor"] is None

    unmarked = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]  # no executor key
    rec2 = utility_comparison.compose_utility(unmarked, composed_at="t")
    assert rec2["cohort"]["executor"] is None


# --- Adoption metrics (tempdoc 624 §M.8 amendment, Step 0 item 5) -----------
#
# Pre-registered per-arm adoption metrics derived purely from the existing
# per-cell tool_calls capture -- adoption_rate, first_mcp_call_index (a
# call-index, not a turn), and mcp_call_share. Baseline (arm A) is always null
# by construction: it never carries an MCP config.

def _adoption_pq(tool_calls_list):
    """Build a per_query dict of length N from a list of per-cell tool_calls
    (each entry a list of {"tool":...} dicts, or None for 'no capture')."""
    return {
        f"q{i}": _pq_entry(tool_calls=tc, disallowed=[], leak_tool_calls=[])
        if tc is not None else {"correct": True, "cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3}
        for i, tc in enumerate(tool_calls_list)
    }


def test_adoption_rate_counts_cells_with_at_least_one_mcp_justsearch_call():
    c_pq = _adoption_pq([
        [{"tool": "mcp__justsearch__search_query", "input": {}}],
        [{"tool": "Read", "input": {}}],
        [{"tool": "mcp__justsearch__ingest", "input": {}}, {"tool": "Read", "input": {}}],
        [],
    ])
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["adoption"]["with_tool"]["adoption_rate"] == 0.5  # 2 of 4 cells adopted
    assert cell["adoption"]["baseline"] == {
        "adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None,
    }


def test_adoption_first_mcp_call_index_is_median_call_index():
    c_pq = _adoption_pq([
        [{"tool": "mcp__justsearch__search_query", "input": {}}],  # index 1
        [{"tool": "Read", "input": {}}, {"tool": "mcp__justsearch__search_query", "input": {}}],  # index 2
        [{"tool": "Read", "input": {}}, {"tool": "Grep", "input": {}},
         {"tool": "mcp__justsearch__search_query", "input": {}}],  # index 3
        [{"tool": "Read", "input": {}}],  # no mcp call
    ])
    a_pq = {f"q{i}": _pq_entry() for i in range(4)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["adoption"]["with_tool"]["first_mcp_call_index"] == 2  # median of [1, 2, 3]


def test_adoption_mcp_call_share_pools_calls_across_cells():
    c_pq = _adoption_pq([
        [{"tool": "mcp__justsearch__search_query", "input": {}}, {"tool": "Read", "input": {}}],
        [{"tool": "mcp__justsearch__search_query", "input": {}}],
    ])
    a_pq = {f"q{i}": _pq_entry() for i in range(2)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["adoption"]["with_tool"]["mcp_call_share"] == round(2 / 3, 4)


def test_adoption_none_tool_calls_excluded_from_denominator():
    """A cell with no tool_calls capture (None) must not count as a 'checked,
    zero adoption' cell -- same tri-state discipline as tool_call_assertions."""
    c_pq = _adoption_pq([
        [{"tool": "mcp__justsearch__search_query", "input": {}}],
        None,
        None,
    ])
    a_pq = {f"q{i}": _pq_entry() for i in range(3)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["adoption"]["with_tool"]["adoption_rate"] == 1.0  # 1 of 1 CHECKED cell


def test_adoption_all_none_yields_null_metrics_not_zero():
    c_pq = _adoption_pq([None, None])
    a_pq = {f"q{i}": _pq_entry() for i in range(2)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["adoption"]["with_tool"] == {
        "adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None,
    }


# --- Adoption FUNNEL (tempdoc 725 increment 3) ------------------------------
#
# offered -> discovered (a `select:` ToolSearch call named a justsearch tool)
# -> invoked (>=1 of those names was actually called) -> reinforced (durable,
# not a one-off). Pure-function tests over `_funnel_metrics` directly, plus an
# end-to-end `compose_utility` cell-level check and the committed-fixture
# byte-preservation guarantee.

def test_funnel_metrics_absent_when_no_cell_carries_funnel_data():
    """Pre-tempdoc-725 evidence (or a `None` triple everywhere) -> the null +
    explicit marker state, never a fabricated zero."""
    result = utility_comparison._funnel_metrics([None, None], [None, None], [None, None])
    assert result == {
        "discovery_rate": None, "post_discovery_invocation_rate": None,
        "first_discovery_turn": None, "reinforced_proxy_rate": None,
        "reinforced_rate": None, "funnel_fields_absent": True,
    }


def test_funnel_metrics_reproduces_pilot_shape_offered14_discovered2_invoked1_reinforced_proxy1():
    """Synthetic campaign reproducing the pilot's observed funnel shape:
    offered 14 -> discovered 2 -> invoked 1 -> reinforced-proxy 1."""
    tool_calls = []
    toolsearch_targets = []
    tool_call_sequences = []

    # Cell 0: discovered, invoked, and reinforced (2 successful mcp calls; the
    # LAST justsearch-related event also succeeds -> strictly reinforced too).
    tool_calls.append([
        {"tool": "mcp__justsearch__search"}, {"tool": "mcp__justsearch__search"},
    ])
    toolsearch_targets.append(["mcp__justsearch__search"])
    tool_call_sequences.append([
        {"name": "ToolSearch", "status": "ok"},
        {"name": "mcp__justsearch__search", "status": "ok"},
        {"name": "mcp__justsearch__search", "status": "ok"},
    ])

    # Cell 1: discovered but never invoked (ToolSearch found it, agent never
    # actually called it).
    tool_calls.append([])
    toolsearch_targets.append(["mcp__justsearch__search"])
    tool_call_sequences.append([{"name": "ToolSearch", "status": "ok"}])

    # Cells 2-13 (12 cells): never discovered at all -- offered, checked, but
    # ToolSearch was never called with a justsearch-resolving query.
    for _ in range(12):
        tool_calls.append([])
        toolsearch_targets.append([])
        tool_call_sequences.append([{"name": "Read", "status": "ok"}])

    assert len(tool_calls) == 14  # offered
    result = utility_comparison._funnel_metrics(tool_calls, toolsearch_targets, tool_call_sequences)

    assert result["funnel_fields_absent"] is False
    assert result["discovery_rate"] == round(2 / 14, 4)  # discovered 2
    assert result["post_discovery_invocation_rate"] == 0.5  # invoked 1 of 2 discovered
    assert result["first_discovery_turn"] == 1.0  # both discoveries were the cell's 1st call
    assert result["reinforced_proxy_rate"] == 1.0  # reinforced-proxy 1 of 1 invoked
    assert result["reinforced_rate"] == 1.0  # the invoked cell's last mcp event also succeeded


def test_funnel_metrics_strict_reinforced_excludes_a_cell_ending_on_a_blocked_call():
    """A cell invoked successfully once, then its LAST justsearch-related
    interaction was blocked -- reinforced_proxy_rate still counts it as
    reinforced (>1 successful call total: `_mcp_call_count` on the EXECUTED
    tool_calls list only ever contains successes, so a single blocked retry
    does not raise the count past 1), but the STRICT reinforced_rate must not,
    because the cell's use of the tool ended on a failure note."""
    tool_calls = [[{"tool": "mcp__justsearch__search"}]]  # exactly one executed success
    toolsearch_targets = [["mcp__justsearch__search"]]
    tool_call_sequences = [[
        {"name": "ToolSearch", "status": "ok"},
        {"name": "mcp__justsearch__search", "status": "ok"},
        {"name": "mcp__justsearch__search", "status": "blocked"},  # last justsearch event fails
    ]]

    result = utility_comparison._funnel_metrics(tool_calls, toolsearch_targets, tool_call_sequences)

    assert result["post_discovery_invocation_rate"] == 1.0
    assert result["reinforced_proxy_rate"] == 0.0  # only 1 EXECUTED mcp call -> not >1
    assert result["reinforced_rate"] == 0.0  # last justsearch event was blocked


def test_funnel_metrics_none_entries_excluded_from_every_denominator():
    """The same tri-state discipline as `_adoption_metrics`: a `None` triple
    for one cell (no funnel capture) must not count in ANY funnel denominator,
    even when other cells in the same record do carry real data."""
    tool_calls = [[{"tool": "mcp__justsearch__search"}], None]
    toolsearch_targets = [["mcp__justsearch__search"], None]
    tool_call_sequences = [
        [{"name": "ToolSearch", "status": "ok"}, {"name": "mcp__justsearch__search", "status": "ok"}],
        None,
    ]
    result = utility_comparison._funnel_metrics(tool_calls, toolsearch_targets, tool_call_sequences)
    assert result["discovery_rate"] == 1.0  # 1 of 1 CHECKED cell, not 1 of 2


def test_compose_utility_cell_has_no_funnel_key_when_no_cell_carries_funnel_data():
    """End-to-end: a comparison composed entirely from pre-tempdoc-725-shaped
    per_query entries (no toolsearch_targets/tool_call_sequence anywhere) must
    have NO "funnel" key on the cell at all -- digest-preservation requirement
    (tempdoc 725 increment 3), same discipline as the cohort-level exposure
    fields."""
    c_pq = _adoption_pq([[{"tool": "mcp__justsearch__search_query", "input": {}}]])
    a_pq = {f"q{i}": _pq_entry() for i in range(1)}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert "funnel" not in cell


def test_compose_utility_cell_carries_funnel_when_any_cell_has_funnel_data():
    c_pq = {
        "q0": _pq_entry(
            tool_calls=[{"tool": "mcp__justsearch__search"}],
            toolsearch_targets=["mcp__justsearch__search"],
            tool_call_sequence=[
                {"name": "ToolSearch", "status": "ok"},
                {"name": "mcp__justsearch__search", "status": "ok"},
            ],
        ),
    }
    a_pq = {"q0": _pq_entry()}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["funnel"]["baseline"]["funnel_fields_absent"] is True
    assert cell["funnel"]["with_tool"]["discovery_rate"] == 1.0


# --- Denominators / seed floor / eligibility stamp (tempdoc 736 B2/B3/B1) ---

def test_compose_utility_denominators_block_names_primary_and_secondary():
    """D13: the top-level `denominators` block names n_attempted as the
    PRIMARY (ITT) denominator and n_checked/n_excluded as SECONDARY
    (funnel-conditional), each with a declarative one-sentence `question`."""
    summaries = [_summary("A", {"q0": _pq_entry()}), _summary("C", {"q0": _pq_entry()}, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    denominators = rec["denominators"]
    assert denominators["n_attempted"]["tier"] == "primary"
    assert denominators["n_checked"]["tier"] == "secondary"
    assert denominators["n_excluded"]["tier"] == "secondary"
    for entry in denominators.values():
        assert entry["question"]  # a non-empty declarative sentence
        assert entry["source"]


def test_compose_utility_denominators_block_does_not_perturb_existing_metrics(tmp_path):
    """U4: adding the `denominators` block is pure reporting -- no existing
    metric numerator/denominator changes value. Reuses the funnel-carrying
    fixture and asserts its funnel rates are untouched by the new block."""
    c_pq = {
        "q0": _pq_entry(
            tool_calls=[{"tool": "mcp__justsearch__search"}],
            toolsearch_targets=["mcp__justsearch__search"],
            tool_call_sequence=[
                {"name": "ToolSearch", "status": "ok"},
                {"name": "mcp__justsearch__search", "status": "ok"},
            ],
        ),
    }
    a_pq = {"q0": _pq_entry()}
    summaries = [_summary("A", a_pq), _summary("C", c_pq, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]

    assert cell["funnel"]["with_tool"]["discovery_rate"] == 1.0
    assert "denominators" in rec
    assert "denominator_note" in cell["funnel"]
    assert "SECONDARY" in cell["funnel"]["denominator_note"]


def test_compose_utility_comparability_carries_denominator_note():
    from jseval.utility_governance import ArmLoss, paired_comparability

    full = {0: {"q0"}}
    A = ArmLoss("A", 1, 1, 1, n_error_cells=0, excluded_query_ids=set(), ok_by_seed=full)
    C = ArmLoss("C", 1, 1, 1, n_error_cells=0, excluded_query_ids=set(), ok_by_seed=full)
    verdict, metrics = paired_comparability({"A": A, "C": C})
    governance = {
        "comparable": verdict.comparable, "reasons": verdict.reasons,
        "metrics": metrics,
        "per_arm_loss": {
            "A": {"n_attempted": A.n_attempted, "n_excluded": A.n_excluded},
            "C": {"n_attempted": C.n_attempted, "n_excluded": C.n_excluded},
        },
    }
    summaries = [_summary("A", {"q0": _pq_entry()}), _summary("C", {"q0": _pq_entry()}, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t", governance=governance)

    assert "PRIMARY" in rec["comparability"]["denominator_note"]
    assert "denominators" in rec["comparability"]["denominator_note"]


def test_compose_utility_seed_floor_met_true_at_the_floor():
    summaries = [
        _summary("A", {"q0": _pq_entry()}, seed=s) for s in range(utility_comparison.SEED_FLOOR)
    ] + [
        _summary("C", {"q0": _pq_entry()}, search_key="s", seed=s)
        for s in range(utility_comparison.SEED_FLOOR)
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["seed_count"] == utility_comparison.SEED_FLOOR
    assert rec["seed_floor_met"] is True


def test_compose_utility_seed_floor_met_false_below_the_floor():
    summaries = [_summary("A", {"q0": _pq_entry()}, seed=0),
                 _summary("C", {"q0": _pq_entry()}, search_key="s", seed=0)]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert rec["seed_count"] == 1
    assert rec["seed_count"] < utility_comparison.SEED_FLOOR
    assert rec["seed_floor_met"] is False


def test_compose_utility_stamps_exposure_contrast_ineligible_when_pre_605_shaped():
    """D11: a record composed without exposure identity anywhere (the shape
    of every pre-#605 evidence) self-describes as exposure-contrast-ineligible
    directly on the composed record, not only when a contrast is attempted."""
    summaries = [_summary("A", {"q0": _pq_entry()}), _summary("C", {"q0": _pq_entry()}, search_key="s")]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")

    assert "exposure_contrast_ineligible" in rec
    assert rec["exposure_contrast_ineligible"]["since"] == "#605"
    assert any("exposure identity" in r for r in rec["exposure_contrast_ineligible"]["reasons"])


def test_compose_utility_omits_exposure_contrast_ineligible_when_eligible():
    """Conditional-omission discipline (tempdoc 725 precedent): an eligible
    record never carries the marker at all, not even as a `null`/false value --
    a post-#605 record stays byte-identical to a record composed before this
    stamp existed."""
    manifest_over = {
        "exposure_config": {"enable_tool_search": None, "always_load": False, "exposure_mode": "deferred"},
        "mcp_initialize_identity": {"instructions": None, "instructions_sha256": "a" * 64,
                                     "server_version": "1.0.0", "protocol_version": "2025-06-18"},
        "exposure_mode": "deferred",
        "instructions_sha256": "a" * 64,
    }
    summaries = [
        _summary("A", {"q0": _pq_entry()}, **manifest_over),
        _summary("C", {"q0": _pq_entry(
            tool_calls=[{"tool": "mcp__justsearch__search"}],
            toolsearch_targets=["mcp__justsearch__search"],
            tool_call_sequence=[{"name": "mcp__justsearch__search", "status": "ok"}],
        )}, search_key="s", **manifest_over),
    ]
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    assert "exposure_contrast_ineligible" not in rec


def test_real_2026_07_12_rejected_fixture_funnel_is_absent_not_null_marker():
    """The immutable committed fixture predates toolsearch_targets/
    tool_call_sequence entirely -- `finalize_evidence` over it must produce a
    record with NO "funnel" key ANYWHERE (cells or ITT strata), preserving the
    fixture's semantic_digest byte-for-byte. Calling `_funnel_metrics` directly
    on the fixture's own (all-None) derived lists independently proves the
    underlying computation legitimately resolves to the null+marker state,
    not merely "happens to be omitted."""
    from pathlib import Path as _Path

    from jseval.utility_evidence import read_evidence
    from jseval.utility_recompose import finalize_evidence

    path = (
        _Path(__file__).parent / "fixtures" / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    observations = read_evidence(path)
    assert all(obs.get("toolsearch_targets") is None for obs in observations)
    assert all(obs.get("tool_call_sequence") is None for obs in observations)

    direct = utility_comparison._funnel_metrics(
        [obs.get("tool_calls") for obs in observations],
        [obs.get("toolsearch_targets") for obs in observations],
        [obs.get("tool_call_sequence") for obs in observations],
    )
    assert direct == {
        "discovery_rate": None, "post_discovery_invocation_rate": None,
        "first_discovery_turn": None, "reinforced_proxy_rate": None,
        "reinforced_rate": None, "funnel_fields_absent": True,
    }

    record = finalize_evidence([path], composed_at="fixture")
    for cells in record["measured"].values():
        for cell in cells.values():
            assert "funnel" not in cell
    for stratum in record["estimands"]["intention_to_treat"]["strata"]:
        assert "funnel" not in stratum


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
    with pytest.raises(utility_comparison.UtilityComposeError, match="mixes corpus/resolved-model"):
        utility_comparison.compose_utility(summaries, composed_at="t")


def test_mix_guard_also_catches_mixed_exposure_identity():
    """tempdoc 725 increment 2: exposure_mode/instructions_sha256 additively
    join the mix-guard identity tuple. Since exposure_mode ALSO joins
    agent_cohort_key's key_surface, a genuine exposure mismatch would already
    be caught by the coarser whole-record 'agent_cohort_key differs' check
    (§1) before compose_utility ever reaches the per-cell mix-guard (§3) --
    so this test forges matching cohort keys to isolate and exercise the
    mix-guard specifically, proving it independently catches the mismatch
    too (belt-and-suspenders, same as several existing identity fields that
    are NOT part of the cohort key at all)."""
    pq = {"q0": {"correct": True, "cost_usd": 0.1, "unique_tokens": 100, "num_turns": 3}}
    eager = _summary("A", pq, exposure_mode="eager")
    deferred = _summary("C", pq, search_key="s", exposure_mode="deferred")
    deferred["manifest"]["agent_cohort_key"] = eager["manifest"]["agent_cohort_key"]
    with pytest.raises(utility_comparison.UtilityComposeError, match="mixes corpus/resolved-model"):
        utility_comparison.compose_utility([eager, deferred], composed_at="t")


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
        samples = [Sample(id=f"{condition}|q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"condition": condition,
                                    "echo": ("WRONG" if (wrong_q0 and i == 0) else f"ANS{i}"),
                                    "cost": cost, "tokens": tok})
                   for i in range(4)]
        return Task(dataset=samples, solver=mock_solver(), scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                              "cohort": cohort})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([mock_task(condition="A", wrong_q0=True, cost=0.10, tok=4000),
              mock_task(condition="C", wrong_q0=False, cost=0.06, tok=2000)],
             log_dir=log_dir, epochs=2, model="mockllm/model", log_format="json")

    summaries = aur.eval_logs_to_summaries(log_dir)
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


def test_inspect_path_leak_detection_needs_a_known_signal_shape(tmp_path):
    """The residual §As-built #5 gap (`agent_utility_inspect.claude_agent_solver`
    running `--output-format json` with no tool_calls capture) is now closed —
    the solver runs `--output-format stream-json --verbose` and stashes real
    `tool_calls` / `disallowed_tool_calls` / `leak_suspect_tool_calls` into
    `state.metadata` (mirroring `agent_retrieval_eval.run_agent_eval`; see
    `test_eval_logs_to_summaries_surfaces_real_tool_call_data` for that path).

    This test documents the boundary that remains even so: both backstops —
    the real tool-call scan (`find_leak_suspect_tool_calls`, reads
    `state.metadata["tool_calls"]`) and the completion-text scan
    (`agent_utility_run.scan_leaked_cells`, reads `state.output.completion`) —
    only see signal shapes they know to look at. A custom/mock solver that
    stashes a leak-shaped string under an UNRELATED metadata key (simulating,
    e.g., a raw un-parsed MCP response preview never surfaced through either
    channel) is still invisible to both. This is not the gap this change
    closes — it is the honest edge of what "capture real tool calls" can cover
    for a solver that doesn't route its signal through the same channel the
    real `claude_agent_solver` does.
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
            # leaked file, stashed under a metadata key neither the tool_calls
            # scan nor the completion-text scan reads.
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
        samples = [Sample(id=f"{condition}|q0", input="Q0", target="ANS0",
                          metadata={"condition": condition, "echo": "ANS0"})]
        return Task(dataset=samples, solver=mock_solver_with_suspicious_response(),
                    scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "sig"},
                              "cohort": cohort})

    log_dir = (tmp_path / "logs").as_posix()
    eval_set([mock_task(condition="A"), mock_task(condition="C")],
             log_dir=log_dir, epochs=1, model="mockllm/model", log_format="json")

    summaries = aur.eval_logs_to_summaries(log_dir)
    rec = utility_comparison.compose_utility(summaries, composed_at="t")
    cell = rec["measured"]["mixed/multihop-rag"]["haiku"]
    assert cell["leak_suspect_cells"] == []  # signal sat in a key neither scan reads
    # No tool_calls captured for this mock solver (it never sets that key) — the
    # new coverage record must report "no tool data" here, not a fabricated 0.
    assert rec["tool_call_assertions"]["A"]["cells_with_tool_data"] == 0
    assert rec["tool_call_assertions"]["A"]["cells_total"] == 1


# --- Run-governance: loss-accounting + paired comparability (tempdoc 624) ------

def test_paired_comparability_clean_and_asymmetric():
    from jseval.utility_governance import ArmLoss, paired_comparability
    full = {0: set(f"q{i}" for i in range(10)), 1: set(f"q{i}" for i in range(10))}
    A = ArmLoss("A", 2, 10, 20, n_error_cells=0,
                excluded_query_ids=set(), ok_by_seed={k: set(v) for k, v in full.items()})
    C = ArmLoss("C", 2, 10, 20, n_error_cells=0,
                excluded_query_ids=set(), ok_by_seed={k: set(v) for k, v in full.items()})
    v, m = paired_comparability({"A": A, "C": C})
    assert v.comparable is True and m["paired_n_retention"] == 1.0
    # C drops q5-q8 (asymmetric, high rate: 9 errored cells of 20); A drops none
    okC = {0: {"q0", "q1", "q2", "q3", "q4"}, 1: {"q0", "q1", "q2", "q3", "q4", "q9"}}
    Cbad = ArmLoss("C", 2, 10, 11, n_error_cells=9,
                   excluded_query_ids={"q5", "q6", "q7", "q8"}, ok_by_seed=okC)
    v2, m2 = paired_comparability({"A": A, "C": Cbad})
    assert v2.comparable is False
    assert any("arm_C" in r for r in v2.reasons)        # per-arm exclusion rate
    assert m2["excluded_jaccard"] < 0.5                 # asymmetry caught


def test_loss_accounting_partial_log_pending_is_not_excluded():
    """Regression (2026-07-03): on a PARTIAL log, in-flight cells must count as
    PENDING, never as excluded. The old arithmetic (n_excluded = planned -
    completed) reported phantom 40%+ exclusion on healthy mid-flight runs and
    got two healthy certified runs aborted."""
    from jseval.utility_governance import ArmLoss
    # Mid-run snapshot: 5 seeds x 10 queries seen (50 planned), 30 flushed clean,
    # 0 errored — 20 cells simply in flight.
    live = ArmLoss("A", 5, 10, 30, n_error_cells=0)
    assert live.n_planned == 50
    assert live.n_attempted == 30
    assert live.n_pending == 20
    assert live.n_excluded == 0            # the regression: this was 20 before
    assert live.exclusion_rate == 0.0
    # Same snapshot with 3 real error cells: only those 3 are excluded.
    live_err = ArmLoss("A", 5, 10, 30, n_error_cells=3)
    assert live_err.n_excluded == 3
    assert live_err.n_attempted == 33 and live_err.n_pending == 17
    assert abs(live_err.exclusion_rate - 3 / 33) < 1e-9


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

    summaries = aur.eval_logs_to_summaries(log)
    gov = {"comparable": verdict.comparable, "reasons": verdict.reasons, "metrics": metrics,
           "per_arm_loss": {c: {"n_excluded": l.n_excluded} for c, l in arms.items()}}
    rec = utility_comparison.compose_utility(
        summaries, composed_at="t", governance=gov, confidence_tier="A")
    assert rec["comparability"]["comparable"] is False
    assert rec["confidence_tier"] == "C"                # DERIVED, overrides the passed "A"


def test_composer_rejects_hidden_corpus_or_resolved_model_mix():
    per_query = {"q0": {"correct": True, "cost_usd": 0.1,
                         "unique_tokens": 100, "num_turns": 1}}
    a = _summary("A", per_query, corpus={"dataset": "mixed/multihop-rag", "signature": "s1"},
                 agent_model_version="provider-v1")
    c = _summary("C", per_query, search_key="sc",
                 corpus={"dataset": "mixed/multihop-rag", "signature": "s2"},
                 agent_model_version="provider-v1")
    with pytest.raises(utility_comparison.UtilityComposeError, match="mixes corpus/resolved-model"):
        utility_comparison.compose_utility([a, c], composed_at="t")

    c_same_corpus_new_model = _summary(
        "C", per_query, search_key="sc",
        corpus={"dataset": "mixed/multihop-rag", "signature": "s1"},
        agent_model_version="provider-v2",
    )
    with pytest.raises(utility_comparison.UtilityComposeError, match="mixes corpus/resolved-model"):
        utility_comparison.compose_utility([a, c_same_corpus_new_model], composed_at="t")


def test_composer_rejects_duplicate_seed_summaries():
    per_query = {"q0": {"correct": True, "cost_usd": 0.1,
                         "unique_tokens": 100, "num_turns": 1}}
    a = _summary("A", per_query)
    c = _summary("C", per_query, search_key="sc")
    with pytest.raises(utility_comparison.UtilityComposeError, match="duplicate summaries"):
        utility_comparison.compose_utility([a, dict(a), c], composed_at="t")


def test_governance_reads_sample_conditions_from_one_consolidated_log(tmp_path):
    """Regression: executor-v2 stores every arm in one task/log.

    Loss accounting must use each sample's condition, normalize ``A|qid`` IDs,
    and retain failed attempts that the valid-cell composer projection omits.
    """
    pytest.importorskip("inspect_ai")
    from inspect_ai import Task, eval_set, task
    from inspect_ai.dataset import Sample
    from inspect_ai.solver import solver

    from jseval import agent_utility_run as aur
    from jseval.agent_utility_inspect import substring_scorer
    from jseval.utility_governance import compute_loss_accounting

    @solver
    def consolidated_solver():
        async def solve(state, generate):
            metadata = state.metadata or {}
            if metadata.get("force_error"):
                state.metadata["error"] = "forced timeout"
                return state
            state.output.completion = metadata["answer"]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 2000, "num_turns": 3})
            return state
        return solve

    @task
    def consolidated_task():
        samples = []
        for condition in ("A", "C"):
            for index in range(4):
                samples.append(Sample(
                    id=f"{condition}|q{index}",
                    input=f"Q{index}",
                    target=f"ANS{index}",
                    metadata={
                        "condition": condition,
                        "answer": f"ANS{index}",
                        "force_error": condition == "C" and index < 2,
                    },
                ))
        return Task(
            dataset=samples,
            solver=consolidated_solver(),
            scorer=substring_scorer(),
            metadata={
                "model": "haiku",
                "corpus": {"dataset": "mixed/multihop-rag", "signature": "s"},
                "cohort": {
                    "model": "haiku",
                    "cli_version": "v",
                    "mcp_tool_surface_hash": "h",
                    "judge_kind": "substring-em",
                    "prompt_template_hash": "p",
                },
            },
        )

    log_dir = (tmp_path / "consolidated").as_posix()
    eval_set(
        [consolidated_task()],
        log_dir=log_dir,
        epochs=2,
        model="mockllm/model",
        log_format="json",
    )

    arms = compute_loss_accounting(log_dir)
    assert set(arms) == {"A", "C"}
    assert arms["A"].n_completed == 8
    assert arms["A"].excluded_query_ids == set()
    assert arms["C"].n_completed == 4
    assert arms["C"].n_excluded == 4
    assert arms["C"].excluded_query_ids == {"q0", "q1"}
    assert arms["C"].ok_by_seed == {0: {"q2", "q3"}, 1: {"q2", "q3"}}

    summaries = aur.eval_logs_to_summaries(log_dir)
    by_condition_seed = {
        (summary["condition"], summary["manifest"]["seed"]): set(summary["per_query"])
        for summary in summaries
    }
    assert by_condition_seed[("A", 0)] == {"q0", "q1", "q2", "q3"}
    assert by_condition_seed[("C", 0)] == {"q2", "q3"}


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
            if md.get("condition") == "B":
                state.metadata["observed_mcp_tool_surface_hash"] = "h"
            return state
        return solve

    @task
    def gt(condition="A", correct_through=99):
        samples = [Sample(id=f"{condition}|q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"condition": condition, "idx": i, "tgt": f"ANS{i}"})
                   for i in range(4)]
        return Task(dataset=samples, solver=graded(correct_through), scorer=substring_scorer(),
                    metadata={"model": "haiku",
                              "corpus": {"dataset": "mixed/multihop-rag", "signature": "s"},
                              "cohort": _COHORT})

    log = (tmp_path / "g").as_posix()
    eval_set([gt(condition=c, correct_through=ct) for c, ct in conds_through.items()],
             log_dir=log, epochs=1, model="mockllm/model", log_format="json")
    return log


def test_pure_recomposition_digest_ignores_only_composed_at(tmp_path):
    from jseval.utility_recompose import finalize_logs, semantic_digest

    log = _graded_logs(tmp_path, {"A": 1, "B": 2})
    first = finalize_logs([log], composed_at="2026-01-01T00:00:00Z")
    replay = finalize_logs([log], composed_at="2026-02-01T00:00:00Z")

    assert first["schema_version"] == 2
    assert first["semantic_digest"] == replay["semantic_digest"]
    assert semantic_digest(first) == first["semantic_digest"]
    assert first["comparability"]["per_arm_loss"]["A"]["n_attempted"] == 4

    changed = dict(replay)
    changed["confidence_tier"] = "A"
    assert semantic_digest(changed) != first["semantic_digest"]


def test_semantic_digest_excludes_tempdoc_729_self_description_fields(tmp_path):
    """Cross-chain finding (tempdoc 736 U1): `denominators`, `seed_floor_met`,
    `exposure_contrast_ineligible`, and their nested `denominator_note`/
    claim-policy-gate mirrors are pure self-description -- either a fixed
    constant or a deterministic re-derivation of an already-digested field
    -- so they must NOT move `semantic_digest`. Regression-locks
    `semantic_projection`'s declared exclusion (utility_recompose.py)."""
    import copy

    from jseval.utility_recompose import finalize_logs, semantic_digest

    log = _graded_logs(tmp_path, {"A": 1, "B": 2})
    real = finalize_logs([log], composed_at="t")
    assert "denominators" in real
    assert "seed_floor_met" in real

    stripped = copy.deepcopy(real)
    del stripped["denominators"]
    del stripped["seed_floor_met"]
    stripped.pop("exposure_contrast_ineligible", None)
    if isinstance(stripped.get("comparability"), dict):
        stripped["comparability"].pop("denominator_note", None)
    for by_model in (stripped.get("measured") or {}).values():
        for cell in by_model.values():
            if isinstance(cell.get("funnel"), dict):
                cell["funnel"].pop("denominator_note", None)
    stripped["claim_verdict"]["gates"] = [
        g for g in stripped["claim_verdict"]["gates"] if g["name"] != "seed_floor_met"
    ]
    for stratum in stripped["claim_verdict"]["stratum_outcomes"]:
        stratum["gates"].pop("seed_floor_met", None)

    assert semantic_digest(stripped) == real["semantic_digest"]


def test_composer_separates_addition_b_and_substitution_c(tmp_path):
    pytest.importorskip("inspect_ai")
    from jseval import agent_utility_run as aur
    # A correct q0-1 (0.5); B correct q0-2 (0.75); C correct all (1.0).
    log = _graded_logs(tmp_path, {"A": 1, "B": 2, "C": 3})
    summaries = aur.eval_logs_to_summaries(log)
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
    summaries = aur.eval_logs_to_summaries(log)
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
            qid = str(state.sample_id).split("|", 1)[-1]
            state.output.completion = answers[qid]
            state.metadata.update({"cost_usd": 0.1, "unique_tokens": 1000, "num_turns": 3})
            return state
        return solve

    @task
    def ct():
        samples = [Sample(id=f"C|q{i}", input=f"Q{i}", target=f"ANS{i}",
                          metadata={"condition": "C"}) for i in range(4)]
        return Task(dataset=samples, solver=fixed(), scorer=substring_scorer(),
                    metadata={"model": "haiku",
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
    jsonschema = pytest.importorskip("jsonschema")
    schema = json.loads(
        (Path(__file__).parents[1] / "utility-comparison-cross-corpus.v1.schema.json")
        .read_text(encoding="utf-8")
    )
    identity_schema = schema["properties"]["measured"]["additionalProperties"][
        "properties"
    ]["identity"]
    jsonschema.validate(rec["measured"]["haiku"]["identity"], identity_schema)

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
    assert "git_dirty" in rec["cohort"]
    assert "environment" in rec["cohort"]
    assert set(cell["identity"]["corpus_signatures"]) == {
        "golden/battlefield-en-v1", "golden/battlefield-de-v1", "golden/synth-scan-v1",
    }
    assert cell["identity"]["resolved_provider_model"] == "4.5"


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


# --- exposure-identity carry-through (tempdoc 756) --------------------------
# compose_utility_cross_corpus dropped cohort.exposure_config +
# cohort.mcp_initialize_identity that every post-725 per-run record carries, so
# source_identity_complete (utility_claim_policy) failed on composed evidence
# that was complete per-run. These prove the blocks are carried through verbatim
# and that mixing differently-exposed campaigns fails closed (never first-wins).

_XCORPUS_EXPOSURE_CONFIG = {
    "enable_tool_search": "true", "always_load": False, "exposure_mode": "deferred",
}
_XCORPUS_MCP_INIT_IDENTITY = {
    "instructions": "search the corpus", "instructions_sha256": "d" * 64,
    "server_version": "0.4.0", "protocol_version": "2025-06-18",
}


def _xcorpus_identity_summary(dataset, condition, per_query, *, search_key=None,
                              exposure_config=None, mcp_initialize_identity=None):
    return _summary(
        condition, per_query, search_key=search_key, seed=0,
        corpus={"dataset": dataset, "signature": "v1"},
        exposure_config=(_XCORPUS_EXPOSURE_CONFIG if exposure_config is None
                         else exposure_config),
        mcp_initialize_identity=(_XCORPUS_MCP_INIT_IDENTITY if mcp_initialize_identity is None
                                 else mcp_initialize_identity),
        # Scalars folded into agent_cohort_key -- held constant across every run so
        # the cohort-key spanning check passes and the block-level carry-through
        # (which agent_cohort_key does NOT cover) is what is under test.
        exposure_mode="deferred",
        instructions_sha256="d" * 64,
    )


def _xcorpus_identity_fixture():
    en_a, en_c = _xcorpus_pq([False, True, False, True], [True, True, True, True])
    de_a, de_c = _xcorpus_pq([True, True, True, True], [True, False, True, False])
    return [
        _xcorpus_identity_summary("golden/battlefield-en-v1", "A", en_a),
        _xcorpus_identity_summary("golden/battlefield-en-v1", "C", en_c, search_key="s"),
        _xcorpus_identity_summary("golden/battlefield-de-v1", "A", de_a),
        _xcorpus_identity_summary("golden/battlefield-de-v1", "C", de_c, search_key="s"),
    ]


def test_cross_corpus_carries_exposure_identity_and_satisfies_source_identity_gate():
    from jseval.utility_claim_policy import evaluate_claim
    from tests.test_utility_claim_policy import _record

    rec = utility_comparison.compose_utility_cross_corpus(
        _xcorpus_identity_fixture(), composed_at="t",
        contamination_class="private-synthetic")

    # Carried through verbatim (the bug dropped both -> exposure_mode: None).
    assert rec["cohort"]["exposure_config"] == _XCORPUS_EXPOSURE_CONFIG
    assert rec["cohort"]["mcp_initialize_identity"] == _XCORPUS_MCP_INIT_IDENTITY

    # ...and those carried blocks satisfy source_identity_complete on an
    # otherwise-complete record (the gate that was failing on complete evidence).
    complete = _record()
    complete["cohort"]["exposure_config"] = rec["cohort"]["exposure_config"]
    complete["cohort"]["mcp_initialize_identity"] = rec["cohort"]["mcp_initialize_identity"]
    verdict = evaluate_claim(complete)
    gate = next(g for g in verdict["gates"] if g["name"] == "source_identity_complete")
    assert gate["passed"] is True


def test_cross_corpus_fails_closed_on_mismatched_exposure_config():
    fixture = _xcorpus_identity_fixture()
    # One corpus's run was exposed in a different mode -- mixing differently-exposed
    # campaigns must stay an error, never a silent first-wins (tempdoc 756). The
    # folded scalar exposure_mode is held equal (see helper) so the agent_cohort_key
    # spanning check passes and THIS block-level check is what fires.
    fixture[2]["manifest"]["exposure_config"] = {
        **_XCORPUS_EXPOSURE_CONFIG, "exposure_mode": "eager",
    }
    with pytest.raises(utility_comparison.UtilityComposeError, match="exposure_config"):
        utility_comparison.compose_utility_cross_corpus(fixture, composed_at="t")


def test_cross_corpus_fails_closed_on_mismatched_mcp_initialize_identity():
    fixture = _xcorpus_identity_fixture()
    # A differing MCP server_version (instructions_sha256 held equal, so the
    # cohort key still agrees) must fail closed rather than first-wins.
    fixture[2]["manifest"]["mcp_initialize_identity"] = {
        **_XCORPUS_MCP_INIT_IDENTITY, "server_version": "9.9.9",
    }
    with pytest.raises(utility_comparison.UtilityComposeError, match="mcp_initialize_identity"):
        utility_comparison.compose_utility_cross_corpus(fixture, composed_at="t")


def test_cross_corpus_governance_derives_tier_from_least_comparable_input():
    gov = {"comparable": False, "reasons": ["logs-de: asymmetric_exclusion"], "metrics": {},
           "per_arm_loss": {}}
    rec = utility_comparison.compose_utility_cross_corpus(
        _xcorpus_fixture(), composed_at="t", confidence_tier="A", governance=gov)
    assert rec["comparability"]["comparable"] is False
    assert rec["confidence_tier"] == "C"                # DERIVED, overrides the passed "A"


# --- revision (tempdoc 624 Design 1) ----------------------------------------

def test_build_revision_constructs_valid_object():
    rev = utility_comparison.build_revision(
        supersedes="../out-en/utility-comparison.v1.json",
        reason="leak_correction",
        changed_fields=["measured.golden/battlefield-en-v1.haiku.accuracy"],
    )
    assert rev == {
        "supersedes": "../out-en/utility-comparison.v1.json",
        "reason": "leak_correction",
        "changed_fields": ["measured.golden/battlefield-en-v1.haiku.accuracy"],
    }


def test_build_revision_accepts_every_closed_set_reason():
    for reason in utility_comparison.REVISION_REASONS:
        rev = utility_comparison.build_revision(
            supersedes="../out/utility-comparison.v1.json", reason=reason, changed_fields=[])
        assert rev["reason"] == reason


def test_build_revision_accepts_arm_invalidation_reason():
    """tempdoc 624 battlefield retrospective: annotates a record whose with-tool
    arm is now known to have run under a dead mcp_config (0 MCP tool calls)."""
    rev = utility_comparison.build_revision(
        supersedes="../out/utility-comparison.v1.json",
        reason="arm_invalidation",
        changed_fields=["measured.golden/battlefield-en-v1.haiku.with_tool"],
    )
    assert rev["reason"] == "arm_invalidation"


def test_build_revision_rejects_reason_outside_closed_set():
    with pytest.raises(utility_comparison.UtilityComposeError, match="not in closed set"):
        utility_comparison.build_revision(
            supersedes="../out/utility-comparison.v1.json",
            reason="oops_not_a_real_reason",
            changed_fields=[],
        )


def test_build_revision_changed_fields_is_a_copy_not_the_caller_list():
    src = ["cohort.judge"]
    rev = utility_comparison.build_revision(
        supersedes="../out/utility-comparison.v1.json", reason="judge_rescore", changed_fields=src)
    src.append("mutated_after_the_call")
    assert rev["changed_fields"] == ["cohort.judge"]


def test_build_revision_rejects_empty_supersedes():
    with pytest.raises(utility_comparison.UtilityComposeError, match="non-empty path string"):
        utility_comparison.build_revision(supersedes="", reason="leak_correction", changed_fields=[])


def test_build_revision_rejects_whitespace_only_supersedes():
    with pytest.raises(utility_comparison.UtilityComposeError, match="non-empty path string"):
        utility_comparison.build_revision(supersedes="   ", reason="leak_correction", changed_fields=[])


def test_build_revision_rejects_non_string_changed_field():
    with pytest.raises(utility_comparison.UtilityComposeError, match="must be strings"):
        utility_comparison.build_revision(
            supersedes="../out/utility-comparison.v1.json",
            reason="leak_correction",
            changed_fields=["cohort.judge", 42],
        )


def test_non_semantic_digest_exclusion_list_is_exactly_pinned():
    """Tempdoc 736 hardening (refute-first review finding): the digest-exclusion
    frozenset must be an exact, test-visible contract -- adding ANY field to it
    silently removes that field from semantic_digest coverage, so growth must be
    a deliberate act that fails this pin first. Do not extend the set without a
    tempdoc-recorded justification that the new field is a pure re-derivation of
    already-digested content."""
    from jseval.utility_recompose import _NON_SEMANTIC_TOP_LEVEL_FIELDS

    # tempdoc 624 (2026-07-17 "Time as the third utility axis" / outcome-rule
    # provenance): `outcome_rule` is a fixed constant stamped on every record --
    # pure self-description, never discriminating measurement content -- so it is
    # excluded from the digest by the same rationale as `denominators`.
    assert _NON_SEMANTIC_TOP_LEVEL_FIELDS == frozenset(
        {"denominators", "seed_floor_met", "exposure_contrast_ineligible", "outcome_rule"}
    )
