"""Pre-freeze claim-policy dry-run — tempdoc 791 axis 4.

BOTH tempdoc 782 freeze defects were policy-vs-design INCOMPATIBILITIES, and
both were reachable only at run or compose time:

* **BLOCKER-1** — v3 required a ``2_hop`` schema breakdown (``known_schemas``
  ``["1_hop", "2_hop"]``, ``require_all_present``) while every certified hero
  corpus is 100% ``1_hop`` by construction. No run under the frozen design could
  have satisfied ``schema_strata_reported``. Caught pre-launch by a refusal
  gate, but only after the design was frozen.
* **FREEZE DEFECT #2** — ``corpus_certification_complete`` compared
  ``cert.query_count == cell.query_count`` exactly. The 781 certifications
  certify the 50-query committed gold set; the frozen design measures the
  pre-registered 20-qid leading prefix. 50 != 20 on every stratum, so no run
  could ever pass. Caught at COMPOSE, after ~$278 of measured cells.

Both were $0 to find in advance: synthesize a minimal record with the shape the
design declares, evaluate every policy gate against it, and report which gates
can never pass. That is what this module does.

Three verdict categories, and the distinction between them is the whole point:

``structurally-impossible``
    A design-derived precondition can never hold, whatever the run produces.
    Determined by targeted checks over the design, the referenced certification
    files and the referenced gold files -- independently of the synthetic
    evaluation, so a synthesizer bug cannot manufacture one. BLOCKING.
``undetermined``
    A gate failed and the structural analysis cannot say why, or a design fact
    could not be read (a missing certification or gold file). Fail-closed:
    "we could not tell" is never reported as "fine". BLOCKING.
``placeholder``
    A gate that passed only because the synthesizer supplied an optimistic value
    the design does not pin (identity hashes, judge kind, tool-surface capture).
    Advisory, non-blocking, and listed explicitly -- it is what this dry-run
    does NOT vouch for.
"""

from __future__ import annotations

import json
from pathlib import Path

from jseval.utility_claim_policy import (
    SUPPORTED_REQUIREMENTS,
    # A PROJECTION of the gate's own matrix comparison, deliberately not a copy:
    # a dry-run that forked `required_strata_exact`'s key set would drift from
    # the gate it exists to predict -- the representation-drift failure mode.
    _required_projection,
    canonical_digest,
    evaluate_claim,
    policy_digest,
)
from jseval.utility_question_level import (
    METHOD_ID as QUESTION_LEVEL_METHOD_ID,
    MINIMUM_DRAWS as QUESTION_LEVEL_MINIMUM_DRAWS,
    question_level_statistics,
)

SCHEMA = "utility-policy-dryrun.v1"

STRUCTURAL = "structurally-impossible"
UNDETERMINED = "undetermined"
PLACEHOLDER = "placeholder"

# Gates whose inputs this dry-run reads from the DESIGN (or from the real files
# the design references). A failure here is a design-vs-policy fact, so the
# structural analysis owes an explanation; an unexplained failure is
# `undetermined`, never waved through.
DESIGN_DERIVED_GATES = frozenset({
    "policy_resolved", "supported_policy_requirements", "per_stratum_promotion",
    "required_strata_exact", "corpus_certification_complete",
    "schema_strata_reported", "closed_book_at_hero_tier",
    "minimum_seeds", "seed_floor_met", "minimum_paired_observations",
    "contamination_class", "significance_alpha",
    "question_level_primary_reported",
})

