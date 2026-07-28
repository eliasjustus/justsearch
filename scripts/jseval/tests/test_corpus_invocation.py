"""Invocation recording for corpus-materialization commands (tempdoc 767 §R.4-1)."""
from __future__ import annotations

import json
from pathlib import Path

from jseval import corpus_invocation


def _write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )


def _inject_fixture(tmp_path: Path):
    """A minimal real-host + fabricated-gold pair that `corpus-inject-real` can assemble."""
    real = tmp_path / "real"
    gold = tmp_path / "gold"
    real.mkdir()
    gold.mkdir()
    _write_jsonl(real / "corpus.jsonl", [
        {"_id": f"real-{i}", "title": f"Host {i}", "text": (f"Real host private sentence {i}. " * 80)}
        for i in range(6)
    ])
    _write_jsonl(gold / "docs.jsonl", [{
        "_id": "gold-1", "title": "Quenby ferrolite descriptor",
        "text": "The fabricated Quenby attribute is ochre ferrolite 0047. Another linked fact follows.",
    }])
    (gold / "queries.json").write_text(json.dumps([{
        "query": "Which invented material belongs to the marsh engineer?",
        "answer": "ochre ferrolite 0047",
        "evidence_ids": ["gold-1"],
        "question_type": "semantic",
    }]), encoding="utf-8")
    (gold / "meta.json").write_text(json.dumps({
        "type_axis": "prose",
        "generation_provenance": {"method": "procedural-fabricated", "seed": 7},
    }), encoding="utf-8")
    return real, gold


def test_record_invocation_writes_required_fields_and_appends(tmp_path, monkeypatch):
    """The lowest-level record writer captures argv, seed, git_sha, timestamp and appends."""
    out = tmp_path / "mixed" / "fixture"
    monkeypatch.setattr(
        corpus_invocation.sys, "argv",
        ["jseval", "corpus-inject-real", "--name", "fixture", "--seed", "707"],
    )

    sidecar = corpus_invocation.record_invocation(
        out,
        command="corpus-inject-real",
        params={"name": "fixture", "seed": 707, "datasets_dir": Path("/some/dir")},
        seeds=[707],
        input_digests={"real_source_sha256": "a" * 64},
        output_digests={"corpus_signature": "c" * 64},
    )
    assert sidecar == out / corpus_invocation.FILENAME
    assert sidecar.is_file()

    lines = sidecar.read_text(encoding="utf-8").splitlines()
    assert len(lines) == 1
    record = json.loads(lines[0])
    assert record["schema"] == "707-corpus-invocation.v1"
    assert record["command"] == "corpus-inject-real"
    assert record["argv"][:2] == ["jseval", "corpus-inject-real"]
    assert record["seeds"] == [707]
    # git_sha key is always present (value is a 40-char sha in a repo checkout, or None otherwise).
    assert "git_sha" in record
    assert record["git_sha"] is None or len(record["git_sha"]) == 40
    # timestamp is UTC ISO-8601 and parses.
    assert record["timestamp"].startswith("20")
    from datetime import datetime
    datetime.fromisoformat(record["timestamp"])
    # Path-valued params are coerced to strings (JSON-serializable).
    assert record["params"]["datasets_dir"] == str(Path("/some/dir"))
    assert record["input_digests"]["real_source_sha256"] == "a" * 64
    assert record["output_digests"]["corpus_signature"] == "c" * 64
    assert record["cwd"] and record["jseval_package_path"].endswith("jseval")

    # A second invocation appends rather than overwriting.
    corpus_invocation.record_invocation(
        out, command="corpus-inject-real", params={"seed": 42}, seeds=[42],
    )
    assert len(sidecar.read_text(encoding="utf-8").splitlines()) == 2


def test_record_invocation_never_raises_on_write_failure(tmp_path, monkeypatch):
    """A write failure is a WARN returning None, never an exception that breaks the command."""
    def _boom(*_a, **_k):
        raise OSError("disk full")

    monkeypatch.setattr(corpus_invocation.json, "dumps", _boom)
    result = corpus_invocation.record_invocation(
        tmp_path / "out", command="corpus-fetch-clerc", params={"seed": 1}, seeds=[1],
    )
    assert result is None


