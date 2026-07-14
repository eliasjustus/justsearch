"""Tests for jseval.exposure_contrast (tempdoc 725 increment 4).

`exposure_contrast` is a pure projection over two already-composed
`utility-comparison.v1` records -- these tests build minimal-but-shape-accurate
records directly (mirroring `utility_comparison.compose_utility`'s actual output
shape: `cohort.exposure_config` / `cohort.mcp_initialize_identity`,
`measured[dataset][model].funnel.with_tool` / `.adoption.with_tool` /
`.identity`), rather than re-deriving a simplified shortcut.
"""

from __future__ import annotations

import pytest

from jseval.agent_manifest import agent_cohort_key, build_agent_manifest
from jseval.exposure_contrast import (
    ExposureContrastError,
    exposure_contrast,
    exposure_contrast_eligibility,
)

_FUNNEL_A = {
    "discovery_rate": 0.2,
    "post_discovery_invocation_rate": 0.5,
    "first_discovery_turn": 21.0,
    "reinforced_proxy_rate": 0.0,
    "reinforced_rate": 0.0,
    "funnel_fields_absent": False,
}
_FUNNEL_B = {
    "discovery_rate": 0.8,
    "post_discovery_invocation_rate": 0.75,
    "first_discovery_turn": 3.0,
    "reinforced_proxy_rate": 0.4,
    "reinforced_rate": 0.6,
    "funnel_fields_absent": False,
}
_ADOPTION_A = {"adoption_rate": 0.1, "first_mcp_call_index": 21.0, "mcp_call_share": 0.05}
_ADOPTION_B = {"adoption_rate": 0.5, "first_mcp_call_index": 3.0, "mcp_call_share": 0.3}


def _record(
    *,
    exposure_mode="deferred",
    instructions_sha256="a" * 64,
    corpus_signature="sig-707-member",
    resolved_provider_model="claude-haiku-4-5",
    query_sha="q" * 64,
    dataset="mixed/battlefield",
    model="haiku",
    funnel=None,
    adoption=None,
    omit_funnel=False,
    omit_adoption=False,
    omit_exposure_identity=False,
    mcp_tool_surface_hash="surface-hash-v1",
    server_version="1.0.0",
):
    cell = {
        "accuracy": {"baseline": 0.9, "with_tool": 0.9},
        "adoption": {
            "baseline": {"adoption_rate": None, "first_mcp_call_index": None, "mcp_call_share": None},
            "with_tool": dict(adoption if adoption is not None else _ADOPTION_A),
        },
        "identity": {
            "corpus_signature": corpus_signature,
            "requested_model_alias": model,
            "resolved_provider_model": resolved_provider_model,
            "query_identity": {"sha256": query_sha, "row_count": 20},
            "campaign_identity": None,
            "corpus_identity": None,
            "corpus_certification": None,
        },
    }
    if omit_adoption:
        del cell["adoption"]
    if not omit_funnel:
        cell["funnel"] = {
            "baseline": {
                "discovery_rate": None, "post_discovery_invocation_rate": None,
                "first_discovery_turn": None, "reinforced_proxy_rate": None,
                "reinforced_rate": None, "funnel_fields_absent": True,
            },
            "with_tool": dict(funnel if funnel is not None else _FUNNEL_A),
        }
    cohort = {
        "agent_cohort_key": "cohort-key-placeholder",
        "git_sha": "f" * 40,
        "mcp_tool_surface_hash": mcp_tool_surface_hash,
    }
    if not omit_exposure_identity:
        cohort["exposure_config"] = {
            "enable_tool_search": None, "always_load": (exposure_mode == "eager"),
            "exposure_mode": exposure_mode,
        }
        cohort["mcp_initialize_identity"] = {
            "instructions": None, "instructions_sha256": instructions_sha256,
            "server_version": server_version, "protocol_version": "2025-06-18",
        }
    return {
        "schema": "utility-comparison.v1",
        "schema_version": 2,
        "cohort": cohort,
        "measured": {dataset: {model: cell}},
    }


# --- happy path --------------------------------------------------------------


