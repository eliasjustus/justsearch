"""Tests for `experiments/lucene_query_derivation_q020.py` -- the Q-020 / F-046
deterministic LUCENE-syntax query-variant derivation.

Mirrors `tests/test_corpus_query_variant.py`'s pattern: pure-function rule tests need no
I/O, and the local-source `build_lucene_variant` orchestration is tested against a tiny
synthetic `mixed/`-shaped fixture (never the real ~5000-doc `beir/scifact` corpus -- that
would make this suite network/disk-cache dependent and slow). `materialize_beir_mirror`
(the one function that touches `ir_datasets`) is exercised separately with `ir_datasets`
mocked out, so the whole suite stays hermetic and fast.
"""

from __future__ import annotations

import json
import sys
from collections import Counter
from pathlib import Path
from unittest.mock import MagicMock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "experiments"))

import lucene_query_derivation_q020 as m  # noqa: E402


# ---------------------------------------------------------------------------
# Pure-function rule tests: derive_lucene_query
# ---------------------------------------------------------------------------

class TestDeriveLuceneQuery:
    def test_quote_phrase_wraps_longest_content_run(self):
        df = Counter({"biomaterials": 2, "lack": 5, "inductive": 5, "properties": 5})
        text = "biomaterials lack inductive properties"
        derived, prov = m.derive_lucene_query(text, df)
        assert derived == '"biomaterials lack inductive properties"'
        assert m.RULE_QUOTE_PHRASE in prov["rules_fired"]

    def test_require_rare_term_fires_when_outside_phrase(self):
        # Two disjoint content runs (separated by a stopword) of unequal length: the
        # longer one is quoted; the rarest ELIGIBLE term sits in the other one, outside
        # the quoted span, so it gets its own `+` clause.
        df = Counter({"gadget": 5, "widget": 5, "assembly": 5, "zephyr": 1})
        text = "gadget widget assembly for zephyr"
        derived, prov = m.derive_lucene_query(text, df)
        assert '"gadget widget assembly"' in derived
        assert "+zephyr" in derived
        assert prov["rules_fired"] == [m.RULE_QUOTE_PHRASE, m.RULE_REQUIRE_RARE_TERM]

    def test_require_rare_term_absorbed_when_inside_phrase(self):
        # Single content run covering the whole query -- the rarest term necessarily
        # falls inside the quoted phrase, so no separate `+` clause is added.
        df = Counter({"biomaterials": 2, "lack": 5, "inductive": 1, "properties": 5})
        text = "biomaterials lack inductive properties"
        derived, prov = m.derive_lucene_query(text, df)
        assert derived.count("+") == 0
        assert prov["rules_fired"] == [m.RULE_QUOTE_PHRASE, m.RULE_REQUIRE_RARE_TERM_ABSORBED]

    def test_no_eligible_content_leaves_query_unchanged(self):
        df: Counter[str] = Counter()
        derived, prov = m.derive_lucene_query("the of", df)
        assert derived == "the of"
        assert prov["rules_fired"] == []
        assert prov["escaped_chars"] == 0

    def test_single_content_word_has_no_phrase_but_can_require(self):
        df = Counter({"zephyr": 1})
        derived, prov = m.derive_lucene_query("the zephyr", df)
        assert m.RULE_QUOTE_PHRASE not in prov["rules_fired"]
        assert "+zephyr" in derived

    def test_lucene_specials_are_escaped(self):
        df: Counter[str] = Counter()
        derived, prov = m.derive_lucene_query("error:timeout or c++ (crash)", df)
        assert ":" not in derived.replace("\\:", "")
        assert prov["escaped_chars"] > 0
        # every occurrence of a special char must be backslash-prefixed
        for ch in ":+()":
            assert ("\\" + ch) in derived or ch not in "error:timeout or c++ (crash)".replace(ch, "", 0)

    def test_escaping_runs_even_when_no_structural_rule_fires(self):
        # quote_phrase needs >= 2 content-word TOKENS ("the" is a stopword, leaving
        # only the single word "thing"); require_rare_term needs corpus-vocabulary
        # membership ("thing" isn't in the empty df) -- so neither structural rule is
        # eligible here, but the colon must still be escaped.
        df: Counter[str] = Counter()
        derived, prov = m.derive_lucene_query("the:thing", df)
        assert derived == "the\\:thing"
        assert prov["rules_fired"] == []
        assert prov["escaped_chars"] == 1

    def test_tie_break_leftmost_for_equal_length_runs(self):
        df = Counter({"aa": 5, "bb": 5, "cc": 5, "dd": 5})
        # Two runs of equal length 2, separated by a stopword: leftmost wins.
        derived, prov = m.derive_lucene_query("aa bb and cc dd", df)
        assert derived.startswith('"aa bb"')

    def test_is_pure_no_mutation_of_input_counter(self):
        df = Counter({"zephyr": 1})
        df_copy = Counter(df)
        m.derive_lucene_query("the zephyr", df)
        assert df == df_copy


