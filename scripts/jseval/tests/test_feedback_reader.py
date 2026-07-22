"""Tests for jseval.feedback_reader (tempdoc 778) — the one-interface reader over
the tempdoc 580 §17 ResultDisposition feedback stream."""

from __future__ import annotations

import json
from pathlib import Path

from jseval import feedback_reader


def _write(path: Path, records: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for r in records:
            f.write(json.dumps(r) + "\n")


def _seed(data_dir: Path) -> None:
    feedback = data_dir / "feedback"
    _write(
        feedback / "result-dispositions.ndjson",
        [
            {"interactionId": "q1", "docId": "doc-A", "kind": "OPENED",
             "contributor": "SEARCH_INTERACTION", "occurredAtMs": 200},
            {"interactionId": "q1", "docId": "doc-B", "kind": "REFINED_WITHOUT_OPENING",
             "contributor": "SEARCH_INTERACTION", "occurredAtMs": 100},
            {"interactionId": "run-9", "docId": "doc-C", "kind": "CITED",
             "contributor": "AGENT_CITATION", "occurredAtMs": 300},
        ],
    )
    _write(
        feedback / "feature-snapshots.ndjson",
        [
            {"interactionId": "q1", "query": "index tuning", "occurredAtMs": 90,
             "hits": [
                 {"docId": "doc-A", "rank": 1, "sparse": 0.5, "dense": 0.4,
                  "splade": 0.3, "fused": 0.6, "parentTokenCount": 1200},
                 {"docId": "doc-B", "rank": 2, "sparse": 0.2, "dense": 0.1,
                  "splade": 0.1, "fused": 0.2, "parentTokenCount": None},
             ]},
            {"interactionId": "run-9", "query": "convex fusion", "occurredAtMs": 290,
             "hits": [
                 {"docId": "doc-C", "rank": 1, "sparse": 0.7, "dense": 0.8,
                  "splade": 0.6, "fused": 0.75, "parentTokenCount": 800},
             ]},
        ],
    )


def test_read_feedback_signals_unifies_and_sorts(tmp_path: Path) -> None:
    _seed(tmp_path)
    signals = feedback_reader.read_feedback_signals(tmp_path)
    assert [s["occurredAtMs"] for s in signals] == [100, 200, 300], "sorted by time"
    user = [s for s in signals if s["source"] == "user-event"]
    agent = [s for s in signals if s["source"] == "agent-citation"]
    assert len(user) == 2 and len(agent) == 1
    opened = next(s for s in signals if s["kind"] == "OPENED")
    assert opened["polarity"] == "positive" and opened["docId"] == "doc-A"
    refined = next(s for s in signals if s["kind"] == "REFINED_WITHOUT_OPENING")
    assert refined["polarity"] == "negative"
    cited = next(s for s in signals if s["kind"] == "CITED")
    assert cited["source"] == "agent-citation" and cited["polarity"] == "positive"


def test_read_labeled_examples_joins_features(tmp_path: Path) -> None:
    _seed(tmp_path)
    examples = feedback_reader.read_labeled_examples(tmp_path)
    assert len(examples) == 3, "all three dispositions join their snapshot hit"
    opened = next(e for e in examples if e["docId"] == "doc-A")
    assert opened["features"]["rank"] == 1
    assert opened["features"]["fused"] == 0.6
    cited = next(e for e in examples if e["docId"] == "doc-C")
    assert cited["contributor"] == "AGENT_CITATION"
    assert cited["features"]["dense"] == 0.8


def test_unjoinable_disposition_dropped(tmp_path: Path) -> None:
    feedback = tmp_path / "feedback"
    _write(feedback / "result-dispositions.ndjson",
           [{"interactionId": "orphan", "docId": "doc-X", "kind": "OPENED",
             "contributor": "SEARCH_INTERACTION", "occurredAtMs": 1}])
    # No matching snapshot → no featured example (honest limit).
    assert feedback_reader.read_labeled_examples(tmp_path) == []
    # …but it still shows in the raw signal stream.
    assert len(feedback_reader.read_feedback_signals(tmp_path)) == 1


def test_sealed_lines_skipped_not_misparsed(tmp_path: Path) -> None:
    feedback = tmp_path / "feedback"
    feedback.mkdir(parents=True, exist_ok=True)
    with (feedback / "result-dispositions.ndjson").open("w", encoding="utf-8") as f:
        f.write(json.dumps({"interactionId": "q1", "docId": "d", "kind": "OPENED",
                            "contributor": "SEARCH_INTERACTION", "occurredAtMs": 1}) + "\n")
        f.write("JSEv1:Zm9vYmFy\n")  # a sealed line Python cannot decrypt
    result = feedback_reader.read_dispositions(tmp_path)
    assert len(result.records) == 1
    assert result.sealed_skipped == 1


def test_missing_files_are_empty(tmp_path: Path) -> None:
    assert feedback_reader.read_feedback_signals(tmp_path) == []
    assert feedback_reader.read_labeled_examples(tmp_path) == []
    summary = feedback_reader.summarize(tmp_path)
    assert summary["dispositions"] == 0 and summary["labeledExamples"] == 0


def test_summarize_rolls_up(tmp_path: Path) -> None:
    _seed(tmp_path)
    summary = feedback_reader.summarize(tmp_path)
    assert summary["dispositions"] == 3
    assert summary["labeledExamples"] == 3
    assert summary["byContributor"]["SEARCH_INTERACTION"] == 2
    assert summary["byContributor"]["AGENT_CITATION"] == 1
    assert summary["byPolarity"]["positive"] == 2
    assert summary["byPolarity"]["negative"] == 1
    assert summary["sealedSkipped"] == 0
