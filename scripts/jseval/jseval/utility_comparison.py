"""Agent-utility comparison composer (tempdoc 624).

A *utility comparison* is the condition-paired sibling of the 623 benchmark
release: it projects over agent-eval runs cohort-identical on every axis except
``condition`` (with-tool vs. without-tool), reports one cell per
``(corpus, agent_model)``, pairs the arms, and aggregates over ``seed``.

It is a SEPARATE canonical record — NOT a metric family inside the single-cohort
623 release object, which ``compose()`` refuses for cross-cohort sets and models
scalars, not paired deltas (tempdoc 624 §D.2 / §Confidence-pass R4). It reuses
the 623 / manifest substrate (canonical hashing, the cited-baseline + coverage +
confidence-tier honesty fields) and the ``compare_runs`` paired statistics,
conforming to the canonical-record + governed-projection seam (553 / 622 / 623).

Each input summary is shaped:

    {
      "manifest": <agent_manifest.build_agent_manifest(...)>,
      "condition": "A" | "B" | "C",
      "agent_model": "haiku",
      "corpus": {"dataset": "...", "signature": "...", ...},
      "per_query": {qid: {"correct": bool, "cost_usd": float,
                          "unique_tokens": int, "num_turns": int}},
    }
"""

from __future__ import annotations

import statistics as _stats

from jseval import compare_runs
from jseval.agent_manifest import agent_cohort_key
from jseval.release import canonical_dataset_slug

SCHEMA = "utility-comparison.v1"
SCHEMA_VERSION = 1

# Condition semantics (tempdoc 346): A = file tools only (baseline),
# B = file + JustSearch, C = JustSearch only (substitution).
_WITH_TOOL = {"B", "C"}
_BASELINE = "A"


class UtilityComposeError(ValueError):
    """Raised when a candidate run-set cannot form one coherent comparison."""


# Cited external prior art (tempdoc 624 §D-4 / §D.5) — CONSTANTS, never a
# projection of our runs. They position the artifact as a contribution, not a
# boast; pinned by source + version, self_reproduced=False.
CITED_BASELINES: dict = {
    "retrieval_tool_utility": [
        {
            "name": "FRAMES",
            "claim": "retrieval lifts downstream QA: 0.40 (no retrieval) -> 0.66 (multi-step), same model",
            "value": {"no_retrieval": 0.40, "with_retrieval": 0.66},
            "source_url": "https://arxiv.org/abs/2409.12941",
            "version": "Google, NAACL 2025",
            "self_reproduced": False,
        },
        {
            "name": "BFCL-V4 web-search",
            "claim": "accuracy drops sharply without the retrieval tool (with/without-tool precedent)",
            "value": None,
            "source_url": "https://gorilla.cs.berkeley.edu/blogs/15_bfcl_v4_web_search.html",
            "version": "Berkeley Function-Calling Leaderboard V4 (2025)",
            "self_reproduced": False,
        },
        {
            "name": "Sourcegraph CodeScaleBench",
            "claim": "direct structural twin: grep/read baseline vs. MCP search tools; own caveat that the swap-the-backend benchmark 'was not enough on its own'",
            "value": None,
            "source_url": "https://sourcegraph.com/blog/codescalebench-testing-coding-agents-on-large-codebases-and-multi-repo-software-engineering-tasks",
            "version": "Sourcegraph (2025)",
            "self_reproduced": False,
        },
        {
            "name": "PHMForge",
            "claim": "the exact MCP-tools-vs-text-RAG ablation (industrial prognostics domain)",
            "value": None,
            "source_url": "https://arxiv.org/html/2604.01532",
            "version": "2026",
            "self_reproduced": False,
        },
        {
            "name": "A-RAG",
            "claim": "ablates removing individual retrieval tools from the agent toolkit on multi-hop QA",
            "value": None,
            "source_url": "https://arxiv.org/html/2602.03442v1",
            "version": "2026",
            "self_reproduced": False,
        },
    ],
}


def _percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    s = sorted(values)
    if len(s) == 1:
        return float(s[0])
    k = (len(s) - 1) * pct
    lo = int(k)
    hi = min(lo + 1, len(s) - 1)
    return float(s[lo] + (s[hi] - s[lo]) * (k - lo))