# ---------------------------------------------------------------------------
# Local-source orchestration: build_lucene_variant
# ---------------------------------------------------------------------------

def _write_source_mirror(base, name="mixed/toy-scifact-mirror"):
    """Tiny synthetic `mixed/`-shaped source (mirrors _write_source_dataset in
    test_corpus_query_variant.py): corpus.jsonl + queries.jsonl + qrels/test.tsv."""
    source_dir = base / name
    source_dir.mkdir(parents=True)

    docs = [
        {"_id": "d1", "title": "", "text": "the gadget widget zephyr assembly manual"},
        {"_id": "d2", "title": "", "text": "gadget widget maintenance procedure"},
        {"_id": "d3", "title": "", "text": "gadget repair notes for technicians"},
        {"_id": "d4", "title": "", "text": "gadget safety warnings and disclaimers"},
    ]
    with (source_dir / "corpus.jsonl").open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d) + "\n")

    queries = [
        {"_id": "q1", "text": "zephyr widget gadget"},
        {"_id": "q2", "text": "gadget maintenance procedure"},
        {"_id": "q3", "text": "xyzzy plugh"},  # zero eligible tokens
    ]
    with (source_dir / "queries.jsonl").open("w", encoding="utf-8") as f:
        for q in queries:
            f.write(json.dumps(q) + "\n")

    qrels_dir = source_dir / "qrels"
    qrels_dir.mkdir()
    (qrels_dir / "test.tsv").write_text(
        "query-id\tcorpus-id\tscore\nq1\td1\t1\nq2\td2\t1\nq3\td1\t1\n", encoding="utf-8",
    )
    return source_dir


class TestBuildLuceneVariant:
    def test_corpus_and_qrels_copied_verbatim(self, tmp_path):
        source_dir = _write_source_mirror(tmp_path)
        dest_dir = tmp_path / "out"
        m.build_lucene_variant(source_dir, dest_dir)

        assert (dest_dir / "corpus.jsonl").read_bytes() == (source_dir / "corpus.jsonl").read_bytes()
        assert (
            (dest_dir / "qrels" / "test.tsv").read_bytes()
            == (source_dir / "qrels" / "test.tsv").read_bytes()
        )

    def test_every_derived_query_keeps_its_source_qid(self, tmp_path):
        """qrels-compatibility: the derived queries.jsonl must carry EXACTLY the source
        ids, unchanged -- since qrels/ is a verbatim copy, this is what makes the
        variant scoreable against the identical qrels."""
        source_dir = _write_source_mirror(tmp_path)
        dest_dir = tmp_path / "out"
        m.build_lucene_variant(source_dir, dest_dir)

        source_ids = {
            json.loads(line)["_id"]
            for line in (source_dir / "queries.jsonl").read_text("utf-8").splitlines()
            if line.strip()
        }
        derived_ids = {
            json.loads(line)["_id"]
            for line in (dest_dir / "queries.jsonl").read_text("utf-8").splitlines()
            if line.strip()
        }
        assert derived_ids == source_ids

    def test_qrels_qids_are_a_subset_check_against_derived_queries(self, tmp_path):
        """Every qid referenced in qrels/test.tsv must resolve against the derived
        query set -- the actual end-to-end qrels-compatibility guarantee."""
        source_dir = _write_source_mirror(tmp_path)
        dest_dir = tmp_path / "out"
        m.build_lucene_variant(source_dir, dest_dir)

        derived_ids = {
            json.loads(line)["_id"]
            for line in (dest_dir / "queries.jsonl").read_text("utf-8").splitlines()
            if line.strip()
        }
        qrels_lines = (dest_dir / "qrels" / "test.tsv").read_text("utf-8").splitlines()[1:]
        qrels_qids = {line.split("\t")[0] for line in qrels_lines if line.strip()}
        assert qrels_qids <= derived_ids

    def test_per_query_provenance_is_recorded(self, tmp_path):
        source_dir = _write_source_mirror(tmp_path)
        dest_dir = tmp_path / "out"
        m.build_lucene_variant(source_dir, dest_dir)

        by_id = {
            json.loads(line)["_id"]: json.loads(line)
            for line in (dest_dir / "queries.jsonl").read_text("utf-8").splitlines()
            if line.strip()
        }
        assert set(by_id["q1"].keys()) >= {"_id", "text", "source_text", "lucene_rules", "escaped_chars"}
        assert by_id["q1"]["source_text"] == "zephyr widget gadget"
        # "xyzzy plugh": both words are non-stopwords, so quote_phrase still fires (it
        # only looks at stopword-ness) -- but neither is in the corpus vocabulary, so
        # require_rare_term finds nothing eligible to require.
        assert by_id["q3"]["lucene_rules"] == [m.RULE_QUOTE_PHRASE]
        assert by_id["q3"]["text"] == '"xyzzy plugh"'

    def test_rule_coverage_stats_sum_to_total_queries(self, tmp_path):
        source_dir = _write_source_mirror(tmp_path)
        dest_dir = tmp_path / "out"
        meta = m.build_lucene_variant(source_dir, dest_dir)

        assert meta["total_queries"] == 3
        assert sum(meta["rule_coverage"].values()) == 3
        assert "rules" in meta and m.RULE_QUOTE_PHRASE in meta["rules"]

    def test_deterministic_across_two_runs(self, tmp_path):
        source_dir = _write_source_mirror(tmp_path)
        dest1 = tmp_path / "out1"
        dest2 = tmp_path / "out2"
        m.build_lucene_variant(source_dir, dest1)
        m.build_lucene_variant(source_dir, dest2)

        assert (dest1 / "queries.jsonl").read_bytes() == (dest2 / "queries.jsonl").read_bytes()
        assert (dest1 / "metadata.json").read_bytes() == (dest2 / "metadata.json").read_bytes()
        assert (dest1 / "corpus.jsonl").read_bytes() == (dest2 / "corpus.jsonl").read_bytes()


