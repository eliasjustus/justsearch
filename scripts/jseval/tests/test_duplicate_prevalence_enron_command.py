from __future__ import annotations

import hashlib
import io
import json
import tarfile
from copy import deepcopy
from pathlib import Path

import pytest
from click.testing import CliRunner

from jseval import duplicate_prevalence_enron as adapter
from jseval import duplicate_prevalence as prevalence
from jseval import duplicate_prevalence_production as production_adapter
from jseval import duplicate_review_packet as review
from jseval.cli import main


def _analysis() -> dict:
    return {
        "shingle_width": 1,
        "simhash_bits": 64,
        "max_hamming": 3,
        "jaccard_thresholds": [0.5, 0.9],
        "exhaustive_slice_size": 100,
        "bootstrap_draws": 100,
        "seed": 17,
        "max_candidate_pairs": 10_000,
    }


def _spec(root: Path, tarball: str = "enron-source.tar.gz") -> dict:
    return {
        "schema": adapter.INPUT_SCHEMA,
        "source": {
            "kind": adapter.SOURCE_KIND,
            "raw_root": str(root),
            "tarball": tarball,
            "min_words": 3,
        },
        "analysis": _analysis(),
    }


def _spec_v2(root: Path, *, size: int = 3, seed: int = 7) -> dict:
    payload = _spec(root)
    payload["schema"] = adapter.INPUT_SCHEMA_V2
    payload["source"]["eligible_sample"] = {
        "method": "algorithm-r-reservoir-without-replacement-v1",
        "size": size,
        "seed": seed,
    }
    return payload


def _message(body: str) -> bytes:
    return f"From: sender@example.test\nSubject: Fixture\n\n{body}".encode("utf-8")


def _write_archive(root: Path, members: list[tuple[str, bytes]] | None = None) -> Path:
    root.mkdir(parents=True)
    archive_path = root / "enron-source.tar.gz"
    planted = members or [
        ("mail/a", _message("duplicate body words")),
        ("mail/b", _message("duplicate body words")),
        ("mail/c", _message("alpha beta gamma")),
        ("mail/d", _message("red blue green")),
        ("mail/e", _message("one two three")),
        ("mail/f", _message("PRIVATE_SHORT_BODY_TOKEN")),
    ]
    with tarfile.open(archive_path, "w:gz") as archive:
        for name, payload in planted:
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            info.mtime = 0
            archive.addfile(info, io.BytesIO(payload))
    return archive_path


def _write_spec(path: Path, payload: object) -> Path:
    path.write_text(json.dumps(payload), encoding="utf-8")
    return path