def test_exposure_contrast_happy_path_reports_per_metric_a_b_delta():
    record_a = _record(exposure_mode="deferred", funnel=_FUNNEL_A, adoption=_ADOPTION_A)
    record_b = _record(exposure_mode="eager", funnel=_FUNNEL_B, adoption=_ADOPTION_B)

    result = exposure_contrast(record_a, record_b)

    assert result["funnel"]["discovery_rate"] == {"a": 0.2, "b": 0.8, "delta": pytest.approx(0.6)}
    assert result["funnel"]["reinforced_rate"] == {"a": 0.0, "b": 0.6, "delta": pytest.approx(0.6)}
    assert result["adoption"]["adoption_rate"] == {"a": 0.1, "b": 0.5, "delta": pytest.approx(0.4)}
    assert result["adoption"]["first_mcp_call_index"] == {"a": 21.0, "b": 3.0, "delta": pytest.approx(-18.0)}

    # Identity echo: exposure_mode, instructions_sha256, corpus signature, resolved model.
    assert result["identity"]["record_a"]["exposure_mode"] == "deferred"
    assert result["identity"]["record_b"]["exposure_mode"] == "eager"
    assert result["identity"]["record_a"]["instructions_sha256"] == "a" * 64
    assert result["identity"]["record_a"]["corpus_signature"] == "sig-707-member"
    assert result["identity"]["record_a"]["resolved_provider_model"] == "claude-haiku-4-5"

    # Descriptive only: no verdict/significance field leaks into the result.
    assert "verdict" not in result
    assert "p_value" not in result
    assert "significant" not in result


def test_exposure_contrast_null_metric_values_produce_null_delta_not_a_crash():
    """`first_discovery_turn` is legitimately `None` when no cell discovered at all --
    the delta must degrade to `None`, never raise or fabricate a numeric delta."""
    funnel_none_discovery = dict(_FUNNEL_A)
    funnel_none_discovery["first_discovery_turn"] = None
    record_a = _record(exposure_mode="deferred", funnel=funnel_none_discovery)
    record_b = _record(exposure_mode="eager", funnel=_FUNNEL_B)

    result = exposure_contrast(record_a, record_b)

    assert result["funnel"]["first_discovery_turn"]["a"] is None
    assert result["funnel"]["first_discovery_turn"]["delta"] is None


# --- hard validation: missing funnel ------------------------------------------


def test_exposure_contrast_rejects_record_missing_funnel_data():
    record_a = _record(omit_funnel=True)
    record_b = _record(exposure_mode="eager")

    with pytest.raises(ExposureContrastError, match="funnel"):
        exposure_contrast(record_a, record_b)


def test_exposure_contrast_rejects_record_b_missing_funnel_data():
    record_a = _record(exposure_mode="deferred")
    record_b = _record(exposure_mode="eager", omit_funnel=True)

    with pytest.raises(ExposureContrastError, match="funnel"):
        exposure_contrast(record_a, record_b)


def test_exposure_contrast_rejects_record_missing_adoption_data():
    record_a = _record(omit_adoption=True)
    record_b = _record(exposure_mode="eager")

    with pytest.raises(ExposureContrastError, match="adoption"):
        exposure_contrast(record_a, record_b)


def test_exposure_contrast_rejects_record_missing_exposure_identity():
    record_a = _record(omit_exposure_identity=True)
    record_b = _record(exposure_mode="eager")

    with pytest.raises(ExposureContrastError, match="exposure identity"):
        exposure_contrast(record_a, record_b)


# --- exposure_contrast_eligibility / pre-#605 tombstone (tempdoc 729 D11) -----


def test_eligibility_flags_empty_measured():
    result = exposure_contrast_eligibility({"cohort": {}, "measured": {}})

    assert result["eligible"] is False
    assert any("measured is empty" in r and "#605" in r for r in result["reasons"])


def test_eligibility_flags_missing_measured_key_entirely():
    result = exposure_contrast_eligibility({"cohort": {}})

    assert result["eligible"] is False
    assert any("measured is empty" in r for r in result["reasons"])


