"""Question-level (cluster-aware) primary statistics — tempdoc 791 axis 4.

The 782 hero record's headline significance was a CELL-level exact McNemar over
the ``(seed, qid)`` grid. That treats the 3 seeds of one question as three
independent observations, which they are not: seeds are replicates *within* a
question cluster, so the cell-level test inflates ``n`` by the replicate factor
and understates ``p``. On the decisive stratum the two disagree materially —
cell-level ``p = 0.045`` versus a question-level sign-flip permutation
``p = 0.136`` — and every question-cluster bootstrap interval crosses zero
(``tmp/hero-arc-analysis/stats/heterogeneity.v1.json``, summarized in tempdoc
782 §I second-pass).

Claim policy ``agent-utility-public-v5`` therefore makes the question-level
statistics the PRE-REGISTERED PRIMARY, and the cell-level McNemar stays in the
record as a descriptive companion. Two estimators, both taking ``qid`` as the
resampling unit — the property a ``correct ~ arm + (1|qid)`` random intercept
would buy, without the mixed-model dependency:

* a paired **sign-flip permutation test** over the per-question mean deltas, and
* a **question-cluster bootstrap** interval (BCa where computable, percentile
  otherwise, with the method named in the record).

Everything here is deterministic. The RNG is seeded from a digest of
record-resident identity material and both the seed and its recipe are written
into the record, so no ``Date``/ambient entropy can reach a published number and
any reviewer can re-derive it byte-for-byte.
"""

from __future__ import annotations

import hashlib
import math
import random
from statistics import NormalDist

METHOD_ID = "paired-signflip-permutation+question-cluster-bootstrap.v1"

RNG_ALGORITHM = "python random.Random(int) (Mersenne Twister)"
RNG_SEED_RECIPE = (
    "int(sha256(canonical_json(rng_seed_material)).hexdigest()[:16], 16); "
    "permutation stream uses rng_seed, bootstrap stream uses rng_seed + 1"
)

# Floor on resampling effort. A policy may demand more (thresholds
# `minimum_permutation_draws` / `minimum_cluster_bootstrap_draws`); it may not
# demand less than a test that can resolve alpha=0.05 at all.
MINIMUM_DRAWS = 20_000


def _quantile(ordered: list[float], q: float) -> float:
    """Empirical inverse-CDF order statistic of an ASCENDING-sorted sample."""
    if not ordered:
        raise ValueError("no bootstrap replicates")
    index = math.ceil(q * len(ordered)) - 1
    return ordered[min(len(ordered) - 1, max(0, index))]


def derive_rng_seed(material: dict) -> int:
    """Content-address the RNG seed from record-resident identity material.

    Deterministic by construction: the same record always yields the same seed,
    and the material is written into the record beside it so the derivation is
    auditable rather than asserted.
    """
    from jseval.utility_claim_policy import canonical_bytes

    return int(hashlib.sha256(canonical_bytes(material)).hexdigest()[:16], 16)


def per_question_deltas(pairs: dict) -> dict[str, dict]:
    """``qid -> {n_seeds, rate_baseline, rate_with_tool, delta}``.

    ``pairs`` is the ITT paired set keyed ``"{seed}|{qid}"`` (the key
    ``utility_recompose._intention_to_treat_estimand`` mints). The per-question
    delta is the mean over that question's seed replicates, which is exactly the
    cluster-level statistic the resampling below treats as one unit.
    """
    by_qid: dict[str, list[dict]] = {}
    for key, pair in pairs.items():
        qid = str(key).split("|", 1)[1] if "|" in str(key) else str(key)
        by_qid.setdefault(qid, []).append(pair)
    out: dict[str, dict] = {}
    for qid, items in by_qid.items():
        n = len(items)
        a = sum(1 for item in items if item.get("a_correct"))
        c = sum(1 for item in items if item.get("c_correct"))
        out[qid] = {
            "n_seeds": n,
            "rate_baseline": a / n,
            "rate_with_tool": c / n,
            "delta": (c - a) / n,
        }
    return out


def _signflip_p_two_sided(deltas: list[float], draws: int, rng: random.Random) -> float:
    """Paired sign-flip permutation over per-question deltas.

    Under the null the arm label carries no information, so each QUESTION's
    delta is equally likely to have come out with the opposite sign. Flipping
    whole clusters (never individual cells) is what keeps the replicate
    structure out of the null distribution. ``(hits + 1) / (draws + 1)`` is the
    standard conservative Monte-Carlo p-value: it can never report 0.
    """
    observed = abs(sum(deltas) / len(deltas))
    hits = 0
    for _ in range(draws):
        total = 0.0
        for delta in deltas:
            total += delta if rng.random() < 0.5 else -delta
        if abs(total / len(deltas)) >= observed - 1e-12:
            hits += 1
    return (hits + 1) / (draws + 1)


def _cluster_bootstrap(
    deltas: list[float], draws: int, rng: random.Random
) -> list[float]:
    """Resample QUESTIONS with replacement; each carries its seed replicates.

    A question's replicates are already collapsed into its cluster statistic
    (``per_question_deltas``), so drawing the question draws its replicates with
    it — the cluster bootstrap, not a cell bootstrap.
    """
    n = len(deltas)
    replicates = []
    for _ in range(draws):
        total = 0.0
        for _ in range(n):
            total += deltas[rng.randrange(n)]
        replicates.append(total / n)
    replicates.sort()
    return replicates