# Gates the synthesizer satisfies with values a real run supplies and the design
# does not pin. They are reported as `placeholder` so the report never implies
# the dry-run checked them.
_PLACEHOLDER_NOTES = {
    "source_identity_complete":
        "synthetic git/environment/exposure/MCP identity; a real run must capture its own",
    "captured_search_config": "synthetic search_config_cohort_key",
    "verified_tool_surface": "synthetic tools/list hash, verified on every with-tool cell",
    "verified_exposure_mode": "synthetic exposure mode, verified on every with-tool cell",
    "no_leak_suspect_cells": "assumed zero; the real leak checks run at capture",
    "no_disallowed_tool_calls": "assumed zero; the real assertions run at capture",
    "judge_calibration": "synthetic non-LLM judge; an LLM judge needs a calibration hash",
    "minimum_adoption_rate": "adoption stubbed at 1.0; the run measures it",
    "accuracy_delta_interval": "zero-width placeholder intervals; the run measures them",
    "accuracy_margin_resolved": "read from the policy, not the design",
    "itt_strata_derived": "synthesized as a complete factorial matrix",
    "complete_expected_matrix": "synthesized with zero pending cells",
    "maximum_exclusion_rate": "synthesized with zero exclusions",
    "minimum_paired_retention": "synthesized with full retention",
    "minimum_excluded_jaccard": "synthesized with no exclusions",
    "completion_triple_reported": "synthesized with full completion in both arms",
}

_HEX_FILL = "0" * 64
_GIT_FILL = "0" * 40


class DryRunError(ValueError):
    """The design or policy could not be read well enough to dry-run at all."""


# --------------------------------------------------------------------------
# design projection
# --------------------------------------------------------------------------

def _design_conditions(design: dict) -> list[str]:
    raw = ((design.get("campaign") or {}).get("conditions") or "")
    return [value.strip() for value in str(raw).split(",") if value.strip()]


def _design_qids(design: dict) -> list[str]:
    """The qid vocabulary the HARNESS mints for the design's measured prefix.

    ``agent_utility_inspect.agent_utility_task`` mints ``Sample(id=f"{c}|q{i}")``
    with a 0-BASED ordinal over ``rows[:max_queries]``. ``cells.v1.json``'s own
    ``qids`` list carries the 1-based canonical *labels* (``q0001`` ...), which
    are what the frozen ``qid_list_sha256`` digests -- a different vocabulary for
    a different purpose. Synthesizing the harness's vocabulary is what lets the
    derived-subset-identity leg of the certification gate be exercised for real.
    """
    max_queries = (design.get("campaign") or {}).get("max_queries")
    if not isinstance(max_queries, int) or isinstance(max_queries, bool) or max_queries < 1:
        raise DryRunError("design campaign.max_queries must be a positive integer")
    return [f"q{index}" for index in range(max_queries)]


def _design_projection(design: dict, stratum: dict) -> dict:
    campaign = design.get("campaign") or {}
    return _required_projection({
        "stratum_id": stratum.get("stratum_id"),
        "corpus_member": stratum.get("corpus_member"),
        "dataset": stratum.get("dataset"),
        "size": stratum.get("size"),
        "query_variant": stratum.get("query_variant"),
        "requested_model": campaign.get("model"),
        "query_count": campaign.get("max_queries"),
        "seed_ids": campaign.get("seed_ids"),
    })


def _resolve(repo_root: Path, value: object) -> Path:
    path = Path(str(value))
    return path if path.is_absolute() else repo_root / path


def _gold_schema_census(repo_root: Path, stratum: dict, n_selected: int) -> tuple[dict, str | None]:
    """``question_type`` census over the design's MEASURED prefix of the gold set.

    Returns ``({}, reason)`` -- never a guess -- when the gold file the design
    references is absent or unreadable.
    """
    source = stratum.get("gold_source_cell")
    if not source:
        return {}, "the design declares no gold_source_cell for this stratum"
    path = _resolve(repo_root, source)
    if not path.is_file():
        return {}, f"gold source {path} is not present in this checkout"
    try:
        rows = json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, OSError) as error:
        return {}, f"gold source {path} could not be read: {error}"
    if isinstance(rows, dict):
        rows = rows.get("queries") or []
    if not isinstance(rows, list) or not rows:
        return {}, f"gold source {path} carries no query rows"
    census: dict[str, int] = {}
    for row in rows[:n_selected]:
        name = (row or {}).get("question_type") if isinstance(row, dict) else None
        census[str(name)] = census.get(str(name), 0) + 1
    return census, None


