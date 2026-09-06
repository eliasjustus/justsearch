from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path

import pytest

jsonschema = pytest.importorskip("jsonschema", reason="jseval[dev] extra")

from jseval import duplicate_prevalence as dp


SCHEMA_PATH = Path(__file__).parents[1] / "duplicate-prevalence.v1.schema.json"
def _digest(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _canonical_digest(value: object) -> str:
    payload = json.dumps(
        value,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _corpus_identity(count: int) -> dict:
    return {
        "profile_id": None,
        "signature": _digest("corpus"),
        "kind": "raw-files",
        "schema": "jseval.raw-corpus-manifest.v1",
        "file_count": count,
        "total_bytes": count * 10,
        "manifest_pointer": "private/manifest.json",
        "admission_policy": {
            "JUSTSEARCH_INGESTION_SKIP_PATTERNS": "default",
            "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "default",
            "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES": "default",
        },
    }


def _observations(source_kind: str) -> list[dp.DocumentObservation]:
    return [
        dp.DocumentObservation(
            opaque_id="one",
            raw_sha256=_digest("same content"),
            extracted_text="same content",
            format_id="txt",
            source_kind=source_kind,
            extraction_status="success",
        ),
        dp.DocumentObservation(
            opaque_id="two",
            raw_sha256=_digest("same content"),
            extracted_text="same content",
            format_id="txt",
            source_kind=source_kind,
            extraction_status="success",
        ),
    ]


def _extraction_identity(
    observations: list[dp.DocumentObservation], corpus_identity: dict
) -> dict:
    unsigned = {
        "schema": dp.EXTRACTION_SNAPSHOT_SCHEMA,
        "corpus_signature": corpus_identity["signature"],
        "observations_digest": dp.observation_commitment(observations),
        "source_kind": dp.PRODUCTION_EXTRACTED,
        "extractor_build": "worker@abc123",
        "extraction_policy_digest": _digest("policy"),
        "document_count": len(observations),
        "reconciliation": {
            "status": "matched",
            "expected_count": len(observations),
            "exported_count": len(observations),
            "unique_opaque_ids": len(observations),
        },
    }
    return {"digest": _canonical_digest(unsigned), **unsigned}


def _config(thresholds: tuple[float, ...] = (0.8,)) -> dp.AnalysisConfig:
    return dp.AnalysisConfig(
        shingle_width=1,
        max_hamming=63,
        jaccard_thresholds=thresholds,
        exhaustive_slice_size=2,
        bootstrap_draws=5,
        seed=897,
        max_candidate_pairs=10,
    )


def _artifact(source_kind: str = dp.SOURCE_BODY_PROXY) -> dict:
    observations = _observations(source_kind)
    corpus_identity = _corpus_identity(len(observations))
    extraction_identity = (
        _extraction_identity(observations, corpus_identity)
        if source_kind == dp.PRODUCTION_EXTRACTED
        else None
    )
    source_projection_identity = (
        dp.build_enron_source_projection_identity(
            observations,
            corpus_signature=corpus_identity["signature"],
            min_words=60,
            stage_counts={
                "raw_member": 3,
                "parsed_body": 3,
                "eligible_body": 2,
                "retained_body": 1,
            },
        )
        if source_kind == dp.SOURCE_BODY_PROXY
        else None
    )
    return dp.analyze(
        observations,
        corpus_identity=corpus_identity,
        extraction_identity=extraction_identity,
        source_projection_identity=source_projection_identity,
        config=_config(),
    )


def _sampled_artifact(thresholds: tuple[float, ...] = (0.8,)) -> dict:
    observations = _observations(dp.SOURCE_BODY_PROXY)
    corpus_identity = _corpus_identity(len(observations))
    projection = dp.build_enron_sampled_source_projection_identity(
        observations,
        corpus_signature=corpus_identity["signature"],
        min_words=60,
        stage_counts={
            "raw_member": 3,
            "parsed_body": 3,
            "eligible_body": 3,
            "retained_body": 2,
        },
        sample_size=2,
        sample_seed=17,
        raw_body_digest_counts={_digest("same content"): 2, _digest("other"): 1},
        normalized_content_digest_counts={
            _digest("same content"): 2,
            _digest("other"): 1,
        },
    )
    return dp.analyze(
        observations,
        corpus_identity=corpus_identity,
        source_projection_identity=projection,
        config=_config(thresholds),
    )


def _bound_decision(artifact, root: Path):
    from jseval import duplicate_review_scoring as scoring
    from jseval import duplicate_review_packet as packet_module
    from tests.test_duplicate_review_scoring import _campaign

    previous_root = packet_module.LOCAL_REVIEW_ROOT
    packet_module.LOCAL_REVIEW_ROOT = root
    try:
        return scoring.build_decision(*_campaign(root, "campaign", analyzer_artifact=artifact))
    finally:
        packet_module.LOCAL_REVIEW_ROOT = previous_root


def _rehash_decision(decision: dict) -> None:
    decision["artifact_hash"] = _canonical_digest(
        {key: value for key, value in decision.items() if key != "artifact_hash"}
    )


def test_apply_frozen_decision_selects_existing_sample_row_without_rescoring(monkeypatch, tmp_path):
    from jseval import duplicate_review_scoring as scoring

    source = _sampled_artifact((0.5, 0.8))
    before = copy.deepcopy(source)
    decision = _bound_decision(source, tmp_path)
    monkeypatch.setattr(scoring, "build_decision", lambda *a, **kw: pytest.fail("must not rescore"))
    monkeypatch.setattr(scoring, "_score_records", lambda *a, **kw: pytest.fail("must not rescore"))
    selected = scoring.apply_decision(
        source, decision, expected_decision_hash=decision["artifact_hash"]
    )
    jsonschema.validate(selected, json.loads(SCHEMA_PATH.read_text(encoding="utf-8")))
    dp.validate_artifact_hash(selected)
    assert source == before
    assert selected["near_duplicate"]["threshold_sweep"] == source["near_duplicate"]["threshold_sweep"]
    for block in ("algorithm", "denominators", "byte_exact", "content_exact", "near_duplicate"):
        assert selected[block]["scope"] == source[block]["scope"] == dp.ENRON_SAMPLE_SCOPE
    assert selected["near_duplicate"]["decision"]["selected_threshold"] == 0.8
    assert selected["near_duplicate"]["decision"]["analyzer_artifact_hash"] == source["artifact_hash"]
    assert selected["input"] == source["input"]
    assert selected["denominators"] == source["denominators"]


@pytest.mark.parametrize(
    "mutation",
    [
        "incomplete_stub",
        "pin_mismatch",
        "cohort_mismatch",
        "inconsistent_holdout_threshold",
        "no_holdout",
        "unknown_rule",
        "wrong_selected_objective",
        "nonwinning_threshold",
        "duplicate_calibration_threshold",
        "missing_calibration_threshold",
    ],
)
def test_apply_frozen_decision_rejects_unbound_or_inconsistent_decision(mutation, tmp_path):
    from jseval import duplicate_review_scoring as scoring

    source = _sampled_artifact((0.5, 0.8))
    decision = _bound_decision(source, tmp_path)
    expected_hash = decision["artifact_hash"]
    if mutation == "incomplete_stub":
        decision.pop("adjudication")
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "pin_mismatch":
        expected_hash = "f" * 64
    elif mutation == "cohort_mismatch":
        decision["input"]["analyzer_artifact_hash"] = "f" * 64
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "inconsistent_holdout_threshold":
        decision["holdout"]["selected_threshold"] = 0.5
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "no_holdout":
        decision["review_summary"]["holdout"]["binary_count"] = 0
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "unknown_rule":
        decision["prediction_rule"] = "unvalidated-threshold-only"
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "wrong_selected_objective":
        decision["selection"]["objective"] = "wrong-objective"
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "nonwinning_threshold":
        for key in ("decision", "selection", "holdout"):
            decision[key]["selected_threshold"] = 0.5
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "duplicate_calibration_threshold":
        decision["selection"]["threshold_metrics"].append(
            copy.deepcopy(decision["selection"]["threshold_metrics"][0])
        )
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    elif mutation == "missing_calibration_threshold":
        decision["selection"]["threshold_metrics"].pop()
        _rehash_decision(decision)
        expected_hash = decision["artifact_hash"]
    with pytest.raises(scoring.DuplicateReviewScoringError):
        scoring.apply_decision(source, decision, expected_decision_hash=expected_hash)


def test_apply_frozen_decision_cli_preserves_inputs_and_rejects_changed_output(tmp_path):
    from click.testing import CliRunner
    from jseval.commands.analysis import cmd_duplicate_prevalence_apply_decision

    source = _artifact()
    decision = _bound_decision(source, tmp_path)
    paths = [tmp_path / name for name in ("source.json", "decision.json", "selected.json")]
    paths[0].write_text(json.dumps(source), encoding="utf-8")
    paths[1].write_text(json.dumps(decision), encoding="utf-8")
    args = [
        "--prevalence", str(paths[0]),
        "--decision", str(paths[1]),
        "--expected-decision-hash", decision["artifact_hash"],
        "--out", str(paths[2]),
    ]
    runner = CliRunner()
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args, obj={}).exit_code == 0
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args, obj={}).exit_code == 0
    assert json.loads(paths[0].read_text()) == source
    assert json.loads(paths[1].read_text()) == decision
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args[:-1] + [str(paths[0])], obj={}).exit_code != 0
    paths[2].write_text("{}", encoding="utf-8")
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args, obj={}).exit_code != 0
    assert paths[2].read_text() == "{}"

    paths[2].unlink()
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args, obj={}).exit_code == 0
    valid_output = json.loads(paths[2].read_text(encoding="utf-8"))
    jsonschema.validate(valid_output, json.loads(SCHEMA_PATH.read_text(encoding="utf-8")))

    corrupted = copy.deepcopy(valid_output)
    assert corrupted["near_duplicate"]["threshold_sweep"][0]["prevalence"] == 1.0
    corrupted["near_duplicate"]["threshold_sweep"][0]["prevalence"] = True
    corrupted["artifact_hash"] = _canonical_digest(
        {key: value for key, value in corrupted.items() if key != "artifact_hash"}
    )
    corrupted_bytes = json.dumps(corrupted, sort_keys=True).encode("utf-8")
    paths[2].write_bytes(corrupted_bytes)
    assert runner.invoke(cmd_duplicate_prevalence_apply_decision, args, obj={}).exit_code != 0
    assert paths[2].read_bytes() == corrupted_bytes


