#!/usr/bin/env python3
"""FINALIZE-time coverage assertion for a JustSearch Sandbox validation round.

Diffs what a round was REQUIRED to exercise (a generated must-touch manifest,
see gen_coverage_brief.py) against what it ACTUALLY exercised (endpoint traces
+ evidence screenshots), and fails closed on any untouched required surface.

This is the fail-closed half of "coverage follows shipment" (tempdoc 728): it
lives where coverage happens (the validation round), not in CI.

Gates enforced by main() (all fail-closed unless noted):
  - mustTouch coverage (cohort/surface/shape items, tier=sandbox)
  - duplicate-content collisions across required evidence tokens (F-729-3)
  - round retrospective (D1): presence, substance, required topic coverage,
    AND a TBS time-accounting section (Session-Based Test Management, Bach &
    Bach, STQE 2000 -- adapted; tempdoc 750 Part B)
  - round findings (823 §4): presence, substance, required topic coverage --
    severity, observation with an evidence pointer, regression home; an
    explicit no-findings declaration satisfies the topics for a clean round
  - evidence review (735-followup): a reader must examine every credit-
    eligible screenshot
  - mustWatch verdict record (808 I1a): every mode-included mustWatch id
    carries a verdict; 'observed-fail' prints loudly but does NOT flip the
    exit code (recording is graded, outcomes stay judgment)
  - mutating-surface probe verdict (808 I1b): collect-evidence.ps1's POST
    rung result, fail-closed; 'skipped' warns loudly instead
  - session self-analysis (808 I2): presence + byte floor, content ungraded
  - evidence mtime timeline: REPORT-ONLY, does not affect the exit code

Pure Python 3 stdlib. No network access.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass, field
from typing import Iterable


# UI surfaces/shapes are proven by SCREENSHOTS, not by the mechanical API
# snapshots the capture harness (collect-evidence.ps1) also drops into the
# evidence dir (e.g. api-api-health.json). Crediting a surface token from an
# api-*.json filename is a false positive (tempdoc 728 review, defect F1), so
# surface/shape matching considers only image files.
IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"}

# Bulk-frame exclusion (round-12 retrospective A5 / tempdoc 806 W3 item 1): a
# periodic capture driver (e.g. one screenshot every ~1.5s to watch a long
# install/upgrade) can produce hundreds of near-identical frames. Every one
# clears MIN_SCREENSHOT_BYTES, so without this exclusion each becomes
# "credit-eligible" and evidence-review.v1.json's reader gate (see
# check_evidence_review below) must enumerate every single one -- making the
# mandatory reader pass impossible by construction (sandbox-CLAUDE.md states
# ~90 images is one agent's practical review budget; round 12 hit 947).
#
# Convention: a round's periodic/bulk capture driver writes into a
# subdirectory of the evidence dir named BULK_FRAMES_DIRNAME (documented in
# sandbox-CLAUDE.md's "Evidence review" section). Files under it are still
# collected by load_evidence_files (present as evidence, inspectable, not
# deleted) but excluded from the credit-eligible screenshot set: they cannot
# satisfy a mustTouch surface/shape token, and evidence-review.v1.json is not
# required to enumerate them individually. A round that wants a bulk frame to
# actually evidence a requirement must promote/copy that one frame to the top
# level (or another non-bulk directory) with a real name -- exactly like any
# other screenshot.
BULK_FRAMES_DIRNAME = "raw-frames"

# Post-round investigation exclusion (round-15 retrospective finding 6,
# tempdoc 817): a same-share investigation session that runs AFTER finalize
# (e.g. following up on a filed finding) can add screenshots/notes into the
# SAME evidence dir the finalized round already reviewed. Round 15: a
# post-finalize investigation session added ~52 screenshots, and re-running
# check_coverage.py against that evidence dir then failed the ALREADY-
# FINALIZED review as incomplete, because none of those new files were (or
# could have been) in the round's evidence-review.v1.json 'examined' list --
# the review was complete and correct at the moment the round finalized.
#
# Convention (sandbox-CLAUDE.md, "Writing results" section): investigation
# that happens after finalize writes its screenshots/notes into a
# subdirectory of the evidence dir named POST_ROUND_DIRNAME. Same treatment
# as BULK_FRAMES_DIRNAME above: files under it are still collected by
# load_evidence_files (present as evidence, inspectable, never deleted) but
# excluded from the credit-eligible screenshot set -- they cannot satisfy a
# mustTouch surface/shape token, and evidence-review.v1.json's 'examined'
# list is not required to enumerate them.
POST_ROUND_DIRNAME = "post-round"


# --------------------------------------------------------------------------
# Round retrospective check (D1): a required PROCESS deliverable, not a
# product surface. Deliberately NOT routed through mustTouch/cohortCoverage/
# surfaceCoverage -- that schema classifies surfaces the candidate SHIPS;
# this is a fixed, every-round check on the harness's own improvement loop,
# so it lives as its own top-level gate here instead.
#
# tempdoc 750 Part B extends this with a TBS (Time-Box-Session) debrief gate:
# Session-Based Test Management (J. Bach & J. Bach, STQE 2000) treats a
# session's time accounting -- how much went to setup, testing, bug
# investigation/write-up, and how much was on-charter vs. opportunity -- as
# part of the debrief, not an afterthought. Rounds capture evidence files
# (screenshots, api-*.json) whose file mtimes are the only OTHER timing
# signal that exists (see emit_evidence_timeline below, report-only); this
# gate requires the round's own TBS self-report in the retrospective.
# --------------------------------------------------------------------------

RETROSPECTIVE_FILENAME = "retrospective.md"

# Deliberately dumb (no NLP, per the design constraint): a minimum
# non-whitespace byte count, AND at least one substring hit per required
# topic group, checked case-insensitively against the whole file. This
# cannot judge QUALITY, but it reliably rejects the two concrete failure
# modes this closes: an absent file, and a placeholder/stub file that
# exists only to satisfy the check.
RETROSPECTIVE_MIN_BYTES = 400

RETROSPECTIVE_REQUIRED_TOPICS: list[tuple[str, tuple[str, ...]]] = [
    (
        "what the harness/docs got wrong or made impossible",
        ("wrong", "impossible", "couldn't", "could not", "unfollowable", "doesn't work", "does not work"),
    ),
    (
        "what had to be worked around or built",
        ("work around", "workaround", "worked around", "built", "wrote its own", "had to build", "had to write"),
    ),
    (
        "what slowed the round down",
        ("slow", "friction", "wasted", "delay", "cost us", "cost time"),
    ),
    (
        "what would change",
        ("would change", "should change", "recommend", "next round", "fix:"),
    ),
]

# TBS debrief gate (tempdoc 750 Part B): the retrospective must ALSO carry a
# time-accounting section -- a heading line matching TIME_ACCOUNTING_HEADING_RE
# -- whose body hits at least one keyword from EACH group below. Deliberately
# the same dumb keyword-substring style as RETROSPECTIVE_REQUIRED_TOPICS
# above: no NLP, no attempt to judge whether the numbers are honest, only
# whether the round bothered to account for where session time went at all.
TIME_ACCOUNTING_HEADING_RE = re.compile(r"^#{2,6}\s.*time accounting", re.IGNORECASE | re.MULTILINE)

# Matches every markdown heading line (any level from 2-6 "#"s), used to find
# the end of the time-accounting section: everything up to the NEXT such
# heading, or end of file.
_HEADING_LINE_RE = re.compile(r"^#{2,6}\s.*$", re.MULTILINE)

TIME_ACCOUNTING_REQUIRED_GROUPS: list[tuple[str, tuple[str, ...]]] = [
    ("setup", ("setup",)),
    ("install", ("install",)),
    ("coverage", ("coverage",)),
    ("investigat", ("investigat",)),
    ("write-up/writeup/report", ("write-up", "writeup", "report")),
    ("on-charter/charter", ("on-charter", "charter")),
    ("opportunity", ("opportunity",)),
]


def _time_accounting_section(content: str) -> str | None:
    """Return the body of the retrospective's time-accounting section (the
    heading line itself excluded; everything from just after that heading up
    to the next markdown heading of any level, or end of file).

    Returns None if no heading line matches TIME_ACCOUNTING_HEADING_RE.
    """
    headings = list(_HEADING_LINE_RE.finditer(content))
    for i, heading in enumerate(headings):
        if TIME_ACCOUNTING_HEADING_RE.match(heading.group(0)):
            start = heading.end()
            end = headings[i + 1].start() if i + 1 < len(headings) else len(content)
            return content[start:end]
    return None


def check_retrospective(evidence_dir: str | None) -> tuple[bool, str]:
    """Check evidence/retrospective.md is present and substantial (D1).

    Returns (ok, reason). Does not raise on I/O errors -- a missing/unreadable
    file is reported as a normal failure reason, matching this module's
    warn-and-continue style elsewhere.
    """
    if not evidence_dir:
        return False, "no --evidence-dir given; cannot check for evidence/retrospective.md"

    path = os.path.join(evidence_dir, RETROSPECTIVE_FILENAME)
    if not os.path.isfile(path):
        return False, f"{RETROSPECTIVE_FILENAME} not found in evidence dir {evidence_dir!r}"

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            content = fh.read()
    except OSError as exc:
        return False, f"{RETROSPECTIVE_FILENAME} could not be read: {exc}"

    stripped = content.strip()
    if len(stripped) < RETROSPECTIVE_MIN_BYTES:
        return False, (
            f"{RETROSPECTIVE_FILENAME} is only {len(stripped)} non-whitespace-trimmed byte(s) "
            f"(minimum {RETROSPECTIVE_MIN_BYTES}) -- reads like an empty or placeholder stub, "
            f"not a real retrospective"
        )

    lowered = content.lower()
    missing_topics = [
        label
        for label, alternatives in RETROSPECTIVE_REQUIRED_TOPICS
        if not any(alt in lowered for alt in alternatives)
    ]
    if missing_topics:
        return False, (
            f"{RETROSPECTIVE_FILENAME} is missing required coverage of: {'; '.join(missing_topics)} "
            f"(no matching keyword found for the topic) -- the retrospective must cover what the "
            f"harness/docs got wrong or made impossible, what was worked around or built, what "
            f"slowed the round down, and what would change"
        )

    # TBS debrief gate (tempdoc 750 Part B): a time-accounting section is
    # required in addition to the four topic groups above.
    tbs_section = _time_accounting_section(content)
    if tbs_section is None:
        return False, (
            f"{RETROSPECTIVE_FILENAME} is missing a time-accounting section -- no heading line "
            "matches '##+ ... time accounting' (case-insensitive). Session-Based Test "
            "Management (Bach & Bach, STQE 2000) requires a TBS debrief: add a heading such as "
            "'## Time accounting' covering setup, install, coverage, investigation, write-up, "
            "and on-charter vs. opportunity time."
        )

    lowered_tbs = tbs_section.lower()
    missing_tbs_groups = [
        label
        for label, alternatives in TIME_ACCOUNTING_REQUIRED_GROUPS
        if not any(alt in lowered_tbs for alt in alternatives)
    ]
    if missing_tbs_groups:
        return False, (
            f"{RETROSPECTIVE_FILENAME}'s time-accounting section is missing required coverage "
            f"of: {'; '.join(missing_tbs_groups)} (no matching keyword found) -- the TBS section "
            "must account for setup, install, coverage, investigation, write-up, on-charter "
            "classification, and opportunity time."
        )

    return True, (
        f"{RETROSPECTIVE_FILENAME} present ({len(stripped)} bytes, all required topics found, "
        "TBS time-accounting section present with all required groups)"
    )


# --------------------------------------------------------------------------
# Round findings check (round 16, tempdoc 823 §4): the round's DEFECT report,
# as its own artifact.
#
# Round 16 produced five findings -- one blocking -- and wrote no standalone
# findings file: they were scattered across mustwatch-verdicts.v1.json,
# retrospective.md and session-analysis.md, and reassembling them cost the
# host-side reader a pass over three artifacts written for three other
# purposes. Nothing checked for it, because until now the convention lived
# only in prose ("Report findings by journey" in sandbox-CLAUDE.md's *Writing
# results*).
#
# Modeled directly on check_retrospective above: presence, a deliberately dumb
# byte floor, keyword topic groups, a clear PRESENT/BLOCKING report, AND-ed
# into the same fail-closed exit in main(). It cannot judge whether the
# findings are RIGHT -- only that the round wrote them down somewhere a reader
# can find them.
#
# Escape valve (the same shape check_mustwatch_verdicts gives 'unobservable':
# an honest answer is acceptable when it says so explicitly): a round that
# genuinely found nothing satisfies the topic groups with an explicit
# no-findings declaration. The byte floor still applies -- "no findings" as a
# one-line file is a stub, and a clean round still has to say what it
# exercised to reach that conclusion.
# --------------------------------------------------------------------------

FINDINGS_FILENAME = "findings.md"

FINDINGS_MIN_BYTES = 400

# An explicit no-findings declaration satisfies the topic groups below. Kept
# separate (not folded in as extra alternatives) so the pass reason can SAY
# which of the two shapes was accepted -- a clean round and a round with
# findings should not read identically in the finalize report.
FINDINGS_NO_FINDINGS_DECLARATIONS: tuple[str, ...] = (
    "no findings",
    "zero findings",
    "no defects",
    "no blocking findings and no non-blocking findings",
)

FINDINGS_REQUIRED_TOPICS: list[tuple[str, tuple[str, ...]]] = [
    (
        "each finding's severity/classification",
        ("severity", "blocking", "high", "medium", "low", "critical"),
    ),
    (
        "what was observed (evidence pointer)",
        ("evidence", "screenshot", ".png", "api-", "log", "traces", "repro"),
    ),
    (
        "each finding's regression home / routing",
        ("regression home", "routing", "route", "must-watch", "mustwatch", "gate", "test", "tempdoc"),
    ),
]


def check_findings(evidence_dir: str | None) -> tuple[bool, str]:
    """Check evidence/findings.md is present and substantial (823 §4).

    Returns (ok, reason). Does not raise on I/O errors -- a missing/unreadable
    file is reported as a normal failure reason, matching check_retrospective's
    style.
    """
    if not evidence_dir:
        return False, f"no --evidence-dir given; cannot check for evidence/{FINDINGS_FILENAME}"

    path = os.path.join(evidence_dir, FINDINGS_FILENAME)
    if not os.path.isfile(path):
        return False, f"{FINDINGS_FILENAME} not found in evidence dir {evidence_dir!r}"

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            content = fh.read()
    except OSError as exc:
        return False, f"{FINDINGS_FILENAME} could not be read: {exc}"

    stripped = content.strip()
    if len(stripped) < FINDINGS_MIN_BYTES:
        return False, (
            f"{FINDINGS_FILENAME} is only {len(stripped)} non-whitespace-trimmed byte(s) "
            f"(minimum {FINDINGS_MIN_BYTES}) -- reads like an empty or placeholder stub, not a "
            f"real findings report (a round that genuinely found nothing still has to say what "
            f"it exercised to reach that conclusion)"
        )

    lowered = content.lower()
    declared_clean = any(phrase in lowered for phrase in FINDINGS_NO_FINDINGS_DECLARATIONS)
    if declared_clean:
        return True, (
            f"{FINDINGS_FILENAME} present ({len(stripped)} bytes) with an explicit no-findings "
            "declaration -- accepted as a clean-round report"
        )

    missing_topics = [
        label
        for label, alternatives in FINDINGS_REQUIRED_TOPICS
        if not any(alt in lowered for alt in alternatives)
    ]
    if missing_topics:
        return False, (
            f"{FINDINGS_FILENAME} is missing required coverage of: {'; '.join(missing_topics)} "
            f"(no matching keyword found for the topic) -- each finding needs a severity, what "
            f"was observed with an evidence pointer, and its regression home. If the round "
            f"genuinely found nothing, say so explicitly (e.g. 'no findings') and describe what "
            f"was exercised to reach that conclusion"
        )

    return True, (
        f"{FINDINGS_FILENAME} present ({len(stripped)} bytes, all required topics found)"
    )


# --------------------------------------------------------------------------
# Evidence review check: a required READER gate, not a product surface.
#
# Measured, not assumed (tempdoc 735-followup): known-bad artefacts were
# planted into a copy of a real round's evidence. A "mislabeled-capture"
# defect (right bytes, wrong claim -- e.g. a command-palette screenshot
# named/credited as the logs surface) was caught 0/4 by check_coverage's own
# filename-token match (check_surface/check_shape structurally can only see
# the name, never the pixels), while three independent blind readers caught
# it 4/4, 4/4, 4/4. Four such plants alone flip the gate from a correct FAIL
# to a clean exit-0 -- each uncovered surface gets "credited" by a screenshot
# of something else entirely. No content hash can catch this (a
# mislabeled-capture is real, correctly-sized, non-duplicate bytes); only a
# reader who looks at the pixels can. This makes that reader step a required,
# mechanically fail-closed tier instead of an optional judgment call.
#
# Modeled directly on check_retrospective above: file presence, a substance
# check, a clear PRESENT/BLOCKING report, AND-ed into the same fail-closed
# exit in main(). See scripts/sandbox/evidence-review.schema.json for the
# documented shape and scripts/sandbox/sandbox-CLAUDE.md 'Writing results' ->
# 'Evidence review' for the authoring procedure.
# --------------------------------------------------------------------------

EVIDENCE_REVIEW_FILENAME = "evidence-review.v1.json"


def _load_evidence_review_json(evidence_dir: str) -> tuple[dict | None, str | None]:
    """Load evidence/evidence-review.v1.json. Returns (data, error_reason).

    Exactly one of the two is non-None. Mirrors check_retrospective's
    warn-and-continue style: I/O and parse failures are reported as normal
    failure reasons, not raised.
    """
    path = os.path.join(evidence_dir, EVIDENCE_REVIEW_FILENAME)
    if not os.path.isfile(path):
        return None, f"{EVIDENCE_REVIEW_FILENAME} not found in evidence dir {evidence_dir!r}"

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = json.load(fh)
    except OSError as exc:
        return None, f"{EVIDENCE_REVIEW_FILENAME} could not be read: {exc}"
    except json.JSONDecodeError as exc:
        return None, f"{EVIDENCE_REVIEW_FILENAME} is not valid JSON: {exc}"

    if not isinstance(data, dict):
        return None, (
            f"{EVIDENCE_REVIEW_FILENAME} must contain a JSON object, got "
            f"{type(data).__name__}"
        )

    return data, None


def check_evidence_review(evidence_dir: str | None, screenshots: set[str]) -> tuple[bool, str]:
    """Check evidence/evidence-review.v1.json: presence, shape, and the two
    fail-closed assertions (missing-examined, non-empty mismatches).

    `screenshots` is the caller's credit-eligible set (post-size-floor,
    image-only, lowercased filenames) -- exactly the set check_surface/
    check_shape can award coverage credit against, and therefore exactly the
    set a reader must have looked at for a clean pass to mean anything.

    Returns (ok, reason). Does not raise on I/O or shape errors -- reported
    as a normal failure reason, matching check_retrospective's style.
    """
    if not evidence_dir:
        return False, "no --evidence-dir given; cannot check for evidence/evidence-review.v1.json"

    data, err = _load_evidence_review_json(evidence_dir)
    if err:
        return False, err
    assert data is not None  # narrowed by the err check above

    examined_raw = data.get("examined")
    if not isinstance(examined_raw, list):
        return False, (
            f"{EVIDENCE_REVIEW_FILENAME} 'examined' must be a list, got "
            f"{type(examined_raw).__name__}"
        )

    mismatches = data.get("mismatches")
    if not isinstance(mismatches, list):
        return False, (
            f"{EVIDENCE_REVIEW_FILENAME} 'mismatches' must be a list, got "
            f"{type(mismatches).__name__}"
        )

    uncertain = data.get("uncertain")
    if not isinstance(uncertain, list):
        return False, (
            f"{EVIDENCE_REVIEW_FILENAME} 'uncertain' must be a list, got "
            f"{type(uncertain).__name__}"
        )

    examined = {str(f).lower() for f in examined_raw}

    # THE load-bearing assertion (735-followup): every credit-eligible
    # screenshot must appear in the reviewer's examined list. Fail closed on
    # any omission -- a reader that stopped partway through and reported only
    # what it managed to open would otherwise be indistinguishable from a
    # clean pass on the un-opened remainder ("no mismatches" in the 40% it
    # never looked at is not a finding, it's silence). This is the exact
    # "absence of signal is not evidence of absence" trap the reader step was
    # added to close, so it cannot be allowed to recur inside the reader step
    # itself.
    missing = screenshots - examined
    if missing:
        return False, (
            f"{EVIDENCE_REVIEW_FILENAME} does not list {len(missing)} credit-eligible "
            f"screenshot(s) as examined: {sorted(missing)!r} -- every screenshot this round "
            "could receive coverage credit for must be opened and judged by a reader. A "
            "partial review (budget exhausted mid-set, sharded and not reconciled, etc.) "
            "must fail closed here, not report a clean pass on the screenshots it never "
            "opened. See sandbox-CLAUDE.md 'Writing results' -> 'Evidence review' for the "
            "sharding procedure on large evidence sets."
        )

    # F-735: fail closed when the review reports a mismatch. A review that
    # finds a lie and passes anyway is decoration, not a review.
    if mismatches:
        details = "; ".join(
            f"{m.get('file', '<no file>')!r} claims {m.get('claims', '<no claims>')!r} but "
            f"shows {m.get('shows', '<no shows>')!r}"
            for m in mismatches
            if isinstance(m, dict)
        )
        return False, (
            f"{EVIDENCE_REVIEW_FILENAME} reports {len(mismatches)} mismatch(es) -- a filename "
            "claiming something the pixels do not support is BLOCKING, not advisory: "
            f"{details}"
        )

    if uncertain:
        details = "; ".join(
            f"{u.get('file', '<no file>')!r}: {u.get('reason', '<no reason>')}"
            for u in uncertain
            if isinstance(u, dict)
        )
        return True, (
            f"{EVIDENCE_REVIEW_FILENAME} present, all {len(screenshots)} credit-eligible "
            f"screenshot(s) examined, no mismatches -- but {len(uncertain)} flagged uncertain "
            f"(non-blocking, needs human follow-up): {details}"
        )

    return True, (
        f"{EVIDENCE_REVIEW_FILENAME} present, all {len(screenshots)} credit-eligible "
        "screenshot(s) examined, no mismatches, none uncertain"
    )


# --------------------------------------------------------------------------
# mustWatch verdict record (tempdoc 808 I1a).
#
# The register's mustWatch items are re-injected into every round's brief
# (gen_coverage_brief.py's build_manifest writes the mode-filtered list into
# coverage-manifest.json's top-level "mustWatch" array) with their validateHow
# notes -- and until now nothing here referenced them. A round could observe
# NOTHING on all 13 and still exit 0: a recorded claim nothing verifies, which
# is this campaign's own signature defect class applied to the harness itself.
#
# What is graded is the RECORDING, not the outcome. The gate's job is to make
# the *claim of observation* verifiable (the write-time witness shape of
# tempdoc 798 D2); deciding what a failed watch MEANS is the round agent's and
# the owner's call, and flows through the findings process like any defect.
# So: a missing file, an item set that does not cover every mode-included
# mustWatch id, an invalid verdict enum, or an 'unobservable' with no note all
# fail closed -- while 'observed-fail' prints prominently and does NOT flip the
# exit code.
#
# The 'unobservable' + non-empty-note rule mirrors the register's own honesty
# rule for install-trust-prompts (observability: blocked-by-posture carries a
# note explaining WHY): "not observable this round" is an acceptable answer
# only when it says why.
# --------------------------------------------------------------------------

MUSTWATCH_VERDICTS_FILENAME = "mustwatch-verdicts.v1.json"

MUSTWATCH_VERDICT_VALUES = ("observed-pass", "observed-fail", "unobservable")


def check_mustwatch_verdicts(
    evidence_dir: str | None, manifest: dict
) -> tuple[bool, str, list[str]]:
    """Check evidence/mustwatch-verdicts.v1.json against the manifest's
    mode-included mustWatch ids (tempdoc 808 I1a).

    Returns (ok, reason, observed_fail_ids). The third element is reported
    separately BECAUSE it is deliberately not part of `ok`: an observed-fail
    is a real finding the report must shout about, but severity is judgment,
    so it must not silently flip the exit code (see the section comment).

    Does not raise on I/O or shape errors -- reported as a normal failure
    reason, matching check_retrospective/check_evidence_review's style.
    """
    required_ids = [
        str(item.get("id", ""))
        for item in (manifest.get("mustWatch") or [])
        if isinstance(item, dict) and item.get("id")
    ]

    if not evidence_dir:
        return False, f"no --evidence-dir given; cannot check for evidence/{MUSTWATCH_VERDICTS_FILENAME}", []

    path = os.path.join(evidence_dir, MUSTWATCH_VERDICTS_FILENAME)
    if not os.path.isfile(path):
        return False, (
            f"{MUSTWATCH_VERDICTS_FILENAME} not found in evidence dir {evidence_dir!r} -- "
            f"the round must record a verdict for each of the {len(required_ids)} mustWatch "
            "item(s) in this round's coverage-manifest.json"
        ), []

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = json.load(fh)
    except OSError as exc:
        return False, f"{MUSTWATCH_VERDICTS_FILENAME} could not be read: {exc}", []
    except json.JSONDecodeError as exc:
        return False, f"{MUSTWATCH_VERDICTS_FILENAME} is not valid JSON: {exc}", []

    if not isinstance(data, dict):
        return False, (
            f"{MUSTWATCH_VERDICTS_FILENAME} must contain a JSON object, got "
            f"{type(data).__name__}"
        ), []

    items = data.get("items")
    if not isinstance(items, list):
        return False, (
            f"{MUSTWATCH_VERDICTS_FILENAME} 'items' must be a list, got "
            f"{type(items).__name__}"
        ), []

    recorded: dict[str, str] = {}
    observed_fail_ids: list[str] = []
    for index, raw in enumerate(items):
        if not isinstance(raw, dict):
            return False, (
                f"{MUSTWATCH_VERDICTS_FILENAME} items[{index}] must be an object, got "
                f"{type(raw).__name__}"
            ), []
        item_id = str(raw.get("id", "")).strip()
        if not item_id:
            return False, f"{MUSTWATCH_VERDICTS_FILENAME} items[{index}] has no 'id'", []
        verdict = raw.get("verdict")
        if verdict not in MUSTWATCH_VERDICT_VALUES:
            return False, (
                f"{MUSTWATCH_VERDICTS_FILENAME} item {item_id!r} has verdict={verdict!r}, "
                f"must be one of {list(MUSTWATCH_VERDICT_VALUES)!r}"
            ), []
        if verdict == "unobservable" and not str(raw.get("note", "")).strip():
            return False, (
                f"{MUSTWATCH_VERDICTS_FILENAME} item {item_id!r} is verdict='unobservable' "
                "with an empty/missing 'note' -- 'not observable this round' is only an "
                "honest answer when it says WHY (mirrors the register's own note rule for "
                "observability='blocked-by-posture')"
            ), []
        recorded[item_id] = verdict
        if verdict == "observed-fail":
            observed_fail_ids.append(item_id)

    missing = [item_id for item_id in required_ids if item_id not in recorded]
    if missing:
        return False, (
            f"{MUSTWATCH_VERDICTS_FILENAME} records no verdict for {len(missing)} "
            f"mode-included mustWatch item(s): {sorted(missing)!r} -- every mustWatch id in "
            "this round's coverage-manifest.json must carry a verdict. An item nobody looked "
            "at is 'unobservable' WITH a note, not an omission."
        ), []

    extra = sorted(set(recorded) - set(required_ids))
    extra_note = f"; {len(extra)} extra id(s) also recorded: {extra!r}" if extra else ""
    return True, (
        f"{MUSTWATCH_VERDICTS_FILENAME} present, all {len(required_ids)} mode-included "
        f"mustWatch item(s) carry a verdict{extra_note}"
    ), observed_fail_ids


# --------------------------------------------------------------------------
# Mutating-surface probe verdict (tempdoc 808 I1b).
#
# collect-evidence.ps1 already detects the round-10 false-green class (every
# GET rung green while the whole mutating surface 401s) and already refuses to
# change its own exit code -- capture-only by contract, judgment is host-side.
# But it wrote that verdict only to the console and collect-evidence-summary.txt,
# which NO host-side checker reads, so three rounds of tested detection never
# reached an exit code. It now also writes a machine-readable
# evidence/mutating-probe.v1.json; this is the host-side half that grades it.
#
# Fail-closed immediately (no soak period): this completes existing, three-
# rounds-tested machinery rather than debuting new detection. 'skipped' -- the
# backend was never reachable -- prints a prominent warning instead of failing,
# because an unreachable backend already fails coverage elsewhere and a second
# failure for the same cause is noise, not signal.
# --------------------------------------------------------------------------

MUTATING_PROBE_FILENAME = "mutating-probe.v1.json"

MUTATING_PROBE_STATUS_VALUES = ("pass", "fail", "skipped")


def check_mutating_probe(evidence_dir: str | None) -> tuple[bool, str, bool]:
    """Check evidence/mutating-probe.v1.json (tempdoc 808 I1b).

    Returns (ok, reason, skipped). `skipped` is surfaced separately so main()
    can print the loud warning without the status flipping the exit code.
    """
    if not evidence_dir:
        return False, f"no --evidence-dir given; cannot check for evidence/{MUTATING_PROBE_FILENAME}", False

    path = os.path.join(evidence_dir, MUTATING_PROBE_FILENAME)
    if not os.path.isfile(path):
        return False, (
            f"{MUTATING_PROBE_FILENAME} not found in evidence dir {evidence_dir!r} -- "
            "collect-evidence.ps1 writes it on every run, so its absence means the capture "
            "harness never ran (or ran a pre-808 copy) and the mutating surface was never "
            "probed this round"
        ), False

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            data = json.load(fh)
    except OSError as exc:
        return False, f"{MUTATING_PROBE_FILENAME} could not be read: {exc}", False
    except json.JSONDecodeError as exc:
        return False, f"{MUTATING_PROBE_FILENAME} is not valid JSON: {exc}", False

    if not isinstance(data, dict):
        return False, (
            f"{MUTATING_PROBE_FILENAME} must contain a JSON object, got {type(data).__name__}"
        ), False

    status = data.get("status")
    detail = str(data.get("detail", "")).strip()
    if status not in MUTATING_PROBE_STATUS_VALUES:
        return False, (
            f"{MUTATING_PROBE_FILENAME} has status={status!r}, must be one of "
            f"{list(MUTATING_PROBE_STATUS_VALUES)!r}"
        ), False

    if status == "fail":
        return False, (
            f"{MUTATING_PROBE_FILENAME} reports status='fail' -- the product's mutating "
            "surface (search, ingest, chat) did not answer this round. Every GET rung can "
            "still be green while this is true; that combination IS the round-10 false-green "
            f"(finding F7). Detail: {detail or '(none given)'}"
        ), False

    if status == "skipped":
        return True, (
            f"{MUTATING_PROBE_FILENAME} reports status='skipped' (backend never reachable) -- "
            f"the mutating surface was NOT proven this round. Detail: {detail or '(none given)'}"
        ), True

    return True, f"{MUTATING_PROBE_FILENAME} reports status='pass'. Detail: {detail or '(none given)'}", False


# --------------------------------------------------------------------------
# Session self-analysis (tempdoc 808 I2).
#
# Round 12 wrote a session-level self-analysis nobody asked for -- what the
# harness/charter made hard, what was done off-charter and why, what the next
# round should do differently -- and it produced ~11 adopted harness fixes
# (tempdoc 734), the single highest-yield artifact of the campaign. Nothing
# collected it, so it happened once.
#
# Deliberately dumb, exactly like check_retrospective's byte floor and for the
# same stated reason: this cannot judge QUALITY, only reject an absent file and
# a placeholder stub. The value is that the artifact exists at all.
#
# Not folded into retrospective.md on purpose: the retrospective debriefs THE
# ROUND against its charter (SBTM), this debriefs THE SESSION against the
# harness. Merging them loses the second every time the first is long enough
# to feel done.
# --------------------------------------------------------------------------

SESSION_ANALYSIS_FILENAME = "session-analysis.md"

SESSION_ANALYSIS_MIN_BYTES = 400


def check_session_analysis(evidence_dir: str | None) -> tuple[bool, str]:
    """Check evidence/session-analysis.md is present and not a stub (808 I2).

    Content is UNGRADED beyond the byte floor -- see the section comment.
    """
    if not evidence_dir:
        return False, f"no --evidence-dir given; cannot check for evidence/{SESSION_ANALYSIS_FILENAME}"

    path = os.path.join(evidence_dir, SESSION_ANALYSIS_FILENAME)
    if not os.path.isfile(path):
        return False, f"{SESSION_ANALYSIS_FILENAME} not found in evidence dir {evidence_dir!r}"

    try:
        with open(path, "r", encoding="utf-8-sig") as fh:
            content = fh.read()
    except OSError as exc:
        return False, f"{SESSION_ANALYSIS_FILENAME} could not be read: {exc}"

    stripped = content.strip()
    if len(stripped) < SESSION_ANALYSIS_MIN_BYTES:
        return False, (
            f"{SESSION_ANALYSIS_FILENAME} is only {len(stripped)} non-whitespace-trimmed "
            f"byte(s) (minimum {SESSION_ANALYSIS_MIN_BYTES}) -- reads like an empty or "
            "placeholder stub, not a real session self-analysis"
        )

    return True, f"{SESSION_ANALYSIS_FILENAME} present ({len(stripped)} bytes; content ungraded)"


# --------------------------------------------------------------------------
# Evidence mtime timeline (tempdoc 750 Part B): REPORT-ONLY, no gate.
#
# Rounds capture evidence files (screenshots, api-*.json) whose file mtimes
# are the only timing signal that exists -- no other time accounting is
# recorded anywhere in the pipeline. This prints the mtime span and a
# per-30-minute-bucket file-count table as a mechanical cross-check a human
# reviewer can compare against the retrospective's TBS self-report (see
# check_retrospective's time-accounting gate above). It never affects the
# exit code -- it is evidence for a reader, not an assertion.
# --------------------------------------------------------------------------

TIMELINE_BUCKET_SECONDS = 30 * 60


def _format_timestamp(epoch_seconds: float) -> str:
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(epoch_seconds))


def _format_duration(seconds: float) -> str:
    total = int(seconds)
    hours, remainder = divmod(total, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h {minutes}m {secs}s"
    if minutes:
        return f"{minutes}m {secs}s"
    return f"{secs}s"


def emit_evidence_timeline(evidence_dir: str | None) -> None:
    """Print a report-only mtime timeline of the evidence directory.

    Reports first/last evidence file mtime, the total span between them, and
    a per-30-minute-bucket file-count table. Has NO effect on the exit code:
    file mtimes are the only timing signal this pipeline has, so this is a
    mechanical cross-check surfaced for a human reviewer, not a gate.
    """
    print("=" * 72)
    print("Evidence mtime timeline (report-only, no gate)")
    print("=" * 72)

    if not evidence_dir or not os.path.isdir(evidence_dir):
        print(f"No evidence directory to report on ({evidence_dir!r}).")
        return

    mtimes: list[float] = []
    for entry in os.listdir(evidence_dir):
        full = os.path.join(evidence_dir, entry)
        if os.path.isfile(full):
            try:
                mtimes.append(os.path.getmtime(full))
            except OSError:
                continue

    if not mtimes:
        print("No files found in evidence dir; nothing to report.")
        return

    mtimes.sort()
    first, last = mtimes[0], mtimes[-1]
    span_seconds = last - first
    print(f"First evidence mtime: {_format_timestamp(first)}")
    print(f"Last evidence mtime:  {_format_timestamp(last)}")
    print(f"Total span: {_format_duration(span_seconds)} ({len(mtimes)} file(s))")

    buckets: dict[int, int] = {}
    for t in mtimes:
        bucket = int((t - first) // TIMELINE_BUCKET_SECONDS)
        buckets[bucket] = buckets.get(bucket, 0) + 1

    print("-" * 72)
    print(f"{'Bucket start (30-min)':<28}{'Files':>8}")
    for bucket in range(max(buckets) + 1):
        bucket_start = first + bucket * TIMELINE_BUCKET_SECONDS
        print(f"{_format_timestamp(bucket_start):<28}{buckets.get(bucket, 0):>8}")
    print("=" * 72)


# --------------------------------------------------------------------------
# Data model
# --------------------------------------------------------------------------


@dataclass
class MustTouchItem:
    kind: str
    id: str
    tier: str
    validate_how: str
    routes: list[str] = field(default_factory=list)
    required_routes: list[str] = field(default_factory=list)
    evidence_token: str | None = None

    @staticmethod
    def from_dict(raw: dict) -> "MustTouchItem":
        return MustTouchItem(
            kind=raw.get("kind", ""),
            id=raw.get("id", ""),
            tier=raw.get("tier", ""),
            validate_how=raw.get("validateHow", ""),
            routes=list(raw.get("routes", []) or []),
            required_routes=list(raw.get("requiredRoutes", []) or []),
            evidence_token=raw.get("evidenceToken"),
        )


@dataclass
class CoverageResult:
    item: MustTouchItem
    covered: bool
    reason: str


# --------------------------------------------------------------------------
# Manifest loading
# --------------------------------------------------------------------------


def load_manifest(path: str) -> dict:
    """Load and return the coverage manifest. Caller handles I/O/JSON errors."""
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


# --------------------------------------------------------------------------
# Trace parsing -> exercised endpoint set
# --------------------------------------------------------------------------


def load_exercised_endpoints(path: str | None) -> tuple[set[tuple[str, str]], set[str]]:
    """Parse traces.ndjson and return (exercised_pairs, exercised_paths).

    734-followup fix (method-blind route matching, verified defect): the prior
    version returned a single path-only set, so `requiredRoutes: ["POST /mcp"]`
    was satisfiable by a DELETE-only (or any-method) trace on the same path.

    - `exercised_pairs`: {(METHOD, path)} built from every span that carries a
      non-empty `attrs["http.method"]`.
    - `exercised_paths`: every endpoint path seen regardless of method — the
      fallback set a *bare* (no-method-prefix) route still matches against, and
      also covers spans that lack an `http.method` attr entirely.

    For every line whose span `name` starts with "http." (or which carries
    an `attrs["http.method"]`), the endpoint path is
    `attrs.get("http.route")`, falling back to `attrs.get("http.target")`.
    Non-http spans are ignored. Malformed lines are skipped with a warning,
    not a crash. A missing file yields empty sets plus a warning.
    """
    exercised_pairs: set[tuple[str, str]] = set()
    exercised_paths: set[str] = set()

    if not path:
        print("WARNING: no --traces path given; exercised-endpoint set is empty.", file=sys.stderr)
        return exercised_pairs, exercised_paths

    if not os.path.isfile(path):
        print(f"WARNING: traces file not found: {path!r} (treating as empty).", file=sys.stderr)
        return exercised_pairs, exercised_paths

    with open(path, "r", encoding="utf-8") as fh:
        for line_no, raw_line in enumerate(fh, start=1):
            line = raw_line.strip()
            if not line:
                continue
            try:
                span = json.loads(line)
            except json.JSONDecodeError as exc:
                print(
                    f"WARNING: skipping malformed trace line {line_no}: {exc}",
                    file=sys.stderr,
                )
                continue

            if not isinstance(span, dict):
                print(
                    f"WARNING: skipping non-object trace line {line_no}.",
                    file=sys.stderr,
                )
                continue

            name = span.get("name", "")
            attrs = span.get("attrs", {}) or {}
            is_http_span = isinstance(name, str) and name.startswith("http.")
            has_http_method = isinstance(attrs, dict) and "http.method" in attrs

            if not (is_http_span or has_http_method):
                continue

            if not isinstance(attrs, dict):
                print(
                    f"WARNING: skipping trace line {line_no} with non-object attrs.",
                    file=sys.stderr,
                )
                continue

            endpoint = attrs.get("http.route") or attrs.get("http.target")
            if not endpoint:
                continue

            exercised_paths.add(endpoint)
            method = attrs.get("http.method")
            if isinstance(method, str) and method.strip():
                exercised_pairs.add((method.strip().upper(), endpoint))

    return exercised_pairs, exercised_paths


# --------------------------------------------------------------------------
# Evidence directory -> filename set
# --------------------------------------------------------------------------


# Minimum byte size for a screenshot to count as coverage evidence (defect
# F-729-2: check_surface/check_shape credited a screenshot by FILENAME alone,
# with no inspection of the image at all -- a blank, occluded, or plain wrong
# capture satisfied coverage silently). This is a cheap first filter, in the
# same deliberately-dumb spirit as check_retrospective's byte-count-plus-
# keyword check above: no image decoding, no pixel analysis, just a size
# floor a solid-colour/near-blank PNG can't clear.
#
# Calibrated against the round-4 evidence set
# (tmp/sandbox-evidence/round4-2026-07-15/, ~74 real captures): the one known-
# bad file, 02-installer-page2.png, is 8,554 bytes (a near-blank capture).
# The smallest genuine capture in that same set is 35,131 bytes -- more than
# 4x the known-bad file. 16,384 bytes (16 KiB) sits with clean margin on both
# sides (~1.9x the known-bad size, ~2.1x below the smallest real capture),
# rejecting the known-bad file without risking a legitimate capture.
MIN_SCREENSHOT_BYTES = 16384


def load_evidence_files(path: str | None) -> dict[str, int]:
    """Collect {lowercased evidence relative-path: size in bytes} in `path`,
    recursively.

    Keys are relative to `path` with forward-slash separators regardless of
    platform, so a top-level file's key is unchanged from before this walked
    subdirectories (e.g. "42-recovery-key.png"), and a nested file's key
    carries its subdirectory prefix (e.g. "raw-frames/seq-0001.png",
    "post-round/investigation-01.png") -- see BULK_FRAMES_DIRNAME and
    POST_ROUND_DIRNAME above for the two conventions that currently read
    that prefix.

    A missing directory yields an empty dict plus a warning.
    """
    files: dict[str, int] = {}

    if not path:
        print("WARNING: no --evidence-dir given; evidence set is empty.", file=sys.stderr)
        return files

    if not os.path.isdir(path):
        print(f"WARNING: evidence dir not found: {path!r} (treating as empty).", file=sys.stderr)
        return files

    for root, _dirs, filenames in os.walk(path):
        rel_root = os.path.relpath(root, path)
        for entry in filenames:
            full = os.path.join(root, entry)
            if rel_root == ".":
                rel = entry
            else:
                rel = f"{rel_root.replace(os.sep, '/')}/{entry}"
            files[rel.lower()] = os.path.getsize(full)

    return files


def is_bulk_frame(evidence_relpath: str) -> bool:
    """True if `evidence_relpath` (a load_evidence_files key: lowercased,
    forward-slash-separated, relative to the evidence dir) lives under the
    designated bulk-frames convention directory.

    Only the FIRST path component is checked -- BULK_FRAMES_DIRNAME must be a
    direct child of the evidence dir, matching the documented convention in
    sandbox-CLAUDE.md. A top-level file (no "/") is never a bulk frame.
    """
    parts = evidence_relpath.split("/", 1)
    return len(parts) > 1 and parts[0] == BULK_FRAMES_DIRNAME


def is_post_round_capture(evidence_relpath: str) -> bool:
    """True if `evidence_relpath` lives under the post-finalize investigation
    convention directory (POST_ROUND_DIRNAME) -- same direct-child-only rule
    as is_bulk_frame above, and the same exclusion treatment: present as
    evidence, never required in evidence-review.v1.json's 'examined' list,
    never credit-eligible for a mustTouch surface/shape token.
    """
    parts = evidence_relpath.split("/", 1)
    return len(parts) > 1 and parts[0] == POST_ROUND_DIRNAME


# --------------------------------------------------------------------------
# Coverage logic
# --------------------------------------------------------------------------


def parse_route(route: str) -> tuple[str | None, str]:
    """Split a route string into (METHOD, path).

    "POST /mcp" -> ("POST", "/mcp"). A route with no method prefix (no
    whitespace) returns (None, path) — a bare path, matched method-blind.
    """
    parts = route.split(" ", 1)
    if len(parts) == 2:
        return parts[0].strip().upper(), parts[1].strip()
    return None, route.strip()


def route_path(route: str) -> str:
    """Strip the leading "<METHOD> " from a route string, returning the path.

    "POST /mcp" -> "/mcp". A route with no method prefix (no whitespace) is
    returned unchanged.
    """
    _, path = parse_route(route)
    return path


def route_matches(
    route: str, exercised_pairs: set[tuple[str, str]], exercised_paths: set[str]
) -> bool:
    """734-followup fix: a route carrying a method prefix (every route in this
    checker's inputs does — both requiredRoutes and manifest-derived routes are
    "METHOD path" strings) must match the exact (method, path) pair; a bare path
    (no method prefix) keeps the old method-blind path-only match."""
    method, path = parse_route(route)
    if method is None:
        return path in exercised_paths
    return (method, path) in exercised_pairs


def shape_token(shape_id: str) -> str:
    """Derive the evidence-filename search token for a `shape` mustTouch item.

    `core.rag-ask` -> `rag-ask` (strip the leading "core." prefix if present).
    """
    prefix = "core."
    if shape_id.startswith(prefix):
        return shape_id[len(prefix):]
    return shape_id


def check_cohort(
    item: MustTouchItem, exercised_pairs: set[tuple[str, str]], exercised_paths: set[str]
) -> CoverageResult:
    # requiredRoutes (tempdoc 728 review, defect F2): when a cohort names the
    # specific product route(s) that MUST be hit (e.g. mcp -> "POST /mcp"),
    # any-route OR-semantics is a hole — GET /api/mcp/token would satisfy the
    # cohort without ever touching the endpoint the release exists for. So when
    # requiredRoutes is present, EVERY required route must be exercised (AND).
    # 734-followup fix (defect: method-blind matching): route_matches() now
    # requires the exact (METHOD, path) pair for a method-prefixed route, so
    # a DELETE-only (or any-other-method) trace can no longer satisfy a
    # "POST /mcp" requirement.
    if item.required_routes:
        missing = [
            r for r in item.required_routes if not route_matches(r, exercised_pairs, exercised_paths)
        ]
        if missing:
            return CoverageResult(
                item=item,
                covered=False,
                reason=f"required route(s) not exercised: {missing!r}",
            )
        return CoverageResult(
            item=item,
            covered=True,
            reason=f"all required route(s) {item.required_routes!r} exercised",
        )

    matched_route = None
    for route in item.routes:
        # Baseline: exact (method, path) match on the route template (both the
        # manifest route and the trace's http.method/http.route are Javalin
        # registration strings).
        if route_matches(route, exercised_pairs, exercised_paths):
            matched_route = route
            break

    if matched_route is not None:
        return CoverageResult(
            item=item,
            covered=True,
            reason=f"route {matched_route!r} exercised (matched path {route_path(matched_route)!r})",
        )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"none of routes {item.routes!r} found in exercised endpoints",
    )


def required_evidence_tokens(items: list[MustTouchItem]) -> dict[str, list[str]]:
    """Map each required evidence token -> the requirement id(s) it credits.

    Mirrors exactly what check_surface/check_shape match on: a surface item's
    evidenceToken, a shape item's shape_token(id). Both lowercased, since the
    filename match is lowercased. A token can legitimately map to more than
    one requirement id, hence the list.
    """
    tokens: dict[str, list[str]] = {}
    for item in items:
        if item.kind == "surface" and item.evidence_token:
            tokens.setdefault(item.evidence_token.lower(), []).append(f"surface:{item.id}")
        elif item.kind == "shape":
            tokens.setdefault(shape_token(item.id).lower(), []).append(f"shape:{item.id}")
    return tokens


def content_hash(path: str) -> str | None:
    """SHA-256 of a file's raw bytes, or None if unreadable.

    Deliberately dumb, matching this module's house style (check_retrospective's
    keyword matching, MIN_SCREENSHOT_BYTES' size floor): a plain content hash.
    No image decoding, no perceptual/near-duplicate similarity, no new
    dependencies. It answers exactly one question -- "are these the same
    bytes?" -- and nothing about what the pixels depict.
    """
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(65536), b""):
                digest.update(chunk)
    except OSError as exc:
        print(f"WARNING: could not hash evidence file {path!r}: {exc}", file=sys.stderr)
        return None
    return digest.hexdigest()


def find_duplicate_token_collisions(
    evidence_dir: str | None,
    screenshots: set[str],
    tokens: dict[str, list[str]],
) -> list[tuple[str, list[str], list[str]]]:
    """Find byte-identical screenshots that credit DIFFERENT required tokens.

    Returns [(hash, sorted filenames, sorted distinct tokens credited), ...].

    The signal (defect F-729-3): one capture cannot honestly evidence two
    different requirements. Two byte-identical files whose filenames credit
    two different tokens means a single image is the sole proof of two
    distinct things.

    Deliberately scoped to CROSS-token collisions. Byte-identical files that
    credit the SAME token (or no token at all) are NOT flagged: a round saving
    one capture under two names for one requirement isn't lying about coverage
    -- that token is credited once either way. Flagging those would cry wolf on
    benign duplicates, and a gate that cries wolf gets ignored, which costs
    more than the bug.

    Only screenshots that can actually credit coverage are considered -- the
    caller passes the post-size-floor eligible set, so an undersized image
    (which already credits nothing) can't manufacture a collision.
    """
    if not evidence_dir:
        return []

    by_hash: dict[str, list[str]] = {}
    for filename in sorted(screenshots):
        digest = content_hash(os.path.join(evidence_dir, filename))
        if digest is not None:
            by_hash.setdefault(digest, []).append(filename)

    collisions: list[tuple[str, list[str], list[str]]] = []
    for digest, files in by_hash.items():
        if len(files) < 2:
            continue
        credited = {token for token in tokens for f in files if token in f}
        if len(credited) > 1:
            collisions.append((digest, sorted(files), sorted(credited)))
    return sorted(collisions, key=lambda c: c[1][0])


def check_surface(item: MustTouchItem, evidence_filenames: set[str]) -> CoverageResult:
    token = (item.evidence_token or "").lower()
    if not token:
        return CoverageResult(
            item=item,
            covered=False,
            reason="surface item has no evidenceToken to match against",
        )

    for filename in evidence_filenames:
        if token in filename:
            return CoverageResult(
                item=item,
                covered=True,
                reason=f"evidenceToken {item.evidence_token!r} matched evidence file {filename!r}",
            )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"evidenceToken {item.evidence_token!r} not found in any evidence filename",
    )


def check_shape(item: MustTouchItem, evidence_filenames: set[str]) -> CoverageResult:
    token = shape_token(item.id).lower()
    for filename in evidence_filenames:
        if token in filename:
            return CoverageResult(
                item=item,
                covered=True,
                reason=f"shape token {token!r} matched evidence file {filename!r}",
            )
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"shape token {token!r} not found in any evidence filename",
    )


def evaluate_item(
    item: MustTouchItem,
    exercised_pairs: set[tuple[str, str]],
    exercised_paths: set[str],
    evidence_filenames: set[str],
) -> CoverageResult:
    if item.kind == "cohort":
        return check_cohort(item, exercised_pairs, exercised_paths)
    if item.kind == "surface":
        return check_surface(item, evidence_filenames)
    if item.kind == "shape":
        return check_shape(item, evidence_filenames)
    return CoverageResult(
        item=item,
        covered=False,
        reason=f"unrecognized mustTouch kind {item.kind!r}",
    )


def sort_key(item: MustTouchItem) -> tuple[str, str]:
    return (item.kind, item.id)


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------


def print_report(
    results: list[CoverageResult],
    covered_elsewhere: Iterable[dict],
    exempt: Iterable[dict],
) -> bool:
    """Print the deterministic coverage report. Returns True iff all covered."""
    covered_results = [r for r in results if r.covered]
    uncovered_results = [r for r in results if not r.covered]

    print("=" * 72)
    print("Sandbox coverage report (tier=sandbox mustTouch items)")
    print("=" * 72)

    for result in sorted(results, key=lambda r: sort_key(r.item)):
        status = "COVERED" if result.covered else "UNCOVERED"
        marker = "  " if result.covered else "**"
        print(f"{marker}[{status}]{marker} {result.item.kind}:{result.item.id}")
        print(f"    validateHow: {result.item.validate_how}")
        print(f"    reason:      {result.reason}")

    print("-" * 72)
    print(
        f"Sandbox mustTouch items: {len(results)} total, "
        f"{len(covered_results)} covered, {len(uncovered_results)} UNCOVERED"
    )

    covered_elsewhere = sorted(
        (str(entry.get("id", entry)) for entry in covered_elsewhere),
    )
    exempt_sorted = sorted(str(entry.get("id", entry)) for entry in exempt)

    print("-" * 72)
    print(f"coveredElsewhere (informational, never fails): {len(covered_elsewhere)}")
    for entry_id in covered_elsewhere:
        print(f"    - {entry_id}")

    print(f"exempt (informational, never fails): {len(exempt_sorted)}")
    for entry_id in exempt_sorted:
        print(f"    - {entry_id}")

    if uncovered_results:
        print("=" * 72)
        print("BLOCKING: the following sandbox-tier mustTouch items were not exercised:")
        for result in sorted(uncovered_results, key=lambda r: sort_key(r.item)):
            print(f"  - {result.item.kind}:{result.item.id} ({result.reason})")
        print("=" * 72)

    return len(uncovered_results) == 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "FINALIZE-time coverage assertion for a JustSearch Sandbox "
            "validation round (tempdoc 728)."
        )
    )
    parser.add_argument(
        "--manifest",
        required=True,
        help="Path to coverage-manifest.json produced by gen_coverage_brief.py",
    )
    parser.add_argument(
        "--traces",
        required=False,
        default=None,
        help="Path to the round's traces.ndjson (OpenTelemetry NDJSON)",
    )
    parser.add_argument(
        "--evidence-dir",
        required=False,
        default=None,
        help="Path to the round's evidence directory (screenshots etc.)",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    try:
        manifest = load_manifest(args.manifest)
    except OSError as exc:
        print(f"ERROR: could not read manifest {args.manifest!r}: {exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"ERROR: manifest {args.manifest!r} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    all_items = [MustTouchItem.from_dict(raw) for raw in manifest.get("mustTouch", []) or []]
    sandbox_items = [item for item in all_items if item.tier == "sandbox"]

    exercised_pairs, exercised_paths = load_exercised_endpoints(args.traces)
    evidence_files = load_evidence_files(args.evidence_dir)
    # F1: only screenshots count as UI-surface/shape evidence — the API-snapshot
    # JSON the harness also writes here must not satisfy a surface token.
    all_screenshots = {
        f for f in evidence_files if os.path.splitext(f)[1].lower() in IMAGE_EXTS
    }
    # Bulk-frame exclusion (A5 / tempdoc 806 W3 item 1): frames under the
    # BULK_FRAMES_DIRNAME convention remain present as evidence but are never
    # credit-eligible -- see the constant's docstring above. Excluded BEFORE
    # the size floor below so the printed undersized-count doesn't include
    # frames that were never going to be eligible either way.
    bulk_frames = {f for f in all_screenshots if is_bulk_frame(f)}
    all_screenshots = all_screenshots - bulk_frames
    if bulk_frames:
        print(
            f"INFO: {len(bulk_frames)} screenshot(s) under '{BULK_FRAMES_DIRNAME}/' are "
            "excluded from the credit-eligible set (bulk/periodic capture-driver "
            "convention) -- present as evidence, not required in evidence-review.v1.json's "
            "'examined' list, and cannot satisfy a mustTouch surface/shape token.",
            file=sys.stderr,
        )
    # Post-round investigation exclusion (round-15 retrospective finding 6,
    # tempdoc 817): same treatment as the bulk-frame exclusion above, for
    # screenshots/notes a same-share investigation session added AFTER this
    # round already finalized -- see POST_ROUND_DIRNAME's docstring.
    post_round_frames = {f for f in all_screenshots if is_post_round_capture(f)}
    all_screenshots = all_screenshots - post_round_frames
    if post_round_frames:
        print(
            f"INFO: {len(post_round_frames)} screenshot(s) under '{POST_ROUND_DIRNAME}/' are "
            "excluded from the credit-eligible set (post-finalize investigation convention) -- "
            "present as evidence, not required in evidence-review.v1.json's 'examined' list, "
            "and cannot satisfy a mustTouch surface/shape token.",
            file=sys.stderr,
        )
    # F-729-2: drop screenshots under the size floor before they can match a
    # surface/shape token — a blank, occluded, or near-blank capture must not
    # silently credit coverage. See MIN_SCREENSHOT_BYTES for calibration.
    undersized = {
        f for f in all_screenshots if evidence_files[f] < MIN_SCREENSHOT_BYTES
    }
    screenshots = all_screenshots - undersized
    if undersized:
        print(
            f"WARNING: {len(undersized)} screenshot(s) are under the "
            f"{MIN_SCREENSHOT_BYTES}-byte floor and will NOT count as coverage "
            "evidence (looks blank/occluded/placeholder):",
            file=sys.stderr,
        )
        for f in sorted(undersized):
            print(f"    - {f} ({evidence_files[f]} bytes)", file=sys.stderr)
    if evidence_files and not screenshots:
        print(
            "WARNING: evidence dir has files but no image/screenshot files above "
            f"the {MIN_SCREENSHOT_BYTES}-byte floor; UI surfaces are proven by "
            "screenshots (not API snapshots) and will read UNCOVERED.",
            file=sys.stderr,
        )

    results = [
        evaluate_item(item, exercised_pairs, exercised_paths, screenshots) for item in sandbox_items
    ]

    all_covered = print_report(
        results,
        covered_elsewhere=manifest.get("coveredElsewhere", []) or [],
        exempt=manifest.get("exempt", []) or [],
    )

    # F-729-3: a single capture cannot honestly evidence two different
    # requirements. Fails closed (unlike the size floor's warning) -- see
    # find_duplicate_token_collisions.
    collisions = find_duplicate_token_collisions(
        args.evidence_dir, screenshots, required_evidence_tokens(sandbox_items)
    )
    print("=" * 72)
    print("Duplicate-content check (F-729-3 -- one capture cannot evidence two requirements)")
    print("=" * 72)
    if not collisions:
        print("[OK] no byte-identical screenshots credit different required tokens")
    else:
        for digest, files, credited in collisions:
            print(f"**[COLLISION]** sha256:{digest[:12]} credits {len(credited)} different tokens: {credited}")
            for filename in files:
                print(f"    - {filename}")
        print("=" * 72)
        print(
            "BLOCKING: the byte-identical file group(s) above each credit two or more "
            "DIFFERENT required tokens. One image cannot be honest proof of two distinct "
            "requirements; at least one of them is credited by a capture of something else."
        )
        print(
            "Remedy: capture each requirement separately. If one screen genuinely does "
            "evidence both (e.g. a chat-window capture that also shows a cited answer), "
            "save it ONCE under a single filename containing both tokens -- that credits "
            "both honestly and makes the sharing visible to a reviewer, instead of hiding "
            "it behind two copies of the same bytes."
        )
        print("=" * 72)

    retrospective_ok, retrospective_reason = check_retrospective(args.evidence_dir)
    print("=" * 72)
    print("Round retrospective check (D1 -- process deliverable, not a mustTouch item)")
    print("=" * 72)
    print(f"[{'PRESENT' if retrospective_ok else 'MISSING/TRIVIAL'}] evidence/{RETROSPECTIVE_FILENAME}")
    print(f"    reason: {retrospective_reason}")
    if not retrospective_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{RETROSPECTIVE_FILENAME} is required and must be substantial.")
        print(
            "Every round must write it covering: what the harness/docs got WRONG or made "
            "impossible, what had to be worked around or built, what slowed the round down, "
            "and what would change. See scripts/sandbox/sandbox-CLAUDE.md 'Writing results' "
            "-> 'Retrospective' for the full ask."
        )
        print("=" * 72)

    findings_ok, findings_reason = check_findings(args.evidence_dir)
    print("=" * 72)
    print("Round findings check (823 §4 -- the defect report as its own artifact)")
    print("=" * 72)
    print(f"[{'PRESENT' if findings_ok else 'MISSING/TRIVIAL'}] evidence/{FINDINGS_FILENAME}")
    print(f"    reason: {findings_reason}")
    if not findings_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{FINDINGS_FILENAME} is required and must be substantial.")
        print(
            "Round 16 wrote five findings -- one blocking -- and no findings file: they were "
            "scattered across mustwatch-verdicts.v1.json, retrospective.md and "
            "session-analysis.md. Write each finding with a severity, what was observed (with an "
            "evidence pointer) and its regression home; a round that genuinely found nothing "
            "says so explicitly. See scripts/sandbox/sandbox-CLAUDE.md 'Writing results' -> "
            "'Findings'."
        )
        print("=" * 72)

    evidence_review_ok, evidence_review_reason = check_evidence_review(args.evidence_dir, screenshots)
    print("=" * 72)
    print("Evidence review check (735-followup -- a reader gate, filenames are claims not proof)")
    print("=" * 72)
    print(f"[{'PRESENT' if evidence_review_ok else 'BLOCKING'}] evidence/{EVIDENCE_REVIEW_FILENAME}")
    print(f"    reason: {evidence_review_reason}")
    if not evidence_review_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{EVIDENCE_REVIEW_FILENAME} is required and must be complete.")
        print(
            "Every credit-eligible screenshot must be opened by a reader and listed in "
            "'examined'; any reported 'mismatches' fails the round closed. See "
            "scripts/sandbox/sandbox-CLAUDE.md 'Writing results' -> 'Evidence review' and "
            "scripts/sandbox/evidence-review.schema.json for the required shape."
        )
        print("=" * 72)

    mustwatch_ok, mustwatch_reason, observed_fail_ids = check_mustwatch_verdicts(
        args.evidence_dir, manifest
    )
    print("=" * 72)
    print("mustWatch verdict record (808 I1a -- the claim of observation must be verifiable)")
    print("=" * 72)
    print(f"[{'PRESENT' if mustwatch_ok else 'BLOCKING'}] evidence/{MUSTWATCH_VERDICTS_FILENAME}")
    print(f"    reason: {mustwatch_reason}")
    if not mustwatch_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{MUSTWATCH_VERDICTS_FILENAME} is required and must be complete.")
        print(
            "Every mustWatch id in this round's coverage-manifest.json needs a verdict of "
            f"{list(MUSTWATCH_VERDICT_VALUES)!r}; 'unobservable' needs a note saying why. "
            "See scripts/sandbox/sandbox-CLAUDE.md 'Writing results' -> 'Must-watch verdicts'."
        )
        print("=" * 72)
    if observed_fail_ids:
        # Deliberately NOT part of the exit composition below (808 I1a): what a
        # failed watch MEANS is judgment, routed through the findings process.
        # It must be impossible to MISS, not impossible to pass.
        print("=" * 72)
        print(f"**OBSERVED-FAIL** on {len(observed_fail_ids)} mustWatch item(s): {sorted(observed_fail_ids)!r}")
        print(
            "This does NOT flip the exit code by itself -- severity is the round's and the "
            "owner's call -- but each one must be written up as a finding, not left in this "
            "file alone. A round that ends with an unexplained observed-fail is not finished."
        )
        print("=" * 72)

    mutating_ok, mutating_reason, mutating_skipped = check_mutating_probe(args.evidence_dir)
    print("=" * 72)
    print("Mutating-surface probe (808 I1b -- the round-10 false-green, now machine-visible)")
    print("=" * 72)
    print(f"[{'PRESENT' if mutating_ok else 'BLOCKING'}] evidence/{MUTATING_PROBE_FILENAME}")
    print(f"    reason: {mutating_reason}")
    if not mutating_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{MUTATING_PROBE_FILENAME} is required and must not report 'fail'.")
        print(
            "collect-evidence.ps1 writes this on every run. A missing file means the capture "
            "harness never ran; status='fail' means every GET rung above can be green while "
            "the product's whole mutating surface is dead (round-10 finding F7)."
        )
        print("=" * 72)
    elif mutating_skipped:
        print("=" * 72)
        print(
            "**WARNING** the mutating-surface probe was SKIPPED (backend never reachable) -- "
            "this round proved nothing about search/ingest/chat. Not failed here only because "
            "an unreachable backend already fails coverage above."
        )
        print("=" * 72)

    session_analysis_ok, session_analysis_reason = check_session_analysis(args.evidence_dir)
    print("=" * 72)
    print("Session self-analysis (808 I2 -- the harness's own highest-yield artifact)")
    print("=" * 72)
    print(f"[{'PRESENT' if session_analysis_ok else 'MISSING/TRIVIAL'}] evidence/{SESSION_ANALYSIS_FILENAME}")
    print(f"    reason: {session_analysis_reason}")
    if not session_analysis_ok:
        print("=" * 72)
        print(f"BLOCKING: evidence/{SESSION_ANALYSIS_FILENAME} is required and must be substantial.")
        print(
            "Write what the harness/charter/instructions made HARD, what was done off-charter "
            "and why, and what the next round should do differently. Content is not graded -- "
            "see scripts/sandbox/sandbox-CLAUDE.md 'Writing results' -> 'Session self-analysis'."
        )
        print("=" * 72)

    # Report-only (no gate): see emit_evidence_timeline's docstring. Runs
    # after every gate above and never affects the exit code below.
    emit_evidence_timeline(args.evidence_dir)

    return (
        0
        if (
            all_covered
            and retrospective_ok
            and findings_ok
            and evidence_review_ok
            and mustwatch_ok
            and mutating_ok
            and session_analysis_ok
            and not collisions
        )
        else 1
    )


if __name__ == "__main__":
    sys.exit(main())