def test_planted_archive_counts_all_stages_and_keeps_eligible_duplicate(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    artifact = adapter.analyze_input_spec(spec_path)

    projection = artifact["input"]["source_projection_identity"]
    assert projection["stage_counts"] == {
        "raw_member": 6,
        "parsed_body": 6,
        "eligible_body": 5,
        "retained_body": 4,
    }
    assert artifact["denominators"]["source_observations"] == 5
    assert artifact["byte_exact"]["duplicate_documents"] == 2
    assert artifact["content_exact"]["duplicate_documents"] == 2
    assert artifact["input"]["corpus_identity"]["file_count"] == 1
    assert artifact["input"]["source_kind"] == "source-body-proxy"


def test_v2_full_frame_census_precedes_retention_and_sample_at_population_size(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec_v2(root, size=5, seed=11))

    artifact = adapter.analyze_input_spec(spec_path)

    projection = artifact["input"]["source_projection_identity"]
    census = projection["population_exact_census"]
    assert projection["schema"] == "jseval.source-body-projection.v2"
    assert projection["stage_counts"] == {
        "raw_member": 6,
        "parsed_body": 6,
        "eligible_body": 5,
        "retained_body": 4,
    }
    assert projection["sampling"] == {
        "method": "algorithm-r-reservoir-without-replacement-v1",
        "frame": "eligible_body-before-sha-retention",
        "requested_size": 5,
        "seed": 11,
        "population_count": 5,
        "sample_count": 5,
        "per_occurrence_inclusion_probability": 1.0,
    }
    assert census["eligible_occurrences"] == 5
    assert census["byte_exact"]["duplicate_documents"] == 2
    assert census["content_exact"]["duplicate_documents"] == 2
    assert artifact["byte_exact"]["duplicate_documents"] == 2
    assert artifact["content_exact"]["duplicate_documents"] == 2
    assert artifact["algorithm"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert artifact["denominators"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert artifact["byte_exact"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert artifact["content_exact"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert artifact["near_duplicate"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert "population_exact_census" not in artifact
    assert "raw_body_digest_counts" not in census
    assert "normalized_content_digest_counts" not in census


def test_v2_size_one_separates_frozen_sample_from_full_exact_census(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec_v2(root, size=1, seed=3))

    artifact = adapter.analyze_input_spec(spec_path)

    projection = artifact["input"]["source_projection_identity"]
    census = projection["population_exact_census"]
    assert artifact["denominators"]["source_observations"] == 1
    assert artifact["byte_exact"]["duplicate_documents"] == 0
    assert artifact["content_exact"]["duplicate_documents"] == 0
    assert census["eligible_occurrences"] == 5
    assert census["byte_exact"]["duplicate_documents"] == 2
    assert census["content_exact"]["duplicate_documents"] == 2
    assert census["near_duplicate"] == {
        "status": "unmeasured",
        "reason": "full-population body texts are not retained",
    }


def test_v2_cli_reports_population_and_sample_denominators(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec_v2(root, size=1, seed=3))

    result = CliRunner().invoke(
        main,
        ["duplicate-prevalence", "--input-spec", str(spec_path), "--out", str(tmp_path / "out.json")],
    )

    assert result.exit_code == 0, result.output
    assert "5 population observations, 1 frozen-sample observations" in result.output


def test_cli_can_emit_bound_sensitive_review_packet_without_echoing_text(tmp_path, monkeypatch):
    root = tmp_path / "PRIVATE-raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec_v2(root, size=5, seed=11))
    aggregate_path = tmp_path / "aggregate.json"
    review_path = tmp_path / "review.json"
    monkeypatch.setattr(review, "LOCAL_REVIEW_ROOT", tmp_path)

    result = CliRunner().invoke(
        main,
        [
            "duplicate-prevalence",
            "--input-spec",
            str(spec_path),
            "--out",
            str(aggregate_path),
            "--review-packet-out",
            str(review_path),
            "--review-per-stratum-quota",
            "1",
            "--review-seed",
            "897",
        ],
    )

    assert result.exit_code == 0, result.output
    aggregate = json.loads(aggregate_path.read_text(encoding="utf-8"))
    packet = json.loads(review_path.read_text(encoding="utf-8"))
    assert packet["schema"] == review.SCHEMA
    assert packet["sensitivity"] == "local-review-text"
    assert packet["intended_persistence"] == "uncommitted-local-only"
    assert packet["source"]["analyzer_artifact_hash"] == aggregate["artifact_hash"]
    assert packet["records"]
    assert all(row["label"] is None for row in packet["records"])
    assert any("duplicate body words" in row["left"]["text"] for row in packet["records"])
    assert "duplicate body words" not in aggregate_path.read_text(encoding="utf-8")
    assert "duplicate body words" not in result.output
    assert "do not commit or publish" in result.output


def test_cli_rejects_review_packet_outside_private_ignored_root(tmp_path, monkeypatch):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec_v2(root, size=5, seed=11))
    private_root = tmp_path / "private"
    private_root.mkdir()
    tracked_destination = tmp_path / "tracked-review.json"
    monkeypatch.setattr(review, "LOCAL_REVIEW_ROOT", private_root)

    result = CliRunner().invoke(
        main,
        [
            "duplicate-prevalence",
            "--input-spec",
            str(spec_path),
            "--out",
            str(tmp_path / "aggregate.json"),
            "--review-packet-out",
            str(tracked_destination),
        ],
    )

    assert result.exit_code != 0
    assert "private gitignored root" in result.output
    assert not tracked_destination.exists()
    assert not (tmp_path / "aggregate.json").exists()


def test_cli_rejects_same_aggregate_and_review_destination(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))
    destination = tmp_path / "result.json"

    result = CliRunner().invoke(
        main,
        [
            "duplicate-prevalence",
            "--input-spec",
            str(spec_path),
            "--out",
            str(destination),
            "--review-packet-out",
            str(destination),
        ],
    )

    assert result.exit_code != 0
    assert "destinations must differ" in result.output
    assert not destination.exists()


def test_v2_reservoir_is_deterministic_and_seed_only_changes_sample(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    same_a = adapter.analyze_input_spec(
        _write_spec(tmp_path / "same-a.json", _spec_v2(root, size=1, seed=1))
    )
    same_b = adapter.analyze_input_spec(
        _write_spec(tmp_path / "same-b.json", _spec_v2(root, size=1, seed=1))
    )
    changed = adapter.analyze_input_spec(
        _write_spec(tmp_path / "changed.json", _spec_v2(root, size=1, seed=99))
    )

    projection_a = same_a["input"]["source_projection_identity"]
    projection_b = same_b["input"]["source_projection_identity"]
    projection_changed = changed["input"]["source_projection_identity"]
    assert same_a == same_b
    assert projection_a["observations_digest"] == projection_b["observations_digest"]
    assert projection_a["observations_digest"] != projection_changed["observations_digest"]
    assert (
        projection_a["population_exact_census"]
        == projection_changed["population_exact_census"]
    )


@pytest.mark.parametrize("size", [0, 5_001, True])
def test_v2_rejects_out_of_range_reservoir_size(tmp_path, size):
    root = tmp_path / "raw"
    payload = _spec_v2(root, size=size)
    spec_path = _write_spec(tmp_path / "input.json", payload)

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="between 1 and 5000"):
        adapter.load_input_spec(spec_path)


def test_v2_accepts_maximum_reservoir_and_rejects_sampling_extras(tmp_path):
    root = tmp_path / "raw"
    accepted = adapter.load_input_spec(
        _write_spec(tmp_path / "accepted.json", _spec_v2(root, size=5_000))
    )
    assert accepted.source.eligible_sample is not None
    assert accepted.source.eligible_sample.size == 5_000

    payload = _spec_v2(root)
    payload["source"]["eligible_sample"]["extra"] = "forbidden"
    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="eligible_sample"):
        adapter.load_input_spec(_write_spec(tmp_path / "extra.json", payload))


def test_v2_projection_validation_rejects_census_and_sampling_tampering():
    body = "duplicate body words"
    raw_digest = hashlib.sha256(body.encode("utf-8")).hexdigest()
    observations = [
        prevalence.DocumentObservation(
            opaque_id=f"sample-{index}",
            raw_sha256=raw_digest,
            extracted_text=body,
            format_id="enron-email-body",
            source_kind=prevalence.SOURCE_BODY_PROXY,
            extraction_status="success",
        )
        for index in range(2)
    ]
    signature = "a" * 64
    projection = prevalence.build_enron_sampled_source_projection_identity(
        observations,
        corpus_signature=signature,
        min_words=3,
        stage_counts={
            "raw_member": 3,
            "parsed_body": 3,
            "eligible_body": 3,
            "retained_body": 2,
        },
        sample_size=2,
        sample_seed=7,
        raw_body_digest_counts={raw_digest: 2, "b" * 64: 1},
        normalized_content_digest_counts={raw_digest: 2, "b" * 64: 1},
    )
    assert prevalence.validate_source_projection_identity(
        projection, observations, corpus_signature=signature
    ) == projection

    tampered_census = deepcopy(projection)
    tampered_census["population_exact_census"]["byte_exact"]["duplicate_documents"] = 0
    with pytest.raises(prevalence.DuplicatePrevalenceError, match="size histogram"):
        prevalence.validate_source_projection_identity(
            tampered_census, observations, corpus_signature=signature
        )

    tampered_frame = deepcopy(projection)
    tampered_frame["sampling"]["population_count"] = 2
    with pytest.raises(prevalence.DuplicatePrevalenceError, match="sampling counts"):
        prevalence.validate_source_projection_identity(
            tampered_frame, observations, corpus_signature=signature
        )


@pytest.mark.parametrize(
    "mutation",
    [
        lambda payload: payload["source"].pop("eligible_sample"),
        lambda payload: payload["source"]["eligible_sample"].update(seed=-1),
        lambda payload: payload["source"]["eligible_sample"].update(method="other"),
    ],
)
def test_v2_strict_sampling_fields_fail_closed(tmp_path, mutation):
    payload = _spec_v2(tmp_path / "raw")
    mutation(payload)
    with pytest.raises(adapter.EnronDuplicatePrevalenceError):
        adapter.load_input_spec(_write_spec(tmp_path / "input.json", payload))


def test_repeat_is_deterministic_and_artifact_contains_no_source_disclosures(tmp_path):
    root = tmp_path / "PRIVATE-raw-root"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    first = adapter.analyze_input_spec(spec_path)
    second = adapter.analyze_input_spec(spec_path)

    assert first == second
    assert first["artifact_hash"] == second["artifact_hash"]
    serialized = json.dumps(first, sort_keys=True)
    for forbidden in (
        str(root),
        "PRIVATE-raw-root",
        "mail/a",
        "mail/b",
        "mail/c",
        "mail/d",
        "mail/e",
        "mail/f",
        "duplicate body words",
        "alpha beta gamma",
        "red blue green",
        "one two three",
        "PRIVATE_SHORT_BODY_TOKEN",
    ):
        assert forbidden not in serialized


def test_v2_artifact_contains_no_population_paths_or_text(tmp_path):
    root = tmp_path / "PRIVATE-v2-raw-root"
    _write_archive(root)
    artifact = adapter.analyze_input_spec(
        _write_spec(tmp_path / "input-v2.json", _spec_v2(root, size=1, seed=5))
    )

    serialized = json.dumps(artifact, sort_keys=True)
    for forbidden in (
        str(root),
        "PRIVATE-v2-raw-root",
        "mail/a",
        "mail/f",
        "duplicate body words",
        "PRIVATE_SHORT_BODY_TOKEN",
    ):
        assert forbidden not in serialized


@pytest.mark.parametrize(
    "mutation",
    [
        lambda payload: payload.update(extra=True),
        lambda payload: payload.pop("analysis"),
        lambda payload: payload["source"].update(extra=True),
        lambda payload: payload["analysis"].pop("seed"),
        lambda payload: payload.update(schema="wrong.v1"),
    ],
)
def test_spec_rejects_extra_missing_and_wrong_version_fields(tmp_path, mutation):
    root = tmp_path / "raw"
    _write_archive(root)
    payload = _spec(root)
    mutation(payload)
    spec_path = _write_spec(tmp_path / "input.json", payload)

    with pytest.raises(adapter.EnronDuplicatePrevalenceError):
        adapter.analyze_input_spec(spec_path)


def test_spec_rejects_duplicate_json_keys(tmp_path):
    spec_path = tmp_path / "input.json"
    spec_path.write_text(
        '{"schema":"jseval.duplicate-prevalence-input.v1",'
        '"schema":"jseval.duplicate-prevalence-input.v1","source":{},"analysis":{}}',
        encoding="utf-8",
    )
    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="duplicate JSON"):
        adapter.load_input_spec(spec_path)


def test_tarball_path_traversal_is_rejected(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root, "../enron-source.tar.gz"))

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="dot-dot"):
        adapter.analyze_input_spec(spec_path)


