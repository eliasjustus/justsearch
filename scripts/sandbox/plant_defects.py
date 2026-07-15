#!/usr/bin/env python3
"""plant_defects.py -- plants known-bad sandbox-evidence artefacts of registered
defect classes (governance/sandbox-defect-classes.v1.json) into a COPY of a
round's evidence directory, so we can measure which verification tier
(scripts/sandbox/check_coverage.py, the round itself, or an independent
reader) actually catches them. This is the "give the round a bite" instrument
from tempdoc 734 Part F: every other verification mechanism in this repo
(hooks, governance gates, registered logic seams) already has to prove it
catches a known-bad input before it is trusted; the sandbox round had none.

Promoted from a one-off hand-run measurement script (tempdoc 734 Part G.6 /
H.3: "The planting script is preserved so the measurement stays repeatable --
otherwise Part F's own falsifier ('a mechanism whose measured catch rate is
zero gets deleted') is unenforceable"). See
governance/sandbox-defect-classes.v1.json for the three registered classes,
their real-round precedents, and the observed-only registration rule this
script's class plan is cross-checked against at startup.

Requires Pillow (`pip install Pillow` if missing -- same optional dependency
scripts/jseval/jseval/corpus_generate.py already uses via the jseval `scan`
extra). This script fails closed with that remedy at import time rather than
silently skipping mislabeled-capture / blank-capture planting.

Two hard design constraints carried over verbatim from the original
measurement script (tempdoc 734 F.7), both ENFORCED here, not left to caller
discipline:

1. Ground truth must never be written inside the planted evidence directory.
   "A plant the round can recognise as a plant measures nothing... ground-truth
   bookkeeping must live host-side and must never be staged into the sandbox."
   --ground-truth is REFUSED if it resolves inside --output-dir. See
   assert_ground_truth_outside_output().

2. mislabeled-capture instances are built by RE-ENCODING the source image's
   pixels (a fresh PIL re-save), never by a raw byte-copy. See
   build_mislabeled()'s docstring for why this is load-bearing: a byte-copy
   would leave the plant byte-identical to its honestly-labelled donor, so
   find_duplicate_token_collisions() (check_coverage.py) would flag the pair
   as a duplicate-credit collision -- a real mechanism, but firing for a
   reason that has nothing to do with mislabeling, contaminating BOTH
   classes' measured catch rates. Do not "simplify" this back to
   shutil.copyfile.

Exit codes: 0 success. 1 a design/business-rule guard fired (ground-truth
location, register/plan drift, --source-dir missing a file this plant plan
needs). 2 a usage/I-O error (bad args, unreadable register/source dir,
--output-dir exists without --force).

Pure stdlib except for Pillow (image re-encode); no network access.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import sys
import zlib
from pathlib import Path

try:
    from PIL import Image
except ImportError as exc:  # pragma: no cover - environment guard, not a code path
    print(
        "ERROR: plant_defects.py requires Pillow to re-encode mislabeled-capture "
        f"pixels (`pip install Pillow`). Import failed: {exc}",
        file=sys.stderr,
    )
    sys.exit(2)


DEFAULT_REGISTER_REL = "governance/sandbox-defect-classes.v1.json"


# ---------------------------------------------------------------------------
# Defect-class plant plans. Calibrated to round-4-shaped evidence (tempdoc
# 734): each source filename below is a REAL, honestly-labelled capture from
# tmp/sandbox-evidence/round4-2026-07-15/. --source-dir must contain files of
# these names for the corresponding class to be plantable; a missing file
# fails closed (missing_source_files()) rather than silently skipping.
# ---------------------------------------------------------------------------

MISLABELED_PLANTS = [
    {
        "source": "04-command-palette.png",
        "dest": "90-agent-run-delegate-confirmed.png",
        "claims": "shape:core.agent-run (filename token 'agent-run')",
        "actually_shows": "command palette",
    },
    {
        "source": "27-health-surface.png",
        "dest": "91-extract-schema-result.png",
        "claims": "shape:core.extract (filename token 'extract')",
        "actually_shows": "health surface",
    },
    {
        "source": "32-library-surface.png",
        "dest": "92-free-chat-response-rendered.png",
        "claims": "shape:core.free-chat (filename token 'free-chat')",
        "actually_shows": "library surface",
    },
    {
        "source": "16-security-panel.png",
        "dest": "93-workflow-run-completed-view.png",
        "claims": "shape:core.workflow-run (filename token 'workflow-run')",
        "actually_shows": "security panel",
    },
]

BLANK_PLANTS = [
    {
        "dest": "98-activity-surface-blank.png",
        "size_px": (640, 400),
        "color": (250, 250, 250),
        "pad_bytes": 0,
        "claims": "surface:core.activity-surface (filename token 'activity')",
        "description": "flat off-white rectangle, no UI chrome, under the 16 KiB size floor",
    },
    {
        "dest": "100-presentation-gallery-blank.png",
        "size_px": (640, 400),
        "color": (32, 32, 32),
        "pad_bytes": 0,
        "claims": "surface:core.presentation-gallery-surface (filename token 'presentation-gallery')",
        "description": "flat dark rectangle, no UI chrome, under the 16 KiB size floor",
    },
    {
        "dest": "99-command-palette-blank-noisy.png",
        "size_px": (640, 400),
        "color": (245, 245, 245),
        "pad_bytes": 20000,
        "claims": "surface:core.command-palette (filename token 'command-palette')",
        "description": (
            "flat near-white rectangle, no UI chrome, PADDED past the 16 KiB floor with an "
            "inert tEXt chunk (no visible pixel content added) -- probes whether the size "
            "floor detects blankness or merely file size"
        ),
    },
]

DUPLICATE_PLANTS = [
    {
        "source": "09-settings-surface.png",
        "pair": [
            ("94-dup-settings-recheck.png", "surface:core.settings-surface (filename token 'settings')"),
            ("95-dup-help-recheck.png", "surface:core.help-surface (filename token 'help')"),
        ],
    },
    {
        "source": "33-browse-surface.png",
        "pair": [
            ("96-dup-browse-recheck.png", "surface:core.browse-surface (filename token 'browse')"),
            ("97-dup-memory-recheck.png", "surface:core.memory-surface (filename token 'memory')"),
        ],
    },
]

CLASS_SOURCE_FILES: dict[str, list[str]] = {
    "mislabeled-capture": sorted({p["source"] for p in MISLABELED_PLANTS}),
    "blank-capture": [],  # synthesized from scratch; no --source-dir dependency
    "duplicate-credit": sorted({p["source"] for p in DUPLICATE_PLANTS}),
}


# ---------------------------------------------------------------------------
# Guards
# ---------------------------------------------------------------------------


class GroundTruthInsideOutputError(ValueError):
    """--ground-truth resolves inside --output-dir (tempdoc 734 F.7 constraint)."""


class RegisterDriftError(ValueError):
    """This script's plant plan and governance/sandbox-defect-classes.v1.json disagree."""


