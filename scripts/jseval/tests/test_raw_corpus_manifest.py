from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path

import pytest

from jseval import raw_corpus_manifest as rcm


_ALPHA_SHA = "8ed3f6ad685b959ead7022518e1af76cd816f8e8ec7ccdda1ed4018e8f2223f8"
_BETA_SHA = "f2c82decdd7181cf98945929a62598db7e6b477e11f6e0eb0ae97020eff151ad"
_PINNED_DIGEST = "18cf97e86b9d09b39f04e2a79ec9c903f1a04d007ef0fd66af7d34665bea62b4"


def _write_pinned_corpus(root: Path) -> None:
    (root / "a").mkdir(parents=True)
    (root / "a" / "é.txt").write_bytes(b"alpha")
    (root / "b.txt").write_bytes(b"beta\n")


def _mutable_manifest(identity: rcm.RawCorpusIdentity) -> dict:
    return json.loads(json.dumps(identity.manifest, ensure_ascii=False))


def _one_file_manifest(path: object = "only.txt") -> dict:
    return {
        "schema": rcm.RAW_CORPUS_MANIFEST_SCHEMA,
        "files": [{"path": path, "size_bytes": 1, "sha256": hashlib.sha256(b"x").hexdigest()}],
    }


def test_build_is_pinned_canonical_and_root_independent(tmp_path):
    first = tmp_path / "first"
    second = tmp_path / "relocated" / "corpus"
    _write_pinned_corpus(first)
    _write_pinned_corpus(second)

    identity = rcm.build_raw_manifest(first)
    relocated = rcm.build_raw_manifest(second)

    expected = {
        "schema": "jseval.raw-corpus-manifest.v1",
        "files": [
            {"path": "a/é.txt", "size_bytes": 5, "sha256": _ALPHA_SHA},
            {"path": "b.txt", "size_bytes": 5, "sha256": _BETA_SHA},
        ],
    }
    assert identity.manifest == expected
    assert identity.digest == _PINNED_DIGEST
    assert identity.file_count == 2
    assert identity.total_bytes == 10
    assert relocated.manifest == identity.manifest
    assert relocated.digest == identity.digest

    canonical = json.dumps(
        expected,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")
    assert hashlib.sha256(canonical).hexdigest() == identity.digest


def test_identity_manifest_is_deeply_immutable_but_json_serializable(tmp_path):
    _write_pinned_corpus(tmp_path)
    identity = rcm.build_raw_manifest(tmp_path)

    json.dumps(identity.manifest, ensure_ascii=False)
    with pytest.raises(TypeError):
        identity.manifest["schema"] = "changed"
    with pytest.raises(TypeError):
        identity.manifest["files"].append({})
    with pytest.raises(TypeError):
        identity.manifest["files"][0]["path"] = "changed"


def test_validate_returns_same_strict_identity(tmp_path):
    _write_pinned_corpus(tmp_path)
    built = rcm.build_raw_manifest(tmp_path)

    validated = rcm.validate_raw_manifest(tmp_path, _mutable_manifest(built))

    assert validated == built


@pytest.mark.parametrize("missing", ["schema", "files"])
def test_manifest_rejects_missing_top_level_field(tmp_path, missing):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest = _one_file_manifest()
    del manifest[missing]

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


def test_manifest_rejects_extra_top_level_field(tmp_path):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest = _one_file_manifest()
    manifest["dataset"] = "legacy metadata is not strict"

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


@pytest.mark.parametrize("missing", ["path", "size_bytes", "sha256"])
def test_manifest_rejects_missing_file_field(tmp_path, missing):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest = _one_file_manifest()
    del manifest["files"][0][missing]

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


def test_manifest_rejects_extra_file_field(tmp_path):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest = _one_file_manifest()
    manifest["files"][0]["source"] = "not part of the strict row"

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


@pytest.mark.parametrize(
    ("location", "value"),
    [
        ("manifest", []),
        ("files", ()),
        ("row", []),
        ("path", 7),
        ("size_bytes", True),
        ("size_bytes", "1"),
        ("size_bytes", -1),
        ("sha256", 7),
        ("sha256", "A" * 64),
        ("sha256", "a" * 63),
    ],
)
def test_manifest_rejects_wrong_field_types_and_values(tmp_path, location, value):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest: object = _one_file_manifest()
    if location == "manifest":
        manifest = value
    elif location == "files":
        manifest["files"] = value
    elif location == "row":
        manifest["files"][0] = value
    else:
        manifest["files"][0][location] = value

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


@pytest.mark.parametrize(
    "path",
    [
        "",
        "/absolute.txt",
        "//server/share.txt",
        "C:/drive.txt",
        "folder\\windows.txt",
        "nul\x00name.txt",
        ".",
        "./file.txt",
        "folder/../escape.txt",
        "folder//empty.txt",
        "folder/",
        "e\u0301.txt",
    ],
)
def test_manifest_rejects_noncanonical_or_escaping_paths(tmp_path, path):
    tmp_path.joinpath("only.txt").write_bytes(b"x")

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, _one_file_manifest(path))


