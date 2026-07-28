"""Derive the five tempdoc-782 §E.4 artifacts from the completed hero run.

Offline derivation only: reads the per-stratum Inspect EvalLog, the per-stratum
`out/utility-comparison.v1.json`, and nothing else. Writes ONLY into `derived/`.
Nothing paid runs; no existing file is modified.

Run (from the pub-hero worktree):
    PYTHONUTF8=1 PYTHONPATH=<worktree>/scripts/jseval python \
        tmp/782-run-2026-07-28b-hero/derive-e4.py

Field-path provenance (verified against source before use):
  * per-search-call digests   `samples[].metadata.tool_result_digests[i]`
    (producer `agent_utility_inspect.py:1279` `_tool_result_digest_entry`,
     keys minted at `:967-990`: `delivered_tier`, `delivered_fields`,
     `component_bytes`, `ordered_doc_ids`, `scores`, `gold_rank`)
  * ordered attempts          `samples[].metadata.tool_call_sequence[i]`
    (`{name,status}`, producer `agent_utility_inspect.py:1268`) -- SAME ORDER
    and SAME LENGTH as `tool_result_digests` by construction (`:1279-1282`,
    both iterate `attempts`), which this script asserts per cell.
  * executed calls w/ inputs  `samples[].metadata.tool_calls[]` (`{tool,input}`,
    producer `agent_utility_inspect.py:1256`; executed-only == the `status=="ok"`
    subsequence of `tool_call_sequence`, `_call_status` at `:1205-1221`)
  * exhaustion label          `samples[].metadata.error`, classified with
    `jseval.utility_governance.classify_error_kind` markers (`:34`)
    and `jseval.utility_evidence._error_class` (`:47-50`)
  * duration                  `samples[].total_time` / `samples[].working_time`
    (the same SAMPLE-level attributes the composer reads,
     `agent_utility_observations.py:110-111`)
  * cost                      `samples[].metadata.cost_usd` (`:115` there)
  * arm ledger                `comparability.per_arm_loss.<arm>` of
    `out/utility-comparison.v1.json`
"""

from __future__ import annotations

import json
import statistics
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

# `_percentile` is the composer's own percentile (linear interpolation on the
# sorted sample), imported rather than re-implemented so every p90/p95 here is
# computed identically to the numbers in `utility-comparison.v1.json`.
from jseval.utility_comparison import _percentile

RUN_DIR = Path(__file__).resolve().parent
DERIVED = RUN_DIR / "derived"
COMMIT = "bea1ac37"

STRATA = [
    "en-email-enron-raw-1k-verbose",
    "en-email-enron-raw-10k-verbose",
    "en-legal-clerc-1k-verbose",
]

# `agent_utility_inspect.read_digest_entries:1044` sidecar set -- the files under
# `logs/` that are NOT candidate EvalLogs.
SIDECARS = {"eval-set.json", "logs.json", "source-identity.v1.json", "judge-overlay.json"}

SEARCH_TOOL = "mcp__justsearch__justsearch_search"
MCP_PREFIX = "mcp__justsearch"  # `utility_comparison._MCP_JUSTSEARCH_PREFIX` semantics
READ_TOOL = "Read"
STRUCTURED = "structured-json"

# Raw executor error markers, verbatim from `utility_governance.py:34`.
USD_MARKER = "error_max_budget_usd"
WALL_CLOCK_MARKER = "per-cell wall-clock budget exhausted"


def rel(path: Path) -> str:
    return path.resolve().as_posix()


def find_log(stratum: str) -> Path:
    logs = RUN_DIR / stratum / "logs"
    candidates = [p for p in sorted(logs.glob("*.json")) if p.name not in SIDECARS]
    if len(candidates) != 1:
        raise SystemExit(f"{stratum}: expected exactly 1 candidate EvalLog, got {candidates}")
    return candidates[0]


