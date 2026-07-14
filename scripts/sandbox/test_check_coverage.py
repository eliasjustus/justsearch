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
    RETROSPECTIVE_FILENAME,
    RETROSPECTIVE_MIN_BYTES,
    check_retrospective,
    main,
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


if __name__ == "__main__":
    unittest.main()
