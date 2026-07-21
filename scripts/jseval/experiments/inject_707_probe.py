#!/usr/bin/env python3
"""Real-text-injection feasibility probe for tempdoc 707 (THROWAWAY, no-stack).

Builds three small corpora under ``datasets/mixed/`` (gitignored — `.gitignore:215`) so a
downstream retrieval eval can answer "does injected gold retrieve above real distractors?":

- ``707-probe-control-fab``    — pure `corpus_generate.generate()` output: fabricated gold
  docs + fabricated distractors (the "full fabrication" retrieval baseline).
- ``707-probe-inject-append``  — the SAME fabricated gold chains (unchanged `_id`/`title`,
  evidence_ids untouched), each spliced onto a distinct real Enron email host via
  `host.text + "\\n\\n" + gold.text`, plus ~400 untouched real emails as distractors.
- ``707-probe-inject-interleave`` — same gold chains + same hosts + same real distractors,
  but the gold sentences are interleaved at evenly-spaced positions among the host's
  sentences instead of appended.

Schema mirrors the known-good ``scripts/jseval/635-corpora/needle-burial-v1/`` source shape
(docs.jsonl / queries.json / meta.json) — see that dir + `corpus_generate.py:679-693` (the
exact keys `generate()` itself writes) and `corpus_build.py:52-54,82-94` (confirms
`evidence_ids` is the sole gold-membership signal; the retrieval-qrels view derived from it
downstream marks only the first-hop id, but this probe's own acceptance check is "every
evidence_id resolves to a doc _id", which all three corpora satisfy directly). NOTE: the
brief that spawned this script asked to verify "how corpora.py loads a local mixed/ corpus's
qrels" — `corpora.py:74-102`'s `_load_local` actually reads the ALREADY-MATERIALIZED BEIR shape
(corpus.jsonl/queries.jsonl/qrels/test.tsv) that `corpus_build.build_golden()` derives FROM
docs.jsonl/queries.json; the evidence_ids -> qrels derivation itself lives in
`corpus_build.py:82-87`, not in `corpora.py`. Since `descriptor_collision_report` (the one gate
this probe runs) and this probe's own resolution check both operate directly on the
docs.jsonl/queries.json source shape, that's what gets written here — a downstream retrieval
eval can run `corpus_build.build_golden()` over any of these three dirs if it needs the
materialized BEIR view.

Run: python scripts/jseval/experiments/inject_707_probe.py
"""

from __future__ import annotations

import json
import random
import re
import sys
from pathlib import Path

# Make sure we import THIS worktree's jseval package, not whatever `pip install -e` happens
# to point at (tempdoc 716: jseval is normally editable-installed against wherever it was
# first set up, which can silently be a DIFFERENT worktree/checkout). Prepending this
# worktree's scripts/jseval/ to sys.path sidesteps that entirely for a direct import (no
# `jseval` CLI invocation happens anywhere in this script).
_EXPERIMENTS_DIR = Path(__file__).resolve().parent
_JSEVAL_ROOT = _EXPERIMENTS_DIR.parent  # scripts/jseval
_REPO_ROOT = _JSEVAL_ROOT.parent.parent  # worktree root
sys.path.insert(0, str(_JSEVAL_ROOT))

from jseval import corpus_generate  # noqa: E402
from jseval import corpus_certify  # noqa: E402

SEED = 707
N_CHAINS = 12
HOPS = 1
# semantic=True is REQUIRED for a valid 707-style probe: it makes the head doc describe the
# entity in one surface phrasing while the QUERY references it via SYNONYMS (low lexical
# overlap) — so grep/pure-BM25 fail at the entry point and only dense/SPLADE can bridge. This
# is the exact "retrieval beats a grep-agent" mechanism U0 measures; semantic=False (the
# generate() default) would let grep find the gold and defeat the probe's purpose.
SEMANTIC = True
# Small needle: keep each fabricated gold fragment a MINORITY of its real-email host (~100
# words vs the ~295-word median host) so the real-text near-duplicate geometry (F-029, the
# actual feasibility risk) dominates the statistics, not the injected text.
DOC_WORDS = 100
SUITE = "707-real-text-injection-probe"
# tempdoc 767 made the entity bank a required `generate()` input (chain entities are now
# minted type- and length-matched against a frozen committed bank instead of syllable
# pairs). This probe is kept RUNNABLE, but note its output is no longer byte-comparable
# with the recorded pre-767 run — the payload itself changed.
ENTITY_BANK = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "entity-bank-fixture"
N_DISTRACTORS = 400
HOST_SOURCE = "enron-qa/dasovich-j (MichaelR207/enron_qa_0922, train split, via convert-enronqa-to-beir.py)"

ENRON_SRC_DIR = _REPO_ROOT / "datasets" / "mixed" / "enron-probe-src"
CONTROL_FAB_DIR = _REPO_ROOT / "datasets" / "mixed" / "707-probe-control-fab"
INJECT_APPEND_DIR = _REPO_ROOT / "datasets" / "mixed" / "707-probe-inject-append"
INJECT_INTERLEAVE_DIR = _REPO_ROOT / "datasets" / "mixed" / "707-probe-inject-interleave"
DETERMINISM_SCRATCH_DIR = _REPO_ROOT / "datasets" / "mixed" / "707-probe-determinism-scratch"