def load_cells(stratum: str) -> tuple[list[dict], Path, Path]:
    """One dict per measured cell, with the per-attempt view already merged."""
    log_path = find_log(stratum)
    record_path = RUN_DIR / stratum / "out" / "utility-comparison.v1.json"
    log = json.loads(log_path.read_text(encoding="utf-8"))
    if log.get("status") != "success":
        raise SystemExit(f"{stratum}: EvalLog status is {log.get('status')!r}, not success")

    cells = []
    for sample in log["samples"]:
        md = sample.get("metadata") or {}
        condition = md.get("condition")
        raw_id = str(sample.get("id"))
        prefix = f"{condition}|"
        qid = raw_id[len(prefix):] if raw_id.startswith(prefix) else raw_id
        seq = md.get("tool_call_sequence") or []
        digests = md.get("tool_result_digests") or []
        executed = md.get("tool_calls") or []
        aligned = len(seq) == len(digests)
        n_ok = sum(1 for e in seq if e.get("status") == "ok")
        executed_aligned = aligned and n_ok == len(executed)

        # Merge: sequence order + digest + (for executed attempts) the call input.
        attempts = []
        it = iter(executed)
        for idx, entry in enumerate(seq):
            digest = digests[idx] if aligned else {}
            call = next(it, None) if (executed_aligned and entry.get("status") == "ok") else None
            attempts.append({
                "name": entry.get("name"),
                "status": entry.get("status"),
                "digest": digest if isinstance(digest, dict) else {},
                "input": (call or {}).get("input"),
            })

        error = md.get("error")
        error_text = None if error is None else str(error)
        if error_text is None:
            error_kind = None
        elif USD_MARKER in error_text:
            error_kind = "usd_budget_exhausted"
        elif WALL_CLOCK_MARKER in error_text:
            error_kind = "wall_clock_budget_exhausted"
        else:
            error_kind = "other"

        cells.append({
            "stratum": stratum,
            "arm": condition,
            "qid": qid,
            "seed": sample.get("epoch"),
            "attempts": attempts,
            "executed": executed,
            "sequence_digest_aligned": aligned,
            "executed_alignment_ok": executed_aligned,
            "error_kind": error_kind,
            "cost_usd": md.get("cost_usd"),
            "usage_truncated": md.get("usage_truncated"),
            "total_time": sample.get("total_time"),
            "working_time": sample.get("working_time"),
            "question_type": md.get("question_type"),
        })
    return cells, log_path, record_path


def stats(values: list[float], pcts=(0.5, 0.9)) -> dict:
    out = {"n": len(values)}
    out["median"] = round(statistics.median(values), 4) if values else None
    for p in pcts:
        key = f"p{int(p * 100)}"
        v = _percentile([float(x) for x in values], p)
        out[key] = round(v, 4) if v is not None else None
    if values:
        out["min"] = round(min(values), 4)
        out["max"] = round(max(values), 4)
        out["mean"] = round(sum(values) / len(values), 4)
    else:
        out["min"] = out["max"] = out["mean"] = None
    return out


def doc_id_availability(by_stratum: dict) -> dict:
    """Run-wide count of B-arm search calls whose delivered ranking carried NO
    usable per-hit id -- the fact several `not_derivable` reasons rest on.
    Computed here so no reason string ever retypes a number."""
    total = 0
    with_hits = 0
    no_id = 0
    no_id_with_hits = 0
    id_bytes_zero_with_hits = 0
    per_stratum = {}
    for stratum, (cells, _, _) in by_stratum.items():
        s_total = s_hits = s_no_id = s_no_id_hits = s_zero_hits = 0
        for cell in cells:
            if cell["arm"] != "B":
                continue
            for att in cell["attempts"]:
                if att["name"] != SEARCH_TOOL:
                    continue
                s_total += 1
                ordered = att["digest"].get("ordered_doc_ids") or []
                has_id = any(x is not None for x in ordered)
                if not has_id:
                    s_no_id += 1
                if not ordered:
                    continue  # a zero-hit delivery has no ranking to carry ids at all
                s_hits += 1
                if not has_id:
                    s_no_id_hits += 1
                hit_bytes = ((att["digest"].get("component_bytes") or {})
                             .get("hit_component_bytes") or {})
                if (hit_bytes.get("id") or 0) == 0:
                    s_zero_hits += 1
        per_stratum[stratum] = {
            "n_search_calls": s_total,
            "n_calls_with_hits": s_hits,
            "n_calls_without_any_nonnull_doc_id": s_no_id,
            "n_calls_with_hits_and_no_nonnull_doc_id": s_no_id_hits,
            "n_calls_with_hits_and_id_bytes_zero": s_zero_hits,
        }
        total += s_total
        with_hits += s_hits
        no_id += s_no_id
        no_id_with_hits += s_no_id_hits
        id_bytes_zero_with_hits += s_zero_hits
    return {
        "n_search_calls": total,
        "n_calls_with_hits": with_hits,
        "n_calls_without_any_nonnull_doc_id": no_id,
        "n_calls_with_hits_and_no_nonnull_doc_id": no_id_with_hits,
        "n_calls_with_hits_and_id_bytes_zero": id_bytes_zero_with_hits,
        "denominator_note": ("a zero-hit delivery carries no ranking, so it is excluded from the "
                             "`with_hits` denominators rather than counted as an id gap"),
        "per_stratum": per_stratum,
    }


