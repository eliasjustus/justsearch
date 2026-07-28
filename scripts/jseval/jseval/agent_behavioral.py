"""tempdoc 789 Phase 1 — behavioral ("continuation-survival") telemetry.

The 782 hero campaign's mechanism analysis was a HAND census over one campaign's
transcripts (`tmp/hero-arc-analysis/census/`). This module promotes that census's
explanatory metrics into the standing harness so no future campaign needs one.

Two entity sources, deliberately kept distinct (the charter's honest limit):

* **delivered-span** (`name_pivot`, `hop1_stop`) — entities are extracted from the
  tool RESULT text, which only exists in-process while a cell runs. These fields are
  therefore computed in `agent_utility_inspect._record_cell` and stashed under
  `state.metadata["behavioral_delivered"]`; they are `None` for any log recorded
  before this module existed (the window-2 hero logs included — result text is not
  persisted anywhere in an Inspect log, see `tmp/hero-arc-analysis/census/extract.py`).
* **supplied** — an explicit entity list handed in by a caller that knows the corpus
  ground truth. This is the census's own hop-1-person definition and is used by
  `scripts/jseval/experiments/replay_behavioral_789.py` to reproduce the published
  census numbers EXACTLY over the window-2 logs.

Both sources run through the SAME matching core (`name_pivot` / `hop1_stop` below),
so the replay validates the shipped classifier, not a parallel copy of it.

Everything else in the record is derivable from what an Inspect log already persists
(`tool_call_sequence`, `tool_calls`, the answer, the target), so it is computed in the
observation projection (`agent_utility_observations.read_inspect_observations`) and is
available for historical logs too.

LEAK BOUNDARY: this module reads raw answer/result text but returns ONLY booleans,
counts and class labels. No verbatim text ever reaches a returned record.
"""

from __future__ import annotations

import re
from typing import Any, Iterable, Sequence

SCHEMA = "agent-behavioral.v1"

SEARCH_TOOL = "mcp__justsearch__justsearch_search"
ANSWER_TOOL = "mcp__justsearch__justsearch_answer"
_MCP_JUSTSEARCH_PREFIX = "mcp__justsearch__"
_RETRIEVAL_TOOLS = (SEARCH_TOOL, ANSWER_TOOL)

# The agent's own file-inspection tools (the fallback surface the census tracked as
# "did the cell abandon the tool and go back to grepping the corpus by hand").
NATIVE_FILE_TOOLS = ("Grep", "Read", "Glob", "Bash")
_COUNTED_TOOLS = ("Grep", "Read", "Glob", "Bash", "ToolSearch")

# The input keys a tool call can carry a free-text probe under. Same three the census
# used (`tmp/hero-arc-analysis/census/pivot.py`): a Grep pattern, a Bash command, an
# MCP query. A pivot is visible in ANY of them.
PROBE_INPUT_KEYS = ("pattern", "command", "query")

# Verbatim from the census classifier (`tmp/hero-arc-analysis/census/features.py`
# `classify_wrong`). Order and wording are load-bearing: the replay reproduces the
# published `wrongness.v1.json` counts against them.
ABSTENTION_MARKERS = (
    "not found", "no reference", "could not find", "couldn't find", "not present",
    "does not appear", "no mention", "unable to find", "not exist", "no document",
    "found no", "no match", "cannot find", "not contain", "no such", "not appear",
)

# The census's corpus-specific injected-identifier shape, kept for the replay's
# exact-replication arm ONLY. The harness default derives the shape from the cell's
# own gold answer instead (`gold_shape_pattern`) so no corpus-specific literal is
# baked into a standing metric.
CENSUS_IDENTIFIER_SHAPE = (
    r"(?:[A-Z]{3}\s?\d{3}|ref-\d\d-\d{4}|lot\s?\d{7}|grade\s?\d\d-[A-Z]{2}-\d"
    r"|[A-Z]{2}-\d{4}|\d{4}[A-Z])"
)