def _distribution(values: list) -> dict:
    """Per-arm distribution (median + p95 + mean) — NOT a single average.

    Agent cost/token usage is heavy-tailed (tempdoc 624 D-3 / HAL), so a single
    mean is misleading; the median + p95 are the honest summary.
    """
    vals = [float(v) for v in values if v is not None]
    if not vals:
        return {"n": 0, "median": None, "p95": None, "mean": None}
    return {
        "n": len(vals),
        "median": round(_percentile(vals, 0.5), 6),
        "p95": round(_percentile(vals, 0.95), 6),
        "mean": round(sum(vals) / len(vals), 6),
    }


def _seed_envelope(values: list[float]) -> dict:
    """mean +/- population-sigma over per-seed accuracies (the seed envelope, R3).

    Extends the 400/623 within-config envelope from 'reruns of one config' to
    'seeds of one cell' — the variance is larger but the shape is the same.
    """
    if not values:
        return {"mean": None, "stdev": None, "n": 0}
    if len(values) < 2:
        return {"mean": round(values[0], 4), "stdev": None, "n": 1}
    return {
        "mean": round(_stats.mean(values), 4),
        "stdev": round(_stats.pstdev(values), 4),
        "n": len(values),
    }


def compose_utility(
    run_summaries: list[dict],
    *,
    composed_at: str,
    external_baselines: dict | None = None,
    coverage: dict | None = None,
    confidence_tier: str = "C",
    contamination_class: str = "unknown",
    governance: dict | None = None,
) -> dict:
    """Compose agent-eval run summaries into one ``utility-comparison.v1`` record.

    ``governance`` (from ``utility_governance.paired_comparability``) carries the
    run's loss-accounting + comparability verdict; when present, the record's
    ``comparable`` verdict + ``confidence_tier`` are **derived from it**, never
    hand-set (tempdoc 624 §Run-governance design)."""
    if not run_summaries:
        raise UtilityComposeError("no run summaries provided")

    # 1. One harness cohort across the whole record (mirror release.compose).
    keys = set()
    for s in run_summaries:
        m = s.get("manifest")
        if not isinstance(m, dict):
            raise UtilityComposeError(
                f"summary for {s.get('corpus')!r} has no embedded manifest",
            )
        keys.add(m.get("agent_cohort_key") or agent_cohort_key(m))
    if len(keys) != 1:
        raise UtilityComposeError(
            "runs are not one harness cohort (agent_cohort_key differs): "
            f"{sorted(k[:12] for k in keys)}",
        )
    cohort_key = keys.pop()

    # 2. With-tool arms must share one search-config (it co-varies with condition;
    #    R2 — recorded at the record level, never part of the pairing key).
    search_keys = {
        s["manifest"].get("search_config_cohort_key")
        for s in run_summaries
        if s.get("condition") in _WITH_TOOL
    }
    search_keys.discard(None)
    if len(search_keys) > 1:
        raise UtilityComposeError(
            "with-tool arms span multiple search configs: "
            f"{sorted(k[:12] for k in search_keys)}",
        )
    search_config = next(iter(search_keys), None)

    ref = run_summaries[0]["manifest"]
    cohort = {
        "agent_cohort_key": cohort_key,
        "git_sha": ref.get("git_sha"),
        "cli_version": ref.get("cli_version"),
        "mcp_tool_surface_hash": ref.get("mcp_tool_surface_hash"),
        "judge": ref.get("judge"),
        "prompt_template_hash": ref.get("prompt_template_hash"),
        "decoding": ref.get("decoding"),
        "eval_limits": ref.get("eval_limits"),
        "search_config_cohort_key": search_config,
        "hardware": ref.get("hardware"),
    }

    # 3. Group by (corpus slug, agent_model) -> per-cell paired comparison.
    cells: dict = {}
    seeds_seen: set = set()
    for s in run_summaries:
        slug = canonical_dataset_slug((s.get("corpus") or {}).get("dataset"))
        model = s.get("agent_model")
        cells.setdefault((slug, model), []).append(s)
        seeds_seen.add(s["manifest"].get("seed"))

    measured: dict = {}
    for (slug, model), cell_summaries in sorted(
        cells.items(), key=lambda x: (str(x[0][0]), str(x[0][1])),
    ):
        cell = _compose_cell(cell_summaries)
        if cell is not None:
            measured.setdefault(slug, {})[model] = cell

    # Run-governance: derive the comparability verdict + tier from the run's
    # loss-accounting (never hand-set when governance is present). A run that is
    # not comparable (e.g. high/asymmetric timeout exclusion) is tier C + flagged.
    comparability = None
    derived_tier = confidence_tier
    if governance is not None:
        comparability = {
            "comparable": governance.get("comparable"),
            "reasons": governance.get("reasons", []),
            "metrics": governance.get("metrics", {}),
            "per_arm_loss": governance.get("per_arm_loss", {}),
        }
        derived_tier = "C" if not governance.get("comparable") else confidence_tier

    return {
        "schema": SCHEMA,
        "schema_version": SCHEMA_VERSION,
        "composed_at": composed_at,
        "cohort": cohort,
        "conditions": {
            "baseline": "A (file tools only)",
            "with_tool": "C (JustSearch only)",
            "addition": "B (file tools + JustSearch)",
        },
        "measured": measured,
        "seed_count": len([x for x in seeds_seen if x is not None]),
        "confidence_tier": derived_tier,
        # Run-governance verdict — the record vouching for its own trustworthiness.
        "comparability": comparability,
        "coverage": coverage or _default_coverage(contamination_class),
        # External references are CITED CONSTANTS (never a projection of our runs).
        "external_baselines": external_baselines or {},
    }


