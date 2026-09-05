"""Tests for jseval.trec — right-anchored TREC run-file parsing (tempdoc 916 §L.8).

The defect these pin: a left-anchored ``parts[2]`` read truncated every doc id
containing a space (OHR-bench ids do), silently dropping the gold from every
set-membership check downstream.
"""

from __future__ import annotations

from unittest.mock import MagicMock

from jseval.artifacts import _write_trec_run
from jseval.trec import DELIMITER, format_trec_line, load_trec_run, parse_trec_line

SPACEY = "law/airtechinternationalgroupinc_05_08_2000-ex-10.4-franchise agreement_p8"


class TestParseTrecLine:
    def test_doc_id_with_spaces_survives_space_delimited_line(self):
        e = parse_trec_line(f"q_0 Q0 {SPACEY} 3 9.500000 jseval_hybrid")
        assert e.qid == "q_0"
        assert e.doc_id == SPACEY
        assert e.rank == 3
        assert e.score == 9.5
        assert e.run_tag == "jseval_hybrid"

    def test_tab_delimited_line_parses(self):
        e = parse_trec_line("\t".join(["q_1", "Q0", SPACEY, "1", "10.000000", "jseval_vector"]))
        assert (e.qid, e.doc_id, e.rank, e.run_tag) == ("q_1", SPACEY, 1, "jseval_vector")

    def test_plain_doc_id_unchanged(self):
        e = parse_trec_line("q_0 Q0 administration/dude_9660_p13 1 10.000000 jseval_hybrid")
        assert e.doc_id == "administration/dude_9660_p13"

    def test_malformed_lines_rejected(self):
        assert parse_trec_line("") is None
        assert parse_trec_line("q_0 Q0 d 1") is None          # too few fields
        assert parse_trec_line("q_0 Q0 d rank score tag") is None  # non-numeric rank
        # 5 fields whose tail happens to be numeric: accepting it would yield an
        # empty doc id, so the six-field minimum is what rejects it.
        assert parse_trec_line("q_0 Q0 1 2 3") is None


class TestLoadTrecRun:
    def test_groups_by_qid_in_file_order(self, tmp_path):
        p = tmp_path / "hybrid_run.trec"
        p.write_text(
            f"q_0 Q0 {SPACEY} 1 10.000000 jseval_hybrid\n"
            "q_0 Q0 plain/doc_p1 2 9.000000 jseval_hybrid\n"
            f"q_1\tQ0\t{SPACEY}\t1\t8.000000\tjseval_hybrid\n",
            encoding="utf-8",
        )
        assert load_trec_run(p) == {"q_0": [SPACEY, "plain/doc_p1"], "q_1": [SPACEY]}

    def test_missing_file_is_empty(self, tmp_path):
        assert load_trec_run(tmp_path / "absent_run.trec") == {}


class TestRoundTrip:
    def test_format_then_parse(self):
        line = format_trec_line("q_0", SPACEY, 4, 1.25, "jseval_hybrid")
        assert DELIMITER == "\t"
        assert line.split("\t") == ["q_0", "Q0", SPACEY, "4", "1.250000", "jseval_hybrid"]
        e = parse_trec_line(line)
        assert (e.qid, e.doc_id, e.rank, e.score, e.run_tag) == (
            "q_0", SPACEY, 4, 1.25, "jseval_hybrid",
        )

    def test_writer_to_reader_preserves_spacey_doc_id(self, tmp_path):
        scored = [
            MagicMock(query_id="q_0", doc_id=SPACEY, score=2.0),
            MagicMock(query_id="q_0", doc_id="plain/doc_p1", score=1.0),
        ]
        path = tmp_path / "hybrid_run.trec"
        _write_trec_run(path, scored, "jseval_hybrid")
        # The writer must tab-delimit: six fields exactly, even with a spacey id.
        first = path.read_text(encoding="utf-8").splitlines()[0]
        assert first.split("\t") == ["q_0", "Q0", SPACEY, "1", "2.000000", "jseval_hybrid"]
        assert load_trec_run(path) == {"q_0": [SPACEY, "plain/doc_p1"]}