WRONG_CLASSES = (
    "harness_error", "abstained", "format_near_miss", "wrong_value", "fabricated_specific",
)

_MIN_ENTITY_TOKEN_LEN = 4          # census: `len(t) > 3`
_MIN_ANSWER_ENTITY_NORM_LEN = 6    # census: `len(n(person)) > 5`
_ENTITY_RUN = re.compile(r"[A-Z][A-Za-z'’\-]+(?:\s+[A-Z][A-Za-z'’\-]+){1,3}")
_MAX_DELIVERED_ENTITIES = 400


def normalize(value: Any) -> str:
    """Alphanumeric-only lowercase fold — the census's `n()` / `norm()`."""
    return re.sub(r"[^a-z0-9]", "", (value or "").lower())


def entity_tokens(entity: str) -> list[str]:
    """The >3-character word tokens of an entity name (census `pivot.py` line 14)."""
    return [
        token for token in re.split(r"[^A-Za-z0-9]+", entity or "")
        if len(token) >= _MIN_ENTITY_TOKEN_LEN
    ]


def probe_texts(tool_calls: Iterable[dict] | None) -> list[str]:
    """Every free-text probe string a cell's executed tool calls carried."""
    probes: list[str] = []
    for call in tool_calls or []:
        if not isinstance(call, dict):
            continue
        payload = call.get("input")
        if not isinstance(payload, dict):
            continue
        for key in PROBE_INPUT_KEYS:
            value = payload.get(key)
            if value:
                probes.append(str(value))
    return probes


def name_pivot(entities: Sequence[str], probes: Sequence[str]) -> bool:
    """Did the cell put an entity's name INTO a tool call?

    The hop-2 move: an entity the cell did not start with appears in a probe it
    subsequently issued. Matching is normalize-then-substring on any >3-char token
    of any entity — identical to the census's `pivot.py`.
    """
    blob = normalize(" ".join(str(p) for p in probes))
    if not blob:
        return False
    for entity in entities:
        tokens = entity_tokens(str(entity))
        if tokens and any(normalize(token) in blob for token in tokens):
            return True
    return False


def hop1_stop(entities: Sequence[str], answer: str | None, gold: str | None) -> bool:
    """Did the final answer stop at an intermediate entity?

    True when the answer names one of `entities` but does NOT carry the gold value —
    the cell delivered the hop-1 hand-off and then terminated on it. Census parity:
    an entity whose normalized form is <=5 chars is too short to assert presence on.
    """
    answer_norm = normalize(answer)
    if not answer_norm:
        return False
    if normalize(gold) and normalize(gold) in answer_norm:
        return False
    for entity in entities:
        entity_norm = normalize(entity)
        if len(entity_norm) > _MIN_ANSWER_ENTITY_NORM_LEN - 1 and entity_norm in answer_norm:
            return True
    return False


def _delivered_text_view(content: Any) -> str:
    """Leak-safe text view of a tool result, for entity extraction only.

    Wider than `agent_utility_inspect._content_text` on purpose: an MCP
    structured-json delivery carries its payload under a `json` block, and the
    entities the agent was HANDED live in exactly that payload.
    """
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for block in content:
            if isinstance(block, dict):
                text = block.get("text")
                if isinstance(text, str):
                    parts.append(text)
                elif block.get("json") is not None:
                    parts.append(_delivered_text_view(block.get("json")))
            elif isinstance(block, str):
                parts.append(block)
        return "\n".join(parts)
    if isinstance(content, dict):
        return "\n".join(_delivered_text_view(v) for v in content.values())
    return str(content)