def assert_ground_truth_outside_output(ground_truth_path: Path, output_dir: Path) -> None:
    """Refuse to run if the ground-truth answer key would be reachable from
    inside the planted evidence directory.

    tempdoc 734 F.7: "A plant the round can recognise as a plant measures
    nothing. If a seeded defect is distinguishable from a real artefact --
    by filename convention, by a marker, by living in a manifest the round
    can read -- the round's catch rate is theatre. Therefore: ground-truth
    bookkeeping must live host-side and must never be staged into the
    sandbox. This is a hard constraint on the design, not an implementation
    detail."

    Resolves both paths (Path.resolve(), not string-prefix comparison -- a
    relative ".." segment or symlink can defeat a naive prefix check) and
    checks real containment. Raises GroundTruthInsideOutputError; does not
    return a bool, so a caller cannot forget to check a return value.
    """
    gt = ground_truth_path.resolve()
    out = output_dir.resolve()
    if gt == out or out in gt.parents:
        raise GroundTruthInsideOutputError(
            f"--ground-truth ({gt}) resolves inside --output-dir ({out}). Ground truth "
            "must live OUTSIDE the planted evidence directory -- a reader who can reach "
            "the answer key is not being measured (tempdoc 734 F.7). Pick a sibling path "
            f"for --ground-truth, e.g. {out.parent / (out.name + '-ground-truth.json')}"
        )


