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
                          "unique_tokens": int, "num_turns": int,
                          # Optional (tempdoc 624 §As-built #5) — absent/None on
                          # older summaries, present when the runner captured
                          # real per-call tool-use data:
                          "tool_calls": list | None,
                          "disallowed_tool_calls": list | None,
                          "leak_suspect_tool_calls": list | None,
                          "leak_suspect": bool}},
    }
"""

from __future__ import annotations

import statistics as _stats

from jseval import compare_runs
from jseval.agent_manifest import agent_cohort_key
from jseval.agent_utility_observations import WITH_TOOL_CONDITIONS
from jseval.exposure_contrast import exposure_contrast_eligibility
from jseval.release import canonical_dataset_slug

SCHEMA = "utility-comparison.v1"
SCHEMA_CROSS_CORPUS = "utility-comparison-cross-corpus.v1"
SCHEMA_VERSION = 2

# Condition semantics (tempdoc 346): A = file tools only (baseline),
# B = file + JustSearch, C = JustSearch only (substitution).
_BASELINE = "A"

# Minimum seed count for a DECISION-GRADE accuracy claim (tempdoc 736 D15).
# `--seeds` already defaults to 3 (commands/utility.py); this is a labeling
# floor, not a default change -- the A/B smokes ran single-seed and were
# nonetheless read for accuracy, which is the protocol gap this closes. A
# record below the floor self-labels `seed_floor_met: False` and
# `utility_claim_policy` refuses to promote an accuracy-based claim (benefit
# / null) from it -- exploratory/smoke publication and harmful-result
# publication are both untouched by this floor (see utility_claim_policy.py).
SEED_FLOOR = 3


class UtilityComposeError(ValueError):
    """Raised when a candidate run-set cannot form one coherent comparison."""


# Closed set of revision reasons (tempdoc 624 Design 1). A record's `revision`
# field is present only when it corrects a prior composition of the SAME
# identity-bearing inputs -- never for an unrelated new measurement.
#
# "arm_invalidation" (tempdoc 624 battlefield retrospective, 2026-07-03): the
# with-tool arm (B/C) was certified using a dead `mcp_config` (a `url`-only
# `mcpServers` entry the `claude` CLI silently drops, see
# `utility_calibrate.McpConfigMissingTypeError`) -- annotates a record whose
# with-tool arm is now known to have run tool-less, not a scoring/judge/seed
# correction of an otherwise-valid measurement.
REVISION_REASONS = frozenset(
    {"leak_correction", "judge_rescore", "reseed", "arm_invalidation", "other"})


def build_revision(supersedes: str, reason: str, changed_fields: list[str]) -> dict:
    """Build the `revision` object attached to a corrected ``utility-comparison.v1``
    (or cross-corpus) record (tempdoc 624 Design 1 / Confidence pass #6).

    ``supersedes`` MUST be a relative path to the prior record's JSON file --
    no field in the record itself (``agent_cohort_key``, a pairing key) is
    unique across revisions of the same underlying run, since those are
    deliberately invariant across the original and every corrected version.

    This is metadata construction, not a diffing engine: ``changed_fields`` is
    caller-specified, not auto-derived.
    """
    if not isinstance(supersedes, str) or not supersedes.strip():
        raise UtilityComposeError(
            f"revision supersedes must be a non-empty path string, got {supersedes!r}",
        )
    if reason not in REVISION_REASONS:
        raise UtilityComposeError(
            f"revision reason {reason!r} not in closed set: {sorted(REVISION_REASONS)}",
        )
    for field in changed_fields:
        if not isinstance(field, str):
            raise UtilityComposeError(
                f"revision changed_fields entries must be strings, got {field!r} ({type(field).__name__})",
            )
    return {
        "supersedes": supersedes,
        "reason": reason,
        "changed_fields": list(changed_fields),
    }


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


def _censored_distribution(items: list) -> dict:
    """Per-arm duration distribution + its MANDATORY censoring context
    (tempdoc 624, 2026-07-17 "Time as the third utility axis").

    ``items`` is one ``(duration_or_None, censored_bool)`` tuple per cell.
    Budget-exhausted cells are RIGHT-CENSORED at the cap, so a duration median
    must never be published bare -- it is only ever meaningful alongside how
    many of its contributing observations were censored and the arm's completion
    rate. Every duration arm is emitted through THIS helper, making a bare median
    (a median without `n_censored`/`completion_rate`) structurally impossible.

    `n_censored` counts ONLY cells that contribute a duration to the distribution
    -- a censored cell that recorded no wall-clock is not in the denominator, so
    `completion_rate` can never go out of [0, 1]. Cells lacking a time (censored
    or clean) are surfaced separately as `n_missing_duration` when any exist.
    """
    values = [duration for duration, _ in items if duration is not None]
    dist = _distribution(values)
    n = dist["n"]
    n_censored = sum(1 for duration, censored in items if censored and duration is not None)
    n_missing = sum(1 for duration, _ in items if duration is None)
    dist["n_censored"] = n_censored
    dist["completion_rate"] = (
        round(max(0.0, min(1.0, (n - n_censored) / n)), 6) if n else None
    )
    if n_missing:
        dist["n_missing_duration"] = n_missing
    return dist


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


# Which denominator answers which question (tempdoc 736 D13) -- a PURE
# declaration, never a re-derivation: every number named below already lives
# on the record (`comparability.per_arm_loss.<arm>` for n_attempted/n_excluded;
# `measured.<dataset>.<model>.funnel` for the checked-cell population), this
# block only makes the RELATIONSHIP between them machine-readable. No metric
# numerator/denominator is computed here (tempdoc 736 derisk U4).
_DENOMINATORS = {
    "n_attempted": {
        "tier": "primary",
        "estimand": "intention_to_treat",
        "source": "comparability.per_arm_loss.<condition>.n_attempted",
        "question": (
            "How many cells did this campaign spend money attempting, "
            "including the errored/timed-out tail? The honest base for "
            "cost accounting and cross-campaign comparability -- the ITT "
            "ledger never silently drops an attempted cell."
        ),
    },
    "n_checked": {
        "tier": "secondary",
        "estimand": "funnel_conditional",
        "source": (
            "measured.<dataset>.<model>.funnel.with_tool -- the paired "
            "cells carrying tool_calls/toolsearch_targets/tool_call_sequence"
        ),
        "question": (
            "Of the paired cells, how many carried funnel instrumentation "
            "and are therefore usable for adoption-funnel behavior "
            "analysis? Descriptive of behavior GIVEN a usable cell, not of "
            "campaign spend -- a different question than n_attempted."
        ),
    },
    "n_excluded": {
        "tier": "secondary",
        "estimand": "funnel_conditional",
        "source": (
            "measured.<dataset>.<model>.funnel -- paired cells lacking "
            "funnel instrumentation (e.g. evidence composed before funnel "
            "fields existed)"
        ),
        "question": (
            "Of the paired cells, how many lack funnel instrumentation and "
            "are excluded from the funnel's OWN denominator? NOT the same "
            "population as the ITT ledger's errored/excluded tail -- a cell "
            "can be a successfully-attempted ITT n_attempted member and "
            "still be excluded here for lacking funnel data."
        ),
    },
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
    statistical_alpha: float = 0.05,
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
        if s.get("condition") in WITH_TOOL_CONDITIONS
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
        "git_dirty": ref.get("git_dirty"),
        "source_git_state": ref.get("source_git_state"),
        "cli_version": ref.get("cli_version"),
        "mcp_tool_surface_hash": ref.get("mcp_tool_surface_hash"),
        "judge": ref.get("judge"),
        "prompt_template_hash": ref.get("prompt_template_hash"),
        "decoding": ref.get("decoding"),
        "eval_limits": ref.get("eval_limits"),
        "search_config_cohort_key": search_config,
        "hardware": ref.get("hardware"),
        "environment": ref.get("environment"),
        "query_identity": ref.get("query_identity"),
        "campaign_identity": ref.get("campaign_identity"),
        "tool_surfacing_mode": _tool_surfacing_mode(run_summaries),
        "executor": _executor_stamp(run_summaries),
    }
    # tempdoc 725 increment 2: EXCLUDED (not added as `None`) when absent, so a
    # record composed from pre-725 evidence -- which never captured this identity
    # -- hashes byte-identical to before this field existed (same discipline as
    # the agent_cohort_key key_surface exclusion above).
    if ref.get("exposure_config") is not None:
        cohort["exposure_config"] = ref.get("exposure_config")
    if ref.get("mcp_initialize_identity") is not None:
        cohort["mcp_initialize_identity"] = ref.get("mcp_initialize_identity")

    # 3. Group by display axes, but reject hidden content/model identity mixes.
    cells: dict = {}
    cell_identities: dict = {}
    seeds_seen: set = set()
    for s in run_summaries:
        slug = canonical_dataset_slug((s.get("corpus") or {}).get("dataset"))
        model = s.get("agent_model")
        manifest = s["manifest"]
        identity = (
            (s.get("corpus") or {}).get("signature"),
            manifest.get("agent_model_version"),
            manifest.get("query_identity"),
            manifest.get("campaign_identity"),
            manifest.get("corpus_identity"),
            manifest.get("corpus_certification"),
            # tempdoc 725 increment 2: a cell mixing two differently-configured
            # exposure/instructions campaigns is the same class of hidden-identity
            # mix this guard exists to catch (R1/R2 harness-identity discipline).
            manifest.get("exposure_mode"),
            manifest.get("instructions_sha256"),
        )
        display_key = (slug, model)
        prior = cell_identities.setdefault(display_key, identity)
        if prior != identity:
            raise UtilityComposeError(
                f"cell {display_key!r} mixes corpus/resolved-model snapshots: "
                f"{prior!r} vs {identity!r}"
            )
        cells.setdefault((slug, model), []).append(s)
        seeds_seen.add(s["manifest"].get("seed"))

    measured: dict = {}
    for (slug, model), cell_summaries in sorted(
        cells.items(), key=lambda x: (str(x[0][0]), str(x[0][1])),
    ):
        cell = _compose_cell(cell_summaries, statistical_alpha=statistical_alpha)
        if cell is not None:
            (signature, resolved_model, query_identity, campaign_identity,
             corpus_identity, corpus_certification,
             _exposure_mode, _instructions_sha256) = (
                cell_identities[(slug, model)]
            )
            # exposure_mode/instructions_sha256 (tempdoc 725 increment 2) are part
            # of the mix-guard identity tuple above but are deliberately NOT
            # projected into cell["identity"] -- unlike the cohort-level fields,
            # this dict has no conditional-omission path, so adding them
            # unconditionally (even as `null`) would change semantic_digest for
            # every pre-725 record. The mix-guard check above is what matters;
            # this cell dict stays byte-identical to its pre-725 shape.
            cell["identity"] = {
                "corpus_signature": signature,
                "requested_model_alias": model,
                "resolved_provider_model": resolved_model,
                "query_identity": query_identity,
                "campaign_identity": campaign_identity,
                "corpus_identity": corpus_identity,
                "corpus_certification": corpus_certification,
            }
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
            # tempdoc 736 D13: one-line pointer to the top-level `denominators`
            # declaration -- this block's own n_attempted/n_excluded (per arm)
            # are the PRIMARY (ITT) denominator; pure documentation, no value
            # here is recomputed.
            "denominator_note": (
                "per_arm_loss.<arm>.n_attempted is the PRIMARY (ITT) "
                "denominator for spend/comparability -- see the record's "
                "top-level `denominators` block."
            ),
        }
        derived_tier = "C" if not governance.get("comparable") else confidence_tier

    seed_count = len([x for x in seeds_seen if x is not None])
    seed_floor_met = seed_count >= SEED_FLOOR

    # tempdoc 736 D11: stamp the pre-#605 tombstone reason onto the record
    # ITSELF when detected, so the ineligibility is visible without a failed
    # `exposure_contrast` call. OMITTED entirely (never stamped as
    # `{"eligible": true}` or similar) when the record IS eligible -- a
    # record composed from post-#605 evidence stays byte-identical to before
    # this stamp existed, same conditional-omission discipline as
    # `exposure_config` above.
    exposure_eligibility = exposure_contrast_eligibility(
        {"measured": measured, "cohort": cohort}
    )
    exposure_contrast_ineligible = (
        None if exposure_eligibility["eligible"]
        else {"reasons": exposure_eligibility["reasons"], "since": "#605"}
    )

    result = {
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
        "seed_count": seed_count,
        # tempdoc 736 D15: a decision-grade-signal, analogous to
        # confidence_tier -- `--seeds` already defaults to 3, this is a
        # protocol/labeling floor, not a default change. See SEED_FLOOR.
        "seed_floor_met": seed_floor_met,
        "confidence_tier": derived_tier,
        # Run-governance verdict — the record vouching for its own trustworthiness.
        "comparability": comparability,
        "coverage": coverage or _default_coverage(contamination_class),
        # External references are CITED CONSTANTS (never a projection of our runs).
        "external_baselines": external_baselines or {},
        # Empirical --disallowedTools + answer-key-leak coverage, per condition
        # (tempdoc 624 §As-built #5 residual-gap close). Additive field — the
        # schema is `additionalProperties: true` and every existing field above
        # is unchanged, so a consumer that doesn't know this key is byte-for-byte
        # compatible with the pre-this-change record shape.
        "tool_call_assertions": _tool_call_assertions(run_summaries),
        # tempdoc 736 D13: self-describing denominators (always emitted --
        # a pure declaration, not a per-record computation, see _DENOMINATORS).
        "denominators": _DENOMINATORS,
    }
    if exposure_contrast_ineligible is not None:
        result["exposure_contrast_ineligible"] = exposure_contrast_ineligible
    return result


def _tool_call_assertions(run_summaries: list[dict]) -> dict:
    """Per-condition (arm) coverage of the empirical tool-call assertions
    (tempdoc 624 §As-built #5 residual-gap close): how many per-query cells
    actually carry real ``tool_calls`` data vs. how many of those show a
    ``--disallowedTools`` violation or an answer-key-leak suspect.

    Only the Inspect-AI runner (post-fix) and the classic ``run_agent_eval``
    runner populate ``per_query[qid]["tool_calls"]``; an older on-disk EvalLog
    or run-result JSON simply lacks the key, so ``.get("tool_calls")`` reads
    ``None`` for that cell — degrading to "no tool data" rather than a
    fabricated "0 violations" that would misread as a verified-clean cell.
    This is the field that makes that distinction legible: a consumer checks
    ``cells_with_tool_data`` before trusting ``cells_with_disallowed_violations``
    as "verified clean" (0 violations across N *checked* cells) rather than
    "no data" (0 of 0 checked, because none were captured).

    Also carries the offered-MCP-tool-surface coverage (tempdoc 624 battlefield
    retrospective): ``cells_with_mcp_surface_verified`` counts cells where the
    solver actually parsed an init event AND it offered >=1 ``mcp__justsearch``
    tool (a cell that FAILED this check never reaches here — it was excluded
    upstream as an errored cell, same as any other infra failure);
    ``cells_mcp_surface_unverified`` counts cells where no init event could be
    parsed at all (crash/timeout before the CLI's first stream-json line) — an
    unknown, not a verified-clean 0. Only the Inspect-AI solver currently
    populates these; a classic ``run_agent_eval`` result reads both as absent.

    :returns: ``{condition: {"cells_total", "cells_with_tool_data",
        "cells_with_disallowed_violations", "cells_with_leak_suspect",
        "cells_with_mcp_surface_verified", "cells_mcp_surface_unverified"}}``.
    """
    by_condition: dict = {}
    for s in run_summaries:
        condition = s.get("condition")
        if condition is None:
            continue
        agg = by_condition.setdefault(condition, {
            "cells_total": 0,
            "cells_with_tool_data": 0,
            "cells_with_disallowed_violations": 0,
            "cells_with_leak_suspect": 0,
            "cells_with_mcp_surface_verified": 0,
            "cells_mcp_surface_unverified": 0,
            "observed_mcp_tool_surface_hashes": set(),
        })
        for entry in (s.get("per_query") or {}).values():
            agg["cells_total"] += 1
            if entry.get("tool_calls") is not None:
                agg["cells_with_tool_data"] += 1
                if entry.get("disallowed_tool_calls"):
                    agg["cells_with_disallowed_violations"] += 1
            # `leak_suspect` (unlike the tool-call fields above) can also be set
            # by the text-scan backstop (`agent_utility_run.apply_leak_flags`) on
            # a cell with no captured tool data at all, so it is counted off the
            # bool flag directly rather than gated on `cells_with_tool_data`.
            if entry.get("leak_suspect"):
                agg["cells_with_leak_suspect"] += 1
            # Offered MCP tool-surface visibility (tempdoc 624 battlefield
            # retrospective): a cell whose surface assertion actually FAILED
            # never reaches here (excluded upstream as an `error`'d cell, same
            # as any other infra failure) -- these two counters distinguish, of
            # the cells that DID reach the composer, how many carry a positive
            # "yes, the agent was actually offered mcp__justsearch tools"
            # confirmation vs. how many never got a parseable init event at all
            # (crash/timeout before the CLI's first stream-json line) and so
            # remain an unknown, not a verified-clean 0.
            mcp_tools_offered = entry.get("mcp_tools_offered")
            if mcp_tools_offered is not None and mcp_tools_offered > 0:
                agg["cells_with_mcp_surface_verified"] += 1
            if entry.get("mcp_surface_unverified"):
                agg["cells_mcp_surface_unverified"] += 1
            if entry.get("observed_mcp_tool_surface_hash"):
                agg["observed_mcp_tool_surface_hashes"].add(
                    entry["observed_mcp_tool_surface_hash"]
                )
    for aggregate in by_condition.values():
        hashes = aggregate.pop("observed_mcp_tool_surface_hashes")
        if hashes:
            aggregate["observed_mcp_tool_surface_hashes"] = sorted(hashes)
            aggregate["observed_mcp_tool_surface_consistent"] = len(hashes) == 1
    return by_condition


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


def _tool_surfacing_mode(run_summaries: list[dict]) -> str | None:
    """Cohort-level rollup of `mcp_tools_deferred` (tempdoc 624 §M.8 amendment,
    Step 0 item 4): whether the `claude` CLI surfaced `mcp__justsearch*` tools
    directly in the init event ("eager"), or the justsearch server connected but
    its tools were reachable only via ToolSearch ("deferred") -- a CLI-version-
    dependent behavior that mediates tool adoption, so it must be recorded as
    COHORT identity (next to cli_version), not a per-cell curiosity buried in
    per_query metadata that a consumer would have to rediscover by hand.

    Reads `mcp_tools_deferred` off every per_query entry of every with-tool
    (B/C) run summary in the record. `None` (absent key -- an older summary
    shape, condition A, or a cell whose init event never parsed) is excluded
    from the vote. Returns ``None`` if no cell in the record carries the field
    at all (unknown -- never defaults to "eager"), ``"mixed"`` if both surfacing
    behaviors were observed within the same record (e.g. a mid-record CLI
    upgrade), else the single observed mode.
    """
    flags = []
    for s in run_summaries:
        if s.get("condition") not in WITH_TOOL_CONDITIONS:
            continue
        for entry in (s.get("per_query") or {}).values():
            v = entry.get("mcp_tools_deferred")
            if v is not None:
                flags.append(bool(v))
    if not flags:
        return None
    if all(flags):
        return "deferred"
    if not any(flags):
        return "eager"
    return "mixed"


def _executor_stamp(run_summaries: list[dict]) -> str | None:
    """Cohort-level ``executor`` provenance (tempdoc 624 §M.8 amendment, Step 0
    item 6): ``"legacy-agent-eval"`` when every summary in the record was
    produced by the classic (smoke-only, non-record-grade) shell-out runner,
    ``"inspect-ai"`` when every summary came from the Inspect-AI executor.

    A summary without an ``executor`` key at all is a PRE-Step-0 summary shape
    (composed before this stamp existed) -- rather than guess which runner
    produced it, this returns ``None`` for the whole record the moment even one
    summary is unmarked or the marks disagree. "Can't tell them apart" must
    read as an honest unknown, not a fabricated majority vote.
    """
    executors = {s.get("executor") for s in run_summaries}
    if len(executors) == 1:
        only = executors.pop()
        if only in ("legacy-agent-eval", "inspect-ai"):
            return only
    return None


def _index_by_seed(arm: list[dict]) -> dict:
    by_seed: dict = {}
    for s in arm:
        by_seed.setdefault(s["manifest"].get("seed"), []).append(s)
    return by_seed


def _pair_observations(baseline: list[dict], with_tool: list[dict]) -> tuple[dict, list[dict]]:
    """Build the paired (seed, qid) observation set ONCE — the exact pairing
    logic ``_arm_comparison`` used to inline, now factored so both the pooled
    comparison and any per-stratum breakdown (tempdoc 624 §T.4) share it. A
    stratified view can therefore never disagree with the pooled view about
    which observations are even paired.

    Also separates out any (seed, qid) observation where EITHER arm's per-query
    record carries ``leak_suspect`` (tempdoc 624 §As-built #7 defense-in-depth
    backstop — an agent tool-call scan flagged a Read/Glob path naming the eval's
    own gold-answer file). A leak-suspect observation's "correct" bit cannot be
    trusted, so it is EXCLUDED from the returned pairs — it never reaches
    McNemar/bootstrap-CI — and reported back separately, so it is neither
    silently dropped from the record nor silently counted as a genuine win.

    :returns: ``(pairs, leak_suspect_cells)`` where ``pairs`` is
        ``{"{seed}:{qid}": {"seed":, "qid":, "a_correct":, "c_correct":,
        "a_cost":, "c_cost":, "a_tok":, "c_tok":, "a_turns":, "c_turns":,
        "a_tool_calls":, "c_tool_calls":}}`` (the last two feed the adoption
        metrics, tempdoc 624 §M.8 amendment Step 0 item 5) plus
        ``"c_toolsearch_targets"``/``"c_tool_call_sequence"`` (feed the adoption
        FUNNEL metrics, tempdoc 725 increment 3 -- with-tool-arm-only, like the
        tool-call fields, ``None`` on evidence captured before these fields
        existed) and ``leak_suspect_cells`` is ``[{"seed":, "qid":,
        "baseline_leak_suspect":, "with_tool_leak_suspect":}, ...]``.
    """
    a_by_seed = _index_by_seed(baseline)
    c_by_seed = _index_by_seed(with_tool)
    shared_seeds = sorted(
        set(a_by_seed) & set(c_by_seed), key=lambda x: (x is None, x),
    )
    pairs: dict[str, dict] = {}
    leak_suspect_cells: list[dict] = []
    for seed in shared_seeds:
        if len(a_by_seed[seed]) != 1 or len(c_by_seed[seed]) != 1:
            raise UtilityComposeError(
                f"duplicate summaries for paired seed={seed!r}: "
                f"baseline={len(a_by_seed[seed])} with_tool={len(c_by_seed[seed])}"
            )
        a_pq = a_by_seed[seed][0].get("per_query", {})
        c_pq = c_by_seed[seed][0].get("per_query", {})
        for q in sorted(set(a_pq) & set(c_pq)):
            ca, cc = a_pq[q], c_pq[q]
            a_leak, c_leak = bool(ca.get("leak_suspect")), bool(cc.get("leak_suspect"))
            if a_leak or c_leak:
                leak_suspect_cells.append({
                    "seed": seed,
                    "qid": q,
                    "baseline_leak_suspect": a_leak,
                    "with_tool_leak_suspect": c_leak,
                })
                continue
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
                # Duration axis (tempdoc 624, 2026-07-17): wall-clock total_time.
                # The per-protocol/measured set is completed cells only (excluded
                # cells never reach `per_query`), so nothing here is censored.
                "a_dur": ca.get("total_time"),
                "c_dur": cc.get("total_time"),
                "a_censored": False,
                "c_censored": False,
                "a_tool_calls": ca.get("tool_calls"),
                "c_tool_calls": cc.get("tool_calls"),
                "c_toolsearch_targets": cc.get("toolsearch_targets"),
                "c_tool_call_sequence": cc.get("tool_call_sequence"),
            }
    return pairs, leak_suspect_cells


_MCP_JUSTSEARCH_PREFIX = "mcp__justsearch"


def _mcp_call_count(tool_calls: list[dict] | None) -> int:
    return sum(1 for tc in (tool_calls or []) if str(tc.get("tool", "")).startswith(_MCP_JUSTSEARCH_PREFIX))


def _first_mcp_call_index(tool_calls: list[dict] | None) -> int | None:
    """1-based index (a call-index, not a conversation turn) of the first
    ``mcp__justsearch*`` call within one cell's ``tool_calls`` list, or ``None``
    if no such call was made."""
    for i, tc in enumerate(tool_calls or [], start=1):
        if str(tc.get("tool", "")).startswith(_MCP_JUSTSEARCH_PREFIX):
            return i
    return None


def _adoption_metrics(tool_calls_per_cell: list[list[dict] | None]) -> dict:
    """Pre-registered adoption metrics (tempdoc 624 §M.8 amendment, Step 0 item
    5), derived purely from each cell's already-captured ``tool_calls`` list:

    - ``adoption_rate``: fraction of CHECKED cells with >=1 ``mcp__justsearch*``
      call.
    - ``first_mcp_call_index``: median 1-based call-index of the first such
      call across checked cells with at least one (``None`` if none did).
    - ``mcp_call_share``: total ``mcp__justsearch*`` calls / total tool calls,
      pooled across checked cells.

    A ``None`` entry in ``tool_calls_per_cell`` (no capture for that cell) is
    excluded from every denominator here -- the same tri-state discipline as
    ``_tool_call_assertions``: "no tool data" must never read as "checked and
    zero calls."
    """
    checked = [tc for tc in tool_calls_per_cell if tc is not None]
    if not checked:
        return {"adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None}
    adopted_n = sum(1 for tc in checked if _mcp_call_count(tc) > 0)
    first_indices = [idx for tc in checked if (idx := _first_mcp_call_index(tc)) is not None]
    total_mcp_calls = sum(_mcp_call_count(tc) for tc in checked)
    total_calls = sum(len(tc) for tc in checked)
    return {
        "adoption_rate": round(adopted_n / len(checked), 4),
        "first_mcp_call_index": (
            round(_percentile([float(i) for i in first_indices], 0.5), 2)
            if first_indices else None
        ),
        "mcp_call_share": round(total_mcp_calls / total_calls, 4) if total_calls > 0 else None,
    }


# Arm A (baseline) never carries an MCP config -- its adoption metrics are
# always null by construction, not computed from its (always-zero) tool_calls.
_NULL_ADOPTION = {"adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None}


# --- Adoption FUNNEL metrics (tempdoc 725 increment 3) ----------------------
#
# `_adoption_metrics` above answers "did the cell call an mcp__justsearch tool
# at all." The funnel breaks that single question into the pilot's observed
# stages -- offered -> discovered (a `select:` ToolSearch call named a
# justsearch tool) -> invoked (>=1 of those names was actually called
# successfully) -> reinforced (durable use, not a one-off) -- so a near-zero
# adoption_rate can be attributed to a specific stage instead of read as one
# undifferentiated "didn't use it."

_TOOL_SEARCH_TOOL_NAME = "ToolSearch"

_NULL_FUNNEL = {
    "discovery_rate": None,
    "post_discovery_invocation_rate": None,
    "first_discovery_turn": None,
    "reinforced_proxy_rate": None,
    "reinforced_rate": None,
    "funnel_fields_absent": True,
}


def _first_toolsearch_call_index(sequence: list[dict] | None) -> int | None:
    """1-based index of the FIRST ``ToolSearch``-named entry in a cell's full
    ``tool_call_sequence`` (every attempt, in order, regardless of status).

    Used only for cells already classified as "discovered" (non-empty
    ``toolsearch_targets``), under the simplifying assumption that a cell's
    first ``ToolSearch`` call is the discovery event: a per-call correlation
    between a specific ``ToolSearch`` invocation and the names it resolved
    would need the raw call inputs, which the redacted evidence contract
    deliberately does not carry (only the aggregated, pre-filtered
    ``toolsearch_targets`` survives sanitization)."""
    for i, entry in enumerate(sequence or [], start=1):
        if entry.get("name") == _TOOL_SEARCH_TOOL_NAME:
            return i
    return None


def _is_strictly_reinforced(sequence: list[dict] | None) -> bool:
    """Strict reinforced-adoption signal: among a cell's full
    ``tool_call_sequence`` (every attempt, in order, regardless of status),
    find the LAST ``mcp__justsearch*``-named entry. The cell counts as
    strictly reinforced only if that FINAL justsearch-related interaction
    succeeded (``status == "ok"``).

    If the agent's last interaction with the tool was blocked or disallowed --
    and by construction nothing after it, in the full sequence, is another
    justsearch-named event, since it is the LAST one -- the cell's use of the
    tool ended on a failure note and does not count as durable adoption, even
    if an EARLIER call in the same cell executed successfully (that weaker,
    proxy signal is ``reinforced_proxy_rate``, which only counts raw successful
    calls regardless of how the cell's justsearch usage ended)."""
    justsearch_events = [
        entry for entry in (sequence or [])
        if str(entry.get("name", "")).startswith(_MCP_JUSTSEARCH_PREFIX)
    ]
    if not justsearch_events:
        return False
    return justsearch_events[-1].get("status") == "ok"


def _funnel_metrics(
    tool_calls_per_cell: list[list[dict] | None],
    toolsearch_targets_per_cell: list[list[str] | None],
    tool_call_sequences_per_cell: list[list[dict] | None],
) -> dict:
    """Adoption funnel (tempdoc 725 increment 3): offered -> discovered ->
    invoked -> reinforced, derived purely from each cell's already-captured
    ``tool_calls`` / ``toolsearch_targets`` / ``tool_call_sequence``.

    - ``discovery_rate``: fraction of CHECKED cells whose ``toolsearch_targets``
      is non-empty (a ``select:`` ToolSearch call named >=1 justsearch tool).
    - ``post_discovery_invocation_rate``: of the DISCOVERED cells, the fraction
      that also went on to make >=1 successful ``mcp__justsearch*`` call.
    - ``first_discovery_turn``: median 1-based call-index (see
      ``_first_toolsearch_call_index``) of the discovery event, over
      discovered cells.
    - ``reinforced_proxy_rate``: of the INVOKED cells, the fraction with MORE
      THAN ONE successful ``mcp__justsearch*`` call (a cheap repeat-use proxy).
    - ``reinforced_rate``: of the INVOKED cells, the fraction whose final
      justsearch-related interaction succeeded (see ``_is_strictly_reinforced``
      -- the strict version: a cell that called the tool once successfully and
      then let a later blocked attempt be its last word does NOT count).

    A ``None`` entry in ANY of the three parallel per-cell inputs excludes that
    cell from every denominator -- the same tri-state discipline as
    ``_adoption_metrics``/``_tool_call_assertions``: evidence captured before
    these fields existed must read as "no funnel data," never as a fabricated
    zero. When no cell in the record carries funnel data at all,
    ``funnel_fields_absent`` is ``True`` and every rate is ``None``.
    """
    checked = [
        (tc, targets, seq)
        for tc, targets, seq in zip(
            tool_calls_per_cell, toolsearch_targets_per_cell, tool_call_sequences_per_cell)
        if tc is not None and targets is not None and seq is not None
    ]
    if not checked:
        return dict(_NULL_FUNNEL)

    discovered = [(tc, targets, seq) for tc, targets, seq in checked if targets]
    discovery_rate = round(len(discovered) / len(checked), 4)

    invoked = [(tc, targets, seq) for tc, targets, seq in discovered if _mcp_call_count(tc) > 0]
    post_discovery_invocation_rate = (
        round(len(invoked) / len(discovered), 4) if discovered else None
    )

    first_indices = [
        idx for _, _, seq in discovered
        if (idx := _first_toolsearch_call_index(seq)) is not None
    ]
    first_discovery_turn = (
        round(_percentile([float(i) for i in first_indices], 0.5), 2)
        if first_indices else None
    )

    reinforced_proxy_n = sum(1 for tc, _, _ in invoked if _mcp_call_count(tc) > 1)
    reinforced_proxy_rate = (
        round(reinforced_proxy_n / len(invoked), 4) if invoked else None
    )

    reinforced_n = sum(1 for _, _, seq in invoked if _is_strictly_reinforced(seq))
    reinforced_rate = round(reinforced_n / len(invoked), 4) if invoked else None

    return {
        "discovery_rate": discovery_rate,
        "post_discovery_invocation_rate": post_discovery_invocation_rate,
        "first_discovery_turn": first_discovery_turn,
        "reinforced_proxy_rate": reinforced_proxy_rate,
        "reinforced_rate": reinforced_rate,
        "funnel_fields_absent": False,
    }


def _stats_from_pairs(pairs: dict, *, statistical_alpha: float = 0.05) -> dict | None:
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
            "accuracy_delta": float(bool(p["a_correct"])),
            "cost_usd": float(p["a_cost"] or 0.0),
            "unique_tokens": float(p["a_tok"] or 0),
            "num_turns": float(p["a_turns"] or 0),
            "duration": float(p.get("a_dur") or 0.0),
        }
        for obs, p in pairs.items()
    }
    pqm_c = {
        obs: {
            "accuracy_delta": float(bool(p["c_correct"])),
            "cost_usd": float(p["c_cost"] or 0.0),
            "unique_tokens": float(p["c_tok"] or 0),
            "num_turns": float(p["c_turns"] or 0),
            "duration": float(p.get("c_dur") or 0.0),
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
        metrics=["accuracy_delta", "cost_usd", "unique_tokens", "num_turns", "duration"],
        alpha=statistical_alpha,
    )

    a_cost = [p["a_cost"] for p in pairs.values()]
    c_cost = [p["c_cost"] for p in pairs.values()]
    a_tok = [p["a_tok"] for p in pairs.values()]
    c_tok = [p["c_tok"] for p in pairs.values()]
    a_turns = [p["a_turns"] for p in pairs.values()]
    c_turns = [p["c_turns"] for p in pairs.values()]
    a_items = [(p.get("a_dur"), bool(p.get("a_censored"))) for p in pairs.values()]
    c_items = [(p.get("c_dur"), bool(p.get("c_censored"))) for p in pairs.values()]

    result = {
        "accuracy": {
            "baseline": mc["accuracy_a"],
            "with_tool": mc["accuracy_b"],
            "delta": mc["accuracy_delta"],
            "delta_ci95": cont["accuracy_delta"]["ci_95"],
            "delta_ci": cont["accuracy_delta"]["ci"],
            "mcnemar_p": mc["p_value"],
            "mcnemar_test": mc["test"],
            "n_with_tool_fixes": mc["n_b_only_correct"],
            "n_with_tool_breaks": mc["n_a_only_correct"],
            "seed_envelope_baseline": _seed_envelope(per_seed_acc_a),
            "seed_envelope_with_tool": _seed_envelope(per_seed_acc_c),
        },
        # Provider cache-creation input tokens, reported as per-arm distributions.
        # This counter excludes cache reads; it is not a universal unique-content metric.
        "provider_cache_creation_input_tokens": {
            "baseline": _distribution(a_tok),
            "with_tool": _distribution(c_tok),
            "delta_mean": cont["unique_tokens"]["delta"],
            "delta_ci95": cont["unique_tokens"]["ci_95"],
            "delta_ci": cont["unique_tokens"]["ci"],
        },
        # Deprecated v1 compatibility alias. New claim/publication code uses the
        # provider-semantic name above and never describes this as unique content.
        "tokens_unique": {
            "baseline": _distribution(a_tok),
            "with_tool": _distribution(c_tok),
            "delta_mean": cont["unique_tokens"]["delta"],
            "delta_ci95": cont["unique_tokens"]["ci_95"],
            "delta_ci": cont["unique_tokens"]["ci"],
        },
        "cost_usd": {
            "baseline": _distribution(a_cost),
            "with_tool": _distribution(c_cost),
            "delta_mean": cont["cost_usd"]["delta"],
            "delta_ci95": cont["cost_usd"]["ci_95"],
            "delta_ci": cont["cost_usd"]["ci"],
        },
        "turns": {
            "baseline": _distribution(a_turns),
            "with_tool": _distribution(c_turns),
            "delta_mean": cont["num_turns"]["delta"],
        },
        # Pre-registered adoption metrics (tempdoc 624 §M.8 amendment, Step 0
        # item 5): only meaningful for the with-tool arm -- arm A never carries
        # an MCP config, so its adoption is null by construction, not computed
        # from its (always mcp__justsearch-free) tool_calls.
        "adoption": {
            "baseline": dict(_NULL_ADOPTION),
            "with_tool": _adoption_metrics([p["c_tool_calls"] for p in pairs.values()]),
        },
        "n_paired_observations": mc["n_paired"],
    }
    # Duration metric family (tempdoc 624, 2026-07-17 "Time as the third utility
    # axis"): the tokens_unique/cost_usd projection pattern, PLUS mandatory
    # censoring context. OMITTED entirely (not a null-marker) when NO paired cell
    # carries a wall-clock time at all -- a comparison composed purely from
    # pre-duration evidence projects byte-identical to before this key existed
    # (the funnel/exposure precedent). Once ANY cell carries a time, the block
    # always appears, and every arm is built through `_censored_distribution` so
    # a median can never be published without its `n_censored`/`completion_rate`.
    if any(d is not None for d, _ in a_items) or any(d is not None for d, _ in c_items):
        result["duration"] = {
            "baseline": _censored_distribution(a_items),
            "with_tool": _censored_distribution(c_items),
            "delta_mean": cont["duration"]["delta"],
        }
    # Adoption FUNNEL (tempdoc 725 increment 3): same with-tool-arm-only shape
    # as "adoption" above -- arm A is null by construction. OMITTED entirely
    # (not added as a null-marker dict) when NONE of the paired cells carry
    # funnel data at all -- a comparison composed purely from pre-725 evidence
    # must project to a byte-identical record to before this key existed. Once
    # ANY cell carries real toolsearch_targets/tool_call_sequence data, the key
    # always appears (even if every rate legitimately computes to a genuine
    # 0.0 -- that IS signal, unlike the "never captured" case).
    with_tool_funnel = _funnel_metrics(
        [p["c_tool_calls"] for p in pairs.values()],
        [p.get("c_toolsearch_targets") for p in pairs.values()],
        [p.get("c_tool_call_sequence") for p in pairs.values()],
    )
    if not with_tool_funnel["funnel_fields_absent"]:
        result["funnel"] = {
            "baseline": dict(_NULL_FUNNEL),
            "with_tool": with_tool_funnel,
            # tempdoc 736 D13: one-line pointer to the top-level `denominators`
            # declaration -- the rates above are computed over CHECKED cells
            # (the SECONDARY/funnel-conditional denominator), never the ITT
            # n_attempted population. Pure documentation, no rate recomputed.
            "denominator_note": (
                "rates above are computed over CHECKED cells (paired cells "
                "carrying funnel instrumentation) -- the SECONDARY "
                "denominator, conditional on a usable cell; see the "
                "record's top-level `denominators` block."
            ),
        }
    return result


def _arm_comparison(
    baseline: list[dict],
    with_tool: list[dict],
    *,
    stratify_by: dict[str, str] | None = None,
    statistical_alpha: float = 0.05,
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
    pre-stratification behavior.

    ``leak_suspect_cells`` (tempdoc 624 §As-built #7) is always present when a
    result is returned — the additive per-arm honesty field: the (seed, qid)
    observations ``_pair_observations`` excluded because a leak-suspect signal
    fired on one side of the pair. Empty when nothing was flagged, consistent
    with the existing exclusion convention (an arm/cell with zero surviving
    observations returns ``None``, the same as any other all-excluded case)."""
    if not baseline or not with_tool:
        return None  # need both arms to form a comparison

    pairs, leak_suspect_cells = _pair_observations(baseline, with_tool)
    result = _stats_from_pairs(pairs, statistical_alpha=statistical_alpha)
    if result is None:
        return None
    result["leak_suspect_cells"] = leak_suspect_cells

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
            sub_stats = _stats_from_pairs(sub_pairs, statistical_alpha=statistical_alpha)
            if sub_stats is not None:
                by_stratum[label] = sub_stats
        if by_stratum:
            result["stratified"] = {"by_stratum": by_stratum}

    return result


def _corpus_label(corpus: dict) -> str:
    """The corpus-identity label a summary is stratified/namespaced by.

    Shared by the incidental within-cell stratify path
    (:func:`_default_corpus_stratify` — a slug whose summaries carry more
    than one corpus SIGNATURE) and the deliberate cross-corpus composer
    (:func:`compose_utility_cross_corpus` — summaries pooled across dataset
    SLUGs): one derivation, two call sites, so a query's stratum label is
    never computed two different ways."""
    return f"{corpus.get('dataset')}:{corpus.get('signature')}"


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
        label = _corpus_label(s.get("corpus") or {})
        for qid in (s.get("per_query") or {}):
            qid_to_label[qid] = label
    if len({*qid_to_label.values()}) < 2:
        return None
    return qid_to_label


def _namespace_for_cross_corpus(summaries: list[dict]) -> list[dict]:
    """Rewrite ``per_query`` keys + ``manifest.seed`` so summaries pooled from
    MULTIPLE dataset slugs can be pushed through the existing single-cell path
    (:func:`_compose_cell` / :func:`_arm_comparison`) without collision
    (tempdoc 624 §Cross-corpus).

    Two real-world collisions would otherwise corrupt a pooled comparison:

    - **Seed collision.** Every corpus's eval run independently numbers its
      seeds ``0..N``, so pooling summaries as-is would put e.g. the English
      run's seed 0 and the German run's seed 0 into the SAME
      ``_index_by_seed`` bucket — ``_pair_observations`` keeps only the first
      summary per bucket, silently discarding the rest.
    - **Qid collision.** Every corpus's dataset independently numbers its
      queries ``q0, q1, ...``, so a flat ``stratify_by: qid -> label`` map
      (as :func:`_default_corpus_stratify` builds it) could only assign ONE
      label to "q0" — corrupting every other corpus's "q0" stratum.

    Namespacing both by the corpus label (:func:`_corpus_label`) makes each
    (corpus, seed) bucket and each (corpus, qid) key globally unique, so
    :func:`_compose_cell` composes the pooled cell exactly as it would any
    other multi-summary cell, and :func:`_default_corpus_stratify` — called
    downstream, UNCHANGED — recovers precisely this corpus split as the
    per-stratum breakdown. No pairing/statistics code is touched; this only
    reshapes the input.
    """
    out = []
    for s in summaries:
        label = _corpus_label(s.get("corpus") or {})
        pq = s.get("per_query") or {}
        ns = dict(s)
        ns["manifest"] = {**s["manifest"], "seed": f"{label}::{s['manifest'].get('seed')}"}
        ns["per_query"] = {f"{label}::{qid}": v for qid, v in pq.items()}
        out.append(ns)
    return out


def _compose_cell(
    cell_summaries: list[dict], *, statistical_alpha: float = 0.05
) -> dict | None:
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
    cell = _arm_comparison(
        baseline, primary, stratify_by=stratify_by,
        statistical_alpha=statistical_alpha,
    )
    if cell is None:
        return None
    arms: dict = {}
    if substitution:
        arms["substitution_c"] = _arm_comparison(
            baseline, substitution, stratify_by=stratify_by,
            statistical_alpha=statistical_alpha,
        )
    if addition:
        arms["addition_b"] = _arm_comparison(
            baseline, addition, stratify_by=stratify_by,
            statistical_alpha=statistical_alpha,
        )
    cell["arms"] = arms
    cell["primary_arm"] = "addition_b" if addition else "substitution_c"
    if cell["primary_arm"] == "substitution_c":
        cell["headline_caveat"] = (
            "Accuracy shown is the SUBSTITUTION arm (C, file tools disabled) — NOT a deployment "
            "scenario; lead with token-efficiency, not this accuracy (tempdoc 624 §C-4). Run "
            "condition B for the realistic 'addition' number.")
    return cell


def compose_utility_cross_corpus(
    run_summaries: list[dict],
    *,
    composed_at: str,
    external_baselines: dict | None = None,
    coverage: dict | None = None,
    confidence_tier: str = "C",
    contamination_class: str = "unknown",
    governance: dict | None = None,
    statistical_alpha: float = 0.05,
) -> dict:
    """Cross-corpus stratified sibling of :func:`compose_utility` (tempdoc 624
    §Cross-corpus).

    ``compose_utility`` groups ``cell_summaries`` by ``(corpus, agent_model)``
    BEFORE ever calling :func:`_arm_comparison` — so every dataset slug always
    ends up in its own, separately-composed top-level record, and the
    per-stratum McNemar+bootstrap-CI breakdown ``_arm_comparison`` already
    supports via ``stratify_by`` never gets a chance to stratify by the axis a
    caller may actually care about (e.g. an English/German/scan
    battlefield-dimension split) — ``_default_corpus_stratify`` only fires for
    the INCIDENTAL case of one slug spanning multiple corpus signatures within
    a single composition call, never for genuinely distinct dataset slugs.

    This function pools ``cell_summaries`` from MULTIPLE dataset slugs (same
    ``agent_model``) into ONE :func:`_compose_cell` / :func:`_arm_comparison`
    call, reusing :func:`_default_corpus_stratify`'s existing corpus-identity
    derivation (via :func:`_namespace_for_cross_corpus`, which only makes
    seeds/qids collision-free — it does not reimplement pairing, McNemar, or
    bootstrap-CI). The result is ONE record per ``agent_model`` whose
    top-level ``accuracy``/``tokens_unique``/etc. is the POOLED cross-corpus
    number, and whose ``stratified.by_stratum`` gives each corpus its own,
    independently-significance-tested breakdown (§M.8 item 7) — the fallback
    for exactly the situation where every per-corpus pooled record is
    individually non-significant but a cross-corpus view could still reveal
    whether any one corpus carries a real signal.
    """
    if not run_summaries:
        raise UtilityComposeError("no run summaries provided")

    # 1. One harness cohort across the whole record (identical check to compose_utility).
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

    # 2. With-tool arms must share one search-config (identical check).
    search_keys = {
        s["manifest"].get("search_config_cohort_key")
        for s in run_summaries
        if s.get("condition") in WITH_TOOL_CONDITIONS
    }
    search_keys.discard(None)
    if len(search_keys) > 1:
        raise UtilityComposeError(
            "with-tool arms span multiple search configs: "
            f"{sorted(k[:12] for k in search_keys)}",
        )
    search_config = next(iter(search_keys), None)

    corpora = sorted({_corpus_label(s.get("corpus") or {}) for s in run_summaries})
    if len(corpora) < 2:
        raise UtilityComposeError(
            f"cross-corpus composition needs 2+ distinct corpora, got: {corpora}",
        )

    ref = run_summaries[0]["manifest"]
    per_corpus_identity: dict[str, tuple] = {}
    for summary in run_summaries:
        label = _corpus_label(summary.get("corpus") or {})
        manifest = summary["manifest"]
        identity = (
            manifest.get("query_identity"), manifest.get("campaign_identity"),
            manifest.get("corpus_identity"), manifest.get("corpus_certification"),
        )
        prior = per_corpus_identity.setdefault(label, identity)
        if prior != identity:
            raise UtilityComposeError(
                f"cross-corpus stratum {label!r} mixes source identities"
            )
    cohort = {
        "agent_cohort_key": cohort_key,
        "git_sha": ref.get("git_sha"),
        "git_dirty": ref.get("git_dirty"),
        "source_git_state": ref.get("source_git_state"),
        "cli_version": ref.get("cli_version"),
        "mcp_tool_surface_hash": ref.get("mcp_tool_surface_hash"),
        "judge": ref.get("judge"),
        "prompt_template_hash": ref.get("prompt_template_hash"),
        "decoding": ref.get("decoding"),
        "eval_limits": ref.get("eval_limits"),
        "search_config_cohort_key": search_config,
        "hardware": ref.get("hardware"),
        "environment": ref.get("environment"),
        "query_identities": {
            label: identity[0] for label, identity in sorted(per_corpus_identity.items())
        },
        "campaign_identities": {
            label: identity[1] for label, identity in sorted(per_corpus_identity.items())
        },
        "tool_surfacing_mode": _tool_surfacing_mode(run_summaries),
        "executor": _executor_stamp(run_summaries),
    }

    # 3. Pool by agent_model only (NOT by corpus) — every model's cell spans
    #    all corpora, namespaced so _compose_cell's shared machinery can pool
    #    them and _default_corpus_stratify can split them back apart.
    seeds_seen: set = set()
    measured: dict = {}
    for model in sorted({s.get("agent_model") for s in run_summaries}):
        model_summaries = [s for s in run_summaries if s.get("agent_model") == model]
        for s in model_summaries:
            seeds_seen.add(s["manifest"].get("seed"))
        cell = _compose_cell(
            _namespace_for_cross_corpus(model_summaries),
            statistical_alpha=statistical_alpha,
        )
        if cell is not None:
            resolved_models = {
                summary["manifest"].get("agent_model_version")
                for summary in model_summaries
            }
            if len(resolved_models) != 1:
                raise UtilityComposeError(
                    f"cross-corpus cell for {model!r} mixes resolved provider models: "
                    f"{sorted(str(value) for value in resolved_models)!r}"
                )
            cell["identity"] = {
                "corpus_signatures": {
                    canonical_dataset_slug((summary.get("corpus") or {}).get("dataset")):
                        (summary.get("corpus") or {}).get("signature")
                    for summary in model_summaries
                },
                "requested_model_alias": model,
                "resolved_provider_model": next(iter(resolved_models)),
                "query_identities": {
                    label: per_corpus_identity[label][0]
                    for label in sorted(per_corpus_identity)
                },
                "campaign_identities": {
                    label: per_corpus_identity[label][1]
                    for label in sorted(per_corpus_identity)
                },
                "corpus_certifications": {
                    label: per_corpus_identity[label][3]
                    for label in sorted(per_corpus_identity)
                },
            }
            measured[model] = cell

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
        "schema": SCHEMA_CROSS_CORPUS,
        "schema_version": SCHEMA_VERSION,
        "composed_at": composed_at,
        "cohort": cohort,
        "conditions": {
            "baseline": "A (file tools only)",
            "with_tool": "C (JustSearch only)",
            "addition": "B (file tools + JustSearch)",
        },
        "corpora": corpora,
        "measured": measured,
        "seed_count": len([x for x in seeds_seen if x is not None]),
        "confidence_tier": derived_tier,
        "comparability": comparability,
        "coverage": coverage or _default_coverage(contamination_class),
        "external_baselines": external_baselines or {},
    }
