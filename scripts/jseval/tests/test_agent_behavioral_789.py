"""tempdoc 789 Phase 1 — behavioral (continuation-survival) telemetry.

Five strands:
  1. classifier units — name_pivot / hop1_stop / wrongness / tool-shape, each with a
     positive, a negative and the edge that would silently mis-count;
  2. delivered-span record — the cell-time half: ordering (delivered BEFORE probed),
     question-vocabulary exclusion, structured-json deliveries;
  3. harness capture — `_record_cell` stashes the delivered record + per-turn receipts,
     and a classifier defect degrades to a reason instead of voiding the cell;
  4. composer neutrality — a record composed with the behavioral block is
     verdict-identical AND digest-identical to one composed without it;
  5. evidence round-trip — sanitize -> read_evidence preserves the record, drops an
     undeclared key, keeps the tri-state, and stays byte-identical when absent.

The replay against the 2026-07-28 window-2 hero logs (the charter's acceptance bar)
is `test_replay_behavioral_789.py`, which skips when those logs are absent.
"""

from __future__ import annotations

import copy

import pytest

pytest.importorskip("inspect_ai")

from jseval import agent_utility_inspect as aui  # noqa: E402
from jseval.agent_behavioral import (  # noqa: E402
    CENSUS_IDENTIFIER_SHAPE,
    aggregate_behavioral,
    behavioral_record,
    classify_answer,
    delivered_entities,
    delivered_span_record,
    gold_shape_pattern,
    hop1_stop,
    name_pivot,
    probe_texts,
    tool_shape,
)
from jseval.utility_evidence import read_evidence, sanitize_observation  # noqa: E402
from jseval.utility_recompose import (  # noqa: E402
    finalize_observation_groups,
    semantic_digest,
)
from tests.test_agent_utility_inspect import _rmsg, _state  # noqa: E402
from tests.test_utility_evidence import _observation  # noqa: E402

SEARCH = "mcp__justsearch__justsearch_search"
ANSWER = "mcp__justsearch__justsearch_answer"


# --- 1. classifier units ----------------------------------------------------

def test_name_pivot_fires_on_any_long_token_of_an_entity():
    # The hop-2 move: the cell put a delivered name into a later probe. Matching is
    # normalize-then-substring, so punctuation/case in the probe cannot hide it.
    assert name_pivot(["Langna Solational Tna"], ["grep -r 'solational' corpus/"])
    assert name_pivot(["Blanca Daugherus"], ["daugherus designation"])


def test_name_pivot_ignores_short_tokens():
    # A <=3-char token is noise (an initial, "the", a unit) and must not pivot a cell.
    # Without the length floor, "Tna" would match half the corpus.
    assert not name_pivot(["Tna Xu"], ["tna appears everywhere"])


def test_name_pivot_false_without_entities_or_probes():
    assert not name_pivot([], ["anything at all"])
    assert not name_pivot(["Blanca Daugherus"], [])
    assert not name_pivot(["Blanca Daugherus"], ["power station wetlands"])


def test_hop1_stop_requires_entity_present_and_gold_absent():
    entities = ["Blanca Daugherus"]
    assert hop1_stop(entities, "The observatory was founded by Blanca Daugherus.", "XQN 853")
    # Gold present -> the cell completed the chain, not a hop-1 stop, even though the
    # intermediate entity is also named.
    assert not hop1_stop(entities, "Blanca Daugherus is linked to XQN 853.", "XQN 853")
    # Entity absent -> nothing to stop at.
    assert not hop1_stop(entities, "No such facility exists.", "XQN 853")


def test_hop1_stop_ignores_too_short_an_entity():
    # A 5-char normalized entity would assert presence on almost any answer.
    assert not hop1_stop(["Ab Cd"], "abcd is everywhere in abcdefg", "XQN 853")


def test_wrongness_taxonomy_partitions_in_census_order():
    gold = "XQN 853"
    assert classify_answer("anything", gold, error=None, correct=True) is None
    assert classify_answer("", gold, error="boom", correct=False) == "harness_error"
    assert classify_answer("   ", gold, error=None, correct=False) == "abstained"
    # Gold IS in the text but the substring scorer still said wrong -> a format near
    # miss, NOT a fabrication. This branch must win over the abstention markers below.
    assert classify_answer(
        "I could not find much, but the value is XQN-853.", gold,
        error=None, correct=False) == "format_near_miss"
    assert classify_answer(
        "No document mentions that facility.", gold,
        error=None, correct=False) == "abstained"
    assert classify_answer(
        "The value is the founder, Blanca Daugherus.", gold,
        error=None, correct=False) == "fabricated_specific"