# ---------------------------------------------------------------------------
# BEIR materialization glue (ir_datasets mocked -- no network/real corpus in tests)
# ---------------------------------------------------------------------------

class TestMaterializeBeirMirror:
    def test_writes_sorted_deterministic_files(self, tmp_path):
        fake_doc_a = MagicMock(doc_id="9", text="doc nine text", title="Nine")
        fake_doc_b = MagicMock(doc_id="2", text="doc two text", title="Two")
        fake_query_a = MagicMock(query_id="10", text="query ten")
        fake_query_b = MagicMock(query_id="1", text="query one")

        fake_dataset = MagicMock()
        fake_dataset.docs_iter.return_value = [fake_doc_a, fake_doc_b]
        fake_dataset.queries_iter.return_value = [fake_query_a, fake_query_b]
        fake_dataset.qrels_dict.return_value = {"10": {"9": 1}, "1": {"2": 1}}

        fake_ir_datasets = MagicMock()
        fake_ir_datasets.load.return_value = fake_dataset

        with patch.dict(sys.modules, {"ir_datasets": fake_ir_datasets}):
            with patch("jseval.dataset_cache.apply_ir_datasets_home"):
                stats = m.materialize_beir_mirror("scifact", tmp_path / "mirror")

        assert stats == {
            "beir_name": "scifact",
            "beir_slug": "beir/scifact/test",
            "doc_count": 2,
            "query_count": 2,
            "qrels_row_count": 2,
        }
        docs = [
            json.loads(line)
            for line in (tmp_path / "mirror" / "corpus.jsonl").read_text("utf-8").splitlines()
        ]
        assert [d["_id"] for d in docs] == ["2", "9"]  # sorted by _id

        queries = [
            json.loads(line)
            for line in (tmp_path / "mirror" / "queries.jsonl").read_text("utf-8").splitlines()
        ]
        assert [q["_id"] for q in queries] == ["1", "10"]  # sorted by _id (string order)

        qrels_lines = (tmp_path / "mirror" / "qrels" / "test.tsv").read_text("utf-8").splitlines()
        assert qrels_lines[0] == "query-id\tcorpus-id\tscore"
        assert "1\t2\t1" in qrels_lines
        assert "10\t9\t1" in qrels_lines

    def test_unknown_beir_name_raises(self, tmp_path):
        import pytest

        with pytest.raises(ValueError, match="Unknown BEIR dataset"):
            m.materialize_beir_mirror("not-a-real-dataset", tmp_path / "mirror")
