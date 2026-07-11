"""Tests for dataset_cache.py -- shared, cross-worktree dataset-fetch cache (tempdoc 709).

Every test here opts back into a cache root explicitly (via `JUSTSEARCH_DATASET_CACHE` pointed
at `tmp_path`), overriding the suite-wide `_disable_shared_dataset_cache_by_default` autouse
fixture in `conftest.py` -- see that fixture's docstring for why the default matters.
"""

from __future__ import annotations

import json

import pytest

from jseval import dataset_cache


def _populate_two_files(names):
    def _populate(dest):
        (dest / names[0]).write_text("alpha content", encoding="utf-8")
        (dest / names[1]).write_text("beta content", encoding="utf-8")
    return _populate


def test_cache_miss_populates_and_persists(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["a.txt", "b.txt"]
    calls = {"n": 0}

    def populate(dest):
        calls["n"] += 1
        (dest / names[0]).write_text("alpha", encoding="utf-8")
        (dest / names[1]).write_text("beta", encoding="utf-8")

    assert dataset_cache.lookup("demo", {"k": "v"}, filenames=names) is None

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=populate) as d:
        assert (d / names[0]).read_text(encoding="utf-8") == "alpha"
        assert (d / names[1]).read_text(encoding="utf-8") == "beta"
    assert calls["n"] == 1

    # Persisted: a fresh lookup (simulating a new process/worktree) finds it without populate.
    hit = dataset_cache.lookup("demo", {"k": "v"}, filenames=names)
    assert hit is not None
    assert (hit / names[0]).read_text(encoding="utf-8") == "alpha"


def test_cache_hit_never_calls_populate_again(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["x.txt"]

    def populate_once(dest):
        (dest / names[0]).write_text("first", encoding="utf-8")

    with dataset_cache.cached_dir("demo", {"k": 1}, filenames=names, populate=populate_once) as d1:
        content1 = (d1 / names[0]).read_text(encoding="utf-8")

    def populate_should_not_run(dest):
        raise AssertionError("populate() must not be called on a cache hit")

    with dataset_cache.cached_dir("demo", {"k": 1}, filenames=names,
                                   populate=populate_should_not_run) as d2:
        content2 = (d2 / names[0]).read_text(encoding="utf-8")

    assert content1 == content2 == "first"


def test_different_params_are_different_cache_entries(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["y.txt"]

    with dataset_cache.cached_dir("demo", {"seed": 1}, filenames=names,
                                   populate=lambda d: (d / names[0]).write_text("one")) as d1:
        pass
    with dataset_cache.cached_dir("demo", {"seed": 2}, filenames=names,
                                   populate=lambda d: (d / names[0]).write_text("two")) as d2:
        pass

    assert d1 != d2
    assert (d1 / names[0]).read_text() == "one"
    assert (d2 / names[0]).read_text() == "two"


def test_signature_mismatch_is_treated_as_miss_and_refetches(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["z.txt"]

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names,
                                   populate=lambda d: (d / names[0]).write_text("original")) as d:
        entry_dir = d

    # Corrupt the cached content in place -- the sha256 in signature.json no longer matches.
    (entry_dir / names[0]).write_text("CORRUPTED", encoding="utf-8")
    assert dataset_cache.lookup("demo", {"k": "v"}, filenames=names) is None

    calls = {"n": 0}

    def repopulate(dest):
        calls["n"] += 1
        (dest / names[0]).write_text("refetched", encoding="utf-8")

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=repopulate) as d2:
        assert (d2 / names[0]).read_text(encoding="utf-8") == "refetched"
    assert calls["n"] == 1

    # And the corrupted entry has been overwritten, not left beside a new one.
    hit = dataset_cache.lookup("demo", {"k": "v"}, filenames=names)
    assert (hit / names[0]).read_text(encoding="utf-8") == "refetched"


def test_missing_signature_file_is_a_miss(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["a.txt"]
    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names,
                                   populate=lambda d: (d / names[0]).write_text("x")) as d:
        (d / "signature.json").unlink()
    assert dataset_cache.lookup("demo", {"k": "v"}, filenames=names) is None


def test_missing_member_file_is_a_miss(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["a.txt", "b.txt"]
    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names,
                                   populate=_populate_two_files(names)) as d:
        (d / names[1]).unlink()
    assert dataset_cache.lookup("demo", {"k": "v"}, filenames=names) is None


def test_atomic_layout_no_partial_or_tmp_dirs_survive(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["a.txt"]

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names,
                                   populate=lambda d: (d / names[0]).write_text("x")) as d:
        entry_dir = d

    fetcher_dir = entry_dir.parent
    children = list(fetcher_dir.iterdir())
    # Only the final published entry directory remains -- no leftover ".tmp-" staging dirs.
    assert children == [entry_dir]
    assert not entry_dir.name.startswith(".")
    assert (entry_dir / "signature.json").is_file()
    recorded = json.loads((entry_dir / "signature.json").read_text(encoding="utf-8"))
    assert recorded["signature"] is not None
    assert recorded["filenames"] == names


def test_store_raises_on_populate_failure_and_cleans_up_tmp_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    names = ["a.txt"]

    def broken_populate(dest):
        raise RuntimeError("network exploded")

    with pytest.raises(RuntimeError, match="network exploded"):
        dataset_cache.store("demo", {"k": "v"}, filenames=names, populate=broken_populate)

    fetcher_dir = tmp_path / "demo"
    # No leftover tmp staging directory after a failed populate.
    assert not fetcher_dir.is_dir() or list(fetcher_dir.iterdir()) == []


def test_disabled_mode_is_a_passthrough_with_no_cache_io(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", "0")
    assert dataset_cache.cache_root() is None
    names = ["a.txt"]
    calls = {"n": 0}

    def populate(dest):
        calls["n"] += 1
        (dest / names[0]).write_text("ephemeral", encoding="utf-8")

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=populate) as d:
        content = (d / names[0]).read_text(encoding="utf-8")
        ephemeral_dir = d
    assert content == "ephemeral"
    assert calls["n"] == 1
    # Ephemeral fallback dir is cleaned up on exit (nothing durable to reuse next time).
    assert not ephemeral_dir.exists()

    # A second call with caching disabled populates again (no cross-call reuse possible).
    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=populate):
        pass
    assert calls["n"] == 2