def test_wrong_value_uses_the_shape_of_the_cells_own_gold():
    # An identifier of the gold's shape that is not the gold: a stated wrong value,
    # distinct from a fabricated prose answer.
    assert classify_answer(
        "The value is PQR 111.", "XQN 853", error=None, correct=False) == "wrong_value"
    # A differently-shaped identifier is not a wrong value under the per-cell default.
    assert classify_answer(
        "The value is PD-8836.", "XQN 853", error=None, correct=False) == "fabricated_specific"


def test_gold_shape_pattern_reproduces_the_census_alternatives():
    # The generalization claim: a per-cell derived skeleton IS each hand-written
    # alternative of the census shape. If this drifts, the replay's "definitions
    # agree" assertion is what catches it on real data.
    import re

    for gold, sample in (
        ("XQN 853", "PQR 111"),
        ("PD-8836", "AB-1234"),
        ("2975Y", "1111A"),
        ("ref-68-5149", "ref-11-2222"),
    ):
        pattern = gold_shape_pattern(gold)
        assert pattern and re.search(pattern, sample), (gold, pattern)
        assert re.search(CENSUS_IDENTIFIER_SHAPE, sample), gold
    assert gold_shape_pattern(None) is None
    assert gold_shape_pattern("") is None


def _seq(*names):
    return [{"name": name, "status": "ok"} for name in names]


def test_tool_shape_orders_search_before_grep_and_counts_the_mix():
    shape = tool_shape(
        _seq(SEARCH, "Grep", "Read", "Read", ANSWER),
        [
            {"tool": SEARCH, "input": {"query": "power station"}},
            {"tool": "Grep", "input": {"pattern": "wetlands"}},
            {"tool": ANSWER, "input": {"query": "Blanca Daugherus"}},
        ],
    )
    assert shape["searched_before_grep"] is True
    assert shape["grep_fallback_after_mcp"] is True
    assert shape["fallback_after_mcp"] is True
    assert shape["post_search_reads"] == 2
    assert shape["distinct_queries"] == 2
    assert shape["tool_mix"]["justsearch_search"] == 1
    assert shape["tool_mix"]["justsearch_answer"] == 1
    assert shape["tool_mix"]["Grep"] == 1
    assert shape["tool_mix"]["mcp_justsearch_total"] == 2


def test_distinct_queries_folds_case_and_whitespace():
    # The same query re-issued with different casing/padding is a repeat, not a
    # reformulation -- otherwise "did the cell rephrase?" counts noise.
    shape = tool_shape(
        _seq(SEARCH, SEARCH),
        [{"tool": SEARCH, "input": {"query": "power station"}},
         {"tool": SEARCH, "input": {"query": "  Power Station "}}],
    )
    assert shape["distinct_queries"] == 1


def test_tool_shape_baseline_arm_never_searches_first():
    shape = tool_shape(_seq("Grep", "Read", "Bash"), [{"tool": "Grep", "input": {"pattern": "x"}}])
    assert shape["searched_before_grep"] is False
    assert shape["fallback_after_mcp"] is False
    assert shape["post_search_reads"] == 0
    assert shape["distinct_queries"] == 0


def test_tool_shape_tolerates_missing_sequences():
    shape = tool_shape(None, None)
    assert shape["searched_before_grep"] is False
    assert shape["tool_mix"]["mcp_justsearch_total"] == 0


def test_probe_texts_reads_the_three_probe_keys_only():
    probes = probe_texts([
        {"tool": "Grep", "input": {"pattern": "alpha"}},
        {"tool": "Bash", "input": {"command": "grep beta ."}},
        {"tool": SEARCH, "input": {"query": "gamma"}},
        {"tool": "Read", "input": {"file_path": "/corpus/delta.txt"}},
        {"tool": "Read", "input": None},
        "not-a-dict",
    ])
    assert probes == ["alpha", "grep beta .", "gamma"]


# --- 2. delivered-span record ----------------------------------------------

