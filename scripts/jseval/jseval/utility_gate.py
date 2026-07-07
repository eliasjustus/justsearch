"""Agent-utility standing regression detection gate (tempdoc 673).

The agent-mediated-tool-drive sibling of :mod:`jseval.leak_gate` / :mod:`jseval.llm_gate`. Where
those gate raw retrieval quality / LLM-generation latency, this gates whether an LLM agent can still
successfully *drive* the JustSearch MCP retrieval tool — detection, not estimation (tempdoc 624 stays
the estimation/credibility-claim owner; see tempdoc 673 §D1 scope lock).

The control statistic is the **condition-C absolute pass rate** ("with-tool" accuracy) on a
high-contrast, fabricated-facts smoke corpus (`scripts/jseval/util-smoke/`) — NOT the paired
with-tool-vs-baseline delta the 624 publication run reports. tempdoc 673's confidence pass measured
this statistic directly: on `util-smoke/floor-inspect/utility-comparison.v1.json` (n=100, 3 seeds) the
condition-C floor is 0.92 with a seed-envelope stdev of only 0.0153 — a low-variance, gate-able
quantity, unlike the near-null paired delta (which is why this gate does NOT reuse
`utility_comparison.py`'s delta/McNemar machinery).

This gate is a **consumer of the existing canonical `utility-comparison.v1` record**
(`utility_comparison.compose_utility`'s output), read directly — NOT a registered
`jseval.projections` projection. (tempdoc 673's confidence pass found `projections.run_all` is wired
only into `jseval run`'s retrieval pipeline, not the utility-eval pipeline, so a projection-registry
entry would be a fork of the seam, not a reuse of it.)

Exit codes mirror the other ratchet gates, with a third verdict state adopted per tempdoc 673's research
pass (R-4, "PASS/FAIL/INCONCLUSIVE"): 0/PASS = no regression (or the corpus is un-pinned / not gated),
1/FAIL = a genuine regression (current C-floor dropped below the pinned floor minus tolerance, with the
agent-under-test's identity unchanged), 2/INCONCLUSIVE = either a data problem (baseline malformed,
unsupported record schema, record has no matching cell, condition-C accuracy unresolvable) OR a floor
drop that coincides with the agent's `cli_version` having changed since the baseline was pinned (D9/A2 —
this gate controls only half of what it measures, so a version-confounded drop is never reported as a
false FAIL). ``report["verdict"]`` carries the string form; ``exit_code`` stays the machine contract.
``evaluate`` and ``derive_baselines`` are pure functions over already-parsed dicts, so they are
unit-testable without a live eval run. Pinning a baseline additionally requires the source record's
`coverage.contamination_class` to mark it as a fabricated/engineered smoke corpus (D8) — a realistic
corpus can never become this gate's detection floor by default (see `check_admission`).
"""

from __future__ import annotations

from typing import Any

# Generous default: this is a detection tripwire for a large, structural regression (the tool stops
# working), not a fine-grained quality ratchet — see tempdoc 673 §F1/§D4. Per-corpus overrides are
# expected (a low-variance corpus like the floor-inspect one can afford a tight tolerance ~0.05; a tiny
# n=2 fixture like the raw util-smoke corpus needs a much wider one to avoid single-query noise).
DEFAULT_TOLERANCE_ABS = 0.15

# v1 reads the standard per-corpus record only (tempdoc 673 §D1/§D3) — NOT
# utility-comparison-cross-corpus.v1, whose `measured` is keyed by MODEL, not corpus (a genuinely
# different shape). Without this check, pointing --record at a cross-corpus file would silently
# misread it rather than fail closed with a clear error (post-implementation review finding #2).
SCHEMA = "utility-comparison.v1"


def check_schema(record: dict) -> str | None:
    """Return an error string if ``record`` isn't a schema this gate supports, else ``None``."""
    schema = record.get("schema")
    if schema != SCHEMA:
        return f"unsupported record schema {schema!r} (utility-gate v1 reads {SCHEMA!r} only)"
    return None


# v1's design lock (tempdoc 673 D1/D4/D5/D7): the substrate MUST be a fabricated, engineered smoke
# corpus, never a realistic one (that is 624's estimation job, and reintroduces the noise problem
# D5 exists to dissolve). `coverage.contamination_class` is an EXISTING per-record field
# (`utility_comparison.py`, set by the operator's `--contamination-class` flag at compose time) that
# already carries exactly this signal, put there for a different original purpose (624's data-leak
# labeling) but reused here as the pin-time eligibility marker — no new schema needed. This is a
# PIN-TIME check only (derive_baselines / --update-baseline): it does not run in evaluate(), which
# trusts whatever is already pinned. Self-reported, not independently verified against corpus content
# — it defends against accidental misuse (pinning a convenient corpus without checking provenance,
# the exact mistake this check exists to prevent recurring), not a deliberately mislabeled corpus.
REQUIRED_CONTAMINATION_CLASS = "private-synthetic"