def test_traversal_member_name_is_rejected_without_extraction(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root, [("../escape", _message("enough body words"))])
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="tar member name"):
        adapter.analyze_input_spec(spec_path)
    assert not (tmp_path / "escape").exists()


def test_nonregular_directory_members_are_ignored(tmp_path):
    root = tmp_path / "raw"
    root.mkdir(parents=True)
    archive_path = root / "enron-source.tar.gz"
    with tarfile.open(archive_path, "w:gz") as archive:
        directory = tarfile.TarInfo("mail/")
        directory.type = tarfile.DIRTYPE
        archive.addfile(directory)
        payload = _message("enough body words")
        message = tarfile.TarInfo("mail/a")
        message.size = len(payload)
        archive.addfile(message, io.BytesIO(payload))
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    artifact = adapter.analyze_input_spec(spec_path)

    assert artifact["denominators"]["source_observations"] == 1
    assert artifact["input"]["source_projection_identity"]["stage_counts"]["raw_member"] == 1


def test_invalid_analysis_config_fails_before_reading_source(tmp_path):
    missing_root = tmp_path / "does-not-exist"
    payload = _spec(missing_root)
    payload["analysis"]["max_hamming"] = "invalid"
    spec_path = _write_spec(tmp_path / "input.json", payload)

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="invalid analysis configuration"):
        adapter.load_input_spec(spec_path)