def test_delivered_entities_drops_the_questions_own_vocabulary():
    question = "What is the value associated with the Power Station?"
    entities = delivered_entities("The Power Station was founded by Blanca Daugherus.", question)
    assert "Blanca Daugherus" in entities
    assert "Power Station" not in entities


def test_delivered_entities_reads_structured_json_deliveries():
    content = [{"type": "json", "json": {"excerpts": ["operated by Marcus Vandergrift"]}}]
    assert "Marcus Vandergrift" in delivered_entities(content, "who operates it?")


def test_delivered_span_record_requires_delivery_before_the_probe():
    question = "What is the value for the observatory?"
    delivery = [{"type": "text", "text": "The observatory was founded by Blanca Daugherus."}]
    # Delivered on call 1, probed on call 2 -> a pivot.
    pivoted = delivered_span_record(
        [
            ({"query": "observatory"}, delivery),
            ({"pattern": "Daugherus"}, [{"type": "text", "text": "hit"}]),
        ],
        question=question, answer="The value is XQN 853.", gold="XQN 853")
    assert pivoted["name_pivot"] is True
    assert pivoted["entity_source"] == "delivered-span"
    assert pivoted["delivered_entity_count"] >= 1

    # Same two calls in the opposite order: the name was probed BEFORE anything
    # delivered it, so it came from the cell's own priors, not from the tool.
    not_pivoted = delivered_span_record(
        [
            ({"pattern": "Daugherus"}, [{"type": "text", "text": "hit"}]),
            ({"query": "observatory"}, delivery),
        ],
        question=question, answer="The value is XQN 853.", gold="XQN 853")
    assert not_pivoted["name_pivot"] is False


def test_delivered_span_record_detects_a_hop1_stop():
    record = delivered_span_record(
        [({"query": "observatory"},
          [{"type": "text", "text": "The observatory was founded by Blanca Daugherus."}])],
        question="What is the value for the observatory?",
        answer="It was founded by Blanca Daugherus.", gold="XQN 853")
    assert record["hop1_stop"] is True


def test_behavioral_record_keeps_delivered_fields_unknown_without_a_cell_time_record():
    record = behavioral_record(
        answer="No document mentions that.", gold="XQN 853", error=None, correct=False,
        tool_call_sequence=_seq("Grep"), tool_calls=[{"tool": "Grep", "input": {"pattern": "x"}}])
    # Honest unknown, never a fabricated False -- an aggregate must not count a
    # pre-789 log as "did not pivot".
    assert record["name_pivot"] is None
    assert record["hop1_stop"] is None
    assert record["entity_source"] is None
    assert record["abstained"] is True
    assert record["wrong_class"] == "abstained"


# --- 3. harness capture -----------------------------------------------------

def _got(rmsg, attempts=None, results=None, receipts=None):
    return {
        "attempts": attempts or {}, "results": results or {}, "texts": [], "rmsg": rmsg,
        "mcp_servers": None, "justsearch_tools": [],
        "turn_receipts": receipts if receipts is not None else [],
    }


def _targeted_state(input_text, gold):
    """`TaskState.target` is read-only, so build the state with its Target in place."""
    from inspect_ai.model import ChatMessageUser, ModelName
    from inspect_ai.scorer import Target
    from inspect_ai.solver import TaskState

    return TaskState(
        model=ModelName("mockllm/model"), sample_id="q0", epoch=0,
        input=input_text, messages=[ChatMessageUser(content=input_text)],
        target=Target(gold),
    )


def test_record_cell_stashes_the_delivered_span_record_and_receipts():
    state = _targeted_state("What is the value for the observatory?", "XQN 853")
    attempts = {
        "t1": {"tool": SEARCH, "input": {"query": "observatory"}},
        "t2": {"tool": "Grep", "input": {"pattern": "Daugherus"}},
    }
    results = {
        "t1": {"is_error": False,
               "content": [{"type": "text",
                            "text": "The observatory was founded by Blanca Daugherus."}]},
        "t2": {"is_error": False, "content": "1 hit"},
    }
    receipts = [{"i": 0, "model": "claude-x", "usage": {"input_tokens": 10},
                 "tool_calls_issued": 0}]
    aui._record_cell(state, _got(_rmsg(result="It was founded by Blanca Daugherus."),
                                 attempts, results, receipts),
                     "B", [], None)
    delivered = state.metadata["behavioral_delivered"]
    assert delivered["name_pivot"] is True
    assert delivered["hop1_stop"] is True
    assert delivered["entity_source"] == "delivered-span"
    assert state.metadata["turn_receipts"] == receipts