def test_eligibility_flags_absent_exposure_identity():
    record = _record(omit_exposure_identity=True)

    result = exposure_contrast_eligibility(record)

    assert result["eligible"] is False
    assert any("exposure identity" in r and "#605" in r for r in result["reasons"])
    # measured is populated in this fixture -- only the identity reason fires.
    assert not any("measured is empty" in r for r in result["reasons"])


def test_eligibility_reports_both_reasons_when_both_disqualifiers_present():
    result = exposure_contrast_eligibility({"cohort": {}, "measured": {}})

    assert result["eligible"] is False
    assert len(result["reasons"]) == 2


def test_eligibility_true_for_a_normal_post_605_record():
    record = _record(exposure_mode="deferred")

    result = exposure_contrast_eligibility(record)

    assert result == {"eligible": True, "reasons": []}


def test_exposure_contrast_rejects_pre_605_record_with_tombstone_not_generic_message():
    """A pre-#605-shaped record (empty `measured`, no cohort exposure identity)
    must raise the SPECIFIC tombstone reason, not the old generic 'has no
    measured cells' message -- so a future agent gets a self-describing
    failure, not a puzzle (tempdoc 729 D11)."""
    pre_605_record = {
        "schema": "utility-comparison.v1",
        "schema_version": 2,
        "cohort": {"agent_cohort_key": "old-cohort", "git_sha": "f" * 40},
        "measured": {},
    }
    record_b = _record(exposure_mode="eager")

    with pytest.raises(ExposureContrastError, match="not exposure-contrast-eligible") as excinfo:
        exposure_contrast(pre_605_record, record_b)
    message = str(excinfo.value)
    assert "#605" in message
    assert "record_a" in message
    assert "has no measured cells" not in message


# --- hard validation: mismatched corpus/model ---------------------------------


def test_exposure_contrast_rejects_mismatched_corpus_signature():
    record_a = _record(exposure_mode="deferred", corpus_signature="sig-A")
    record_b = _record(exposure_mode="eager", corpus_signature="sig-B")

    with pytest.raises(ExposureContrastError, match="corpus_signature"):
        exposure_contrast(record_a, record_b)


def test_exposure_contrast_rejects_mismatched_resolved_model():
    record_a = _record(exposure_mode="deferred", resolved_provider_model="claude-haiku-4-5")
    record_b = _record(exposure_mode="eager", resolved_provider_model="claude-haiku-5-0")

    with pytest.raises(ExposureContrastError, match="resolved_provider_model"):
        exposure_contrast(record_a, record_b)


def test_exposure_contrast_rejects_mismatched_query_identity():
    record_a = _record(exposure_mode="deferred", query_sha="q" * 64)
    record_b = _record(exposure_mode="eager", query_sha="z" * 64)

    with pytest.raises(ExposureContrastError, match="query_identity"):
        exposure_contrast(record_a, record_b)


# --- surface-aware guard (tempdoc 725 increment 4 W4) -------------------------
# A version bump changes `mcp_tool_surface_hash` / `server_version` -- two runs
# on different tool surfaces must not silently contrast as an exposure effect.


def test_exposure_contrast_rejects_mismatched_mcp_tool_surface_hash():
    record_a = _record(exposure_mode="deferred", mcp_tool_surface_hash="hash-v1")
    record_b = _record(exposure_mode="eager", mcp_tool_surface_hash="hash-v2")

    with pytest.raises(ExposureContrastError, match="surface_contrast=True") as excinfo:
        exposure_contrast(record_a, record_b)
    assert "hash-v1" in str(excinfo.value)
    assert "hash-v2" in str(excinfo.value)


def test_exposure_contrast_rejects_mismatched_server_version():
    record_a = _record(exposure_mode="deferred", server_version="1.0.0")
    record_b = _record(exposure_mode="eager", server_version="2.0.0")

    with pytest.raises(ExposureContrastError, match="surface_contrast=True") as excinfo:
        exposure_contrast(record_a, record_b)
    assert "1.0.0" in str(excinfo.value)
    assert "2.0.0" in str(excinfo.value)


