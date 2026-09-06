from __future__ import annotations

import contextlib
import hashlib
from io import BytesIO
import json
from pathlib import Path
import re
import tarfile
import zipfile

import pytest

from jseval import dataset_cache
from jseval.cli import main
from jseval.commands import command_groups
from jseval.commands.inventory import INVENTORY_PATH
from jseval.format_breadth_corpus import FormatBreadthError, materialize_format_breadth


_HEX_SHA = re.compile(r"^[0-9a-f]{64}$")
_UNOBSERVED_NOTE = (
    "Run corpus-fetch-format-breadth --write-manifest once, review all observed source "
    "and member hashes, then commit the resulting manifest. Ordinary materialization "
    "fails closed until this is observed."
)


def _sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _zip_bytes(entries: list[tuple[str, bytes]]) -> bytes:
    stream = BytesIO()
    with zipfile.ZipFile(stream, "w") as archive:
        for name, raw in entries:
            archive.writestr(name, raw)
    return stream.getvalue()


def _write_zip(path: Path, entries: list[tuple[str, bytes]]) -> None:
    path.write_bytes(_zip_bytes(entries))


def _write_tar(path: Path, entries: list[tuple[str, bytes]]) -> None:
    with tarfile.open(path, "w:gz") as archive:
        for name, raw in entries:
            info = tarfile.TarInfo(name)
            info.size = len(raw)
            info.mtime = 0
            archive.addfile(info, BytesIO(raw))


def _message(index: int) -> bytes:
    return (
        f"From: Person {index} <person{index}@domain{index:02d}.example>\r\n"
        f"Subject: planted {index}\r\n\r\nBody {index}\r\n"
    ).encode()


def _write_recipe(path: Path, source_dirs: dict[str, Path], *, bad_hash: bool = False) -> None:
    recipe = {
        "schema": "jseval.format-breadth-recipe.v1",
        "dataset": "mixed/format-breadth-v1",
        "enron_selection_method": "sha256-ranked-one-per-sender-domain-then-fill-v1",
        "enron_selection_seed": 897,
        "output_counts": {".eml": 16, ".rtf": 9, ".zip": 8},
        "coverage_qualification": {
            extension: {
                "sample_count": count,
                "source_collection_count": 1,
                "producer_count": 1,
                "producer_count_basis": (
                    "conservative collection-level proxy; producer diversity not established"
                ),
                "claim": "single-source deterministic real-input characterization",
            }
            for extension, count in {".eml": 16, ".rtf": 9, ".zip": 8}.items()
        },
        "sources": {
            "enron": {
                "cache_fetcher": "enron-raw",
                "cache_params": {"url": "https://example.invalid/enron_mail_20150507.tar.gz"},
                "urls": ["https://example.invalid/enron_mail_20150507.tar.gz"],
                "source_revision": "test",
                "sha256": {
                    "enron_mail_20150507.tar.gz": (
                        "0" * 64 if bad_hash else
                        _sha((source_dirs["enron-raw"] /
                              "enron_mail_20150507.tar.gz").read_bytes())
                    )
                },
            },
            "govdocs": {
                "urls": ["https://example.invalid/000.zip", "https://example.invalid/001.zip"],
                "source_revision": "test",
                "sha256": {
                    name: _sha((source_dirs["format-breadth-govdocs"] / name).read_bytes())
                    for name in ("000.zip", "001.zip")
                },
            },
            "napier": {
                "urls": [
                    "https://example.invalid/ZIP-DEFLATE-tiny.zip",
                    "https://example.invalid/ZIP-DEFLATE-tiny_zip_hashes.txt",
                ],
                "source_revision": "test",
                "sha256": {
                    name: _sha((source_dirs["format-breadth-napier"] / name).read_bytes())
                    for name in ("ZIP-DEFLATE-tiny.zip", "ZIP-DEFLATE-tiny_zip_hashes.txt")
                },
            },
        },
        "licenses": {"enron": {"distribution": "fetch-only"}},
    }
    path.write_text(json.dumps(recipe), encoding="utf-8")


