"""SPIKE test — Pillar 2 canonical schema + validator (tempdoc 404
§3.2). Exercises the schema against realistic span shapes produced
by NdjsonSpanExporter.

Validates tempdoc 405 §P2.C.2 claim: a single JSON file + Python
readers + (stubbed) Java readers = sufficient for MVP, no IDL needed."""

from __future__ import annotations

import pytest

from jseval.projections._spike_schema import (
    CANONICAL_SCHEMA,
    export_for_java,
    identity_attrs_for_kind,
    required_attrs_for_kind,
    structural_field_names,
    validate_span,
)


class TestSchemaIntrospection:
    def test_structural_fields_cover_real_span_envelope(self):
        """Every field the NdjsonSpanExporter writes structurally must
        appear in the schema's span_structural_fields list."""
        names = structural_field_names()
        for expected in [
            "trace_id", "span_id", "parent_span_id", "name",
            "start", "end", "duration_ms", "status", "attrs",
        ]:
            assert expected in names, f"missing structural field: {expected}"

    def test_duration_ms_declared_with_source(self):
        """D-1 fix: duration_ms is nano-sourced. Schema documents this
        so consumers can't silently rely on ms-precision start/end."""
        fields = {f["name"]: f for f in
                  CANONICAL_SCHEMA["span_structural_fields"]}
        dur = fields["duration_ms"]
        assert dur["type"] == "double"
        assert "source" in dur
        assert "nanos" in dur["source"].lower() or "1e6" in dur["source"]

    def test_required_attrs_for_encoder_ort_run(self):
        attrs = required_attrs_for_kind("encoder.ort_run")
        assert set(attrs) == {
            "encoder.name", "encoder.gpu",
            "encoder.batch_size", "encoder.seq_len",
        }

    def test_identity_attrs_for_search_retrieval(self):
        """§23.9.4 #10: all 8 commit.* attrs are all_of-required.
        Schema should structurally enforce this."""
        ids = identity_attrs_for_kind("search/retrieval")
        assert len(ids) == 8
        for expected in [
            "commit.schema_fp", "commit.field_catalog_hash",
            "commit.analyzer_fp", "commit.synonyms_hash",
            "commit.grammar_hash", "commit.similarity_fp",
            "commit.boosts_fp", "commit.index_schema_fp",
        ]:
            assert expected in ids

    def test_searcher_generation_struct_has_both_forms(self):
        """§23.9.4 #9: searcher_generation schema declares both
        string id AND numeric epoch_ms. Consumers get both forms."""
        sg = CANONICAL_SCHEMA["attrs"]["search.searcher_generation"]
        assert sg["type"] == "struct"
        field_names = {f["name"] for f in sg["fields"]}
        assert "id" in field_names
        assert "epoch_ms" in field_names


class TestValidator:
    def test_well_formed_span_validates(self):
        span = {
            "trace_id": "t", "span_id": "s", "parent_span_id": None,
            "name": "encoder.ort_run", "start": "...", "end": "...",
            "duration_ms": 1.5, "status": "UNSET",
            "attrs": {
                "encoder.name": "embed", "encoder.gpu": "true",
                "encoder.batch_size": "16", "encoder.seq_len": "128",
            },
        }
        assert validate_span(span) == []

    def test_missing_structural_field_flagged(self):
        """No duration_ms → validator complains. Producer that
        regressed would fail its own emit-side validator on first
        emission, not silently ship."""
        span = {
            "trace_id": "t", "span_id": "s", "parent_span_id": None,
            "name": "encoder.ort_run", "start": "...", "end": "...",
            # NO duration_ms
            "status": "UNSET",
            "attrs": {"encoder.name": "embed", "encoder.gpu": "true",
                      "encoder.batch_size": "16", "encoder.seq_len": "128"},
        }
        errs = validate_span(span)
        assert any("duration_ms" in e for e in errs)

    def test_missing_required_attr_flagged(self):
        span = {
            "trace_id": "t", "span_id": "s", "parent_span_id": None,
            "name": "encoder.ort_run", "start": "...", "end": "...",
            "duration_ms": 1.0, "status": "UNSET",
            "attrs": {"encoder.name": "embed"},
            # missing encoder.gpu, encoder.batch_size, encoder.seq_len
        }
        errs = validate_span(span)
        # All 3 missing required attrs flagged.
        assert sum(1 for e in errs if "required attr" in e) == 3

    def test_missing_identity_all_of_flagged(self):
        """§23.9.4 #10 in action: search/retrieval without all 8
        commit.* attrs is a schema violation. Emission-side guard."""
        span = {
            "trace_id": "t", "span_id": "s", "parent_span_id": None,
            "name": "search/retrieval", "start": "...", "end": "...",
            "duration_ms": 1.0, "status": "UNSET",
            "attrs": {
                "search.mode": "HYBRID",
                "commit.schema_fp": "abc",
                # missing 7 other commit.* attrs
            },
        }
        errs = validate_span(span)
        # 7 identity-all_of errors expected.
        ident_errs = [e for e in errs if "identity-all_of" in e]
        assert len(ident_errs) == 7


class TestCrossLanguageExport:
    def test_java_export_renders(self):
        """Java-side consumer of the schema gets a generated constant.
        Spike demonstrates the shape; production would emit to a
        source-generated file committed to the repo."""
        java = export_for_java()
        assert "REQUIRED_ATTRS" in java
        assert "encoder.ort_run" in java
        assert "encoder.name" in java
        assert "search/retrieval" in java

    def test_single_schema_file_drives_both_sides(self):
        """Conceptual check: the ONE canonical schema object drives
        both the Python validator + the Java export. Drift between
        sides is impossible because both read from the same dict."""
        # Python side
        py_kinds = set(CANONICAL_SCHEMA["span_kinds"].keys())
        # Java side (what would be generated)
        java_snippet = export_for_java()
        for k in py_kinds:
            assert k in java_snippet, (
                f"Java export missing kind {k!r} that Python schema declares"
            )


class TestH1Response:
    """The adversarial review's H1 claim: 'Pillar 2 Python-first
    degrades to the current state renamed.' This test class pins the
    counter-argument into code — validates that a single JSON source
    + both-side validation IS structurally different from today's
    hand-maintained allowlists."""

    def test_schema_drift_would_fail_a_single_contract_test(self):
        """If a Java emitter emits `encoder.seq_length` instead of
        `encoder.seq_len`, the Python validator flags it when the
        consumer reads the span. H1 says this is "the same as today."
        Counter: today's failure mode is SILENT (D-1); this one fails
        LOUDLY at first ingestion of a real span."""
        span_with_typo = {
            "trace_id": "t", "span_id": "s", "parent_span_id": None,
            "name": "encoder.ort_run", "start": "...", "end": "...",
            "duration_ms": 1.0, "status": "UNSET",
            "attrs": {
                "encoder.name": "embed", "encoder.gpu": "true",
                "encoder.batch_size": "16",
                "encoder.seq_length": "128",  # typo vs encoder.seq_len
            },
        }
        errs = validate_span(span_with_typo)
        # "encoder.seq_len" required; missing flags loudly.
        assert any("encoder.seq_len" in e for e in errs)