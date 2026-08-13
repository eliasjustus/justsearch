#!/usr/bin/env python3
"""Self-tests for check_coverage.py's D1 retrospective gate.

Covers the three states the finalize gate must distinguish: retrospective
absent, present-but-trivial (a stub), and present-and-substantial. Also
exercises the end-to-end main() wiring so a green mustTouch report doesn't
mask a missing retrospective (the exact bug D1 closes).

Run: python scripts/sandbox/test_check_coverage.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from typing import Iterable

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_coverage import (  # noqa: E402
    BULK_FRAMES_DIRNAME,
    EVIDENCE_REVIEW_FILENAME,
    FINDINGS_FILENAME,
    FINDINGS_MIN_BYTES,
    MIN_SCREENSHOT_BYTES,
    MUSTWATCH_VERDICTS_FILENAME,
    MUTATING_PROBE_FILENAME,
    POST_ROUND_DIRNAME,
    RETROSPECTIVE_FILENAME,
    RETROSPECTIVE_MIN_BYTES,
    SESSION_ANALYSIS_FILENAME,
    SESSION_ANALYSIS_MIN_BYTES,
    TIME_ACCOUNTING_REQUIRED_GROUPS,
    MustTouchItem,
    _time_accounting_section,
    check_evidence_review,
    check_findings,
    check_mustwatch_verdicts,
    check_mutating_probe,
    check_retrospective,
    check_session_analysis,
    emit_evidence_timeline,
    find_duplicate_token_collisions,
    is_bulk_frame,
    is_post_round_capture,
    main,
    required_evidence_tokens,
)

SUBSTANTIAL_RETROSPECTIVE = """\
## What the harness/docs got wrong or made impossible

The TYPED_CONFIRM procedure as documented could not be followed: the MCP
Inspector CLI's --tool-arg string-coerces every value, so it is impossible to
pass paths as an array. This is unfollowable as written.

## What we had to work around or build

We wrote our own minimal MCP client to drive the tools/call handshake with a
real array argument, since the documented Inspector CLI path could not work.

## What slowed us down

Discovering the --tool-arg limitation cost real time; it was not obvious
from the CLI's own help output and produced a confusing wrong-type error.

## What we would change

Recommend the harness ship a small driver that speaks JSON-RPC directly
instead of routing through a CLI that cannot express array arguments.

## Time accounting (TBS)

Setup (staging + sandbox boot) took about 10 minutes. Install AI's model
download and CUDA runtime install consumed the bulk of the session. Coverage
capture (screenshots, api snapshots) and the investigation of the
--tool-arg limitation split the remaining on-charter time roughly evenly,
and write-up of the retrospective and findings took the final block. All of
this was on-charter time against the round's charter; no opportunity time
was spent testing outside it.
"""


def _empty_evidence_review_json() -> str:
    """A valid, empty evidence-review.v1.json -- for fixtures with zero
    credit-eligible screenshots that only need the file to be PRESENT."""
    return json.dumps({"version": 1, "examined": [], "mismatches": [], "uncertain": []})


SUBSTANTIAL_SESSION_ANALYSIS = """\
## What the harness/charter made hard

The charter's must-watch list named the items but not where to reach them, so
each one cost a fresh round of discovery through the shell before it could be
observed at all. The coverage brief's reach pointers helped where they existed.

## What we did off-charter, and why

We chased an unrelated status-card staleness we noticed while waiting on the
model download, because it looked like the same class as a prior round's
finding. It was not, but it cost about twenty minutes and is written up.

## What the next round should do differently

Run the collector once before touching the UI, so there is a baseline snapshot
to diff the post-install ladder against instead of reasoning from one capture.
""" + ("padding " * 20)


def _mustwatch_verdicts_json(ids: Iterable[str] = ()) -> str:
    """A valid mustwatch-verdicts.v1.json covering exactly `ids`."""
    return json.dumps(
        {
            "schema": "mustwatch-verdicts.v1",
            "items": [
                {"id": item_id, "verdict": "observed-pass", "note": "seen"} for item_id in ids
            ],
        }
    )


def _mutating_probe_json(status: str = "pass") -> str:
    return json.dumps({"schema": "mutating-probe.v1", "status": status, "detail": "POST 200"})


SUBSTANTIAL_FINDINGS = """\
# Round findings

## F1 (HIGH, blocking) -- Install AI reports a missing component that is present

Observed: `/api/ai/install/status` reports `installedFully: false` with one
package permanently `failed`, while the Worker log shows the model loaded and
serving real inference calls. Evidence: `42-brain-install-failed-state.png`,
`api-install-status-5of7-failed.json`, worker log excerpt.
Severity: HIGH -- the product invites a repair loop that can never succeed.
Regression home: a unit/live-stack test on the install service's terminal
state, re-verified next round by TRIGGERING a package failure.

## F2 (MEDIUM) -- publisher string differs from the signing identity

Observed: the uninstall registry entry's Publisher value is the lowercase
bundle string, not the certificate CN. Evidence: screenshot of the Apps list.
Regression home: the installer-execution-level check family.
"""


def _write_round_process_artifacts(evidence: Path, mustwatch_ids: Iterable[str] = ()) -> None:
    """Plant the artifacts tempdoc 808 made mandatory (mustWatch verdicts,
    mutating-probe verdict, session self-analysis) plus the findings report
    tempdoc 823 §4 added.

    Every pre-808 green-path main() fixture predates these files, so each one
    needs them planted to keep testing what it was written to test. Kept as
    one helper so a future required artifact lands in exactly one place --
    and so the 808 bite tests below can omit exactly ONE of them and prove the
    failure comes from that omission, not from a generally lenient fixture.
    """
    (evidence / MUSTWATCH_VERDICTS_FILENAME).write_text(
        _mustwatch_verdicts_json(mustwatch_ids), encoding="utf-8"
    )
    (evidence / MUTATING_PROBE_FILENAME).write_text(_mutating_probe_json(), encoding="utf-8")
    (evidence / SESSION_ANALYSIS_FILENAME).write_text(
        SUBSTANTIAL_SESSION_ANALYSIS, encoding="utf-8"
    )
    (evidence / FINDINGS_FILENAME).write_text(SUBSTANTIAL_FINDINGS, encoding="utf-8")


class CheckRetrospectiveTests(unittest.TestCase):
    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_no_evidence_dir_blocks(self):
        ok, reason = check_retrospective(None)
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)

    def test_trivial_stub_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            stub_path = Path(tmp) / RETROSPECTIVE_FILENAME
            stub_path.write_text("Nothing to report.\n", encoding="utf-8")
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("non-whitespace-trimmed byte", reason)

    def test_long_but_missing_required_topic_blocks(self):
        # Long enough to clear the byte floor, but never mentions a fix/change.
        body = (
            "wrong impossible couldn't work around workaround built slow friction wasted "
        ) * 10
        self.assertGreaterEqual(len(body.strip()), RETROSPECTIVE_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("what would change", reason)

    def test_substantial_retrospective_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(
                SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8"
            )
            ok, reason = check_retrospective(tmp)
            self.assertTrue(ok, reason)
            self.assertIn("present", reason)

    def test_bom_prefixed_file_still_reads(self):
        # collect-evidence.ps1's PowerShell 5.1 writers historically produced
        # BOM'd UTF-8 (see Write-Utf8NoBom's own comment) -- guard against a
        # false MISSING/TRIVIAL verdict caused by a stray BOM in a real file.
        with tempfile.TemporaryDirectory() as tmp:
            data = ("﻿" + SUBSTANTIAL_RETROSPECTIVE).encode("utf-8")
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_bytes(data)
            ok, reason = check_retrospective(tmp)
            self.assertTrue(ok, reason)


# Body carrying all four required topic groups (so the pre-existing topic
# check never fires) but no time-accounting heading at all.
_RETROSPECTIVE_NO_TBS_SECTION = """\
## What the harness/docs got wrong or made impossible

