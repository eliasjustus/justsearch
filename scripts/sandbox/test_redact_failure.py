#!/usr/bin/env python3
"""Regression tests for redact.ps1's fail-loud contract (sandbox round 12
finding A1 / tempdoc 806 W3 item 3).

The defect: [IO.Path]::GetFullPath((Join-Path (Get-Location) $Out)) always
re-joined $Out onto the current directory even when $Out was already
absolute, throwing "The given path's format is not supported." Because
nothing upstream enforced Stop, the script limped forward through
$bmp.Save($null) (also an error) and still printed a success-shaped
`redacted -> ( bytes)` line. Round 12 deleted the unredacted original on the
strength of that line and only recovered because the secret was still on
screen -- the round-10 "H1" class (claimed captures, zero files) living
inside the one tool whose job is protecting a secret.

The contract these tests pin, matching test_gui_capture_failure.py's
philosophy: assert on the PROCESS exit code and Path.exists()/file size,
never on stdout text -- a script can print a success-shaped line while
having done nothing, which is exactly the trap being closed here.

Run: python scripts/sandbox/test_redact_failure.py
"""

from __future__ import annotations

import base64
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
REDACT_PS1 = SCRIPT_DIR / "redact.ps1"

_IS_WINDOWS = os.name == "nt"
_POWERSHELL = shutil.which("powershell.exe") or shutil.which("powershell")

# A minimal, genuinely valid 1x1 transparent PNG -- redact.ps1 needs a real
# -In it can open with System.Drawing.Image.FromFile; arbitrary bytes would
# fail to load for an unrelated reason and defeat the isolation these tests
# need (the point is to isolate the -Out path-handling bug, not PNG decoding).
_TINY_PNG_B64 = (
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY"
    "42YAAAAASUVORK5CYII="
)


def _write_tiny_png(path: Path) -> None:
    path.write_bytes(base64.b64decode(_TINY_PNG_B64))


def _run_redact(in_path: Path, out_path: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [
            _POWERSHELL,
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-File",
            str(REDACT_PS1),
            "-In",
            str(in_path),
            "-Out",
            str(out_path),
            "-X",
            "0",
            "-Y",
            "0",
            "-W",
            "1",
            "-H",
            "1",
        ],
        capture_output=True,
        text=True,
        timeout=120,
    )


@unittest.skipUnless(_IS_WINDOWS and _POWERSHELL, "Windows PowerShell host required")
class RedactFailLoudTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.in_path = self.tmp / "src.png"
        _write_tiny_png(self.in_path)

    def tearDown(self):
        self._tmp.cleanup()

    def test_absolute_out_path_succeeds_and_writes_a_real_file(self):
        """THE regression: a valid ABSOLUTE -Out must genuinely redact, not
        silently corrupt while printing a success-shaped line. The old code
        threw on this exact input (doubled-drive-letter GetFullPath
        failure) and still printed 'redacted -> ( bytes)'."""
        out_dir = Path(tempfile.mkdtemp())
        try:
            out_path = out_dir / "redacted.png"
            self.assertTrue(out_path.is_absolute())
            result = _run_redact(self.in_path, out_path)
            self.assertEqual(
                0,
                result.returncode,
                "redact.ps1 must succeed on a valid absolute -Out "
                f"(exit={result.returncode})\n--- stdout ---\n{result.stdout}\n"
                f"--- stderr ---\n{result.stderr}",
            )
            self.assertTrue(out_path.exists(), f"no file was written at {out_path}")
            self.assertGreater(out_path.stat().st_size, 0, "redacted PNG must not be empty")
        finally:
            shutil.rmtree(out_dir, ignore_errors=True)

    def test_relative_out_path_still_succeeds(self):
        """Precision guard: the relative-path case (never broken) must keep
        working after the fix -- proving the fix is additive, not a
        regression on the path that always worked."""
        out_path = self.tmp / "redacted-relative.png"
        result = subprocess.run(
            [
                _POWERSHELL,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                str(REDACT_PS1),
                "-In",
                str(self.in_path),
                "-Out",
                "redacted-relative.png",
                "-X",
                "0",
                "-Y",
                "0",
                "-W",
                "1",
                "-H",
                "1",
            ],
            capture_output=True,
            text=True,
            timeout=120,
            cwd=str(self.tmp),
        )
        self.assertEqual(
            0, result.returncode, f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
        self.assertTrue(out_path.exists())
        self.assertGreater(out_path.stat().st_size, 0)

    def test_unwritable_target_fails_loud_no_file_written(self):
        """A genuine failure (the parent 'directory' is actually a FILE, so
        it can never be created) must exit non-zero AND write nothing --
        the exact 'success-shaped failure' class the fix closes."""
        blocker = self.tmp / "blocker"
        blocker.write_text("not a directory", encoding="utf-8")
        out_path = blocker / "redacted.png"

        result = _run_redact(self.in_path, out_path)

        self.assertNotEqual(
            0,
            result.returncode,
            "redact.ps1 must exit NON-ZERO when the output cannot be written "
            f"(exit={result.returncode})\n--- stdout ---\n{result.stdout}\n"
            f"--- stderr ---\n{result.stderr}",
        )
        self.assertFalse(
            out_path.exists(), f"no file may exist at {out_path} after a failed redact"
        )
        self.assertTrue(
            blocker.is_file(), "the blocker file must still be a file (fix must not replace it)"
        )

    def test_missing_input_fails_loud_no_file_written(self):
        """-In pointing at a file that does not exist must fail loud, not
        silently produce output derived from nothing."""
        missing_in = self.tmp / "does-not-exist.png"
        out_path = self.tmp / "redacted-from-missing.png"

        result = _run_redact(missing_in, out_path)

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(out_path.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
