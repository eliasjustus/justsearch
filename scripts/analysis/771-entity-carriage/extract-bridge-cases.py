#!/usr/bin/env python3
"""Extract bridge-entity carriage cases from a 781-v2 dataset cell.

Tempdoc 771 item (b). This does the JSON half of the carriage metric: for every
two-hop query in a cell it resolves the hop-1 document, derives the bridge entity
(the name an agent must re-query to reach the answer document), and emits the
document's indexed ``content_preview`` -- the exact field the MCP delivery excerpt
is drawn from.

The measurement half deliberately lives in Java
(``McpEntityCarriageMetricTest``), so the OFF/ON numbers come out of the SAME
renderers production calls rather than a re-implementation of them. This script
exists only because Jackson is not on the ui module's test compile classpath and
adding it would churn dependency lockfiles for a measurement input.

Usage::

    python scripts/analysis/771-entity-carriage/extract-bridge-cases.py \
        --cell tmp/781-v2-datasets/mixed/en-legal-clerc-1k-verbose \
        --out tmp/771-carriage-cases-legal.tsv

Then::

    ./gradlew.bat :modules:ui:test --tests "*McpEntityCarriageMetricTest*" \
        -Djustsearch.entityCarriage.casesTsv=<abs path to the tsv>

Output is TSV with a header row and four columns: ``doc_id``, ``title``,
``bridge_entity``, ``content_preview``. Tabs, CRs and LFs inside a value are
escaped (``\\t``, ``\\r``, ``\\n``) so one case is always one line.
"""

from __future__ import annotations

import argparse
import io
import json
import sys
from pathlib import Path

# The indexer's content_preview cap (IndexingDocumentOps#CONTENT_PREVIEW_MAX_CHARS).
CONTENT_PREVIEW_MAX_CHARS = 4096

# Function words that must not by themselves constitute the "shared run" between
# the two evidence sentences.
BOILERPLATE = {
    "the", "in", "of", "for", "is", "with", "was", "by", "and", "a", "an", "to",
    "at", "on", "that", "as", "notes", "record", "entry", "reads", "lists",
    "carries", "associated", "designation", "filed", "under",
}


def longest_shared_token_run(a: str, b: str) -> str | None:
    """The longest contiguous token run shared by both evidence sentences.

    The bridge entity is by construction the one name present in the hop-1 and
    hop-2 sentences alike -- deriving it beats hard-coding a template pattern,
    which the 767 camouflage rebuild deliberately varies.
    """
    ta = a.replace(".", "").split()
    tb = b.replace(".", "").split()
    best = 0
    best_end = 0
    prev = [0] * (len(tb) + 1)
    for i in range(1, len(ta) + 1):
        cur = [0] * (len(tb) + 1)
        for j in range(1, len(tb) + 1):
            if ta[i - 1] == tb[j - 1] and ta[i - 1].lower() not in BOILERPLATE:
                cur[j] = prev[j - 1] + 1
                if cur[j] > best:
                    best = cur[j]
                    best_end = i
        prev = cur
    if best == 0:
        return None
    return " ".join(ta[best_end - best:best_end])


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--cell", required=True, type=Path, help="781-v2 dataset cell directory")
    ap.add_argument("--out", required=True, type=Path, help="TSV output path")
    args = ap.parse_args()

    cell: Path = args.cell
    offsets = json.loads((cell / "evidence_offsets.json").read_text(encoding="utf-8"))["offsets"]
    queries = json.loads((cell / "queries.json").read_text(encoding="utf-8"))

    docs: dict[str, tuple[str, str]] = {}
    with io.open(cell / "corpus.jsonl", encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            d = json.loads(line)
            docs[d["_id"]] = (d.get("title", ""), d.get("text", ""))

    rows: list[tuple[str, str, str, str]] = []
    skipped = 0
    for q in queries:
        ids = q.get("evidence_ids") or []
        if len(ids) != 2:
            skipped += 1
            continue
        a, b = ids[0], ids[1]
        ev_a = (offsets.get(a) or {}).get("evidence", "")
        ev_b = (offsets.get(b) or {}).get("evidence", "")
        if not ev_a or not ev_b:
            skipped += 1
            continue
        # The hop-1 document introduces the camouflaged designation; the other
        # names the answer value against the bridge entity.
        if ", designated " in ev_a:
            hop_one = a
        elif ", designated " in ev_b:
            hop_one = b
        else:
            skipped += 1
            continue
        bridge = longest_shared_token_run(ev_a, ev_b)
        if not bridge or len(bridge) < 4:
            skipped += 1
            continue
        doc = docs.get(hop_one)
        if doc is None:
            skipped += 1
            continue
        title, text = doc
        rows.append((hop_one, title, bridge, text[:CONTENT_PREVIEW_MAX_CHARS]))

    args.out.parent.mkdir(parents=True, exist_ok=True)
    with io.open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("doc_id\ttitle\tbridge_entity\tcontent_preview\n")
        for row in rows:
            fh.write("\t".join(escape(v) for v in row) + "\n")

    print(f"cell={cell.name} cases={len(rows)} skipped={skipped} -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