# --------------------------------------------------------------------------
# structural analysis (independent of the synthetic evaluation)
# --------------------------------------------------------------------------

def structural_findings(design: dict, policy: dict, *, repo_root: Path) -> list[dict]:
    """Design-vs-policy incompatibilities, decided WITHOUT the synthetic record.

    Deliberately independent: if this and the synthetic evaluation had one
    source, a synthesizer bug could invent a structural impossibility or hide
    one. Two independent derivations that must agree is the check.
    """
    findings: list[dict] = []

    def finding(gate: str, category: str, detail: str) -> None:
        findings.append({"gate": gate, "category": category, "detail": detail})

    campaign = design.get("campaign") or {}
    strata = list(design.get("strata") or [])
    requirements = policy.get("requirements") or {}
    thresholds = policy.get("thresholds") or {}
    required = list(policy.get("required_strata") or [])
    qids = _design_qids(design)
    seed_ids = list(campaign.get("seed_ids") or [])
    conditions = _design_conditions(design)

    if policy.get("status") != "active" or policy.get("unresolved") or not required:
        finding("policy_resolved", STRUCTURAL,
                f"policy status={policy.get('status')!r} "
                f"unresolved={policy.get('unresolved')!r} "
                f"required_strata={len(required)}")
    unknown = sorted(set(requirements) - set(SUPPORTED_REQUIREMENTS))
    if unknown:
        finding("supported_policy_requirements", STRUCTURAL,
                f"policy declares requirements this harness cannot evaluate: {unknown}")
    if requirements.get("per_stratum_promotion") != "all_required_strata_pass":
        finding("per_stratum_promotion", STRUCTURAL,
                f"unsupported promotion mode {requirements.get('per_stratum_promotion')!r}")

    if conditions != ["A", "B"]:
        finding("itt_strata_derived", STRUCTURAL,
                f"the ITT estimand pairs conditions A and B; the design declares "
                f"{conditions!r}")

    # --- the required matrix -------------------------------------------------
    observed_rows = sorted(
        (_design_projection(design, stratum) for stratum in strata),
        key=lambda item: str(item["stratum_id"]),
    )
    expected_rows = sorted(
        (_required_projection(item) for item in required),
        key=lambda item: str(item["stratum_id"]),
    )
    if observed_rows != expected_rows:
        missing = [row for row in expected_rows if row not in observed_rows]
        extra = [row for row in observed_rows if row not in expected_rows]
        finding("required_strata_exact", STRUCTURAL,
                "the design does not measure the policy's pre-registered matrix; "
                f"policy-only={missing!r} design-only={extra!r}")

    # --- counts the design fixes --------------------------------------------
    minimum_seeds = thresholds.get("minimum_seeds")
    if isinstance(minimum_seeds, int) and len(seed_ids) < minimum_seeds:
        finding("minimum_seeds", STRUCTURAL,
                f"the design runs {len(seed_ids)} seeds; the policy floor is {minimum_seeds}")
    minimum_paired = thresholds.get("minimum_paired_observations")
    attainable = len(seed_ids) * len(qids)
    if isinstance(minimum_paired, int) and attainable < minimum_paired:
        finding("minimum_paired_observations", STRUCTURAL,
                f"the design's complete matrix yields at most {attainable} paired "
                f"observations per stratum ({len(seed_ids)} seeds x {len(qids)} "
                f"queries); the policy floor is {minimum_paired}")
    contamination = campaign.get("contamination_class")
    allowed = requirements.get("contamination_classes") or []
    if contamination not in allowed:
        finding("contamination_class", STRUCTURAL,
                f"the design declares contamination_class={contamination!r}; the "
                f"policy admits {allowed!r}")
    if requirements.get("question_level_primary") and len(qids) < 2:
        finding("question_level_primary_reported", STRUCTURAL,
                f"the design measures {len(qids)} question(s); a question-clustered "
                "test needs at least 2")

    # --- per-stratum, against the REAL referenced files -----------------------
    declared_subsets = {
        str(item.get("stratum_id")): item.get("qid_list_sha256")
        for item in required
    }
    ceiling = thresholds.get("maximum_closed_book_accuracy")
    schema_spec = policy.get("required_schema_strata") or {}
    known_schemas = list(schema_spec.get("known_schemas") or [])

    for stratum in strata:
        label = stratum.get("stratum_id")
        snapshot, reason = _certification_for(repo_root, stratum)
        if snapshot is None:
            finding("corpus_certification_complete", UNDETERMINED,
                    f"{label}: {reason}")
        else:
            findings.extend(
                _certification_findings(
                    stratum, snapshot, len(qids), requirements, declared_subsets
                )
            )
            if requirements.get("closed_book_at_hero_tier"):
                observed = (
                    ((snapshot.get("scientific_gates") or {}).get("closed_book") or {})
                    .get("observed") or {}
                )
                accuracy = observed.get("closed_book_accuracy")
                if not isinstance(accuracy, (int, float)) or isinstance(accuracy, bool):
                    finding("closed_book_at_hero_tier", STRUCTURAL,
                            f"{label}: the certification this design PINS carries no "
                            "measured closed-book accuracy, so no run under it can "
                            "supply one")
                elif ceiling is not None and float(accuracy) > float(ceiling):
                    finding("closed_book_at_hero_tier", STRUCTURAL,
                            f"{label}: the pinned certification's closed-book accuracy "
                            f"{accuracy} exceeds the policy ceiling {ceiling}")

        if requirements.get("schema_strata_reported") and schema_spec.get(
            "require_all_present"
        ):
            census, reason = _gold_schema_census(repo_root, stratum, len(qids))
            if not census:
                finding("schema_strata_reported", UNDETERMINED, f"{label}: {reason}")
            else:
                absent = [name for name in known_schemas if name not in census]
                if absent:
                    finding("schema_strata_reported", STRUCTURAL,
                            f"{label}: the design's measured {len(qids)}-query prefix "
                            f"contains question types {sorted(census)}; the policy "
                            f"requires {known_schemas} and {absent} can never appear "
                            "in a breakdown of this query set")
                if not known_schemas:
                    finding("schema_strata_reported", STRUCTURAL,
                            f"{label}: the policy declares no known_schemas, so the "
                            "gate can never pass")
    return findings


