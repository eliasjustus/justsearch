#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's evidence-archiving fix.

A later sandbox-launch.py round used to overwrite the mapped share folder's
evidence/ (screenshots, traces, logs, findings) via resolve_share_dir()'s
clean_dir(canonical) -- destroying a prior round's raw artefacts with no way
to recover them. archive_existing_evidence() closes that: it runs before
resolve_share_dir() and moves (never deletes) a share dir that holds
captured evidence into stage_root/archive/round-NNN-<stamp>/, and fails the
whole staging run (sys.exit) rather than let staging proceed if the move
itself cannot complete.

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
name -- loaded via importlib.util from its file path instead (test_check_
coverage.py's sibling modules are all valid identifiers and don't need this).

Run: python scripts/sandbox/test_sandbox_launch_evidence_archive.py
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

archive_existing_evidence = sandbox_launch.archive_existing_evidence


def _write_fake_evidence(share_dir: Path, *, names: tuple[str, ...] = ("00-first-paint.png", "FINDINGS.md")) -> Path:
    """Populate share_dir/evidence/ with a couple of files, as collect-
    evidence.ps1 would from inside a real sandbox round."""
    evidence_dir = share_dir / "evidence"
    evidence_dir.mkdir(parents=True, exist_ok=True)
    for name in names:
        (evidence_dir / name).write_bytes(b"fake evidence bytes for " + name.encode("utf-8"))
    return evidence_dir


class ArchiveExistingEvidenceTests(unittest.TestCase):
    def test_no_canonical_share_dir_is_first_run_noop(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            result = archive_existing_evidence(stage_root)
            self.assertIsNone(result)
            self.assertFalse((stage_root / "archive").exists())

    def test_share_dir_with_no_evidence_subdir_is_noop(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"
            share.mkdir()
            (share / "CLAUDE.md").write_text("staged docs, no round ran yet", encoding="utf-8")
            result = archive_existing_evidence(stage_root)
            self.assertIsNone(result)
            # Untouched: still sitting at the canonical path, nothing archived.
            self.assertTrue((share / "CLAUDE.md").exists())
            self.assertFalse((stage_root / "archive").exists())

    def test_share_dir_with_empty_evidence_dir_is_noop(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"
            (share / "evidence").mkdir(parents=True)
            result = archive_existing_evidence(stage_root)
            self.assertIsNone(result)
            self.assertTrue((share / "evidence").exists())
            self.assertFalse((stage_root / "archive").exists())

    def test_populated_evidence_is_moved_not_deleted(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"
            _write_fake_evidence(share)

            dest = archive_existing_evidence(stage_root)

            self.assertIsNotNone(dest)
            self.assertFalse(share.exists(), "canonical share dir must be moved away, not left in place")
            self.assertTrue((dest / "evidence" / "00-first-paint.png").exists())
            self.assertTrue((dest / "evidence" / "FINDINGS.md").exists())
            self.assertTrue(str(dest).startswith(str(stage_root / "archive")))

    def test_two_rounds_both_survive_distinct_archives(self):
        """The exact regression scenario: round 1 stages evidence, then a
        second sandbox-launch.py invocation stages a new round over the same
        stage_root -- round 1's evidence must still be readable afterward,
        under its own archive dir, and round 2's evidence (once it also
        completes) must survive a third staging the same way."""
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"

            # --- Round 1 runs, produces evidence in the canonical share dir ---
            _write_fake_evidence(share, names=("round1-shot.png",))

            # --- Round 2 staging begins: archive round 1's evidence first ---
            dest1 = archive_existing_evidence(stage_root)
            self.assertIsNotNone(dest1)
            self.assertTrue((dest1 / "evidence" / "round1-shot.png").exists())
            self.assertFalse(share.exists(), "canonical share dir must be gone after archiving")

            # Round 2 restages into a fresh canonical share dir and produces its own evidence.
            _write_fake_evidence(share, names=("round2-shot.png",))

            # --- Round 3 staging begins: archive round 2's evidence ---
            dest2 = archive_existing_evidence(stage_root)
            self.assertIsNotNone(dest2)
            self.assertNotEqual(dest1, dest2, "each round must get its own archive dir")

            # Round 1's evidence must STILL be there, untouched by round 2's archival.
            self.assertTrue((dest1 / "evidence" / "round1-shot.png").exists())
            self.assertTrue((dest2 / "evidence" / "round2-shot.png").exists())

    def test_archive_dirs_are_ordered_and_distinguishable(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"

            _write_fake_evidence(share, names=("a.png",))
            dest1 = archive_existing_evidence(stage_root)
            share.mkdir(parents=True, exist_ok=True)
            _write_fake_evidence(share, names=("b.png",))
            dest2 = archive_existing_evidence(stage_root)

            archives = sorted(p.name for p in (stage_root / "archive").iterdir())
            self.assertEqual(archives, sorted([dest1.name, dest2.name]))
            # round-001-... must sort before round-002-... lexicographically too.
            self.assertTrue(archives[0].startswith("round-001-"))
            self.assertTrue(archives[1].startswith("round-002-"))

    def test_failed_archive_move_aborts_via_sys_exit_and_leaves_evidence_in_place(self):
        """Prove the fail-closed path: if the move itself cannot complete,
        the function must sys.exit (not silently continue), and the
        original evidence must remain exactly where it was -- never
        deleted, never left half-moved."""
        with tempfile.TemporaryDirectory() as tmp_str:
            stage_root = Path(tmp_str)
            share = stage_root / "share"
            _write_fake_evidence(share)

            with mock.patch.object(sandbox_launch.shutil, "move", side_effect=OSError("simulated lock: file in use")):
                with self.assertRaises(SystemExit) as ctx:
                    archive_existing_evidence(stage_root)

            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("FAILED to archive", str(ctx.exception.code))
            # Nothing was destroyed or half-moved: the original evidence is intact.
            self.assertTrue(share.exists())
            self.assertTrue((share / "evidence" / "00-first-paint.png").exists())
            # archive_root may have been mkdir'd before the failed move, but must
            # be empty -- no half-written round-NNN destination was left behind.
            archive_root = stage_root / "archive"
            if archive_root.exists():
                self.assertEqual(list(archive_root.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