def test_raw_root_lone_surrogate_is_a_click_error_without_traceback(tmp_path):
    payload = _spec(tmp_path / "placeholder")
    payload["source"]["raw_root"] = "bad\ud800root"
    spec_path = _write_spec(tmp_path / "input.json", payload)

    result = CliRunner().invoke(
        main,
        ["duplicate-prevalence", "--input-spec", str(spec_path), "--out", str(tmp_path / "out.json")],
    )

    assert result.exit_code != 0
    assert "valid UTF-8" in result.output
    assert "Traceback" not in result.output


def test_duplicate_regular_member_names_are_rejected(tmp_path):
    root = tmp_path / "raw"
    _write_archive(
        root,
        [
            ("mail/same", _message("first body words")),
            ("mail/same", _message("second body words")),
        ],
    )
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="duplicate regular member"):
        adapter.analyze_input_spec(spec_path)


def test_raw_root_must_contain_only_declared_archive(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    (root / "extra.txt").write_text("not part of the declared source", encoding="utf-8")
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="exactly the declared"):
        adapter.analyze_input_spec(spec_path)


def test_production_source_fails_with_explicit_p6_requirement(tmp_path):
    payload = {
        "schema": adapter.INPUT_SCHEMA,
        "source": {"kind": "production-extracted"},
        "analysis": _analysis(),
    }
    spec_path = _write_spec(tmp_path / "input.json", payload)

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="P6"):
        adapter.load_input_spec(spec_path)


