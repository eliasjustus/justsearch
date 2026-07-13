"""Pure offline finalizer for agent-utility evidence (tempdoc 719).

This module performs no backend, credential, model, or judge calls. All command
surfaces that need a canonical record should converge here.
"""

from __future__ import annotations

import copy
import datetime as dt
import hashlib
import json
from pathlib import Path
from typing import Iterable

from jseval.agent_utility_observations import (
    read_inspect_observations,
    successful_summaries,
)
from jseval.utility_comparison import (
    CITED_BASELINES,
    compose_utility,
    compose_utility_cross_corpus,
)
from jseval.utility_governance import (
    loss_accounting_from_observations,
    paired_comparability,
)

_VOLATILE_SEMANTIC_FIELDS = frozenset({"composed_at", "semantic_digest"})


def _canonical_bytes(value: object) -> bytes:
    return json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    ).encode("utf-8")


def semantic_projection(record: dict) -> dict:
    """Return the record with only the explicit volatile transport set removed."""
    projected = copy.deepcopy(record)
    for field in _VOLATILE_SEMANTIC_FIELDS:
        projected.pop(field, None)
    return projected


def semantic_digest(record: dict) -> str:
    return hashlib.sha256(_canonical_bytes(semantic_projection(record))).hexdigest()


def _load_overlay(log_dir: Path, explicit: str | Path | None) -> dict | None:
    path = Path(explicit) if explicit else log_dir / "judge-overlay.json"
    if not path.is_file():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def _namespace_for_loss(observations: Iterable[dict], namespace: str) -> list[dict]:
    out = []
    for observation in observations:
        item = dict(observation)
        item["qid"] = f"{namespace}::{observation.get('qid')}"
        out.append(item)
    return out


def finalize_logs(
    log_dirs: Iterable[str | Path],
    *,
    judge_overlays: Iterable[str | Path | None] | None = None,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
    readiness=None,
    search_config_cohort_key: str | None = None,
    leaked_cells_by_log: Iterable[set[tuple[str, int, str]] | dict] | None = None,
) -> dict:
    """Recompose completed Inspect logs into one canonical scientific record."""
    roots = [Path(path) for path in log_dirs]
    if not roots:
        raise ValueError("at least one log directory is required")
    overlays = list(judge_overlays or [])
    if overlays and len(overlays) != len(roots):
        raise ValueError("judge_overlays must be empty or match log_dirs one-for-one")
    leak_sets = list(leaked_cells_by_log or [])
    if leak_sets and len(leak_sets) != len(roots):
        raise ValueError("leaked_cells_by_log must be empty or match log_dirs one-for-one")

    observation_groups: list[list[dict]] = []
    for index, root in enumerate(roots):
        overlay = _load_overlay(root, overlays[index] if overlays else None)
        observations = read_inspect_observations(root, judge_overlay=overlay)
        if not observations:
            raise ValueError(f"no Inspect observations found in {root}")
        if leak_sets:
            leaked = leak_sets[index]
            for observation in observations:
                key = (
                    observation.get("condition"),
                    int(observation.get("seed", 0)),
                    str(observation.get("qid")),
                )
                serialized_key = f"{key[0]}|{key[1]}|{key[2]}"
                if key in leaked or serialized_key in leaked:
                    observation["leak_suspect"] = True
        observation_groups.append(observations)
    return finalize_observation_groups(
        observation_groups,
        composed_at=composed_at,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
        readiness=readiness,
        search_config_cohort_key=search_config_cohort_key,
    )


def finalize_evidence(
    evidence_paths: Iterable[str | Path],
    *,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
) -> dict:
    from jseval.utility_evidence import read_evidence

    groups = [read_evidence(path) for path in evidence_paths]
    return finalize_observation_groups(
        groups,
        composed_at=composed_at,
        contamination_class=contamination_class,
        confidence_tier=confidence_tier,
    )


def finalize_observation_groups(
    observation_groups: Iterable[list[dict]],
    *,
    composed_at: str | None = None,
    contamination_class: str = "unknown",
    confidence_tier: str = "C",
    readiness=None,
    search_config_cohort_key: str | None = None,
) -> dict:
    summaries: list[dict] = []
    loss_observations: list[dict] = []
    corpus_identities: set[tuple[str | None, str | None]] = set()
    for raw_group in observation_groups:
        if not raw_group:
            raise ValueError("an evidence group contains no observations")
        # One sanitized evidence file may intentionally carry several corpora.
        # Partition before summary projection so no group silently inherits the
        # first observation's corpus identity.
        by_corpus: dict[tuple[str | None, str | None], list[dict]] = {}
        for observation in raw_group:
            corpus = (observation.get("source") or {}).get("corpus") or {}
            identity = (corpus.get("dataset"), corpus.get("signature"))
            by_corpus.setdefault(identity, []).append(observation)
        for corpus_identity, observations in sorted(by_corpus.items(), key=lambda item: str(item[0])):
            summaries.extend(successful_summaries(
                observations,
                search_config_cohort_key=search_config_cohort_key,
            ))
            corpus_identities.add(corpus_identity)
            namespace = f"{corpus_identity[0]}:{corpus_identity[1]}"
            loss_observations.extend(_namespace_for_loss(observations, namespace))

    arms = loss_accounting_from_observations(loss_observations)
    verdict, metrics = paired_comparability(arms, readiness)
    governance = {
        "comparable": verdict.comparable,
        "reasons": verdict.reasons,
        "metrics": metrics,
        "per_arm_loss": {
            condition: {
                "n_attempted": loss.n_attempted,
                "n_completed": loss.n_completed,
                "n_excluded": loss.n_excluded,
                "exclusion_rate": round(loss.exclusion_rate, 4),
            }
            for condition, loss in arms.items()
        },
    }
    timestamp = composed_at or dt.datetime.now(dt.timezone.utc).isoformat()
    kwargs = {
        "composed_at": timestamp,
        "contamination_class": contamination_class,
        "confidence_tier": confidence_tier,
        "governance": governance,
        "external_baselines": CITED_BASELINES,
    }
    if len(corpus_identities) > 1:
        record = compose_utility_cross_corpus(summaries, **kwargs)
    else:
        record = compose_utility(summaries, **kwargs)
    from jseval.utility_claim_policy import evaluate_claim

    record["claim_verdict"] = evaluate_claim(record)
    record["semantic_digest"] = semantic_digest(record)
    return record


def write_record(record: dict, output_dir: str | Path) -> Path:
    root = Path(output_dir)
    root.mkdir(parents=True, exist_ok=True)
    filename = f"{record['schema']}.json"
    path = root / filename
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
