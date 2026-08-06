#!/usr/bin/env python3
"""Regression tests for the GUI capture harness's fail-loud contract
(sandbox round 10 finding H1, tempdoc 734 / 804 §B10).

The defect: snap.ps1 -> Save-DesktopShot printed "saved: <path>" and the
wrapper exited 0 even when $bmp.Save() never produced a file, so a round
reported screenshot evidence it did not have.

The contract these tests pin, on the two signals that are actually
observable to a caller:
  * powershell.exe's PROCESS exit code (native exit code -- reliable;
    NOT $LASTEXITCODE after dot-sourcing, which round 10 also got wrong), and
  * Test-Path / os.path.exists on the target file.
Never assert on stdout: "saved:" is written with Write-Host, which does not
reach the captured stdout stream at all -- that invisibility is precisely
what made the false success survive two rounds.

Negative case: the fix auto-creates a missing parent directory, so a
genuinely unsavable path is one whose parent is a FILE.
Positive case: an -Out under a not-yet-created directory must now SUCCEED.

Run: python scripts/sandbox/test_gui_capture_failure.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
SNAP_PS1 = SCRIPT_DIR / "gui" / "snap.ps1"

_IS_WINDOWS = os.name == "nt"
_POWERSHELL = shutil.which("powershell.exe") or shutil.which("powershell")

# Substrings that mean "this host cannot capture the screen at all" (no
# interactive desktop / session 0), as opposed to "the save path was bad".
# Deliberately narrow: the harness's own save-failure messages ("could not
# save", "does not exist after Save()") must NEVER be read as unavailable,
# or the positive test would skip itself past a real regression.
_NO_DESKTOP_MARKERS = (
    "null-valued expression",
    "PrimaryScreen",
    "handle is invalid",
    "CopyFromScreen",
)
_SAVE_FAILURE_MARKERS = ("could not save", "does not exist after Save()")


def _run_snap(out_path: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            _POWERSHELL,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(SNAP_PS1),
            "-Out",
            str(out_path),
        ],
        capture_output=True,
        text=True,
        timeout=180,
    )


@unittest.skipUnless(_IS_WINDOWS and _POWERSHELL, "Windows PowerShell host required")
class SnapFailLoudTest(unittest.TestCase):
    def test_unsavable_path_exits_nonzero_and_writes_nothing(self):
        """Parent-is-a-file => non-zero exit AND no file at the target path."""
        with tempfile.TemporaryDirectory() as tmp:
            blocker = Path(tmp) / "blocker"
            blocker.write_text("not a directory", encoding="utf-8")
            target = blocker / "probe.png"

            result = _run_snap(target)

            self.assertNotEqual(
                0,
                result.returncode,
                "snap.ps1 must exit NON-ZERO when the capture cannot be written "
                f"(exit={result.returncode})\n--- stdout ---\n{result.stdout}\n"
                f"--- stderr ---\n{result.stderr}",
            )
            self.assertFalse(
                target.exists(),
                f"No file may exist at {target} after a failed capture",
            )
            self.assertTrue(
                blocker.is_file(),
                "the blocker file must still be a file (the fix must not "
                "replace it with a directory)",
            )

    def test_missing_parent_directory_is_created_and_capture_succeeds(self):
        """-Out under a not-yet-created directory succeeds: dir created, file
        exists, exit 0. (The round-10 defect's most common trigger.)"""
        with tempfile.TemporaryDirectory() as tmp:
            target = Path(tmp) / "not" / "yet" / "created" / "probe.png"

            result = _run_snap(target)

            combined = f"{result.stdout}\n{result.stderr}"
            if result.returncode != 0:
                looks_like_no_desktop = any(
                    marker in combined for marker in _NO_DESKTOP_MARKERS
                ) and not any(
                    marker in combined for marker in _SAVE_FAILURE_MARKERS
                )
                if looks_like_no_desktop:
                    self.skipTest(
                        "screen capture is unavailable in this non-interactive "
                        f"context (no desktop); output was:\n{combined}"
                    )

            self.assertEqual(
                0,
                result.returncode,
                "snap.ps1 must exit 0 when the parent directory merely did not "
                f"exist yet (exit={result.returncode})\n{combined}",
            )
            self.assertTrue(
                target.exists(),
                f"snap.ps1 exited 0 but produced no file at {target} -- that is "
                f"the false success this test exists to catch\n{combined}",
            )
            self.assertGreater(
                target.stat().st_size, 0, "captured PNG must not be empty"
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
