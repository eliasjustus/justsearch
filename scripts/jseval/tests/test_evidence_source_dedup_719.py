"""Run-constant `source` blocks are hoisted out of the per-observation lines.

The defect this file pins (measured 2026-07-28 on the hero logs): every exported
observation repeated its `source` block verbatim, and that block is run-constant and
~1.93 MB (dominated by `corpus_certification.certification_base64`, 2.26 MB of the
2.48 MB source-identity manifest). A 360-cell 3-stratum campaign therefore produced a
788 MB evidence file -- past GitHub's 100 MB blob limit even gzipped, so the
publication bundle that copies it was unpublishable at real campaign scale.

The fix is a layout change only, so the acceptance bar is equality: finalize over a
deduped export must produce the same composed record as finalize over the pre-fix
inline-`source` export. That is asserted here on fixtures, and on the real 2026-07-28
hero logs in the PR body (semantic_digest c5a75457...).
"""

from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from jseval.utility_evidence import (
    SCHEMA,
    SCHEMA_V2,
    SOURCE_SCHEMA,
    dedupe_source_blocks,
    evidence_schema_version,
    read_evidence,
    sanitize_observation,
    source_id,
)
from jseval.utility_recompose import finalize_evidence
from tests.test_utility_evidence import _observation


def _write(path: Path, lines: list[dict]) -> Path:
    path.write_text(
        "".join(
            json.dumps(line, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n"
            for line in lines
        ),
        encoding="utf-8",
    )
    return path


def _write_v1(path: Path, observations: list[dict]) -> Path:
    return _write(path, [sanitize_observation(item) for item in observations])


def _write_v2(path: Path, observations: list[dict]) -> Path:
    return _write(path, dedupe_source_blocks(sanitize_observation(o) for o in observations))


def _two_strata() -> list[dict]:
    """Four observations over two corpora x two conditions -- four DISTINCT sources.

    A condition's source is genuinely not run-constant across conditions in this
    harness (`search_config_cohort_key` is set for the with-tool arm only), so the
    dedup unit is (stratum, condition), not (run). That is what makes the real
    campaign shape 6 header lines for 360 cells rather than 1.
    """
    observations = []
    for dataset in ("fixture-a", "fixture-b"):
        for condition in ("A", "B"):
            item = copy.deepcopy(_observation(condition, qid="q0"))
            item["source"]["corpus"] = {"dataset": dataset, "signature": "c" * 64}
            observations.append(item)
    return observations


def _many_cells_per_source(cells: int = 10) -> list[dict]:
    """Two strata x two conditions x `cells` queries -- four sources, many cells each."""
    observations = []
    for item in _two_strata():
        for index in range(cells):
            cell = copy.deepcopy(item)
            cell["qid"] = f"q{index}"
            observations.append(cell)
    return observations


# --- 1. round-trip: reading a deduped export yields the same observations ---------


def test_deduped_export_reads_back_identical_to_the_inline_format(tmp_path):
    observations = _two_strata()
    inline = read_evidence(_write_v1(tmp_path / "v1.jsonl", observations))
    deduped = read_evidence(_write_v2(tmp_path / "v1.jsonl", observations))
    # Same file name on purpose: read_evidence stamps source.log_file from the path,
    # so reusing it proves the observations match on every OTHER field too.
    assert deduped == inline


def test_deduped_line_carries_every_field_the_inline_line_carried(tmp_path):
    sanitized = sanitize_observation(_observation("B"))
    lines = dedupe_source_blocks([sanitized])
    header, row = lines
    assert header["schema"] == SOURCE_SCHEMA
    assert header["source"] == sanitized["source"]
    assert row["schema"] == SCHEMA_V2
    assert row["source_ref"] == header["source_id"] == source_id(sanitized["source"])
    assert "source" not in row
    assert set(row) == (set(sanitized) - {"source"}) | {"source_ref"}


# --- 2. dedup actually dedups ------------------------------------------------------


def test_cells_sharing_a_source_collapse_to_one_header(tmp_path):
    shared = [_observation("A", qid=f"q{index}") for index in range(10)]
    lines = dedupe_source_blocks(sanitize_observation(item) for item in shared)
    headers = [line for line in lines if line["schema"] == SOURCE_SCHEMA]
    assert len(headers) == 1
    assert len([line for line in lines if line["schema"] == SCHEMA_V2]) == 10


def test_distinct_sources_keep_one_header_each_and_shrink_the_file(tmp_path):
    observations = _many_cells_per_source()
    lines = dedupe_source_blocks(sanitize_observation(item) for item in observations)
    headers = [line for line in lines if line["schema"] == SOURCE_SCHEMA]
    assert len(headers) == 4
    assert len({header["source_id"] for header in headers}) == 4
    assert len([line for line in lines if line["schema"] == SCHEMA_V2]) == 40

    # The size claim is the whole point, so assert it rather than assume it: with a
    # source block far larger than the rest of a line (the real shape -- 1.93 MB vs
    # a few KB), the deduped file must be dramatically smaller than the inline one.
    bulky = copy.deepcopy(_many_cells_per_source())
    for item in bulky:
        item["source"]["cohort"]["corpus_certification"] = {
            "certification_base64": "A" * 200_000,
        }
    inline_bytes = _write_v1(tmp_path / "v1.jsonl", bulky).stat().st_size
    deduped_bytes = _write_v2(tmp_path / "v2.jsonl", bulky).stat().st_size
    assert deduped_bytes * 3 < inline_bytes


# --- 3. digest equality (the acceptance bar) --------------------------------------


def test_finalize_digest_is_identical_across_the_two_formats(tmp_path):
    observations = _two_strata()
    inline = finalize_evidence(
        [_write_v1(tmp_path / "observations.v1.jsonl", observations)], composed_at="fixed")
    deduped = finalize_evidence(
        [_write_v2(tmp_path / "observations.v1.jsonl", observations)], composed_at="fixed")
    assert deduped["semantic_digest"] == inline["semantic_digest"]
    assert deduped == inline


# --- 4. back-compat: the old format keeps working ---------------------------------


def test_old_format_evidence_is_still_accepted(tmp_path):
    path = _write_v1(tmp_path / "v1.jsonl", [_observation("A"), _observation("B")])
    restored = read_evidence(path)
    assert [item["condition"] for item in restored] == ["A", "B"]
    assert restored[0]["source"]["cohort"]["cli_version"] == "1"
    assert evidence_schema_version(path) == SCHEMA


def test_committed_rejected_fixture_still_reads(tmp_path):
    fixture = (
        Path(__file__).parent / "fixtures" / "agent-utility-rejected-2026-07-12"
        / "observations.v1.jsonl"
    )
    assert evidence_schema_version(fixture) == SCHEMA
    assert read_evidence(fixture)


def test_freshly_exported_evidence_reports_the_v2_schema_version(tmp_path):
    path = _write_v2(tmp_path / "v2.jsonl", [_observation("A")])
    assert evidence_schema_version(path) == SCHEMA_V2


# --- 5. the reader fails closed on every malformed dedup shape --------------------


def test_observation_referencing_an_undeclared_source_is_rejected(tmp_path):
    lines = dedupe_source_blocks([sanitize_observation(_observation("A"))])
    path = _write(tmp_path / "v2.jsonl", [line for line in lines if line["schema"] == SCHEMA_V2])
    with pytest.raises(ValueError, match="undeclared evidence source"):
        read_evidence(path)


def test_source_header_whose_id_disagrees_with_its_content_is_rejected(tmp_path):
    lines = dedupe_source_blocks([sanitize_observation(_observation("A"))])
    lines[0]["source"] = dict(lines[0]["source"], model_alias="tampered")
    path = _write(tmp_path / "v2.jsonl", lines)
    with pytest.raises(ValueError, match="does not match its content"):
        read_evidence(path)


def test_v2_line_carrying_an_inline_source_is_rejected(tmp_path):
    sanitized = sanitize_observation(_observation("A"))
    lines = dedupe_source_blocks([sanitized])
    lines[1]["source"] = sanitized["source"]
    path = _write(tmp_path / "v2.jsonl", lines)
    with pytest.raises(ValueError, match="must carry exactly source_ref"):
        read_evidence(path)


def test_v1_line_carrying_a_source_ref_is_rejected(tmp_path):
    sanitized = sanitize_observation(_observation("A"))
    sanitized["source_ref"] = source_id(sanitized["source"])
    path = _write(tmp_path / "v1.jsonl", [sanitized])
    with pytest.raises(ValueError, match="must carry exactly source"):
        read_evidence(path)


def test_malformed_source_header_is_rejected(tmp_path):
    path = _write(tmp_path / "v2.jsonl", [{"schema": SOURCE_SCHEMA, "source": {}}])
    with pytest.raises(ValueError, match="malformed evidence source header"):
        read_evidence(path)


def test_source_header_with_an_unknown_source_field_is_rejected(tmp_path):
    source = {"model_alias": "haiku", "smuggled_raw_text": "corpus secret"}
    path = _write(
        tmp_path / "v2.jsonl",
        [{"schema": SOURCE_SCHEMA, "source_id": source_id(source), "source": source}],
    )
    with pytest.raises(ValueError, match="unknown observation source fields"):
        read_evidence(path)


# --- 6. schema files describe exactly what the writer emits -----------------------


def test_emitted_lines_validate_against_their_schemas(tmp_path):
    jsonschema = pytest.importorskip("jsonschema")
    root = Path(__file__).parents[1]
    observation_schema = json.loads(
        (root / "agent-utility-observation.v1.schema.json").read_text(encoding="utf-8"))
    source_schema = json.loads(
        (root / "agent-utility-evidence-source.v1.schema.json").read_text(encoding="utf-8"))
    for line in dedupe_source_blocks(sanitize_observation(o) for o in _two_strata()):
        schema = source_schema if line["schema"] == SOURCE_SCHEMA else observation_schema
        jsonschema.validate(line, schema)


def test_observation_schema_forbids_carrying_both_source_and_source_ref():
    jsonschema = pytest.importorskip("jsonschema")
    schema = json.loads(
        (Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json")
        .read_text(encoding="utf-8"))
    sanitized = sanitize_observation(_observation("A"))
    both = dict(sanitized, source_ref=source_id(sanitized["source"]))
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(both, schema)
    neither = {key: value for key, value in sanitized.items() if key != "source"}
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(neither, schema)
    v2_with_inline_source = dict(sanitized, schema=SCHEMA_V2)
    with pytest.raises(jsonschema.ValidationError):
        jsonschema.validate(v2_with_inline_source, schema)