This was impossible to follow as written; it just doesn't work.

## What we had to work around or build

We built our own workaround from scratch after the documented path failed.

## What slowed us down

This cost us real time and caused a lot of friction and wasted effort.

## What we would change

We recommend the harness change this for the next round.
""" + ("padding " * 30)


def _retrospective_with_tbs_missing_group(missing_label: str) -> str:
    """SUBSTANTIAL_RETROSPECTIVE's TBS section with every required group's
    keyword present EXCEPT the one named by `missing_label`."""
    lines = [
        "Setup took a while.",
        "Install AI ran the download.",
        "Coverage capture went smoothly.",
        "The investigation of the bug took time.",
        "Write-up of the findings was quick.",
        "This was on-charter time throughout.",
        "No opportunity time was spent elsewhere.",
    ]
    body = "\n".join(
        line for line, (label, _alts) in zip(lines, TIME_ACCOUNTING_REQUIRED_GROUPS) if label != missing_label
    )
    return (
        "## What the harness/docs got wrong or made impossible\n\n"
        "This was impossible to follow as written; it just doesn't work.\n\n"
        "## What we had to work around or build\n\n"
        "We built our own workaround from scratch after the documented path failed.\n\n"
        "## What slowed us down\n\n"
        "This cost us real time and caused a lot of friction and wasted effort.\n\n"
        "## What we would change\n\n"
        "We recommend the harness change this for the next round.\n\n"
        "## Time accounting (TBS)\n\n"
        f"{body}\n"
        + ("padding " * 30)
    )


class TimeAccountingSectionTests(unittest.TestCase):
    """TBS debrief gate (tempdoc 750 Part B): check_retrospective must ALSO
    require a '## ... time accounting' section with all 7 required keyword
    groups present."""

    def test_valid_tbs_section_extracted_correctly(self):
        section = _time_accounting_section(SUBSTANTIAL_RETROSPECTIVE)
        self.assertIsNotNone(section)
        lowered = section.lower()
        for label, alternatives in TIME_ACCOUNTING_REQUIRED_GROUPS:
            self.assertTrue(
                any(alt in lowered for alt in alternatives),
                f"expected a hit for group {label!r} in extracted TBS section",
            )

    def test_retrospective_with_valid_tbs_section_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(
                SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8"
            )
            ok, reason = check_retrospective(tmp)
            self.assertTrue(ok, reason)
            self.assertIn("TBS", reason)

    def test_missing_time_accounting_section_fails_naming_it(self):
        self.assertGreaterEqual(
            len(_RETROSPECTIVE_NO_TBS_SECTION.strip()), RETROSPECTIVE_MIN_BYTES
        )
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(
                _RETROSPECTIVE_NO_TBS_SECTION, encoding="utf-8"
            )
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("time-accounting section", reason)

    def test_tbs_section_missing_one_group_fails_naming_it(self):
        body = _retrospective_with_tbs_missing_group("opportunity")
        self.assertGreaterEqual(len(body.strip()), RETROSPECTIVE_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("opportunity", reason)

    def test_tbs_section_missing_setup_group_fails_naming_it(self):
        body = _retrospective_with_tbs_missing_group("setup")
        self.assertGreaterEqual(len(body.strip()), RETROSPECTIVE_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / RETROSPECTIVE_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_retrospective(tmp)
            self.assertFalse(ok)
            self.assertIn("setup", reason)


class EvidenceTimelineTests(unittest.TestCase):
    """Report-only mtime timeline (tempdoc 750 Part B): must never affect
    the exit code, whether called standalone or via main()."""

    def test_runs_without_error_on_populated_dir(self):
        import io
        from contextlib import redirect_stdout

        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "01-first.png").write_bytes(b"x")
            (d / "02-second.png").write_bytes(b"y")
            buf = io.StringIO()
            with redirect_stdout(buf):
                result = emit_evidence_timeline(tmp)
            self.assertIsNone(result)
            output = buf.getvalue()
            self.assertIn("Evidence mtime timeline (report-only, no gate)", output)
            self.assertIn("First evidence mtime:", output)
            self.assertIn("Bucket start", output)

    def test_runs_without_error_on_empty_dir(self):
        import io
        from contextlib import redirect_stdout

        with tempfile.TemporaryDirectory() as tmp:
            buf = io.StringIO()
            with redirect_stdout(buf):
                result = emit_evidence_timeline(tmp)
            self.assertIsNone(result)
            self.assertIn("nothing to report", buf.getvalue())

    def test_runs_without_error_on_missing_dir(self):
        import io
        from contextlib import redirect_stdout

        buf = io.StringIO()
        with redirect_stdout(buf):
            result = emit_evidence_timeline(None)
        self.assertIsNone(result)
        self.assertIn("No evidence directory", buf.getvalue())

    def test_timeline_does_not_affect_exit_code_via_main(self):
        # Same fixture shape as MainWiringTests' passing case -- the timeline
        # call happens after every gate but must not flip a clean rc=0.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            manifest = {"version": 1, "mustTouch": [], "coveredElsewhere": [], "exempt": []}
            manifest_path = tmp / "coverage-manifest.json"
            manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
            evidence_dir = tmp / "evidence"
            evidence_dir.mkdir()
            (evidence_dir / RETROSPECTIVE_FILENAME).write_text(
                SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8"
            )
            (evidence_dir / EVIDENCE_REVIEW_FILENAME).write_text(
                _empty_evidence_review_json(), encoding="utf-8"
            )
            _write_round_process_artifacts(evidence_dir)
            rc = main(["--manifest", str(manifest_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 0)


class MainWiringTests(unittest.TestCase):
    """A clean (zero mustTouch) coverage report must not mask a missing
    retrospective -- the exact silent-pass bug D1 closes."""

    def _empty_manifest_path(self, tmp: Path) -> Path:
        manifest = {"version": 1, "mustTouch": [], "coveredElsewhere": [], "exempt": []}
        manifest_path = tmp / "coverage-manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return manifest_path

    def test_clean_mustTouch_but_missing_retrospective_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            manifest_path = self._empty_manifest_path(tmp)
            evidence_dir = tmp / "evidence"
            evidence_dir.mkdir()
            rc = main(["--manifest", str(manifest_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 1)

    def test_clean_mustTouch_and_substantial_retrospective_passes(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            manifest_path = self._empty_manifest_path(tmp)
            evidence_dir = tmp / "evidence"
            evidence_dir.mkdir()
            (evidence_dir / RETROSPECTIVE_FILENAME).write_text(
                SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8"
            )
            (evidence_dir / EVIDENCE_REVIEW_FILENAME).write_text(
                _empty_evidence_review_json(), encoding="utf-8"
            )
            _write_round_process_artifacts(evidence_dir)
            rc = main(["--manifest", str(manifest_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 0)


class CheckFindingsTests(unittest.TestCase):
    """823 §4: round 16 filed five findings -- one blocking -- and left no
    standalone findings file; they were scattered across three artifacts
    written for other purposes. Same three states the retrospective gate
    distinguishes, plus the explicit no-findings escape valve."""

    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason = check_findings(tmp)
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_no_evidence_dir_blocks(self):
        ok, reason = check_findings(None)
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)

    def test_trivial_stub_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text("No findings.\n", encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertFalse(ok)
            self.assertIn("non-whitespace-trimmed byte", reason)

    def test_long_but_missing_required_topic_blocks(self):
        # Clears the byte floor and carries severity + evidence language, but
        # never says where any finding is going -- the gap that makes a
        # findings file un-actionable at convergence time.
        body = (
            "The Brain surface showed a HIGH severity disagreement with the API. "
            "Evidence: 42-brain-install-failed-state.png and the api- snapshot beside it. "
        ) * 5
        self.assertGreaterEqual(len(body.strip()), FINDINGS_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertFalse(ok)
            self.assertIn("regression home", reason)

    def test_substantial_findings_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(SUBSTANTIAL_FINDINGS, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertTrue(ok, reason)
            self.assertIn("all required topics found", reason)

    def test_explicit_no_findings_declaration_passes(self):
        # The escape valve: a genuinely clean round is not forced to invent
        # severities -- but it still has to clear the byte floor by saying
        # what it exercised.
        body = (
            "# Round findings\n\n"
            "No findings this round. Every must-touch surface in the brief was reached and "
            "behaved as the charter's healthy signature describes: the install completed with "
            "installedFully true on all seven packages, the escalation ladder disabled its "
            "AI rungs honestly while AI was offline, the encryption ceremony completed and "
            "survived a cold restart, and the warm-reinstall cycle preserved the index. "
            "Nothing was observed that a user would experience as wrong, misleading or scary.\n"
        )
        self.assertGreaterEqual(len(body.strip()), FINDINGS_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertTrue(ok, reason)
            self.assertIn("no-findings declaration", reason)

    def test_no_findings_declaration_still_needs_substance(self):
        # "No findings." alone is a stub, escape valve or not.
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(
                "no findings\n", encoding="utf-8"
            )
            ok, reason = check_findings(tmp)
            self.assertFalse(ok)
            self.assertIn("placeholder stub", reason)

    def test_mid_sentence_no_findings_phrase_is_still_topic_checked(self):
        # The escape valve is a ROUND-LEVEL declaration, not any occurrence of
        # the phrase. This report HAS findings and merely says one journey was
        # clean -- matching the declaration anywhere in the text accepted it as
        # a clean round and skipped the topic checks entirely (round-16 wave
        # review, claim-4). It must still be held to the required topics.
        body = (
            "The install journey produced two HIGH severity problems, while there were "
            "no findings in the search journey worth reporting. "
            "Evidence: 42-brain-install-failed-state.png and the api- snapshot beside it. "
        ) * 4
        self.assertGreaterEqual(len(body.strip()), FINDINGS_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertFalse(ok, reason)
            self.assertIn("regression home", reason)

    def test_line_initial_but_scoped_phrase_is_still_topic_checked(self):
        # Opening a line with the phrase is not enough either when the sentence
        # scopes it to one journey -- the declaration has to close its clause.
        body = (
            "No findings in the search journey, but the install journey produced two "
            "HIGH severity problems. Evidence: 42-brain-install-failed-state.png and "
            "the api- snapshot beside it. "
        ) * 4
        self.assertGreaterEqual(len(body.strip()), FINDINGS_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertFalse(ok, reason)
            self.assertIn("regression home", reason)

    def test_decorated_no_findings_declaration_passes(self):
        # Anchoring must not reject how a clean round actually writes it:
        # a bold/heading-decorated declaration on its own line still counts.
        body = (
            "# Round findings\n\n"
            "**No findings this round.**\n\n"
            "Every must-touch surface in the brief was reached and behaved as the charter's "
            "healthy signature describes: the install completed with installedFully true on "
            "all seven packages, the escalation ladder disabled its AI rungs honestly while "
            "AI was offline, the encryption ceremony completed and survived a cold restart, "
            "and the warm-reinstall cycle preserved the index.\n"
        )
        self.assertGreaterEqual(len(body.strip()), FINDINGS_MIN_BYTES)
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / FINDINGS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_findings(tmp)
            self.assertTrue(ok, reason)
            self.assertIn("no-findings declaration", reason)

    def test_bom_prefixed_file_still_reads(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = ("﻿" + SUBSTANTIAL_FINDINGS).encode("utf-8")
            (Path(tmp) / FINDINGS_FILENAME).write_bytes(data)
            ok, reason = check_findings(tmp)
            self.assertTrue(ok, reason)


class FindingsMainWiringTests(unittest.TestCase):
    """The gate must BITE through main(): an otherwise-clean round with every
    other required artifact present still fails when findings.md is the one
    thing missing (and passes once it is there)."""

    def _empty_manifest_path(self, tmp: Path) -> Path:
        manifest = {"version": 1, "mustTouch": [], "coveredElsewhere": [], "exempt": []}
        manifest_path = tmp / "coverage-manifest.json"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        return manifest_path

    def _evidence_dir(self, tmp: Path) -> Path:
        evidence_dir = tmp / "evidence"
        evidence_dir.mkdir()
        (evidence_dir / RETROSPECTIVE_FILENAME).write_text(
            SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8"
        )
        (evidence_dir / EVIDENCE_REVIEW_FILENAME).write_text(
            _empty_evidence_review_json(), encoding="utf-8"
        )
        _write_round_process_artifacts(evidence_dir)
        return evidence_dir

    def test_missing_findings_fails_an_otherwise_clean_round(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            manifest_path = self._empty_manifest_path(tmp)
            evidence_dir = self._evidence_dir(tmp)
            (evidence_dir / FINDINGS_FILENAME).unlink()
            rc = main(["--manifest", str(manifest_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 1)

    def test_present_findings_passes_the_same_round(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            manifest_path = self._empty_manifest_path(tmp)
            evidence_dir = self._evidence_dir(tmp)
            rc = main(["--manifest", str(manifest_path), "--evidence-dir", str(evidence_dir)])
            self.assertEqual(rc, 0)


class DuplicateContentTests(unittest.TestCase):
    """F-729-3: byte-identical screenshots crediting DIFFERENT required tokens
    are a false-PASS mechanism a size floor cannot see -- both files are large,
    'real' screenshots. Same-token duplicates stay benign on purpose: a gate
    that cries wolf on them gets ignored.
    """

    SURFACE_ITEMS = [
        MustTouchItem(
            kind="surface", id="core.security-surface", tier="sandbox",
            validate_how="security", evidence_token="security",
        ),
        MustTouchItem(
            kind="surface", id="core.memory-surface", tier="sandbox",
            validate_how="memory", evidence_token="memory",
        ),
        MustTouchItem(
            kind="surface", id="core.logs-surface", tier="sandbox",
            validate_how="logs", evidence_token="logs",
        ),
    ]

    def _tokens(self) -> dict:
        return required_evidence_tokens(self.SURFACE_ITEMS)

    def _write_image(self, path: Path, payload: bytes = b"\x89PNG-real-capture") -> None:
        # Must clear MIN_SCREENSHOT_BYTES so the file is credit-eligible; the
        # checker never decodes it, so arbitrary bytes are fine.
        path.write_bytes(payload + b"\x00" * MIN_SCREENSHOT_BYTES)

    def test_cross_token_duplicate_is_caught(self):
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            self._write_image(d / "01-security-panel.png")
            self._write_image(d / "02-memory-surface.png")  # identical bytes
            screenshots = {"01-security-panel.png", "02-memory-surface.png"}
            collisions = find_duplicate_token_collisions(tmp, screenshots, self._tokens())
            self.assertEqual(len(collisions), 1, collisions)
            _digest, files, credited = collisions[0]
            self.assertEqual(files, ["01-security-panel.png", "02-memory-surface.png"])
            self.assertEqual(credited, ["memory", "security"])

    def test_same_token_duplicate_is_benign(self):
        # The real round-4 shape: 43-f3-logs-during-cuda-download.png and
        # 44-f3-logs-all-levels-during-cuda.png are byte-identical but BOTH
        # credit only `logs` -- one requirement, credited once either way.
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            self._write_image(d / "43-f3-logs-during-cuda-download.png")
            self._write_image(d / "44-f3-logs-all-levels-during-cuda.png")
            screenshots = {
                "43-f3-logs-during-cuda-download.png",
                "44-f3-logs-all-levels-during-cuda.png",
            }
            self.assertEqual(find_duplicate_token_collisions(tmp, screenshots, self._tokens()), [])

    def test_duplicate_where_one_file_credits_nothing_is_benign(self):
        # Round-4's 06-ai-install-started.png / 06-brain-surface-simple.png:
        # identical, but only one credits a token -- one requirement credited.
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            self._write_image(d / "06-ai-install-started.png")  # credits nothing
            self._write_image(d / "06-memory-surface.png")
            screenshots = {"06-ai-install-started.png", "06-memory-surface.png"}
            self.assertEqual(find_duplicate_token_collisions(tmp, screenshots, self._tokens()), [])

    def test_distinct_content_crediting_different_tokens_is_fine(self):
        # The normal, honest case: two real, different captures.
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            self._write_image(d / "01-security-panel.png", payload=b"\x89PNG-security")
            self._write_image(d / "02-memory-surface.png", payload=b"\x89PNG-memory")
            screenshots = {"01-security-panel.png", "02-memory-surface.png"}
            self.assertEqual(find_duplicate_token_collisions(tmp, screenshots, self._tokens()), [])

    def test_one_file_crediting_two_tokens_is_not_a_collision(self):
        # The documented remedy: ONE file naming both tokens credits both
        # honestly and visibly. A single file can never be a duplicate group.
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            self._write_image(d / "01-security-and-memory-overview.png")
            screenshots = {"01-security-and-memory-overview.png"}
            self.assertEqual(find_duplicate_token_collisions(tmp, screenshots, self._tokens()), [])


class DuplicateContentMainWiringTests(unittest.TestCase):
    """A collision must flip the round's verdict on its own -- not merely warn."""

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [
                {
                    "kind": "surface", "id": "core.security-surface", "tier": "sandbox",
                    "validateHow": "security", "evidenceToken": "security",
                },
                {
                    "kind": "surface", "id": "core.memory-surface", "tier": "sandbox",
                    "validateHow": "memory", "evidenceToken": "memory",
                },
            ],
            "coveredElsewhere": [],
            "exempt": [],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _evidence_dir(self, tmp: Path, identical: bool) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        body = b"\x00" * MIN_SCREENSHOT_BYTES
        (evidence / "01-security-panel.png").write_bytes(b"\x89PNG-a" + body)
        (evidence / "02-memory-surface.png").write_bytes(
            (b"\x89PNG-a" if identical else b"\x89PNG-b") + body
        )
        review = {
            "version": 1,
            "examined": ["01-security-panel.png", "02-memory-surface.png"],
            "mismatches": [],
            "uncertain": [],
        }
        (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
        _write_round_process_artifacts(evidence)
        return evidence

    def test_fully_covered_round_still_fails_on_cross_token_duplicate(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(self._evidence_dir(tmp, identical=True)),
            ])
            self.assertEqual(rc, 1)

    def test_control_same_round_passes_when_captures_are_distinct(self):
        # Precision guard: proves the test above fails for the COLLISION, not
        # because coverage/retrospective happened to be unsatisfied.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(self._evidence_dir(tmp, identical=False)),
            ])
            self.assertEqual(rc, 0)


