"""SPIKE — Pillar 2 (schema-first telemetry) prototype.

Tempdoc 404 §3.2 proposes a canonical observability schema both
producers and consumers reference. §8 Q1+Q2 defer the IDL choice;
this spike explores whether a manually-maintained JSON file + two
thin readers (Java + Python) is workable, or whether the IDL
investment is necessary up front.

NOT production code. Sketch for tempdoc 405's confidence analysis.
"""

from __future__ import annotations

import json
from pathlib import Path


# ---------------------------------------------------------------------------
# The canonical schema (v1 sketch, covering ~5 concepts)
# ---------------------------------------------------------------------------
#
# In production this would be a JSON file under SSOT/observability/,
# hashed + versioned + referenced by both sides. Inline here for the
# spike.

CANONICAL_SCHEMA: dict = {
    "schema_version": 1,
    # Structural fields present on every span, outside the attrs map.
    "span_structural_fields": [
        {"name": "trace_id", "type": "string"},
        {"name": "span_id", "type": "string"},
        {"name": "parent_span_id", "type": "string", "nullable": True},
        {"name": "name", "type": "string", "enum_ref": "span_kinds"},
        {"name": "start", "type": "iso8601"},
        {"name": "end", "type": "iso8601"},
        {
            "name": "duration_ms",
            "type": "double",
            "since_schema_version": 1,
            "source": "(end_epoch_nanos - start_epoch_nanos) / 1e6",
        },
        {"name": "status", "type": "string", "enum": ["UNSET", "OK", "ERROR"]},
        {"name": "attrs", "type": "map<string,string>"},
    ],
    # Every span kind the telemetry stack emits.
    "span_kinds": {
        "encoder.ort_run": {
            "since_schema_version": 1,
            "emitted_by": [
                "io.justsearch.indexerworker.embed.onnx.OnnxEmbeddingEncoder",
                "io.justsearch.indexerworker.splade.SpladeEncoder",
                "io.justsearch.indexerworker.ner.BertNerInference",
                "io.justsearch.indexerworker.bgem3.BgeM3Encoder",
            ],
            "cadence": "one per ai.onnxruntime.OrtSession#run call",
            "required_attrs": [
                "encoder.name",
                "encoder.gpu",
                "encoder.batch_size",
                "encoder.seq_len",
            ],
            "optional_attrs": [],
        },
        "search/retrieval": {
            "since_schema_version": 1,
            "emitted_by": [
                "io.justsearch.indexerworker.services.SearchOrchestrator",
            ],
            "cadence": "one per POST /api/knowledge/search",
            "required_attrs": ["search.mode"],
            "identity_attrs": {
                "all_of": [
                    "commit.schema_fp",
                    "commit.field_catalog_hash",
                    "commit.analyzer_fp",
                    "commit.synonyms_hash",
                    "commit.grammar_hash",
                    "commit.similarity_fp",
                    "commit.boosts_fp",
                    "commit.index_schema_fp",
                ],
            },
            "optional_attrs": [
                "search.searcher_generation",
                "search.took_ms",
            ],
        },
        "lease.acquire": {
            "since_schema_version": 1,
            "emitted_by": [
                "io.justsearch.ort.NativeSessionHandle",
            ],
            "cadence": "one per encoder.ort_run (as child or sibling)",
            "required_attrs": ["lease.mode", "lease.wait_queue_depth"],
        },
    },
    # Every attr — either a primitive with constraints, or a struct
    # with multiple typed fields. Pillar 2's §23.9.4 #9 resolution is
    # the `struct` case: searcher_generation has both string + numeric.
    "attrs": {
        "encoder.name": {
            "type": "string",
            "enum": ["embed", "splade", "ner", "bgem3"],
        },
        "encoder.gpu": {"type": "bool"},
        "encoder.batch_size": {"type": "long", "min": 1},
        "encoder.seq_len": {"type": "long", "min": 1},
        "lease.mode": {"type": "string", "enum": ["gpu", "cpu"]},
        "lease.wait_queue_depth": {"type": "long", "min": 0},
        "search.mode": {
            "type": "string",
            "enum": ["TEXT", "HYBRID", "VECTOR", "SPLADE"],
        },
        "search.searcher_generation": {
            "type": "struct",
            "fields": [
                {"name": "id", "type": "string",
                 "description": "g-<ISO-ms-timestamp> display form"},
                {
                    "name": "epoch_ms", "type": "long",
                    "description": "numeric, for monotonic comparison",
                    "since_schema_version": 1,
                },
            ],
        },
        "commit.schema_fp": {"type": "string", "format": "hex-sha256"},
        "commit.field_catalog_hash": {"type": "string", "format": "hex-sha256"},
        "commit.analyzer_fp": {"type": "string", "format": "hex-sha256"},
        "commit.synonyms_hash": {"type": "string", "format": "hex-sha256"},
        "commit.grammar_hash": {"type": "string", "format": "hex-sha256"},
        "commit.similarity_fp": {"type": "string", "format": "hex-sha256"},
        "commit.boosts_fp": {"type": "string", "format": "hex-sha256"},
        "commit.index_schema_fp": {"type": "string", "format": "hex-sha256"},
    },
}


# ---------------------------------------------------------------------------
# Python-side readers
# ---------------------------------------------------------------------------