_SENT_RE = re.compile(r"(?<=[.!?])\s+")


def split_sentences(text: str) -> list[str]:
    text = text.strip()
    if not text:
        return []
    return [p.strip() for p in _SENT_RE.split(text) if p.strip()]


def interleave_sentences(host_sents: list[str], gold_sents: list[str]) -> str:
    """Insert gold_sents at evenly-spaced positions among host_sents."""
    n_h, n_g = len(host_sents), len(gold_sents)
    if n_h == 0:
        return " ".join(gold_sents)
    if n_g == 0:
        return " ".join(host_sents)
    positions = []
    for i in range(1, n_g + 1):
        pos = round(i * n_h / (n_g + 1))
        positions.append(max(1, min(n_h, pos)))
    result: list[str] = []
    gi = 0
    for i, hs in enumerate(host_sents, start=1):
        result.append(hs)
        while gi < n_g and positions[gi] == i:
            result.append(gold_sents[gi])
            gi += 1
    while gi < n_g:
        result.append(gold_sents[gi])
        gi += 1
    return " ".join(result)


def read_jsonl(path: Path) -> list[dict]:
    docs = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                docs.append(json.loads(line))
    return docs


def write_jsonl(path: Path, docs: list[dict]) -> None:
    with path.open("w", encoding="utf-8") as f:
        for d in docs:
            f.write(json.dumps(d, ensure_ascii=False) + "\n")


def write_json(path: Path, obj) -> None:
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2), encoding="utf-8")


def write_queries_json(path: Path, obj) -> None:
    # Mirror `corpus_generate.generate()`'s own queries.json formatting exactly
    # (`corpus_generate.py:682`: `indent=1`) so a raw byte-diff against the control
    # corpus's queries.json is meaningful, not just an indent-width artifact.
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=1), encoding="utf-8")


def check_evidence_resolves(docs: list[dict], queries: list[dict]) -> tuple[int, int]:
    doc_ids = {d["_id"] for d in docs}
    passed = 0
    failed = 0
    for q in queries:
        ok = all(eid in doc_ids for eid in q.get("evidence_ids", []))
        if ok:
            passed += 1
        else:
            failed += 1
    return passed, failed


