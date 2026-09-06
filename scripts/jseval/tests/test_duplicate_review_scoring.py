from __future__ import annotations

import hashlib
import json
from copy import deepcopy
from pathlib import Path

import pytest

from jseval import duplicate_review_labels as labels
from jseval import duplicate_review_packet as packet_module
from jseval import duplicate_review_scoring as scoring


def _digest(value: object) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    ).hexdigest()


def _record(number: int, split: str, frame: str, similarity: float) -> dict[str, object]:
    return {
        "pair_id": hashlib.sha256(f"score-pair-{number}".encode()).hexdigest(),
        "split": split,
        "sampling_frame": frame,
        "stratum": {"group": number % 2},
        "inclusion_probability": 1.0,
        "sampling_weight": 1.0,
        "similarity": similarity,
        "left": {
            "opaque_id": hashlib.sha256(f"score-left-{number}".encode()).hexdigest(),
            "format_id": None,
            "token_count": 2,
            "text": f"PRIVATE SCORE LEFT {number}",
        },
        "right": {
            "opaque_id": hashlib.sha256(f"score-right-{number}".encode()).hexdigest(),
            "format_id": None,
            "token_count": 2,
            "text": f"PRIVATE SCORE RIGHT {number}",
        },
        "label": None,
        "labeler": None,
        "notes": None,
    }


def _packet(
    *,
    analyzer_artifact_hash: str = "a" * 64,
    jaccard_thresholds: tuple[float, ...] = (0.5, 0.8),
) -> dict[str, object]:
    artifact: dict[str, object] = {
        "schema": packet_module.SCHEMA,
        "artifact_kind": "sensitive-local-review-packet",
        "sensitivity": "local-review-text",
        "intended_persistence": "uncommitted-local-only",
        "source": {
            "analyzer_artifact_hash": analyzer_artifact_hash,
            "algorithm": {
                "config": {
                    "jaccard_thresholds": list(jaccard_thresholds),
                    "bootstrap_draws": 50,
                    "seed": 897,
                }
            },
        },
        "review_config": {"hidden": True},
        "partition": {"hidden": True},
        "sampling": {"hidden": True},
        "records": [
            _record(1, "calibration", "candidate", 0.9),
            _record(2, "calibration", "candidate", 0.6),
            _record(3, "calibration", "exhaustive-control", 0.95),
            _record(4, "calibration", "exhaustive-control", 0.2),
            _record(5, "holdout", "candidate", 0.85),
            _record(6, "holdout", "candidate", 0.55),
            _record(7, "holdout", "exhaustive-control", 0.9),
            _record(8, "holdout", "exhaustive-control", 0.1),
        ],
        "label_status": "UNLABELED",
        "near_duplicate_decision": {"status": "UNDECIDED", "selected_threshold": None},
    }
    artifact["packet_hash"] = _digest(artifact)
    return artifact


def _campaign(
    root: Path,
    name: str,
    holdout_positive: bool = True,
    analyzer_artifact: dict[str, object] | None = None,
):
    directory = root / name
    directory.mkdir(parents=True)
    packet_path = directory / "packet.local.json"
    packet = _packet(
        analyzer_artifact_hash=(
            str(analyzer_artifact["artifact_hash"])
            if analyzer_artifact is not None
            else "a" * 64
        ),
        jaccard_thresholds=(
            tuple(analyzer_artifact["algorithm"]["config"]["jaccard_thresholds"])
            if analyzer_artifact is not None
            else (0.5, 0.8)
        ),
    )
    packet_path.write_text(json.dumps(packet), encoding="utf-8")
    session = labels.open_label_session(packet_path)
    desired = {
        packet["records"][0]["pair_id"]: "AUTO_NEAR_DUPLICATE",
        packet["records"][1]["pair_id"]: "AUTO_NOT_NEAR_DUPLICATE",
        packet["records"][2]["pair_id"]: "AUTO_NEAR_DUPLICATE",
        packet["records"][3]["pair_id"]: "AUTO_NOT_NEAR_DUPLICATE",
        packet["records"][4]["pair_id"]: (
            "AUTO_NEAR_DUPLICATE" if holdout_positive else "AUTO_NOT_NEAR_DUPLICATE"
        ),
        packet["records"][5]["pair_id"]: "AUTO_NOT_NEAR_DUPLICATE",
        packet["records"][6]["pair_id"]: (
            "AUTO_NEAR_DUPLICATE" if holdout_positive else "AUTO_NOT_NEAR_DUPLICATE"
        ),
        packet["records"][7]["pair_id"]: "AUTO_NOT_NEAR_DUPLICATE",
    }
    triage_path = directory / "triage.local.json"
    labels.apply_model_triage(packet_path, session.labels_path, triage_path, desired)
    return packet_path, session.labels_path, triage_path