def _disposition_artifact() -> dict:
    observations = _observations(dp.PRODUCTION_EXTRACTED)
    observations[1] = dp.DocumentObservation(
        opaque_id="two",
        raw_sha256=_digest("same content"),
        extracted_text=None,
        format_id="ppt",
        source_kind=dp.PRODUCTION_EXTRACTED,
        extraction_status="failed",
    )
    corpus_identity = _corpus_identity(2)
    unsigned = {
        "schema": dp.EXTRACTION_SNAPSHOT_SCHEMA_V2,
        "corpus_signature": corpus_identity["signature"],
        "observations_digest": dp.observation_commitment(observations),
        "source_kind": dp.PRODUCTION_EXTRACTED,
        "extractor_build": "worker@abc123",
        "extraction_policy_digest": _digest("policy"),
        "document_count": 2,
        "reconciliation": {
            "status": "matched-with-disposition-accounting",
            "expected_count": 2,
            "exported_count": 2,
            "unique_opaque_ids": 2,
            "indexed_count": 1,
            "terminal_excluded_count": 1,
            "partial_success_count": 1,
            "terminal_exclusion_reasons": {
                "corrupt-or-unsupported-parser-input": 1
            },
        },
    }
    identity = {"digest": _canonical_digest(unsigned), **unsigned}
    return dp.analyze(
        observations,
        corpus_identity=corpus_identity,
        extraction_identity=identity,
        config=_config(),
    )