def test_empty_string_env_also_disables(monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", "")
    assert dataset_cache.cache_root() is None


def test_main_checkout_unresolvable_falls_back_to_ephemeral_fetch(monkeypatch):
    """When JUSTSEARCH_DATASET_CACHE is unset and the main-checkout resolution itself fails,
    cache_root() must return None (never raise) -- the fetch must still succeed via a direct,
    ephemeral, uncached fetch (fail OPEN, tempdoc 709 pinned constraint b)."""
    monkeypatch.delenv("JUSTSEARCH_DATASET_CACHE", raising=False)

    def _boom():
        raise OSError("simulated unresolvable main checkout")

    monkeypatch.setattr(dataset_cache, "main_repo_root", _boom)
    assert dataset_cache.cache_root() is None

    names = ["a.txt"]
    calls = {"n": 0}

    def populate(dest):
        calls["n"] += 1
        (dest / names[0]).write_text("ok", encoding="utf-8")

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=populate) as d:
        assert (d / names[0]).read_text(encoding="utf-8") == "ok"
    assert calls["n"] == 1


def test_cache_root_unwritable_falls_back_to_ephemeral_fetch(tmp_path, monkeypatch):
    """A cache root that resolves but can't be written to (permissions, read-only mount) must
    also fail OPEN to a direct fetch, not raise and abort the whole operation."""
    unwritable_parent = tmp_path / "not-a-real-writable-root"
    unwritable_parent.write_text("this is a FILE, not a dir -- mkdir under it must fail")
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(unwritable_parent / "cache"))

    names = ["a.txt"]
    calls = {"n": 0}

    def populate(dest):
        calls["n"] += 1
        (dest / names[0]).write_text("ok", encoding="utf-8")

    with dataset_cache.cached_dir("demo", {"k": "v"}, filenames=names, populate=populate) as d:
        assert (d / names[0]).read_text(encoding="utf-8") == "ok"
    assert calls["n"] == 1


def test_apply_ir_datasets_home_sets_env_when_cache_enabled(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    monkeypatch.delenv("IR_DATASETS_HOME", raising=False)
    dataset_cache.apply_ir_datasets_home()
    assert __import__("os").environ["IR_DATASETS_HOME"] == str(tmp_path / "ir_datasets")


def test_apply_ir_datasets_home_is_noop_when_disabled(monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", "0")
    monkeypatch.delenv("IR_DATASETS_HOME", raising=False)
    dataset_cache.apply_ir_datasets_home()
    assert "IR_DATASETS_HOME" not in __import__("os").environ


def test_apply_ir_datasets_home_respects_existing_override(tmp_path, monkeypatch):
    monkeypatch.setenv("JUSTSEARCH_DATASET_CACHE", str(tmp_path))
    monkeypatch.setenv("IR_DATASETS_HOME", "/already/set/by/caller")
    dataset_cache.apply_ir_datasets_home()
    assert __import__("os").environ["IR_DATASETS_HOME"] == "/already/set/by/caller"