def delivered_entities(text: Any, question: str | None) -> list[str]:
    """Candidate entities a tool result delivered that the QUESTION did not contain.

    "Entity" here is a run of 2-4 capitalized words — the generalization of the
    census's hop-1 PERSON, with no corpus-specific template. An entity every one of
    whose long tokens already appears in the question is dropped: re-issuing the
    question's own terms is not a pivot.
    """
    view = _delivered_text_view(text)
    if not view:
        return []
    question_norm = normalize(question)
    seen: dict[str, None] = {}
    for match in _ENTITY_RUN.finditer(view):
        candidate = " ".join(match.group(0).split())
        tokens = entity_tokens(candidate)
        if not tokens:
            continue
        if all(normalize(token) in question_norm for token in tokens):
            continue
        seen.setdefault(candidate, None)
        if len(seen) >= _MAX_DELIVERED_ENTITIES:
            break
    return list(seen)


def gold_shape_pattern(gold: str | None) -> str | None:
    """Derive an identifier-shape regex from the cell's own gold answer.

    Uppercase runs -> ``[A-Z]{n}``, digit runs -> ``\\d{n}``, lowercase runs stay
    literal (they are the identifier's prefix word, e.g. ``ref``/``lot``/``grade``),
    whitespace -> ``\\s?``, anything else -> an escaped literal. On the hero corpora
    this reproduces each alternative of the census's hand-written
    `CENSUS_IDENTIFIER_SHAPE`, without the corpus-specific literal.
    """
    if not gold:
        return None
    parts: list[str] = []
    for kind, run in _runs(gold):
        if kind == "upper":
            parts.append(f"[A-Z]{{{len(run)}}}")
        elif kind == "digit":
            parts.append(rf"\d{{{len(run)}}}")
        elif kind == "lower":
            parts.append(re.escape(run))
        elif kind == "space":
            parts.append(r"\s?")
        else:
            parts.append(re.escape(run))
    return "".join(parts) if parts else None


def _runs(text: str) -> list[tuple[str, str]]:
    runs: list[tuple[str, str]] = []
    for match in re.finditer(r"[A-Z]+|[a-z]+|\d+|\s+|.", text):
        run = match.group(0)
        if run.isspace():
            kind = "space"
        elif run.isdigit():
            kind = "digit"
        elif run.isupper() and run.isalpha():
            kind = "upper"
        elif run.islower() and run.isalpha():
            kind = "lower"
        else:
            kind = "other"
        runs.append((kind, run))
    return runs


def classify_answer(answer: str | None, gold: str | None, *,
                    error: Any = None,
                    identifier_pattern: str | None = None,
                    correct: bool = False) -> str | None:
    """The wrongness taxonomy. Returns None for a correct cell.

    Partition order is the census's (`features.py::classify_wrong`) and must not be
    reordered: harness error > empty > gold-present-but-scored-wrong (format near
    miss) > a non-gold identifier of the gold's shape (wrong value) > explicit
    not-found (abstention) > everything else (a fabricated specific).

    `identifier_pattern` defaults to the shape derived from `gold` itself; the
    replay passes `CENSUS_IDENTIFIER_SHAPE` to reproduce the census exactly.
    """
    if correct:
        return None
    if error:
        return "harness_error"
    text = answer or ""
    if not text.strip():
        return "abstained"
    if normalize(gold) and normalize(gold) in normalize(text):
        return "format_near_miss"
    pattern = identifier_pattern if identifier_pattern is not None else gold_shape_pattern(gold)
    if pattern and re.search(pattern, text):
        return "wrong_value"
    low = text.lower()
    if any(marker in low for marker in ABSTENTION_MARKERS):
        return "abstained"
    return "fabricated_specific"


def _sequence_names(tool_call_sequence: Iterable[dict] | None) -> list[str]:
    return [
        str(item.get("name"))
        for item in (tool_call_sequence or [])
        if isinstance(item, dict) and item.get("name")
    ]