@pytest.fixture(scope="module")
def validator():
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    jsonschema.Draft7Validator.check_schema(schema)
    return jsonschema.Draft7Validator(schema)


@pytest.mark.parametrize("source_kind", [dp.SOURCE_BODY_PROXY, dp.PRODUCTION_EXTRACTED])
def test_analyze_artifact_validates_against_schema(validator, source_kind):
    validator.validate(_artifact(source_kind))


def test_sampled_enron_artifact_validates_against_schema(validator):
    artifact = _sampled_artifact()

    validator.validate(artifact)
    assert artifact["algorithm"]["scope"] == "frozen-uniform-eligible-body-sample"
    assert artifact["input"]["source_projection_identity"]["population_exact_census"][
        "eligible_occurrences"
    ] == 3


def test_production_disposition_artifact_validates_against_schema(validator):
    artifact = _disposition_artifact()

    validator.validate(artifact)
    assert artifact["denominators"]["extraction_partial_successes"] == 1
    assert artifact["denominators"]["terminal_excluded_documents"] == 1


def test_sampled_enron_schema_requires_explicit_analyzer_scope(validator):
    artifact = _sampled_artifact()
    artifact["near_duplicate"].pop("scope")

    assert list(validator.iter_errors(artifact))


@pytest.mark.parametrize("source_kind", [dp.SOURCE_BODY_PROXY, dp.PRODUCTION_EXTRACTED])
def test_non_sampled_artifacts_reject_sample_only_scope(validator, source_kind):
    artifact = _artifact(source_kind)
    for block in ("algorithm", "denominators", "byte_exact", "content_exact", "near_duplicate"):
        artifact[block]["scope"] = "frozen-uniform-eligible-body-sample"

    assert list(validator.iter_errors(artifact))


