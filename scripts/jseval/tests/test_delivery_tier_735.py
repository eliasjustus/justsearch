"""tempdoc 735 W2/W3: delivery-tier capture (`delivered_tier`/`delivered_fields` on
`tool_result_digests` entries) and the recorded-fixture pipeline that backs it.

Covers: tier detection against the four recorded/reconstructed fixtures
(structured-json x2, prose, blocks); `delivered_fields` firing correctly on the
real answer/search structured-json shapes (including the nested
matchedTerms/excerpts placement); schema-optional behavior for historical
records that predate this field pair; and that the `experiments/
delivery_tier_probe_735.py` refresh script imports (never duplicates) the
production tier-detection function.
"""

from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest

from jseval.agent_utility_inspect import (
    _delivered_fields,
    _delivered_tier,
    _tool_result_digest_entry,
)
from jseval.utility_evidence import sanitize_observation

FIXTURES_DIR = Path(__file__).parent / "fixtures" / "recorded"


def _load_fixture(name: str) -> dict:
    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))


# ---------------------------------------------------------------------------
# Tier detection against the recorded/reconstructed fixtures
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "fixture_name, expected_tier",
    [
        ("justsearch_answer_structured.json", "structured-json"),
        ("justsearch_search_structured.json", "structured-json"),
        ("justsearch_status_prose.json", "prose"),
        ("sdk_block_list.json", "blocks"),
    ],
)
def test_delivered_tier_matches_fixture_declaration(fixture_name, expected_tier):
    fixture = _load_fixture(fixture_name)
    content = fixture["result"]["content"]
    assert _delivered_tier(content) == expected_tier
    # The fixture's own self-declared tier (used by the refresh script / README table)
    # must agree with what the real function computes -- a fixture that drifted from
    # the classifier would otherwise go unnoticed.
    assert fixture["delivered_tier"] == expected_tier


def test_delivered_tier_none_for_absent_content():
    assert _delivered_tier(None) is None


def test_delivered_tier_prose_for_non_dict_json():
    # A JSON array or scalar is not "structured-json" for this surface -- every real
    # structuredContent payload McpEvidenceProjection emits is a top-level object
    # (Map<String,Object>), so a top-level array/number is prose, not structured.
    assert _delivered_tier("[1, 2, 3]") == "prose"
    assert _delivered_tier("42") == "prose"
    assert _delivered_tier("not json at all") == "prose"


def test_delivered_tier_blocks_for_list_content():
    assert _delivered_tier([{"type": "text", "text": "hi"}]) == "blocks"
    assert _delivered_tier([]) == "blocks"


# ---------------------------------------------------------------------------
# delivered_fields firing on the real recorded/reconstructed answer + search JSON
# ---------------------------------------------------------------------------

def test_delivered_fields_on_answer_fixture_top_level_only():
    fixture = _load_fixture("justsearch_answer_structured.json")
    fields = _delivered_fields(fixture["result"]["content"])
    assert fields == {
        "quality": True,
        "citations": True,
        "matchedTerms": False,
        "degradation": False,
        "excerpts": False,
        "searchTrace": False,
        "results": False,
    }


def test_delivered_fields_on_search_fixture_including_nested_placement():
    """`matchedTerms`/`excerpts` live per-hit under `results[]` for
    `justsearch_search` (McpEvidenceProjection.java:75-93), never at the top
    level -- this fixture's second hit (doc-2) carries them nested, and
    `_delivered_fields` must still report them True."""
    fixture = _load_fixture("justsearch_search_structured.json")
    fields = _delivered_fields(fixture["result"]["content"])
    assert fields == {
        "quality": False,
        "citations": False,
        "matchedTerms": True,
        "degradation": True,
        "excerpts": True,
        "searchTrace": True,
        "results": True,
    }
    # Cross-check against the raw content: matchedTerms/excerpts are genuinely
    # absent at the TOP level of the parsed payload (the field-presence signal
    # comes from the nested nesting rule, not a top-level key).
    parsed = json.loads(fixture["result"]["content"])
    assert "matchedTerms" not in parsed
    assert "excerpts" not in parsed
    assert "matchedTerms" in parsed["results"][1]
    assert "excerpts" in parsed["results"][1]


def test_delivered_fields_none_for_prose_and_blocks():
    prose = _load_fixture("justsearch_status_prose.json")
    blocks = _load_fixture("sdk_block_list.json")
    assert _delivered_fields(prose["result"]["content"]) is None
    assert _delivered_fields(blocks["result"]["content"]) is None


