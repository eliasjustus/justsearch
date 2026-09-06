from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval import duplicate_review_labels as labels
from jseval import duplicate_review_packet as packet_module
from jseval import duplicate_review_label_gui as label_gui
from jseval.cli import main


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


def _record(number: int) -> dict[str, object]:
    left_id = hashlib.sha256(f"left-{number}".encode()).hexdigest()
    right_id = hashlib.sha256(f"right-{number}".encode()).hexdigest()
    return {
        "pair_id": hashlib.sha256(f"pair-{number}".encode()).hexdigest(),
        "split": "calibration" if number % 2 else "holdout",
        "sampling_frame": "candidate" if number % 2 else "exhaustive-control",
        "stratum": {"hidden": number},
        "inclusion_probability": 0.5,
        "sampling_weight": 2.0,
        "similarity": number / 10,
        "left": {
            "opaque_id": left_id,
            "format_id": "hidden/left",
            "token_count": 2,
            "text": f"PRIVATE LEFT TEXT {number}",
        },
        "right": {
            "opaque_id": right_id,
            "format_id": "hidden/right",
            "token_count": 3,
            "text": f"PRIVATE RIGHT TEXT {number}",
        },
        "label": None,
        "labeler": None,
        "notes": None,
    }


def _packet() -> dict[str, object]:
    artifact: dict[str, object] = {
        "schema": packet_module.SCHEMA,
        "artifact_kind": "sensitive-local-review-packet",
        "sensitivity": "local-review-text",
        "intended_persistence": "uncommitted-local-only",
        "source": {
            "analyzer_artifact_hash": "a" * 64,
        },
        "review_config": {"hidden": True},
        "partition": {"hidden": True},
        "sampling": {"hidden": True},
        "records": [_record(1), _record(2), _record(3)],
        "label_status": "UNLABELED",
        "near_duplicate_decision": {
            "status": "UNDECIDED",
            "selected_threshold": None,
        },
    }
    artifact["packet_hash"] = _digest(artifact)
    return artifact


@pytest.fixture
def private_root(tmp_path, monkeypatch) -> Path:
    root = tmp_path / "private"
    monkeypatch.setattr(packet_module, "LOCAL_REVIEW_ROOT", root)
    return root


def _write_packet(root: Path) -> Path:
    path = root / "campaign" / "packet.local.json"
    path.parent.mkdir(parents=True)
    path.write_text(json.dumps(_packet()), encoding="utf-8")
    return path


def test_session_creates_text_free_bound_artifact_and_uses_blinded_order(private_root):
    packet_path = _write_packet(private_root)
    session = labels.open_label_session(packet_path)

    persisted = json.loads(session.labels_path.read_text(encoding="utf-8"))
    serialized = json.dumps(persisted)
    assert persisted["schema"] == labels.SCHEMA
    assert persisted["packet_binding"]["packet_hash"] == _packet()["packet_hash"]
    assert persisted["presentation"] == {
        "order": labels.ORDER_METHOD,
        "left_right": labels.ORIENTATION_METHOD,
    }
    assert [row["pair_id"] for row in persisted["records"]] != [
        row["pair_id"] for row in _packet()["records"]
    ]
    for forbidden in (
        "PRIVATE",
        "similarity",
        "split",
        "sampling_frame",
        "format_id",
        "token_count",
        "notes",
        "labeler",
    ):
        assert forbidden not in serialized


def test_label_autosaves_advances_and_resume_starts_at_first_unlabeled(private_root):
    session = labels.open_label_session(_write_packet(private_root))
    first_pair = session._labels["records"][0]["pair_id"]
    session.label_current("NEAR_DUPLICATE")
    assert session.index == 1

    resumed = labels.open_label_session(session.packet_path, session.labels_path)
    assert resumed.index == 1
    assert resumed._labels["records"][0] == {
        "pair_id": first_pair,
        "label": "NEAR_DUPLICATE",
    }
    assert resumed.current_texts() == session.current_texts()