def registered_class_ids(register: dict) -> set[str]:
    return {entry["id"] for entry in register.get("classes", [])}


def verify_register_matches_plan(register: dict) -> None:
    """Cross-check this script's plant plan against the register's declared
    classes so the two cannot silently drift apart -- a class implemented
    here but never registered would violate the register's own
    'never invent a class' rule without anyone noticing; a class registered
    but never implemented would be a stale, unplantable row.
    """
    registered = registered_class_ids(register)
    implemented = set(CLASS_SOURCE_FILES)
    unregistered = sorted(implemented - registered)
    unimplemented = sorted(registered - implemented)
    if unregistered or unimplemented:
        raise RegisterDriftError(
            "plant_defects.py's plant plan and "
            f"{DEFAULT_REGISTER_REL} have drifted: "
            f"classes planted but not registered={unregistered!r}, "
            f"classes registered but not planted={unimplemented!r}. "
            "Never invent a class -- see the register's registrationRule."
        )


def missing_source_files(output_dir: Path, class_ids: list[str]) -> dict[str, list[str]]:
    """{class_id: [missing source filenames]} for every requested class whose
    plant plan depends on a --source-dir file that is not present in
    output_dir (post-copy). Empty dict means every requested class is
    plantable against this evidence set.
    """
    missing: dict[str, list[str]] = {}
    for class_id in class_ids:
        needed = CLASS_SOURCE_FILES.get(class_id, [])
        gone = [f for f in needed if not (output_dir / f).is_file()]
        if gone:
            missing[class_id] = gone
    return missing


# ---------------------------------------------------------------------------
# Planting
# ---------------------------------------------------------------------------


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_mislabeled(output_dir: Path, plan_entry: dict) -> dict:
    """Build one mislabeled-capture instance by RE-ENCODING the source
    image's pixels under a new filename that claims a different required
    token -- NOT a raw byte-copy.

    Why re-encoding, not byte-copying (carried over verbatim from the
    original measurement script, tempdoc 734): a byte-copy would leave the
    honestly-labelled donor file byte-identical to this plant, so
    find_duplicate_token_collisions() (check_coverage.py) would flag the
    pair as a duplicate-credit collision -- a REAL mechanism, but firing for
    a reason that has nothing to do with mislabeling. That would credit the
    duplicate-credit detector with "catching" a mislabeled-capture instance,
    contaminating both classes' independently measured catch rates.
    Re-encoding (PIL re-save with a non-default compress_level, forcing a
    fresh zlib stream) keeps the pixels effectively identical -- this IS the
    wrong surface's content, which is the entire point of the class -- while
    guaranteeing a different SHA-256, so mislabeled-capture is measured in
    isolation from duplicate-credit. It is also the more realistic failure
    mode: an honest mislabeling mistake produces a NEW capture of the wrong
    screen, not a literal byte-copy of an existing file.

    DO NOT "simplify" this to shutil.copyfile -- see above.
    """
    src = output_dir / plan_entry["source"]
    dst = output_dir / plan_entry["dest"]
    img = Image.open(src)
    img.load()
    # compress_level differs from PIL's default (6) so the zlib stream --
    # and therefore the file bytes -- differs from the source even if a
    # decoder later produced identical pixels from both.
    img.save(dst, format="PNG", compress_level=9)
    src_hash = sha256_of(src)
    dst_hash = sha256_of(dst)
    assert dst_hash != src_hash, (
        "anti-contamination invariant violated: re-encoded mislabeled-capture plant "
        "must not be byte-identical to its source"
    )
    size = dst.stat().st_size
    return {
        "class": "mislabeled-capture",
        "path": str(dst),
        "filename": dst.name,
        "claims": plan_entry["claims"],
        "actually_shows": f"{plan_entry['actually_shows']} (same pixels as {plan_entry['source']})",
        "source_file_pixels_from": plan_entry["source"],
        "note": (
            "re-encoded (not byte-copied) from source so this file's SHA-256 is unique in "
            "the evidence set -- isolates this class from the duplicate-credit mechanism"
        ),
        "size_bytes": size,
        "sha256": dst_hash,
    }