def envelope(schema: str, sources: list[str], **body) -> dict:
    doc = {
        "schema": schema,
        "derived_at_commit": COMMIT,
        "derived_at": datetime.now(timezone.utc).isoformat(),
        "derived_by": "tmp/782-run-2026-07-28b-hero/derive-e4.py",
        "protocol": "docs/tempdocs/782-hero-preregistration-protocol.md §E.4",
        "sources": sources,
        "source_selection": (
            "exactly one candidate EvalLog per stratum under `<stratum>/logs/` (Inspect sidecars "
            "eval-set.json / logs.json / source-identity.v1.json / judge-overlay.json excluded, "
            "mirroring agent_utility_inspect.read_digest_entries:1044); every selected log has "
            "status == 'success' with 120 samples. Archived VOID logs are NOT read: "
            "`en-email-enron-raw-1k-verbose/logs-void-backend-death/` (incident ledger w2-s1, "
            "backend killed mid-run)."),
    }
    doc.update(body)
    return doc


# --------------------------------------------------------------------------
# Artifact 1 -- rank-of-gold distribution
# --------------------------------------------------------------------------
def artifact_rank_of_gold(by_stratum: dict, availability: dict) -> dict:
    strata_out = {}
    total_calls = 0
    total_null = 0
    total_nonnull = 0
    for stratum, (cells, _, _) in by_stratum.items():
        per_query = {}
        s_calls = []
        for cell in cells:
            if cell["arm"] != "B":
                continue
            q = per_query.setdefault(cell["qid"], {
                "n_search_calls": 0,
                "n_calls_with_hits": 0,
                "n_calls_with_any_nonnull_doc_id": 0,
                "n_gold_rank_null": 0,
                "gold_ranks": [],
                "question_type": cell.get("question_type"),
            })
            for att in cell["attempts"]:
                if att["name"] != SEARCH_TOOL:
                    continue
                d = att["digest"]
                ordered = d.get("ordered_doc_ids")
                gold = d.get("gold_rank")
                q["n_search_calls"] += 1
                s_calls.append(1)
                if ordered:
                    q["n_calls_with_hits"] += 1
                    if any(x is not None for x in ordered):
                        q["n_calls_with_any_nonnull_doc_id"] += 1
                if gold is None:
                    q["n_gold_rank_null"] += 1
                else:
                    q["gold_ranks"].append(gold)

        for q, v in per_query.items():
            n = v["n_search_calls"]
            v["share_gold_rank_null"] = round(v["n_gold_rank_null"] / n, 4) if n else None
            ranks = v["gold_ranks"]
            if ranks:
                v["median_gold_rank"] = statistics.median(ranks)
                p90 = _percentile([float(r) for r in ranks], 0.9)
                v["p90_gold_rank"] = round(p90, 4) if p90 is not None else None
            else:
                v["median_gold_rank"] = None
                v["p90_gold_rank"] = None
                v["not_derivable"] = {
                    "field": "samples[].metadata.tool_result_digests[i].gold_rank",
                    "reason": (
                        "every B-arm search call for this query captured gold_rank=null: "
                        "the delivered payload carried no per-hit `id`, so "
                        "_gold_rank_capture (agent_utility_inspect.py:921) had no ranking "
                        "identity to match the query's evidence_ids against. Median/p90 "
                        "are left null rather than estimated."),
                }
            total_calls += n
            total_null += v["n_gold_rank_null"]
            total_nonnull += len(ranks)

        all_ranks = [r for v in per_query.values() for r in v["gold_ranks"]]
        n_calls = sum(v["n_search_calls"] for v in per_query.values())
        strata_out[stratum] = {
            "arm": "B",
            "n_queries": len(per_query),
            "n_search_calls": n_calls,
            "n_calls_with_hits": sum(v["n_calls_with_hits"] for v in per_query.values()),
            "n_calls_with_any_nonnull_doc_id": sum(
                v["n_calls_with_any_nonnull_doc_id"] for v in per_query.values()),
            "n_gold_rank_null": sum(v["n_gold_rank_null"] for v in per_query.values()),
            "share_gold_rank_null": (
                round(sum(v["n_gold_rank_null"] for v in per_query.values()) / n_calls, 4)
                if n_calls else None),
            "gold_rank_pooled": stats([float(r) for r in all_ranks]) if all_ranks else None,
            "per_query": dict(sorted(per_query.items(), key=lambda kv: (len(kv[0]), kv[0]))),
        }

    return envelope(
        "rank-of-gold-distribution.v1",
        sorted({rel(p) for (_, p, _) in by_stratum.values()}),
        estimand=("per stratum x query: median/p90 of gold_rank over B-arm "
                  "`mcp__justsearch__justsearch_search` calls, and the share of those "
                  "calls whose gold_rank is null (782 §E.4 item 1)"),
        field_paths={
            "search_call_identification": "samples[].metadata.tool_call_sequence[i].name == "
                                          f"'{SEARCH_TOOL}'",
            "gold_rank": "samples[].metadata.tool_result_digests[i].gold_rank",
            "ordered_doc_ids": "samples[].metadata.tool_result_digests[i].ordered_doc_ids",
            "hit_id_bytes": "samples[].metadata.tool_result_digests[i]."
                            "component_bytes.hit_component_bytes.id",
            "alignment": "tool_call_sequence[i] and tool_result_digests[i] are the same attempt "
                         "by construction (agent_utility_inspect.py:1268 / :1279)",
        },
        null_semantics=("gold_rank null is honest-absent, NEVER a fabricated 0 "
                        "(782 §E.4 line 328-329); nulls are counted, never imputed"),
        totals={
            "n_search_calls": total_calls,
            "n_gold_rank_null": total_null,
            "n_gold_rank_non_null": total_nonnull,
            "share_gold_rank_null": round(total_null / total_calls, 4) if total_calls else None,
        },
        doc_id_availability=availability,
        capture_gap={
            "what": "the ranking identity needed to locate gold was not captured for most calls",
            "evidence": (
                f"of the {availability['n_calls_with_hits']} B-arm search calls that returned at "
                f"least one hit, {availability['n_calls_with_hits_and_no_nonnull_doc_id']} "
                f"carried no non-null entry in `ordered_doc_ids`, and "
                f"`component_bytes.hit_component_bytes.id` is 0 bytes on "
                f"{availability['n_calls_with_hits_and_id_bytes_zero']} of them -- i.e. the "
                f"delivered `results[]` hits had no `id` key for the capture to record "
                f"(run-wide: {availability['n_calls_without_any_nonnull_doc_id']} of "
                f"{availability['n_search_calls']} search calls have no usable id)"),
            "mechanism": (
                "`_gold_rank_capture` (agent_utility_inspect.py:921) reads `h.get('id')` per hit "
                "and matches it against the query's `evidence_ids`; with no `id` key it yields "
                "(ordered_doc_ids=[None...], gold_rank=None). The same digest's "
                "`hit_component_bytes.path` is non-zero on most calls, so the delivery did carry "
                "a document identity under `path` -- but `path` values were never recorded and "
                "raw tool content is not committed, so they cannot be recovered offline."),
            "consequence": ("median/p90 rank-of-gold is not derivable for this run except where "
                            "noted per query; nothing is imputed"),
        },
        strata=strata_out,
    )