def tool_shape(tool_call_sequence: Iterable[dict] | None,
               tool_calls: Iterable[dict] | None) -> dict:
    """The ordering/mix half of the record — everything an Inspect log already carries.

    Ordering comes from `tool_call_sequence` (ALL attempts, in order); query text comes
    from `tool_calls` (executed calls, the only ones that carry inputs). Same two
    sources the census's `features.py` used via its aligned view.
    """
    names = _sequence_names(tool_call_sequence)
    first_mcp = next((i for i, n in enumerate(names) if n in _RETRIEVAL_TOOLS), None)
    first_grep = next((i for i, n in enumerate(names) if n == "Grep"), None)
    first_native = next(
        (i for i, n in enumerate(names) if n in NATIVE_FILE_TOOLS), None)
    queries = [
        str(call.get("input", {}).get("query"))
        for call in (tool_calls or [])
        if isinstance(call, dict)
        and str(call.get("tool")) in _RETRIEVAL_TOOLS
        and isinstance(call.get("input"), dict)
        and call["input"].get("query")
    ]
    mix = {
        "justsearch_search": names.count(SEARCH_TOOL),
        "justsearch_answer": names.count(ANSWER_TOOL),
        "mcp_justsearch_total": sum(
            1 for n in names if n.startswith(_MCP_JUSTSEARCH_PREFIX)),
    }
    for tool in _COUNTED_TOOLS:
        mix[tool] = names.count(tool)
    return {
        "tool_mix": mix,
        "distinct_queries": len({q.strip().lower() for q in queries}),
        "searched_before_grep": bool(
            first_mcp is not None and (first_grep is None or first_mcp < first_grep)),
        "post_search_reads": (
            0 if first_mcp is None
            else sum(1 for i, n in enumerate(names) if n == "Read" and i > first_mcp)),
        "fallback_after_mcp": bool(
            first_mcp is not None and first_native is not None and first_native > first_mcp),
        "grep_fallback_after_mcp": bool(
            first_mcp is not None and first_grep is not None and first_grep > first_mcp),
    }


def behavioral_record(*, answer: str | None, gold: str | None, error: Any,
                      correct: bool,
                      tool_call_sequence: Iterable[dict] | None,
                      tool_calls: Iterable[dict] | None,
                      delivered: dict | None = None,
                      identifier_pattern: str | None = None) -> dict:
    """The per-cell behavioral record (chartered field set, tempdoc 789 Phase 1).

    `delivered` is the cell-time delivered-span half (`name_pivot` / `hop1_stop` /
    entity count) when it exists; for a log recorded without it those three fields
    stay `None` — an honest unknown, never a fabricated `False`.
    """
    wrong_class = classify_answer(
        answer, gold, error=error, identifier_pattern=identifier_pattern, correct=correct)
    record = {
        "schema": SCHEMA,
        "wrong_class": wrong_class,
        "abstained": wrong_class == "abstained",
        "fabricated_specific": wrong_class == "fabricated_specific",
        "format_near_miss": wrong_class == "format_near_miss",
        "gold_in_answer": bool(normalize(gold) and normalize(gold) in normalize(answer)),
        **tool_shape(tool_call_sequence, tool_calls),
        "name_pivot": None,
        "hop1_stop": None,
        "delivered_entity_count": None,
        "entity_source": None,
    }
    if isinstance(delivered, dict):
        record.update({
            "name_pivot": (
                None if delivered.get("name_pivot") is None
                else bool(delivered["name_pivot"])),
            "hop1_stop": (
                None if delivered.get("hop1_stop") is None
                else bool(delivered["hop1_stop"])),
            "delivered_entity_count": delivered.get("delivered_entity_count"),
            "entity_source": delivered.get("entity_source"),
        })
    return record


def delivered_span_record(ordered_calls: Sequence[tuple[dict | None, Any]], *,
                          question: str | None, answer: str | None,
                          gold: str | None) -> dict:
    """Cell-time delivered-span half. `ordered_calls` is the cell's calls in issue
    order as ``(input_payload, result_content)`` pairs.

    A pivot only counts when the entity was delivered BEFORE the probe that names it
    — that ordering is what makes it a continuation of what the tool handed back
    rather than a coincidence of the question's own vocabulary.
    """
    known: list[str] = []
    seen: set[str] = set()
    pivoted = False
    for payload, content in ordered_calls:
        if not pivoted and isinstance(payload, dict):
            probes = [
                str(payload[key]) for key in PROBE_INPUT_KEYS if payload.get(key)
            ]
            if probes and name_pivot(known, probes):
                pivoted = True
        for entity in delivered_entities(content, question):
            if entity not in seen:
                seen.add(entity)
                known.append(entity)
    return {
        "name_pivot": pivoted,
        "hop1_stop": hop1_stop(known, answer, gold),
        "delivered_entity_count": len(known),
        "entity_source": "delivered-span",
    }


