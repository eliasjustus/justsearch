"""Tests for the shared corpus-axis resolver (tempdoc 751 P.5 WP-1).

resolve_corpus_axis is the ONE canonical corpus-path resolution every index-cache
caller runs, so the publisher (warm) and the adopter (serve-eval-backend wrapper)
bind identical selector-key components by construction (finding 2). The subdir
shape (datasets/<cell>/corpus-dir) that silently disabled the cache (finding 1)
must now resolve to a usable axis.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from jseval import corpora as corpora_mod
from jseval import ingest as ingest_mod
from jseval import _paths as paths_mod
from jseval.corpus_identity import corpus_signature
from jseval import raw_corpus_manifest as rcm
from jseval.index_identity import (
    CorpusAxis,
    _norm_path,
    compute_selector,
    resolve_corpus_axis,
)


# --------------------------------------------------------------------------- #
# git repo + models-dir fixtures (mirror test_index_identity.py).
# --------------------------------------------------------------------------- #


def _git(cwd: Path, *args: str) -> None:
    subprocess.run(["git", *args], cwd=str(cwd), check=True, capture_output=True)


@pytest.fixture
def git_repo(tmp_path: Path) -> Path:
    repo = tmp_path / "repo"
    repo.mkdir(parents=True, exist_ok=True)
    _git(repo, "init", "-q")
    _git(repo, "config", "user.email", "t@example.com")
    _git(repo, "config", "user.name", "Tester")
    _git(repo, "config", "commit.gpgsign", "false")
    (repo / "modules").mkdir()
    (repo / "modules" / "Engine.java").write_text("class Engine {}\n", encoding="utf-8")
    _git(repo, "add", "-A")
    _git(repo, "commit", "-q", "-m", "init")
    return repo


def _models_dir(root: Path) -> Path:
    models = root / "models"
    (models / "onnx" / "gte-multilingual-base").mkdir(parents=True, exist_ok=True)
    (models / "splade" / "naver-splade-v3").mkdir(parents=True, exist_ok=True)
    (models / "onnx" / "gte-multilingual-base" / "model_fp16.onnx.sha256").write_text(
        "a" * 64 + "  model_fp16.onnx\n", encoding="utf-8",
    )
    (models / "splade" / "naver-splade-v3" / "model.onnx.sha256").write_text(
        "b" * 64 + "\n", encoding="utf-8",
    )
    return models


def _dataset_dir(root: Path, name: str, *, jsonl: str = '{"_id":"1","text":"hi"}\n') -> Path:
    d = root / name
    d.mkdir(parents=True, exist_ok=True)
    (d / "corpus.jsonl").write_text(jsonl, encoding="utf-8")
    (d / "qrels").mkdir(exist_ok=True)
    (d / "qrels" / "test.tsv").write_text("q\t0\t1\t1\n", encoding="utf-8")
    return d


# --------------------------------------------------------------------------- #
# resolve_corpus_axis matrix (WP-1).
# --------------------------------------------------------------------------- #


def test_axis_explicit_dir_with_jsonl(tmp_path: Path):
    d = _dataset_dir(tmp_path, "corpus")
    axis = resolve_corpus_axis(None, d)
    assert axis == CorpusAxis(d, d, None)


def test_axis_subdir_with_parent_jsonl(tmp_path: Path):
    """Finding 1: the exploded datasets/<cell>/corpus-dir subdir. Watched is the
    subdir; the signature root is the parent that holds corpus.jsonl."""
    parent = _dataset_dir(tmp_path, "cell")
    subdir = parent / "corpus-dir"
    subdir.mkdir()
    axis = resolve_corpus_axis(None, subdir)
    assert axis.watched_dir == subdir
    assert axis.signature_root == parent
    assert axis.reason is None


def test_axis_explicit_garbage_names_both_shapes(tmp_path: Path):
    garbage = tmp_path / "nowhere" / "leaf"
    garbage.mkdir(parents=True)
    axis = resolve_corpus_axis(None, garbage)
    assert axis.watched_dir is None and axis.signature_root is None
    # The reason must name both accepted shapes (dir with corpus.jsonl / child of one).
    assert "corpus axis unresolvable" in axis.reason
    assert "corpus.jsonl" in axis.reason
    assert "child directory" in axis.reason


def test_axis_dataset_local_materialized(tmp_path: Path, monkeypatch):
    """No --corpus-dir, materialized golden dataset: watched = the tmp/eval-corpora
    materialization target (need NOT exist yet); signature root = the dataset root."""
    datasets = tmp_path / "datasets"
    ds_root = _dataset_dir(datasets, "golden/foo")
    materialize_target = tmp_path / "eval-corpora" / "golden" / "foo"  # deliberately absent

    monkeypatch.setattr(corpora_mod, "_default_base_dir", lambda: datasets)
    monkeypatch.setattr(paths_mod, "default_corpus_dir", lambda ds: tmp_path / "eval-corpora" / ds)

    axis = resolve_corpus_axis("golden/foo", None)
    assert axis.watched_dir == materialize_target
    assert not axis.watched_dir.exists()  # materialization happens later -- fine
    assert axis.signature_root == ds_root
    assert axis.reason is None


def test_axis_dataset_local_missing_corpus_jsonl_unresolvable(tmp_path: Path, monkeypatch):
    datasets = tmp_path / "datasets"
    (datasets / "golden" / "bare").mkdir(parents=True)  # no corpus.jsonl
    monkeypatch.setattr(corpora_mod, "_default_base_dir", lambda: datasets)
    monkeypatch.setattr(paths_mod, "default_corpus_dir", lambda ds: tmp_path / "eval-corpora" / ds)
    axis = resolve_corpus_axis("golden/bare", None)
    assert axis.watched_dir is None
    assert "no corpus.jsonl" in axis.reason


def test_axis_raw_files_dataset(tmp_path: Path, monkeypatch):
    """raw_files dataset (metadata.json raw_files:true): watched = <ds>/corpus-dir
    (the real binary files), signature root = the dataset root."""
    datasets = tmp_path / "datasets"
    ds_root = datasets / "mixed" / "bar"
    ds_root.mkdir(parents=True)
    (ds_root / "metadata.json").write_text(json.dumps({"raw_files": True}), encoding="utf-8")
    (ds_root / "corpus-dir").mkdir()
    monkeypatch.setattr(corpora_mod, "_default_base_dir", lambda: datasets)

    axis = resolve_corpus_axis("mixed/bar", None)
    assert axis.watched_dir == ds_root / "corpus-dir"
    assert axis.signature_root == ds_root
    assert axis.reason is None


def test_raw_selector_uses_strict_manifest_components(git_repo: Path, tmp_path: Path):
    base = tmp_path / "datasets"
    root = base / "mixed" / "raw"
    corpus = root / "corpus-dir"
    corpus.mkdir(parents=True)
    (corpus / "document.pdf").write_bytes(b"raw bytes")
    (root / "metadata.json").write_text(json.dumps({"raw_files": True}), encoding="utf-8")
    context = rcm.resolve_raw_corpus_context("mixed/raw", base_dir=base)
    env = {"JUSTSEARCH_MODELS_DIR": str(_models_dir(tmp_path))}

    selector = compute_selector(
        git_repo, None, env, dataset_name="mixed/raw", raw_context=context,
    )

    assert selector.key is not None
    assert selector.components["corpus_signature"] == context.identity.digest
    assert selector.components["corpus_kind"] == "raw-files"
    assert selector.components["corpus_manifest_schema"] == rcm.RAW_CORPUS_MANIFEST_SCHEMA
    assert selector.components["corpus_file_count"] == 1
    assert selector.components["corpus_admission_policy"] == dict(context.admission_policy)
    assert selector.components["corpus_dir_path"] == _norm_path(corpus)

    changed_context = rcm.resolve_raw_corpus_context(
        "mixed/raw", base_dir=base,
        env_overrides={"JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "pdf"},
    )
    changed_env = {
        **env,
        "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS": "pdf",
    }
    changed_selector = compute_selector(
        git_repo, None, changed_env, dataset_name="mixed/raw",
        raw_context=changed_context,
    )
    assert changed_selector.key != selector.key
    assert changed_selector.components["corpus_signature"] == context.identity.digest


def test_axis_beir_name_unresolvable(tmp_path: Path):
    axis = resolve_corpus_axis("scifact", None)
    assert axis.watched_dir is None and axis.signature_root is None
    assert "not a local" in axis.reason


def test_axis_both_none_unresolvable():
    axis = resolve_corpus_axis(None, None)
    assert axis.watched_dir is None and axis.signature_root is None
    assert "no dataset name and no --corpus-dir" in axis.reason


# --------------------------------------------------------------------------- #
# Finding-2 regression: shared resolver => identical keys by construction.
# --------------------------------------------------------------------------- #


def test_finding2_same_chain_dir_same_axis_and_key(git_repo: Path, tmp_path: Path):
    """The SAME chain dir input yields the SAME axis (structural) AND the SAME
    selector key at publish-time and adopt-time -- both call the one resolver."""
    parent = _dataset_dir(tmp_path, "cell")
    chain_dir = parent / "corpus-dir"
    chain_dir.mkdir()

    # Warm (publisher) and wrapper (adopter) both run resolve_corpus_axis on the
    # same input -> identical CorpusAxis (frozen dataclass structural equality).
    axis_publish = resolve_corpus_axis(None, chain_dir)
    axis_adopt = resolve_corpus_axis(None, chain_dir)
    assert axis_publish == axis_adopt

    env = {"JUSTSEARCH_MODELS_DIR": str(_models_dir(tmp_path))}
    key_publish = compute_selector(git_repo, chain_dir, env, dataset_name=None).key
    key_adopt = compute_selector(git_repo, chain_dir, env, dataset_name=None).key
    assert key_publish is not None
    assert key_publish == key_adopt


# --------------------------------------------------------------------------- #
# Selector: subdir shape now yields a key (finding 1 fixed at the selector).
# --------------------------------------------------------------------------- #


def test_selector_subdir_shape_yields_key(git_repo: Path, tmp_path: Path):
    parent = _dataset_dir(tmp_path, "cell")
    subdir = parent / "corpus-dir"
    subdir.mkdir()
    env = {"JUSTSEARCH_MODELS_DIR": str(_models_dir(tmp_path))}

    sel = compute_selector(git_repo, subdir, env)
    # Previously unavailable (corpus_signature(subdir) was None); now a real key.
    assert sel.key is not None and sel.unavailable_reason is None
    # Its corpus_signature equals the PARENT-root signature (where corpus.jsonl lives).
    assert sel.components["corpus_signature"] == corpus_signature(parent)
    # corpus_dir_path binds the normalized WATCHED subdir, not the parent.
    assert sel.components["corpus_dir_path"] == _norm_path(subdir)


def test_selector_dataset_name_only_resolves_axis(git_repo: Path, tmp_path: Path, monkeypatch):
    """dataset_name alone (no --corpus-dir) resolves the axis and includes the
    corpus signature -- the run-path case where only --dataset is given."""
    datasets = tmp_path / "datasets"
    ds_root = _dataset_dir(datasets, "golden/foo")
    monkeypatch.setattr(corpora_mod, "_default_base_dir", lambda: datasets)
    monkeypatch.setattr(paths_mod, "default_corpus_dir", lambda ds: tmp_path / "eval-corpora" / ds)
    env = {"JUSTSEARCH_MODELS_DIR": str(_models_dir(tmp_path))}

    sel = compute_selector(git_repo, None, env, dataset_name="golden/foo")
    assert sel.key is not None
    assert sel.components["corpus_signature"] == corpus_signature(ds_root)
    assert sel.components["corpus_dir_path"] == _norm_path(tmp_path / "eval-corpora" / "golden/foo")
