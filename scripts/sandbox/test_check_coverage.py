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

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from check_coverage import (  # noqa: E402
    BULK_FRAMES_DIRNAME,
    EVIDENCE_REVIEW_FILENAME,
    MIN_SCREENSHOT_BYTES,
    RETROSPECTIVE_FILENAME,
    RETROSPECTIVE_MIN_BYTES,
    TIME_ACCOUNTING_REQUIRED_GROUPS,
    MustTouchItem,
    _time_accounting_section,
    check_evidence_review,
    check_retrospective,
    emit_evidence_timeline,
    find_duplicate_token_collisions,
    is_bulk_frame,
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


if __name__ == "__main__":
    unittest.main()
