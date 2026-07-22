"""Multi-schema question generation + comparator + certification tests (tempdoc 776 §A.1).

Pure-function / fixture tests over the single_fact + aggregation schemas layered on the 767
camouflaged-injection substrate. No live claude, no dev stack, no paid API — offline structural
certification only, mirroring ``test_corpus_governance.py``'s inline-fixture style.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
from pathlib import Path

import pytest

from jseval import corpus_build, corpus_certify, corpus_comparators as cmp, corpus_inject
from jseval import corpus_generate as cg
from jseval.entity_bank import resolve_bank_reference

BANK = Path(__file__).resolve().parent / "fixtures" / "entity-bank-fixture"
_JSEVAL_ROOT = Path(__file__).resolve().parents[1]


def _mix(tmp_path, mix, *, seed=776, hops=1, lang="en"):
    cg.generate(tmp_path, axis="prose", lang=lang, n_chains=0, hops=hops, distractor_ratio=0,
                doc_words=None, suite="776-test", seed=seed, semantic=True, entity_bank=BANK,
                schema_mix=mix)
    queries = json.loads((tmp_path / "queries.json").read_text(encoding="utf-8"))
    docs = [json.loads(x) for x in (tmp_path / "docs.jsonl").read_text(encoding="utf-8").splitlines()]
    return queries, docs


# --------------------------------------------------------------------------- comparators

class TestComparators:
    def test_single_value_substring_after_normalization(self):
        assert cmp.score("single_value", "RBF 840", "the answer is RBF 840.") is True
        assert cmp.score("single_value", "RBF 840", "RBF 841") is False
        # normalization: case + trailing period + whitespace
        assert cmp.score("single_value", "ref-89-6503.", "  REF-89-6503  ") is True

    def test_absent_gold_kind_defaults_to_single_value(self):
        assert cmp.score(None, "lot 1234567", "value: lot 1234567") is True

    def test_set_is_order_insensitive_all_present(self):
        gold = cmp.SET_DELIMITER.join(["AAA 1", "BBB 2", "CCC 3"])
        assert cmp.score("set", gold, "found CCC 3, then AAA 1 and BBB 2") is True
        assert cmp.score("set", gold, "AAA 1 and BBB 2 only") is False  # missing CCC 3
        assert cmp.score("set", "", "anything") is False

    def test_count_is_standalone_numeric_exact(self):
        assert cmp.score("count", "4", "there are 4 entries") is True
        assert cmp.score("count", "4", "there are 40 entries") is False  # not a substring FP
        assert cmp.score("count", "4", "three entries") is False

    def test_extremum_is_single_value_exact(self):
        assert cmp.score("extremum", "M-8842", "the highest is M-8842") is True
        assert cmp.score("extremum", "M-8842", "M-8841") is False

    def test_unknown_gold_kind_raises(self):
        with pytest.raises(ValueError):
            cmp.score("bogus", "x", "x")

    def test_registry_covers_the_designed_kinds(self):
        assert set(cmp.COMPARATORS) == {"single_value", "set", "count", "extremum"}


# --------------------------------------------------------------------------- generation

class TestSchemaGeneration:
    def test_question_types_and_gold_kinds_are_distinct_and_correct(self, tmp_path):
        queries, _ = _mix(tmp_path, {"bridge": 6, "single_fact": 5, "aggregation": 6})
        by_type = {}
        for q in queries:
            by_type.setdefault(q["question_type"], []).append(q)
        assert set(by_type) == {"1_hop", "single_fact", "aggregation"}
        assert len(by_type["1_hop"]) == 6
        assert len(by_type["single_fact"]) == 5
        assert len(by_type["aggregation"]) == 6
        # every query carries a gold_kind in a mix
        assert all("gold_kind" in q for q in queries)
        assert {q["gold_kind"] for q in by_type["1_hop"]} == {"single_value"}
        assert {q["gold_kind"] for q in by_type["single_fact"]} == {"single_value"}
        # aggregation cycles set/count/extremum -> all three exercised across 6 items
        assert {q["gold_kind"] for q in by_type["aggregation"]} == {"set", "count", "extremum"}

    def test_evidence_id_counts_follow_the_hop_count_vocabulary(self, tmp_path):
        queries, _ = _mix(tmp_path, {"bridge": 4, "single_fact": 4, "aggregation": 6})
        for q in queries:
            n = len(q["evidence_ids"])
            if q["question_type"] == "single_fact":
                assert n == 1
            elif q["question_type"] == "1_hop":
                assert n == 2  # 2-entity bridge = 2 evidence docs
            else:
                assert 3 <= n <= 5  # aggregation gold set

    def test_evidence_ids_resolve_to_real_gold_docs(self, tmp_path):
        queries, docs = _mix(tmp_path, {"bridge": 3, "single_fact": 3, "aggregation": 4})
        doc_ids = {d["_id"] for d in docs}
        for q in queries:
            for e in q["evidence_ids"]:
                assert e in doc_ids

    def test_aggregation_set_answer_is_the_member_values(self, tmp_path):
        queries, docs = _mix(tmp_path, {"aggregation": 3})
        by_id = {d["_id"]: d for d in docs}
        for q in queries:
            if q["gold_kind"] != "set":
                continue
            parts = q["answer"].split(cmp.SET_DELIMITER)
            assert len(parts) == len(q["evidence_ids"])
            # every member value appears in exactly one member document's text
            for value in parts:
                assert any(value in by_id[e]["text"] for e in q["evidence_ids"])

    def test_aggregation_count_and_extremum_answers(self, tmp_path):
        queries, docs = _mix(tmp_path, {"aggregation": 3})
        by_id = {d["_id"]: d for d in docs}
        for q in queries:
            if q["gold_kind"] == "count":
                assert q["answer"] == str(len(q["evidence_ids"]))
            elif q["gold_kind"] == "extremum":
                measures = [int(by_id[e]["text"].split("M-")[1].split()[0].rstrip("."))
                            for e in q["evidence_ids"]]
                assert q["answer"] == f"M-{max(measures)}"

    def test_naming_leak_free_answer_never_shares_a_token_with_its_entities(self, tmp_path):
        import re
        queries, _ = _mix(tmp_path, {"bridge": 5, "single_fact": 5, "aggregation": 6})
        toks = lambda s: set(re.findall(r"[a-z0-9]+", s.lower()))
        for q in queries:
            if q["gold_kind"] in ("single_value", "extremum"):
                ent_tokens = set().union(*[toks(e) for e in q["evidence_ids"]])
                assert not (toks(q["answer"]) & ent_tokens), q

    def test_paraphrase_barrier_query_shares_no_content_token_with_gold_head(self, tmp_path):
        # single_fact: the query names the entity by SYNONYM descriptor only, so it must not
        # contain the entity surface (the grep anchor the bridge hop-1 mechanism removes).
        queries, docs = _mix(tmp_path, {"single_fact": 6})
        for q in queries:
            for e in q["evidence_ids"]:
                assert e not in q["query"].lower()

    def test_mix_is_deterministic_in_process(self, tmp_path):
        a = tmp_path / "a"
        b = tmp_path / "b"
        mix = {"bridge": 5, "single_fact": 5, "aggregation": 5}
        _mix(a, dict(mix))
        _mix(b, dict(mix))
        for name in ("queries.json", "docs.jsonl", "meta.json"):
            assert (a / name).read_bytes() == (b / name).read_bytes()

    def test_mix_is_deterministic_across_interpreters(self, tmp_path):
        # The cross-process proof `regeneration_determinism_report` runs at certify time: two
        # SEPARATE python processes must emit byte-identical docs/queries for a schema mix.
        result = cg.regenerate_and_diff(
            tmp_path / "r1", tmp_path / "r2", axis="prose", lang="en", seed=776, hops=1,
            distractor_ratio=0, semantic=True, n_chains=0, doc_words=None, entity_bank=BANK,
            schema_mix={"bridge": 4, "single_fact": 4, "aggregation": 4})
        assert result["ok"] is True, result
        assert result["mismatched_files"] == [], result

    def test_provenance_records_the_mix_in_canonical_order(self, tmp_path):
        _mix(tmp_path, {"aggregation": 3, "bridge": 4})  # deliberately non-canonical order
        prov = json.loads((tmp_path / "meta.json").read_text())["generation_provenance"]
        assert list(prov["schema_mix"]) == ["bridge", "aggregation"]  # canonical _SCHEMA_KINDS order

    def test_schema_mix_requires_semantic(self, tmp_path):
        with pytest.raises(ValueError, match="semantic=True"):
            cg.generate(tmp_path, axis="prose", n_chains=0, hops=1, distractor_ratio=0,
                        doc_words=None, seed=1, semantic=False, entity_bank=BANK,
                        schema_mix={"single_fact": 2})

    def test_schema_mix_rejects_unknown_schema(self, tmp_path):
        with pytest.raises(ValueError, match="unknown"):
            cg.generate(tmp_path, axis="prose", n_chains=0, hops=1, distractor_ratio=0,
                        doc_words=None, seed=1, semantic=True, entity_bank=BANK,
                        schema_mix={"temporal": 2})


# --------------------------------------------------------------------------- byte-stability

class TestOptInByteStability:
    def test_default_path_emits_no_gold_kind(self, tmp_path):
        cg.generate(tmp_path, axis="prose", lang="en", n_chains=5, hops=1, distractor_ratio=0,
                    doc_words=None, suite="x", seed=3, semantic=True, entity_bank=BANK)
        queries = json.loads((tmp_path / "queries.json").read_text())
        assert all("gold_kind" not in q for q in queries)
        assert all(q["question_type"] == "1_hop" for q in queries)
        prov = json.loads((tmp_path / "meta.json").read_text())["generation_provenance"]
        assert "schema_mix" not in prov

    @pytest.mark.parametrize("member", [
        "707-corpora/en-legal-clerc/1000-verbose",
        "707-corpora/en-email-enron-raw/1000-verbose",
    ])
    def test_committed_gold_sources_regenerate_byte_identically(self, member, tmp_path):
        """Zero digest drift on the certified cells (756 §F method): a committed fabricated gold
        source regenerated from its own recorded provenance is byte-identical — the multi-schema
        change did not perturb the default single-schema path."""
        cell = _JSEVAL_ROOT / member
        meta = json.loads((cell / "fabricated-meta.json").read_text(encoding="utf-8"))
        prov = meta["generation_provenance"]
        bank = resolve_bank_reference(prov["entity_bank"])
        cg.generate(tmp_path, axis=prov["axis"], lang=prov["lang"], n_chains=prov["n_chains"],
                    hops=prov["hops"], distractor_ratio=prov["distractor_ratio"],
                    doc_words=prov["doc_words"], suite=meta["suite"], seed=prov["seed"],
                    semantic=prov["semantic"], entity_bank=bank)
        for name, committed in (("queries.json", "fabricated-queries.json"),
                                ("docs.jsonl", "fabricated-docs.jsonl"),
                                ("meta.json", "fabricated-meta.json")):
            assert (tmp_path / name).read_bytes() == (cell / committed).read_bytes(), name


# --------------------------------------------------------------------------- inject + build

class TestInjectAndBuildPreserveSchema:
    def test_assemble_passes_gold_kind_through_untouched(self, tmp_path):
        gold = tmp_path / "gold"
        _mix(gold, {"bridge": 3, "single_fact": 3, "aggregation": 3})
        from jseval.corpus_build import read_jsonl
        real = [{"_id": str(1_000_000 + i),
                 "title": f"Opinion {i}",
                 "text": " ".join(["the court affirmed the judgment below on the record"] * 12)}
                for i in range(120)]
        fab = read_jsonl(gold / "docs.jsonl")
        queries = json.loads((gold / "queries.json").read_text())
        _docs, remapped, _report = corpus_inject.assemble(
            real, fab, queries, seed=776, n_distractors=30)
        for original, out in zip(queries, remapped):
            assert out["gold_kind"] == original["gold_kind"]
            assert out["question_type"] == original["question_type"]

    def test_build_golden_preserves_gold_kind_and_marks_aggregation_qrels(self, tmp_path):
        gold = tmp_path / "gold"
        _mix(gold, {"aggregation": 3, "single_fact": 2})
        # inject into a synthetic host to get a materializable source
        from jseval.corpus_build import read_jsonl
        real = [{"_id": str(2_000_000 + i), "title": f"Op {i}",
                 "text": " ".join(["the panel remanded for further proceedings on the merits"] * 12)}
                for i in range(80)]
        (tmp_path / "real").mkdir()
        (tmp_path / "real" / "corpus.jsonl").write_text(
            "".join(json.dumps(d) + "\n" for d in real), encoding="utf-8")
        source = tmp_path / "source"
        corpus_inject.build_source(tmp_path / "real", gold, source, seed=776, n_distractors=20,
                                   style="interleave", real_source_id="t", license_id="CC0-1.0")
        mixed = tmp_path / "mixed"
        corpus_build.build_golden(source, mixed)
        materialized = json.loads((mixed / "queries.json").read_text())
        assert all("gold_kind" in q for q in materialized)
        # aggregation queries mark every member relevant in qrels; single_fact marks its one doc
        qrels = (mixed / "qrels" / "test.tsv").read_text().strip().splitlines()[1:]
        marks_per_qid = {}
        for row in qrels:
            qid = row.split("\t")[0]
            marks_per_qid[qid] = marks_per_qid.get(qid, 0) + 1
        agg = [q for q in materialized if q["question_type"] == "aggregation"]
        # each aggregation query id marks len(evidence_ids) rows
        for i, q in enumerate(materialized, 1):
            if q["question_type"] == "aggregation":
                assert marks_per_qid[f"q{i:04d}"] == len(q["evidence_ids"])


# --------------------------------------------------------------------------- certification

class TestCertificationSchemaChecks:
    def test_single_schema_cell_has_no_schema_check_keys(self, tmp_path):
        # a pure-bridge materialized query list -> is_multi_schema False -> checks stay the 8 keys
        queries = [{"query": "q", "answer": "a", "question_type": "1_hop",
                    "evidence_ids": ["d1", "d2"]}]
        assert corpus_certify.is_multi_schema(queries) is False

    def test_multi_schema_detected_by_gold_kind(self):
        queries = [{"query": "q", "answer": "a", "question_type": "single_fact",
                    "gold_kind": "single_value", "evidence_ids": ["d1"]}]
        assert corpus_certify.is_multi_schema(queries) is True

    def test_schema_dispersion_flags_title_collapse(self):
        docs = [{"_id": "d1", "title": "SHARED", "text": "x"},
                {"_id": "d2", "title": "SHARED", "text": "y"}]
        queries = [{"question_type": "aggregation", "gold_kind": "set",
                    "evidence_ids": ["d1", "d2"], "answer": "x; y"}]
        report = corpus_certify.schema_dispersion_report(docs, queries)
        assert report["passed"] is False
        assert report["per_kind"]["set"]["dispersion"] == 0.5

    def test_demo_recipe_is_structurally_certified(self):
        spec = importlib.util.spec_from_file_location(
            "build_demo_776", _JSEVAL_ROOT / "776-demo" / "build_demo.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        report = module.build_demo()
        assert report["passed"] is True, report
        assert report["checks"]["multi_schema"] is True
        assert set(report["schemas"]) == {"1_hop", "single_fact", "aggregation"}
        assert set(report["gold_kinds"]) == {"single_value", "set", "count", "extremum"}
        for key in ("descriptor_collision", "indistinguishability", "schema_dispersion",
                    "schema_format_leak", "assembly_determinism"):
            assert report["checks"][key] is True, key