def _certification_for(repo_root: Path, stratum: dict) -> tuple[dict | None, str | None]:
    from jseval.corpus_certify import certification_snapshot

    reference = stratum.get("corpus_certification")
    if not reference:
        return None, "the design declares no corpus_certification for this stratum"
    path = _resolve(repo_root, reference)
    if not path.is_file():
        return None, f"certification {path} is not present in this checkout"
    try:
        return certification_snapshot(
            path,
            dataset=stratum.get("dataset"),
            expected_signature=stratum.get("corpus_signature"),
        ), None
    except (ValueError, OSError) as error:
        return None, f"certification {path} is unusable: {error}"


def _certification_findings(
    stratum: dict,
    snapshot: dict,
    n_selected: int,
    requirements: dict,
    declared_subsets: dict,
) -> list[dict]:
    """The certification gate's legs, evaluated against the design's numbers."""
    label = stratum.get("stratum_id")
    out: list[dict] = []

    def bad(detail: str) -> None:
        out.append({
            "gate": "corpus_certification_complete", "category": STRUCTURAL,
            "detail": f"{label}: {detail}",
        })

    if snapshot.get("query_gold_sha256") != stratum.get("query_gold_sha256"):
        bad("the certification's query_gold_sha256 disagrees with the design's")
    if snapshot.get("member") != stratum.get("corpus_member"):
        bad("the certification's member disagrees with the design's")
    if snapshot.get("size") != stratum.get("size"):
        bad("the certification's size disagrees with the design's")
    if snapshot.get("query_variant") != stratum.get("query_variant"):
        bad("the certification's query_variant disagrees with the design's")

    certified = snapshot.get("query_count")
    if not isinstance(certified, int):
        bad("the certification carries no query_count")
        return out
    if certified == n_selected:
        return out
    # A count mismatch. Under a policy WITHOUT the subset branch this is 782
    # FREEZE DEFECT #2 exactly: an exact-equality rule that no subset run can
    # ever satisfy.
    if not requirements.get("certified_query_subset"):
        bad(f"the certification certifies {certified} queries and the design "
            f"measures {n_selected}; this policy compares the two counts for "
            "EXACT equality, so no run under this design can pass "
            "(tempdoc 782 FREEZE DEFECT #2)")
        return out
    if n_selected > certified:
        bad(f"the design measures {n_selected} queries but the certification "
            f"covers only {certified}; a superset is never a certified subset")
        return out
    declared = declared_subsets.get(str(label))
    if not isinstance(declared, str) or len(declared) != 64:
        bad(f"the design measures a {n_selected}-of-{certified} subset but the "
            "policy pre-registers no qid_list_sha256 for this stratum, and the "
            "subset branch fails closed without one")
        return out
    frozen = stratum.get("qid_list_sha256")
    if frozen != declared:
        bad(f"the policy's pre-registered qid_list_sha256 {declared!r} does not "
            f"match the design's frozen {frozen!r}")
    return out


