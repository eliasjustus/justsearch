"""Eager-vs-deferred exposure contrast (tempdoc 725 increment 4).

A pure, descriptive comparison of two already-composed ``utility-comparison.v1``
records whose with-tool arms differ (intentionally) only in HOW the JustSearch MCP
tool surface was exposed to the agent -- eager vs. deferred (or any other two
exposure-mode campaigns run over the same matched corpus/model/query set). This is
the reporting instrument for the first pre-registered experiment in tempdoc 725
Part III ("the exposure-mode A/B ... answers whether visibility or persuasion is
binding").

This module does **no** model/backend calls, runs **no** campaign, and is not a
CLI -- it is a library function over two records that already exist on disk
(composed by ``utility_comparison.compose_utility`` / ``utility_recompose``).

**Explicit boundary: no significance testing, no verdict.** ``exposure_contrast``
reports a plain ``{a, b, delta}`` triple per metric -- a DESCRIPTIVE side-by-side,
not a hypothesis test. Significance testing over the underlying paired cells
(McNemar / bootstrap-CI) is 624's job (``utility_comparison._stats_from_pairs``);
a ship/no-ship verdict on any lever is the owner's, decided against
pre-registered thresholds set *before* results are seen (tempdoc 725 Part III:
"Numeric thresholds are set with 624 when the experiment is registered -- not
invented here, and never after seeing results"). A caller must not read a
nonzero ``delta`` here as proof of anything.
"""

from __future__ import annotations

_FUNNEL_METRICS = (
    "discovery_rate",
    "post_discovery_invocation_rate",
    "first_discovery_turn",
    "reinforced_proxy_rate",
    "reinforced_rate",
)

_ADOPTION_METRICS = (
    "adoption_rate",
    "first_mcp_call_index",
    "mcp_call_share",
)

# Non-exposure identity fields that MUST match between the two records for the
# contrast to be valid -- a campaign differing in any of these is not "the same
# campaign under a different exposure mode," it is a different measurement.
_MATCHED_IDENTITY_FIELDS = ("corpus_signature", "resolved_provider_model", "query_identity")

# MCP tool surface identity fields that MUST also match by default -- two runs
# measuring different tool surfaces (a version bump changed the tools/list
# payload or the server build) would otherwise contrast silently, mislabeled as
# an exposure effect rather than a surface-drift effect. Unlike
# ``_MATCHED_IDENTITY_FIELDS`` this guard is escapable via ``surface_contrast``
# (tempdoc 725 increment 4 W4): a deliberate cross-surface comparison is a
# different, self-describing kind of record, not an error.
_SURFACE_IDENTITY_FIELDS = ("mcp_tool_surface_hash", "server_version")


class ExposureContrastError(ValueError):
    """Raised when two records cannot form a valid exposure contrast."""


def exposure_contrast_eligibility(record: dict) -> dict:
    """Pure predicate (tempdoc 729 D11): is ``record`` even the right SHAPE to
    ever participate in an exposure contrast, independent of whether it happens
    to match some OTHER record's identity?

    A record composed entirely from evidence that predates the #605
    exposure-identity capture fix never has a with-tool ``measured`` cell with
    real funnel/adoption data, AND never has cohort-level exposure identity
    (``cohort.exposure_config`` / ``cohort.mcp_initialize_identity``) -- both
    are increment-2 fields (tempdoc 725) that simply were never recorded. Such
    a record is PERMANENTLY ineligible, not merely "not yet contrasted": even
    recomposing its raw observations cannot manufacture identity that was
    never captured. This predicate names the specific disqualifier so a caller
    (or ``exposure_contrast`` itself) reports WHY, instead of a puzzle-shaped
    generic error.

    :returns: ``{"eligible": bool, "reasons": [str, ...]}`` -- ``reasons`` is
        empty iff ``eligible`` is ``True``.
    """
    reasons: list[str] = []
    measured = record.get("measured")
    cohort = record.get("cohort") or {}
    has_exposure_identity = (
        cohort.get("exposure_config") is not None
        and cohort.get("mcp_initialize_identity") is not None
    )
    if not isinstance(measured, dict) or not measured:
        # Two distinct empty-measured causes with opposite remedies (Campaign U
        # results nit, fixed during Campaign V): a post-#605 single-condition
        # composition (e.g. a B-only pilot) captured identity fine and just has
        # no contrast arm BY DESIGN, while a pre-#605 record is permanently
        # ineligible. Conflating them sent readers chasing a fixed defect.
        if has_exposure_identity:
            reasons.append(
                "measured is empty but cohort exposure identity IS captured -- "
                "a single-condition composition (e.g. a B-only pilot) has no "
                "with-tool contrast cell by design; this record needs a "
                "counterpart arm, not a capture fix (not the pre-#605 defect)"
            )
        else:
            reasons.append(
                "measured is empty -- record predates the #605 exposure-identity "
                "capture fix and carries no with-tool measured cell at all"
            )
    if not has_exposure_identity:
        reasons.append(
            "cohort carries no exposure identity (exposure_config / "
            "mcp_initialize_identity both required) -- record predates the "
            "#605 exposure-identity capture fix and is permanently "
            "descriptive-only, never exposure-contrast-eligible"
        )
    return {"eligible": not reasons, "reasons": reasons}


