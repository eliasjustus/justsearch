"""Read-only post-hoc report over an agent-utility matrix run's Inspect eval logs
(tempdoc 624/655 scale-corpus work).

Purpose: after `jseval utility-run` (or an `agent_utility_inspect` Inspect eval_set)
finishes writing EvalLog files, print a per-(condition, agent_model) arm report with
concrete numbers so the orchestrator can state the (a) discoverability-defect vs
(b) rational-non-adoption verdict, WITHOUT re-running anything. Pure analysis over
already-written logs.

Reuses existing production code rather than re-deriving it:
  - `jseval.agent_utility_observations.read_inspect_observations` (the same
    fail-closed, all-attempt reader used by evidence export and finalization).
  - `jseval.utility_comparison._pair_observations` / `_stats_from_pairs` (the exact
    McNemar + bootstrap-CI cost/token/turn stat path `compose_utility` uses for its
    A-vs-with-tool comparison) -- NOT re-implemented here.
  - `jseval.utility_comparison._adoption_metrics` / `_tool_surfacing_mode` (the
    pre-registered adoption metrics, tempdoc 624 SS M.8 amendment Step 0 item 5).

The all-attempt seam retains `attempts`, `first_error`, and excluded cells, so this
report no longer maintains a second permissive log reader.

Usage (from `scripts/jseval/`):
    python scale_matrix_report.py --log-dir 624-run-2026-07-07-pilot/logs
    python scale_matrix_report.py --log-dir logs-en --log-dir logs-de --json
    python scale_matrix_report.py some-parent-dir   # auto-discovers leaf log dirs
"""

from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from pathlib import Path

from jseval.agent_utility_observations import (
    read_inspect_observations,
    successful_summaries,
)
from jseval.utility_comparison import (
    _adoption_metrics,
    _distribution,
    _pair_observations,
    _stats_from_pairs,
    _tool_surfacing_mode,
)

