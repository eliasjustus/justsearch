#!/usr/bin/env python3
"""Self-tests for plant_defects.py's two hard design constraints (tempdoc 734
F.7) and its manifest/CLI wiring.

Covers: the ground-truth-outside-output guard (both the raise-on-violation
and pass-on-sibling-path cases), the mislabeled-capture anti-contamination
property (re-encoded plant must be SHA-256-unique vs its donor, not merely
"different bytes because we say so"), the register/plan drift check, and an
end-to-end main() run asserting the ground-truth manifest records
class/claims/actually_shows per planted file.

Run: python scripts/sandbox/test_plant_defects.py
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

try:
    from PIL import Image

    _HAS_PIL = True
except ImportError:  # pragma: no cover - exercised only on a host without Pillow
    Image = None  # type: ignore[assignment]
    _HAS_PIL = False

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from plant_defects import (  # noqa: E402
    CLASS_SOURCE_FILES,
    GroundTruthInsideOutputError,
    RegisterDriftError,
    assert_ground_truth_outside_output,
    build_mislabeled,
    main,
    sha256_of,
    verify_register_matches_plan,
)


def _write_source_png(path: Path, color: tuple[int, int, int]) -> None:
    """A tiny, valid PNG -- plant_defects.py never inspects pixel content
    beyond re-encoding it, so a minimal fixture is sufficient and keeps
    these tests hermetic (no dependency on tmp/sandbox-evidence/**)."""
    Image.new("RGB", (64, 48), color).save(path, format="PNG")


def _make_source_evidence(evidence_dir: Path) -> None:
    """Populate evidence_dir with minimal stand-ins for every filename the
    registered plant plan depends on (mislabeled-capture + duplicate-credit;
    blank-capture is synthesized from scratch and needs no source file)."""
    evidence_dir.mkdir(parents=True, exist_ok=True)
    needed = sorted(CLASS_SOURCE_FILES["mislabeled-capture"]) + sorted(
        CLASS_SOURCE_FILES["duplicate-credit"]
    )
    for i, name in enumerate(needed):
        _write_source_png(evidence_dir / name, color=(10 * i % 255, 20 * i % 255, 30 * i % 255))


@unittest.skipUnless(_HAS_PIL, "Pillow (PIL) not installed — plant_defects image ops unavailable")
class GroundTruthLocationGuardTests(unittest.TestCase):
    """tempdoc 734 F.7: "ground-truth bookkeeping must ... never be staged
    into the sandbox." A reader who can reach the answer key is not being
    measured -- this guard is the enforcement, not the docstring alone."""

    def test_ground_truth_inside_output_dir_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "planted"
            ground_truth = output_dir / "ground-truth-manifest.json"
            with self.assertRaises(GroundTruthInsideOutputError) as ctx:
                assert_ground_truth_outside_output(ground_truth, output_dir)
            self.assertIn("resolves inside", str(ctx.exception))

    def test_ground_truth_deeply_nested_inside_output_dir_raises(self):
        # A subdirectory nesting must not defeat the guard -- it checks real
        # path containment (Path.resolve() + `in .parents`), not a shallow
        # "same directory" check.
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "planted"
            ground_truth = output_dir / "nested" / "deeper" / "ground-truth.json"
            with self.assertRaises(GroundTruthInsideOutputError):
                assert_ground_truth_outside_output(ground_truth, output_dir)

    def test_ground_truth_equal_to_output_dir_raises(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "planted"
            with self.assertRaises(GroundTruthInsideOutputError):
                assert_ground_truth_outside_output(output_dir, output_dir)

    def test_ground_truth_sibling_path_is_allowed(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "planted"
            ground_truth = Path(tmp) / "ground-truth" / "ground-truth-manifest.json"
            # Must not raise.
            assert_ground_truth_outside_output(ground_truth, output_dir)

    def test_ground_truth_parent_of_output_dir_is_allowed(self):
        # A ground-truth file living in output_dir's PARENT (but not inside
        # output_dir itself) is a legitimate sibling layout -- must not raise.
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp) / "planted"
            ground_truth = Path(tmp) / "ground-truth-manifest.json"
            assert_ground_truth_outside_output(ground_truth, output_dir)


@unittest.skipUnless(_HAS_PIL, "Pillow (PIL) not installed — plant_defects image ops unavailable")
class MislabeledCaptureAntiContaminationTests(unittest.TestCase):
    """The whole reason build_mislabeled() re-encodes instead of byte-copying:
    a byte-copy would make the plant indistinguishable (by hash) from its
    honest donor, letting the duplicate-credit detector "catch" a
    mislabeled-capture instance for the wrong reason. This is the property
    that must hold for the two classes to be measured in isolation."""

    def test_mislabeled_plant_has_unique_sha256_vs_donor(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp)
            src_name = "04-command-palette.png"
            _write_source_png(output_dir / src_name, color=(200, 50, 50))
            donor_hash = sha256_of(output_dir / src_name)

            entry = build_mislabeled(
                output_dir,
                {
                    "source": src_name,
                    "dest": "90-agent-run-delegate-confirmed.png",
                    "claims": "shape:core.agent-run (filename token 'agent-run')",
                    "actually_shows": "command palette",
                },
            )

            self.assertNotEqual(entry["sha256"], donor_hash)
            plant_path = output_dir / entry["filename"]
            self.assertTrue(plant_path.is_file())
            self.assertNotEqual(sha256_of(plant_path), donor_hash)
            # The donor itself must be untouched -- still present, still its
            # own honest file, not overwritten or renamed.
            self.assertEqual(sha256_of(output_dir / src_name), donor_hash)

    def test_mislabeled_plant_pixels_still_match_donor(self):
        # Re-encoding must preserve pixel content (it IS the wrong surface's
        # content -- that's the point of the class) even though bytes differ.
        with tempfile.TemporaryDirectory() as tmp:
            output_dir = Path(tmp)
            src_name = "27-health-surface.png"
            _write_source_png(output_dir / src_name, color=(11, 22, 33))

            entry = build_mislabeled(
                output_dir,
                {
                    "source": src_name,
                    "dest": "91-extract-schema-result.png",
                    "claims": "shape:core.extract (filename token 'extract')",
                    "actually_shows": "health surface",
                },
            )

            donor_pixels = Image.open(output_dir / src_name).convert("RGB").tobytes()
            plant_pixels = Image.open(output_dir / entry["filename"]).convert("RGB").tobytes()
            self.assertEqual(donor_pixels, plant_pixels)


@unittest.skipUnless(_HAS_PIL, "Pillow (PIL) not installed — plant_defects image ops unavailable")
class RegisterDriftTests(unittest.TestCase):
    """Enforces the register's own registrationRule mechanically at the
    tool/register boundary: the plan and the register must name the exact
    same set of classes, or the tool refuses to run."""

    def test_matching_register_does_not_raise(self):
        register = {"classes": [{"id": cid} for cid in CLASS_SOURCE_FILES]}
        verify_register_matches_plan(register)  # must not raise

    def test_register_missing_a_planted_class_raises(self):
        register = {
            "classes": [
                {"id": cid} for cid in CLASS_SOURCE_FILES if cid != "blank-capture"
            ]
        }
        with self.assertRaises(RegisterDriftError) as ctx:
            verify_register_matches_plan(register)
        self.assertIn("blank-capture", str(ctx.exception))

    def test_register_with_an_unplanted_class_raises(self):
        register = {
            "classes": [{"id": cid} for cid in CLASS_SOURCE_FILES]
            + [{"id": "invented-class-nobody-observed"}]
        }
        with self.assertRaises(RegisterDriftError) as ctx:
            verify_register_matches_plan(register)
        self.assertIn("invented-class-nobody-observed", str(ctx.exception))


@unittest.skipUnless(_HAS_PIL, "Pillow (PIL) not installed — plant_defects image ops unavailable")
class MainEndToEndTests(unittest.TestCase):
    """Round-trip main() against a small synthetic evidence set (hermetic --
    does not touch tmp/sandbox-evidence/**) and assert the ground-truth
    manifest records class/claims/actually_shows per planted file."""

    def _run(self, tmp: Path, classes: str | None = None) -> tuple[int, Path, Path]:
        source_dir = tmp / "source-evidence"
        output_dir = tmp / "planted"
        ground_truth_dir = tmp / "ground-truth"
        ground_truth_path = ground_truth_dir / "ground-truth-manifest.json"
        _make_source_evidence(source_dir)

        argv = [
            "--source-dir", str(source_dir),
            "--output-dir", str(output_dir),
            "--ground-truth", str(ground_truth_path),
        ]
        if classes:
            argv += ["--classes", classes]
        rc = main(argv)
        return rc, output_dir, ground_truth_path

    def test_full_plant_run_succeeds_and_writes_manifest(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            rc, output_dir, ground_truth_path = self._run(tmp)
            self.assertEqual(rc, 0)
            self.assertTrue(ground_truth_path.is_file())

            manifest = json.loads(ground_truth_path.read_text(encoding="utf-8"))
            planted = manifest["plantedFiles"]
            # 4 mislabeled + 3 blank + 4 duplicate (2 pairs) = 11, matching
            # the real tempdoc 734 measurement's planted count.
            self.assertEqual(len(planted), 11)

            by_class: dict[str, list[dict]] = {}
            for entry in planted:
                by_class.setdefault(entry["class"], []).append(entry)
            self.assertEqual(len(by_class["mislabeled-capture"]), 4)
            self.assertEqual(len(by_class["blank-capture"]), 3)
            self.assertEqual(len(by_class["duplicate-credit"]), 4)

            for entry in planted:
                self.assertIn("class", entry)
                self.assertIn("filename", entry)
                self.assertIn("claims", entry)
                self.assertIn("actually_shows", entry)
                self.assertIn("sha256", entry)
                self.assertIn("size_bytes", entry)
                # Every planted file must actually exist where claimed.
                self.assertTrue((output_dir / entry["filename"]).is_file(), entry["filename"])

    def test_ground_truth_manifest_not_written_inside_output_dir(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            source_dir = tmp / "source-evidence"
            output_dir = tmp / "planted"
            _make_source_evidence(source_dir)
            bad_ground_truth = output_dir / "ground-truth-manifest.json"

            rc = main([
                "--source-dir", str(source_dir),
                "--output-dir", str(output_dir),
                "--ground-truth", str(bad_ground_truth),
            ])
            self.assertEqual(rc, 1)
            # The guard must fire BEFORE any copy/plant -- output_dir must
            # not even have been created.
            self.assertFalse(output_dir.exists())

    def test_missing_source_file_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            source_dir = tmp / "source-evidence"
            source_dir.mkdir()
            # Deliberately incomplete evidence set -- none of the required
            # source filenames exist.
            (source_dir / "irrelevant-file.txt").write_text("not evidence", encoding="utf-8")
            output_dir = tmp / "planted"
            ground_truth_path = tmp / "ground-truth" / "ground-truth-manifest.json"

            rc = main([
                "--source-dir", str(source_dir),
                "--output-dir", str(output_dir),
                "--ground-truth", str(ground_truth_path),
            ])
            self.assertEqual(rc, 1)
            self.assertFalse(ground_truth_path.exists())

    def test_output_dir_exists_without_force_refuses(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            source_dir = tmp / "source-evidence"
            _make_source_evidence(source_dir)
            output_dir = tmp / "planted"
            output_dir.mkdir()
            (output_dir / "pre-existing.txt").write_text("do not clobber", encoding="utf-8")
            ground_truth_path = tmp / "ground-truth" / "ground-truth-manifest.json"

            rc = main([
                "--source-dir", str(source_dir),
                "--output-dir", str(output_dir),
                "--ground-truth", str(ground_truth_path),
            ])
            self.assertEqual(rc, 2)
            self.assertTrue((output_dir / "pre-existing.txt").is_file())

    def test_classes_subset_plants_only_requested_class(self):
        with tempfile.TemporaryDirectory() as tmp_str:
            tmp = Path(tmp_str)
            rc, _output_dir, ground_truth_path = self._run(tmp, classes="duplicate-credit")
            self.assertEqual(rc, 0)
            manifest = json.loads(ground_truth_path.read_text(encoding="utf-8"))
            classes_present = {entry["class"] for entry in manifest["plantedFiles"]}
            self.assertEqual(classes_present, {"duplicate-credit"})
            self.assertEqual(len(manifest["plantedFiles"]), 4)


if __name__ == "__main__":
    unittest.main()