def _only_cell(record: dict, *, label: str) -> dict:
    """The sole ``measured[dataset][model]`` cell of a single-campaign record.

    An exposure-mode A/B campaign is one corpus x one model by construction
    (tempdoc 725 Part III); a record with zero or more than one measured cell is
    not what this contrast is defined over, so it fails loudly rather than
    guessing which cell to use.
    """
    measured = record.get("measured")
    if not isinstance(measured, dict) or not measured:
        raise ExposureContrastError(f"{label} has no measured cells")
    cells = [
        (slug, model, cell)
        for slug, by_model in measured.items()
        for model, cell in by_model.items()
    ]
    if len(cells) != 1:
        raise ExposureContrastError(
            f"{label} must have exactly one measured (corpus, model) cell for an "
            f"exposure contrast, found {len(cells)}: "
            f"{[(slug, model) for slug, model, _ in cells]!r}"
        )
    _, _, cell = cells[0]
    return cell


def _identity(record: dict, cell: dict, *, label: str) -> dict:
    """Exposure + matched-campaign identity for one record (cohort- and
    cell-level fields, tempdoc 725 increment 2's capture): ``exposure_mode``,
    ``instructions_sha256`` (both cohort-level, from ``exposure_config`` /
    ``mcp_initialize_identity``), plus the cell-level ``corpus_signature`` /
    ``resolved_provider_model`` / ``query_identity`` used for the matched-campaign
    hard validation below, plus the cohort-level ``mcp_tool_surface_hash`` /
    ``server_version`` used for the surface-aware guard (tempdoc 725 increment 4 W4).

    Raises if either cohort-level exposure block is absent -- a record composed
    from pre-725 evidence (which never captured exposure identity) cannot
    participate in an exposure contrast at all.
    """
    cohort = record.get("cohort") or {}
    exposure_config = cohort.get("exposure_config")
    mcp_initialize_identity = cohort.get("mcp_initialize_identity")
    if exposure_config is None or mcp_initialize_identity is None:
        raise ExposureContrastError(
            f"{label} carries no exposure identity (cohort.exposure_config / "
            "cohort.mcp_initialize_identity are both required) -- an exposure contrast "
            "requires both records to be composed from tempdoc 725 increment 2+ evidence"
        )
    cell_identity = cell.get("identity") or {}
    return {
        "exposure_mode": exposure_config.get("exposure_mode"),
        "instructions_sha256": mcp_initialize_identity.get("instructions_sha256"),
        "corpus_signature": cell_identity.get("corpus_signature"),
        "resolved_provider_model": cell_identity.get("resolved_provider_model"),
        "query_identity": cell_identity.get("query_identity"),
        "mcp_tool_surface_hash": cohort.get("mcp_tool_surface_hash"),
        "server_version": mcp_initialize_identity.get("server_version"),
    }


def _metric_deltas(a_block: dict, b_block: dict, metrics: tuple[str, ...]) -> dict:
    out: dict = {}
    for metric in metrics:
        a_value = a_block.get(metric)
        b_value = b_block.get(metric)
        delta = (
            b_value - a_value
            if isinstance(a_value, (int, float)) and isinstance(b_value, (int, float))
            else None
        )
        out[metric] = {"a": a_value, "b": b_value, "delta": delta}
    return out