def test_record_cell_survives_a_behavioral_defect_without_voiding_the_cell():
    # A telemetry defect must never cost a (paid) cell its place in the estimand:
    # the failure is recorded as a reason, `error` stays unset, cost/turns survive.
    state = _targeted_state("what is x?", "XQN 853")
    got = _got(_rmsg(), attempts={"t1": {"tool": "Grep", "input": {"pattern": "x"}}},
               results={"t1": {"is_error": False, "content": "hit"}})
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(aui, "delivered_span_record",
                      lambda *a, **k: (_ for _ in ()).throw(RuntimeError("boom")))
        aui._record_cell(state, got, "A", [], None)
    assert "error" in state.metadata["behavioral_delivered"]
    assert state.metadata.get("error") is None
    assert state.metadata["cost_usd"] == 0.02


def test_record_cell_defaults_receipts_for_a_capture_without_them():
    # Pre-789 fixtures build `got` without the key; capture must not raise.
    state = _state()
    got = {"attempts": {}, "results": {}, "texts": [], "rmsg": _rmsg(),
           "mcp_servers": None, "justsearch_tools": []}
    aui._record_cell(state, got, "A", [], None)
    assert state.metadata["turn_receipts"] == []


# --- 4. composer neutrality -------------------------------------------------

def _cell(condition, qid, *, correct=True, behavioral=None):
    obs = _observation(condition, qid=qid)
    obs["correct"] = correct
    obs["cost_usd"] = 0.1
    obs["unique_tokens"] = 10
    obs["total_time"] = 100.0
    obs["working_time"] = 100.0
    obs["source"]["cohort"]["query_identity"] = {"sha256": "e" * 64, "row_count": 2}
    obs["source"]["cohort"]["campaign_identity"] = {
        "conditions": ["A", "B"], "seeds": 1,
        "expected_cells": ["A|0|q0", "B|0|q0", "A|0|q1", "B|0|q1"],
    }
    if behavioral is not None:
        obs["behavioral"] = behavioral
    return obs


def _fixture_cells(with_behavioral: bool):
    cells = []
    for condition in ("A", "B"):
        for index, qid in enumerate(("q0", "q1")):
            behavioral = behavioral_record(
                answer="No document mentions that." if index else "The value is XQN 853.",
                gold="XQN 853", error=None, correct=index == 0,
                tool_call_sequence=_seq(SEARCH, "Grep") if condition == "B" else _seq("Grep"),
                tool_calls=[{"tool": SEARCH, "input": {"query": "obs"}}]
                if condition == "B" else [{"tool": "Grep", "input": {"pattern": "obs"}}],
                delivered={"name_pivot": index == 0, "hop1_stop": False,
                           "delivered_entity_count": 3, "entity_source": "delivered-span"},
            ) if with_behavioral else None
            cells.append(_cell(condition, qid, correct=index == 0, behavioral=behavioral))
    return cells


def test_behavioral_block_changes_neither_the_verdict_nor_the_digest():
    with_block = finalize_observation_groups([_fixture_cells(True)], composed_at="t")
    without_block = finalize_observation_groups([_fixture_cells(False)], composed_at="t")
    assert "behavioral" in with_block
    assert "behavioral" not in without_block
    # The whole point of item 4: a descriptive block, and provably so.
    assert with_block["claim_verdict"] == without_block["claim_verdict"]
    assert with_block["comparability"] == without_block["comparability"]
    assert with_block["estimands"] == without_block["estimands"]
    assert semantic_digest(with_block) == semantic_digest(without_block)


def test_behavioral_aggregate_reports_per_stratum_arm_counts():
    record = finalize_observation_groups([_fixture_cells(True)], composed_at="t")
    block = record["behavioral"]
    assert block["descriptive_only"] is True
    keys = sorted(block["per_stratum_arm"])
    assert len(keys) == 2 and keys[0].endswith("|A") and keys[1].endswith("|B")
    arm_b = block["per_stratum_arm"][keys[1]]
    assert arm_b["n_cells"] == 2
    assert arm_b["searched_before_grep"] == {"true": 2, "known": 2}
    assert arm_b["name_pivot"] == {"true": 1, "known": 2}
    assert arm_b["abstained"] == {"true": 1, "known": 2}
    assert arm_b["tool_mix"]["justsearch_search"] == 2


