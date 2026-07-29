#!/usr/bin/env python3
"""748 Phase 2/3 — offline exact-NN bridge-vs-scale probe (no backend, no ANN, no fusion).

Purpose
-------
Tempdoc 748's four candidate causes for the German 10k collapse are:
  (a) the incumbent encoders' German representation quality,
  (b) a scale/candidate-depth interaction (ANN truncation / fusion starving a
      weak-but-nonzero leg as distractor mass grows),
  (c) the fabricated gold design (pure zero-lexical-overlap synonym descriptors
      planted as a short payload inside a large real host document),
  (d) German text mechanics (compounding/tokenization).

This probe removes the ENGINE from the loop entirely. It embeds an assembled
corpus and its queries with the *production* encoder recipe
(`models/onnx/gte-multilingual-base`, CLS pooling, no prefixes — mirrored from
`pooling_config.json` / `prefix_config.json`) and scores **exact** cosine
nearest-neighbour retrieval over a growing distractor pool.

That makes the (a)/(c) vs (b) fork decidable offline and for free:

  * Exact NN has no ANN graph, no candidate cut-off, no fusion, no reranker.
    If recall@10 still collapses as the pool grows from 1k to 10k, the loss is
    in the **representation** (or the gold design), and hypothesis (b) is
    refuted for that corpus.
  * Running the identical construction in EN and DE turns the language axis
    into a controlled comparison: same generator, same host style, same
    zero-lexical-overlap descriptors. A collapse in BOTH is (c); a collapse in
    DE only is (a)/(d).

Deliberately CPU-only by default: the shared machine's GPU is frequently held
by another worker, and this probe must never contend for it. `--provider cuda`
is available when the GPU is known free.

Inputs
------
--docs      JSONL with {"_id", "title"?, "text"} — the assembled corpus
            (gold + distractors), or a directory of `<url-quoted-id>.txt`.
--queries   JSON: either {qid: query_text} or a list of
            {"_id"/"qid", "text"/"query"} records.
--qrels     JSON {qid: {doc_id: relevance}}.
--pool-sizes  Comma-separated distractor pool sizes to score, e.g. "1000,10000".
            Gold documents are always retained; non-gold documents are
            subsampled deterministically (seeded) down to the pool size.

Output: one JSON report with a recall/nDCG-vs-pool-size curve, plus the
gold-vs-best-distractor cosine margin distribution (the separability signal).

Usage:
  python encoder_bridge_scale_748.py --docs <cell>/docs.jsonl \
      --queries <cell>/queries.json --qrels <cell>/qrels.json \
      --pool-sizes 1000,10000 --out tmp/748/bridge-scale-de.json
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
from pathlib import Path
from urllib.parse import unquote

import numpy as np

DEFAULT_MODEL_DIR = Path("F:/JustSearch/models/onnx/gte-multilingual-base")


# ---------------------------------------------------------------------------
# Encoder — production recipe mirror
# ---------------------------------------------------------------------------
class Encoder:
    """gte-multilingual-base via onnxruntime, CLS pooling, L2-normalized.

    Mirrors the production recipe declared by the model directory itself
    (`pooling_config.json` -> cls; `prefix_config.json` -> empty prefixes), so
    this probe measures the encoder the engine actually ships, not a proxy.
    """

    def __init__(
        self,
        model_dir: Path,
        provider: str = "cpu",
        max_len: int = 512,
        weights: str = "fp32",
    ) -> None:
        import onnxruntime as ort
        from tokenizers import Tokenizer

        pooling = json.loads((model_dir / "pooling_config.json").read_text())
        prefixes = json.loads((model_dir / "prefix_config.json").read_text())
        if pooling.get("pooling_mode") != "cls":
            raise SystemExit(f"unexpected pooling mode: {pooling}")
        self.query_prefix = prefixes.get("query_prefix", "")
        self.doc_prefix = prefixes.get("document_prefix", "")

        name = "model.onnx" if weights == "fp32" else "model_fp16.onnx"
        providers = (
            ["CUDAExecutionProvider", "CPUExecutionProvider"]
            if provider == "cuda"
            else ["CPUExecutionProvider"]
        )
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = int(os.environ.get("JS748_THREADS", "0")) or 0
        self.session = ort.InferenceSession(
            str(model_dir / name), sess_options=opts, providers=providers
        )
        self.tokenizer = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
        self.tokenizer.enable_truncation(max_length=max_len)
        self.tokenizer.enable_padding()
        self.max_len = max_len
        self.provider = self.session.get_providers()[0]

    def encode(self, texts: list[str], batch_size: int = 8, log_every: int = 0):
        out = np.zeros((len(texts), 768), dtype=np.float32)
        started = time.time()
        for start in range(0, len(texts), batch_size):
            batch = texts[start : start + batch_size]
            encs = self.tokenizer.encode_batch(batch)
            ids = np.array([e.ids for e in encs], dtype=np.int64)
            mask = np.array([e.attention_mask for e in encs], dtype=np.int64)
            res = self.session.run(
                ["sentence_embedding"], {"input_ids": ids, "attention_mask": mask}
            )[0]
            out[start : start + len(batch)] = res
            if log_every and (start // batch_size) % log_every == 0:
                done = start + len(batch)
                rate = done / max(time.time() - started, 1e-6)
                eta = (len(texts) - done) / max(rate, 1e-6)
                print(
                    f"  encoded {done}/{len(texts)} ({rate:.1f}/s, eta {eta / 60:.1f}m)",
                    file=sys.stderr,
                    flush=True,
                )
        norms = np.linalg.norm(out, axis=1, keepdims=True)
        return out / np.clip(norms, 1e-12, None)


# ---------------------------------------------------------------------------
# IO
# ---------------------------------------------------------------------------
def load_docs(path: Path) -> dict[str, str]:
    docs: dict[str, str] = {}
    if path.is_dir():
        for f in path.glob("*.txt"):
            docs[unquote(f.stem)] = f.read_text(encoding="utf-8", errors="replace")
        return docs
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        rec = json.loads(line)
        doc_id = rec.get("_id") or rec.get("id")
        title = (rec.get("title") or "").strip()
        text = rec.get("text") or ""
        docs[doc_id] = f"{title}\n{text}".strip() if title else text
    return docs


def load_queries(path: Path) -> dict[str, str]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(raw, dict):
        # {qid: text} or {qid: {"text": ...}}
        return {
            k: (v if isinstance(v, str) else (v.get("text") or v.get("query") or ""))
            for k, v in raw.items()
        }
    out = {}
    for rec in raw:
        qid = rec.get("_id") or rec.get("qid") or rec.get("id")
        out[qid] = rec.get("text") or rec.get("query") or ""
    return out


def ndcg_at_k(ranked: list[str], relevant: set[str], k: int = 10) -> float:
    dcg = sum(
        1.0 / math.log2(i + 2) for i, d in enumerate(ranked[:k]) if d in relevant
    )
    ideal = sum(1.0 / math.log2(i + 2) for i in range(min(len(relevant), k)))
    return dcg / ideal if ideal else 0.0


# ---------------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--docs", required=True, type=Path)
    ap.add_argument("--queries", required=True, type=Path)
    ap.add_argument("--qrels", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--pool-sizes", default="1000,10000")
    ap.add_argument("--model-dir", type=Path, default=DEFAULT_MODEL_DIR)
    ap.add_argument("--provider", choices=("cpu", "cuda"), default="cpu")
    ap.add_argument("--weights", choices=("fp32", "fp16"), default="fp32")
    ap.add_argument("--max-len", type=int, default=512)
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--seed", type=int, default=748)
    ap.add_argument("--label", default="")
    args = ap.parse_args(argv)

    docs = load_docs(args.docs)
    queries = load_queries(args.queries)
    qrels = json.loads(args.qrels.read_text(encoding="utf-8"))
    qrels = {q: {d: float(r) for d, r in v.items()} for q, v in qrels.items()}
    queries = {q: t for q, t in queries.items() if q in qrels}

    gold_ids = {d for v in qrels.values() for d, r in v.items() if r > 0}
    missing_gold = sorted(g for g in gold_ids if g not in docs)
    gold_ids = {g for g in gold_ids if g in docs}
    distractor_ids = [d for d in docs if d not in gold_ids]

    pool_sizes = [int(s) for s in args.pool_sizes.split(",") if s.strip()]
    max_pool = max(pool_sizes)
    rng = random.Random(args.seed)
    shuffled = list(distractor_ids)
    rng.shuffle(shuffled)
    # One nested sequence, so a smaller pool is a strict subset of a larger one:
    # the only thing that changes between pool sizes is added distractor mass.
    universe = sorted(gold_ids) + shuffled[: max(0, max_pool - len(gold_ids))]

    enc = Encoder(
        args.model_dir,
        provider=args.provider,
        max_len=args.max_len,
        weights=args.weights,
    )
    print(
        f"encoder provider={enc.provider} weights={args.weights} "
        f"docs={len(universe)} queries={len(queries)}",
        file=sys.stderr,
    )

    t0 = time.time()
    doc_vecs = enc.encode(
        [enc.doc_prefix + docs[d] for d in universe],
        batch_size=args.batch_size,
        log_every=25,
    )
    qids = sorted(queries)
    q_vecs = enc.encode(
        [enc.query_prefix + queries[q] for q in qids], batch_size=args.batch_size
    )
    encode_secs = round(time.time() - t0, 1)

    doc_index = {d: i for i, d in enumerate(universe)}
    results = []
    for size in pool_sizes:
        keep = universe[: max(size, len(gold_ids))]
        idx = np.array([doc_index[d] for d in keep])
        sub = doc_vecs[idx]
        sims = q_vecs @ sub.T  # cosine, both sides L2-normalized

        recalls, ndcgs, p1s, margins, gold_ranks = [], [], [], [], []
        for qi, qid in enumerate(qids):
            rel = {d for d, r in qrels[qid].items() if r > 0 and d in doc_index}
            if not rel:
                continue
            order = np.argsort(-sims[qi])
            full_order = [keep[j] for j in order]
            ranked = full_order[:100]
            recalls.append(1.0 if any(d in rel for d in ranked[:10]) else 0.0)
            ndcgs.append(ndcg_at_k(ranked, rel, 10))
            p1s.append(1.0 if ranked and ranked[0] in rel else 0.0)

            rel_pos = [i for i, d in enumerate(keep) if d in rel]
            best_gold = float(np.max(sims[qi][rel_pos]))
            mask = np.ones(len(keep), dtype=bool)
            mask[rel_pos] = False
            best_distractor = float(np.max(sims[qi][mask])) if mask.any() else -1.0
            margins.append(best_gold - best_distractor)
            gold_ranks.append(
                min(i for i, d in enumerate(full_order) if d in rel) + 1
            )

        results.append(
            {
                "pool_size": len(keep),
                "n_queries": len(recalls),
                "exactnn_recall@10": round(float(np.mean(recalls)), 4),
                "exactnn_ndcg@10": round(float(np.mean(ndcgs)), 4),
                "exactnn_p@1": round(float(np.mean(p1s)), 4),
                "gold_margin_mean": round(float(np.mean(margins)), 4),
                "gold_margin_median": round(float(np.median(margins)), 4),
                "gold_margin_positive_share": round(
                    float(np.mean([1.0 if m > 0 else 0.0 for m in margins])), 4
                ),
                "gold_rank_median": float(np.median(gold_ranks)),
                "gold_rank_p90": float(np.percentile(gold_ranks, 90)),
            }
        )
        print(json.dumps(results[-1]), file=sys.stderr, flush=True)

    report = {
        "schema": "748-encoder-bridge-scale.v1",
        "label": args.label or args.docs.parent.name,
        "docs": str(args.docs),
        "queries": str(args.queries),
        "qrels": str(args.qrels),
        "model_dir": str(args.model_dir),
        "provider": enc.provider,
        "weights": args.weights,
        "max_len": args.max_len,
        "seed": args.seed,
        "n_docs_available": len(docs),
        "n_gold_docs": len(gold_ids),
        "n_gold_ids_missing_from_corpus": missing_gold[:20],
        "encode_seconds": encode_secs,
        "curve": results,
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(report, indent=2, ensure_ascii=False), "utf-8")
    print(json.dumps(report["curve"], indent=2))
    print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