def main() -> None:
    report: dict = {}

    # --- 1. Determinism check: two independent generate() calls, same params, must match ---
    if DETERMINISM_SCRATCH_DIR.exists():
        import shutil

        shutil.rmtree(DETERMINISM_SCRATCH_DIR)
    corpus_generate.generate(
        CONTROL_FAB_DIR, axis="prose", lang="en", n_chains=N_CHAINS, hops=HOPS,
        seed=SEED, suite=SUITE, semantic=SEMANTIC, doc_words=DOC_WORDS,
        entity_bank=ENTITY_BANK,
    )
    corpus_generate.generate(
        DETERMINISM_SCRATCH_DIR, axis="prose", lang="en", n_chains=N_CHAINS, hops=HOPS,
        seed=SEED, suite=SUITE, semantic=SEMANTIC, doc_words=DOC_WORDS,
        entity_bank=ENTITY_BANK,
    )
    docs_a = (CONTROL_FAB_DIR / "docs.jsonl").read_text(encoding="utf-8")
    docs_b = (DETERMINISM_SCRATCH_DIR / "docs.jsonl").read_text(encoding="utf-8")
    queries_a = (CONTROL_FAB_DIR / "queries.json").read_text(encoding="utf-8")
    queries_b = (DETERMINISM_SCRATCH_DIR / "queries.json").read_text(encoding="utf-8")
    report["determinism_docs_match"] = docs_a == docs_b
    report["determinism_queries_match"] = queries_a == queries_b
    import shutil

    shutil.rmtree(DETERMINISM_SCRATCH_DIR)

    # --- 2. Load the fabricated control corpus; split into gold vs. discarded distractors ---
    fab_docs = read_jsonl(CONTROL_FAB_DIR / "docs.jsonl")
    fab_queries = json.loads((CONTROL_FAB_DIR / "queries.json").read_text(encoding="utf-8"))
    gold_ids = set()
    for q in fab_queries:
        gold_ids.update(q.get("evidence_ids", []))
    fab_gold_docs = sorted((d for d in fab_docs if d["_id"] in gold_ids), key=lambda d: d["_id"])
    n_fab_distractors_discarded = len(fab_docs) - len(fab_gold_docs)
    report["n_gold"] = len(fab_gold_docs)
    report["n_fab_distractors_discarded"] = n_fab_distractors_discarded
    report["n_fab_control_total"] = len(fab_docs)

    # --- 3. Load real Enron pool, pick deterministic hosts + distractor pool ---
    pool = read_jsonl(ENRON_SRC_DIR / "corpus.jsonl")
    pool = [d for d in pool if len(d["text"].split()) >= 60]
    pool_sorted = sorted(pool, key=lambda d: d["_id"])
    rng = random.Random(SEED)
    rng.shuffle(pool_sorted)
    n_gold = len(fab_gold_docs)
    hosts = pool_sorted[:n_gold]
    distractors = pool_sorted[n_gold:n_gold + N_DISTRACTORS]
    report["n_real_distractors"] = len(distractors)
    assert len(hosts) == n_gold, "not enough real emails for 1:1 host assignment"
    assert len(distractors) == N_DISTRACTORS, f"expected {N_DISTRACTORS} distractors, got {len(distractors)}"
    # hosts and distractors must be disjoint by _id, and each set internally unique
    host_ids = {h["_id"] for h in hosts}
    distractor_ids = {d["_id"] for d in distractors}
    assert len(host_ids) == n_gold and len(distractor_ids) == len(distractors)
    assert host_ids.isdisjoint(distractor_ids)

    # --- 4. Build injected gold docs (append + interleave), same host mapping for both ---
    append_gold_docs = []
    interleave_gold_docs = []
    for g, h in zip(fab_gold_docs, hosts):
        append_gold_docs.append({
            "_id": g["_id"], "title": g["title"],
            "text": h["text"] + "\n\n" + g["text"],
        })
        host_sents = split_sentences(h["text"])
        gold_sents = split_sentences(g["text"])
        interleave_gold_docs.append({
            "_id": g["_id"], "title": g["title"],
            "text": interleave_sentences(host_sents, gold_sents),
        })

    # --- 5. Write the three corpora ---
    for out_dir, gold_docs, style in (
        (INJECT_APPEND_DIR, append_gold_docs, "append"),
        (INJECT_INTERLEAVE_DIR, interleave_gold_docs, "interleave"),
    ):
        out_dir.mkdir(parents=True, exist_ok=True)
        all_docs = gold_docs + distractors
        write_jsonl(out_dir / "docs.jsonl", all_docs)
        write_queries_json(out_dir / "queries.json", fab_queries)
        meta = {
            "version": "1.0",
            "type_axis": "prose",
            "suite": SUITE,
            "contamination_class": "private-synthetic",
            "generation_provenance": {
                "method": "real-text-injection",
                "style": style,
                "seed": SEED,
                "n_gold": len(gold_docs),
                "n_distractors": len(distractors),
                "host_source": HOST_SOURCE,
                "fabrication_provenance": json.loads(
                    (CONTROL_FAB_DIR / "meta.json").read_text(encoding="utf-8")
                )["generation_provenance"],
            },
        }
        write_json(out_dir / "meta.json", meta)

    # --- 6. queries.json identity check across all three dirs (parsed AND raw-byte) ---
    control_q_text = (CONTROL_FAB_DIR / "queries.json").read_text(encoding="utf-8")
    append_q_text = (INJECT_APPEND_DIR / "queries.json").read_text(encoding="utf-8")
    interleave_q_text = (INJECT_INTERLEAVE_DIR / "queries.json").read_text(encoding="utf-8")
    report["queries_identical_bytes_across_dirs"] = (control_q_text == append_q_text == interleave_q_text)
    report["queries_identical_parsed_across_dirs"] = (
        json.loads(control_q_text) == json.loads(append_q_text) == json.loads(interleave_q_text)
    )

    # --- 7. Per-dir: descriptor_collision_report + evidence_ids-resolve check + counts ---
    for name, out_dir in (
        ("707-probe-control-fab", CONTROL_FAB_DIR),
        ("707-probe-inject-append", INJECT_APPEND_DIR),
        ("707-probe-inject-interleave", INJECT_INTERLEAVE_DIR),
    ):
        docs = read_jsonl(out_dir / "docs.jsonl")
        queries = json.loads((out_dir / "queries.json").read_text(encoding="utf-8"))
        coll = corpus_certify.descriptor_collision_report(docs, queries)
        passed_n, failed_n = check_evidence_resolves(docs, queries)
        gold_in_dir = {eid for q in queries for eid in q.get("evidence_ids", [])}
        n_gold_docs_present = sum(1 for d in docs if d["_id"] in gold_in_dir)
        report[name] = {
            "n_docs": len(docs),
            "n_gold_docs_present": n_gold_docs_present,
            "n_distractor_docs": len(docs) - n_gold_docs_present,
            "n_queries": len(queries),
            "descriptor_collision_report": {
                "n_groups": coll["n_groups"],
                "n_gold_involved": coll["n_gold_involved"],
                "passed": coll["passed"],
            },
            "evidence_resolves_pass": passed_n,
            "evidence_resolves_fail": failed_n,
        }

    print(json.dumps(report, indent=2, ensure_ascii=False))

    print("\n=== SAMPLE: one full append gold doc ===")
    print(json.dumps(append_gold_docs[0], indent=2, ensure_ascii=False))

    print("\n=== SAMPLE: one full interleave gold doc ===")
    print(json.dumps(interleave_gold_docs[0], indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