def _default_coverage(contamination_class: str) -> dict:
    return {
        "measures": (
            "marginal utility of the JustSearch MCP retrieval tool to an LLM "
            "agent (answer accuracy + cost/token efficiency) vs. generic file tools"
        ),
        "does_not_measure": (
            "the realistic 'addition' scenario unless condition B is present; the "
            "favorable delta is the SUBSTITUTION arm (C, file tools disabled) — NOT "
            "'adding JustSearch to an agent that already has file tools' "
            "(tempdoc 624 §C-4)."
        ),
        "contamination_class": contamination_class,
    }


def _index_by_seed(arm: list[dict]) -> dict:
    by_seed: dict = {}
    for s in arm:
        by_seed.setdefault(s["manifest"].get("seed"), []).append(s)
    return by_seed


def _pair_observations(baseline: list[dict], with_tool: list[dict]) -> dict:
    """Build the paired (seed, qid) observation set ONCE — the exact pairing
    logic ``_arm_comparison`` used to inline, now factored so both the pooled
    comparison and any per-stratum breakdown (tempdoc 624 §T.4) share it. A
    stratified view can therefore never disagree with the pooled view about
    which observations are even paired.

    :returns: ``{"{seed}:{qid}": {"seed":, "qid":, "a_correct":, "c_correct":,
        "a_cost":, "c_cost":, "a_tok":, "c_tok":, "a_turns":, "c_turns":}}``
    """
    a_by_seed = _index_by_seed(baseline)
    c_by_seed = _index_by_seed(with_tool)
    shared_seeds = sorted(
        set(a_by_seed) & set(c_by_seed), key=lambda x: (x is None, x),
    )
    pairs: dict[str, dict] = {}
    for seed in shared_seeds:
        a_pq = a_by_seed[seed][0].get("per_query", {})
        c_pq = c_by_seed[seed][0].get("per_query", {})
        for q in sorted(set(a_pq) & set(c_pq)):
            ca, cc = a_pq[q], c_pq[q]
            pairs[f"{seed}:{q}"] = {
                "seed": seed,
                "qid": q,
                "a_correct": bool(ca.get("correct")),
                "c_correct": bool(cc.get("correct")),
                "a_cost": ca.get("cost_usd"),
                "c_cost": cc.get("cost_usd"),
                "a_tok": ca.get("unique_tokens"),
                "c_tok": cc.get("unique_tokens"),
                "a_turns": ca.get("num_turns"),
                "c_turns": cc.get("num_turns"),
            }
    return pairs


