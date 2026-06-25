"""Tests for materialize.py — corpus materialization."""

from __future__ import annotations

import urllib.parse
from pathlib import Path
from unittest.mock import MagicMock

from jseval.materialize import (
    SENTINEL_DOC_ID,
    doc_id_to_filename,
    materialize,
    verify_sentinel,
)


class TestDocIdToFilename:
    def test_simple(self):
        assert doc_id_to_filename("abc") == "abc.txt"

    def test_dots_preserved(self):
        # Python's quote() doesn't encode dots (safe in URLs). The roundtrip
        # still works because PurePosixPath("5.1234.txt").stem → "5.1234".
        assert doc_id_to_filename("5.1234") == "5.1234.txt"

    def test_url_encodes_slashes(self):
        assert doc_id_to_filename("10.1038/nphys3025") == "10.1038%2Fnphys3025.txt"

    def test_url_encodes_spaces(self):
        assert doc_id_to_filename("hello world") == "hello%20world.txt"

    def test_roundtrip_with_retriever(self):
        """Verify materialization filename can be reversed by retriever."""
        doc_id = "10.1038/nphys3025"
        filename = doc_id_to_filename(doc_id)
        # Reverse: strip .txt, url-decode
        stem = Path(filename).stem
        recovered = urllib.parse.unquote(stem)
        assert recovered == doc_id


class TestMaterialize:
    def test_writes_txt_files(self, tmp_path):
        corpus = [
            {"_id": "d1", "text": "Hello world", "title": "Doc 1"},
            {"_id": "d2", "text": "Goodbye world", "title": None},
        ]
        count = materialize(corpus, tmp_path)
        assert count == 2

        f1 = tmp_path / doc_id_to_filename("d1")
        assert f1.exists()
        content = f1.read_text(encoding="utf-8")
        assert content == "Doc 1\n\nHello world"

        f2 = tmp_path / doc_id_to_filename("d2")
        assert f2.read_text(encoding="utf-8") == "Goodbye world"

    def test_writes_sentinel(self, tmp_path):
        materialize([], tmp_path)
        assert verify_sentinel(tmp_path)

    def test_skip_existing(self, tmp_path):
        corpus = [{"_id": "d1", "text": "original"}]
        materialize(corpus, tmp_path)

        # Write again with different content — should skip
        corpus2 = [{"_id": "d1", "text": "updated"}]
        count = materialize(corpus2, tmp_path, skip_existing=True)
        assert count == 0

        f = tmp_path / doc_id_to_filename("d1")
        assert f.read_text(encoding="utf-8") == "original"

    def test_overwrite_existing(self, tmp_path):
        corpus = [{"_id": "d1", "text": "original"}]
        materialize(corpus, tmp_path)

        corpus2 = [{"_id": "d1", "text": "updated"}]
        count = materialize(corpus2, tmp_path, skip_existing=False)
        assert count == 1

        f = tmp_path / doc_id_to_filename("d1")
        assert f.read_text(encoding="utf-8") == "updated"

    def test_ir_datasets_namedtuple(self, tmp_path):
        doc = MagicMock()
        doc.doc_id = "d1"
        doc.text = "content"
        doc.title = "Title"

        count = materialize([doc], tmp_path)
        assert count == 1

        f = tmp_path / doc_id_to_filename("d1")
        assert "Title" in f.read_text(encoding="utf-8")

    def test_creates_output_dir(self, tmp_path):
        out = tmp_path / "sub" / "dir"
        materialize([{"_id": "d1", "text": "hi"}], out)
        assert out.is_dir()
        assert (out / doc_id_to_filename("d1")).exists()

    def test_special_chars_in_id(self, tmp_path):
        corpus = [{"_id": "hello world!", "text": "content"}]
        materialize(corpus, tmp_path)
        expected = tmp_path / doc_id_to_filename("hello world!")
        assert expected.exists()


class TestVerifySentinel:
    def test_sentinel_present(self, tmp_path):
        materialize([], tmp_path)
        assert verify_sentinel(tmp_path) is True

    def test_sentinel_absent(self, tmp_path):
        tmp_path.mkdir(exist_ok=True)
        assert verify_sentinel(tmp_path) is False
