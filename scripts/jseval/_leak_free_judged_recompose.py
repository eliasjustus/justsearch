"""Throwaway, uncommitted re-analysis script (tempdoc 624 "The judge-scoring gap" follow-up,
2026-07-02).

Purpose: `_leak_free_recompose.py` closed the leak-free gap but every number it produced was
still `substring-em`-only -- the hybrid EM-auto-pass -> local-LLM-judge pipeline
(`jseval/utility_judge.py`, §M.4) was never actually invoked on the real Phase-7 logs
(`tmp/624-run/logs-{en,de,scan}/`). This script closes BOTH gaps in one pass, orthogonally:

  1. **Judge-rescore** every EM-miss via `utility_judge.judge_logs` against a live local
     llama-server (a different model family than the claude-haiku agent under test --
     the self-preference control from C-6), dual-order, abstain-to-EM on disagreement.
     Writes `judge-overlay.json` into each log dir (`utility_judge.write_overlay`, the
     same artifact `jseval utility-judge` would write) for provenance.
  2. **Leak-exclude** using the identical text-derived leak signature
     `_leak_free_recompose.py` already validated (a case-insensitive `queries.json(l)`
     mention in the completion text -- these Phase-7 logs predate the tool_calls
     backstop and were captured through a solver that never records a tool_calls
     stream, so the backstop has no data to act on; confirmed in the prior pass).

A cell can be BOTH leak-excluded AND judge-rescored (survives leak exclusion but was an
EM-miss the judge then adjudicates) -- the two signals are orthogonal per-query flags on
the same summary dict, so composing them is just doing both in the same
`eval_logs_to_summaries` -> `apply_leak_flags` -> `compose_utility` pipeline, unmodified.

Reuses the EXISTING composition machinery unmodified (no hand-rolled stats):
  - `agent_utility_run.eval_logs_to_summaries(..., judge_overlay=overlay)` for the
    judge-rescored per-query `correct` verdicts + judge identity in the cohort manifest.
  - The same `scan_leaked_cells` / `apply_leak_flags` helpers `_leak_free_recompose.py`
    defines, imported directly (no duplication).
  - `utility_governance.compute_loss_accounting` / `paired_comparability` for governance.
  - `utility_comparison.compose_utility` / `compose_utility_cross_corpus` for the actual
    paired statistics (McNemar, bootstrap CI).

Run from `scripts/jseval/` with a local judge model already serving an OpenAI-compatible
endpoint (`ai_activate` / `jseval run --start-backend --llm` / a bare llama-server):
`python _leak_free_judged_recompose.py --judge-url http://127.0.0.1:<port>`
"""
from __future__ import annotations

import argparse
import datetime as _dt
import json
from pathlib import Path

from jseval import agent_utility_run as aur
from jseval import utility_comparison as uc
from jseval import utility_governance as ug
from jseval import utility_judge as uj
from jseval.agent_utility_run import apply_leak_flags, scan_leaked_cells

REPO_ROOT = Path(__file__).resolve().parents[2]
RUN_ROOT = REPO_ROOT / "tmp" / "624-run"
OUT_ROOT = Path(__file__).resolve().parent / "624-run-2026-07-02"

# Script-local config (was previously imported from the now-retired
# `_leak_free_recompose.py` -- scan_leaked_cells/apply_leak_flags are promoted
# into `jseval.agent_utility_run`; this run-specific config stays a one-off here).
SEARCH_CONFIG_KEY = "eaa0251611268cf1cbfd9cc444824db32881fb08382a48fa3996964445985156"
CONTAMINATION_CLASS = "private-synthetic"
CONFIDENCE_TIER = "C"

CORPORA = {
    "en": RUN_ROOT / "logs-en",
    "de": RUN_ROOT / "logs-de",
    "scan": RUN_ROOT / "logs-scan",
}


def compose_corpus(tag: str, log_dir: Path, judge_url: str, judge_model: str | None) -> tuple[dict, dict]:
    overlay = uj.judge_logs(str(log_dir), judge_url=judge_url, judge_model=judge_model)
    overlay_path = uj.write_overlay(str(log_dir), overlay)

    leaked = scan_leaked_cells(log_dir)
    summaries = aur.eval_logs_to_summaries(
        str(log_dir), search_config_cohort_key=SEARCH_CONFIG_KEY, judge_overlay=overlay)
    n_flagged = apply_leak_flags(summaries, leaked)

    arms = ug.compute_loss_accounting(str(log_dir))
    verdict, gmetrics = ug.paired_comparability(arms)
    governance = {
        "comparable": verdict.comparable,
        "reasons": verdict.reasons,
        "metrics": gmetrics,
        "per_arm_loss": {
            c: {"n_attempted": l.n_attempted, "n_completed": l.n_completed,
                "n_excluded": l.n_excluded, "exclusion_rate": round(l.exclusion_rate, 4)}
            for c, l in arms.items()
        },
    }
    record = uc.compose_utility(
        summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES, contamination_class=CONTAMINATION_CLASS,
        confidence_tier=CONFIDENCE_TIER, governance=governance,
    )
    return record, {
        "tag": tag,
        "leaked": leaked,
        "n_flagged_per_query_entries": n_flagged,
        "summaries": summaries,
        "overlay_path": overlay_path,
        "judge_stats": overlay["stats"],
        "judge_identity": overlay["judge_identity"],
    }