# --------------------------------------------------------------------------
# synthesis
# --------------------------------------------------------------------------

def synthesize_record(design: dict, policy: dict, *, repo_root: Path) -> dict:
    """A MINIMAL structurally-valid composed record with the design's shape.

    Every number the DESIGN pins (strata, seeds, qids, conditions, corpus
    signatures, certifications, gold schema census) is read from the design or
    from the real file it references. Everything a RUN would supply is stubbed
    optimistically, so a gate that still fails is a design fact rather than a
    missing measurement.
    """
    campaign = design.get("campaign") or {}
    thresholds = policy.get("thresholds") or {}
    requirements = policy.get("requirements") or {}
    qids = _design_qids(design)
    seed_ids = list(campaign.get("seed_ids") or [])
    conditions = _design_conditions(design) or ["A", "B"]
    model = campaign.get("model")
    alpha = thresholds.get("significance_alpha", 0.05)

    expected_cells = [
        f"{condition}|{seed}|{qid}"
        for condition in conditions
        for seed in seed_ids
        for qid in qids
    ]
    n_pairs = len(seed_ids) * len(qids)
    strata_cells = []
    measured: dict = {}
    for stratum in design.get("strata") or []:
        snapshot, _ = _certification_for(repo_root, stratum)
        census, _ = _gold_schema_census(repo_root, stratum, len(qids))
        arm_loss = {
            condition: {
                "n_expected": n_pairs, "n_attempted": n_pairs,
                "n_completed": n_pairs, "n_excluded": 0, "n_pending": 0,
                "exclusion_rate": 0.0,
            }
            for condition in ("A", "B")
        }
        cell = {
            "stratum_id": stratum.get("stratum_id"),
            "corpus_member": stratum.get("corpus_member"),
            "corpus": stratum.get("dataset"),
            "corpus_size": stratum.get("size"),
            "query_variant": stratum.get("query_variant"),
            "corpus_signature": stratum.get("corpus_signature"),
            "model": model,
            "resolved_provider_model": f"{model}-synthetic-resolved",
            "query_identity": {
                "sha256": stratum.get("query_gold_sha256"), "row_count": len(qids),
            },
            "campaign_identity": {"expected_cells": expected_cells},
            "corpus_certification": snapshot or {},
            "seed_ids": sorted(seed_ids),
            "seed_count": len(seed_ids),
            "query_count": len(qids),
            "n_expected_cells": len(expected_cells),
            "n_observed_cells": len(expected_cells),
            "n_pending_cells": 0,
            "n_expected_pairs": n_pairs,
            "n_paired_observations": n_pairs,
            "n_per_protocol_pairs": n_pairs,
            "paired_retention": 1.0,
            "excluded_jaccard": 1.0,
            "per_arm_loss": arm_loss,
            "usage_complete": True,
            # Zero-width placeholder intervals: the run measures these, and a
            # zero difference is the one value that cannot flatter either arm.
            "accuracy": {"delta_ci": [0.0, 0.0], "mcnemar_p": 1.0},
            "provider_cache_creation_input_tokens": {"delta_ci": [0.0, 0.0]},
            "cost_usd": {"delta_ci": [0.0, 0.0]},
            "adoption": {"with_tool": {
                "adopted_cells": n_pairs, "eligible_cells": n_pairs,
                "adoption_rate": 1.0,
            }},
        }
        if requirements.get("question_level_primary"):
            cell["question_level"] = _synthetic_question_level(cell, thresholds, alpha)
        strata_cells.append(cell)
        # The schema breakdown mirrors the DESIGN's gold census, never the
        # policy's known_schemas -- otherwise the synthetic record would satisfy
        # `schema_strata_reported` by construction and BLOCKER-1 would be
        # invisible here as well as at freeze.
        measured.setdefault(stratum.get("dataset"), {})[model] = {
            "primary_arm": "addition_b",
            "n_paired_observations": n_pairs,
            "accuracy": {"delta_ci": [0.0, 0.0]},
            "schema_stratified": {"by_stratum": {
                name: {"n_paired_observations": count,
                       "accuracy": {"delta_ci": [0.0, 0.0]}}
                for name, count in sorted(census.items())
            }},
        }

    with_tool_cells = n_pairs * len(strata_cells)
    return {
        "schema": "utility-comparison-cross-corpus.v1",
        "schema_version": 2,
        "seed_count": len(seed_ids),
        "synthetic": {
            "source": SCHEMA,
            "note": "structural placeholder record; carries no measurement",
        },
        "cohort": {
            "git_sha": _GIT_FILL,
            "git_dirty": False,
            "source_git_state": {
                "tracked_diff_sha256": _HEX_FILL, "untracked_sha256": _HEX_FILL,
                "untracked_count": 0, "dirty": False,
            },
            "environment": {"platform": {
                "system": "synthetic", "release": "0", "machine": "synthetic",
            }},
            "search_config_cohort_key": "synthetic-search-config",
            "mcp_tool_surface_hash": _HEX_FILL,
            "judge": {"kind": "substring-em"},
            "exposure_config": {"exposure_mode": "deferred"},
            "mcp_initialize_identity": {
                "instructions_sha256": _HEX_FILL,
                "server_version": "0.0.0-synthetic",
            },
        },
        "coverage": {"contamination_class": campaign.get("contamination_class")},
        "statistical_alpha": alpha,
        "tool_call_assertions": {"B": {
            "cells_total": with_tool_cells,
            "cells_with_mcp_surface_verified": with_tool_cells,
            "cells_mcp_surface_unverified": 0,
            "cells_with_leak_suspect": 0,
            "cells_with_disallowed_violations": 0,
            "observed_mcp_tool_surface_hashes": [_HEX_FILL],
            "observed_mcp_tool_surface_consistent": True,
            "cells_with_exposure_mode_verified": with_tool_cells,
            "observed_exposure_modes": ["deferred"],
            "observed_exposure_mode_consistent": True,
        }},
        "estimands": {
            "primary": "intention_to_treat",
            "intention_to_treat": {"strata": strata_cells},
            "per_protocol": {"role": "secondary", "source": "measured"},
            "completion": {"role": "secondary", "source": "measured", "strata": [
                {
                    "stratum_id": cell["stratum_id"],
                    "corpus": cell["corpus"],
                    "model": cell["model"],
                    "by_arm": {
                        arm: {
                            "n_expected": n_pairs, "n_attempted": n_pairs,
                            "n_completed": n_pairs, "n_exhausted": 0,
                            "completion_rate": 1.0,
                        }
                        for arm in ("A", "B")
                    },
                }
                for cell in strata_cells
            ]},
        },
        "measured": measured,
    }