def test_corpus_inject_real_cli_writes_invocation_sidecar(tmp_path):
    """End-to-end: the real CLI path drops invocations.v1.jsonl next to the materialized corpus."""
    from click.testing import CliRunner

    from jseval.cli import main

    real, gold = _inject_fixture(tmp_path)
    datasets = tmp_path / "datasets"
    commitment = tmp_path / "throwaway-commitment"

    result = CliRunner().invoke(main, [
        "corpus-inject-real",
        "--real-corpus", str(real),
        "--gold-source", str(gold),
        "--name", "fixture-cell",
        "--seed", "707",
        "--n-distractors", "3",
        "--real-source-id", "fixture-real-v1",
        "--license-id", "test-only",
        "--datasets-dir", str(datasets),
        "--commitment-dir", str(commitment),
    ])
    assert result.exit_code == 0, result.output

    sidecar = datasets / "mixed" / "fixture-cell" / corpus_invocation.FILENAME
    assert sidecar.is_file(), "corpus-inject-real did not write an invocation record"
    record = json.loads(sidecar.read_text(encoding="utf-8").splitlines()[-1])
    assert record["command"] == "corpus-inject-real"
    assert record["params"]["name"] == "fixture-cell"
    assert record["params"]["seed"] == 707
    assert record["seeds"] == [707]
    # `argv` captures the real OS process argv (here pytest's, under CliRunner); the resolved
    # command+params above are the faithful invocation. argv capture itself is asserted by the
    # sys.argv-monkeypatched unit test above.
    assert isinstance(record["argv"], list) and record["argv"]
    # Reused digests (no re-hashing): host-pool sha + assembled digest as inputs, signature as output.
    assert len(record["input_digests"]["real_source_sha256"]) == 64
    assert record["input_digests"]["assembled_digest"]
    assert record["output_digests"]["corpus_signature"]


def test_corpus_fetch_cli_writes_invocation_sidecar(tmp_path, monkeypatch):
    """The corpus-fetch-* path records the invocation too (mocked fetch, no network)."""
    from click.testing import CliRunner

    from jseval import corpus_fetch
    from jseval.cli import main

    # _write_recipe writes under REPO_ROOT/scripts/jseval/666-corpora — redirect to a scratch tree.
    monkeypatch.setattr("jseval._paths.REPO_ROOT", tmp_path / "scratch-repo")

    def _fake_fetch_clerc(td, *, seed, n_queries, n_docs):
        out = Path(td)
        _write_jsonl(out / "docs.jsonl", [
            {"_id": "d1", "title": "A", "text": "alpha body text here"},
            {"_id": "d2", "title": "B", "text": "beta body text here"},
        ])
        (out / "queries.json").write_text(
            json.dumps([{"query": "q", "answer": "a", "evidence_ids": ["d1"]}]), encoding="utf-8")
        prov = {"method": "huggingface-direct-sample", "source": "clerc",
                "seed": seed, "n_docs": 2, "n_queries": 1}
        (out / "meta.json").write_text(
            json.dumps({"generation_provenance": prov}), encoding="utf-8")
        return prov

    monkeypatch.setattr(corpus_fetch, "fetch_clerc_sample", _fake_fetch_clerc)

    result = CliRunner().invoke(main, [
        "corpus-fetch-clerc", "--name", "fetch-cell", "--seed", "707",
        "--n-queries", "1", "--datasets-dir", str(tmp_path / "datasets"),
    ])
    assert result.exit_code == 0, result.output

    sidecar = tmp_path / "datasets" / "mixed" / "fetch-cell" / corpus_invocation.FILENAME
    assert sidecar.is_file()
    record = json.loads(sidecar.read_text(encoding="utf-8").splitlines()[-1])
    assert record["command"] == "corpus-fetch-clerc"
    assert record["seeds"] == [707]
    assert record["output_digests"]["corpus_signature"]
    # A fetch samples fresh: no input-pool sha, and the record says so.
    assert record["input_digests"] == {}
    assert "no input-pool sha" in record["digests_note"]
