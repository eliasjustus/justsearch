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
generate_kickoff = sandbox_launch.generate_kickoff
KICKOFF_FILENAME = sandbox_launch.KICKOFF_FILENAME


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


class KickoffGenerationTests(unittest.TestCase):
    """Round 12 was launched with the instruction '/start also read
    KICKOFF.md' and no such file existed anywhere in the mapped folder
    (tempdoc 806 W3 item 4, session-analysis-round12.md B2). generate_kickoff()
    replaces "nobody ever staged it" with a normal staging step whose claims
    are derived from what actually landed in share_dir, and whose own
    absence becomes a declared staging gap -- the same discipline B2 already
    established for tools\\ assets, applied to this file."""

    def _share_dir(self, tmp: Path) -> Path:
        share_dir = tmp / "share"
        share_dir.mkdir()
        return share_dir

    def test_writes_kickoff_with_mode_from_validation_mode_md(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = self._share_dir(tmp)
            (share_dir / "validation-mode.md").write_text(
                "# Sandbox Validation Mode\n\n- Mode: fresh-install\n- Installer: x.exe\n",
                encoding="utf-8",
            )
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()

            gap = generate_kickoff(share_dir, "JustSearch_0.2.0_x64-setup.exe", tools_dst)

            self.assertIsNone(gap)
            kickoff_path = share_dir / KICKOFF_FILENAME
            self.assertTrue(kickoff_path.is_file())
            text = kickoff_path.read_text(encoding="utf-8")
            self.assertIn("fresh-install", text)
            self.assertIn("JustSearch_0.2.0_x64-setup.exe", text)

    def test_authority_docs_present_are_checked_absent_are_not(self):
        """Derived from reality, not a hardcoded list: a doc actually staged
        gets a checked box, one that wasn't (e.g. --no-charter's charter.md)
        gets an unchecked one -- never a blanket assumption either way."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = self._share_dir(tmp)
            (share_dir / "coverage-brief.md").write_text("brief", encoding="utf-8")
            (share_dir / "sandbox-environment.md").write_text("env", encoding="utf-8")
            # validation-mode.md, charter.md, staging-gaps.md deliberately absent.
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()

            gap = generate_kickoff(share_dir, "x.exe", tools_dst)
            self.assertIsNone(gap)

            text = (share_dir / KICKOFF_FILENAME).read_text(encoding="utf-8")
            self.assertIn("[x] `coverage-brief.md`", text)
            self.assertIn("[x] `sandbox-environment.md`", text)
            self.assertIn("[ ] `charter.md`", text)
            self.assertIn("[ ] `validation-mode.md`", text)
            self.assertIn("[ ] `staging-gaps.md`", text)

    def test_git_setup_staged_changes_the_setup_instruction(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = self._share_dir(tmp)
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()
            (tools_dst / "Git-Setup.exe").write_bytes(b"fake installer bytes")

            generate_kickoff(share_dir, "x.exe", tools_dst)

            text = (share_dir / KICKOFF_FILENAME).read_text(encoding="utf-8")
            self.assertIn("Git-Setup.exe", text)
            self.assertNotIn("no Git installer is staged", text)

    def test_git_not_staged_notes_the_fallback(self):
        """Precision guard, inverted: with no Git installer staged, KICKOFF.md
        must say so and point at the fallback, not silently claim step 1 as
        if it were staged."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = self._share_dir(tmp)
            tools_dst = share_dir / "tools"
            tools_dst.mkdir()

            generate_kickoff(share_dir, "x.exe", tools_dst)

            text = (share_dir / KICKOFF_FILENAME).read_text(encoding="utf-8")
            self.assertIn("no Git installer is staged", text)

    def test_missing_share_dir_returns_a_gap_not_a_crash(self):
        """The declared-gap requirement: if KICKOFF.md cannot be written (or
        is missing right after), generate_kickoff() must return a gap
        message for the caller's gaps list, not raise or silently succeed --
        the exact failure class B2 already closed for tools\\ assets."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "does-not-exist"  # never created
            tools_dst = share_dir / "tools"

            gap = generate_kickoff(share_dir, "x.exe", tools_dst)

            self.assertIsNotNone(gap)
            self.assertIn(KICKOFF_FILENAME, gap)
            self.assertFalse((share_dir / KICKOFF_FILENAME).exists())

    def test_kickoff_gap_reaches_staging_gaps_md(self):
        """End-to-end: a KICKOFF.md generation failure must land in the same
        staging-gaps.md every other missing asset is recorded in -- proving
        the two mechanisms are actually wired together, not just both
        individually correct."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            broken_share_dir = tmp / "unwritable-share"  # not created yet -> write fails
            tools_dst = broken_share_dir / "tools"

            gap = generate_kickoff(broken_share_dir, "x.exe", tools_dst)
            self.assertIsNotNone(gap)

            # write_staging_gaps needs an existing share_dir to write into --
            # in main()'s real ordering share_dir always exists by this
            # point (only KICKOFF.md's own write failed above).
            broken_share_dir.mkdir()
            write_staging_gaps(broken_share_dir, [gap])

            text = (broken_share_dir / "staging-gaps.md").read_text(encoding="utf-8")
            self.assertIn(KICKOFF_FILENAME, text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