def test_manifest_rejects_non_utf8_path_with_contract_exception(tmp_path):
    tmp_path.joinpath("only.txt").write_bytes(b"x")

    with pytest.raises(rcm.RawCorpusManifestError, match="valid UTF-8"):
        rcm.validate_raw_manifest(tmp_path, _one_file_manifest("bad\ud800.txt"))


def test_manifest_rejects_duplicate_paths_before_filesystem_comparison(tmp_path):
    manifest = _one_file_manifest()
    manifest["files"].append(dict(manifest["files"][0]))

    with pytest.raises(rcm.RawCorpusManifestError, match="duplicate"):
        rcm.validate_raw_manifest(tmp_path, manifest)


@pytest.mark.parametrize(
    "paths",
    [
        ("A.txt", "a.txt"),
        ("straße.txt", "strasse.txt"),
        ("Σ.txt", "ς.txt"),
    ],
)
def test_manifest_rejects_case_and_unicode_casefold_collisions(tmp_path, paths):
    manifest = {
        "schema": rcm.RAW_CORPUS_MANIFEST_SCHEMA,
        "files": [
            {"path": path, "size_bytes": 1, "sha256": "a" * 64} for path in paths
        ],
    }

    with pytest.raises(rcm.RawCorpusManifestError, match="collision"):
        rcm.validate_raw_manifest(tmp_path, manifest)


def test_manifest_rejects_rows_not_sorted_by_utf8_path_bytes(tmp_path):
    (tmp_path / "a.txt").write_bytes(b"a")
    (tmp_path / "b.txt").write_bytes(b"b")
    manifest = _mutable_manifest(rcm.build_raw_manifest(tmp_path))
    manifest["files"].reverse()

    with pytest.raises(rcm.RawCorpusManifestError, match="sorted"):
        rcm.validate_raw_manifest(tmp_path, manifest)


@pytest.mark.parametrize("schema", ["jseval.raw-corpus-manifest.v0", None, True])
def test_manifest_rejects_wrong_schema(tmp_path, schema):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    manifest = _one_file_manifest()
    manifest["schema"] = schema

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, manifest)


def test_builder_and_validator_reject_empty_corpus(tmp_path):
    with pytest.raises(rcm.RawCorpusManifestError, match="at least one"):
        rcm.build_raw_manifest(tmp_path)
    with pytest.raises(rcm.RawCorpusManifestError, match="at least one"):
        rcm.validate_raw_manifest(
            tmp_path, {"schema": rcm.RAW_CORPUS_MANIFEST_SCHEMA, "files": []}
        )


def test_validator_rejects_missing_and_extra_files(tmp_path):
    only = tmp_path / "only.txt"
    only.write_bytes(b"x")
    manifest = _mutable_manifest(rcm.build_raw_manifest(tmp_path))

    only.unlink()
    (tmp_path / "replacement.txt").write_bytes(b"x")
    with pytest.raises(rcm.RawCorpusManifestError, match="file set mismatch"):
        rcm.validate_raw_manifest(tmp_path, manifest)