def write_solid_png(path: Path, size_px: tuple[int, int], color: tuple[int, int, int], pad_bytes: int = 0) -> int:
    """Write a flat-colour PNG. If pad_bytes, inflate the file size by
    appending a harmless PNG tEXt ancillary chunk (real decoders/browsers
    ignore unknown tEXt content) -- adds bytes without adding any visible
    pixel content, so a size-floor defence can be probed independently of
    actual blankness. Returns the final file size in bytes.
    """
    img = Image.new("RGB", size_px, color)
    img.save(path, format="PNG", optimize=True)
    if pad_bytes:
        data = path.read_bytes()
        iend_idx = data.rfind(b"IEND") - 4  # start of the IEND chunk (len + type)
        chunk_type = b"tEXt"
        chunk_data = b"Comment\x00" + os.urandom(pad_bytes)
        chunk = (
            struct.pack(">I", len(chunk_data))
            + chunk_type
            + chunk_data
            + struct.pack(">I", zlib.crc32(chunk_type + chunk_data) & 0xFFFFFFFF)
        )
        data = data[:iend_idx] + chunk + data[iend_idx:]
        path.write_bytes(data)
    return path.stat().st_size


def build_blank(output_dir: Path, plan_entry: dict) -> dict:
    path = output_dir / plan_entry["dest"]
    size = write_solid_png(
        path, plan_entry["size_px"], plan_entry["color"], plan_entry.get("pad_bytes", 0)
    )
    return {
        "class": "blank-capture",
        "path": str(path),
        "filename": path.name,
        "claims": plan_entry["claims"],
        "actually_shows": plan_entry["description"],
        "size_bytes": size,
        "sha256": sha256_of(path),
    }


def build_duplicate_pair(output_dir: Path, plan_entry: dict) -> list[dict]:
    """Byte-copy (NOT re-encode -- the opposite of build_mislabeled, on
    purpose) one source file to two new filenames, each claiming a different
    required token. The whole point of this class is that the bytes ARE
    identical.
    """
    src = output_dir / plan_entry["source"]
    names = [name for name, _token in plan_entry["pair"]]
    for name in names:
        shutil.copyfile(src, output_dir / name)
    digest = sha256_of(output_dir / names[0])
    for other in names[1:]:
        assert sha256_of(output_dir / other) == digest, (
            "duplicate-credit plant invariant violated: collision pair must be byte-identical"
        )
    size = (output_dir / names[0]).stat().st_size
    entries = []
    for name, token in plan_entry["pair"]:
        entries.append(
            {
                "class": "duplicate-credit",
                "path": str(output_dir / name),
                "filename": name,
                "claims": token,
                "actually_shows": (
                    f"identical bytes to source {plan_entry['source']}, shared with the "
                    "other member of this collision pair"
                ),
                "collision_pair": names,
                "source_file_copied_from": plan_entry["source"],
                "size_bytes": size,
                "sha256": digest,
            }
        )
    return entries


def plant_mislabeled(output_dir: Path) -> list[dict]:
    return [build_mislabeled(output_dir, p) for p in MISLABELED_PLANTS]


def plant_blank(output_dir: Path) -> list[dict]:
    return [build_blank(output_dir, p) for p in BLANK_PLANTS]


def plant_duplicates(output_dir: Path) -> list[dict]:
    entries: list[dict] = []
    for p in DUPLICATE_PLANTS:
        entries.extend(build_duplicate_pair(output_dir, p))
    return entries