def _stats_from_pairs(pairs: dict) -> dict | None:
    """The full per-comparison stat block — McNemar accuracy + bootstrap-CI
    cost/token/turn deltas + seed envelope — computed independently over
    whichever paired-observation set is handed in: the pooled set, or one
    stratum's subset (tempdoc 624 §T.4). Each call is a fully self-contained
    significance test/CI on ITS OWN n; a per-stratum caller never borrows the
    pooled result (§M.8 item 7)."""
    if not pairs:
        return None

    a_correct = {obs: p["a_correct"] for obs, p in pairs.items()}
    c_correct = {obs: p["c_correct"] for obs, p in pairs.items()}

    by_seed: dict = {}
    for p in pairs.values():
        by_seed.setdefault(p["seed"], []).append(p)
    seed_order = sorted(by_seed, key=lambda x: (x is None, x))
    per_seed_acc_a = [
        sum(1 for p in by_seed[seed] if p["a_correct"]) / len(by_seed[seed])
        for seed in seed_order
    ]
    per_seed_acc_c = [
        sum(1 for p in by_seed[seed] if p["c_correct"]) / len(by_seed[seed])
        for seed in seed_order
    ]

    pqm_a = {
        obs: {
            "cost_usd": float(p["a_cost"] or 0.0),
            "unique_tokens": float(p["a_tok"] or 0),
            "num_turns": float(p["a_turns"] or 0),
        }
        for obs, p in pairs.items()
    }
    pqm_c = {
        obs: {
            "cost_usd": float(p["c_cost"] or 0.0),
            "unique_tokens": float(p["c_tok"] or 0),
            "num_turns": float(p["c_turns"] or 0),
        }
        for obs, p in pairs.items()
    }

    # accuracy (binary) -> McNemar over the (seed, qid) discordant pairs.
    mc = compare_runs.mcnemar(a_correct, c_correct)

    # continuous metrics -> paired delta + bootstrap CI + Cohen's d_z.
    pseudo_qrels = {obs: {} for obs in pqm_a}
    cont = compare_runs.compare(
        {"per_query_metrics": pqm_a},
        {"per_query_metrics": pqm_c},
        pseudo_qrels,
        metrics=["cost_usd", "unique_tokens", "num_turns"],
    )

    a_cost = [p["a_cost"] for p in pairs.values()]
    c_cost = [p["c_cost"] for p in pairs.values()]
    a_tok = [p["a_tok"] for p in pairs.values()]
    c_tok = [p["c_tok"] for p in pairs.values()]
    a_turns = [p["a_turns"] for p in pairs.values()]
    c_turns = [p["c_turns"] for p in pairs.values()]

    return {
        "accuracy": {
            "baseline": mc["accuracy_a"],
            "with_tool": mc["accuracy_b"],
            "delta": mc["accuracy_delta"],
            "mcnemar_p": mc["p_value"],
            "mcnemar_test": mc["test"],
            "n_with_tool_fixes": mc["n_b_only_correct"],
            "n_with_tool_breaks": mc["n_a_only_correct"],
            "seed_envelope_baseline": _seed_envelope(per_seed_acc_a),
            "seed_envelope_with_tool": _seed_envelope(per_seed_acc_c),
        },
        # Token-efficiency is the contamination-robust headline (tempdoc 624 D-1):
        # cache_creation (unique) tokens, reported as per-arm distributions.
        "tokens_unique": {
            "baseline": _distribution(a_tok),
            "with_tool": _distribution(c_tok),
            "delta_mean": cont["unique_tokens"]["delta"],
            "delta_ci95": cont["unique_tokens"]["ci_95"],
        },
        "cost_usd": {
            "baseline": _distribution(a_cost),
            "with_tool": _distribution(c_cost),
            "delta_mean": cont["cost_usd"]["delta"],
            "delta_ci95": cont["cost_usd"]["ci_95"],
        },
        "turns": {
            "baseline": _distribution(a_turns),
            "with_tool": _distribution(c_turns),
            "delta_mean": cont["num_turns"]["delta"],
        },
        "n_paired_observations": mc["n_paired"],
    }