def test_validator_rejects_size_and_hash_mismatch(tmp_path):
    only = tmp_path / "only.txt"
    only.write_bytes(b"x")
    manifest = _mutable_manifest(rcm.build_raw_manifest(tmp_path))

    wrong_size = json.loads(json.dumps(manifest))
    wrong_size["files"][0]["size_bytes"] = 2
    with pytest.raises(rcm.RawCorpusManifestError, match="size mismatch"):
        rcm.validate_raw_manifest(tmp_path, wrong_size)

    wrong_hash = json.loads(json.dumps(manifest))
    wrong_hash["files"][0]["sha256"] = "0" * 64
    with pytest.raises(rcm.RawCorpusManifestError, match="SHA-256 mismatch"):
        rcm.validate_raw_manifest(tmp_path, wrong_hash)


def test_builder_rejects_non_nfc_filesystem_path(tmp_path):
    candidate = tmp_path / "e\u0301.txt"
    candidate.write_bytes(b"x")
    if candidate.name == "é.txt":
        pytest.skip("filesystem normalized the decomposed test name")

    with pytest.raises(rcm.RawCorpusManifestError, match="exact NFC"):
        rcm.build_raw_manifest(tmp_path)


@pytest.mark.skipif(os.name == "nt", reason="Windows paths cannot contain undecodable bytes")
def test_builder_rejects_non_utf8_filesystem_path(tmp_path):
    raw_path = os.fsencode(tmp_path) + b"/bad-\xff.txt"
    descriptor = os.open(raw_path, os.O_CREAT | os.O_WRONLY, 0o600)
    os.close(descriptor)

    with pytest.raises(rcm.RawCorpusManifestError, match="valid UTF-8"):
        rcm.build_raw_manifest(tmp_path)


def test_builder_rescan_rejects_file_set_change(tmp_path, monkeypatch):
    tmp_path.joinpath("only.txt").write_bytes(b"x")
    original = rcm._hash_stable_file
    changed = False

    def hash_then_add(path, root):
        nonlocal changed
        result = original(path, root)
        if not changed:
            changed = True
            root.joinpath("late.txt").write_bytes(b"late")
        return result

    monkeypatch.setattr(rcm, "_hash_stable_file", hash_then_add)
    with pytest.raises(rcm.RawCorpusManifestError, match="file set changed"):
        rcm.build_raw_manifest(tmp_path)


def test_builder_rescan_rejects_hashed_file_stat_change(tmp_path, monkeypatch):
    only = tmp_path / "only.txt"
    only.write_bytes(b"x")
    original = rcm._hash_stable_file
    changed = False

    def hash_then_mutate(path, root):
        nonlocal changed
        result = original(path, root)
        if not changed:
            changed = True
            path.write_bytes(b"changed after hash")
        return result

    monkeypatch.setattr(rcm, "_hash_stable_file", hash_then_mutate)
    with pytest.raises(rcm.RawCorpusManifestError, match="changed after hashing"):
        rcm.build_raw_manifest(tmp_path)


def test_builder_rejects_symlink_even_when_it_points_inside_root(tmp_path):
    target = tmp_path / "target.txt"
    target.write_bytes(b"x")
    link = tmp_path / "link.txt"
    try:
        link.symlink_to(target)
    except (NotImplementedError, OSError):
        pytest.skip("symlinks are not available to this test process")

    with pytest.raises(rcm.RawCorpusManifestError, match="symlink or reparse"):
        rcm.build_raw_manifest(tmp_path)


def test_builder_rejects_symlink_escape(tmp_path):
    root = tmp_path / "root"
    root.mkdir()
    outside = tmp_path / "outside.txt"
    outside.write_bytes(b"outside")
    try:
        root.joinpath("escape.txt").symlink_to(outside)
    except (NotImplementedError, OSError):
        pytest.skip("symlinks are not available to this test process")

    with pytest.raises(rcm.RawCorpusManifestError, match="symlink or reparse"):
        rcm.build_raw_manifest(root)


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="FIFO creation is unsupported")
def test_builder_rejects_nonregular_entry(tmp_path):
    fifo = tmp_path / "named-pipe"
    try:
        os.mkfifo(fifo)
    except OSError:
        pytest.skip("FIFO creation is unavailable to this test process")

    with pytest.raises(rcm.RawCorpusManifestError, match="not a regular file"):
        rcm.build_raw_manifest(tmp_path)