def _synthetic_question_level(cell: dict, thresholds: dict, alpha: float) -> dict:
    """Run the REAL v5 producer over a zero-difference paired grid.

    Calling the producer rather than hand-writing a block is what keeps the
    dry-run honest about the v5 gate: if the gate and the producer ever disagree
    about the block's shape, this fails here instead of at compose time -- which
    is the entire lesson of the two 782 freeze defects.
    """
    pairs = {}
    for value in cell["campaign_identity"]["expected_cells"]:
        condition, seed, qid = str(value).split("|", 2)
        if condition != "A":
            continue
        pairs[f"{seed}|{qid}"] = {
            "seed": int(seed), "a_correct": False, "c_correct": False,
        }
    return question_level_statistics(
        pairs,
        seed_material={
            "method": QUESTION_LEVEL_METHOD_ID,
            "stratum_id": cell["stratum_id"],
            "corpus_signature": cell["corpus_signature"],
            "query_identity_sha256": cell["query_identity"]["sha256"],
            "n_expected_cells": len(cell["campaign_identity"]["expected_cells"]),
        },
        alpha=alpha,
        permutation_draws=thresholds.get(
            "minimum_permutation_draws", QUESTION_LEVEL_MINIMUM_DRAWS),
        bootstrap_draws=thresholds.get(
            "minimum_cluster_bootstrap_draws", QUESTION_LEVEL_MINIMUM_DRAWS),
    )