def _plant_sources(tmp_path: Path, monkeypatch) -> tuple[dict[str, Path], Path, Path]:
    source_dirs = {
        name: tmp_path / name
        for name in ("enron-raw", "format-breadth-govdocs", "format-breadth-napier")
    }
    for directory in source_dirs.values():
        directory.mkdir()

    emails = [(f"maildir/user{index}/inbox/{index}", _message(index)) for index in range(20)]
    _write_tar(source_dirs["enron-raw"] / "enron_mail_20150507.tar.gz", emails)
    _write_zip(
        source_dirs["format-breadth-govdocs"] / "000.zip",
        [(f"000/{index:03d}.rtf", f"{{\\rtf1 planted {index}}}".encode()) for index in range(5)]
        + [("000/readme.txt", b"not selected")],
    )
    _write_zip(
        source_dirs["format-breadth-govdocs"] / "001.zip",
        [(f"001/{index:03d}.RTF", f"{{\\rtf1 planted {index + 5}}}".encode())
         for index in range(4)],
    )
    inner = [
        (f"set/{index:02d}.zip", _zip_bytes([(f"payload/{index:02d}.txt", f"zip {index}".encode())]))
        for index in range(10)
    ]
    napier_archive = source_dirs["format-breadth-napier"] / "ZIP-DEFLATE-tiny.zip"
    _write_zip(napier_archive, inner)
    (source_dirs["format-breadth-napier"] / "ZIP-DEFLATE-tiny_zip_hashes.txt").write_text(
        "Generating Hash for File 'ZIP-tiny.zip'\n\n"
        f"SHA256            32               {_sha(napier_archive.read_bytes())}\n",
        encoding="utf-8",
    )

    @contextlib.contextmanager
    def planted_cache(fetcher, params, *, filenames, populate):
        del params, filenames, populate
        yield source_dirs[fetcher]

    monkeypatch.setattr(dataset_cache, "cached_dir", planted_cache)
    recipe = tmp_path / "recipe.json"
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps({
        "schema": "jseval.format-breadth-manifest.v1",
        "status": "unobserved",
        "dataset": "mixed/format-breadth-v1",
        "note": _UNOBSERVED_NOTE,
    }), encoding="utf-8")
    _write_recipe(recipe, source_dirs)
    return source_dirs, recipe, manifest


def _materialize(
    tmp_path: Path, source_dirs: dict[str, Path], recipe: Path, manifest: Path, *,
    name: str = "out", write_manifest: bool = True,
) -> tuple[Path, dict]:
    realdocs = tmp_path / "realdocs"
    realdocs.mkdir(exist_ok=True)
    realdocs_file = realdocs / "unrelated.pdf"
    realdocs_file.write_bytes(b"realdocs unrelated")
    realdocs_manifest = tmp_path / "realdocs-manifest.json"
    realdocs_manifest.write_text(json.dumps({
        "dataset": "mixed/realdocs-v1",
        "files": [{
            "file": realdocs_file.name,
            "size_bytes": realdocs_file.stat().st_size,
            "sha256": _sha(realdocs_file.read_bytes()),
        }],
    }), encoding="utf-8")
    output = tmp_path / name
    metadata = materialize_format_breadth(
        dataset_dir=output, realdocs_dir=realdocs,
        realdocs_manifest_path=realdocs_manifest, recipe_path=recipe,
        manifest_path=manifest, write_manifest=write_manifest,
    )
    return output, metadata


