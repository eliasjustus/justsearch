#!/usr/bin/env python3
"""gen_coverage_brief.py — tempdoc 728 "coverage follows shipment" guard.

Generates a per-round "must-touch surfaces" coverage brief for a JustSearch
release-candidate Sandbox validation round, DERIVED from committed surface
artifacts (route-manifest cohorts, CorePlugin.ts surface ids,
coreInteractionShapes.ts), and FAILS CLOSED if any shipped surface is
unclassified in governance/sandbox-coverage.v1.json.

Pure Python 3 standard library only. No network access.

Modes:
  --check              Run the drift check only, print a summary, exit
                        0/1. No files written. (Also the default mode.)
  --out-dir DIR        Run the drift check (still fail closed); if clean,
                        write coverage-brief.md and coverage-manifest.json
                        into DIR.
  --register PATH      Override the register path (testability only; the
                        real register is the default and should be used in
                        normal operation).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

# ---------------------------------------------------------------------------
# Repo-root resolution + input paths
# ---------------------------------------------------------------------------

ROUTE_MANIFEST_REL = "modules/ui-web/src/api/generated/route-manifest.snapshot.json"
CORE_PLUGIN_REL = "modules/ui-web/src/shell-v0/plugin-api/CorePlugin.ts"
INTERACTION_SHAPES_REL = "modules/ui-web/src/shell-v0/plugin-api/coreInteractionShapes.ts"
REGISTER_REL = "governance/sandbox-coverage.v1.json"
UI_WEB_SRC_REL = "modules/ui-web/src"

# D4 (tempdoc 728-followup): mustWatch observability tiers. 'blocked-by-posture'
# is the honest "cannot be observed under the CURRENT sandbox posture" status
# (distinct from 'this was never checked') -- see install-trust-prompts.
ALLOWED_MUST_WATCH_OBSERVABILITY = {"sandbox", "host", "blocked-by-posture"}

SURFACE_ID_RE = re.compile(
    r"id:\s*'(core\.[^']+)'(?:(?!id:\s*')[\s\S])*?placement:\s*'([A-Z]+)'"
)
SHAPE_ID_RE = re.compile(r"'(core\.[^']+)'")

# Part D (tempdoc 750): reach-pointer verification. A `reach.testid` on a
# surfaceCoverage/shapeCoverage register row is only trustworthy if it is
# still greppable in the frontend source -- a stale pointer is worse than
# none (750 Fork-risk control). Plain literal `data-testid="..."` scan, no
# subprocess/git dependency.
TESTID_ATTR_RE = re.compile(r'data-testid="([^"]+)"')


def find_repo_root(start: Path) -> Path:
    """Walk up from `start` until a directory containing gradlew.bat is found."""
    cur = start.resolve()
    for candidate in [cur, *cur.parents]:
        if (candidate / "gradlew.bat").exists():
            return candidate
    raise RuntimeError(
        f"Could not locate repo root (no gradlew.bat found walking up from {start})"
    )


# ---------------------------------------------------------------------------
# Extraction
# ---------------------------------------------------------------------------


def extract_cohorts(route_manifest_path: Path) -> dict[str, list[str]]:
    """Return {cohort: sorted ["<METHOD> <path>", ...]} from the route manifest."""
    data = json.loads(route_manifest_path.read_text(encoding="utf-8"))
    cohorts: dict[str, set[str]] = {}
    for route in data["routes"]:
        cohort = route["cohort"]
        if cohort is None:
            raise ValueError(
                f"route {route.get('method')} {route.get('path')} has a null cohort "
                f"— every route must be classified into a cohort"
            )
        cohorts.setdefault(cohort, set()).add(f"{route['method']} {route['path']}")
    return {cohort: sorted(routes) for cohort, routes in sorted(cohorts.items())}


def extract_surfaces(core_plugin_path: Path) -> dict[str, str]:
    """Return {surface_id: placement} parsed from CorePlugin.ts."""
    text = core_plugin_path.read_text(encoding="utf-8")
    surfaces: dict[str, str] = {}
    for match in SURFACE_ID_RE.finditer(text):
        surface_id, placement = match.group(1), match.group(2)
        surfaces[surface_id] = placement
    return dict(sorted(surfaces.items()))


def extract_shapes(interaction_shapes_path: Path) -> list[str]:
    """Return the sorted list of interaction shape ids from coreInteractionShapes.ts."""
    text = interaction_shapes_path.read_text(encoding="utf-8")
    marker = "CORE_INTERACTION_SHAPES"
    idx = text.find(marker)
    if idx == -1:
        raise ValueError(f"could not find {marker} in {interaction_shapes_path}")
    # Scope the scan to the array literal itself (up to the closing `] as const;`)
    end = text.find("as const", idx)
    region = text[idx: end if end != -1 else len(text)]
    shapes = sorted({m.group(1) for m in SHAPE_ID_RE.finditer(region)})
    return shapes


def scan_data_testids(ui_web_src_path: Path) -> set[str]:
    """Part D (tempdoc 750): plain-text scan of every `data-testid="..."` literal
    under modules/ui-web/src/**/*.ts. Used to verify a register row's
    reach.testid is still real at generation time -- never emit an unverified
    testid (750 Fork-risk control: a stale pointer is worse than none)."""
    testids: set[str] = set()
    for ts_file in ui_web_src_path.rglob("*.ts"):
        text = ts_file.read_text(encoding="utf-8", errors="ignore")
        testids.update(TESTID_ATTR_RE.findall(text))
    return testids


def evidence_token(surface_id: str) -> str:
    """core.security-surface -> security; core.presentation-gallery-surface -> presentation-gallery."""
    token = surface_id
    if token.startswith("core."):
        token = token[len("core."):]
    if token.endswith("-surface"):
        token = token[: -len("-surface")]
    return token


# ---------------------------------------------------------------------------
# Drift check
# ---------------------------------------------------------------------------


class DriftResult:
    def __init__(self, kind: str, id_key: str):
        self.kind = kind
        self.id_key = id_key
        self.drift: list[str] = []
        self.stale: list[str] = []
        self.covered_map: dict[str, dict[str, Any]] = {}
        self.exempt_ids: set[str] = set()


def classify(
    kind: str,
    id_key: str,
    derived_ids: set[str],
    coverage: list[dict[str, Any]],
    exempt: list[dict[str, Any]],
) -> DriftResult:
    result = DriftResult(kind, id_key)
    result.covered_map = {row[id_key]: row for row in coverage}
    result.exempt_ids = {row[id_key] for row in exempt}
    classified_ids = set(result.covered_map) | result.exempt_ids
    result.drift = sorted(derived_ids - classified_ids)
    result.stale = sorted(classified_ids - derived_ids)
    return result


def validate_exempt_reasons(register: dict[str, Any]) -> list[str]:
    """734-followup review: an exempt row/entry with no 'reason' is a silent
    'trust me' with nothing for a future reviewer to check — fail closed instead.
    Covers both inline `tier: "exempt"` rows inside cohortCoverage/surfaceCoverage/
    shapeCoverage AND the top-level `*Exempt` arrays, which previously accepted a
    bare id with no reason at all (both arrays are empty today, so this is a
    forward-looking guard, not a break)."""
    errors: list[str] = []

    coverage_lists = {
        "cohortCoverage": "cohort",
        "surfaceCoverage": "surfaceId",
        "shapeCoverage": "shape",
    }
    for list_key, id_key in coverage_lists.items():
        for row in register.get(list_key, []) or []:
            if row.get("tier") == "exempt":
                reason = row.get("reason")
                if not isinstance(reason, str) or not reason.strip():
                    errors.append(
                        f"EXEMPT-NO-REASON: {list_key} row {row.get(id_key)!r} has tier=exempt "
                        f"but no non-empty 'reason' — fail closed rather than silently exempting."
                    )

    exempt_array_keys = ["cohortExempt", "surfaceExempt", "shapeExempt"]
    for array_key in exempt_array_keys:
        for entry in register.get(array_key, []) or []:
            if not isinstance(entry, dict):
                errors.append(
                    f"EXEMPT-NO-REASON: {array_key} entry {entry!r} is a bare id, not an object — "
                    f"every exempt entry must carry a non-empty 'reason'."
                )
                continue
            reason = entry.get("reason")
            if not isinstance(reason, str) or not reason.strip():
                entry_id = entry.get("cohort") or entry.get("surfaceId") or entry.get("shape") or entry
                errors.append(
                    f"EXEMPT-NO-REASON: {array_key} entry {entry_id!r} has no non-empty 'reason' — "
                    f"fail closed rather than silently exempting."
                )

    return errors


def validate_must_watch_observability(register: dict[str, Any]) -> list[str]:
    """D4 (tempdoc 728-followup): every mustWatch entry must declare an
    `observability` tier -- 'sandbox' | 'host' | 'blocked-by-posture'.
    Without this, a round has no mechanical way to distinguish "nobody
    checked this" from "structurally impossible to check here" (the
    install-trust-prompts item, which the current SAC-disable + folder-mount
    posture makes unobservable). A 'blocked-by-posture' entry must also carry
    a non-empty 'note' explaining WHY, so the brief can render an honest
    status instead of a silent skip. Fail closed, same style as
    validate_exempt_reasons above."""
    errors: list[str] = []
    for item in register.get("mustWatch", []) or []:
        item_id = item.get("id", "<unknown>")
        observability = item.get("observability")
        if observability not in ALLOWED_MUST_WATCH_OBSERVABILITY:
            errors.append(
                f"MUSTWATCH-BAD-OBSERVABILITY: mustWatch {item_id!r} has observability="
                f"{observability!r}, must be one of {sorted(ALLOWED_MUST_WATCH_OBSERVABILITY)!r}"
            )
            continue
        if observability == "blocked-by-posture":
            note = item.get("note")
            if not isinstance(note, str) or not note.strip():
                errors.append(
                    f"MUSTWATCH-BAD-OBSERVABILITY: mustWatch {item_id!r} is observability="
                    "'blocked-by-posture' but has no non-empty 'note' explaining why -- "
                    "fail closed rather than silently rendering an unexplained skip."
                )
    return errors


def verify_reach_testids(
    results: dict[str, DriftResult], known_testids: set[str]
) -> list[str]:
    """Part D (tempdoc 750): drop any reach.testid that isn't a real, current
    `data-testid` under modules/ui-web/src -- mutates the register rows in
    `results[*].covered_map` in place (this run's in-memory copy only, never
    the file on disk). A stale testid is dropped, not fatal: generation
    continues, but the warning names the entry + testid so the register gets
    fixed (750 Fork-risk control: a stale pointer is worse than none)."""
    warnings: list[str] = []
    for kind, id_key in (("surface", "surfaceId"), ("shape", "shape")):
        result = results[kind]
        for item_id, row in result.covered_map.items():
            reach = row.get("reach")
            if not isinstance(reach, dict):
                continue
            testid = reach.get("testid")
            if testid and testid not in known_testids:
                warnings.append(
                    f"REACH-STALE-TESTID: {kind} {item_id!r} declares reach.testid={testid!r} "
                    f"but it was not found under {UI_WEB_SRC_REL} -- dropped from output."
                )
                del reach["testid"]
                if not reach:
                    del row["reach"]
    return warnings


def mark_undeclared_reach(results: dict[str, DriftResult]) -> None:
    """Part D (tempdoc 750): a sandbox-tier surface/shape that declares NO reach --
    or whose only reach pointer was just dropped as stale -- is synthesized into the
    honest `unknown` form, so the brief SAYS "entry point unknown, finding it is a
    round deliverable" instead of saying nothing.

    Silence is the failure mode this part exists to kill: a newly-shipped surface
    passes the drift check the moment someone adds a register row, and if nobody
    also remembers a reach pointer, the round re-pays the discovery cost exactly as
    round 6 did (tempdoc 734: a dozen-plus screenshot round-trips hunting an entry
    point that may not exist). Reported, never fail-closed -- whether the entry
    point exists at all is a question only a round can answer.

    Synthesizing at the source means the manifest and derive_round_plan.py inherit
    it for free; neither renderer needs to know about the undeclared case.
    """
    for kind, id_key in (("surface", "surfaceId"), ("shape", "shape")):
        for item_id, row in results[kind].covered_map.items():
            if row.get("tier") != "sandbox":
                continue  # host-tier/exempt items are not reached by hand
            reach = row.get("reach")
            if isinstance(reach, dict) and reach:
                continue
            row["reach"] = {
                "unknown": True,
                "note": (
                    f"reach not declared for {kind} {item_id!r} in the coverage register "
                    "-- locating its entry point (or proving it has none) is a round "
                    "deliverable; record the answer back into "
                    "governance/sandbox-coverage.v1.json"
                ),
            }


def run_drift_check(
    register: dict[str, Any],
    cohort_routes: dict[str, list[str]],
    surface_placements: dict[str, str],
    shapes: list[str],
    known_testids: set[str] | None = None,
) -> tuple[list[str], list[str], dict[str, DriftResult]]:
    """Returns (fatal_errors, warnings, {kind: DriftResult})."""
    errors: list[str] = []
    warnings: list[str] = []

    derived_sets = {
        "cohort": set(cohort_routes.keys()),
        "surface": set(surface_placements.keys()),
        "shape": set(shapes),
    }
    for kind, ids in derived_sets.items():
        if not ids:
            errors.append(
                f"HARD ERROR: derived set '{kind}' is EMPTY — the source moved or the parser broke; "
                f"refusing to silently pass."
            )

    if errors:
        return errors, warnings, {}

    exempt_reason_errors = validate_exempt_reasons(register)
    if exempt_reason_errors:
        # Return early: classify() below indexes *Exempt entries by id_key,
        # which would crash on a malformed bare-id entry this check just caught.
        errors.extend(exempt_reason_errors)
        return errors, warnings, {}

    must_watch_errors = validate_must_watch_observability(register)
    if must_watch_errors:
        errors.extend(must_watch_errors)
        return errors, warnings, {}

    results: dict[str, DriftResult] = {
        "cohort": classify(
            "cohort",
            "cohort",
            derived_sets["cohort"],
            register.get("cohortCoverage", []),
            register.get("cohortExempt", []),
        ),
        "surface": classify(
            "surface",
            "surfaceId",
            derived_sets["surface"],
            register.get("surfaceCoverage", []),
            register.get("surfaceExempt", []),
        ),
        "shape": classify(
            "shape",
            "shape",
            derived_sets["shape"],
            register.get("shapeCoverage", []),
            register.get("shapeExempt", []),
        ),
    }

    register_name = REGISTER_REL
    for kind, result in results.items():
        for drifted_id in result.drift:
            errors.append(
                f"DRIFT: {kind} '{drifted_id}' is neither covered nor exempt in {register_name}"
            )
        for stale_id in result.stale:
            # F3 (tempdoc 728 review): a register row whose id no longer derives
            # from the source is FATAL, not a warning. Otherwise a partial
            # extraction failure (a regex under-match that drops some surfaces)
            # silently shrinks the must-touch set — surfaces quietly vanish from
            # the brief, the opposite of fail-closed. Bidirectional drift control:
            # a new unclassified surface fails, and a vanished one fails too.
            errors.append(
                f"STALE: {kind} '{stale_id}' is classified in {register_name} but no longer "
                f"derives from the current source artifacts. Either the surface was genuinely "
                f"removed (delete its register row) or the parser under-matched (fix the source/"
                f"regex) — do not leave the register drifting from what ships."
            )

    if known_testids is not None:
        warnings.extend(verify_reach_testids(results, known_testids))

    # After stale pointers are dropped, anything left without reach gets the honest
    # "unknown" form -- ordering matters: a row whose only pointer was just dropped
    # must land here, not stay silent.
    mark_undeclared_reach(results)

    return errors, warnings, results


# ---------------------------------------------------------------------------
# Brief / manifest generation
# ---------------------------------------------------------------------------


def mode_included(item: dict[str, Any], mode: str | None) -> bool:
    """Sandbox round B10/round-9+10 (tempdoc 734/804): OPTIONAL 'modes' field
    on a coverage-register item (cohortCoverage/surfaceCoverage/shapeCoverage/
    claims/mustWatch row) scopes the item to specific validation-mode rounds
    -- e.g. an upgrade-survival mustWatch item that is only meaningful in an
    'upgrade-from-release' round and would just be noise in a fresh-install
    brief. An item with NO 'modes' key is unconditional and always included
    (backward-compatible default -- every pre-existing register row has no
    'modes' key and must keep behaving exactly as before).

    `mode=None` (the --check / no-round-context path used by drift-checking
    and by callers that don't know or care about a round mode) means
    "include everything regardless of any declared modes" -- the derivation-
    closure check must still see every declared item, mode-scoped or not.
    """
    modes = item.get("modes")
    if not modes:
        return True
    if mode is None:
        return True
    return mode in modes


def build_manifest(
    results: dict[str, DriftResult],
    cohort_routes: dict[str, list[str]],
    register: dict[str, Any],
    mode: str | None = None,
) -> dict[str, Any]:
    must_touch: list[dict[str, Any]] = []
    covered_elsewhere: list[dict[str, Any]] = []
    exempt: list[dict[str, Any]] = []

    for kind, result in results.items():
        for item_id in sorted(set(result.covered_map) | result.exempt_ids):
            row = result.covered_map.get(item_id)
            tier = row["tier"] if row is not None else "exempt"

            if tier == "sandbox":
                if row is not None and not mode_included(row, mode):
                    # Mode-scoped register row that doesn't apply to this
                    # round's mode -- not a must-touch requirement this round,
                    # so it is simply omitted (not exempt, not covered
                    # elsewhere -- those both mean something else). No
                    # register row currently declares 'modes' on a cohort/
                    # surface/shape item; this branch exists for the same
                    # generic support the mustWatch filtering below has.
                    continue
                entry: dict[str, Any] = {
                    "kind": kind,
                    "id": item_id,
                    "tier": "sandbox",
                    "validateHow": row.get("validateHow", ""),
                }
                if kind == "cohort":
                    entry["routes"] = cohort_routes.get(item_id, [])
                    # F2: propagate a cohort's requiredRoutes (e.g. mcp -> POST /mcp)
                    # so the finalize check can require the product route, not any route.
                    required = row.get("requiredRoutes") if row is not None else None
                    if required:
                        entry["requiredRoutes"] = list(required)
                elif kind == "surface":
                    entry["evidenceToken"] = evidence_token(item_id)
                # Part D (tempdoc 750): propagate the (already testid-verified,
                # see verify_reach_testids) reach pointer verbatim so the round
                # plan and the brief can render "how to find it" instead of
                # re-paying the discovery cost every round.
                reach = row.get("reach") if row is not None else None
                if reach:
                    entry["reach"] = reach
                must_touch.append(entry)
            elif tier == "host":
                covered_elsewhere.append({"kind": kind, "id": item_id, "tier": "host"})
            else:
                # tier == "exempt" (explicit row) or no row at all (bare *Exempt array entry)
                exempt.append({"kind": kind, "id": item_id})

    return {
        "version": 1,
        "mustTouch": must_touch,
        "coveredElsewhere": covered_elsewhere,
        "exempt": exempt,
        # Part D (tempdoc 750): mustWatch joins the manifest so
        # derive_round_plan.py stops being structurally unable to derive it
        # (it previously only lived in coverage-brief.md's Markdown).
        # sandbox rounds 9+10 (tempdoc 734/804 B10): mode-scoped entries are
        # filtered here, at staging time, against the round's resolved mode --
        # see mode_included() above.
        "mustWatch": [
            item for item in (register.get("mustWatch", []) or []) if mode_included(item, mode)
        ],
    }


def render_reach_line(reach: dict[str, Any]) -> str | None:
    """Part D (tempdoc 750): render a reach pointer as one "Reach:" line for
    coverage-brief.md. `unknown: true` renders the honest "entry point
    unknown, this is a round deliverable" form instead of a fabricated path
    (750 Part D: reported, not fail-closed -- free-chat/workflow-run are
    genuinely unresolved product questions a stage-time block can't answer)."""
    if not reach:
        return None
    if reach.get("unknown"):
        note = reach.get("note", "")
        return f"Reach: ENTRY POINT UNKNOWN - {note}"
    parts: list[str] = []
    if reach.get("testid"):
        parts.append(f"testid=`{reach['testid']}`")
    if reach.get("navPath"):
        parts.append(f"navPath: {reach['navPath']}")
    if reach.get("apiRecipe"):
        parts.append(f"apiRecipe: {reach['apiRecipe']}")
    if reach.get("note"):
        parts.append(f"note: {reach['note']}")
    if not parts:
        return None
    return "Reach: " + "; ".join(parts)


def build_brief_markdown(
    manifest: dict[str, Any], register: dict[str, Any], mode: str | None = None
) -> str:
    lines: list[str] = []
    lines.append(
        "<!-- GENERATED by scripts/sandbox/gen_coverage_brief.py (tempdoc 728). "
        "This file is the authority for this round's Sandbox coverage — do not hand-edit; "
        "regenerate instead. -->"
    )
    lines.append("")
    lines.append("# Sandbox release-candidate coverage brief")
    lines.append("")

    must_touch = manifest["mustTouch"]
    covered_elsewhere = manifest["coveredElsewhere"]
    exempt = manifest["exempt"]

    lines.append("## Must-touch surfaces (sandbox tier)")
    lines.append("")
    if not must_touch:
        lines.append("None this round.")
    else:
        by_kind: dict[str, list[dict[str, Any]]] = {}
        for item in must_touch:
            by_kind.setdefault(item["kind"], []).append(item)
        for kind in sorted(by_kind):
            lines.append(f"### {kind.capitalize()}")
            lines.append("")
            for item in sorted(by_kind[kind], key=lambda x: x["id"]):
                lines.append(f"- **{item['id']}** — {item['validateHow']}")
                if kind == "cohort" and item.get("routes"):
                    routes_str = ", ".join(f"`{r}`" for r in item["routes"])
                    lines.append(f"  - routes: {routes_str}")
                if kind == "surface":
                    lines.append(f"  - evidence token: `{item['evidenceToken']}`")
                reach_line = render_reach_line(item.get("reach"))
                if reach_line:
                    lines.append(f"  - {reach_line}")
            lines.append("")

    lines.append("## Covered elsewhere (host tier)")
    lines.append("")
    if not covered_elsewhere:
        lines.append("None this round.")
    else:
        for item in sorted(covered_elsewhere, key=lambda x: (x["kind"], x["id"])):
            lines.append(f"- {item['kind']}:{item['id']}")
    lines.append("")

    lines.append("## Exempt (not validated in Sandbox)")
    lines.append("")
    if not exempt:
        lines.append("None this round.")
    else:
        by_kind_exempt: dict[str, list[str]] = {}
        for item in exempt:
            by_kind_exempt.setdefault(item["kind"], []).append(item["id"])
        summary_parts = [
            f"{len(ids)} {kind}(s): {', '.join(sorted(ids))}"
            for kind, ids in sorted(by_kind_exempt.items())
        ]
        lines.append("- " + "; ".join(summary_parts))
    lines.append("")

    lines.append("## Must-watch (re-injected every round)")
    lines.append("")
    # sandbox rounds 9+10 (tempdoc 734/804 B10): render from the manifest's
    # already mode-filtered list, not the raw register, so the brief a round
    # reads and the coverage-manifest.json a round's tooling reads never
    # disagree about which mustWatch items apply to this round's mode.
    must_watch = manifest.get("mustWatch", register.get("mustWatch", []))
    if not must_watch:
        lines.append("None.")
    else:
        observability_labels = {
            "sandbox": "sandbox-observable",
            "host": "host-observable",
            "blocked-by-posture": "NOT OBSERVABLE under current sandbox posture",
        }
        for item in must_watch:
            observability = item.get("observability", "sandbox")
            label = observability_labels.get(observability, observability)
            lines.append(f"- **{item['id']}** [{label}] — {item['reason']}")
            note = item.get("note")
            if note:
                lines.append(f"  - {note}")
    lines.append("")

    lines.append("## Claims cross-check")
    lines.append("")
    # Generic 'modes' support (same as mustWatch above) extends to claims too,
    # per the register's documented item shape -- filtered here since claims
    # never joined the JSON manifest schema (no consumer needs it there).
    claims = [c for c in (register.get("claims", []) or []) if mode_included(c, mode)]
    if not claims:
        lines.append("None.")
    else:
        for claim in claims:
            lines.append(
                f"- \"{claim['claim']}\" (source: {claim['source']}) -> covers `{claim['coversId']}`"
            )
    lines.append("")

    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Generate a per-round Sandbox coverage brief, DERIVED from committed surface "
            "artifacts, failing closed if any shipped surface is unclassified (tempdoc 728)."
        )
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="Run the drift check only; print a summary; no files written (default mode).",
    )
    parser.add_argument(
        "--out-dir",
        type=str,
        default=None,
        help="Run the drift check, then (if clean) write coverage-brief.md + coverage-manifest.json here.",
    )
    parser.add_argument(
        "--register",
        type=str,
        default=None,
        help=argparse.SUPPRESS,  # testability-only override; real register is the default
    )
    parser.add_argument(
        "--mode",
        type=str,
        default=None,
        help=(
            "This round's resolved validation mode (e.g. 'upgrade-from-release'), as written "
            "by sandbox-launch.py's write_validation_mode(). Scopes out any coverage-register "
            "item declaring a 'modes' list that does not include this mode. Omitted (default) "
            "means include everything, regardless of any declared 'modes' -- used by --check "
            "and by any caller without round context."
        ),
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    repo_root = find_repo_root(Path(__file__).parent)

    route_manifest_path = repo_root / ROUTE_MANIFEST_REL
    core_plugin_path = repo_root / CORE_PLUGIN_REL
    interaction_shapes_path = repo_root / INTERACTION_SHAPES_REL
    register_path = Path(args.register) if args.register else (repo_root / REGISTER_REL)
    ui_web_src_path = repo_root / UI_WEB_SRC_REL

    cohort_routes = extract_cohorts(route_manifest_path)
    surface_placements = extract_surfaces(core_plugin_path)
    shapes = extract_shapes(interaction_shapes_path)
    register = json.loads(register_path.read_text(encoding="utf-8"))
    known_testids = scan_data_testids(ui_web_src_path)

    errors, warnings, results = run_drift_check(
        register, cohort_routes, surface_placements, shapes, known_testids
    )

    if errors:
        print("gen_coverage_brief: FAILED\n", file=sys.stderr)
        for err in errors:
            print(f"  - {err}", file=sys.stderr)
        return 1

    print(
        f"gen_coverage_brief: OK — {len(cohort_routes)} cohort(s), {len(surface_placements)} "
        f"surface(s), {len(shapes)} shape(s) all classified against {register_path}."
    )
    if warnings:
        print("\nWarnings (non-fatal):")
        for warn in warnings:
            print(f"  - {warn}")

    if args.out_dir:
        out_dir = Path(args.out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        manifest = build_manifest(results, cohort_routes, register, mode=args.mode)
        brief_md = build_brief_markdown(manifest, register, mode=args.mode)

        manifest_path = out_dir / "coverage-manifest.json"
        brief_path = out_dir / "coverage-brief.md"

        manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=False) + "\n", encoding="utf-8")
        brief_path.write_text(brief_md, encoding="utf-8")

        print(f"\nWrote {brief_path}")
        print(f"Wrote {manifest_path}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