def test_production_artifact_rejects_source_projection_identity(validator):
    production = _artifact(dp.PRODUCTION_EXTRACTED)
    production["input"]["source_projection_identity"] = _artifact()["input"][
        "source_projection_identity"
    ]

    assert list(validator.iter_errors(production))


def test_empty_body_nullable_fields_validate(validator):
    empty_body = dp.DocumentObservation(
        opaque_id="empty",
        raw_sha256=_digest("empty body source"),
        extracted_text="",
        format_id="txt",
        source_kind=dp.SOURCE_BODY_PROXY,
        extraction_status="success",
    )
    artifact = dp.analyze(
        [empty_body], corpus_identity=_corpus_identity(1), config=_config()
    )

    validator.validate(artifact)
    assert artifact["content_exact"]["prevalence"] is None
    assert artifact["content_exact"]["stability_interval"] is None
    assert artifact["near_duplicate"]["threshold_sweep"][0]["stability_interval"] is None
    assert artifact["near_duplicate"]["exhaustive_slice"]["candidate_recall_by_threshold"][0][
        "candidate_recall"
    ] is None


def _add_root_field(artifact: dict) -> None:
    artifact["document_ids"] = ["private-id"]


def _add_nested_path(artifact: dict) -> None:
    artifact["input"]["corpus_identity"]["path"] = "private/path.txt"


def _remove_required_denominator(artifact: dict) -> None:
    artifact["denominators"].pop("source_observations")


def _malform_artifact_hash(artifact: dict) -> None:
    artifact["artifact_hash"] = "A" * 64


def _malform_observations_digest(artifact: dict) -> None:
    artifact["input"]["observations_digest"] = "not-a-sha256"


def _add_projection_member_names(artifact: dict) -> None:
    artifact["input"]["source_projection_identity"]["member_names"] = ["private/message"]


def _change_projection_policy(artifact: dict) -> None:
    artifact["input"]["source_projection_identity"]["policy"]["text_decode"] = "strict"


def _change_projection_member_order(artifact: dict) -> None:
    artifact["input"]["source_projection_identity"]["policy"][
        "member_order"
    ] = "archive-physical-order"


def _malform_projection_min_words(artifact: dict) -> None:
    artifact["input"]["source_projection_identity"]["policy"]["min_words"] = "60"


def _add_ids_to_duplicate_group(artifact: dict) -> None:
    artifact["byte_exact"]["duplicate_groups"][0]["ids"] = ["one", "two"]


def _change_decision(artifact: dict) -> None:
    artifact["near_duplicate"]["decision"]["status"] = "MEASURED"


def _select_threshold(artifact: dict) -> None:
    artifact["near_duplicate"]["decision"]["selected_threshold"] = 0.8


def _weaken_privacy(artifact: dict) -> None:
    artifact["privacy"]["paths_emitted"] = True


def _mix_source_interpretations(artifact: dict) -> None:
    artifact["content_exact"]["interpretation"] = "production-extracted-content"


def _claim_proxy_extraction_identity(artifact: dict) -> None:
    production = _artifact(dp.PRODUCTION_EXTRACTED)
    artifact["input"]["extraction_identity"] = production["input"]["extraction_identity"]


def _change_algorithm_constant(artifact: dict) -> None:
    artifact["algorithm"]["content_normalization"] = "unspecified"


def _add_extracted_text(artifact: dict) -> None:
    artifact["content_exact"]["text"] = "private text"


def _malform_candidate_recall(artifact: dict) -> None:
    row = artifact["near_duplicate"]["exhaustive_slice"]["candidate_recall_by_threshold"][0]
    row["candidate_recall"] = None


@pytest.mark.parametrize(
    "mutate",
    [
        _add_root_field,
        _add_nested_path,
        _remove_required_denominator,
        _malform_artifact_hash,
        _malform_observations_digest,
        _add_projection_member_names,
        _change_projection_policy,
        _change_projection_member_order,
        _malform_projection_min_words,
        _add_ids_to_duplicate_group,
        _change_decision,
        _select_threshold,
        _weaken_privacy,
        _mix_source_interpretations,
        _claim_proxy_extraction_identity,
        _change_algorithm_constant,
        _add_extracted_text,
        _malform_candidate_recall,
    ],
    ids=lambda mutate: mutate.__name__,
)
def test_schema_rejects_invalid_or_privacy_leaking_mutations(validator, mutate):
    artifact = copy.deepcopy(_artifact())
    mutate(artifact)

    errors = list(validator.iter_errors(artifact))

    assert errors, f"schema accepted invalid mutation {mutate.__name__}"
