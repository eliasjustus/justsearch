from __future__ import annotations

import json
import shutil
import copy
from pathlib import Path

import pytest

from jseval import utility_publication as publication
from jseval.utility_claim_policy import load_policy
from jseval.utility_evidence import sanitize_observation
from jseval.utility_publication import POINTER_REF_KEYS
from jseval.utility_recompose import finalize_evidence, finalize_observation_groups, write_record
from tests.test_utility_evidence import _observation
from tests.test_corpus_inject import _certification_snapshot_fixture


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


def _setup_accepted(tmp_path):
    observations = []
    expected = []
    certification = _certification_snapshot_fixture(query_gold_sha256="e" * 64)
    for seed in range(5):
        for index in range(20):
            qid = f"q{index}"
            for condition in ("A", "B"):
                item = copy.deepcopy(_observation(condition, qid=qid))
                item["seed"] = seed
                campaign = item["source"]["cohort"]["campaign_identity"]
                item["source"]["cohort"]["corpus_certification"] = certification
                item["source"]["cohort"]["query_identity"]["row_count"] = 20
                expected.append(f"{condition}|{seed}|{qid}")
                campaign.update({"conditions": ["A", "B"], "seeds": 5})
                observations.append(item)
    for item in observations:
        item["source"]["cohort"]["campaign_identity"]["expected_cells"] = expected

    policy = copy.deepcopy(load_policy())
    policy["status"] = "active"
    policy["unresolved"] = []
    policy["required_strata"] = [{
        "stratum_id": "fixture-member|fixture|1000|verbose|haiku",
        "corpus_member": "fixture-member",
        "dataset": "fixture",
        "size": 1000,
        "query_variant": "verbose",
        "requested_model": "haiku",
        "query_count": 20,
        "seed_ids": [0, 1, 2, 3, 4],
    }]
    policy["thresholds"].update({
        "minimum_adoption_rate": 0.5,
        "accuracy_noninferiority_margin": 0.02,
        "provider_token_equivalence_margin": 5,
        "cost_equivalence_margin_usd": 0.001,
    })
    input_dir = tmp_path / "accepted-input"
    input_dir.mkdir()
    evidence = input_dir / "observations.v1.jsonl"
    evidence.write_text("".join(
        json.dumps(sanitize_observation(item), sort_keys=True) + "\n"
        for item in observations
    ), encoding="utf-8")
    record = finalize_observation_groups(
        [observations], composed_at="2026-07-13T00:00:00Z",
        contamination_class="private-synthetic", policy=policy,
    )
    assert record["claim_verdict"]["accepted"] is True
    record_path = write_record(record, input_dir)
    policy_path = input_dir / "policy.json"
    policy_path.write_text(json.dumps(policy), encoding="utf-8")
    root = tmp_path / "public-agent-utility-accepted"
    root.mkdir()
    (root / "current.v1.json").write_text(json.dumps({
        "schema": publication.POINTER_SCHEMA, "schema_version": 1,
        "current": None, "previous": None, "reason": "initial", "selected_at": None,
    }), encoding="utf-8")
    return root, record_path, evidence, policy_path


def test_publication_builder_refuses_rejected_record(tmp_path):
    root, record, evidence = _setup_rejected(tmp_path)
    with pytest.raises(ValueError, match="only an accepted"):
        publication.build_publication(
            root=root, record_path=record, evidence_path=evidence,
            publication_id="rejected-pilot",
        )


def test_replay_detects_evidence_tampering(tmp_path):
    root, record, evidence, policy_path = _setup_accepted(tmp_path)
    manifest = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="tamper-test", policy_path=policy_path,
    )
    copied = manifest.parent / "observations.v1.jsonl"
    copied.write_text(copied.read_text(encoding="utf-8") + "{}\n", encoding="utf-8")
    with pytest.raises(ValueError, match="evidence byte hash"):
        publication.replay_publication(manifest)


def test_replay_uses_bundled_policy_not_changed_default(tmp_path, monkeypatch):
    root, record, evidence, policy_path = _setup_accepted(tmp_path)
    manifest = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="policy-snapshot", policy_path=policy_path,
    )
    import jseval.utility_claim_policy as claim_policy

    monkeypatch.setattr(
        claim_policy, "load_policy",
        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("default policy consulted")),
    )
    assert publication.replay_publication(manifest)["ok"] is True


def test_replay_rejects_manifest_state_that_disagrees_with_verdict(tmp_path):
    root, record, evidence, policy_path = _setup_accepted(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="state-tamper", policy_path=policy_path,
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["lifecycle_state"] = "rejected"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="lifecycle state"):
        publication.replay_publication(manifest_path)


def test_replay_rejects_unknown_manifest_fields(tmp_path):
    root, record, evidence, policy_path = _setup_accepted(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="shape-tamper", policy_path=policy_path,
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["raw_private_log"] = "forbidden"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="publication manifest fields differ"):
        publication.replay_publication(manifest_path)


def test_replay_rejects_paths_escaping_the_immutable_bundle(tmp_path):
    root, record, evidence, policy_path = _setup_accepted(tmp_path)
    manifest_path = publication.build_publication(
        root=root, record_path=record, evidence_path=evidence,
        publication_id="path-escape", policy_path=policy_path,
    )
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["observations"]["path"] = "../../../../outside.jsonl"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="escapes"):
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


def test_initial_null_pointer_cannot_be_misrendered_as_withdrawn(tmp_path):
    root, _, _ = _setup_rejected(tmp_path)
    with pytest.raises(ValueError, match="initial-null"):
        publication.select_publication(root=root, clear=True, reason="not a transition")


def test_pointer_ref_keys_match_schema_ref_definition_exactly():
    schema_path = Path(__file__).parents[1] / "agent-utility-publication-pointer.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    ref_schema = schema["definitions"]["ref"]
    assert POINTER_REF_KEYS == set(ref_schema["properties"])
    assert POINTER_REF_KEYS == set(ref_schema["required"])