# --------------------------------------------------------------------------
# Artifact 2 -- evidence/span carriage
# --------------------------------------------------------------------------
def artifact_span_carriage(by_stratum: dict, availability: dict) -> dict:
    strata_out = {}
    for stratum, (cells, _, _) in by_stratum.items():
        tier_census = Counter()
        tier_by_tool = {}
        tier_by_arm = {}
        b_cells = 0
        cells_any_structured_search = 0
        cells_with_span = 0
        cells_with_span_bytes = 0
        calls_structured = 0
        calls_span_flag = 0
        calls_span_bytes = 0
        for cell in cells:
            for att in cell["attempts"]:
                tier = att["digest"].get("delivered_tier")
                tier_census[str(tier)] += 1
                tool = tier_by_tool.setdefault(att["name"], Counter())
                tool[str(tier)] += 1
                tier_by_arm.setdefault(cell["arm"], Counter())[str(tier)] += 1
            if cell["arm"] != "B":
                continue
            b_cells += 1
            any_struct = False
            any_span = False
            any_span_bytes = False
            for att in cell["attempts"]:
                if att["name"] != SEARCH_TOOL:
                    continue
                d = att["digest"]
                if d.get("delivered_tier") != STRUCTURED:
                    continue
                any_struct = True
                calls_structured += 1
                fields = d.get("delivered_fields") or {}
                if fields.get("excerpts"):
                    calls_span_flag += 1
                    any_span = True
                hit_bytes = (d.get("component_bytes") or {}).get("hit_component_bytes") or {}
                if (hit_bytes.get("excerpts") or 0) > 0:
                    calls_span_bytes += 1
                    any_span_bytes = True
            cells_any_structured_search += 1 if any_struct else 0
            cells_with_span += 1 if any_span else 0
            cells_with_span_bytes += 1 if any_span_bytes else 0

        strata_out[stratum] = {
            "b_cells": {
                "n_attempted": b_cells,
                "n_with_structured_search_delivery": cells_any_structured_search,
                "n_carrying_span": cells_with_span,
                "share_carrying_span_of_attempted": (
                    round(cells_with_span / b_cells, 4) if b_cells else None),
                "share_carrying_span_of_cells_with_structured_delivery": (
                    round(cells_with_span / cells_any_structured_search, 4)
                    if cells_any_structured_search else None),
                "n_carrying_span_bytes_gt_zero": cells_with_span_bytes,
            },
            "search_calls": {
                "n_structured_deliveries": calls_structured,
                "n_with_excerpts_flag": calls_span_flag,
                "share_with_excerpts_flag": (
                    round(calls_span_flag / calls_structured, 4) if calls_structured else None),
                "n_with_excerpt_bytes_gt_zero": calls_span_bytes,
                "share_with_excerpt_bytes_gt_zero": (
                    round(calls_span_bytes / calls_structured, 4) if calls_structured else None),
            },
            "delivered_tier_census": dict(sorted(tier_census.items())),
            "delivered_tier_census_scope": (
                "every attempt in every cell of BOTH arms (one digest per attempt); 'None' is a "
                "call with nothing to classify, e.g. a blocked attempt that returned no content"),
            "delivered_tier_census_by_arm": {
                arm: dict(sorted(counts.items())) for arm, counts in sorted(tier_by_arm.items())
            },
            "delivered_tier_census_by_tool": {
                tool: dict(sorted(counts.items())) for tool, counts in sorted(tier_by_tool.items())
            },
        }

    return envelope(
        "evidence-span-carriage.v1",
        sorted({rel(p) for (_, p, _) in by_stratum.values()}),
        estimand=("share of B cells whose delivered payload carried the answer-bearing span "
                  "(post-775 flip), plus the delivered_tier census (782 §E.4 item 2)"),
        field_paths={
            "delivered_tier": "samples[].metadata.tool_result_digests[i].delivered_tier",
            "span_presence_flag": "samples[].metadata.tool_result_digests[i]."
                                  "delivered_fields.excerpts",
            "span_bytes": "samples[].metadata.tool_result_digests[i]."
                          "component_bytes.hit_component_bytes.excerpts",
        },
        operationalization=(
            "`excerpts` IS the answer-bearing span surface post-775: `delivered_fields.excerpts` "
            "is True when a `results[]` hit carried an `excerpts` key "
            "(agent_utility_inspect.py:775-784), and the byte column is the same span's "
            "serialized cost (`_HIT_COMPONENT_KEYS`, :793). Carriage is therefore measured as "
            "SPAN-PRESENT-IN-DELIVERY, at both the per-call and the per-cell (any-call) level."),
        not_derivable=[{
            "field": "gold-conditioned span carriage (did the span belong to the "
                     "ANSWER-BEARING document, not merely to some delivered hit)",
            "reason": (
                "requires joining `delivered_fields.excerpts` to the gold document's rank, but "
                f"`ordered_doc_ids` carried no non-null id on "
                f"{availability['n_calls_without_any_nonnull_doc_id']} of "
                f"{availability['n_search_calls']} B-arm search calls "
                "(see rank-of-gold-distribution.v1 `capture_gap`) -- the delivered payload "
                "carried no per-hit `id`. Raw tool content is never committed "
                "(782 §E.4: digests only), so the excerpt text cannot be re-checked "
                "offline against the gold answer either. Reported as absent, not estimated."),
        }],
        strata=strata_out,
    )