def check_admission(record: dict) -> str | None:
    """Return an error string if ``record`` isn't eligible to become a pinned baseline, else ``None``.

    Refuses a record whose ``coverage.contamination_class`` isn't the fabricated/engineered marker
    (tempdoc 673 D8 — the corpus-authenticity admission gate).
    """
    contamination_class = (record.get("coverage") or {}).get("contamination_class")
    if contamination_class != REQUIRED_CONTAMINATION_CLASS:
        return (
            f"record's contamination_class {contamination_class!r} is not a fabricated/engineered "
            f"smoke corpus (utility-gate v1 requires {REQUIRED_CONTAMINATION_CLASS!r} — a realistic "
            f"corpus cannot become a detection floor here, tempdoc 673 D1/D4/D5/D7; pass "
            f"--allow-realistic-corpus to override deliberately)"
        )
    return None


def _resolve_cell(record: dict, corpus: str, model: str | None) -> dict | None:
    """The ``measured[corpus][model]`` cell, or the sole model's cell if ``model`` is unset and
    unambiguous. Returns ``None`` if the corpus/model isn't in this record (a data problem, not a
    "not gated" skip — the caller has already confirmed the corpus IS pinned)."""
    models = (record.get("measured") or {}).get(corpus)
    if not isinstance(models, dict) or not models:
        return None
    if model is not None:
        cell = models.get(model)
        return cell if isinstance(cell, dict) else None
    if len(models) == 1:
        cell = next(iter(models.values()))
        return cell if isinstance(cell, dict) else None
    return None  # ambiguous — caller must specify which agent_model


def _c_floor(cell: dict) -> float | None:
    """Extract condition C's (JustSearch-only / substitution) absolute accuracy from one cell.

    A cell's top-level ``accuracy`` field reflects whichever condition is ``primary_arm`` — on a
    3-condition (A/B/C) run that can be B ("addition_b"), with C's own stats relegated to
    ``arms.substitution_c`` (tempdoc 624 §T.4 per-stratum arms). Always prefer ``arms.substitution_c``
    when present (unambiguously C, regardless of what's primary); only fall back to the top-level field
    when there is no ``arms`` breakdown AND the primary arm is unset or already C — never attribute a
    DIFFERENT condition's (B's) number to C.
    """
    arms = cell.get("arms") or {}
    sub_c = arms.get("substitution_c")
    if isinstance(sub_c, dict):
        v = (sub_c.get("accuracy") or {}).get("with_tool")
        return float(v) if isinstance(v, (int, float)) else None
    primary_arm = cell.get("primary_arm")
    if primary_arm not in (None, "substitution_c"):
        return None  # primary is B and C has no separate arms entry -> C not resolvable from this cell
    v = (cell.get("accuracy") or {}).get("with_tool")
    return float(v) if isinstance(v, (int, float)) else None


def compare_cli_version(current: str | None, pinned: str | None) -> tuple[str, str | None]:
    """Pure verdict for the agent-identity (CLI version) drift check (tempdoc 673 D9).

    Mirrors :func:`jseval.ratchet_kernel.compare_engine_sets`'s tri-state shape. ``skip`` when either
    side doesn't record a ``cli_version`` (an old baseline pinned before this field existed, or a
    record from a path that never captured it) — backward-compatible no-op, never breaks an existing
    baseline. A ``mismatch`` means the agent-under-test itself changed since this baseline was pinned
    (a Claude Code CLI/model update) — the confound D5/A2 names as "the sharpest risk", since this gate
    controls only half of what it measures.
    """
    if current is None or pinned is None:
        return ("skip", "cli_version not recorded on one side (backward-compatible no-op)")
    if current == pinned:
        return ("ok", None)
    return (
        "mismatch",
        f"the record's agent cli_version {current!r} differs from the pinned baseline's {pinned!r} — "
        f"the agent under test changed since this baseline was pinned (tempdoc 673 D9/A2); re-baseline "
        f"before treating a floor drop as a JustSearch regression",
    )