def test_delivered_fields_none_for_absent_content():
    assert _delivered_fields(None) is None


# ---------------------------------------------------------------------------
# _tool_result_digest_entry end-to-end: furniture_markers is null exactly when
# delivered_fields carries the signal instead, never both/neither.
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    "fixture_name",
    [
        "justsearch_answer_structured.json",
        "justsearch_search_structured.json",
        "justsearch_status_prose.json",
        "sdk_block_list.json",
    ],
)
def test_tool_result_digest_entry_exactly_one_of_markers_or_fields_populated(fixture_name):
    fixture = _load_fixture(fixture_name)
    digest = _tool_result_digest_entry(fixture["result"])
    is_structured = digest["delivered_tier"] == "structured-json"
    assert (digest["furniture_markers"] is None) == is_structured
    assert (digest["delivered_fields"] is None) != is_structured


# ---------------------------------------------------------------------------
# Schema-optional: a historical record (pre-tempdoc-735, no delivered_tier/
# delivered_fields keys at all) must still sanitize and validate cleanly.
# ---------------------------------------------------------------------------

def _historical_observation() -> dict:
    """A minimal, schema-complete observation whose `tool_result_digests` entry
    is shaped exactly like the pre-735 producer (tempdoc 729 D9) -- no
    `delivered_tier`/`delivered_fields` keys at all, not even as `null`. This is
    the REAL historical shape (the D9-era `_tool_result_digest_entry` never
    emitted those keys), not a hand-waved approximation."""
    return {
        "condition": "A",
        "seed": 0,
        "qid": "q0",
        "correct": True,
        "source": {"cohort": {}},
        "tool_result_digests": [{
            "content_sha256": "0" * 64,
            "content_len": 42,
            "content_is_error": False,
            "content_shape": "text",
            "furniture_markers": {
                "rationale": True, "evidence_pack": False, "coverage": False, "degradation": False,
            },
        }],
    }


def test_historical_digest_without_delivered_keys_sanitizes_to_null():
    sanitized = sanitize_observation(_historical_observation())
    entry = sanitized["tool_result_digests"][0]
    assert entry["delivered_tier"] is None
    assert entry["delivered_fields"] is None
    assert entry["furniture_markers"] == {
        "rationale": True, "evidence_pack": False, "coverage": False, "degradation": False,
    }


def test_historical_digest_without_delivered_keys_validates_against_schema():
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    sanitized = sanitize_observation(_historical_observation())
    jsonschema.validate(sanitized, schema)


def test_freshly_captured_digest_with_delivered_keys_validates_against_schema():
    """The emitter-always-emits-but-schema-optional half: a NEW record DOES
    carry the keys (as null for non-applicable tiers), and must also validate."""
    jsonschema = pytest.importorskip("jsonschema")
    schema_path = Path(__file__).parents[1] / "agent-utility-observation.v1.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    fixture = _load_fixture("justsearch_answer_structured.json")
    digest = _tool_result_digest_entry(fixture["result"])
    observation = _historical_observation()
    observation["tool_result_digests"] = [digest]
    sanitized = sanitize_observation(observation)
    jsonschema.validate(sanitized, schema)
    assert sanitized["tool_result_digests"][0]["delivered_tier"] == "structured-json"
    assert sanitized["tool_result_digests"][0]["furniture_markers"] is None


# ---------------------------------------------------------------------------
# The refresh script imports (never duplicates) the production classifier.
# ---------------------------------------------------------------------------

def test_experiment_script_imports_the_same_delivered_tier_function():
    script_path = (
        Path(__file__).parents[1] / "experiments" / "delivery_tier_probe_735.py"
    )
    spec = importlib.util.spec_from_file_location("delivery_tier_probe_735", script_path)
    module = importlib.util.module_from_spec(spec)
    sys.modules.setdefault("delivery_tier_probe_735", module)
    spec.loader.exec_module(module)

    assert module._delivered_tier is _delivered_tier
    assert module._delivered_fields is _delivered_fields
    # And it agrees with the production function on all four fixtures -- a
    # duplicated (drifted) copy would be the exact bug this test exists to catch.
    for fixture_name in (
        "justsearch_answer_structured.json",
        "justsearch_search_structured.json",
        "justsearch_status_prose.json",
        "sdk_block_list.json",
    ):
        fixture = _load_fixture(fixture_name)
        content = fixture["result"]["content"]
        assert module._delivered_tier(content) == _delivered_tier(content)