def test_materializes_exact_raw_files_and_is_deterministic(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    first, metadata = _materialize(tmp_path, source_dirs, recipe, manifest)
    second, second_metadata = _materialize(
        tmp_path, source_dirs, recipe, manifest, name="out-2", write_manifest=False,
    )

    assert metadata == second_metadata
    assert metadata["raw_files"] is True
    assert metadata["corpus_size"] == 33
    assert metadata["extension_counts"] == {".eml": 16, ".rtf": 9, ".zip": 8}
    assert all(
        row["producer_count"] == row["source_collection_count"] == 1
        for row in metadata["coverage_qualification"].values()
    )
    first_files = sorted((path.name, path.read_bytes()) for path in (first / "corpus-dir").iterdir())
    second_files = sorted((path.name, path.read_bytes()) for path in (second / "corpus-dir").iterdir())
    assert first_files == second_files
    observed = json.loads(manifest.read_text(encoding="utf-8"))
    assert observed["status"] == "observed"
    assert len({row["sender_domain"] for row in observed["sources"]["enron"]["members"]}) == 16
    assert len(observed["sources"]["govdocs"]["members"]) == 9
    assert [row["source_member"] for row in observed["sources"]["napier"]["members"]] == [
        f"set/{index:02d}.zip" for index in range(8)
    ]
    assert "ZIP-DEFLATE-tiny_zip_hashes.txt" in observed["source_sha256"]["napier"]
    assert (observed["napier_sidecar_declared_archive_sha256"] ==
            observed["source_sha256"]["napier"]["ZIP-DEFLATE-tiny.zip"])
    planted_email_bytes = {_message(index) for index in range(20)}
    assert all(raw in planted_email_bytes for name, raw in first_files if name.endswith(".eml"))
    assert json.loads((first / "metadata.json").read_text(encoding="utf-8"))["raw_files"] is True


def test_unobserved_manifest_refuses_ordinary_materialization(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    with pytest.raises(FormatBreadthError, match="requires --write-manifest"):
        _materialize(tmp_path, source_dirs, recipe, manifest, write_manifest=False)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("schema", "wrong", "schema/dataset mismatch"),
        ("dataset", "mixed/other", "schema/dataset mismatch"),
        ("status", "typo", "status must be exactly"),
        ("extra", True, "exact first-run stub"),
    ],
)
def test_write_manifest_rejects_malformed_unobserved_stub(
    tmp_path, monkeypatch, field, value, message,
):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload[field] = value
    manifest.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(FormatBreadthError, match=message):
        _materialize(tmp_path, source_dirs, recipe, manifest, write_manifest=True)


@pytest.mark.parametrize("bad_name", ["../escape.rtf", "folder\\escape.rtf", "/absolute.rtf"])
def test_rejects_unsafe_archive_member_paths(tmp_path, monkeypatch, bad_name):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    if "\\" in bad_name:
        archive = source_dirs["enron-raw"] / "enron_mail_20150507.tar.gz"
        _write_tar(archive, [(bad_name, _message(99))] +
                   [(f"maildir/{index}", _message(index)) for index in range(20)])
    else:
        archive = source_dirs["format-breadth-govdocs"] / "000.zip"
        _write_zip(archive, [(bad_name, b"{\\rtf1 bad}")] +
                   [(f"000/{index}.rtf", b"{\\rtf1 ok}") for index in range(5)])
    _write_recipe(recipe, source_dirs)
    with pytest.raises(FormatBreadthError, match="canonical safe POSIX path"):
        _materialize(tmp_path, source_dirs, recipe, manifest)