def _evaluate(baselines: dict, record: dict, corpus: str, model: str | None = None) -> dict:
    """The actual comparison logic — see :func:`evaluate` (the public wrapper) for the documented
    contract. Split out so the public function can stamp ``report["verdict"]`` in one place without
    touching any of this function's several early-return branches (tempdoc 673 D9)."""
    report: dict = {"dataset": corpus, "checks": [], "exit_code": 0}

    pinned = (baselines.get("baselines") or {}).get(corpus)
    if pinned is None:
        report["checks"].append({
            "name": "baseline-pinned",
            "status": "skip",
            "detail": f"no pinned condition-C floor for {corpus}; not gated",
        })
        return report  # un-pinned corpora do not gate (exit 0)

    floor = pinned.get("c_floor_min")
    tolerance = pinned.get(
        "tolerance_abs", baselines.get("tolerance_default_abs", DEFAULT_TOLERANCE_ABS)
    )
    report["baseline"] = floor
    report["tolerance_abs"] = tolerance

    if not isinstance(floor, (int, float)):
        report["checks"].append({
            "name": "floor-valid",
            "status": "fail",
            "detail": f"pinned baseline for {corpus} has no numeric c_floor_min",
        })
        report["exit_code"] = 2
        return report

    schema_error = check_schema(record)
    if schema_error is not None:
        report["checks"].append({
            "name": "schema-supported",
            "status": "fail",
            "detail": schema_error,
        })
        report["exit_code"] = 2
        return report

    resolved_model = model or pinned.get("agent_model")
    cell = _resolve_cell(record, corpus, resolved_model)
    if cell is None:
        report["checks"].append({
            "name": "cell-present",
            "status": "fail",
            "detail": f"record has no unambiguous cell for corpus={corpus!r} model={resolved_model!r}",
        })
        report["exit_code"] = 2
        return report

    current = _c_floor(cell)
    if not isinstance(current, (int, float)):
        report["checks"].append({
            "name": "c-floor-present",
            "status": "fail",
            "detail": (
                "could not extract condition-C accuracy from the cell "
                "(arms.substitution_c / top-level accuracy.with_tool missing or ambiguous)"
            ),
        })
        report["exit_code"] = 2
        return report

    limit = floor - tolerance
    regressed = current < limit
    report["current"] = float(current)
    report["floor"] = limit  # the floor-tolerance the run must stay at/above
    report["checks"].append({
        "name": "c-floor-no-regression",
        "status": "fail" if regressed else "ok",
        "detail": (
            f"current={current:.4f} pinned_floor={floor:.4f} "
            f"limit={limit:.4f} (tolerance={tolerance})"
        ),
    })

    # Agent-identity drift (tempdoc 673 D9): always computed and recorded for transparency, but it
    # only ever DOWNGRADES a genuine FAIL to INCONCLUSIVE — it never turns a pass into anything else
    # (a version bump alone, with the floor still holding, is not this gate's concern; D5) and never
    # invents a new failure mode on its own.
    version_verdict, version_reason = compare_cli_version(
        (record.get("cohort") or {}).get("cli_version"), pinned.get("cli_version"))
    report["checks"].append({
        "name": "agent-identity-stable",
        "status": {"ok": "ok", "skip": "skip", "mismatch": "warn"}[version_verdict],
        "detail": version_reason or "cli_version matches the pinned baseline",
    })

    if regressed:
        report["exit_code"] = 2 if version_verdict == "mismatch" else 1
    return report


def evaluate(baselines: dict, record: dict, corpus: str, model: str | None = None) -> dict:
    """Compare ``record``'s condition-C absolute pass rate against the pinned floor for ``corpus``.

    :param baselines: ``{"baselines": {<corpus>: {c_floor_min, agent_model?, cli_version?,
        tolerance_abs}}, "tolerance_default_abs"}``.
    :param record: a parsed ``utility-comparison.v1`` (or ``-cross-corpus.v1``) document.
    :param corpus: the dataset slug (e.g. ``golden/util-smoke``).
    :param model: the agent model tier to check; if omitted, the pinned baseline's ``agent_model`` is
        used, then the record's sole model for that corpus if unambiguous.
    :returns: a report dict with ``exit_code``, ``checks``, and ``verdict`` (``"PASS"``/``"FAIL"``/
        ``"INCONCLUSIVE"`` — tempdoc 673 R-4's third state; a data problem OR an identity-confounded
        regression both read as INCONCLUSIVE, a coherent umbrella rather than a special case).
    """
    report = _evaluate(baselines, record, corpus, model)
    report["verdict"] = {0: "PASS", 1: "FAIL", 2: "INCONCLUSIVE"}[report["exit_code"]]
    return report