def test_cli_dispatches_production_source_to_snapshot_adapter(tmp_path, monkeypatch):
    payload = {
        "schema": adapter.INPUT_SCHEMA,
        "source": {
            "kind": production_adapter.SOURCE_KIND,
            "raw_root": str(tmp_path / "raw"),
            "base_url": "http://127.0.0.1:33221",
        },
        "analysis": _analysis(),
    }
    spec_path = _write_spec(tmp_path / "input.json", payload)
    artifact = {
        "denominators": {"source_observations": 3},
        "near_duplicate": {"decision": {"status": "UNDECIDED"}},
        "privacy": {"mode": "aggregate-only"},
    }
    seen = []

    def fake_analyze(path):
        seen.append(Path(path))
        return artifact

    monkeypatch.setattr(production_adapter, "analyze_input_spec", fake_analyze)
    output_path = tmp_path / "out.json"

    result = CliRunner().invoke(
        main,
        ["duplicate-prevalence", "--input-spec", str(spec_path), "--out", str(output_path)],
    )

    assert result.exit_code == 0, result.output
    assert seen == [spec_path]
    assert json.loads(output_path.read_text(encoding="utf-8")) == artifact


def test_cli_is_registered_writes_atomically_and_supports_global_json(tmp_path):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))
    output_path = tmp_path / "nested" / "result.json"
    runner = CliRunner()

    help_result = runner.invoke(main, ["duplicate-prevalence", "--help"])
    assert help_result.exit_code == 0, help_result.output
    assert "--input-spec" in help_result.output
    assert "--out" in help_result.output

    result = runner.invoke(
        main,
        [
            "--json",
            "duplicate-prevalence",
            "--input-spec",
            str(spec_path),
            "--out",
            str(output_path),
        ],
    )
    assert result.exit_code == 0, result.output
    stdout_artifact = json.loads(result.output)
    disk_artifact = json.loads(output_path.read_text(encoding="utf-8"))
    assert stdout_artifact == disk_artifact
    assert not list(output_path.parent.glob(f".{output_path.name}.*.tmp"))


def test_cli_converts_invalid_spec_to_click_error_without_traceback(tmp_path):
    spec_path = _write_spec(tmp_path / "input.json", {"schema": "wrong"})
    result = CliRunner().invoke(
        main,
        ["duplicate-prevalence", "--input-spec", str(spec_path), "--out", str(tmp_path / "out.json")],
    )
    assert result.exit_code != 0
    assert "Error:" in result.output
    assert "Traceback" not in result.output


def test_atomic_writer_preserves_existing_destination_when_replace_fails(tmp_path, monkeypatch):
    destination = tmp_path / "result.json"
    destination.write_bytes(b"previous-result\n")

    def fail_replace(_source, _destination):
        raise OSError("injected replacement failure")

    monkeypatch.setattr(adapter.os, "replace", fail_replace)

    with pytest.raises(OSError, match="injected replacement failure"):
        adapter.write_artifact_atomic(destination, {"new": "result"})

    assert destination.read_bytes() == b"previous-result\n"
    assert not list(tmp_path.glob(f".{destination.name}.*.tmp"))


def test_post_analysis_manifest_revalidation_fails_closed(tmp_path, monkeypatch):
    root = tmp_path / "raw"
    _write_archive(root)
    spec_path = _write_spec(tmp_path / "input.json", _spec(root))

    def fail_revalidation(_root, _manifest):
        raise adapter.RawCorpusManifestError("injected corpus drift")

    monkeypatch.setattr(adapter, "validate_raw_manifest", fail_revalidation)

    with pytest.raises(adapter.EnronDuplicatePrevalenceError, match="changed during"):
        adapter.analyze_input_spec(spec_path)
