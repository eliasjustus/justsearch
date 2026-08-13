#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's convergence-tempdoc freshness guard
(tempdoc 734 Part B8, item B8.1).

The launcher refuses to stage when the release's convergence tempdoc
(docs/tempdocs/NNN-<version>-sandbox-convergence.md) records a latest round
number LOWER than the round the charter declares. Round 8 read a tempdoc that
stopped at round 6: it could not perform its documented function ("which prior
findings this round exists to re-confirm fixed, and which are still open"), and
a round-6 finding was rediscovered from scratch. Updating the tempdoc is a
host-side step after a round ends, and the only party positioned to notice the
skip is the NEXT round -- an agent with no baseline reading the document
precisely because it does not already know its contents. So the check runs on
the host, at staging time.

Both inputs are derived defensively: neither an unparseable charter nor an
unparseable tempdoc may report OK, because "a guard that cannot read its
inputs silently passes" is the exact defect class being fixed.

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
name -- loaded via importlib.util from its file path, mirroring
test_sandbox_launch_charter.py's load pattern.

Run: python scripts/sandbox/test_sandbox_launch_convergence_freshness.py
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_freshness_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

assert_convergence_tempdoc_current = sandbox_launch.assert_convergence_tempdoc_current
find_convergence_tempdoc = sandbox_launch.find_convergence_tempdoc
latest_recorded_round = sandbox_launch.latest_recorded_round
parse_charter_round = sandbox_launch.parse_charter_round


CHARTER_ROUND_8 = """\
# Round 8 charter -- v0.2.x candidate, post-798

Purpose: verify the candidate is now qualifiable.

## Blockers

- B1 ingest livelock (round 7 finding 2, HIGH): FIXED -- verify, do not assume.
  In round 7 it did not work, silently, forever.
"""

# Shaped like the real convergence tempdoc: a Part heading that MENTIONS a
# round, a pre-registration section for a round that has not run, and the
# actual round records.
TEMPDOC_THROUGH_ROUND_6 = """\
---
title: Sandbox convergence -- rounds 1-6
---

# 0.2.0 Sandbox convergence -- round 1+2

## Part A -- Round convergence record (findings + routing)

### A.1 [HIGH] Dense retrieval never activates

## Round 3 (qualifying, fresh-install) -- findings

## Round 5 (fresh-install, first GUI-capable round) -- DO-NOT-QUALIFY

## Round 6 pre-registration (staged, not yet run -- 2026-07-16)

## Round 6 (fresh-install, pre-registered) -- DO-NOT-QUALIFY

## Part B6 -- harness retrospective (round 6)
"""

TEMPDOC_THROUGH_ROUND_8 = TEMPDOC_THROUGH_ROUND_6 + """\

## Round 7 (fresh-install, first post-772 payload) -- DO-NOT-QUALIFY

## Round 8 (fresh-install, GUI-capable) -- QUALIFIABLE
"""


def _write_case(tmp: Path, charter_text: str, tempdoc_text: str) -> tuple[Path, Path]:
    """Write a charter file and a docs/tempdocs dir holding one convergence
    tempdoc; return (charter_path, tempdocs_dir)."""
    charter = tmp / "round-charter.md"
    charter.write_text(charter_text, encoding="utf-8")
    tempdocs = tmp / "tempdocs"
    tempdocs.mkdir()
    (tempdocs / "734-0.2.0-sandbox-convergence.md").write_text(tempdoc_text, encoding="utf-8")
    return charter, tempdocs


class ParseCharterRoundTests(unittest.TestCase):
    def test_round_number_read_from_heading(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            charter = Path(tmp_str) / "c.md"
            charter.write_text(CHARTER_ROUND_8, encoding="utf-8")
            self.assertEqual(parse_charter_round(charter), 8)

    def test_body_mention_of_an_earlier_round_does_not_win(self):
        """The charter body says "round 7 finding 2" AFTER the heading. The
        declared round is the heading's, never a body sentence's."""
        with tempfile.TemporaryDirectory() as tmp_str:
            charter = Path(tmp_str) / "c.md"
            charter.write_text(CHARTER_ROUND_8, encoding="utf-8")
            self.assertEqual(parse_charter_round(charter), 8)

    def test_charter_without_a_round_heading_aborts_nonzero(self):
        """A guard that cannot determine its input must fail, not pass."""
        with tempfile.TemporaryDirectory() as tmp_str:
            charter = Path(tmp_str) / "c.md"
            charter.write_text("# Charter\n\nPurpose: something.\n", encoding="utf-8")
            with self.assertRaises(SystemExit) as ctx:
                parse_charter_round(charter)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("Cannot determine this round's number", str(ctx.exception.code))


class LatestRecordedRoundTests(unittest.TestCase):
    def test_highest_recorded_round_section_wins(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            doc = Path(tmp_str) / "t.md"
            doc.write_text(TEMPDOC_THROUGH_ROUND_8, encoding="utf-8")
            self.assertEqual(latest_recorded_round(doc), 8)

    def test_pre_registration_section_is_not_a_record(self):
        """A round staged but not yet run is intent, not a record. Counting it
        would let the guard pass on the document state it exists to catch."""
        with tempfile.TemporaryDirectory() as tmp_str:
            doc = Path(tmp_str) / "t.md"
            doc.write_text(
                TEMPDOC_THROUGH_ROUND_6
                + "\n## Round 9 pre-registration (staged, not yet run)\n",
                encoding="utf-8",
            )
            self.assertEqual(latest_recorded_round(doc), 6)

    def test_part_heading_mentioning_a_round_is_not_counted(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            doc = Path(tmp_str) / "t.md"
            doc.write_text(
                TEMPDOC_THROUGH_ROUND_6
                + "\n## Part B8 -- harness retrospective (round 8, 2026-07-31)\n",
                encoding="utf-8",
            )
            self.assertEqual(latest_recorded_round(doc), 6)

    def test_tempdoc_without_round_sections_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            doc = Path(tmp_str) / "t.md"
            doc.write_text("# Convergence\n\nNo round sections here.\n", encoding="utf-8")
            with self.assertRaises(SystemExit) as ctx:
                latest_recorded_round(doc)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("Cannot determine the latest recorded round", str(ctx.exception.code))


class FindConvergenceTempdocTests(unittest.TestCase):
    def test_missing_convergence_tempdoc_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tempdocs = Path(tmp_str)
            (tempdocs / "750-sandbox-round-economics.md").write_text("x", encoding="utf-8")
            with self.assertRaises(SystemExit) as ctx:
                find_convergence_tempdoc(tempdocs)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("No convergence tempdoc found", str(ctx.exception.code))

    def test_highest_numbered_convergence_tempdoc_wins(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tempdocs = Path(tmp_str)
            (tempdocs / "734-0.2.0-sandbox-convergence.md").write_text("x", encoding="utf-8")
            (tempdocs / "812-0.3.0-sandbox-convergence.md").write_text("x", encoding="utf-8")
            self.assertEqual(
                find_convergence_tempdoc(tempdocs).name, "812-0.3.0-sandbox-convergence.md"
            )


class AssertConvergenceTempdocCurrentTests(unittest.TestCase):
    """The guard itself: fires when the tempdoc is behind the charter's round,
    passes when it is current."""

    def test_stale_tempdoc_refuses_to_stage(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            charter, tempdocs = _write_case(tmp, CHARTER_ROUND_8, TEMPDOC_THROUGH_ROUND_6)
            with self.assertRaises(SystemExit) as ctx:
                assert_convergence_tempdoc_current(str(charter), no_charter=False, tempdocs_dir=tempdocs)
            self.assertNotEqual(ctx.exception.code, 0)
            message = str(ctx.exception.code)
            self.assertIn("STALE", message)
            self.assertIn("round 8", message)
            self.assertIn("round 6", message)
            # The message must carry a remedy, not just a verdict.
            self.assertIn("Remedy", message)
            self.assertIn("734-0.2.0-sandbox-convergence.md", message)

    def test_current_tempdoc_passes(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            charter, tempdocs = _write_case(tmp, CHARTER_ROUND_8, TEMPDOC_THROUGH_ROUND_8)
            # No SystemExit: the tempdoc records round 8, the charter declares 8.
            assert_convergence_tempdoc_current(str(charter), no_charter=False, tempdocs_dir=tempdocs)

    def test_the_normal_new_round_case_passes(self):
        """THE discriminating case, and the one the first implementation got
        wrong: a tempdoc recording round N-1 while the charter declares round N.

        This is what EVERY new round looks like — the round being staged has not
        run, so its own section cannot exist yet. The bound is therefore
        `recorded >= declared - 1`, not `recorded >= declared`; the latter passes
        every other test in this class while refusing every real round. Round 8's
        actual defect was a two-round gap (tempdoc at 6, charter at 8), which the
        correct bound still catches — see test_stale_tempdoc_refuses_to_stage."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            charter_round_9 = CHARTER_ROUND_8.replace("# Round 8 charter", "# Round 9 charter")
            charter, tempdocs = _write_case(tmp, charter_round_9, TEMPDOC_THROUGH_ROUND_8)
            # No SystemExit: round 9 is being staged and rounds through 8 are recorded.
            assert_convergence_tempdoc_current(str(charter), no_charter=False, tempdocs_dir=tempdocs)

    def test_a_two_round_gap_still_refuses(self):
        """The correct bound must not become permissive: a tempdoc at round 7
        staging a round 9 charter is missing round 8 and must still refuse."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            charter_round_9 = CHARTER_ROUND_8.replace("# Round 8 charter", "# Round 9 charter")
            tempdoc_through_7 = TEMPDOC_THROUGH_ROUND_6.replace("## Round 6", "## Round 7")
            charter, tempdocs = _write_case(tmp, charter_round_9, tempdoc_through_7)
            with self.assertRaises(SystemExit) as ctx:
                assert_convergence_tempdoc_current(str(charter), no_charter=False, tempdocs_dir=tempdocs)
            self.assertIn("STALE", str(ctx.exception.code))
            # The remedy names the missing round only — not the one being staged.
            self.assertIn("record round(s) 8-8", str(ctx.exception.code))

    def test_tempdoc_ahead_of_charter_passes(self):
        """Only BEHIND is a defect. A tempdoc that already records a later
        round (e.g. a re-run of round 7) is not stale."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            charter_round_7 = CHARTER_ROUND_8.replace("# Round 8 charter", "# Round 7 charter")
            charter, tempdocs = _write_case(tmp, charter_round_7, TEMPDOC_THROUGH_ROUND_8)
            assert_convergence_tempdoc_current(str(charter), no_charter=False, tempdocs_dir=tempdocs)

    def test_no_charter_skips_the_check_without_touching_the_tempdoc(self):
        """--no-charter declares no round number, so there is nothing to
        compare against. The check is skipped -- and must not fail on a
        tempdoc/charter it never needed to read."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            missing_tempdocs = tmp / "no-such-dir"
            assert_convergence_tempdoc_current(
                None, no_charter=True, tempdocs_dir=missing_tempdocs
            )

    def test_missing_charter_file_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            tempdocs = tmp / "tempdocs"
            tempdocs.mkdir()
            with self.assertRaises(SystemExit) as ctx:
                assert_convergence_tempdoc_current(
                    str(tmp / "nope.md"), no_charter=False, tempdocs_dir=tempdocs
                )
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("not found", str(ctx.exception.code))


class RealRepoConvergenceTempdocTests(unittest.TestCase):
    """The guard's inputs must be derivable from the REAL checked-in artifacts,
    not only from synthetic fixtures -- otherwise the parsers could be green
    against fixtures shaped to fit them."""

    def test_real_convergence_tempdoc_parses(self):
        tempdocs = SCRIPT_DIR.parent.parent / "docs" / "tempdocs"
        if not tempdocs.is_dir():
            self.skipTest("docs/tempdocs not present in this checkout")
        doc = find_convergence_tempdoc(tempdocs)
        self.assertTrue(doc.name.endswith("-sandbox-convergence.md"))
        self.assertGreaterEqual(latest_recorded_round(doc), 8)


if __name__ == "__main__":
    unittest.main(verbosity=2)
