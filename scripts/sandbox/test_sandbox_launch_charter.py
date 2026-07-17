#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's charter-staging mechanics (tempdoc 750
Part B).

Session-Based Test Management (J. Bach & J. Bach, STQE 2000 -- charter/
debrief/TBS time accounting, adapted) treats the charter as a round's
pre-registered contract: a qualifying round pre-registers its purpose and
each blocker's needs-round/needs-dig classification BEFORE the round
starts. sandbox-launch.py now requires exactly one of --charter <path> or
--no-charter on every real invocation (main()), and stages a charter.md
(verbatim + a generated "Resolved launch mode" trailer) or prints a
non-qualifying notice accordingly.

The requirement is enforced inside main()'s argument parsing, which only
executes when the script actually runs (`if __name__ == "__main__":` /
sys.argv-driven main() calls) -- NOT when the module is merely imported for
its functions, which is exactly how test_sandbox_launch_evidence_archive.py
uses it (it never calls main()), so that sibling test suite is unaffected by
this new required flag.

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
name -- loaded via importlib.util from its file path, mirroring
test_sandbox_launch_evidence_archive.py's load pattern.

Run: python scripts/sandbox/test_sandbox_launch_charter.py
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_charter_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

stage_charter = sandbox_launch.stage_charter
write_validation_mode = sandbox_launch.write_validation_mode
main = sandbox_launch.main


CHARTER_BODY = """\
# Round charter

Purpose: verify the sandbox-launch.py TBS mechanics (tempdoc 750 Part B).

## Blockers

- Blocker A: needs-round (must reproduce inside a real sandbox boot)
- Blocker B: needs-dig (can be resolved by reading the source alone)
"""


class StageCharterTests(unittest.TestCase):
    """Unit-level tests for stage_charter(), independent of main()/argparse --
    mirrors test_sandbox_launch_evidence_archive.py's style of testing the
    staging function directly rather than driving the whole CLI."""

    def test_charter_staged_verbatim_with_trailer_appended(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            # Set up a real resolved-mode file the way main() would have,
            # without touching write_validation_mode()'s own logic.
            write_validation_mode(share_dir, Path("JustSearch-setup.exe"), None, no_models=True)

            charter_path = tmp / "my-charter.md"
            charter_path.write_text(CHARTER_BODY, encoding="utf-8")

            stage_charter(share_dir, str(charter_path), no_charter=False)

            staged = share_dir / "charter.md"
            self.assertTrue(staged.exists())
            staged_text = staged.read_text(encoding="utf-8")
            # Verbatim: the original charter body is present unmodified.
            self.assertIn(CHARTER_BODY.strip(), staged_text)
            # Trailer: a generated "Resolved launch mode" section, reflecting
            # the mode write_validation_mode() actually resolved
            # (no_models=True -> "fresh-install").
            self.assertIn("## Resolved launch mode", staged_text)
            self.assertIn("fresh-install", staged_text)
            # Trailer comes after the original body.
            self.assertLess(
                staged_text.index(CHARTER_BODY.strip().splitlines()[0]),
                staged_text.index("## Resolved launch mode"),
            )

    def test_charter_trailer_reflects_pre_staged_models_mode(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            fake_models_dir = tmp / "models"
            fake_models_dir.mkdir()
            write_validation_mode(
                share_dir, Path("JustSearch-setup.exe"), fake_models_dir, no_models=False
            )

            charter_path = tmp / "charter.md"
            charter_path.write_text(CHARTER_BODY, encoding="utf-8")
            stage_charter(share_dir, str(charter_path), no_charter=False)

            staged_text = (share_dir / "charter.md").read_text(encoding="utf-8")
            self.assertIn("pre-staged-models", staged_text)

    def test_no_charter_prints_notice_and_stages_nothing(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            write_validation_mode(share_dir, Path("JustSearch-setup.exe"), None, no_models=True)

            stage_charter(share_dir, None, no_charter=True)

            self.assertFalse((share_dir / "charter.md").exists())

    def test_missing_charter_file_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            write_validation_mode(share_dir, Path("JustSearch-setup.exe"), None, no_models=True)

            missing_path = tmp / "does-not-exist.md"
            with self.assertRaises(SystemExit) as ctx:
                stage_charter(share_dir, str(missing_path), no_charter=False)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("not found", str(ctx.exception.code))
            self.assertFalse((share_dir / "charter.md").exists())

    def test_empty_charter_file_aborts_nonzero(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share_dir = tmp / "share"
            share_dir.mkdir()
            write_validation_mode(share_dir, Path("JustSearch-setup.exe"), None, no_models=True)

            empty_path = tmp / "empty-charter.md"
            empty_path.write_text("   \n\n", encoding="utf-8")  # whitespace-only

            with self.assertRaises(SystemExit) as ctx:
                stage_charter(share_dir, str(empty_path), no_charter=False)
            self.assertNotEqual(ctx.exception.code, 0)
            self.assertIn("empty", str(ctx.exception.code))
            self.assertFalse((share_dir / "charter.md").exists())


class MainArgvDrivenTests(unittest.TestCase):
    """Proves the fail-closed requirement fires from main() itself, and that
    it fires BEFORE any filesystem-dependent staging step (find_installer,
    etc.) -- so these tests need no installer, no repo checkout state
    beyond this real checkout's own gradlew.bat. This also documents the
    enforcement boundary: main() is the only caller of this check, and
    main() only runs on a real script invocation, never on module import
    (see module docstring) -- that is exactly why
    test_sandbox_launch_evidence_archive.py, which never calls main(),
    still passes unmodified with this new required flag in place.

    main() in sandbox-launch.py takes no argv parameter and reads sys.argv
    directly (unlike check_coverage.py's main(argv=None)), so these tests
    patch sys.argv the same way the real CLI is invoked."""

    def _run_main_with_argv(self, argv_tail: list[str]):
        old_argv = sys.argv
        sys.argv = ["sandbox-launch.py"] + argv_tail
        try:
            main()
        finally:
            sys.argv = old_argv

    def test_neither_flag_given_aborts_nonzero_with_named_error(self):
        with self.assertRaises(SystemExit) as ctx:
            self._run_main_with_argv(["--no-models"])
        self.assertNotEqual(ctx.exception.code, 0)
        message = str(ctx.exception.code)
        self.assertIn("--charter", message)
        self.assertIn("--no-charter", message)
        self.assertIn("tempdoc 750 Part B", message)

    def test_both_flags_given_aborts_nonzero(self):
        with self.assertRaises(SystemExit) as ctx:
            self._run_main_with_argv(["--no-models", "--charter", "some.md", "--no-charter"])
        self.assertNotEqual(ctx.exception.code, 0)
        self.assertIn("mutually exclusive", str(ctx.exception.code))

    def test_no_charter_alone_does_not_trip_the_required_check(self):
        # Should get past the charter requirement check and fail LATER, on
        # something filesystem-dependent (no repo root / no installer) --
        # never on "charter is required".
        with self.assertRaises(SystemExit) as ctx:
            self._run_main_with_argv(["--no-models", "--no-charter", "--installer", "does-not-exist.exe"])
        message = str(ctx.exception.code)
        self.assertNotIn("Exactly one of --charter", message)


if __name__ == "__main__":
    unittest.main()