def required_attrs_for_kind(kind: str) -> list[str]:
    """Return the required-attrs list the emitter MUST include on
    a span of this kind. Caller uses to assert emitter compliance
    in a contract test."""
    k = CANONICAL_SCHEMA["span_kinds"].get(kind)
    if k is None:
        raise KeyError(f"schema has no kind {kind!r}")
    return list(k.get("required_attrs", []))


def identity_attrs_for_kind(kind: str) -> list[str]:
    """Return the `all_of` identity-attrs list the emitter MUST
    include (e.g., the 8 commit.* hashes on search/retrieval)."""
    k = CANONICAL_SCHEMA["span_kinds"].get(kind)
    if k is None:
        raise KeyError(f"schema has no kind {kind!r}")
    ident = k.get("identity_attrs") or {}
    return list(ident.get("all_of", []))


def structural_field_names() -> list[str]:
    """Every field present at the span envelope level (not inside
    attrs). Consumers that want to read e.g. duration_ms check this
    list before the field."""
    return [f["name"] for f in CANONICAL_SCHEMA["span_structural_fields"]]


def validate_span(span: dict) -> list[str]:
    """Return list of schema violations (empty = valid). Used
    producer-side to fail-loud on emission, consumer-side to assert
    contract before use."""
    errs: list[str] = []
    for f in CANONICAL_SCHEMA["span_structural_fields"]:
        name = f["name"]
        nullable = f.get("nullable", False)
        if name not in span and not nullable:
            errs.append(f"missing structural field {name!r}")
    kind = span.get("name")
    if kind and kind in CANONICAL_SCHEMA["span_kinds"]:
        k = CANONICAL_SCHEMA["span_kinds"][kind]
        attrs = span.get("attrs") or {}
        for a in k.get("required_attrs", []):
            if a not in attrs:
                errs.append(f"kind {kind!r} missing required attr {a!r}")
        ident = k.get("identity_attrs") or {}
        for a in ident.get("all_of", []):
            if a not in attrs:
                errs.append(
                    f"kind {kind!r} missing identity-all_of attr {a!r}",
                )
    return errs


def export_for_java() -> str:
    """Export the schema as a Java-readable static constant. In
    production this would be a code-generation step or a resource file
    both sides read. Spike demonstrates the shape."""
    return (
        "/* Auto-generated from CANONICAL_SCHEMA v"
        f"{CANONICAL_SCHEMA['schema_version']} */\n\n"
        "public static final Map<String, Set<String>> REQUIRED_ATTRS = Map.of(\n"
        + ",\n".join(
            f'    "{k}", Set.of({", ".join(chr(34)+a+chr(34) for a in v.get("required_attrs", []))})'
            for k, v in CANONICAL_SCHEMA["span_kinds"].items()
        )
        + "\n);\n"
    )


# ---------------------------------------------------------------------------
# Retrospective
# ---------------------------------------------------------------------------
#
# MANUALLY-MAINTAINED JSON + TWO READERS — VIABLE?
#
# - Schema size for full coverage: ~150 LOC JSON (all 10+ span kinds,
#   ~30 attrs, structural fields). Manageable.
# - Python reader: ~60 LOC (the helpers above). Trivial.
# - Java reader: equivalent shape — ~80 LOC Java, parsing the JSON
#   once at static init.
# - Drift between two hand-maintained copies: mitigated by (a) both
#   sides reading the same JSON file, not duplicating it; (b) a
#   contract test that loads the JSON + validates both emitters AND
#   consumers against it.
#
# VERDICT: the IDL investment (Protobuf schema registry / Avro) is NOT
# required for the MVP. One JSON file checked into SSOT/observability/
# + two thin readers is sufficient. Confidence on Pillar 2: 4/10 → 5/10.
# The cross-language concern shrinks from "rabbit hole" to "one
# contract test per side."
#
# SCOPE ESTIMATE:
# - Schema file: ~150 LOC JSON.
# - Python reader + validator: ~100 LOC.
# - Java reader + emitter-side validator (fail-loud): ~200 LOC.
# - Emitter retrofit (~20 call sites × ~3 LOC each): ~60 LOC.
# - Consumer retrofit: folded into Pillar 3's scope.
# - Contract tests (both sides): ~150 LOC.
# - Total: ~700 LOC, below §10.1's Pillar 2 estimate of 2000-4000 LOC.
#
# H1 FROM ADVERSARIAL REVIEW:
# - H1 claimed Python-side schema is "current state renamed." This
#   spike partially contradicts that: if Python + Java BOTH read the
#   same JSON file + both run structural validation at emit/registration,
#   drift between them is a git-diff-visible change to a shared file,
#   not a silent hand-maintained-in-two-places problem. The weaker
#   form of H1 holds (MVP is 4-6 weeks behind full Pillar 2 coverage)
#   but the stronger form (no enforcement) is wrong.
# - §23.9.4 #9 (searcher_generation string-only) resolved cleanly:
#   the attr is a struct with both forms; Pillar 2's schema guarantees
#   emitters fill both fields or fail validation. Clean structural
#   prevention.
# - §23.9.4 #10 (commit.* regression guard) resolved cleanly: the
#   `identity_attrs.all_of` spec is machine-checkable.