def test_behavioral_aggregate_never_counts_an_unknown_as_a_negative():
    known = behavioral_record(
        answer="x", gold="XQN 853", error=None, correct=False,
        tool_call_sequence=_seq("Grep"), tool_calls=[],
        delivered={"name_pivot": True, "hop1_stop": False,
                   "delivered_entity_count": 1, "entity_source": "delivered-span"})
    unknown = behavioral_record(
        answer="x", gold="XQN 853", error=None, correct=False,
        tool_call_sequence=_seq("Grep"), tool_calls=[])
    block = aggregate_behavioral([
        _cell("A", "q0", behavioral=known), _cell("A", "q1", behavioral=unknown)])
    stratum = next(iter(block["per_stratum_arm"].values()))
    assert stratum["n_cells"] == 2
    assert stratum["name_pivot"] == {"true": 1, "known": 1}


def test_behavioral_aggregate_is_empty_without_any_record():
    assert aggregate_behavioral([_cell("A", "q0")]) == {}


# --- 5. evidence round-trip -------------------------------------------------

def _write(tmp_path, observations):
    import json

    path = tmp_path / "evidence.jsonl"
    path.write_text(
        "".join(json.dumps(sanitize_observation(o), sort_keys=True) + "\n" for o in observations),
        encoding="utf-8")
    return path


def test_behavioral_and_receipts_survive_sanitize_and_read(tmp_path):
    record = behavioral_record(
        answer="No document mentions that.", gold="XQN 853", error=None, correct=False,
        tool_call_sequence=_seq(SEARCH, "Read"),
        tool_calls=[{"tool": SEARCH, "input": {"query": "obs"}}],
        delivered={"name_pivot": True, "hop1_stop": False,
                   "delivered_entity_count": 4, "entity_source": "delivered-span"})
    observation = _cell("B", "q0", behavioral=record)
    observation["turn_receipts"] = [
        {"i": 0, "model": "claude-x", "stop_reason": "tool_use",
         "usage": {"input_tokens": 12, "output_tokens": 3}, "tool_calls_issued": 0}]
    restored = read_evidence(_write(tmp_path, [observation]))[0]
    assert restored["behavioral"]["name_pivot"] is True
    assert restored["behavioral"]["wrong_class"] == "abstained"
    assert restored["behavioral"]["post_search_reads"] == 1
    assert restored["turn_receipts"][0]["usage"] == {"input_tokens": 12, "output_tokens": 3}


def test_sanitizer_drops_an_undeclared_behavioral_key(tmp_path):
    observation = _cell("B", "q0", behavioral={
        "wrong_class": "abstained", "name_pivot": True,
        "delivered_entity_names": ["Blanca Daugherus"],   # raw text: must not survive
    })
    sanitized = sanitize_observation(observation)
    assert "delivered_entity_names" not in sanitized["behavioral"]
    assert sanitized["behavioral"]["name_pivot"] is True
    # And an unknown key would be rejected on read, so the drop is enforced twice.
    read_evidence(_write(tmp_path, [observation]))


def test_sanitizer_preserves_the_delivered_tristate(tmp_path):
    observation = _cell("A", "q0", behavioral=behavioral_record(
        answer="x", gold="XQN 853", error=None, correct=False,
        tool_call_sequence=_seq("Grep"), tool_calls=[]))
    restored = read_evidence(_write(tmp_path, [observation]))[0]
    assert restored["behavioral"]["name_pivot"] is None
    assert restored["behavioral"]["hop1_stop"] is None


def test_pre_789_evidence_is_byte_identical(tmp_path):
    observation = _cell("A", "q0")
    sanitized = sanitize_observation(observation)
    assert "behavioral" not in sanitized
    assert "turn_receipts" not in sanitized
    baseline = copy.deepcopy(sanitized)
    observation["behavioral"] = None
    observation["turn_receipts"] = []
    assert sanitize_observation(observation) == baseline