def test_legacy_realdocs_projection_is_explicit_and_drops_source_extras(tmp_path):
    _write_pinned_corpus(tmp_path)
    legacy = {
        "dataset": "mixed/realdocs-v1",
        "version": 1,
        "files": [
            {
                "file": "b.txt",
                "source": "govdocs1",
                "archive_member": "original/b.txt",
                "size_bytes": 5,
                "sha256": _BETA_SHA,
            },
            {
                "file": "a/é.txt",
                "source": "napierone",
                "size_bytes": 5,
                "sha256": _ALPHA_SHA,
                "future_legacy_metadata": {"ignored": True},
            },
        ],
    }

    with pytest.raises(rcm.RawCorpusManifestError):
        rcm.validate_raw_manifest(tmp_path, legacy)

    projected = rcm.project_legacy_raw_manifest(legacy)
    assert projected == {
        "schema": rcm.RAW_CORPUS_MANIFEST_SCHEMA,
        "files": [
            {"path": "a/é.txt", "size_bytes": 5, "sha256": _ALPHA_SHA},
            {"path": "b.txt", "size_bytes": 5, "sha256": _BETA_SHA},
        ],
    }
    assert rcm.validate_raw_manifest(tmp_path, projected).digest == _PINNED_DIGEST


def test_legacy_projection_rejects_missing_required_source_field():
    legacy = {"files": [{"file": "a.txt", "size_bytes": 1}]}

    with pytest.raises(rcm.RawCorpusManifestError, match="missing"):
        rcm.project_legacy_raw_manifest(legacy)


def test_legacy_projection_rejects_non_utf8_path_with_contract_exception():
    legacy = {
        "files": [
            {
                "file": "bad\ud800.txt",
                "size_bytes": 1,
                "sha256": hashlib.sha256(b"x").hexdigest(),
            }
        ]
    }

    with pytest.raises(rcm.RawCorpusManifestError, match="valid UTF-8"):
        rcm.project_legacy_raw_manifest(legacy)


def _raw_dataset(base: Path, *, pointer: object = ...):
    root = base / "mixed" / "raw-x"
    corpus = root / "corpus-dir"
    corpus.mkdir(parents=True)
    (corpus / "one.pdf").write_bytes(b"one")
    metadata = {"raw_files": True}
    if pointer is not ...:
        metadata["manifest"] = pointer
    (root / "metadata.json").write_text(json.dumps(metadata), encoding="utf-8")
    return root, corpus


def test_resolver_builds_when_pointer_absent_and_binds_content_and_names(tmp_path):
    base = tmp_path / "datasets"
    _, corpus = _raw_dataset(base)
    first = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)

    (corpus / "one.pdf").write_bytes(b"changed")
    changed = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)
    assert changed.identity.digest != first.identity.digest
    (corpus / "one.pdf").rename(corpus / "renamed.pdf")
    renamed = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)
    assert renamed.identity.digest != changed.identity.digest
    (corpus / "added.docx").write_bytes(b"added")
    added = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)
    assert added.identity.digest != renamed.identity.digest
    (corpus / "added.docx").unlink()
    deleted = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)
    assert deleted.identity.digest == renamed.identity.digest


def test_resolver_validates_strict_pointer_and_projects_legacy_pointer(tmp_path):
    base = tmp_path / "datasets"
    root, corpus = _raw_dataset(base)
    strict = rcm.build_raw_manifest(corpus)
    strict_path = tmp_path / "strict.json"
    strict_path.write_text(json.dumps(strict.manifest), encoding="utf-8")
    (root / "metadata.json").write_text(
        json.dumps({"raw_files": True, "manifest": "strict.json"}), encoding="utf-8"
    )
    context = rcm.resolve_raw_corpus_context(
        "mixed/raw-x", base_dir=base, repo_root=tmp_path,
    )
    assert context.identity == strict
    assert context.to_corpus_identity()["manifest_pointer"] == "strict.json"

    legacy_path = tmp_path / "legacy.json"
    legacy_path.write_text(json.dumps({"files": [{
        "file": "one.pdf", "size_bytes": 3,
        "sha256": hashlib.sha256(b"one").hexdigest(), "source": "legacy",
    }]}), encoding="utf-8")
    (root / "metadata.json").write_text(
        json.dumps({"raw_files": True, "manifest": "legacy.json"}), encoding="utf-8"
    )
    projected = rcm.resolve_raw_corpus_context(
        "mixed/raw-x", base_dir=base, repo_root=tmp_path,
    )
    assert projected.identity == strict
    assert projected.manifest_pointer == "legacy.json"