class CheckEvidenceReviewTests(unittest.TestCase):
    """Unit-level tests for check_evidence_review() (735-followup): presence,
    shape validation, the missing-examined coverage assertion, and the
    mismatch/uncertain distinction."""

    def test_no_evidence_dir_blocks(self):
        ok, reason = check_evidence_review(None, set())
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)

    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason = check_evidence_review(tmp, set())
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_malformed_json_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text("{not valid json", encoding="utf-8")
            ok, reason = check_evidence_review(tmp, set())
            self.assertFalse(ok)
            self.assertIn("not valid JSON", reason)

    def test_non_object_json_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text("[1, 2, 3]", encoding="utf-8")
            ok, reason = check_evidence_review(tmp, set())
            self.assertFalse(ok)
            self.assertIn("JSON object", reason)

    def test_examined_wrong_type_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = {"version": 1, "examined": "01.png", "mismatches": [], "uncertain": []}
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            ok, reason = check_evidence_review(tmp, set())
            self.assertFalse(ok)
            self.assertIn("'examined' must be a list", reason)

    def test_missing_examined_entry_blocks(self):
        # The load-bearing coverage assertion: a screenshot present in the
        # evidence dir but absent from 'examined' must fail closed.
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {"01-security-panel.png", "02-memory-surface.png"}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertFalse(ok)
            self.assertIn("02-memory-surface.png", reason)

    def test_partial_review_budget_exhausted_mid_set_blocks(self):
        # Reproduces the exact measured failure mode: a reader that examined
        # only 2 of 10 present screenshots and reported no mismatches on the
        # 2 it saw. Must NOT read as a clean pass on the other 8.
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["01.png", "02.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {f"{i:02d}.png" for i in range(1, 11)}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertFalse(ok)
            self.assertIn("credit-eligible", reason)

    def test_case_insensitive_examined_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["01-Security-Panel.PNG"],
                "mismatches": [],
                "uncertain": [],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {"01-security-panel.png"}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertTrue(ok, reason)

    def test_all_examined_no_mismatches_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["01-security-panel.png", "02-memory-surface.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {"01-security-panel.png", "02-memory-surface.png"}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertTrue(ok, reason)

    def test_mismatch_blocks_even_when_all_examined(self):
        # F-735: a review that finds a lie must not pass anyway.
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["07-logs-surface.png"],
                "mismatches": [
                    {
                        "file": "07-logs-surface.png",
                        "claims": "core.logs-surface",
                        "shows": "the command palette overlay",
                    }
                ],
                "uncertain": [],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {"07-logs-surface.png"}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertFalse(ok)
            self.assertIn("mismatch", reason.lower())
            self.assertIn("command palette overlay", reason)

    def test_uncertain_is_reported_but_non_blocking(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = {
                "version": 1,
                "examined": ["02-memory-surface.png"],
                "mismatches": [],
                "uncertain": [
                    {"file": "02-memory-surface.png", "reason": "partially occluded by a toast"}
                ],
            }
            (Path(tmp) / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(data), encoding="utf-8")
            screenshots = {"02-memory-surface.png"}
            ok, reason = check_evidence_review(tmp, screenshots)
            self.assertTrue(ok, reason)
            self.assertIn("uncertain", reason.lower())
            self.assertIn("partially occluded by a toast", reason)


class EvidenceReviewMainWiringTests(unittest.TestCase):
    """End-to-end proof (via main()) that a TRUNCATED review -- one that
    examined only SOME of the present, credit-eligible screenshots -- fails
    the round closed even though mustTouch coverage, the retrospective, and
    the duplicate-content check are all otherwise clean. This is the exact
    scenario the task exists to close: a reader that exhausted its budget
    mid-set and reported no mismatches on the screenshots it never opened
    must not be indistinguishable from a clean pass.

    Paired with a positive control on the identical evidence set (only the
    review's completeness differs), following the
    test_control_same_round_passes_when_captures_are_distinct pattern above:
    the control isolates the truncated review as the cause of the failure,
    not incidental unsatisfied coverage/retrospective/collision state.
    """

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [
                {
                    "kind": "surface", "id": "core.security-surface", "tier": "sandbox",
                    "validateHow": "security", "evidenceToken": "security",
                },
                {
                    "kind": "surface", "id": "core.memory-surface", "tier": "sandbox",
                    "validateHow": "memory", "evidenceToken": "memory",
                },
            ],
            "coveredElsewhere": [],
            "exempt": [],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _write_common_evidence(self, tmp: Path) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        body = b"\x00" * MIN_SCREENSHOT_BYTES
        (evidence / "01-security-panel.png").write_bytes(b"\x89PNG-security" + body)
        (evidence / "02-memory-surface.png").write_bytes(b"\x89PNG-memory" + body)
        _write_round_process_artifacts(evidence)
        return evidence

    def test_truncated_review_fails_closed_despite_clean_coverage_and_retrospective(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._write_common_evidence(tmp)
            # Reader opened only ONE of the two present, credit-eligible
            # screenshots and reported no mismatches on it -- it never looked
            # at the second.
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_control_complete_review_of_same_evidence_passes(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._write_common_evidence(tmp)
            review = {
                "version": 1,
                "examined": ["01-security-panel.png", "02-memory-surface.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)


class SizeFloorBiteTests(unittest.TestCase):
    """No existing test asserted that MIN_SCREENSHOT_BYTES actually REJECTS
    an undersized image -- every prior fixture already wrote images clearing
    the floor. Proves the bite both ways, isolating the floor as the cause
    (test_check_coverage.py's collision-test pairing pattern): an undersized
    capture fails to credit coverage, and the SAME capture padded to clear
    the floor credits it.
    """

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [
                {
                    "kind": "surface", "id": "core.security-surface", "tier": "sandbox",
                    "validateHow": "security", "evidenceToken": "security",
                },
            ],
            "coveredElsewhere": [],
            "exempt": [],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _evidence_dir(self, tmp: Path, payload: bytes) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        (evidence / "01-security-panel.png").write_bytes(payload)
        review = {
            "version": 1,
            "examined": ["01-security-panel.png"],
            "mismatches": [],
            "uncertain": [],
        }
        (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
        _write_round_process_artifacts(evidence)
        return evidence

    def test_undersized_screenshot_does_not_credit_coverage(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            undersized = b"\x89PNG-blank" + b"\x00" * (MIN_SCREENSHOT_BYTES - 100)
            self.assertLess(len(undersized), MIN_SCREENSHOT_BYTES)
            evidence = self._evidence_dir(tmp, undersized)
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_control_same_capture_padded_above_floor_credits_coverage(self):
        # Precision guard: identical filename and content prefix -- only the
        # SIZE changes -- proving the floor, not something else, caused the
        # failure above.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            sized = b"\x89PNG-blank" + b"\x00" * MIN_SCREENSHOT_BYTES
            self.assertGreaterEqual(len(sized), MIN_SCREENSHOT_BYTES)
            evidence = self._evidence_dir(tmp, sized)
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)


class BulkFrameExclusionTests(unittest.TestCase):
    """A5 / tempdoc 806 W3 item 1: a periodic capture driver's bulk frames
    (round 12: ~947 near-identical images from a ~1.5s-interval installer
    watcher) must not (a) require individual reader review in
    evidence-review.v1.json's 'examined' list, or (b) silently satisfy a
    mustTouch surface/shape token by filename alone. Proves both directions
    via main() end-to-end, isolating BULK_FRAMES_DIRNAME as the cause with a
    same-filename top-level control (SizeFloorBiteTests' pairing pattern) --
    without the bulk-dir convention the exact same fixture must fail closed.
    """

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [
                {
                    "kind": "surface", "id": "core.security-surface", "tier": "sandbox",
                    "validateHow": "security", "evidenceToken": "security",
                },
            ],
            "coveredElsewhere": [],
            "exempt": [],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _image_bytes(self) -> bytes:
        return b"\x89PNG-real-capture" + b"\x00" * MIN_SCREENSHOT_BYTES

    def _base_evidence_dir(self, tmp: Path) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        _write_round_process_artifacts(evidence)
        return evidence

    def test_bulk_frames_excluded_from_required_examined_list(self):
        """Bulk frames need not be opened/listed by the reader -- only the
        genuine top-level capture does -- and the round still passes."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            bulk_dir = evidence / BULK_FRAMES_DIRNAME
            bulk_dir.mkdir()
            (bulk_dir / "seq-0001.png").write_bytes(self._image_bytes())
            (bulk_dir / "seq-0002.png").write_bytes(self._image_bytes())
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],  # bulk frames deliberately absent
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)

    def test_control_same_frames_at_top_level_must_be_examined(self):
        """Precision guard: WITHOUT the bulk-dir convention (identical files,
        same names, top level), omitting them from 'examined' fails closed --
        proving the pass above is because of the bulk-dir exclusion, not
        merely because the fixture is otherwise lenient."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            (evidence / "seq-0001.png").write_bytes(self._image_bytes())
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],  # seq-0001.png NOT listed
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_bulk_frame_cannot_satisfy_mustTouch_token(self):
        """A mustTouch token whose ONLY matching filename lives under the
        bulk-frames directory must remain UNCOVERED -- a bulk driver cannot
        accidentally (or deliberately) manufacture coverage credit.

        Isolation: the bulk file's relpath is listed in 'examined' anyway
        (harmless whether or not it's required) so a failure here can only
        come from the coverage gate, not incidentally from the evidence-
        review gate also failing for the same underlying reason -- otherwise
        a broken exclusion could still yield rc=1 for the WRONG reason and
        this test would not actually bite.
        """
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            bulk_dir = evidence / BULK_FRAMES_DIRNAME
            bulk_dir.mkdir()
            (bulk_dir / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": [f"{BULK_FRAMES_DIRNAME}/01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_control_same_filename_at_top_level_satisfies_the_token(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)

    def test_is_bulk_frame_helper(self):
        self.assertTrue(is_bulk_frame(f"{BULK_FRAMES_DIRNAME}/seq-0001.png"))
        self.assertFalse(is_bulk_frame("seq-0001.png"))
        self.assertFalse(is_bulk_frame("findings/f1.md"))
        # Only a DIRECT child of the evidence dir counts -- a nested
        # coincidental name deeper in the tree is not the convention.
        self.assertFalse(is_bulk_frame(f"other/{BULK_FRAMES_DIRNAME}/seq-0001.png"))


class PostRoundExclusionTests(unittest.TestCase):
    """Round-15 retrospective finding 6 (tempdoc 817): a post-finalize
    investigation session's screenshots must not (a) require individual
    reader review in evidence-review.v1.json's 'examined' list, or (b)
    silently satisfy a mustTouch surface/shape token by filename alone --
    exactly parallel to BulkFrameExclusionTests above, for POST_ROUND_DIRNAME
    instead of BULK_FRAMES_DIRNAME. Round 15's actual failure: a post-finalize
    investigation added ~52 screenshots into the SAME evidence dir an
    already-complete, already-correct review had finalized against, and
    re-running check_coverage.py then failed that finalized review as
    incomplete.
    """

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [
                {
                    "kind": "surface", "id": "core.security-surface", "tier": "sandbox",
                    "validateHow": "security", "evidenceToken": "security",
                },
            ],
            "coveredElsewhere": [],
            "exempt": [],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _image_bytes(self) -> bytes:
        return b"\x89PNG-real-capture" + b"\x00" * MIN_SCREENSHOT_BYTES

    def _base_evidence_dir(self, tmp: Path) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        _write_round_process_artifacts(evidence)
        return evidence

    def test_post_round_frames_excluded_from_required_examined_list(self):
        """Screenshots added by a post-finalize investigation need not be
        opened/listed by the reader -- only the genuine top-level, in-round
        capture does -- and the round still passes."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            post_round_dir = evidence / POST_ROUND_DIRNAME
            post_round_dir.mkdir()
            (post_round_dir / "investigation-01.png").write_bytes(self._image_bytes())
            (post_round_dir / "investigation-02.png").write_bytes(self._image_bytes())
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],  # post-round frames deliberately absent
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)

    def test_control_same_frames_at_top_level_must_be_examined(self):
        """Precision guard: WITHOUT the post-round-dir convention (identical
        files, same names, top level), omitting them from 'examined' fails
        closed -- proving the pass above is because of the post-round
        exclusion, not merely because the fixture is otherwise lenient."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            (evidence / "investigation-01.png").write_bytes(self._image_bytes())
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],  # investigation-01.png NOT listed
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_post_round_frame_cannot_satisfy_mustTouch_token(self):
        """A mustTouch token whose ONLY matching filename lives under
        post-round/ must remain UNCOVERED -- a post-finalize investigation
        cannot retroactively manufacture coverage credit for the already-
        finalized round.

        Isolation: the file's relpath is listed in 'examined' anyway
        (harmless whether or not it's required) so a failure here can only
        come from the coverage gate, not incidentally from the evidence-
        review gate also failing for the same underlying reason.
        """
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            post_round_dir = evidence / POST_ROUND_DIRNAME
            post_round_dir.mkdir()
            (post_round_dir / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": [f"{POST_ROUND_DIRNAME}/01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 1)

    def test_control_same_filename_at_top_level_satisfies_the_token(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._base_evidence_dir(tmp)
            (evidence / "01-security-panel.png").write_bytes(self._image_bytes())
            review = {
                "version": 1,
                "examined": ["01-security-panel.png"],
                "mismatches": [],
                "uncertain": [],
            }
            (evidence / EVIDENCE_REVIEW_FILENAME).write_text(json.dumps(review), encoding="utf-8")
            rc = main([
                "--manifest", str(self._manifest_path(tmp)),
                "--evidence-dir", str(evidence),
            ])
            self.assertEqual(rc, 0)

    def test_is_post_round_capture_helper(self):
        self.assertTrue(is_post_round_capture(f"{POST_ROUND_DIRNAME}/investigation-01.png"))
        self.assertFalse(is_post_round_capture("investigation-01.png"))
        self.assertFalse(is_post_round_capture("findings/f1.md"))
        # Only a DIRECT child of the evidence dir counts -- a nested
        # coincidental name deeper in the tree is not the convention.
        self.assertFalse(is_post_round_capture(f"other/{POST_ROUND_DIRNAME}/investigation-01.png"))
        # Distinct from BULK_FRAMES_DIRNAME -- the two conventions don't
        # cross-satisfy each other's exclusion.
        self.assertFalse(is_post_round_capture(f"{BULK_FRAMES_DIRNAME}/seq-0001.png"))


# --------------------------------------------------------------------------
# tempdoc 808 bite proof. Wiring alone is not evidence (798 D2d): every new
# check ships with tests proving it catches its known-bad inputs, plus a
# green-path control on the SAME fixture so a failure is attributable to the
# one thing that was made bad.
# --------------------------------------------------------------------------


class CheckMustWatchVerdictsTests(unittest.TestCase):
    """I1a unit-level: presence, coverage of the manifest's mode-included ids,
    the verdict enum, the unobservable-needs-a-note rule, and the deliberate
    NON-failure of observed-fail."""

    MANIFEST = {
        "version": 1,
        "mustTouch": [],
        "coveredElsewhere": [],
        "exempt": [],
        "mustWatch": [
            {"id": "ui-api-truthfulness-under-load", "reason": "r", "observability": "sandbox"},
            {"id": "install-trust-prompts", "reason": "r", "observability": "blocked-by-posture"},
        ],
    }

    def _write(self, tmp: str, payload) -> None:
        (Path(tmp) / MUSTWATCH_VERDICTS_FILENAME).write_text(
            payload if isinstance(payload, str) else json.dumps(payload), encoding="utf-8"
        )

    def test_no_evidence_dir_blocks(self):
        ok, reason, fails = check_mustwatch_verdicts(None, self.MANIFEST)
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)
        self.assertEqual(fails, [])

    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_malformed_json_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "{not valid json")
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("not valid JSON", reason)

    def test_items_wrong_type_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {"schema": "mustwatch-verdicts.v1", "items": "nope"})
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("'items' must be a list", reason)

    def test_missing_one_mode_included_id_blocks(self):
        # THE load-bearing assertion: a verdict set that silently drops one of
        # the round's must-watch items must fail closed, exactly like the
        # evidence review's missing-examined rule.
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mustwatch-verdicts.v1",
                "items": [{"id": "ui-api-truthfulness-under-load", "verdict": "observed-pass"}],
            })
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("install-trust-prompts", reason)

    def test_bad_verdict_enum_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mustwatch-verdicts.v1",
                "items": [
                    {"id": "ui-api-truthfulness-under-load", "verdict": "ok"},
                    {"id": "install-trust-prompts", "verdict": "observed-pass"},
                ],
            })
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("verdict='ok'", reason.replace('"', "'"))

    def test_unobservable_with_empty_note_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mustwatch-verdicts.v1",
                "items": [
                    {"id": "ui-api-truthfulness-under-load", "verdict": "observed-pass"},
                    {"id": "install-trust-prompts", "verdict": "unobservable", "note": "   "},
                ],
            })
            ok, reason, _ = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertFalse(ok)
            self.assertIn("install-trust-prompts", reason)
            self.assertIn("note", reason)

    def test_unobservable_with_a_real_note_passes(self):
        # Precision guard for the test above: only the note changes.
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mustwatch-verdicts.v1",
                "items": [
                    {"id": "ui-api-truthfulness-under-load", "verdict": "observed-pass"},
                    {
                        "id": "install-trust-prompts",
                        "verdict": "unobservable",
                        "note": "SAC force-disabled at boot and the installer arrives by folder mount, so no MOTW.",
                    },
                ],
            })
            ok, reason, fails = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertTrue(ok, reason)
            self.assertEqual(fails, [])

    def test_observed_fail_passes_the_gate_but_is_reported(self):
        # The deliberate design point (808 I1a): recording is graded, the
        # OUTCOME is judgment. An observed-fail must be surfaced, not swallowed
        # -- and must not flip ok, or severity would be decided by a checker.
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mustwatch-verdicts.v1",
                "items": [
                    {"id": "ui-api-truthfulness-under-load", "verdict": "observed-fail",
                     "note": "shell showed Reconnecting while /api/health was READY"},
                    {"id": "install-trust-prompts", "verdict": "observed-pass"},
                ],
            })
            ok, reason, fails = check_mustwatch_verdicts(tmp, self.MANIFEST)
            self.assertTrue(ok, reason)
            self.assertEqual(fails, ["ui-api-truthfulness-under-load"])

    def test_manifest_without_mustwatch_key_requires_the_file_but_no_ids(self):
        # A pre-750 manifest has no 'mustWatch' key at all. The file is still
        # required (that is the point of the gate), but nothing is missing.
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {"schema": "mustwatch-verdicts.v1", "items": []})
            ok, reason, _ = check_mustwatch_verdicts(tmp, {"version": 1, "mustTouch": []})
            self.assertTrue(ok, reason)