def _arm_comparison(
    baseline: list[dict],
    with_tool: list[dict],
    *,
    stratify_by: dict[str, str] | None = None,
) -> dict | None:
    """One paired baseline(A)-vs-with-tool-arm comparison: McNemar accuracy +
    bootstrap-CI cost/token/turn deltas, over the seeds + queries both completed.

    ``stratify_by`` (optional ``qid -> stratum label``, tempdoc 624 §T.4) adds
    an additive ``"stratified": {"by_stratum": {label: {...same shape as this
    dict...}}}`` key: the identical McNemar+bootstrap computation, independently
    re-run per stratum over ONLY that stratum's paired observations — each
    stratum's ``mcnemar_p``/``ci_95`` is its own, never inherited from the
    pooled result (§M.8 item 7). The pooled top-level fields are never changed
    by this parameter, and when ``stratify_by`` is ``None`` (the default) no
    ``"stratified"`` key is added at all — byte-identical to the
    pre-stratification behavior."""
    if not baseline or not with_tool:
        return None  # need both arms to form a comparison

    pairs = _pair_observations(baseline, with_tool)
    result = _stats_from_pairs(pairs)
    if result is None:
        return None

    if stratify_by:
        labels = sorted({
            stratify_by[p["qid"]] for p in pairs.values() if p["qid"] in stratify_by
        })
        by_stratum: dict = {}
        for label in labels:
            sub_pairs = {
                obs: p for obs, p in pairs.items()
                if stratify_by.get(p["qid"]) == label
            }
            sub_stats = _stats_from_pairs(sub_pairs)
            if sub_stats is not None:
                by_stratum[label] = sub_stats
        if by_stratum:
            result["stratified"] = {"by_stratum": by_stratum}

    return result


def _default_corpus_stratify(cell_summaries: list[dict]) -> dict[str, str] | None:
    """Default ``qid -> corpus`` stratum mapping (tempdoc 624 §T.4).

    ``compose_utility`` already groups a cell by canonical dataset SLUG, so
    every summary in ``cell_summaries`` shares one slug — but distinct
    summaries can still carry a different corpus SIGNATURE (e.g. the corpus
    was refreshed between eval runs), which is the finer sub-population this
    stratifies on automatically, with no caller-supplied mapping required.
    Each query's label is derived from whichever summary's ``per_query`` dict
    it appears in. Returns ``None`` (no stratification) when every query
    resolves to the same corpus identity, so a single-corpus cell's composed
    output stays byte-identical to the pre-stratification behavior."""
    qid_to_label: dict[str, str] = {}
    for s in cell_summaries:
        corpus = s.get("corpus") or {}
        label = f"{corpus.get('dataset')}:{corpus.get('signature')}"
        for qid in (s.get("per_query") or {}):
            qid_to_label[qid] = label
    if len({*qid_to_label.values()}) < 2:
        return None
    return qid_to_label


def _compose_cell(cell_summaries: list[dict]) -> dict | None:
    """Compose one (corpus, model) cell. The top-level headlines baseline A vs the
    REALISTIC arm — **addition B** (agent that already has file tools *and* gets
    JustSearch) when present, falling back to C only if B was not run. It NEVER
    defaults the headline to the substitution arm C (file tools disabled — nobody
    deploys that), per tempdoc 624 §C-4. ``arms`` always carries the SEPARATE
    substitution (C) and addition (B) deltas; a substitution-only cell is flagged
    so a consumer cannot lift its favorable accuracy as a deployment headline."""
    baseline = [s for s in cell_summaries if s.get("condition") == _BASELINE]
    substitution = [s for s in cell_summaries if s.get("condition") == "C"]
    addition = [s for s in cell_summaries if s.get("condition") == "B"]
    if not baseline or not (substitution or addition):
        return None
    primary = addition or substitution  # prefer B (realistic); never headline C (C-4)
    # Auto-derived qid -> corpus-signature stratum map (§T.4); None (no field
    # added) unless this cell actually spans more than one corpus signature.
    stratify_by = _default_corpus_stratify(cell_summaries)
    cell = _arm_comparison(baseline, primary, stratify_by=stratify_by)
    if cell is None:
        return None
    arms: dict = {}
    if substitution:
        arms["substitution_c"] = _arm_comparison(baseline, substitution, stratify_by=stratify_by)
    if addition:
        arms["addition_b"] = _arm_comparison(baseline, addition, stratify_by=stratify_by)
    cell["arms"] = arms
    cell["primary_arm"] = "addition_b" if addition else "substitution_c"
    if cell["primary_arm"] == "substitution_c":
        cell["headline_caveat"] = (
            "Accuracy shown is the SUBSTITUTION arm (C, file tools disabled) — NOT a deployment "
            "scenario; lead with token-efficiency, not this accuracy (tempdoc 624 §C-4). Run "
            "condition B for the realistic 'addition' number.")
    return cell
