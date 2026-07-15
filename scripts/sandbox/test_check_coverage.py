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
    MIN_SCREENSHOT_BYTES,
    RETROSPECTIVE_FILENAME,
    RETROSPECTIVE_MIN_BYTES,
    MustTouchItem,
    check_retrospective,
    find_duplicate_token_collisions,
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
"""


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


if __name__ == "__main__":
    unittest.main()