# --------------------------------------------------------------------------
# report
# --------------------------------------------------------------------------

def dryrun(design: dict, policy: dict, *, repo_root: Path) -> dict:
    """Evaluate every policy gate against the design and classify each failure."""
    if not (design.get("strata") or []):
        raise DryRunError("design declares no strata")

    findings = structural_findings(design, policy, repo_root=repo_root)
    by_gate: dict[str, list[dict]] = {}
    for item in findings:
        by_gate.setdefault(item["gate"], []).append(item)

    record = synthesize_record(design, policy, repo_root=repo_root)
    verdict = evaluate_claim(record, policy)

    gates = []
    for item in verdict["gates"]:
        name = item["name"]
        related = by_gate.get(name, [])
        if item["passed"]:
            # A structural finding on a gate the synthetic record PASSES means
            # the two independent derivations disagree. Never silently prefer
            # one: surface it and block.
            if related:
                category = UNDETERMINED
                details = [
                    "the synthetic record passes this gate but the structural "
                    "analysis says it cannot; the dry-run refuses to choose"
                ] + [entry["detail"] for entry in related]
            elif name in _PLACEHOLDER_NOTES:
                category, details = PLACEHOLDER, [_PLACEHOLDER_NOTES[name]]
            else:
                category, details = None, []
        elif related:
            category = (
                STRUCTURAL if all(entry["category"] == STRUCTURAL for entry in related)
                else UNDETERMINED
            )
            details = [entry["detail"] for entry in related]
        elif name in _PLACEHOLDER_NOTES:
            category = UNDETERMINED
            details = [
                "the synthesizer's optimistic placeholder still fails this gate, so "
                "the dry-run cannot say whether a real run could satisfy it: "
                + _PLACEHOLDER_NOTES[name]
            ]
        else:
            category = UNDETERMINED
            details = [
                "this gate failed and the structural analysis offers no explanation; "
                "fail-closed rather than assume a real run would fix it"
            ]
        gates.append({
            "name": name,
            "passed": item["passed"],
            "category": category,
            "details": details,
            "observed": item["observed"],
            "threshold": item["threshold"],
        })

    # Structural findings can also name a gate the policy never emits (e.g. a
    # conditional gate the policy does not declare). Those must not vanish.
    emitted = {item["name"] for item in verdict["gates"]}
    for name, entries in sorted(by_gate.items()):
        if name in emitted:
            continue
        gates.append({
            "name": name,
            "passed": None,
            "category": UNDETERMINED,
            "details": [
                "the structural analysis flagged this gate but the policy never "
                "emits it, so the finding could not be reconciled"
            ] + [entry["detail"] for entry in entries],
            "observed": None,
            "threshold": None,
        })

    blocking = [item for item in gates if item["category"] in (STRUCTURAL, UNDETERMINED)]
    return {
        "schema": SCHEMA,
        "policy_id": policy.get("policy_id"),
        "policy_hash": policy_digest(policy),
        "policy_status": policy.get("status"),
        "design": {
            "schema": design.get("schema"),
            "tempdoc": (design.get("protocol") or {}).get("tempdoc"),
            "frozen_policy_id": (design.get("protocol") or {}).get("policy_id"),
            "n_strata": len(design.get("strata") or []),
            "stratum_ids": [
                item.get("stratum_id") for item in design.get("strata") or []
            ],
            "seed_ids": list((design.get("campaign") or {}).get("seed_ids") or []),
            "max_queries": (design.get("campaign") or {}).get("max_queries"),
            "conditions": _design_conditions(design),
            "model": (design.get("campaign") or {}).get("model"),
        },
        "synthetic_record_digest": canonical_digest(record),
        "synthetic_verdict": {
            "status": verdict["status"],
            "outcome": verdict["outcome"],
            "_note": "the outcome of PLACEHOLDER measurements; it predicts nothing "
                     "about what a real run would find",
        },
        "gates": gates,
        "counts": {
            "total": len(gates),
            "passed": sum(1 for item in gates if item["passed"] is True),
            STRUCTURAL: sum(1 for item in gates if item["category"] == STRUCTURAL),
            UNDETERMINED: sum(1 for item in gates if item["category"] == UNDETERMINED),
            PLACEHOLDER: sum(1 for item in gates if item["category"] == PLACEHOLDER),
        },
        "compatible": not blocking,
        "blocking_gates": [item["name"] for item in blocking],
        "exit_code": 1 if blocking else 0,
    }