_AGGREGATE_BOOLS = (
    "abstained", "fabricated_specific", "format_near_miss", "gold_in_answer",
    "searched_before_grep", "fallback_after_mcp", "grep_fallback_after_mcp",
    "name_pivot", "hop1_stop",
)
_AGGREGATE_MEANS = ("distinct_queries", "post_search_reads")


def _mean(values: list[float]) -> float | None:
    return round(sum(values) / len(values), 4) if values else None


def aggregate_behavioral(observations: Iterable[dict]) -> dict:
    """Per-stratum, per-arm descriptive aggregates for the composed record.

    DESCRIPTIVE ONLY (tempdoc 789 Phase 1 item 4): no gate, verdict, comparability
    rule or digest input reads this block. A tri-state boolean aggregates as
    ``{"true": n, "known": n}`` so an unknown (`None`) cell is never counted as a
    negative.
    """
    buckets: dict[str, dict] = {}
    receipts_cells = 0
    receipts_turns = 0
    for observation in observations:
        record = observation.get("behavioral")
        receipts = observation.get("turn_receipts")
        if isinstance(receipts, list) and receipts:
            receipts_cells += 1
            receipts_turns += len(receipts)
        if not isinstance(record, dict):
            continue
        corpus = ((observation.get("source") or {}).get("corpus")) or {}
        key = f"{corpus.get('dataset')}|{observation.get('condition')}"
        bucket = buckets.setdefault(key, {
            "n_cells": 0,
            "n_correct": 0,
            **{name: {"true": 0, "known": 0} for name in _AGGREGATE_BOOLS},
            **{name: [] for name in _AGGREGATE_MEANS},
            "tool_mix": {},
        })
        bucket["n_cells"] += 1
        bucket["n_correct"] += bool(observation.get("correct"))
        for name in _AGGREGATE_BOOLS:
            value = record.get(name)
            if value is None:
                continue
            bucket[name]["known"] += 1
            bucket[name]["true"] += bool(value)
        for name in _AGGREGATE_MEANS:
            value = record.get(name)
            if isinstance(value, (int, float)) and not isinstance(value, bool):
                bucket[name].append(value)
        for tool, count in (record.get("tool_mix") or {}).items():
            if isinstance(count, int) and not isinstance(count, bool):
                bucket["tool_mix"][tool] = bucket["tool_mix"].get(tool, 0) + count
    if not buckets:
        return {}
    per_stratum_arm = {}
    for key in sorted(buckets):
        bucket = buckets[key]
        per_stratum_arm[key] = {
            "n_cells": bucket["n_cells"],
            "n_correct": bucket["n_correct"],
            **{name: dict(bucket[name]) for name in _AGGREGATE_BOOLS},
            **{f"mean_{name}": _mean(bucket[name]) for name in _AGGREGATE_MEANS},
            "tool_mix": dict(sorted(bucket["tool_mix"].items())),
        }
    return {
        "schema": SCHEMA,
        "descriptive_only": True,
        "note": (
            "tempdoc 789 Phase 1 continuation-survival telemetry. Descriptive only -- "
            "no gate, verdict or comparability rule reads it, and it is excluded from "
            "semantic_digest. Tri-state booleans report {true, known}: `known` < "
            "`n_cells` means the metric was not derivable for those cells (delivered-span "
            "fields are unknown for logs recorded before tempdoc 789)."
        ),
        "per_stratum_arm": per_stratum_arm,
        "turn_receipts": {
            "cells_with_receipts": receipts_cells,
            "turns_observed": receipts_turns,
        },
    }
