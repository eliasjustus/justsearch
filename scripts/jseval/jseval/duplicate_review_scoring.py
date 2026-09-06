"""Calibration-only threshold selection and frozen holdout evaluation."""

from __future__ import annotations

import hashlib
import json
import math
import random
from collections import Counter, defaultdict
from copy import deepcopy
from pathlib import Path
from typing import Any, Mapping, Sequence

from . import duplicate_review_labels as review_labels
from . import duplicate_review_packet as review_packet


SCHEMA = "jseval.duplicate-review-decision.v1"
SELECTION_OBJECTIVE = "max-horvitz-thompson-weighted-f1-then-precision-then-threshold-v1"
PREDICTION_RULE = "simhash-candidate-and-full-shingle-jaccard-at-or-above-threshold-v1"


class DuplicateReviewScoringError(ValueError):
    """Complete bound labels cannot produce a valid calibration decision."""


def _canonical_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise DuplicateReviewScoringError("decision data must be canonical UTF-8 JSON") from exc


def _digest(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _validated_artifact_bytes(value: object, *, role: str) -> bytes:
    if not isinstance(value, dict) or value.get("schema") != SCHEMA:
        raise DuplicateReviewScoringError(f"{role} is not a {SCHEMA} artifact")
    artifact_hash = value.get("artifact_hash")
    if not isinstance(artifact_hash, str) or len(artifact_hash) != 64:
        raise DuplicateReviewScoringError(f"{role} artifact hash is malformed")
    unhashed = {key: item for key, item in value.items() if key != "artifact_hash"}
    if artifact_hash != _digest(unhashed):
        raise DuplicateReviewScoringError(f"{role} artifact hash is invalid")
    return _canonical_bytes(value)


def _metric(numerator: float, denominator: float) -> float | None:
    return numerator / denominator if denominator > 0 else None


def _score_records(records: Sequence[Mapping[str, Any]], threshold: float) -> dict[str, Any]:
    raw = Counter(tp=0, fp=0, tn=0, fn=0)
    weighted = Counter(tp=0.0, fp=0.0, tn=0.0, fn=0.0)
    for record in records:
        actual = record["label"] == "NEAR_DUPLICATE"
        predicted = (
            record["sampling_frame"] == "candidate"
            and record["similarity"] >= threshold
        )
        bucket = "tp" if actual and predicted else "fn" if actual else "fp" if predicted else "tn"
        raw[bucket] += 1
        weighted[bucket] += record["sampling_weight"]
    precision = _metric(weighted["tp"], weighted["tp"] + weighted["fp"])
    recall = _metric(weighted["tp"], weighted["tp"] + weighted["fn"])
    f1 = (
        2 * precision * recall / (precision + recall)
        if precision is not None and recall is not None and precision + recall > 0
        else 0.0 if precision is not None and recall is not None else None
    )
    return {
        "raw_confusion": {key: int(raw[key]) for key in ("tp", "fp", "tn", "fn")},
        "weighted_confusion": {key: weighted[key] for key in ("tp", "fp", "tn", "fn")},
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }


def _percentile(values: Sequence[float], quantile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    position = quantile * (len(ordered) - 1)
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] * (1 - fraction) + ordered[upper] * fraction


def _bootstrap(
    records: Sequence[Mapping[str, Any]],
    threshold: float,
    *,
    draws: int,
    seed_material: object,
) -> dict[str, Any]:
    strata: dict[str, list[Mapping[str, Any]]] = defaultdict(list)
    for record in records:
        key = json.dumps(
            [record["sampling_frame"], record["stratum"]],
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
        )
        strata[key].append(record)
    rng = random.Random(int(_digest(seed_material), 16))
    samples: dict[str, list[float]] = {"precision": [], "recall": [], "f1": []}
    for _ in range(draws):
        resampled = [
            rng.choice(group)
            for key in sorted(strata)
            for group in (strata[key],)
            for _item in range(len(group))
        ]
        scored = _score_records(resampled, threshold)
        for metric in samples:
            value = scored[metric]
            if value is not None:
                samples[metric].append(value)
    metrics = {}
    for metric, values in samples.items():
        invalid = draws - len(values)
        metrics[metric] = {
            "status": "OK" if invalid == 0 else "UNDEFINED_IN_SOME_DRAWS",
            "lower": _percentile(values, 0.025) if invalid == 0 else None,
            "upper": _percentile(values, 0.975) if invalid == 0 else None,
            "valid_draws": len(values),
            "invalid_draws": invalid,
        }
    return {
        "method": "within-stratum-nonparametric-percentile-no-draw-dropping-v1",
        "requested_draws": draws,
        "metrics": metrics,
    }


def _validated_rows(session: review_labels.DuplicateReviewSession) -> list[dict[str, Any]]:
    label_by_pair = {row["pair_id"]: row["label"] for row in session._labels["records"]}
    if any(label is None for label in label_by_pair.values()):
        raise DuplicateReviewScoringError("every review pair must be labeled before scoring")
    rows = []
    for packet_row in session._packet["records"]:
        label = label_by_pair[packet_row["pair_id"]]
        weight = packet_row["sampling_weight"]
        similarity = packet_row["similarity"]
        if (
            isinstance(weight, bool)
            or not isinstance(weight, (int, float))
            or not math.isfinite(weight)
            or weight <= 0
            or isinstance(similarity, bool)
            or not isinstance(similarity, (int, float))
            or not math.isfinite(similarity)
            or not 0 <= similarity <= 1
            or packet_row["split"] not in {"calibration", "holdout"}
            or packet_row["sampling_frame"] not in {"candidate", "exhaustive-control"}
            or not isinstance(packet_row["stratum"], dict)
        ):
            raise DuplicateReviewScoringError("review sampling metadata is malformed")
        rows.append(
            {
                "split": packet_row["split"],
                "sampling_frame": packet_row["sampling_frame"],
                "stratum": packet_row["stratum"],
                "sampling_weight": float(weight),
                "similarity": float(similarity),
                "label": label,
            }
        )
    return rows


def _split_summary(rows: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    labels = Counter(row["label"] for row in rows)
    return {
        "reviewed_count": len(rows),
        "binary_count": labels["NEAR_DUPLICATE"] + labels["NOT_NEAR_DUPLICATE"],
        "label_counts": {label: labels[label] for label in review_labels.LABEL_VOCABULARY},
    }


def build_decision(
    packet_path: Path | str,
    labels_path: Path | str,
    triage_path: Path | str,
) -> dict[str, Any]:
    """Select on calibration only and evaluate the selected threshold on holdout."""

    try:
        session = review_labels.open_label_session(
            packet_path,
            labels_path,
            triage_path,
            allow_empty_triage_queue=True,
        )
    except review_labels.DuplicateReviewLabelsError as exc:
        raise DuplicateReviewScoringError(str(exc)) from exc
    if session._triage is None:
        raise DuplicateReviewScoringError("model-assisted scoring requires its bound triage sidecar")
    rows = _validated_rows(session)
    binary = [
        row
        for row in rows
        if row["label"] in {"NEAR_DUPLICATE", "NOT_NEAR_DUPLICATE"}
    ]
    calibration = [row for row in binary if row["split"] == "calibration"]
    holdout = [row for row in binary if row["split"] == "holdout"]
    thresholds = session._packet["source"].get("algorithm", {}).get("config", {}).get(
        "jaccard_thresholds"
    )
    if (
        not isinstance(thresholds, list)
        or not thresholds
        or any(
            isinstance(value, bool)
            or not isinstance(value, (int, float))
            or not math.isfinite(value)
            or not 0 <= value <= 1
            for value in thresholds
        )
        or thresholds != sorted(set(thresholds))
    ):
        raise DuplicateReviewScoringError("packet threshold sweep is malformed")
    if not calibration or not holdout:
        raise DuplicateReviewScoringError("both splits require binary review labels")
    calibration_metrics = []
    for threshold in thresholds:
        scored = _score_records(calibration, float(threshold))
        calibration_metrics.append({"threshold": threshold, **scored})
    selectable = [
        row
        for row in calibration_metrics
        if row["precision"] is not None and row["recall"] is not None and row["f1"] is not None
    ]
    if not selectable:
        selected = None
        holdout_metrics = None
        status = "INSUFFICIENT_BINARY_CALIBRATION"
    else:
        selected = max(
            selectable,
            key=lambda row: (row["f1"], row["precision"], row["threshold"]),
        )
        threshold = float(selected["threshold"])
        holdout_metrics = _score_records(holdout, threshold)
        draws = session._packet["source"]["algorithm"]["config"]["bootstrap_draws"]
        if isinstance(draws, bool) or not isinstance(draws, int) or draws < 1:
            raise DuplicateReviewScoringError("packet bootstrap draw count is malformed")
        holdout_metrics["bootstrap_95_percentile"] = _bootstrap(
            holdout,
            threshold,
            draws=draws,
            seed_material=[
                "duplicate-review-holdout-bootstrap-v1",
                session._packet["packet_hash"],
                threshold,
                session._packet["source"]["algorithm"]["config"]["seed"],
            ],
        )
        status = "SELECTED"

    triage_records = session._triage["records"]
    artifact: dict[str, Any] = {
        "schema": SCHEMA,
        "artifact_kind": "aggregate-model-assisted-duplicate-review-decision",
        "input": {
            "packet_hash": session._packet["packet_hash"],
            "analyzer_artifact_hash": session._packet["source"]["analyzer_artifact_hash"],
            "labels_hash": session._labels["labels_hash"],
            "triage_hash": session._triage["triage_hash"],
        },
        "adjudication": {
            "kind": "model-assisted",
            "pre_triage_preserved_human_count": len(rows) - len(triage_records),
            "model_auto_labeled_count": sum(
                row["decision"].startswith("AUTO_") for row in triage_records
            ),
            "post_triage_human_review_count": sum(
                row["decision"] == "HUMAN_REVIEW" for row in triage_records
            ),
            "uncertainty_scope": (
                "stratified-review-sample-bootstrap-conditional-on-model-assisted-labels;"
                " excludes-model-label-error-and-archive-population-inference"
            ),
        },
        "prediction_rule": PREDICTION_RULE,
        "selection": {
            "split": "calibration",
            "objective": SELECTION_OBJECTIVE,
            "threshold_metrics": calibration_metrics,
            "selected_threshold": selected["threshold"] if selected is not None else None,
        },
        "holdout": {
            "split": "holdout",
            "selected_threshold": selected["threshold"] if selected is not None else None,
            "metrics": holdout_metrics,
        },
        "review_summary": {
            "calibration": _split_summary(
                [row for row in rows if row["split"] == "calibration"]
            ),
            "holdout": _split_summary([row for row in rows if row["split"] == "holdout"]),
        },
        "decision": {
            "status": status,
            "selected_threshold": selected["threshold"] if selected is not None else None,
        },
        "privacy": {
            "mode": "aggregate-only",
            "contains_text_paths_or_pair_ids": False,
        },
    }
    artifact["artifact_hash"] = _digest(artifact)
    return artifact


def write_decision_atomic(
    path: Path | str,
    artifact: Mapping[str, Any],
    *,
    protected_paths: Sequence[Path | str] = (),
) -> Path:
    """Write the aggregate under the private review root until publication review."""

    try:
        destination = review_packet.validate_review_packet_destination(path)
    except review_packet.DuplicateReviewPacketError as exc:
        raise DuplicateReviewScoringError(str(exc)) from exc
    expected_bytes = _validated_artifact_bytes(dict(artifact), role="new decision")
    protected = {Path(item).resolve(strict=False) for item in protected_paths}
    if destination in protected:
        raise DuplicateReviewScoringError("decision output must differ from every input artifact")
    if destination.exists():
        try:
            existing = json.loads(destination.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as exc:
            raise DuplicateReviewScoringError(
                "existing decision output is unreadable and will not be overwritten"
            ) from exc
        existing_bytes = _validated_artifact_bytes(existing, role="existing decision")
        if existing_bytes != expected_bytes:
            raise DuplicateReviewScoringError(
                "existing decision output differs and will not be overwritten"
            )
        return destination
    review_labels.write_labels_atomic(destination, artifact)
    return destination


def _closed(value: Any, fields: set[str], role: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != fields:
        raise DuplicateReviewScoringError(f"{role} shape is incomplete or unsupported")
    return value


def _validate_frozen_provenance(decision: dict[str, Any], thresholds: list[float]) -> None:
    """Check stored evidence consistency without reading labels or rescoring pairs."""
    _closed(decision, {"schema", "artifact_kind", "input", "adjudication", "prediction_rule",
                       "selection", "holdout", "review_summary", "decision", "privacy", "artifact_hash"}, "decision")
    if decision["artifact_kind"] != "aggregate-model-assisted-duplicate-review-decision":
        raise DuplicateReviewScoringError("unsupported decision kind")
    inputs = _closed(decision["input"], {"packet_hash", "analyzer_artifact_hash", "labels_hash", "triage_hash"}, "decision inputs")
    if any(not isinstance(v, str) or len(v) != 64 or any(c not in "0123456789abcdef" for c in v) for v in inputs.values()):
        raise DuplicateReviewScoringError("decision input hashes are malformed")
    adjudication = _closed(decision["adjudication"], {"kind", "pre_triage_preserved_human_count",
        "model_auto_labeled_count", "post_triage_human_review_count", "uncertainty_scope"}, "adjudication")
    if adjudication["kind"] != "model-assisted" or adjudication["uncertainty_scope"] != (
        "stratified-review-sample-bootstrap-conditional-on-model-assisted-labels;"
        " excludes-model-label-error-and-archive-population-inference"
    ):
        raise DuplicateReviewScoringError("unsupported adjudication scope")
    counts = [adjudication[key] for key in adjudication if key.endswith("_count")]
    if any(type(v) is not int or v < 0 for v in counts):
        raise DuplicateReviewScoringError("invalid adjudication counts")
    summaries = _closed(decision["review_summary"], {"calibration", "holdout"}, "review summary")
    for summary in summaries.values():
        _closed(summary, {"reviewed_count", "binary_count", "label_counts"}, "split summary")
        labels = _closed(summary["label_counts"], set(review_labels.LABEL_VOCABULARY), "label counts")
        if (any(type(v) is not int or v < 0 for v in labels.values())
            or type(summary["reviewed_count"]) is not int or type(summary["binary_count"]) is not int
            or summary["reviewed_count"] != sum(labels.values())
            or summary["binary_count"] != labels["NEAR_DUPLICATE"] + labels["NOT_NEAR_DUPLICATE"]):
            raise DuplicateReviewScoringError("review counts disagree with labels")
    if sum(counts) != sum(s["reviewed_count"] for s in summaries.values()):
        raise DuplicateReviewScoringError("adjudication counts disagree with review population")
    selection = _closed(decision["selection"], {"split", "objective", "threshold_metrics", "selected_threshold"}, "selection")
    holdout = _closed(decision["holdout"], {"split", "selected_threshold", "metrics"}, "holdout")
    _closed(decision["decision"], {"status", "selected_threshold"}, "verdict")
    rows = selection["threshold_metrics"]
    if not isinstance(rows, list) or [row.get("threshold") for row in rows if isinstance(row, dict)] != thresholds:
        raise DuplicateReviewScoringError("calibration metrics do not cover the analyzer sweep")
    metric_fields = {"raw_confusion", "weighted_confusion", "precision", "recall", "f1"}
    for row, split, extra in [(r, "calibration", {"threshold"}) for r in rows] + [(holdout["metrics"], "holdout", {"bootstrap_95_percentile"})]:
        _closed(row, metric_fields | extra, "review metrics")
        raw = _closed(row["raw_confusion"], {"tp", "fp", "tn", "fn"}, "raw confusion")
        weighted = _closed(row["weighted_confusion"], set(raw), "weighted confusion")
        if (any(type(v) is not int or v < 0 for v in raw.values())
            or sum(raw.values()) != summaries[split]["binary_count"]
            or raw["tp"] + raw["fn"] != summaries[split]["label_counts"]["NEAR_DUPLICATE"]
            or raw["tn"] + raw["fp"] != summaries[split]["label_counts"]["NOT_NEAR_DUPLICATE"]
            or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in weighted.values())):
            raise DuplicateReviewScoringError("review confusion counts are inconsistent")
        p = _metric(weighted["tp"], weighted["tp"] + weighted["fp"])
        r = _metric(weighted["tp"], weighted["tp"] + weighted["fn"])
        f = 2*p*r/(p+r) if p is not None and r is not None and p+r > 0 else 0.0 if p is not None and r is not None else None
        for key, expected in (("precision", p), ("recall", r), ("f1", f)):
            value = row[key]
            if value is None and expected is None:
                continue
            if isinstance(value, bool) or not isinstance(value, (int, float)) or expected is None or not math.isclose(value, expected, rel_tol=1e-12):
                raise DuplicateReviewScoringError("review metrics disagree with confusion counts")
    selectable = [row for row in rows if row["precision"] is not None and row["recall"] is not None and row["f1"] is not None]
    if not selectable or max(selectable, key=lambda row: (row["f1"], row["precision"], row["threshold"]))["threshold"] != selection["selected_threshold"]:
        raise DuplicateReviewScoringError("frozen threshold contradicts calibration objective")
    bootstrap = _closed(holdout["metrics"]["bootstrap_95_percentile"], {"method", "requested_draws", "metrics"}, "holdout bootstrap")
    if bootstrap["method"] != "within-stratum-nonparametric-percentile-no-draw-dropping-v1" or type(bootstrap["requested_draws"]) is not int or bootstrap["requested_draws"] < 1:
        raise DuplicateReviewScoringError("holdout bootstrap provenance is invalid")
    for interval in _closed(bootstrap["metrics"], {"precision", "recall", "f1"}, "bootstrap metrics").values():
        _closed(interval, {"status", "lower", "upper", "valid_draws", "invalid_draws"}, "bootstrap interval")
        valid, invalid = interval["valid_draws"], interval["invalid_draws"]
        if type(valid) is not int or type(invalid) is not int or min(valid, invalid) < 0 or valid + invalid != bootstrap["requested_draws"]:
            raise DuplicateReviewScoringError("bootstrap draw accounting is invalid")
        if invalid:
            if interval["status"] != "UNDEFINED_IN_SOME_DRAWS" or interval["lower"] is not None or interval["upper"] is not None:
                raise DuplicateReviewScoringError("undefined bootstrap interval is inconsistent")
        elif (interval["status"] != "OK" or any(isinstance(interval[k], bool) or not isinstance(interval[k], (int, float)) for k in ("lower", "upper"))
              or not 0 <= interval["lower"] <= interval["upper"] <= 1):
            raise DuplicateReviewScoringError("bootstrap interval is invalid")


def apply_decision(prevalence_artifact: Mapping[str, Any], decision: dict[str, Any], *, expected_decision_hash: str) -> dict[str, Any]:
    """Bind a frozen review decision to its exact analyzer output, without scoring again.

    The selected threshold identifies one existing sweep row. It changes neither
    the observation population nor the scope of its descriptive intervals.
    """
    from . import duplicate_prevalence as prevalence

    source = prevalence.validate_artifact_hash(prevalence_artifact)
    _validated_artifact_bytes(decision, role="review decision")
    if decision["artifact_hash"] != expected_decision_hash:
        raise DuplicateReviewScoringError("decision does not match the externally pinned hash")
    try:
        _validate_frozen_provenance(decision, source["algorithm"]["config"]["jaccard_thresholds"])
        if decision["input"]["analyzer_artifact_hash"] != source["artifact_hash"]:
            raise DuplicateReviewScoringError("review decision belongs to a different analyzer artifact")
        if source["near_duplicate"]["decision"]["status"] != "UNDECIDED":
            raise DuplicateReviewScoringError("analyzer artifact already has a decision")
        if decision["prediction_rule"] != PREDICTION_RULE:
            raise DuplicateReviewScoringError("review prediction rule is unsupported")
        selected = decision["decision"]
        threshold = selected["selected_threshold"]
        if (
            selected["status"] != "SELECTED"
            or isinstance(threshold, bool)
            or not isinstance(threshold, (int, float))
            or not math.isfinite(threshold)
            or not 0 < threshold <= 1
        ):
            raise DuplicateReviewScoringError("review decision has no selected threshold")
        if (
            decision["selection"]["split"] != "calibration"
            or decision["selection"]["objective"] != SELECTION_OBJECTIVE
            or decision["holdout"]["split"] != "holdout"
            or decision["selection"]["selected_threshold"] != threshold
            or decision["holdout"]["selected_threshold"] != threshold
            or decision["review_summary"]["calibration"]["binary_count"] <= 0
            or decision["review_summary"]["holdout"]["binary_count"] <= 0
        ):
            raise DuplicateReviewScoringError("review calibration/holdout provenance is inconsistent")
        if (
            threshold not in source["algorithm"]["config"]["jaccard_thresholds"]
            or sum(row["threshold"] == threshold for row in source["near_duplicate"]["threshold_sweep"]) != 1
            or sum(row["threshold"] == threshold for row in source["near_duplicate"]["exhaustive_slice"]["candidate_recall_by_threshold"]) != 1
        ):
            raise DuplicateReviewScoringError("selected threshold lacks unique measurement and recall rows")
        if decision["privacy"] != {"mode": "aggregate-only", "contains_text_paths_or_pair_ids": False}:
            raise DuplicateReviewScoringError("review decision is not aggregate-only")
    except (KeyError, TypeError) as exc:
        raise DuplicateReviewScoringError("review decision or analyzer provenance is incomplete") from exc

    result = deepcopy(source)
    result["near_duplicate"]["decision"] = {
        "status": "SELECTED",
        "selected_threshold": threshold,
        "reason": "frozen cohort-bound review decision; descriptive scope unchanged",
        "analyzer_artifact_hash": source["artifact_hash"],
        "review_decision_artifact_hash": decision["artifact_hash"],
    }
    result["artifact_hash"] = _digest({key: value for key, value in result.items() if key != "artifact_hash"})
    return result


__all__ = [
    "PREDICTION_RULE",
    "SCHEMA",
    "SELECTION_OBJECTIVE",
    "DuplicateReviewScoringError",
    "build_decision",
    "write_decision_atomic",
]