# --------------------------------------------------------------------------
# Artifact 3 -- read-amplification after-measure (DESCRIPTIVE ONLY)
# --------------------------------------------------------------------------
def artifact_read_amplification(by_stratum: dict, availability: dict) -> dict:
    strata_out = {}
    for stratum, (cells, _, _) in by_stratum.items():
        per_cell = []
        pooled_mcp_calls = 0
        pooled_calls = 0
        for cell in cells:
            if cell["arm"] != "B":
                continue
            attempts = cell["attempts"]
            first_search = next(
                (i for i, a in enumerate(attempts) if a["name"] == SEARCH_TOOL), None)
            reads_total = sum(1 for a in attempts if a["name"] == READ_TOOL)
            reads_after = (
                sum(1 for a in attempts[first_search + 1:] if a["name"] == READ_TOOL)
                if first_search is not None else 0)
            executed = cell["executed"]
            n_mcp = sum(1 for c in executed if str(c.get("tool", "")).startswith(MCP_PREFIX))
            pooled_mcp_calls += n_mcp
            pooled_calls += len(executed)
            per_cell.append({
                "qid": cell["qid"],
                "seed": cell["seed"],
                "first_search_attempt_index": first_search,
                "n_read_calls_total": reads_total,
                "n_read_calls_after_first_search": reads_after,
                "n_executed_tool_calls": len(executed),
                "n_executed_mcp_calls": n_mcp,
                "mcp_call_share": round(n_mcp / len(executed), 4) if executed else None,
            })
        reads_after_values = [float(c["n_read_calls_after_first_search"]) for c in per_cell]
        shares = [c["mcp_call_share"] for c in per_cell if c["mcp_call_share"] is not None]
        strata_out[stratum] = {
            "n_b_cells": len(per_cell),
            "n_b_cells_with_a_search_call": sum(
                1 for c in per_cell if c["first_search_attempt_index"] is not None),
            "reads_after_first_search": {
                "total": int(sum(reads_after_values)),
                **stats(reads_after_values),
            },
            "reads_total_all_positions": sum(c["n_read_calls_total"] for c in per_cell),
            "mcp_call_share_pooled": (
                round(pooled_mcp_calls / pooled_calls, 4) if pooled_calls else None),
            "mcp_call_share_per_cell": stats([float(s) for s in shares]),
            "pooled_counts": {"mcp_calls": pooled_mcp_calls, "tool_calls": pooled_calls},
            "per_cell": per_cell,
        }

    return envelope(
        "read-amplification.v1",
        sorted({rel(p) for (_, p, _) in by_stratum.values()}),
        descriptive_only=True,
        promotion_ban=(
            "This after-measure is pre-registered as DESCRIPTIVE ONLY -- it is not a comparison, "
            "not a claim, and no delta against the pre-flip reference may be promoted."),
        preregistration_text_verbatim=(
            "Read-amplification after-measure (771 item 3). From `tool_call_sequence`, per B "
            "cell: (a) count of filesystem `Read` calls occurring after the first "
            "`justsearch_search` call; (b) the share of those Reads whose target is not in any "
            "preceding `ordered_doc_ids`; (c) `mcp_call_share`. Pre-flip reference (765 §E / "
            "770 §A.2, different cohort, model and corpora): `mcp_call_share` ~ 0.5, and 50.3% "
            "of post-search Reads (N=862) targeted documents search never returned. This "
            "after-measure is pre-registered as DESCRIPTIVE ONLY -- it is not a comparison, not "
            "a claim, and no delta against the pre-flip reference may be promoted. Naming this "
            "before launch is what stops \"we cut re-reads by X%\" from appearing afterwards."),
        forbidden_headline_reminder=(
            "782 §E.5 forbidden headlines: 'any read-amplification delta (§E.4 item 3 is "
            "descriptive only)'. No delta is computed in this artifact."),
        field_paths={
            "attempt_sequence": "samples[].metadata.tool_call_sequence[i].name",
            "executed_calls": "samples[].metadata.tool_calls[] ({tool, input})",
            "mcp_call_share": "count(tool_calls[].tool startswith 'mcp__justsearch') / "
                              "count(tool_calls), the utility_comparison._adoption_metrics "
                              "definition (utility_comparison.py:769-790)",
        },
        not_derivable=[{
            "field": "item (b) -- share of post-search Reads whose target is NOT in any "
                     "preceding `ordered_doc_ids`",
            "reason": (
                f"`ordered_doc_ids` carried no non-null id on "
                f"{availability['n_calls_without_any_nonnull_doc_id']} of "
                f"{availability['n_search_calls']} B-arm search calls run-wide "
                f"({availability['n_calls_with_hits_and_id_bytes_zero']} of the "
                f"{availability['n_calls_with_hits']} calls that returned hits had "
                f"`component_bytes.hit_component_bytes.id` == 0), so 'the set of "
                "documents search returned' has no offline representation to test a Read target "
                "against. Raw tool content is never committed (digests only), so the delivered "
                "ids cannot be recovered from the logs. Counted, not estimated."),
        }],
        strata=strata_out,
    )