_WITH_TOOL = {"B", "C"}
_BASELINE = "A"
_MCP_JS_PREFIX = "mcp__justsearch"
_NULL_ADOPTION = {"adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None}


# --- discovery: --log-dir may be a leaf (contains *.eval/*.json directly) or a
# parent whose descendants contain one or more leaf log dirs. --------------------

def _find_log_dirs(root: Path) -> list[Path]:
    """Recursively (rglob covers depth 0 too, so `root` itself is checked as well)
    find every directory containing `*.eval`/`*.json` files that could be Inspect
    EvalLogs. Deliberately does NOT fast-path on "root has *.json directly" -- a
    run dir like `<run>/calibration.json` + `<run>/logs/*.json` has stray JSON at
    the root that is NOT an EvalLog; `eval_logs_to_summaries` already skips
    unparseable files per-directory, so over-including candidate dirs here is
    harmless (they just contribute zero summaries), while under-including (the
    prior single-level fast-path) silently missed the real `logs/` subdirectory."""
    root = Path(root)
    found: dict[Path, None] = {}
    for pattern in ("*.eval", "*.json"):
        for p in root.rglob(pattern):
            if p.name == "logs.json":
                continue
            found[p.parent] = None
    return sorted(found.keys())


def load_summaries(log_dir_args: list[str]) -> tuple[list[dict], list[str], list[dict]]:
    """:returns: (successful summaries, resolved log dirs, all observations)."""
    summaries: list[dict] = []
    observations: list[dict] = []
    resolved: list[str] = []
    for d in log_dir_args:
        leaves = _find_log_dirs(Path(d))
        if not leaves:
            print(f"WARNING: no *.eval/*.json log files found under {d!r} (skipped)")
            continue
        for leaf in leaves:
            resolved.append(str(leaf))
            leaf_observations = read_inspect_observations(
                str(leaf), require_complete=False)
            observed_hashes = {
                row.get("observed_mcp_tool_surface_hash")
                for row in leaf_observations
                if row.get("observed_mcp_tool_surface_hash")
            }
            if len(observed_hashes) > 1:
                raise ValueError(
                    f"mixed MCP tool surfaces in {leaf}: {sorted(observed_hashes)!r}")
            observations.extend(leaf_observations)
            summaries.extend(successful_summaries(
                leaf_observations,
                observed_mcp_tool_surface_hash=next(iter(observed_hashes), None),
            ))
    return summaries, resolved, observations


def _raw_rows(observations: list[dict]) -> list[dict]:
    """Project retry and exclusion facts retained by the shared observation seam."""
    return [{
        "condition": row.get("condition"),
        "model": (row.get("source") or {}).get("model_alias"),
        "seed": row.get("seed"),
        "qid": row.get("qid"),
        "attempts": row.get("attempts"),
        "first_error": row.get("first_error"),
        "error": row.get("error"),
        "excluded": bool(row.get("excluded")),
        "num_turns": row.get("num_turns"),
        "cost_usd": row.get("cost_usd"),
    } for row in observations]


def _is_timeout_text(row: dict) -> bool:
    """`subprocess.run(..., timeout=timeout_s)` raising `TimeoutExpired` is captured
    verbatim as ``f"{type(e).__name__}: ..."`` into `first_error`/`error`
    (agent_utility_inspect.py's `except Exception as e` branch) -- a code-verified,
    reliable signal for "this cell's process was killed by the harness's wall-clock
    timeout", which is NOT the same thing as "hit --max-budget-usd" or a turn cap
    (see `_raw_rows` and the `budget_signal.note` this feeds)."""
    text = " ".join(str(x) for x in (row.get("first_error"), row.get("error")) if x)
    return "TimeoutExpired" in text


# --- tri-state helpers: None must never silently read as zero. ------------------

def _frac(numer: int, denom: int) -> float | None:
    if denom == 0:
        return None
    return round(numer / denom, 4)


def _median_mean(values: list) -> dict:
    vals = [float(v) for v in values if v is not None]
    if not vals:
        return {"n": 0, "median": None, "mean": None}
    return {
        "n": len(vals),
        "median": round(statistics.median(vals), 6),
        "mean": round(statistics.mean(vals), 6),
    }


def _mcp_tool_names(tool_calls: list[dict] | None) -> set[str]:
    return {
        str(tc.get("tool", ""))
        for tc in (tool_calls or [])
        if str(tc.get("tool", "")).startswith(_MCP_JS_PREFIX)
    }


# --- per-arm (single condition, single model), unpaired stats -------------------

def arm_cells(summaries_for_arm: list[dict]) -> list[dict]:
    cells: list[dict] = []
    for s in summaries_for_arm:
        cells.extend((s.get("per_query") or {}).values())
    return cells


def arm_alone_stats(cells: list[dict], condition: str) -> dict:
    """Everything computable from `per_query` cells alone (no baseline pairing
    required): n / accuracy, cost/token/turn distributions, adoption, tool-surface
    verification, and the NEW behavioral signals (5a/5c/5d) this report adds on top
    of what `utility_comparison.compose_utility` already reports."""
    n = len(cells)
    correct_n = sum(1 for c in cells if c.get("correct"))
    accuracy = _frac(correct_n, n)

    tool_calls_list = [c.get("tool_calls") for c in cells]
    checked_tc = [tc for tc in tool_calls_list if tc is not None]  # "no data" excluded, not zeroed

    costs = [c.get("cost_usd") for c in cells]
    tokens = [c.get("unique_tokens") for c in cells]
    turns = [c.get("num_turns") for c in cells]

    adoption = _adoption_metrics(tool_calls_list) if condition in _WITH_TOOL else dict(_NULL_ADOPTION)

    offered_vals = [c.get("mcp_tools_offered") for c in cells]
    n_offered_known = sum(1 for v in offered_vals if v is not None)
    n_offered_gt0 = sum(1 for v in offered_vals if v is not None and v > 0)
    n_unverified = sum(1 for c in cells if bool(c.get("mcp_surface_unverified")))
    # "invalid cell" flag (task item 3): a with-tool cell that was never
    # surface-verified as actually offering mcp__justsearch tools.
    invalid_unverified_with_tool = n_unverified if condition in _WITH_TOOL else 0

    deferred_vals = [c.get("mcp_tools_deferred") for c in cells]
    deferred = {
        "true": sum(1 for v in deferred_vals if v is True),
        "false": sum(1 for v in deferred_vals if v is False),
        "none": sum(1 for v in deferred_vals if v is None),
    }

    # 5a: justsearch_answer usage vs search-only usage. Tool-name heuristic
    # (startswith "mcp__justsearch" + substring match on "answer"/"search" in the
    # lowercased name) rather than one hardcoded literal name: the production tool
    # surface has used different literal names across tempdocs (observed in this
    # repo: "answer_question"/"search_files" in the current McpToolSurface.java,
    # "justsearch_answer"/"justsearch_search" and "search_query" in older
    # fixtures/tests) -- see `all_mcp_tool_names` in the returned dict to sanity-check
    # this heuristic against what was ACTUALLY called in this run.
    all_mcp_tool_names: set[str] = set()
    cells_with_answer_call = 0
    cells_with_search_only_call = 0
    cells_with_any_mcp_call = 0
    for tc in checked_tc:
        names = _mcp_tool_names(tc)
        all_mcp_tool_names |= names
        lname = {n.lower() for n in names}
        has_answer = any("answer" in n for n in lname)
        has_search = any("search" in n for n in lname)
        if names:
            cells_with_any_mcp_call += 1
        if has_answer:
            cells_with_answer_call += 1
        if has_search and not has_answer:
            cells_with_search_only_call += 1

    # 5c: tool-call error rate (call-level and cell-level).
    total_calls = sum(len(tc) for tc in checked_tc)
    total_error_calls = sum(1 for tc in checked_tc for call in tc if call.get("is_error"))
    cells_with_any_tool_error = sum(1 for tc in checked_tc if any(call.get("is_error") for call in tc))

    # 5d: disallowed / leak-suspect cell counts (from existing per-query fields).
    disallowed_cells = sum(1 for c in cells if c.get("disallowed_tool_calls"))
    leak_suspect_cells = sum(1 for c in cells if c.get("leak_suspect"))

    return {
        "condition": condition,
        "n_cells": n,
        "n_correct": correct_n,
        "accuracy": accuracy,
        "cost_usd": _median_mean(costs),
        "unique_tokens": _median_mean(tokens),
        "num_turns": _median_mean(turns),
        "adoption": adoption,
        "surface": {
            "n_cells_offered_known": n_offered_known,
            "n_cells_offered_gt0": n_offered_gt0,
            "n_cells_surface_unverified": n_unverified,
            "n_invalid_unverified_with_tool_cells": invalid_unverified_with_tool,
            "mcp_tools_deferred": deferred,
        },
        "answer_vs_search": {
            "n_cells_with_tool_data": len(checked_tc),
            "n_cells_with_any_mcp_call": cells_with_any_mcp_call,
            "n_cells_with_answer_call": cells_with_answer_call,
            "answer_call_rate": _frac(cells_with_answer_call, len(checked_tc)),
            "n_cells_search_only_no_answer": cells_with_search_only_call,
            "search_only_rate": _frac(cells_with_search_only_call, len(checked_tc)),
            "all_mcp_tool_names_observed": sorted(all_mcp_tool_names),
        },
        "tool_error": {
            "total_tool_calls": total_calls,
            "total_error_calls": total_error_calls,
            "call_error_rate": _frac(total_error_calls, total_calls),
            "n_cells_with_any_tool_error": cells_with_any_tool_error,
            "cell_error_rate": _frac(cells_with_any_tool_error, len(checked_tc)),
        },
        "disallowed_and_leak": {
            "n_disallowed_cells": disallowed_cells,
            "n_leak_suspect_cells": leak_suspect_cells,
        },
    }


def budget_signal_for_arm(rows: list[dict]) -> dict:
    """Item 5b. See `_raw_scan` / `_is_timeout_text` docstrings for what's reliable.

    NOT computed: whether a SUCCESSFUL cell was truncated early by `--max-budget-usd`
    or a turn cap. `agent_utility_inspect.claude_agent_solver` never parses the
    `claude` CLI result event's `subtype` (only `total_cost_usd`/`usage`/`num_turns`
    are stashed on success), so a cell that finished under budget/turns is
    indistinguishable, in this harness's captured fields, from one that was cut off
    early while still returning a usable answer. Do not infer a budget-hit rate from
    `cost_usd` proximity to a configured `--max-budget-usd` -- that value is not even
    recorded in the cohort/manifest (`eval_limits` is empty in observed records).
    """
    n_seen = len(rows)
    n_excluded = sum(1 for r in rows if r["excluded"])
    attempts_known = [r["attempts"] for r in rows if r["attempts"] is not None]
    retry_n = sum(1 for a in attempts_known if a and a > 1)
    timeout_excl = sum(1 for r in rows if r["excluded"] and _is_timeout_text(r))
    other_excl = n_excluded - timeout_excl
    return {
        "n_samples_seen": n_seen,
        "n_excluded": n_excluded,
        "exclusion_rate": _frac(n_excluded, n_seen),
        "n_attempts_known": len(attempts_known),
        "retry_rate": _frac(retry_n, len(attempts_known)),
        "n_timeout_exclusions": timeout_excl,
        "n_other_exclusions": other_excl,
        "budget_or_turn_cap_hit_rate": None,
        "note": (
            "budget/turn-cap-hit rate is NOT reliably sourced from this harness -- "
            "agent_utility_inspect.claude_agent_solver never captures the claude CLI "
            "result event's `subtype`, so a cell truncated early by --max-budget-usd "
            "or a turn limit cannot be distinguished from a natural finish. "
            "retry_rate (>1 attempts needed) and n_timeout_exclusions (a captured "
            "subprocess.TimeoutExpired) are the closest available proxies -- they "
            "measure TRANSIENT infra retries and wall-clock process timeouts, "
            "NOT a budget or turn-limit stop."
        ),
    }


# --- paired (A vs with-tool) stats: reuses the composer's exact stat path --------

def paired_stats(baseline_summaries: list[dict], with_tool_summaries: list[dict]) -> dict | None:
    if not baseline_summaries or not with_tool_summaries:
        return None
    pairs, leak_suspect_cells = _pair_observations(baseline_summaries, with_tool_summaries)
    stats = _stats_from_pairs(pairs)
    if stats is None:
        return None
    stats["leak_suspect_cells"] = leak_suspect_cells
    return stats


# --- report assembly -------------------------------------------------------------

def build_report(summaries: list[dict], raw_rows: list[dict]) -> dict:
    by_arm: dict[tuple, list[dict]] = defaultdict(list)
    for s in summaries:
        by_arm[(s.get("condition"), s.get("agent_model"))].append(s)

    models = sorted({m for (_c, m) in by_arm if m is not None}, key=str)

    arms: dict[str, dict] = {}
    for (cond, model), sums in by_arm.items():
        if cond is None or model is None:
            continue
        key = f"{model}|{cond}"
        cells = arm_cells(sums)
        stats = arm_alone_stats(cells, cond)
        stats["agent_model"] = model
        stats["n_summaries_seed_blocks"] = len(sums)
        stats["seeds"] = sorted(
            {s["manifest"].get("seed") for s in sums}, key=lambda x: (x is None, str(x)),
        )
        arms[key] = stats

    raw_by_arm: dict[tuple, list[dict]] = defaultdict(list)
    for r in raw_rows:
        raw_by_arm[(r["condition"], r["model"])].append(r)
    for (cond, model), rows in raw_by_arm.items():
        if cond is None or model is None:
            continue
        key = f"{model}|{cond}"
        signal = budget_signal_for_arm(rows)
        if key in arms:
            arms[key]["budget_signal"] = signal
        else:
            # Every raw sample was excluded for this arm (no per_query cells at all).
            arms[key] = {"agent_model": model, "condition": cond, "n_cells": 0,
                         "budget_signal": signal, "note": "all cells excluded; only raw-scan data available"}

    pairs: dict[str, dict] = {}
    for model in models:
        baseline_sums = by_arm.get((_BASELINE, model), [])
        for wt in ("B", "C"):
            wt_sums = by_arm.get((wt, model), [])
            stats = paired_stats(baseline_sums, wt_sums)
            if stats is not None:
                pairs[f"{model}|A_vs_{wt}"] = stats

    surfacing_modes: dict[str, str | None] = {}
    for model in models:
        for wt in ("B", "C"):
            wt_sums = by_arm.get((wt, model), [])
            if wt_sums:
                surfacing_modes[f"{model}|{wt}"] = _tool_surfacing_mode(wt_sums)

    return {"arms": arms, "pairs": pairs, "surfacing_modes": surfacing_modes}


# --- printing ----------------------------------------------------------------

def _fmt(v, pct=False) -> str:
    if v is None:
        return "n/a"
    if pct:
        return f"{v * 100:.1f}%"
    return str(v)


def print_report(report: dict, resolved_log_dirs: list[str]) -> None:
    print("=" * 88)
    print("SCALE MATRIX REPORT (read-only, post-hoc over Inspect eval logs)")
    print("=" * 88)
    print(f"log dirs scanned ({len(resolved_log_dirs)}):")
    for d in resolved_log_dirs:
        print(f"  - {d}")
    print()

    arms = report["arms"]
    if not arms:
        print("No (condition, agent_model) arms found in the scanned log dirs.")
        return

    for key in sorted(arms):
        a = arms[key]
        cond = a.get("condition", key.split("|")[-1])
        model = a.get("agent_model", key.split("|")[0])
        print("-" * 88)
        print(f"ARM: model={model}  condition={cond}")
        print("-" * 88)
        print(f"  n_cells={a.get('n_cells')}  n_correct={a.get('n_correct')}  "
              f"accuracy={_fmt(a.get('accuracy'), pct=True)}")
        if "cost_usd" in a:
            c, t, n = a["cost_usd"], a["unique_tokens"], a["num_turns"]
            print(f"  cost_usd: median={_fmt(c.get('median'))} mean={_fmt(c.get('mean'))} (n={c.get('n')})")
            print(f"  unique_tokens: median={_fmt(t.get('median'))} mean={_fmt(t.get('mean'))} (n={t.get('n')})")
            print(f"  num_turns: median={_fmt(n.get('median'))} mean={_fmt(n.get('mean'))} (n={n.get('n')})")
        if "adoption" in a:
            ad = a["adoption"]
            print(f"  adoption: rate={_fmt(ad.get('adoption_rate'), pct=True)} "
                  f"first_mcp_call_index(median)={_fmt(ad.get('first_mcp_call_index'))} "
                  f"mcp_call_share={_fmt(ad.get('mcp_call_share'), pct=True)}")
        if "surface" in a:
            sf = a["surface"]
            print(f"  surface: offered_known={sf['n_cells_offered_known']} "
                  f"offered_gt0={sf['n_cells_offered_gt0']} "
                  f"UNVERIFIED={sf['n_cells_surface_unverified']} "
                  f"deferred(T/F/None)={sf['mcp_tools_deferred']['true']}/"
                  f"{sf['mcp_tools_deferred']['false']}/{sf['mcp_tools_deferred']['none']}")
            if sf["n_invalid_unverified_with_tool_cells"] > 0:
                print(f"  *** WARNING: {sf['n_invalid_unverified_with_tool_cells']} with-tool cell(s) "
                      f"were NEVER surface-verified -- INVALID for adoption conclusions ***")
        if "answer_vs_search" in a:
            av = a["answer_vs_search"]
            print(f"  answer-tool usage (5a): answer_call_rate={_fmt(av['answer_call_rate'], pct=True)} "
                  f"({av['n_cells_with_answer_call']}/{av['n_cells_with_tool_data']}) "
                  f"search_only_rate={_fmt(av['search_only_rate'], pct=True)}")
            print(f"    mcp__justsearch tool names observed: {av['all_mcp_tool_names_observed'] or '(none called)'}")
        if "tool_error" in a:
            te = a["tool_error"]
            print(f"  tool-error rate (5c): call_error_rate={_fmt(te['call_error_rate'], pct=True)} "
                  f"({te['total_error_calls']}/{te['total_tool_calls']} calls)  "
                  f"cell_error_rate={_fmt(te['cell_error_rate'], pct=True)}")
        if "disallowed_and_leak" in a:
            dl = a["disallowed_and_leak"]
            print(f"  disallowed cells (5d)={dl['n_disallowed_cells']}  "
                  f"leak-suspect cells (5d)={dl['n_leak_suspect_cells']}")
        if "budget_signal" in a:
            bs = a["budget_signal"]
            print(f"  budget/limit signal (5b): n_seen={bs['n_samples_seen']} "
                  f"n_excluded={bs['n_excluded']} (rate={_fmt(bs['exclusion_rate'], pct=True)})  "
                  f"retry_rate={_fmt(bs['retry_rate'], pct=True)} "
                  f"(n_attempts_known={bs['n_attempts_known']})  "
                  f"n_timeout_exclusions={bs['n_timeout_exclusions']}  "
                  f"n_other_exclusions={bs['n_other_exclusions']}")
            print(f"    NOTE: {bs['note']}")
        else:
            print("  budget/limit signal (5b): NO RAW-SCAN DATA (no matching EvalLog samples found)")
        print()

    if report["pairs"]:
        print("=" * 88)
        print("PAIRED A-vs-with-tool COMPARISONS (McNemar + bootstrap CI, via "
              "utility_comparison._stats_from_pairs)")
        print("=" * 88)
        for key in sorted(report["pairs"]):
            p = report["pairs"][key]
            acc = p["accuracy"]
            print(f"  {key}: n_paired={p['n_paired_observations']}  "
                  f"baseline_acc={_fmt(acc['baseline'], pct=True)}  "
                  f"with_tool_acc={_fmt(acc['with_tool'], pct=True)}  "
                  f"delta={_fmt(acc['delta'], pct=True)}  "
                  f"mcnemar_p={_fmt(acc['mcnemar_p'])} ({acc['mcnemar_test']})")
            cost = p["cost_usd"]
            tok = p["tokens_unique"]
            print(f"    cost_usd delta_mean={_fmt(cost['delta_mean'])} ci95={cost['delta_ci95']}  "
                  f"unique_tokens delta_mean={_fmt(tok['delta_mean'])} ci95={tok['delta_ci95']}")
            if p.get("leak_suspect_cells"):
                print(f"    leak-suspect (excluded from pairing): {len(p['leak_suspect_cells'])} cell(s)")
        print()

    if report["surfacing_modes"]:
        print("Tool-surfacing mode per with-tool arm:")
        for key in sorted(report["surfacing_modes"]):
            print(f"  {key}: {report['surfacing_modes'][key]}")
        print()

    # Compact final summary block.
    print("=" * 88)
    print("SUMMARY (one row per model|condition)")
    print("=" * 88)
    header = (f"{'model|cond':<22}{'n':>5}{'acc':>8}{'adopt':>8}{'answer%':>9}"
              f"{'toolErr%':>10}{'unverif':>9}{'excl%':>8}{'retry%':>8}")
    print(header)
    print("-" * len(header))
    for key in sorted(arms):
        a = arms[key]
        acc = _fmt(a.get("accuracy"), pct=True) if a.get("accuracy") is not None else "n/a"
        adopt = a.get("adoption", {}).get("adoption_rate")
        adopt_s = _fmt(adopt, pct=True) if adopt is not None else "n/a"
        answer_rate = a.get("answer_vs_search", {}).get("answer_call_rate")
        answer_s = _fmt(answer_rate, pct=True) if answer_rate is not None else "n/a"
        err_rate = a.get("tool_error", {}).get("call_error_rate")
        err_s = _fmt(err_rate, pct=True) if err_rate is not None else "n/a"
        unverif = a.get("surface", {}).get("n_cells_surface_unverified", "n/a")
        bs = a.get("budget_signal", {})
        excl_s = _fmt(bs.get("exclusion_rate"), pct=True) if bs.get("exclusion_rate") is not None else "n/a"
        retry_s = _fmt(bs.get("retry_rate"), pct=True) if bs.get("retry_rate") is not None else "n/a"
        print(f"{key:<22}{a.get('n_cells', 0):>5}{acc:>8}{adopt_s:>8}{answer_s:>9}"
              f"{err_s:>10}{str(unverif):>9}{excl_s:>8}{retry_s:>8}")
    print()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("parent_dirs", nargs="*", help="positional log dir(s); leaf or parent")
    ap.add_argument("--log-dir", dest="log_dir_opt", action="append", default=[],
                     help="repeatable log dir (leaf containing *.eval/*.json, or a parent)")
    ap.add_argument("--label", action="append", default=[],
                     help="optional label per --log-dir (position-matched; cosmetic only)")
    ap.add_argument("--json", action="store_true", help="also emit the structured report as JSON")
    args = ap.parse_args()

    all_dirs = list(args.log_dir_opt) + list(args.parent_dirs)
    if not all_dirs:
        ap.error("provide at least one --log-dir (or a positional dir)")

    summaries, resolved, observations = load_summaries(all_dirs)
    if not summaries:
        print("No successful cells found in the resolved log dirs.")
        print(f"Resolved log dirs: {resolved}")
        return 1

    raw_rows = _raw_rows(observations)
    report = build_report(summaries, raw_rows)
    print_report(report, resolved)

    if args.json:
        print(json.dumps(report, indent=2, default=str))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