def test_existing_state_rejects_hash_binding_order_population_and_vocabulary(private_root):
    session = labels.open_label_session(_write_packet(private_root))
    original = json.loads(session.labels_path.read_text(encoding="utf-8"))

    mutations = []
    wrong_binding = json.loads(json.dumps(original))
    wrong_binding["packet_binding"]["packet_hash"] = "b" * 64
    mutations.append(wrong_binding)
    wrong_order = json.loads(json.dumps(original))
    wrong_order["records"].reverse()
    mutations.append(wrong_order)
    missing = json.loads(json.dumps(original))
    missing["records"].pop()
    mutations.append(missing)
    wrong_vocab = json.loads(json.dumps(original))
    wrong_vocab["label_vocabulary"].append("MAYBE")
    mutations.append(wrong_vocab)
    wrong_label = json.loads(json.dumps(original))
    wrong_label["records"][0]["label"] = "MAYBE"
    mutations.append(wrong_label)

    for mutation in mutations:
        mutation["labels_hash"] = _digest(
            {key: value for key, value in mutation.items() if key != "labels_hash"}
        )
        session.labels_path.write_text(json.dumps(mutation), encoding="utf-8")
        with pytest.raises(labels.DuplicateReviewLabelsError):
            labels.open_label_session(session.packet_path, session.labels_path)


def test_paths_must_be_private_and_destinations_must_differ(private_root, tmp_path):
    packet_path = _write_packet(private_root)
    with pytest.raises(labels.DuplicateReviewLabelsError, match="private gitignored root"):
        labels.open_label_session(tmp_path / "outside.json")
    with pytest.raises(labels.DuplicateReviewLabelsError, match="destinations must differ"):
        labels.open_label_session(packet_path, packet_path)
    with pytest.raises(labels.DuplicateReviewLabelsError, match="private gitignored root"):
        labels.open_label_session(packet_path, tmp_path / "outside-labels.json")


def test_packet_hash_and_embedded_labels_fail_closed(private_root):
    packet_path = _write_packet(private_root)
    artifact = json.loads(packet_path.read_text(encoding="utf-8"))
    artifact["records"][0]["label"] = "NEAR_DUPLICATE"
    artifact["packet_hash"] = _digest(
        {key: value for key, value in artifact.items() if key != "packet_hash"}
    )
    packet_path.write_text(json.dumps(artifact), encoding="utf-8")
    with pytest.raises(labels.DuplicateReviewLabelsError, match="must remain empty"):
        labels.open_label_session(packet_path)


def test_failed_atomic_save_keeps_disk_memory_and_position(private_root, monkeypatch):
    session = labels.open_label_session(_write_packet(private_root))
    old_bytes = session.labels_path.read_bytes()
    old_hash = session._labels["labels_hash"]

    def fail_replace(_source, _destination):
        raise OSError("injected replacement failure")

    monkeypatch.setattr(labels.os, "replace", fail_replace)
    with pytest.raises(OSError, match="injected replacement failure"):
        session.label_current("NEAR_DUPLICATE")
    assert session.index == 0
    assert session.current_label is None
    assert session._labels["labels_hash"] == old_hash
    assert session.labels_path.read_bytes() == old_bytes
    assert not list(session.labels_path.parent.glob(f".{session.labels_path.name}.*.tmp"))


def test_cli_validates_then_launches_without_printing_sensitive_text(
    private_root, monkeypatch
):
    packet_path = _write_packet(private_root)
    seen = []

    def fake_launch(session):
        seen.append(session)

    from jseval import duplicate_review_label_gui

    monkeypatch.setattr(duplicate_review_label_gui, "launch_label_gui", fake_launch)
    result = CliRunner().invoke(
        main,
        ["duplicate-review-label", "--packet", str(packet_path)],
    )
    assert result.exit_code == 0, result.output
    assert result.output == ""
    assert len(seen) == 1
    assert seen[0].labels_path == labels.default_labels_path(packet_path).resolve()


def test_cli_rejects_global_json_before_opening_packet(private_root):
    packet_path = _write_packet(private_root)
    result = CliRunner().invoke(
        main,
        ["--json", "duplicate-review-label", "--packet", str(packet_path)],
    )
    assert result.exit_code != 0
    assert "--json is not supported" in result.output
    assert "PRIVATE" not in result.output
    assert not labels.default_labels_path(packet_path).exists()


def test_gui_instructions_define_all_four_judgments():
    assert "Near duplicate = the same substantive content" in label_gui.INSTRUCTIONS
    assert "Not near duplicate = substantively distinct content" in label_gui.INSTRUCTIONS
    assert "Uncertain = both texts are reviewable" in label_gui.INSTRUCTIONS
    assert "Abstain = no judgment is possible" in label_gui.INSTRUCTIONS