# --------------------------------------------------------------------------
# Artifact 4 -- exhaustion ledger
# --------------------------------------------------------------------------
def artifact_exhaustion(by_stratum: dict) -> dict:
    strata_out = {}
    sources = []
    for stratum, (cells, log_path, record_path) in by_stratum.items():
        sources += [rel(log_path), rel(record_path)]
        record = json.loads(record_path.read_text(encoding="utf-8"))
        per_arm_loss = record["comparability"]["per_arm_loss"]
        arms = {}
        for arm in sorted({c["arm"] for c in cells}):
            arm_cells = [c for c in cells if c["arm"] == arm]
            kinds = Counter(c["error_kind"] for c in arm_cells if c["error_kind"])
            null_cost = [c for c in arm_cells if c["cost_usd"] is None]
            usd = kinds.get("usd_budget_exhausted", 0)
            wall = kinds.get("wall_clock_budget_exhausted", 0)
            other = kinds.get("other", 0)
            ledger = dict(per_arm_loss.get(arm) or {})
            arms[arm] = {
                "per_arm_loss": ledger,
                "taxonomy_split": {
                    "usd_exhausted_receipts_retained": usd,
                    "wall_clock_cancelled_cost_null_by_design": wall,
                    "other_error_missing_data": other,
                },
                "null_cost_cells": {
                    "n": len(null_cost),
                    "cells": [{"qid": c["qid"], "seed": c["seed"], "error_kind": c["error_kind"]}
                              for c in null_cost],
                    "policy": "segmented, never imputed (757 §D.2) -- a null cost is reported "
                              "as null and counted here, never estimated",
                },
                "cost_usd_receipts": {
                    "n_cells_with_cost": sum(1 for c in arm_cells if c["cost_usd"] is not None),
                    "n_exhausted_cells_with_cost": sum(
                        1 for c in arm_cells
                        if c["error_kind"] in ("usd_budget_exhausted",
                                               "wall_clock_budget_exhausted")
                        and c["cost_usd"] is not None),
                },
                "cross_check": {
                    "log_derived_exhausted": usd + wall,
                    "record_n_exhausted": ledger.get("n_exhausted"),
                    "agrees": (usd + wall) == ledger.get("n_exhausted"),
                },
            }
        strata_out[stratum] = arms

    return envelope(
        "exhaustion-ledger.v1",
        sorted(set(sources)),
        estimand=("per stratum x arm: n_attempted / n_completed / n_exhausted / n_excluded / "
                  "n_pending and per_arm_loss, plus the 765 §E taxonomy split -- USD-exhausted "
                  "(receipts retained) vs wall-clock-cancelled (cost null by design). Null costs "
                  "are segmented, never imputed (757 §D.2). 782 §E.4 item 4."),
        field_paths={
            "per_arm_loss": "out/utility-comparison.v1.json :: comparability.per_arm_loss.<arm>",
            "error_text": "samples[].metadata.error",
            "usd_marker": f"substring {USD_MARKER!r} (utility_governance.py:34)",
            "wall_clock_marker": f"substring {WALL_CLOCK_MARKER!r} (utility_governance.py:34)",
            "cost": "samples[].metadata.cost_usd",
        },
        classification_rule=("fail-closed, mirroring utility_governance.classify_error_kind: only "
                             "the two known exhaustion markers classify as exhaustion; every "
                             "other non-empty error is `other` (missing data)"),
        strata=strata_out,
    )


