#!/usr/bin/env python3
"""Self-tests for sandbox-launch.py's candidate-provenance artifact (tempdoc
734 Part B8, round-8 retrospective).

Round 8 recorded no candidate commit hash -- no PR, branch, CI-run or artifact
URL was reachable from inside the sandbox -- so settling whether a specific fix
was present in the validated candidate later required matching a CI run's head
SHA against a merge commit by hand. The launcher now writes
<share>/candidate-provenance.md at staging time: installer filename, SHA-256,
size, host source path, agreement with a SHA256SUMS manifest staged beside the
installer, and the build commit WHEN it can be derived from disk.

The commit is best-effort and honest: with no build metadata beside the
installer the file says NOT DETERMINABLE in as many words. Deriving it from the
host checkout's HEAD would be a fabrication -- the candidate is normally
downloaded from a CI run and has no relationship to whatever the host has
checked out -- so these tests also pin that the host checkout is never used as
a substitute.

sandbox-launch.py's filename contains a hyphen, so it can't be `import`ed by
name -- loaded via importlib.util from its file path, mirroring
test_sandbox_launch_upgrade.py's load pattern.

Run: python scripts/sandbox/test_sandbox_launch_provenance.py
"""

from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent

_spec = importlib.util.spec_from_file_location(
    "sandbox_launch_provenance_under_test", SCRIPT_DIR / "sandbox-launch.py"
)
sandbox_launch = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sandbox_launch)  # type: ignore[union-attr]

write_candidate_provenance = sandbox_launch.write_candidate_provenance

INSTALLER_BYTES = b"candidate installer bytes, 0.2.0"
INSTALLER_NAME = "JustSearch_0.2.0_x64-setup.exe"


def _fixture(tmp: Path) -> tuple[Path, Path]:
    """Create a share dir and a candidate installer; return (share, installer)."""
    share = tmp / "share"
    share.mkdir()
    artifact = tmp / "windows-installer"
    artifact.mkdir()
    installer = artifact / INSTALLER_NAME
    installer.write_bytes(INSTALLER_BYTES)
    return share, installer


class ProvenanceFieldsTests(unittest.TestCase):
    def test_writes_filename_sha256_size_and_source_path(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)

            path = write_candidate_provenance(share, installer)

            self.assertEqual(path, share / "candidate-provenance.md")
            text = path.read_text(encoding="utf-8")
            self.assertIn(f"- Installer: {INSTALLER_NAME}", text)
            self.assertIn(
                f"- SHA-256: {hashlib.sha256(INSTALLER_BYTES).hexdigest()}", text
            )
            self.assertIn(f"- Size: {len(INSTALLER_BYTES)} bytes", text)
            self.assertIn(str(installer), text)
            self.assertIn("- Modified (host, UTC): ", text)

    def test_undeterminable_commit_is_stated_not_fabricated(self):
        """No build metadata beside the installer -> the artifact says so,
        carries no 40-hex value, and names what would make it derivable."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn("Candidate commit: NOT DETERMINABLE", text)
            self.assertIn("build workflow", text)
            # No commit-shaped token anywhere on the commit line.
            commit_line = next(
                line for line in text.splitlines() if line.startswith("- Candidate commit:")
            )
            self.assertIsNone(sandbox_launch._GIT_SHA_RE.search(commit_line))

    def test_host_checkout_head_is_never_substituted_for_the_commit(self):
        """This suite runs inside a real git checkout with a real HEAD. The
        artifact must still report NOT DETERMINABLE -- a host HEAD is not the
        candidate's provenance."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)
            write_candidate_provenance(share, installer)
            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertNotIn("Candidate commit: 0", text)
            self.assertIn("NOT DETERMINABLE", text)

    def test_commit_read_from_build_metadata_json_when_present(self):
        """The read side of the contract: the day a build drops metadata beside
        the installer, the commit is recorded rather than reported missing."""
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)
            sha = "a" * 39 + "7"
            (installer.parent / "build-info.json").write_text(
                json.dumps({"commit": sha, "workflow": "build-installer.yml"}),
                encoding="utf-8",
            )

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn(f"- Candidate commit: {sha}", text)
            self.assertIn("build-info.json", text)
            self.assertNotIn("NOT DETERMINABLE", text)

    def test_commit_read_from_plain_text_metadata(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)
            sha = "0123456789abcdef0123456789abcdef01234567"
            (installer.parent / "commit.txt").write_text(sha + "\n", encoding="utf-8")

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn(f"- Candidate commit: {sha}", text)


class ChecksumManifestAgreementTests(unittest.TestCase):
    """The SHA256SUMS manifest is what the real artifact download ships
    alongside the installer -- provenance records whether it agrees."""

    def test_matching_manifest_entry_is_recorded(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)
            digest = hashlib.sha256(INSTALLER_BYTES).hexdigest()
            (installer.parent / "SHA256SUMS").write_text(
                "# SHA-256 checksums for JustSearch release assets.\n"
                f"{digest}  {INSTALLER_NAME}\n",
                encoding="utf-8",
            )

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn("- Checksum manifest: matches the SHA256SUMS entry", text)

    def test_mismatching_manifest_entry_is_reported(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)
            wrong = hashlib.sha256(b"a different build entirely").hexdigest()
            (installer.parent / "SHA256SUMS").write_text(
                f"{wrong}  {INSTALLER_NAME}\n", encoding="utf-8"
            )

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn("MISMATCH", text)
            self.assertIn(wrong, text)

    def test_absent_manifest_is_recorded_as_absent(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            share, installer = _fixture(tmp)

            write_candidate_provenance(share, installer)

            text = (share / "candidate-provenance.md").read_text(encoding="utf-8")
            self.assertIn("no SHA256SUMS manifest beside the installer", text)


if __name__ == "__main__":
    unittest.main(verbosity=2)
