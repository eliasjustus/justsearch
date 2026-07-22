"""Offline demonstration recipe for the tempdoc 776 §A.1 multi-schema lane.

Generates a small legal-flavoured host corpus, injects a THREE-SCHEMA gold mix (bridge +
single_fact + aggregation) via the 767 camouflaged-injection substrate, materializes it, and
runs the structural certification (the five 767 leak gates + the two new per-schema checks) plus
the cross-process determinism proof — all offline, zero paid API, zero GPU, zero network.

The SCIENTIFIC per-schema difficulty bands (retrieval-reachability, closed-book, union recall,
leak floor) are policy work for the later combined stack window; see the deferred-command list in
tempdoc 776 §E. Run:

    PYTHONPATH=scripts/jseval PYTHONUTF8=1 python scripts/jseval/776-demo/build_demo.py

Exit 0 and ``"passed": true`` mean the demo cell is structurally certified with a green
determinism proof.
"""

from __future__ import annotations

import json
import random
import tempfile
from pathlib import Path

from jseval import corpus_build, corpus_certify, corpus_inject
from jseval import corpus_generate as cg

#: The committed demo recipe: a schema MIX on a synthetic legal host. Opt-in per recipe — the
#: existing 707 recipes carry no `schema_mix` and are untouched.
RECIPE = {
    "member": "776-demo-legal-mix",
    "host": {"n_docs": 240, "min_words": 60, "seed": 776},
    "generate": {
        "axis": "prose", "lang": "en", "hops": 1, "doc_words": None, "seed": 776,
        "semantic": True, "suite": "776-demo",
        "schema_mix": {"bridge": 10, "single_fact": 15, "aggregation": 15},
    },
    "inject": {"seed": 776, "n_distractors": 105, "style": "interleave", "host_min_words": 60},
}

_BANK = Path(__file__).resolve().parents[1] / "tests" / "fixtures" / "entity-bank-fixture"

# Legal-flavoured clause pools for a varied synthetic host (the native null the leak gates
# calibrate against needs realistic native n-gram variety, not one repeated boilerplate).
_SUBJECTS = [
    "the appellant", "the district court", "the respondent corporation", "the trial judge",
    "the plaintiff", "the board of review", "the arbitration panel", "the circuit court",
    "the moving party", "the administrative agency", "the trustee", "the commission",
]
_VERBS = [
    "held that the statute of limitations barred the claim", "remanded the matter for further findings",
    "affirmed the judgment in all respects", "denied the motion for summary judgment",
    "reversed on the question of proximate cause", "construed the indemnity clause narrowly",
    "vacated the award and ordered a new hearing", "sustained the objection to the evidence",
    "declined to reach the constitutional question", "entered judgment for the prevailing party",
]
_TAILS = [
    "The record supports this conclusion under the applicable standard of review.",
    "Precedent from the reporter volumes compels the same result on these facts.",
    "No genuine dispute of material fact remains for the finder of fact to resolve.",
    "The parties stipulated to the underlying chronology at the pretrial conference.",
    "Costs are taxed against the losing party pursuant to the governing rule.",
    "The dissent would have remanded for reconsideration of the damages calculation.",
]


def _build_host(out_dir: Path, *, n_docs: int, seed: int) -> Path:
    """Write a deterministic synthetic legal host corpus (integer ids, >=60-word opinions)."""
    rng = random.Random(seed)
    # CLERC-shaped 7-digit integer ids, drawn distinct.
    ids: set[int] = set()
    while len(ids) < n_docs:
        ids.add(rng.randrange(1_000_000, 9_999_999))
    lines = []
    for doc_id in sorted(ids):
        sentences = []
        for _ in range(rng.randint(5, 8)):
            sentences.append(f"{rng.choice(_SUBJECTS).capitalize()} {rng.choice(_VERBS)}. "
                             f"{rng.choice(_TAILS)}")
        text = " ".join(sentences)
        lines.append(json.dumps({"_id": str(doc_id), "title": f"Opinion {doc_id}", "text": text},
                                ensure_ascii=False))
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / "corpus.jsonl"
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return out_dir


def build_demo(work_root: str | Path | None = None) -> dict:
    """Run the full offline demo and return a structured verdict."""
    ctx = tempfile.TemporaryDirectory() if work_root is None else None
    root = Path(work_root or ctx.name)
    try:
        host_dir = _build_host(root / "host", n_docs=RECIPE["host"]["n_docs"],
                               seed=RECIPE["host"]["seed"])
        gold_dir = root / "gold"
        g = RECIPE["generate"]
        stats = cg.generate(gold_dir, axis=g["axis"], lang=g["lang"], n_chains=0, hops=g["hops"],
                            distractor_ratio=0, doc_words=g["doc_words"], suite=g["suite"],
                            seed=g["seed"], semantic=g["semantic"], entity_bank=_BANK,
                            schema_mix=g["schema_mix"])

        # Inject the gold mix into the real host + prove cross-process assembly determinism.
        source_dir = root / "source"
        i = RECIPE["inject"]
        source_meta = corpus_inject.build_source(
            host_dir, gold_dir, source_dir, seed=i["seed"], n_distractors=i["n_distractors"],
            style=i["style"], real_source_id="776-demo-synthetic-legal",
            license_id="CC0-1.0", host_min_words=i["host_min_words"],
        )
        determinism = source_meta["generation_provenance"]["assembly_determinism"]

        # Materialize to the BEIR/agent layout and run structural certification on it.
        mixed_dir = root / "mixed"
        corpus_build.build_golden(source_dir, mixed_dir)
        docs = [json.loads(line) for line in (mixed_dir / "corpus.jsonl").read_text(
            encoding="utf-8").splitlines() if line.strip()]
        queries = json.loads((mixed_dir / "queries.json").read_text(encoding="utf-8"))

        collisions = corpus_certify.descriptor_collision_report(docs, queries)
        indistinguishability = corpus_certify.indistinguishability_report(docs, queries)
        dispersion = corpus_certify.schema_dispersion_report(docs, queries)
        format_leak = corpus_certify.schema_format_leak_report(docs, queries)

        from collections import Counter
        checks = {
            "descriptor_collision": collisions["passed"] is True,
            "indistinguishability": indistinguishability["passed"] is True,
            "schema_dispersion": dispersion["passed"] is True,
            "schema_format_leak": format_leak["passed"] is True,
            "assembly_determinism": determinism["passed"] is True,
            "multi_schema": corpus_certify.is_multi_schema(queries),
        }
        return {
            "member": RECIPE["member"],
            "corpus_size": len(docs),
            "query_count": len(queries),
            "schemas": dict(Counter(q["question_type"] for q in queries)),
            "gold_kinds": dict(Counter(q["gold_kind"] for q in queries)),
            "checks": checks,
            "reports": {
                "descriptor_collision": {k: collisions[k] for k in
                                         ("n_groups", "n_gold_involved", "passed")},
                "indistinguishability": {k: indistinguishability[k] for k in
                                         ("id_shape_passed", "ngram_passed", "passed")},
                "schema_dispersion": dispersion,
                "schema_format_leak": format_leak,
                "assembly_determinism": determinism,
            },
            "generate_stats": stats,
            "passed": all(checks.values()),
        }
    finally:
        if ctx is not None:
            ctx.cleanup()


if __name__ == "__main__":
    report = build_demo()
    print(json.dumps(report, indent=2, ensure_ascii=False))
    raise SystemExit(0 if report["passed"] else 1)