# --------------------------------------------------------------------------
# Artifact 5 -- duration ledger
# --------------------------------------------------------------------------
def artifact_duration(by_stratum: dict) -> dict:
    strata_out = {}
    sources = []
    for stratum, (cells, log_path, record_path) in by_stratum.items():
        sources += [rel(log_path), rel(record_path)]
        record = json.loads(record_path.read_text(encoding="utf-8"))
        eval_limits = record["cohort"].get("eval_limits")
        arms = {}
        for arm in sorted({c["arm"] for c in cells}):
            arm_cells = [c for c in cells if c["arm"] == arm]
            censored = [c for c in arm_cells
                        if c["error_kind"] in ("usd_budget_exhausted",
                                               "wall_clock_budget_exhausted")]
            censored_ids = {id(c) for c in censored}
            uncensored = [c for c in arm_cells if id(c) not in censored_ids]

            def series(pool, key):
                return [float(c[key]) for c in pool if c[key] is not None]

            arms[arm] = {
                "n_cells": len(arm_cells),
                "all_attempted": {
                    "total_time_s": stats(series(arm_cells, "total_time"), (0.5, 0.9, 0.95)),
                    "working_time_s": stats(series(arm_cells, "working_time"), (0.5, 0.9, 0.95)),
                },
                "uncensored_only": {
                    "n": len(uncensored),
                    "total_time_s": stats(series(uncensored, "total_time"), (0.5, 0.9, 0.95)),
                    "working_time_s": stats(series(uncensored, "working_time"), (0.5, 0.9, 0.95)),
                },
                "censoring": {
                    "censored": bool(censored),
                    "n_censored_cells": len(censored),
                    "share_censored": (
                        round(len(censored) / len(arm_cells), 4) if arm_cells else None),
                    "mechanism": "right-censored at the per-cell budget cap: a cell stopped by a "
                                 "limit contributes the time it had spent when killed, not its "
                                 "would-be completion time (agent_utility_observations.py:94)",
                    "censored_kinds": dict(Counter(c["error_kind"] for c in censored)),
                    "interpretation": "medians/p95 over `all_attempted` are lower bounds on the "
                                      "uncensored distribution; the `uncensored_only` block is "
                                      "conditional on completion and is NOT an efficiency claim",
                },
            }
        strata_out[stratum] = {
            "arms": arms,
            "per_cell_cap": {
                "cohort_eval_limits": eval_limits,
                "note": ("`cohort.eval_limits` is empty in the composed record, so the numeric "
                         "per-cell cap is not machine-readable from these sources; it is NOT "
                         "imputed here"),
            },
        }

    return envelope(
        "duration-ledger.v1",
        sorted(set(sources)),
        estimand=("per stratum x arm: wall-clock duration stats with an explicit censoring flag; "
                  "cells stopped by limits are censored observations (782 §E.4 item 5)"),
        field_paths={
            "total_time": "samples[].total_time (full wall clock, seconds)",
            "working_time": "samples[].working_time (excludes retry/rate-limit waits, seconds)",
            "censoring_label": "samples[].metadata.error classified as in exhaustion-ledger.v1",
        },
        fail_closed_note=("782 §E.4 item 5: efficiency intervals fail closed wherever with-tool "
                          "truncation exists -- a pre-registered accepted outcome, not a defect. "
                          "This ledger reports durations and censoring only; it computes no "
                          "efficiency interval and no arm delta."),
        percentile_method="jseval.utility_comparison._percentile (linear interpolation, "
                          "same helper the composed record uses)",
        strata=strata_out,
    )


def main() -> int:
    DERIVED.mkdir(parents=True, exist_ok=True)
    by_stratum = {}
    for stratum in STRATA:
        cells, log_path, record_path = load_cells(stratum)
        bad = [c for c in cells if not c["sequence_digest_aligned"] or not c["executed_alignment_ok"]]
        if bad:
            raise SystemExit(f"{stratum}: {len(bad)} cells failed the attempt-alignment assertion")
        by_stratum[stratum] = (cells, log_path, record_path)
        print(f"{stratum}: {len(cells)} cells from {log_path.name}", file=sys.stderr)

    availability = doc_id_availability(by_stratum)
    artifacts = {
        "rank-of-gold-distribution.v1.json": artifact_rank_of_gold(by_stratum, availability),
        "evidence-span-carriage.v1.json": artifact_span_carriage(by_stratum, availability),
        "read-amplification.v1.json": artifact_read_amplification(by_stratum, availability),
        "exhaustion-ledger.v1.json": artifact_exhaustion(by_stratum),
        "duration-ledger.v1.json": artifact_duration(by_stratum),
    }
    for name, doc in artifacts.items():
        path = DERIVED / name
        path.write_text(json.dumps(doc, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"wrote {path} ({path.stat().st_size} bytes)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