class CheckMutatingProbeTests(unittest.TestCase):
    """I1b unit-level: fail-closed on missing/malformed/`fail`; `skipped`
    passes but is flagged for the loud warning."""

    def _write(self, tmp: str, payload) -> None:
        (Path(tmp) / MUTATING_PROBE_FILENAME).write_text(
            payload if isinstance(payload, str) else json.dumps(payload), encoding="utf-8"
        )

    def test_no_evidence_dir_blocks(self):
        ok, reason, skipped = check_mutating_probe(None)
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)
        self.assertFalse(skipped)

    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason, _ = check_mutating_probe(tmp)
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_malformed_json_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, "{nope")
            ok, reason, _ = check_mutating_probe(tmp)
            self.assertFalse(ok)
            self.assertIn("not valid JSON", reason)

    def test_bad_status_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {"schema": "mutating-probe.v1", "status": "green", "detail": "d"})
            ok, reason, _ = check_mutating_probe(tmp)
            self.assertFalse(ok)
            self.assertIn("status='green'", reason.replace('"', "'"))

    def test_status_fail_blocks_and_quotes_the_detail(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mutating-probe.v1",
                "status": "fail",
                "detail": "FAIL: POST /api/knowledge/search returned 401 UNAUTHORIZED",
            })
            ok, reason, skipped = check_mutating_probe(tmp)
            self.assertFalse(ok)
            self.assertIn("401", reason)
            self.assertFalse(skipped)

    def test_status_skipped_passes_but_flags(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, {
                "schema": "mutating-probe.v1",
                "status": "skipped",
                "detail": "Backend API port could not be determined.",
            })
            ok, reason, skipped = check_mutating_probe(tmp)
            self.assertTrue(ok, reason)
            self.assertTrue(skipped)

    def test_status_pass_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            self._write(tmp, _mutating_probe_json("pass"))
            ok, reason, skipped = check_mutating_probe(tmp)
            self.assertTrue(ok, reason)
            self.assertFalse(skipped)