def exposure_contrast(
    record_a: dict, record_b: dict, *, surface_contrast: bool = False,
) -> dict:
    """Descriptive per-metric ``{a, b, delta}`` contrast of two ``utility-comparison.v1``
    records' with-tool-arm ``funnel`` + ``adoption`` blocks (e.g. record_a = deferred,
    record_b = eager, or any other two exposure-mode campaigns).

    Hard validation (raises ``ExposureContrastError``, a ``ValueError`` subclass):

    - both records must carry with-tool ``funnel`` data
      (``measured[...][...].funnel.with_tool``);
    - both records must carry with-tool ``adoption`` data
      (``measured[...][...].adoption.with_tool``);
    - both records must carry cohort-level exposure identity
      (``cohort.exposure_config`` + ``cohort.mcp_initialize_identity``);
    - the two records' non-exposure identity (corpus signature, resolved provider
      model, query identity) must MATCH -- the contrast is only valid on the same
      campaign measured under two exposure modes, never on two different campaigns
      that also happen to differ in exposure;
    - by default, the two records' MCP tool surface identity (``mcp_tool_surface_hash``,
      ``server_version``) must also MATCH -- two runs measuring different tool
      surfaces (a version bump) must not silently contrast as if it were an
      exposure effect. Pass ``surface_contrast=True`` to intentionally allow and
      report a cross-surface comparison instead; the returned block then carries
      a ``surface_identities`` field so the record self-describes that it is a
      cross-surface comparison, NOT an exposure contrast.

    Descriptive only -- see the module docstring for the no-significance-testing /
    no-verdict boundary.
    """
    for label, record in (("record_a", record_a), ("record_b", record_b)):
        eligibility = exposure_contrast_eligibility(record)
        if not eligibility["eligible"]:
            raise ExposureContrastError(
                f"{label} is not exposure-contrast-eligible: "
                f"{'; '.join(eligibility['reasons'])}"
            )

    cell_a = _only_cell(record_a, label="record_a")
    cell_b = _only_cell(record_b, label="record_b")

    funnel_a = (cell_a.get("funnel") or {}).get("with_tool")
    funnel_b = (cell_b.get("funnel") or {}).get("with_tool")
    if funnel_a is None or funnel_b is None:
        raise ExposureContrastError(
            "both records must carry with-tool funnel data for an exposure contrast "
            "(measured[...][...].funnel.with_tool) -- at least one record has none"
        )

    adoption_a = (cell_a.get("adoption") or {}).get("with_tool")
    adoption_b = (cell_b.get("adoption") or {}).get("with_tool")
    if adoption_a is None or adoption_b is None:
        raise ExposureContrastError(
            "both records must carry with-tool adoption data for an exposure contrast "
            "(measured[...][...].adoption.with_tool) -- at least one record has none"
        )

    identity_a = _identity(record_a, cell_a, label="record_a")
    identity_b = _identity(record_b, cell_b, label="record_b")
    mismatched = {
        field: (identity_a[field], identity_b[field])
        for field in _MATCHED_IDENTITY_FIELDS
        if identity_a[field] != identity_b[field]
    }
    if mismatched:
        raise ExposureContrastError(
            "record_a/record_b non-exposure identity disagrees -- the contrast is only "
            f"valid on the same campaign measured under two exposure modes: {mismatched!r}"
        )

    surface_mismatched = {
        field: (identity_a[field], identity_b[field])
        for field in _SURFACE_IDENTITY_FIELDS
        if identity_a[field] != identity_b[field]
    }
    if surface_mismatched and not surface_contrast:
        raise ExposureContrastError(
            "record_a/record_b MCP tool surface disagrees -- record_a="
            f"{ {f: identity_a[f] for f in _SURFACE_IDENTITY_FIELDS} !r}, record_b="
            f"{ {f: identity_b[f] for f in _SURFACE_IDENTITY_FIELDS} !r} "
            f"({surface_mismatched!r}); a contrast across two different tool surfaces is "
            "not an exposure-mode effect -- if this is an intentional cross-surface "
            "comparison, pass surface_contrast=True"
        )

    result = {
        "funnel": _metric_deltas(funnel_a, funnel_b, _FUNNEL_METRICS),
        "adoption": _metric_deltas(adoption_a, adoption_b, _ADOPTION_METRICS),
        "identity": {
            "record_a": {k: v for k, v in identity_a.items() if k != "query_identity"},
            "record_b": {k: v for k, v in identity_b.items() if k != "query_identity"},
        },
    }
    if surface_contrast:
        result["surface_identities"] = {
            "a": {field: identity_a[field] for field in _SURFACE_IDENTITY_FIELDS},
            "b": {field: identity_b[field] for field in _SURFACE_IDENTITY_FIELDS},
        }
    return result