CLASS_PLANTERS = {
    "mislabeled-capture": plant_mislabeled,
    "blank-capture": plant_blank,
    "duplicate-credit": plant_duplicates,
}


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def find_repo_root(start: Path) -> Path:
    cur = start.resolve()
    for candidate in [cur, *cur.parents]:
        if (candidate / "gradlew.bat").exists():
            return candidate
    raise RuntimeError(f"Could not locate repo root (no gradlew.bat found walking up from {start})")


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Plant known-bad sandbox-evidence artefacts of registered defect classes "
            "(governance/sandbox-defect-classes.v1.json) into a COPY of a round's evidence "
            "directory, to measure which verification tier catches them (tempdoc 734 Part F)."
        )
    )
    parser.add_argument(
        "--source-dir",
        required=True,
        help="Path to the round evidence directory to copy FROM. Read-only; never modified.",
    )
    parser.add_argument(
        "--output-dir",
        required=True,
        help="Path to write the planted evidence COPY to. Must not already exist unless --force.",
    )
    parser.add_argument(
        "--ground-truth",
        required=True,
        help="Path to write the ground-truth manifest JSON to. MUST resolve OUTSIDE --output-dir.",
    )
    parser.add_argument(
        "--register",
        default=None,
        help="Override the defect-class register path (testability only; default governance/sandbox-defect-classes.v1.json).",
    )
    parser.add_argument(
        "--classes",
        default=None,
        help="Comma-separated subset of class ids to plant (default: all registered classes).",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Remove and replace --output-dir if it already exists.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    source_dir = Path(args.source_dir)
    output_dir = Path(args.output_dir)
    ground_truth_path = Path(args.ground_truth)

    # Safety-critical guard first, before any filesystem mutation.
    try:
        assert_ground_truth_outside_output(ground_truth_path, output_dir)
    except GroundTruthInsideOutputError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    if not source_dir.is_dir():
        print(f"ERROR: --source-dir {str(source_dir)!r} is not a directory.", file=sys.stderr)
        return 2

    if args.register:
        register_path = Path(args.register)
    else:
        try:
            register_path = find_repo_root(Path(__file__).parent) / DEFAULT_REGISTER_REL
        except RuntimeError as exc:
            print(f"ERROR: {exc}", file=sys.stderr)
            return 2

    try:
        register = json.loads(register_path.read_text(encoding="utf-8"))
    except OSError as exc:
        print(f"ERROR: could not read register {str(register_path)!r}: {exc}", file=sys.stderr)
        return 2
    except json.JSONDecodeError as exc:
        print(f"ERROR: register {str(register_path)!r} is not valid JSON: {exc}", file=sys.stderr)
        return 2

    try:
        verify_register_matches_plan(register)
    except RegisterDriftError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1

    all_ids = sorted(CLASS_SOURCE_FILES)
    if args.classes:
        requested = [c.strip() for c in args.classes.split(",") if c.strip()]
        unknown = [c for c in requested if c not in CLASS_SOURCE_FILES]
        if unknown:
            print(
                f"ERROR: unknown class id(s) {unknown!r}; known classes: {all_ids!r}",
                file=sys.stderr,
            )
            return 2
        class_ids = requested
    else:
        class_ids = all_ids

    if output_dir.exists():
        if not args.force:
            print(
                f"ERROR: --output-dir {str(output_dir)!r} already exists. Pass --force to "
                "replace it, or choose a path that does not exist.",
                file=sys.stderr,
            )
            return 2
        shutil.rmtree(output_dir)

    shutil.copytree(source_dir, output_dir)

    missing = missing_source_files(output_dir, class_ids)
    if missing:
        print(
            "ERROR: --source-dir is missing file(s) this plant plan is calibrated against:",
            file=sys.stderr,
        )
        for class_id, files in sorted(missing.items()):
            print(f"    {class_id}: {files!r}", file=sys.stderr)
        print(
            "This plan is calibrated against round-4-shaped evidence (tempdoc 734); re-run "
            "with a --source-dir containing these files, or plant a narrower --classes subset.",
            file=sys.stderr,
        )
        return 1

    ground_truth: list[dict] = []
    for class_id in class_ids:
        entries = CLASS_PLANTERS[class_id](output_dir)
        ground_truth.extend(entries)
        for entry in entries:
            print(
                f"[{entry['class']}] {entry['filename']}  "
                f"(claims {entry['claims']!r}, {entry['size_bytes']} bytes, "
                f"sha256={entry['sha256'][:12]})"
            )

    ground_truth_path.parent.mkdir(parents=True, exist_ok=True)
    ground_truth_path.write_text(
        json.dumps({"plantedFiles": ground_truth}, indent=2) + "\n", encoding="utf-8"
    )

    print("-" * 72)
    print(
        f"Planted {len(ground_truth)} defect instance(s) across {len(class_ids)} "
        f"class(es) into {output_dir}"
    )
    print(f"Wrote ground truth ({len(ground_truth)} entries) to {ground_truth_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