def run(judge_url: str, judge_model: str | None = None):
    all_summaries: list[dict] = []
    per_dir_governance = []
    per_corpus_reports = {}

    for tag, log_dir in CORPORA.items():
        record, report = compose_corpus(tag, log_dir, judge_url, judge_model)
        out_dir = OUT_ROOT / f"out-{tag}-leak-free-judged"
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "utility-comparison.v1.json").write_text(
            json.dumps(record, indent=2, default=str), encoding="utf-8")
        per_corpus_reports[tag] = {
            "leaked_cells": sorted(report["leaked"].values(), key=lambda x: (x["condition"], x["seed"], x["qid"])),
            "n_flagged_per_query_entries": report["n_flagged_per_query_entries"],
            "judge_stats": report["judge_stats"],
            "judge_identity": report["judge_identity"],
            "overlay_path": report["overlay_path"],
            "measured": record["measured"],
        }
        all_summaries.extend(report["summaries"])
        arms = ug.compute_loss_accounting(str(log_dir))
        verdict, gmetrics = ug.paired_comparability(arms)
        per_dir_governance.append((str(log_dir), verdict, gmetrics, arms))
        print(f"=== {tag} ===")
        print(f"  judge: {report['judge_stats']}")
        print(f"  leaked cells found: {len(report['leaked'])}")
        for slug, by_model in record["measured"].items():
            for m, cell in by_model.items():
                acc = cell["accuracy"]
                print(f"  [{slug}/{m}] acc {acc['baseline']}->{acc['with_tool']} "
                      f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}, n={cell['n_paired_observations']})")

    def _label(ld):
        return Path(ld).name

    governance = {
        "comparable": all(v.comparable for _, v, _, _ in per_dir_governance),
        "reasons": [f"{_label(ld)}: {r}" for ld, v, _, _ in per_dir_governance for r in v.reasons],
        "metrics": {_label(ld): gm for ld, _, gm, _ in per_dir_governance},
        "per_arm_loss": {
            f"{_label(ld)}:{c}": {"n_attempted": l.n_attempted, "n_completed": l.n_completed,
                                  "n_excluded": l.n_excluded, "exclusion_rate": round(l.exclusion_rate, 4)}
            for ld, _, _, arms in per_dir_governance for c, l in arms.items()
        },
    }
    cross_record = uc.compose_utility_cross_corpus(
        all_summaries, composed_at=_dt.datetime.now(_dt.timezone.utc).isoformat(),
        external_baselines=uc.CITED_BASELINES, contamination_class=CONTAMINATION_CLASS,
        confidence_tier=CONFIDENCE_TIER, governance=governance,
    )
    out_dir = OUT_ROOT / "out-cross-corpus-leak-free-judged"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "utility-comparison-cross-corpus.v1.json").write_text(
        json.dumps(cross_record, indent=2, default=str), encoding="utf-8")

    print("=== cross-corpus ===")
    for m, cell in cross_record["measured"].items():
        acc = cell["accuracy"]
        print(f"  [pooled/{m}] acc {acc['baseline']}->{acc['with_tool']} "
              f"(d={acc['delta']:+}, McNemar p={acc['mcnemar_p']}, n={cell['n_paired_observations']})")

    report_path = OUT_ROOT / "_leak_free_judged_exclusion_report.json"
    report_path.write_text(json.dumps(per_corpus_reports, indent=2, default=str), encoding="utf-8")
    print(f"\nException/exclusion/judge detail written to {report_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--judge-url", default="http://127.0.0.1:33221",
                     help="JustSearch Head API base URL (NOT llama-server's raw ephemeral port -- "
                          "the Head proxies /v1/chat/completions and /v1/models to whatever port "
                          "llama-server actually bound, per OpenAiCompatController.java. Default "
                          "matches jseval's own eval-backend port, jseval/commands/_common.py "
                          "_DEFAULT_BASE_URL_EVAL.")
    ap.add_argument("--judge-model", default=None)
    args = ap.parse_args()
    run(args.judge_url, args.judge_model)


if __name__ == "__main__":
    main()