def test_rejects_duplicate_archive_member_paths(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    archive = source_dirs["format-breadth-govdocs"] / "000.zip"
    with pytest.warns(UserWarning, match="Duplicate name"):
        _write_zip(archive, [("000/dup.rtf", b"one"), ("000/dup.rtf", b"two")])
    _write_recipe(recipe, source_dirs)
    with pytest.raises(FormatBreadthError, match="duplicate member path"):
        _materialize(tmp_path, source_dirs, recipe, manifest)


def test_rejects_pinned_source_hash_drift(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    _write_recipe(recipe, source_dirs, bad_hash=True)
    with pytest.raises(FormatBreadthError, match="pinned source hash drift"):
        _materialize(tmp_path, source_dirs, recipe, manifest)


def test_rejects_observed_member_hash_drift(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    _materialize(tmp_path, source_dirs, recipe, manifest)
    observed = json.loads(manifest.read_text(encoding="utf-8"))
    observed["sources"]["enron"]["members"][0]["sha256"] = "0" * 64
    manifest.write_text(json.dumps(observed), encoding="utf-8")
    with pytest.raises(FormatBreadthError, match="member manifest drift"):
        _materialize(
            tmp_path, source_dirs, recipe, manifest, name="out-2", write_manifest=False,
        )


def test_rejects_sha_overlap_with_materialized_realdocs(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    realdocs = tmp_path / "realdocs"
    realdocs.mkdir()
    with zipfile.ZipFile(source_dirs["format-breadth-govdocs"] / "000.zip") as archive:
        (realdocs / "same.bin").write_bytes(archive.read("000/000.rtf"))
    same = realdocs / "same.bin"
    realdocs_manifest = tmp_path / "realdocs-manifest.json"
    realdocs_manifest.write_text(json.dumps({
        "dataset": "mixed/realdocs-v1",
        "files": [{
            "file": same.name,
            "size_bytes": same.stat().st_size,
            "sha256": _sha(same.read_bytes()),
        }],
    }), encoding="utf-8")
    with pytest.raises(FormatBreadthError, match="overlaps materialized realdocs-v1"):
        materialize_format_breadth(
            dataset_dir=tmp_path / "out", realdocs_dir=realdocs, recipe_path=recipe,
            realdocs_manifest_path=realdocs_manifest,
            manifest_path=manifest, write_manifest=True,
        )
    assert json.loads(manifest.read_text(encoding="utf-8"))["status"] == "unobserved"


def test_rejects_realdocs_tree_that_does_not_match_immutable_manifest(tmp_path, monkeypatch):
    source_dirs, recipe, manifest = _plant_sources(tmp_path, monkeypatch)
    realdocs = tmp_path / "realdocs"
    realdocs.mkdir()
    realdocs_file = realdocs / "unrelated.pdf"
    realdocs_file.write_bytes(b"drifted")
    realdocs_manifest = tmp_path / "realdocs-manifest.json"
    realdocs_manifest.write_text(json.dumps({
        "dataset": "mixed/realdocs-v1",
        "files": [{
            "file": realdocs_file.name,
            "size_bytes": len(b"original"),
            "sha256": _sha(b"original"),
        }],
    }), encoding="utf-8")

    with pytest.raises(FormatBreadthError, match="immutable manifest"):
        materialize_format_breadth(
            dataset_dir=tmp_path / "out",
            realdocs_dir=realdocs,
            realdocs_manifest_path=realdocs_manifest,
            recipe_path=recipe,
            manifest_path=manifest,
            write_manifest=True,
        )


def test_command_is_registered_and_locked_in_inventory():
    assert "corpus-fetch-format-breadth" in main.commands
    assert command_groups()["corpus-fetch-format-breadth"] == "corpus"
    inventory = json.loads(INVENTORY_PATH.read_text(encoding="utf-8"))
    assert {"name": "corpus-fetch-format-breadth", "group": "corpus"} in inventory


def test_committed_recipe_and_observed_manifest_are_internally_consistent():
    corpus_dir = Path(__file__).parents[1] / "666-corpora" / "format-breadth-v1"
    recipe = json.loads((corpus_dir / "recipe.json").read_text(encoding="utf-8"))
    manifest = json.loads((corpus_dir / "manifest.v1.json").read_text(encoding="utf-8"))

    recipe_sha = _sha(
        json.dumps(
            recipe, ensure_ascii=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
    )
    assert manifest["status"] == "observed"
    assert manifest["recipe_sha256"] == recipe_sha
    assert manifest["output_counts"] == recipe["output_counts"] == {
        ".eml": 16,
        ".rtf": 9,
        ".zip": 8,
    }
    assert all(
        row["producer_count"] == row["source_collection_count"] == 1
        and row["claim"] == "single-source deterministic real-input characterization"
        for row in recipe["coverage_qualification"].values()
    )
    assert (
        manifest["napier_sidecar_declared_archive_sha256"]
        == manifest["source_sha256"]["napier"]["ZIP-DEFLATE-tiny.zip"]
    )

    expected = {"enron": 16, "govdocs": 9, "napier": 8}
    all_outputs: list[str] = []
    for source, expected_count in expected.items():
        members = manifest["sources"][source]["members"]
        assert len(members) == expected_count
        assert all(_HEX_SHA.fullmatch(row["sha256"]) for row in members)
        all_outputs.extend(row["output"] for row in members)
    assert len(all_outputs) == len(set(all_outputs)) == 33
    assert len(
        {row["sender_domain"] for row in manifest["sources"]["enron"]["members"]}
    ) == 16
