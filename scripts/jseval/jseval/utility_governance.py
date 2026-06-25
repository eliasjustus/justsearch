"""Run-governance for the agent-utility eval (tempdoc 624 §Run-governance design).

A governed record produced by an *ungoverned run* cannot vouch for itself. The
floor run shipped with a hand-set tier and **no comparability verdict** despite
13 %/27 % **asymmetric** timeout exclusion (confidence pass #3 A2: Jaccard 0.42,
paired-n retention 66 %). This module gives the run **loss-accounting** + a
**comparability verdict**, conforming to the retrieval eval's seam
(`comparability.determine_comparability`) — reused **per arm** — plus the one
thing the single-arm gate genuinely lacks: the **paired exclusion asymmetry**
that biases a paired A/C comparison.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from jseval.comparability import determine_comparability
from jseval.types import AnnProofResult, ComparabilityResult, ReadinessResult

# Condition semantics (tempdoc 346): A = baseline (file tools), C = with-tool.
_BASELINE = "A"


@dataclass
class ArmLoss:
    """Per-arm execution loss-accounting (excluded = solver errors / timeouts)."""

    condition: str
    n_seeds: int
    n_queries: int                       # distinct queries per seed
    n_completed: int
    excluded_query_ids: set = field(default_factory=set)   # excluded in >=1 seed
    ok_by_seed: dict = field(default_factory=dict)         # seed -> {completed query ids}

    @property
    def n_attempted(self) -> int:
        return self.n_seeds * self.n_queries

    @property
    def n_excluded(self) -> int:
        return self.n_attempted - self.n_completed

    @property
    def exclusion_rate(self) -> float:
        return self.n_excluded / self.n_attempted if self.n_attempted else 0.0


def compute_loss_accounting(log_dir: str) -> dict[str, ArmLoss]:
    """Per-arm loss-accounting from the Inspect EvalLogs (one task per condition).

    Reads the same logs `eval_logs_to_summaries` reads, but **counts the excluded
    (errored/timed-out) cells** the projection drops. Returns ``{condition: ArmLoss}``.
    """
    from inspect_ai.log import read_eval_log

    arms: dict[str, ArmLoss] = {}
    files = sorted(Path(log_dir).glob("*.eval")) + sorted(Path(log_dir).glob("*.json"))
    for lf in files:
        if lf.name in ("eval-set.json", "logs.json"):
            continue
        try:
            log = read_eval_log(lf.as_posix())
        except Exception:
            continue
        if not getattr(log, "eval", None):
            continue
        cond = (log.eval.metadata or {}).get("condition")
        if cond is None:
            continue
        completed = 0
        excluded_ids: set = set()
        ok_by_seed: dict = {}
        all_q: set = set()
        seeds: set = set()
        for s in (log.samples or []):
            qid = str(s.id)
            seed = int(s.epoch or 1) - 1
            all_q.add(qid)
            seeds.add(seed)
            if (s.metadata or {}).get("error"):
                excluded_ids.add(qid)
            else:
                completed += 1
                ok_by_seed.setdefault(seed, set()).add(qid)
        arms[cond] = ArmLoss(
            condition=cond, n_seeds=len(seeds) or 1, n_queries=len(all_q),
            n_completed=completed, excluded_query_ids=excluded_ids, ok_by_seed=ok_by_seed,
        )
    return arms


def paired_comparability(
    arms: dict[str, ArmLoss],
    readiness: ReadinessResult | None = None,
    *,
    max_exclusion_rate: float = 0.15,
    min_paired_retention: float = 0.70,
    min_excluded_jaccard: float = 0.50,
) -> tuple[ComparabilityResult, dict]:
    """Per-arm comparability (reuse) + the paired-asymmetry extension.

    Per arm: ``comparability.determine_comparability`` with ``ann_proof=NOT_APPLICABLE``
    and ``error_rate = exclusion_rate`` (confidence pass #3 A1 — verified reusable).
    Paired: the **excluded-set Jaccard** (low = the arms drop *different* queries =
    asymmetric bias) and the **paired-n retention** (queries both arms completed,
    per seed). The floor (A 13 % / C 27 %, Jaccard 0.42, retention 66 %) → *not
    comparable* under the defaults.
    """
    rd = readiness or ReadinessResult(passed=True)
    ann_na = AnnProofResult(status="NOT_APPLICABLE")
    reasons: list[str] = []

    for cond, loss in sorted(arms.items()):
        r = determine_comparability(
            rd, ann_na, loss.n_excluded, loss.n_attempted, max_error_rate=max_exclusion_rate)
        reasons += [f"arm_{cond}: {x}" for x in r.reasons]

    metrics: dict = {
        "per_arm_exclusion_rate": {c: round(l.exclusion_rate, 4) for c, l in arms.items()},
    }

    # The paired axis (only meaningful with both a baseline and a with-tool arm).
    with_tool = next((c for c in arms if c != _BASELINE), None)
    if _BASELINE in arms and with_tool:
        a, c = arms[_BASELINE], arms[with_tool]
        ea, ec = a.excluded_query_ids, c.excluded_query_ids
        union = ea | ec
        jaccard = round(len(ea & ec) / len(union), 4) if union else 1.0
        seeds = set(a.ok_by_seed) & set(c.ok_by_seed)
        retentions = [
            len(a.ok_by_seed[s] & c.ok_by_seed[s]) / a.n_queries
            for s in seeds if a.n_queries
        ]
        retention = round(sum(retentions) / len(retentions), 4) if retentions else 0.0
        metrics.update({"excluded_jaccard": jaccard, "paired_n_retention": retention})
        if jaccard < min_excluded_jaccard:
            reasons.append(
                f"asymmetric_exclusion: excluded_jaccard={jaccard} < {min_excluded_jaccard} "
                f"(arms dropped different queries -> biased per-arm distributions)")
        if retention < min_paired_retention:
            reasons.append(
                f"low_paired_retention: {retention} < {min_paired_retention} "
                f"(too few both-arms-completed queries)")

    return ComparabilityResult(comparable=len(reasons) == 0, reasons=reasons), metrics