class CheckSessionAnalysisTests(unittest.TestCase):
    """I2 unit-level: presence + byte floor only. Content is UNGRADED, so the
    'substantial' case here deliberately proves no keyword/topic requirement
    sneaked in -- a check that quietly grew a topic list would stop being the
    trivially-satisfiable gate the design argued for."""

    def test_no_evidence_dir_blocks(self):
        ok, reason = check_session_analysis(None)
        self.assertFalse(ok)
        self.assertIn("no --evidence-dir", reason)

    def test_absent_file_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            ok, reason = check_session_analysis(tmp)
            self.assertFalse(ok)
            self.assertIn("not found", reason)

    def test_undersized_stub_blocks(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / SESSION_ANALYSIS_FILENAME).write_text(
                "Nothing to report.\n", encoding="utf-8"
            )
            ok, reason = check_session_analysis(tmp)
            self.assertFalse(ok)
            self.assertIn("non-whitespace-trimmed", reason)

    def test_just_under_the_floor_blocks(self):
        # Boundary bite: the floor is the whole gate, so prove it at the edge.
        with tempfile.TemporaryDirectory() as tmp:
            body = "x" * (SESSION_ANALYSIS_MIN_BYTES - 1)
            (Path(tmp) / SESSION_ANALYSIS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_session_analysis(tmp)
            self.assertFalse(ok)
            self.assertIn(str(SESSION_ANALYSIS_MIN_BYTES - 1), reason)

    def test_at_the_floor_passes_with_no_content_requirement(self):
        with tempfile.TemporaryDirectory() as tmp:
            body = "x" * SESSION_ANALYSIS_MIN_BYTES
            (Path(tmp) / SESSION_ANALYSIS_FILENAME).write_text(body, encoding="utf-8")
            ok, reason = check_session_analysis(tmp)
            self.assertTrue(ok, reason)

    def test_substantial_analysis_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / SESSION_ANALYSIS_FILENAME).write_text(
                SUBSTANTIAL_SESSION_ANALYSIS, encoding="utf-8"
            )
            ok, reason = check_session_analysis(tmp)
            self.assertTrue(ok, reason)

    def test_bom_prefixed_file_still_reads(self):
        with tempfile.TemporaryDirectory() as tmp:
            data = b"\xef\xbb\xbf" + SUBSTANTIAL_SESSION_ANALYSIS.encode("utf-8")
            (Path(tmp) / SESSION_ANALYSIS_FILENAME).write_bytes(data)
            ok, reason = check_session_analysis(tmp)
            self.assertTrue(ok, reason)