def test_exposure_contrast_surface_contrast_true_allows_mismatch_and_echoes_identities():
    record_a = _record(
        exposure_mode="deferred", mcp_tool_surface_hash="hash-v1", server_version="1.0.0",
    )
    record_b = _record(
        exposure_mode="eager", mcp_tool_surface_hash="hash-v2", server_version="2.0.0",
    )

    result = exposure_contrast(record_a, record_b, surface_contrast=True)

    assert result["surface_identities"] == {
        "a": {"mcp_tool_surface_hash": "hash-v1", "server_version": "1.0.0"},
        "b": {"mcp_tool_surface_hash": "hash-v2", "server_version": "2.0.0"},
    }
    # The contrast still computes -- surface_contrast permits, it does not suppress.
    assert result["funnel"]["discovery_rate"]["a"] == _FUNNEL_A["discovery_rate"]


def test_exposure_contrast_surface_contrast_true_same_surface_is_harmless():
    record_a = _record(exposure_mode="deferred")
    record_b = _record(exposure_mode="eager")

    result = exposure_contrast(record_a, record_b, surface_contrast=True)

    assert result["surface_identities"] == {
        "a": {"mcp_tool_surface_hash": "surface-hash-v1", "server_version": "1.0.0"},
        "b": {"mcp_tool_surface_hash": "surface-hash-v1", "server_version": "1.0.0"},
    }


def test_exposure_contrast_default_surface_contrast_false_omits_echo_on_match():
    record_a = _record(exposure_mode="deferred")
    record_b = _record(exposure_mode="eager")

    result = exposure_contrast(record_a, record_b)

    assert "surface_identities" not in result


def test_exposure_contrast_rejects_record_with_more_than_one_measured_cell():
    record_a = _record(exposure_mode="deferred")
    record_a["measured"]["mixed/other-corpus"] = {"haiku": record_a["measured"]["mixed/battlefield"]["haiku"]}
    record_b = _record(exposure_mode="eager")

    with pytest.raises(ExposureContrastError, match="exactly one measured"):
        exposure_contrast(record_a, record_b)


# --- dry-config: exposure_mode alone must split agent_cohort_key -------------
# (reuses agent_manifest.build_agent_manifest / agent_cohort_key directly --
# tempdoc 725 increment 2's mix-guard substrate, not re-derived here.)


def _manifest(*, exposure_mode, instructions_sha256="a" * 64):
    return build_agent_manifest(
        corpus={"dataset": "mixed/battlefield", "signature": "sig-707-member"},
        agent_model="haiku",
        agent_model_version="claude-haiku-4-5",
        cli_version="1.2.3",
        mcp_tool_surface=[{"name": "mcp__justsearch__justsearch_search"}],
        judge={"kind": "substring-em"},
        prompt_template="Answer the question: {query}",
        condition="C",
        seed=0,
        exposure_config={"enable_tool_search": "true", "always_load": False,
                          "exposure_mode": exposure_mode},
        mcp_initialize_identity={"instructions_sha256": instructions_sha256},
        exposure_mode=exposure_mode,
        instructions_sha256=instructions_sha256,
    )


def test_exposure_mode_alone_splits_agent_cohort_key():
    """Two campaigns identical on every harness axis EXCEPT exposure_mode must NOT
    silently pair -- `agent_cohort_key` must differ (tempdoc 725 increment 2 R1/R2:
    exposure identity joins cohort/pairing identity)."""
    deferred = _manifest(exposure_mode="deferred")
    eager = _manifest(exposure_mode="eager")

    assert deferred["agent_cohort_key"] != eager["agent_cohort_key"]
    # Sanity: the manifests otherwise agree on every non-exposure axis.
    assert deferred["mcp_tool_surface_hash"] == eager["mcp_tool_surface_hash"]
    assert deferred["cli_version"] == eager["cli_version"]

    # And the standalone helper agrees with what build_agent_manifest already stamped.
    assert agent_cohort_key(deferred) == deferred["agent_cohort_key"]
    assert agent_cohort_key(eager) == eager["agent_cohort_key"]