def test_selection_uses_calibration_only_and_holdout_is_evaluated_once(tmp_path, monkeypatch):
    private = tmp_path / "private"
    monkeypatch.setattr(packet_module, "LOCAL_REVIEW_ROOT", private)
    first = _campaign(private, "first", holdout_positive=True)
    second = _campaign(private, "second", holdout_positive=False)

    decision = scoring.build_decision(*first)
    changed_holdout = scoring.build_decision(*second)

    assert decision["decision"] == {"status": "SELECTED", "selected_threshold": 0.8}
    assert changed_holdout["decision"]["selected_threshold"] == 0.8
    assert decision["selection"] == changed_holdout["selection"]
    assert decision["holdout"]["metrics"] != changed_holdout["holdout"]["metrics"]
    assert decision["holdout"]["metrics"]["precision"] == 1.0
    assert decision["holdout"]["metrics"]["recall"] == 0.5
    assert decision["adjudication"] == {
        "kind": "model-assisted",
        "pre_triage_preserved_human_count": 0,
        "model_auto_labeled_count": 8,
        "post_triage_human_review_count": 0,
        "uncertainty_scope": (
            "stratified-review-sample-bootstrap-conditional-on-model-assisted-labels;"
            " excludes-model-label-error-and-archive-population-inference"
        ),
    }
    serialized = json.dumps(decision)
    for forbidden in ("PRIVATE", '"pair_id":', '"text":', '"opaque_id":', '"format_id":', '"stratum":'):
        assert forbidden not in serialized


def test_decision_writer_is_private_and_artifact_hash_is_valid(tmp_path, monkeypatch):
    private = tmp_path / "private"
    monkeypatch.setattr(packet_module, "LOCAL_REVIEW_ROOT", private)
    campaign = _campaign(private, "campaign")
    decision = scoring.build_decision(*campaign)
    output = private / "campaign" / "decision.local.json"

    assert scoring.write_decision_atomic(output, decision) == output.resolve()
    persisted = json.loads(output.read_text(encoding="utf-8"))
    unhashed = {key: value for key, value in persisted.items() if key != "artifact_hash"}
    assert persisted["artifact_hash"] == _digest(unhashed)

    outside = tmp_path / "public.json"
    try:
        scoring.write_decision_atomic(outside, decision)
    except scoring.DuplicateReviewScoringError as exc:
        assert "private gitignored root" in str(exc)
    else:  # pragma: no cover - safety contract
        raise AssertionError("public decision destination was accepted")


def test_decision_writer_protects_inputs_and_refuses_different_existing_output(
    tmp_path, monkeypatch
):
    private = tmp_path / "private"
    monkeypatch.setattr(packet_module, "LOCAL_REVIEW_ROOT", private)
    campaign = _campaign(private, "campaign")
    decision = scoring.build_decision(*campaign)

    for protected in campaign:
        with pytest.raises(scoring.DuplicateReviewScoringError, match="must differ"):
            scoring.write_decision_atomic(
                protected,
                decision,
                protected_paths=campaign,
            )

    output = private / "campaign" / "decision.local.json"
    assert (
        scoring.write_decision_atomic(output, decision, protected_paths=campaign)
        == output.resolve()
    )
    assert (
        scoring.write_decision_atomic(output, decision, protected_paths=campaign)
        == output.resolve()
    )
    different = deepcopy(decision)
    different["decision"]["selected_threshold"] = 0.5
    different["artifact_hash"] = _digest(
        {key: value for key, value in different.items() if key != "artifact_hash"}
    )
    output.write_text(json.dumps(different), encoding="utf-8")
    with pytest.raises(scoring.DuplicateReviewScoringError, match="differs"):
        scoring.write_decision_atomic(output, decision, protected_paths=campaign)


def test_decision_writer_rejects_type_corruption_that_python_equality_hides(
    tmp_path, monkeypatch
):
    private = tmp_path / "private"
    monkeypatch.setattr(packet_module, "LOCAL_REVIEW_ROOT", private)
    campaign = _campaign(private, "campaign")
    decision = scoring.build_decision(*campaign)
    output = private / "campaign" / "decision.local.json"
    scoring.write_decision_atomic(output, decision, protected_paths=campaign)

    corrupted = deepcopy(decision)
    assert corrupted["holdout"]["metrics"]["precision"] == 1.0
    corrupted["holdout"]["metrics"]["precision"] = True
    assert corrupted == decision  # Python equality erases this JSON type distinction.
    output.write_text(json.dumps(corrupted), encoding="utf-8")

    with pytest.raises(scoring.DuplicateReviewScoringError, match="hash is invalid"):
        scoring.write_decision_atomic(output, decision, protected_paths=campaign)


def test_bootstrap_reports_undefined_draws_without_conditioning_interval():
    records = [
        {
            "sampling_frame": "candidate",
            "stratum": {"group": 1},
            "sampling_weight": 1.0,
            "similarity": 0.9,
            "label": "NEAR_DUPLICATE",
        },
        {
            "sampling_frame": "candidate",
            "stratum": {"group": 1},
            "sampling_weight": 1.0,
            "similarity": 0.1,
            "label": "NOT_NEAR_DUPLICATE",
        },
    ]

    result = scoring._bootstrap(records, 0.5, draws=200, seed_material="sparse")

    assert result["requested_draws"] == 200
    for metric in ("precision", "recall", "f1"):
        summary = result["metrics"][metric]
        assert summary["status"] == "UNDEFINED_IN_SOME_DRAWS"
        assert summary["invalid_draws"] > 0
        assert summary["valid_draws"] + summary["invalid_draws"] == 200
        assert summary["lower"] is None
        assert summary["upper"] is None