class NewGatesMainWiringTests(unittest.TestCase):
    """End-to-end (via main()) proof that each 808 gate is actually joined to
    the exit composition -- the wrong-gate failure mode: a check can exist,
    print, and be entirely absent from the return expression.

    Every case starts from ONE fully-green fixture and breaks exactly one
    artifact, so an rc=1 is attributable to that artifact and not to
    incidental unsatisfied coverage/retrospective/review state (the pairing
    pattern used by DuplicateContentMainWiringTests / SizeFloorBiteTests).
    """

    MUSTWATCH_IDS = ("ui-api-truthfulness-under-load", "install-trust-prompts")

    def _manifest_path(self, tmp: Path) -> Path:
        manifest = {
            "version": 1,
            "mustTouch": [],
            "coveredElsewhere": [],
            "exempt": [],
            "mustWatch": [
                {"id": item_id, "reason": "r", "observability": "sandbox"}
                for item_id in self.MUSTWATCH_IDS
            ],
        }
        path = tmp / "coverage-manifest.json"
        path.write_text(json.dumps(manifest), encoding="utf-8")
        return path

    def _green_evidence(self, tmp: Path) -> Path:
        evidence = tmp / "evidence"
        evidence.mkdir()
        (evidence / RETROSPECTIVE_FILENAME).write_text(SUBSTANTIAL_RETROSPECTIVE, encoding="utf-8")
        (evidence / EVIDENCE_REVIEW_FILENAME).write_text(
            _empty_evidence_review_json(), encoding="utf-8"
        )
        _write_round_process_artifacts(evidence, self.MUSTWATCH_IDS)
        return evidence

    def _run(self, tmp: Path, evidence: Path) -> int:
        return main(["--manifest", str(self._manifest_path(tmp)), "--evidence-dir", str(evidence)])

    def test_full_green_path_passes(self):
        # The control every case below is measured against.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            self.assertEqual(self._run(tmp, self._green_evidence(tmp)), 0)

    def test_missing_mustwatch_verdicts_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUSTWATCH_VERDICTS_FILENAME).unlink()
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mustwatch_verdicts_missing_one_mode_included_id_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUSTWATCH_VERDICTS_FILENAME).write_text(
                _mustwatch_verdicts_json(self.MUSTWATCH_IDS[:1]), encoding="utf-8"
            )
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mustwatch_verdicts_bad_enum_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUSTWATCH_VERDICTS_FILENAME).write_text(
                json.dumps({
                    "schema": "mustwatch-verdicts.v1",
                    "items": [
                        {"id": self.MUSTWATCH_IDS[0], "verdict": "PASS"},
                        {"id": self.MUSTWATCH_IDS[1], "verdict": "observed-pass"},
                    ],
                }),
                encoding="utf-8",
            )
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mustwatch_unobservable_without_note_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUSTWATCH_VERDICTS_FILENAME).write_text(
                json.dumps({
                    "schema": "mustwatch-verdicts.v1",
                    "items": [
                        {"id": self.MUSTWATCH_IDS[0], "verdict": "observed-pass"},
                        {"id": self.MUSTWATCH_IDS[1], "verdict": "unobservable", "note": ""},
                    ],
                }),
                encoding="utf-8",
            )
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mustwatch_observed_fail_does_not_flip_the_exit_code(self):
        # The design point, proven end-to-end: an observed-fail is reported,
        # not enforced. If this ever starts returning 1, the gate has quietly
        # taken over a judgment call it was explicitly not given.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUSTWATCH_VERDICTS_FILENAME).write_text(
                json.dumps({
                    "schema": "mustwatch-verdicts.v1",
                    "items": [
                        {"id": self.MUSTWATCH_IDS[0], "verdict": "observed-fail",
                         "note": "stale cards while every status endpoint read READY"},
                        {"id": self.MUSTWATCH_IDS[1], "verdict": "observed-pass"},
                    ],
                }),
                encoding="utf-8",
            )
            self.assertEqual(self._run(tmp, evidence), 0)

    def test_missing_mutating_probe_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUTATING_PROBE_FILENAME).unlink()
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mutating_probe_status_fail_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUTATING_PROBE_FILENAME).write_text(
                _mutating_probe_json("fail"), encoding="utf-8"
            )
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_mutating_probe_status_skipped_passes(self):
        # 'skipped' warns loudly but does not fail: an unreachable backend
        # already fails coverage elsewhere, and a second failure for the same
        # cause is noise. Same fixture as the 'fail' case above -- only the
        # status differs -- so this isolates the status as the cause.
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / MUTATING_PROBE_FILENAME).write_text(
                _mutating_probe_json("skipped"), encoding="utf-8"
            )
            self.assertEqual(self._run(tmp, evidence), 0)

    def test_missing_session_analysis_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / SESSION_ANALYSIS_FILENAME).unlink()
            self.assertEqual(self._run(tmp, evidence), 1)

    def test_undersized_session_analysis_fails(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            evidence = self._green_evidence(tmp)
            (evidence / SESSION_ANALYSIS_FILENAME).write_text("too short", encoding="utf-8")
            self.assertEqual(self._run(tmp, evidence), 1)


if __name__ == "__main__":
    unittest.main()
