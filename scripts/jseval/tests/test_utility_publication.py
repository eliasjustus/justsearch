from __future__ import annotations

import json
import shutil
from pathlib import Path

import pytest

from jseval import utility_publication as publication
from jseval.utility_recompose import finalize_evidence, write_record


def _setup_rejected(tmp_path):
    source = (
        Path(__file__).parent / "fixtures" / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    evidence = tmp_path / "input" / "observations.v1.jsonl"
    evidence.parent.mkdir()
    shutil.copyfile(source, evidence)
    record = finalize_evidence([evidence], composed_at="2026-07-13T00:00:00Z")
    record_path = write_record(record, tmp_path / "input")
    root = tmp_path / "public-agent-utility"
    root.mkdir()
    (root / "current.v1.json").write_text(json.dumps({
        "schema": publication.POINTER_SCHEMA,
        "schema_version": 1,
        "current": None,
        "previous": None,
        "reason": "initial",
        "selected_at": None,
    }), encoding="utf-8")
    return root, record_path, evidence


def test_rejected_bundle_is_immutable_replayable_and_never_selectable(tmp_path):
    root, record, evidence = _setup_rejected(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="rejected-pilot", created_at="2026-07-13T00:00:00Z",
    )
    result = publication.replay_publication(manifest_path)
    assert result["ok"] is True
    assert result["lifecycle_state"] == "rejected"
    assert publication.load_pointer(root)["current"] is None

    with pytest.raises(FileExistsError):
        publication.build_publication(
            root=root, record_path=record, evidence_path=evidence,
            publication_id="rejected-pilot",
        )
    with pytest.raises(ValueError, match="only an accepted"):
        publication.select_publication(
            root=root, publication_id="rejected-pilot", reason="must fail"
        )


def test_replay_detects_evidence_tampering(tmp_path):
    root, record, evidence = _setup_rejected(tmp_path)
    manifest = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="tamper-test",
    )
    copied = manifest.parent / "observations.v1.jsonl"
    copied.write_text(copied.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="evidence byte hash"):
        publication.replay_publication(manifest)


def test_replay_rejects_manifest_state_that_disagrees_with_verdict(tmp_path):
    root, record, evidence = _setup_rejected(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="state-tamper",
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["lifecycle_state"] = "accepted"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="lifecycle state"):
        publication.replay_publication(manifest_path)


def test_replay_rejects_unknown_manifest_fields(tmp_path):
    root, record, evidence = _setup_rejected(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="shape-tamper",
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["raw_private_log"] = "forbidden"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="publication manifest fields differ"):
        publication.replay_publication(manifest_path)


def test_pointer_accept_current_supersede_and_clear_transitions(tmp_path, monkeypatch):
    root, _, _ = _setup_rejected(tmp_path)
    publications = root / "publications"
    for publication_id in ("accepted-one", "accepted-two"):
        directory = publications / publication_id
        directory.mkdir(parents=True)
        body = {
            "schema": publication.PUBLICATION_SCHEMA,
            "publication_id": publication_id,
            "lifecycle_state": "accepted",
        }
        (directory / "publication.v1.json").write_text(json.dumps(body), encoding="utf-8")
    monkeypatch.setattr(publication, "replay_publication", lambda path: {"ok": True})

    publication.select_publication(
        root=root, publication_id="accepted-one", reason="owner accepted", selected_at="one"
    )
    first = publication.load_pointer(root)
    assert first["current"]["publication_id"] == "accepted-one"
    assert first["previous"] is None

    publication.select_publication(
        root=root, publication_id="accepted-two", reason="supersedes first", selected_at="two"
    )
    second = publication.load_pointer(root)
    assert second["current"]["publication_id"] == "accepted-two"
    assert second["previous"]["publication_id"] == "accepted-one"

    publication.select_publication(root=root, clear=True, reason="withdrawn", selected_at="three")
    cleared = publication.load_pointer(root)
    assert cleared["current"] is None
    assert cleared["previous"]["publication_id"] == "accepted-two"


def test_checked_in_no_result_pointer_matches_schema():
    jsonschema = pytest.importorskip("jsonschema")
    root = Path(__file__).parents[1]
    pointer = json.loads((root / "public-agent-utility" / "current.v1.json").read_text())
    schema = json.loads((root / "agent-utility-publication-pointer.v1.schema.json").read_text())
    jsonschema.validate(pointer, schema)
    assert pointer["current"] is None
