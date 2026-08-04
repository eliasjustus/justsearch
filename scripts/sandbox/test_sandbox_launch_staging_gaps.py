#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's documented-tools staging-gap diff
(tempdoc 805 Part E / G.5, round-11 retrospective item 2).

Round 11 recorded staging-gaps.md saying "None -- all documented assets
staged" while `tools\\Git-Setup.exe` was silently absent: nothing in the
generator had ever checked for it, so the default-to-"None" behaviour
masked a real gap. `check_documented_tools_staged()` replaces that trust
with a diff: a declared list of assets the staged docs tell a round to run
out of `tools\\` (DOCUMENTED_TOOLS_ASSETS), checked by glob against what
actually landed in the staged share dir's `tools\\`.

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed
by name -- loaded via importlib.util from its file path, mirroring
test_sandbox_launch_provenance.py's load pattern.

Run: python scripts/sandbox/test_sandbox_launch_staging_gaps.py
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_staging_gaps_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

check_documented_tools_staged = sandbox_launch.check_documented_tools_staged
write_staging_gaps = sandbox_launch.write_staging_gaps
DOCUMENTED_TOOLS_ASSETS = sandbox_launch.DOCUMENTED_TOOLS_ASSETS


class DocumentedToolsDiffTests(unittest.TestCase):
    def test_empty_tools_dir_reports_a_gap_per_documented_asset(self):
        """Round 11's exact failure mode: nothing staged in tools\\ at all.
        Every documented asset must come back as its own named gap -- not a
        single generic 'tools missing' message, and not silently absorbed."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tools_dst = Path(tmp_str) / "tools"
            tools_dst.mkdir()

            gaps = check_documented_tools_staged(tools_dst)

            self.assertEqual(len(gaps), len(DOCUMENTED_TOOLS_ASSETS))
            joined = "\n".join(gaps)
            self.assertIn("Git for Windows", joined)
            self.assertIn("Node.js Windows installer", joined)

    def test_git_setup_exe_present_clears_only_the_git_gap(self):
        """Round 11's specific miss, inverted: Git-Setup.exe staged, Node
        installer still absent -- exactly one gap should remain, and it must
        name Node, not Git."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tools_dst = Path(tmp_str) / "tools"
            tools_dst.mkdir()
            (tools_dst / "Git-Setup.exe").write_bytes(b"fake installer bytes")

            gaps = check_documented_tools_staged(tools_dst)

            self.assertEqual(len(gaps), 1)
            self.assertIn("Node.js Windows installer", gaps[0])
            self.assertNotIn("Git", gaps[0])

    def test_all_documented_assets_present_reports_no_gaps(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tools_dst = Path(tmp_str) / "tools"
            tools_dst.mkdir()
            (tools_dst / "Git-Setup.exe").write_bytes(b"fake git installer")
            (tools_dst / "node-v20.11.1-x64.msi").write_bytes(b"fake node installer")

            gaps = check_documented_tools_staged(tools_dst)

            self.assertEqual(gaps, [])

    def test_missing_tools_dir_reports_a_gap_per_documented_asset(self):
        """tools_dst itself never got created (e.g. staging aborted earlier)
        -- Path.glob on a non-existent dir must not raise, and must still be
        treated as 'nothing staged', not silently pass."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tools_dst = Path(tmp_str) / "tools-never-created"

            gaps = check_documented_tools_staged(tools_dst)

            self.assertEqual(len(gaps), len(DOCUMENTED_TOOLS_ASSETS))


class StagingGapsFileIntegrationTests(unittest.TestCase):
    """The diff's output must actually reach staging-gaps.md as named
    entries, not just be computed and discarded -- this is what write_staging_gaps
    turns into the in-sandbox agent's authoritative gap record."""

    def test_diffed_gap_reaches_staging_gaps_md(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            share_dir = Path(tmp_str) / "share"
            share_dir.mkdir()
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()
            (tools_dst / "node-v20.11.1-x64.msi").write_bytes(b"fake node installer")

            gaps = check_documented_tools_staged(tools_dst)
            write_staging_gaps(share_dir, gaps)

            text = (share_dir / "staging-gaps.md").read_text(encoding="utf-8")
            self.assertIn("Git for Windows", text)
            self.assertNotIn("documented assets staged.", text)

    def test_no_gaps_still_writes_the_none_line(self):
        """The default-to-'None' wording is not wrong in itself -- it was
        wrong when nothing had checked. With everything actually staged, the
        same 'None' line is an honest report, not a masked gap."""
        with tempfile.TemporaryDirectory() as tmp_str:
            share_dir = Path(tmp_str) / "share"
            share_dir.mkdir()
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()
            (tools_dst / "Git-Setup.exe").write_bytes(b"fake git installer")
            (tools_dst / "node-v20.11.1-x64.msi").write_bytes(b"fake node installer")

            gaps = check_documented_tools_staged(tools_dst)
            write_staging_gaps(share_dir, gaps)

            text = (share_dir / "staging-gaps.md").read_text(encoding="utf-8")
            self.assertIn("all documented assets staged.", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