def _bca_interval(
    deltas: list[float], replicates: list[float], observed: float, alpha: float
) -> tuple[list[float], str, str | None]:
    """BCa interval over the cluster bootstrap, or percentile when undefined.

    Returns ``(interval, method, fallback_reason)``. BCa is skipped — never
    faked — when the bias correction or the acceleration is not computable:
    every bootstrap replicate on one side of the observed value leaves ``z0``
    infinite, and a jackknife with zero spread leaves the acceleration 0/0.
    """
    lower_q, upper_q = alpha / 2.0, 1.0 - alpha / 2.0
    percentile = [_quantile(replicates, lower_q), _quantile(replicates, upper_q)]

    n_below = sum(1 for value in replicates if value < observed)
    proportion = n_below / len(replicates)
    if proportion <= 0.0 or proportion >= 1.0:
        return percentile, "percentile", (
            "bias correction undefined: every bootstrap replicate lies on one "
            "side of the observed statistic"
        )
    normal = NormalDist()
    z0 = normal.inv_cdf(proportion)

    total = sum(deltas)
    n = len(deltas)
    if n < 2:
        return percentile, "percentile", "acceleration undefined: fewer than 2 clusters"
    jackknife = [(total - value) / (n - 1) for value in deltas]
    jack_mean = sum(jackknife) / n
    residuals = [jack_mean - value for value in jackknife]
    squared = sum(value * value for value in residuals)
    if squared <= 0.0:
        return percentile, "percentile", (
            "acceleration undefined: the jackknife distribution has zero spread"
        )
    acceleration = sum(value ** 3 for value in residuals) / (6.0 * squared ** 1.5)

    adjusted = []
    for quantile in (lower_q, upper_q):
        z = normal.inv_cdf(quantile)
        denominator = 1.0 - acceleration * (z0 + z)
        if denominator == 0.0:
            return percentile, "percentile", (
                "acceleration correction is singular for this sample"
            )
        adjusted.append(normal.cdf(z0 + (z0 + z) / denominator))
    if not (0.0 < adjusted[0] < adjusted[1] < 1.0):
        return percentile, "percentile", (
            "BCa-adjusted quantiles left the open unit interval"
        )
    return (
        [_quantile(replicates, adjusted[0]), _quantile(replicates, adjusted[1])],
        "BCa",
        None,
    )


def question_level_statistics(
    pairs: dict,
    *,
    seed_material: dict,
    alpha: float = 0.05,
    permutation_draws: int = MINIMUM_DRAWS,
    bootstrap_draws: int = MINIMUM_DRAWS,
) -> dict | None:
    """The v5 primary accuracy block for ONE stratum, or ``None`` if underivable.

    Returns ``None`` — never a permissive placeholder — when the stratum has no
    paired observations at all. A single-question stratum still returns a block,
    with an honest degenerate interval; the policy gate is what refuses it.
    """
    if not pairs:
        return None
    if permutation_draws < 1 or bootstrap_draws < 1:
        raise ValueError("permutation and bootstrap draws must be positive")

    per_question = per_question_deltas(pairs)
    qids = sorted(per_question, key=lambda value: (len(value), value))
    deltas = [per_question[qid]["delta"] for qid in qids]
    observed = sum(deltas) / len(deltas)
    replicate_counts = [per_question[qid]["n_seeds"] for qid in qids]

    rng_seed = derive_rng_seed(seed_material)
    permutation_seed = rng_seed
    bootstrap_seed = rng_seed + 1
    signflip_p = _signflip_p_two_sided(
        deltas, permutation_draws, random.Random(permutation_seed)
    )
    replicates = _cluster_bootstrap(
        deltas, bootstrap_draws, random.Random(bootstrap_seed)
    )
    interval, ci_method, fallback_reason = _bca_interval(
        deltas, replicates, observed, alpha
    )
    # The plain percentile interval is recorded beside the primary one for the
    # same reason the cell-level McNemar is: a reviewer must be able to see the
    # estimator that was NOT promoted. On the hero cohort the two differ by at
    # most one lattice step of the discrete cluster statistic (measured across 8
    # independent RNG streams: both are Monte-Carlo stable to <= 1/60, and BCa
    # shifts location rather than narrowing width).
    percentile_interval = [
        _quantile(replicates, alpha / 2.0), _quantile(replicates, 1.0 - alpha / 2.0)
    ]

    block = {
        "method": METHOD_ID,
        "unit": "qid",
        "n_qids": len(qids),
        "n_paired_cells": len(pairs),
        "n_seed_replicates_min": min(replicate_counts),
        "n_seed_replicates_max": max(replicate_counts),
        "aggregate_delta": observed,
        "signflip_p_two_sided": signflip_p,
        "permutation_draws": permutation_draws,
        "delta_ci": interval,
        "ci_alpha": alpha,
        "ci_method": ci_method,
        "delta_ci_percentile": percentile_interval,
        "bootstrap_draws": bootstrap_draws,
        "per_question_delta": {qid: per_question[qid]["delta"] for qid in qids},
        "rng_seed": rng_seed,
        "rng_seed_permutation": permutation_seed,
        "rng_seed_bootstrap": bootstrap_seed,
        "rng_algorithm": RNG_ALGORITHM,
        "rng_seed_recipe": RNG_SEED_RECIPE,
        "rng_seed_material": dict(seed_material),
        "cell_level_mcnemar_role": "descriptive",
    }
    if fallback_reason:
        block["ci_method_fallback_reason"] = fallback_reason
    return block