def project_release_to_baselines(
    release: dict,
    *,
    tolerance_default_abs: float = DEFAULT_TOLERANCE_ABS,
    per_corpus_tolerance: dict | None = None,
) -> dict:
    """Project a ``release.v1`` object's optional ``utility`` section into baseline floors.

    The utility-gate twin of :func:`relevance_gate.project_release_to_baselines`
    (tempdoc 683): when the canonical release carries per-corpus condition-C floors
    (``{"utility": {<corpus>: {c_floor_min, agent_model, cli_version}}}``), the floor
    projects live from the release; a corpus absent from the section is not projected,
    so the pointer file's ``fallback_baselines`` governs via
    :func:`ratchet_kernel.load_baselines_doc`'s merge. No release currently carries the
    section (utility records come from the agent-eval pipeline, not run summaries), so
    today this projects nothing — the pointer mechanism only.
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    cohort = release.get("cohort") or {}
    src_tag = release.get("release_id") or (cohort.get("git_sha") or "")[:10]
    baselines: dict[str, dict] = {}
    for corpus, entry in (release.get("utility") or {}).items():
        floor = (entry or {}).get("c_floor_min")
        if not isinstance(floor, (int, float)):
            continue
        baselines[corpus] = {
            "c_floor_min": float(floor),
            "agent_model": entry.get("agent_model"),
            "cli_version": entry.get("cli_version"),
            "tolerance_abs": per_corpus_tolerance.get(corpus, tolerance_default_abs),
            "src": f"projected from release {src_tag}".strip(),
        }
    return {
        "schema": "utility-ratchet-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "projected_from_release": True,
        "baselines": baselines,
    }


def derive_baselines(
    records: dict[str, dict],
    *,
    tolerance_default_abs: float = DEFAULT_TOLERANCE_ABS,
    per_corpus_tolerance: dict | None = None,
    allow_realistic_corpus: bool = False,
) -> dict:
    """Derive utility-gate baselines from measured ``utility-comparison.v1`` records.

    The condition-C-floor sibling of :func:`jseval.leak_gate.derive_baselines` (tempdoc 623 anti-fork
    discipline): a corpus's pinned ``c_floor_min`` is its **measured** condition-C accuracy in a real
    run, never a hand-typed number. ``evaluate`` subtracts ``tolerance_abs`` (limit = floor − tolerance),
    generous by default since this is a coarse detection tripwire, not a fine-grained ratchet. Also pins
    the record's ``cli_version`` (tempdoc 673 D9) so a later ``evaluate()`` can distinguish a genuine
    regression from the agent-under-test having drifted since this baseline was pinned.

    :param records: ``{<any key>: parsed utility-comparison.v1 document}`` — the key is caller-chosen
        (e.g. a source-file label); only the record's own ``measured`` corpora/models matter for output
        keys. A later record for the same ``(corpus)`` overwrites an earlier one (last-wins, mirroring
        dict-iteration order — callers pass one record per corpus in practice).
    :param allow_realistic_corpus: skip the D8 corpus-authenticity admission check (default: refuse a
        record whose ``coverage.contamination_class`` isn't the fabricated/engineered marker). An
        explicit, visible override — never a silent default.
    :returns: the ``utility-ratchet-baseline.v1`` shape :func:`evaluate` consumes.
    """
    per_corpus_tolerance = per_corpus_tolerance or {}
    baselines: dict[str, dict] = {}
    for record in (records or {}).values():
        if not isinstance(record, dict) or check_schema(record) is not None:
            continue  # e.g. a cross-corpus record — different `measured` shape, never derivable here
        if not allow_realistic_corpus and check_admission(record) is not None:
            continue  # not a fabricated/engineered smoke corpus (tempdoc 673 D8)
        cli_version = (record.get("cohort") or {}).get("cli_version")
        for corpus, models in (record.get("measured") or {}).items():
            if not isinstance(models, dict):
                continue
            for model, cell in models.items():
                if not isinstance(cell, dict):
                    continue
                measured = _c_floor(cell)
                if not isinstance(measured, (int, float)):
                    continue  # skip cells with no resolvable condition-C accuracy
                baselines[corpus] = {
                    "c_floor_min": float(measured),
                    "agent_model": model,
                    "cli_version": cli_version,
                    "tolerance_abs": per_corpus_tolerance.get(corpus, tolerance_default_abs),
                    "src": "measured condition-C accuracy from a utility-comparison.v1 record",
                }
    return {
        "schema": "utility-ratchet-baseline.v1",
        "tolerance_default_abs": tolerance_default_abs,
        "derived_from_runs": True,
        "baselines": baselines,
    }