def test_model_triage_autolabels_only_obvious_pairs_and_builds_human_queue(private_root):
    packet_path = _write_packet(private_root)
    session = labels.open_label_session(packet_path)
    pair_ids = [row["pair_id"] for row in session._labels["records"]]
    triage_path = private_root / "campaign" / "triage.local.json"

    summary = labels.apply_model_triage(
        packet_path,
        session.labels_path,
        triage_path,
        {
            pair_ids[0]: "AUTO_NEAR_DUPLICATE",
            pair_ids[1]: "HUMAN_REVIEW",
            pair_ids[2]: "AUTO_NOT_NEAR_DUPLICATE",
        },
    )

    assert summary == {"auto_labeled": 2, "human_review": 1}
    queued = labels.open_label_session(packet_path, session.labels_path, triage_path)
    assert queued.triaged is True
    assert queued.count == 1
    assert queued.current_label is None
    assert queued._labels["records"][0]["label"] == "NEAR_DUPLICATE"
    assert queued._labels["records"][2]["label"] == "NOT_NEAR_DUPLICATE"
    queued.label_current("UNCERTAIN")
    assert queued.current_label == "UNCERTAIN"
    resumed = labels.open_label_session(packet_path, session.labels_path, triage_path)
    assert resumed.current_label == "UNCERTAIN"
    assert resumed.completed_count == 1

    triage = json.loads(triage_path.read_text(encoding="utf-8"))
    serialized = json.dumps(triage)
    assert triage["schema"] == labels.TRIAGE_SCHEMA
    assert triage["method"] == labels.TRIAGE_METHOD
    for forbidden in (
        "PRIVATE",
        "text",
        "split",
        "similarity",
        "stratum",
        "format_id",
        "token_count",
    ):
        assert forbidden not in serialized


def test_model_triage_requires_complete_unlabeled_population_and_bound_state(private_root):
    packet_path = _write_packet(private_root)
    session = labels.open_label_session(packet_path)
    pair_ids = [row["pair_id"] for row in session._labels["records"]]
    triage_path = private_root / "campaign" / "triage.local.json"
    with pytest.raises(labels.DuplicateReviewLabelsError, match="every and only"):
        labels.apply_model_triage(
            packet_path,
            session.labels_path,
            triage_path,
            {pair_ids[0]: "HUMAN_REVIEW"},
        )

    session.label_current("NEAR_DUPLICATE")
    labels.apply_model_triage(
        packet_path,
        session.labels_path,
        triage_path,
        {pair_id: "HUMAN_REVIEW" for pair_id in pair_ids[1:]},
    )
    state = json.loads(session.labels_path.read_text(encoding="utf-8"))
    state["records"][0]["label"] = "NOT_NEAR_DUPLICATE"
    state["labels_hash"] = _digest(
        {key: value for key, value in state.items() if key != "labels_hash"}
    )
    session.labels_path.write_text(json.dumps(state), encoding="utf-8")
    with pytest.raises(labels.DuplicateReviewLabelsError, match="post-state reconstruction"):
        labels.open_label_session(packet_path, session.labels_path, triage_path)


def test_model_triage_second_write_failure_is_retryable(private_root, monkeypatch):
    packet_path = _write_packet(private_root)
    session = labels.open_label_session(packet_path)
    decisions = {
        row["pair_id"]: "HUMAN_REVIEW" for row in session._labels["records"]
    }
    triage_path = private_root / "campaign" / "triage.local.json"
    real_write = labels.write_labels_atomic
    real_unlink = Path.unlink
    calls = 0

    def fail_second_write(path, value):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("injected labels write failure")
        return real_write(path, value)

    def fail_cleanup(_self, *args, **kwargs):
        raise OSError("injected cleanup failure")

    monkeypatch.setattr(labels, "write_labels_atomic", fail_second_write)
    monkeypatch.setattr(Path, "unlink", fail_cleanup)
    with pytest.raises(OSError, match="injected labels write failure"):
        labels.apply_model_triage(
            packet_path, session.labels_path, triage_path, decisions
        )
    assert triage_path.exists()
    assert all(row["label"] is None for row in session._labels["records"])

    monkeypatch.setattr(labels, "write_labels_atomic", real_write)
    monkeypatch.setattr(Path, "unlink", real_unlink)
    assert labels.apply_model_triage(
        packet_path, session.labels_path, triage_path, decisions
    ) == {"auto_labeled": 0, "human_review": 3}
    assert labels.open_label_session(
        packet_path, session.labels_path, triage_path
    ).count == 3