@pytest.mark.parametrize("pointer", ["", None, "missing.json"])
def test_resolver_rejects_missing_or_invalid_manifest_pointer(tmp_path, pointer):
    base = tmp_path / "datasets"
    _raw_dataset(base, pointer=pointer)
    with pytest.raises(rcm.RawCorpusManifestError, match="pointer"):
        rcm.resolve_raw_corpus_context(
            "mixed/raw-x", base_dir=base, repo_root=tmp_path,
        )


def test_resolver_rejects_different_explicit_dir(tmp_path):
    base = tmp_path / "datasets"
    _raw_dataset(base)
    other = tmp_path / "other"
    other.mkdir()
    with pytest.raises(rcm.RawCorpusManifestError, match="declared corpus-dir"):
        rcm.resolve_raw_corpus_context("mixed/raw-x", other, base_dir=base)


@pytest.mark.parametrize("pointer", ["../escape.json", "/absolute.json", "dir\\file.json"])
def test_resolver_rejects_non_repo_relative_manifest_pointer(tmp_path, pointer):
    base = tmp_path / "datasets"
    _raw_dataset(base, pointer=pointer)

    with pytest.raises(rcm.RawCorpusManifestError, match="repo-relative|escape"):
        rcm.resolve_raw_corpus_context(
            "mixed/raw-x", base_dir=base, repo_root=tmp_path,
        )


@pytest.mark.parametrize("override", ["", "0" * 64])
def test_resolver_rejects_present_unequal_signature_override(tmp_path, override):
    base = tmp_path / "datasets"
    _raw_dataset(base)
    with pytest.raises(rcm.RawCorpusManifestError, match="disagrees"):
        rcm.resolve_raw_corpus_context(
            "mixed/raw-x", base_dir=base,
            env_overrides={"JUSTSEARCH_CORPUS_SIGNATURE": override},
        )


def test_resolver_allows_equal_effective_signature_override(tmp_path, monkeypatch):
    base = tmp_path / "datasets"
    _raw_dataset(base)
    context = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)
    monkeypatch.setenv("JUSTSEARCH_CORPUS_SIGNATURE", "host-value-must-lose")
    same = rcm.resolve_raw_corpus_context(
        "mixed/raw-x", base_dir=base,
        env_overrides={"JUSTSEARCH_CORPUS_SIGNATURE": context.identity.digest},
    )
    assert same.identity == context.identity


def test_context_refuses_dataset_root_and_admission_policy_mismatch(tmp_path):
    base = tmp_path / "datasets"
    _, corpus = _raw_dataset(base)
    context = rcm.resolve_raw_corpus_context("mixed/raw-x", base_dir=base)

    with pytest.raises(rcm.RawCorpusManifestError, match="dataset"):
        rcm.validate_raw_corpus_context(
            context, expected_dataset="mixed/other",
        )
    with pytest.raises(rcm.RawCorpusManifestError, match="explicit"):
        rcm.validate_raw_corpus_context(
            context, expected_dataset="mixed/raw-x", explicit_dir=tmp_path / "other",
        )
    with pytest.raises(rcm.RawCorpusManifestError, match="admission policy"):
        rcm.validate_raw_corpus_context(
            context,
            {"JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "pdf"},
            expected_dataset="mixed/raw-x",
            explicit_dir=corpus,
        )


def test_admission_policy_is_set_semantic_and_part_of_public_identity(tmp_path):
    base = tmp_path / "datasets"
    _raw_dataset(base)
    first = rcm.resolve_raw_corpus_context(
        "mixed/raw-x",
        base_dir=base,
        env_overrides={"JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": " PDF,docx,pdf "},
    )
    equivalent = rcm.resolve_raw_corpus_context(
        "mixed/raw-x",
        base_dir=base,
        env_overrides={"JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "docx,pdf"},
    )

    assert first.admission_policy == equivalent.admission_policy
    assert first.to_corpus_identity()["admission_policy"] == {
        "JUSTSEARCH_INGESTION_SKIP_PATTERNS": "default",
        "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "docx,pdf",
        "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES": "default",
    }