def load_design(path: str | Path) -> dict:
    design = json.loads(Path(path).read_text(encoding="utf-8"))
    if design.get("schema") != "782-hero-campaign-cells.v1":
        raise DryRunError(
            "unsupported design schema "
            f"{design.get('schema')!r}; expected 782-hero-campaign-cells.v1"
        )
    return design


def format_report(report: dict) -> str:
    lines = [
        f"policy   {report['policy_id']} ({report['policy_status']})",
        f"design   {report['design']['schema']} — "
        f"{report['design']['n_strata']} strata x "
        f"{len(report['design']['seed_ids'])} seeds x "
        f"{report['design']['max_queries']} queries, model "
        f"{report['design']['model']!r}",
        "",
    ]
    for item in report["gates"]:
        if item["passed"] and item["category"] is None:
            mark = "PASS"
        elif item["passed"]:
            mark = f"PASS[{item['category']}]"
        else:
            mark = f"FAIL[{item['category']}]"
        lines.append(f"  {mark:<28} {item['name']}")
        for detail in item["details"]:
            lines.append(f"       {detail}")
    counts = report["counts"]
    lines += [
        "",
        f"{counts['passed']}/{counts['total']} gates pass  |  "
        f"{counts[STRUCTURAL]} structurally impossible  |  "
        f"{counts[UNDETERMINED]} undetermined  |  "
        f"{counts[PLACEHOLDER]} passed on placeholders",
    ]
    lines.append(
        "COMPATIBLE — every gate is reachable under this design"
        if report["compatible"] else
        "INCOMPATIBLE — " + ", ".join(report["blocking_gates"])
    )
    return "\n".join(lines)
