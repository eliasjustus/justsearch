#!/usr/bin/env python3
"""Offline cross-encoder (reranker) bake-off — the UPGRADE screen F-052 opened.

Sibling of ``encoder_bakeoff_708.py`` (same house pattern: Gate-0 anchoring,
per-model-favorable recipes, paired per-query sign tests, one JSON per run,
experiment-grade screen and NOT a register baseline producer).  Where 708
screened the *bi-encoder*, this screens the *cross-encoder*: F-052 refuted
F-001/F-006 and showed CE model quality is a first-order lever, which re-opened
"is there a better CE than the incumbent?" — a question D-001's retired
rationale had closed.

Protocol
--------
Stage 1 (``--stage pool``) builds ONE candidate pool per query and freezes it:

  * doc chunking mirrors the index side (500 tokens / 50 overlap,
    ``ChunkDocumentWriter`` granularity) using the production embedder's
    tokenizer;
  * BM25 (Okapi, doc-level) top ``--pool-bm25`` UNION dense chunk-MaxP top
    ``--pool-dense`` (dense = the production embedder
    ``Alibaba-NLP/gte-multilingual-base``, CLS pooling, no prefixes) — an
    offline stand-in for the engine's leg union;
  * the CE input text per (query, doc) pair is the **argmax dense chunk**,
    capped at 4096 chars — which is exactly what the shipped engine feeds the
    CE since tempdoc 774 Stage 2 (``SearchResponseBuilder.capEvidencePreview``,
    ``search.evidence_preview.enabled`` default true, ``EVIDENCE_PREVIEW_CAP =
    4096``).  Selecting it with the production embedder (not with each CE)
    keeps the CE input BIT-IDENTICAL across arms, so an arm delta is the CE
    model and nothing else.

Stage 2 (``--stage rerank --model X``) scores the frozen pairs with one CE and
writes per-query nDCG@10 / P@1 / R@10 plus per-pair latency.  Because the pool
and the pair texts are frozen, arms are exactly paired per query.  Two
conditions matter and both should be run:

  * ``--max-len 512 --rerank-top-k 20`` — the SHIPPED configuration
    (``justsearch.rerank.max_seq_len`` = 512, ``justsearch.rerank.top_k`` = 20).
  * long context, whole pool (the defaults here) — the candidate-favorable
    condition, in the 708 tradition: a candidate that loses under treatment
    biased in its favor is a clean no-swap datapoint.

Truncation follows production exactly (``RerankerTokenizer.pack``: encode the
pair untruncated, then keep the FIRST maxLength ids).  That is head-first, so a
reranker whose prompt puts its scoring instruction AFTER the document loses it
under a tight cap — a real integration hazard, not a harness artifact.

Gate 0
------
Before screening any candidate, ``--model anchor`` and ``--model minilm`` must
separate on ``mixed/legal-clerc-200`` in the same direction as the live
delivered-rank A/B (F-052: incumbent 0.5788 vs MiniLM 0.4435, −23.4%).  If they
do not, the harness is broken — fix it before reading any candidate number.
Offline LEVELS are not engine claims (F-040 inversion warning); only the
between-arm DELTAS are load-bearing.

Usage
-----
  python reranker_bakeoff_f052.py --stage pool \
      --dataset mixed/legal-clerc-200 --out tmp/ce-bakeoff/pools
  python reranker_bakeoff_f052.py --stage rerank --model anchor \
      --pool tmp/ce-bakeoff/pools/mixed_legal-clerc-200.json \
      --max-len 512 --rerank-top-k 20 --tag @prod --out tmp/ce-bakeoff/results
  python reranker_bakeoff_f052.py --stage compare \
      --results tmp/ce-bakeoff/results --anchor anchor --tag @prod
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import sys
import time
from collections import Counter
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Cross-encoder recipe registry
#
# Per-model-favorable recipes (708 convention): each model is scored the way
# its own publisher documents, so a candidate that loses does so on merit.
#
# ``kind`` selects the scoring path:
#   seq_cls -- AutoModelForSequenceClassification, score = logits[:, -1]
#              (classic cross-encoder; single-logit or [neg, pos] head)
#   st      -- sentence_transformers.CrossEncoder, which carries the model's
#              own chat template / prompt / LogitScore module (the publisher
#              recipe for the LLM-backbone rerankers).  Scored with an identity
#              activation so saturating sigmoids cannot manufacture rank ties.
#
# Eligibility (D-003 + license) was screened before this registry: general
# multilingual only, Apache/MIT-class license only, <= ~1B params.  The
# named-and-excluded list lives in the run notes, not here.
# ---------------------------------------------------------------------------

RECIPES: dict[str, dict] = {
    # ---- Gate-0 pair: the shipped incumbent and the model it replaced -------
    "anchor": {
        "hf_id": "Alibaba-NLP/gte-multilingual-reranker-base",
        "kind": "seq_cls",
        "trust_remote_code": True,
        "max_len": 1024,          # native 8192; evidence previews are ~<600 tok
        "license": "apache-2.0",
        "note": "INCUMBENT (models/onnx/reranker/), ~306M, mGTE encoder, ctx 8192",
    },
    "minilm": {
        "hf_id": "cross-encoder/ms-marco-MiniLM-L-6-v2",
        "kind": "seq_cls",
        "trust_remote_code": False,
        "max_len": 512,           # hard model limit; F-052 arm B used 512
        "license": "apache-2.0",
        "note": "KNOWN-BAD Gate-0 anchor (models/onnx/reranker-minilm-backup/), English-only",
    },
    # ---- eligible candidates ----------------------------------------------
    "qwen3-0.6b": {
        "hf_id": "Qwen/Qwen3-Reranker-0.6B",
        "kind": "st",
        "trust_remote_code": False,
        "max_len": 2048,
        "license": "apache-2.0",
        "note": "596M Qwen3 decoder, yes/no logit head via ST LogitScore, ctx 32k",
    },
    "mxbai-v2-base": {
        "hf_id": "mixedbread-ai/mxbai-rerank-base-v2",
        "kind": "st",
        "trust_remote_code": False,
        "max_len": 2048,
        "license": "apache-2.0",
        "note": "494M Qwen2 decoder + LogitScore, 109 languages, ctx 8k",
    },
    "bge-v2-m3": {
        "hf_id": "BAAI/bge-reranker-v2-m3",
        "kind": "seq_cls",
        "trust_remote_code": False,
        "max_len": 1024,
        "license": "apache-2.0",
        "note": "568M XLM-R-large; carries the F-005 ONNX-CUDA 5.7x regression risk",
    },
    "mmarco-mminilm-l12": {
        "hf_id": "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1",
        "kind": "seq_cls",
        "trust_remote_code": False,
        "max_len": 512,
        "license": "apache-2.0",
        "note": "118M XLM-R, 14 langs, ONNX O1-O4 in-repo — the fast floor control",
    },
}

EMBEDDER_ID = "Alibaba-NLP/gte-multilingual-base"
CHUNK_TOKENS = 500
CHUNK_OVERLAP = 50
EVIDENCE_CAP_CHARS = 4096  # SearchResponseBuilder.EVIDENCE_PREVIEW_CAP


# ---------------------------------------------------------------------------
# Dataset loading
# ---------------------------------------------------------------------------

def load_dataset(name: str, base_dir: Path):
    """Returns (docs, queries, qrels, signature).

    ``beir/<x>`` goes through ir_datasets (same cache jseval uses);
    ``mixed/<x>`` reads the BEIR-style local layout under ``base_dir``.
    """
    if name.startswith("beir/"):
        import ir_datasets
        bare = name.split("/", 1)[1]
        ds = ir_datasets.load(f"beir/{bare}/test")
        docs = {}
        for d in ds.docs_iter():
            title = getattr(d, "title", "") or ""
            docs[str(d.doc_id)] = (title + " " + d.text).strip() if title else d.text
        queries = {str(q.query_id): q.text for q in ds.queries_iter()}
        qrels = {str(q): {str(d): int(r) for d, r in rels.items()}
                 for q, rels in ds.qrels_dict().items()}
        h = hashlib.sha256()
        for k in sorted(docs):
            h.update(k.encode()); h.update(docs[k].encode("utf-8"))
        return docs, queries, qrels, h.hexdigest()

    ddir = base_dir / name
    docs = {}
    for line in (ddir / "corpus.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        text = " ".join(p for p in (obj.get("title", ""), obj.get("text", "")) if p)
        docs[str(obj["_id"])] = text
    queries = {}
    for line in (ddir / "queries.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        queries[str(obj["_id"])] = obj["text"]
    qrels: dict[str, dict[str, int]] = {}
    for i, line in enumerate((ddir / "qrels" / "test.tsv").read_text(
            encoding="utf-8").splitlines()):
        if not line.strip():
            continue
        parts = line.split("\t")
        if i == 0 and not parts[-1].strip().lstrip("-").isdigit():
            continue
        qrels.setdefault(str(parts[0]), {})[str(parts[-2])] = int(parts[-1])
    h = hashlib.sha256()
    h.update((ddir / "corpus.jsonl").read_bytes())
    h.update((ddir / "qrels" / "test.tsv").read_bytes())
    return docs, queries, qrels, h.hexdigest()


# ---------------------------------------------------------------------------
# BM25 (Okapi, doc level) -- self-contained so the pool is dependency-free
# and byte-reproducible.
# ---------------------------------------------------------------------------

_TOKEN_RE = re.compile(r"\w+", re.UNICODE)


def _tok(text: str) -> list[str]:
    return _TOKEN_RE.findall(text.lower())


class BM25:
    def __init__(self, corpus_tokens: list[list[str]], k1: float = 1.2,
                 b: float = 0.75):
        self.k1, self.b = k1, b
        self.n = len(corpus_tokens)
        self.lens = np.array([len(t) for t in corpus_tokens], dtype=np.float32)
        self.avgdl = float(self.lens.mean()) if self.n else 0.0
        self.tf: list[Counter] = [Counter(t) for t in corpus_tokens]
        df: Counter = Counter()
        for c in self.tf:
            df.update(c.keys())
        self.idf = {
            t: math.log(1.0 + (self.n - d + 0.5) / (d + 0.5)) for t, d in df.items()
        }
        self.postings: dict[str, list[int]] = {}
        for i, c in enumerate(self.tf):
            for t in c:
                self.postings.setdefault(t, []).append(i)

    def scores(self, query: str) -> np.ndarray:
        out = np.zeros(self.n, dtype=np.float32)
        norm = self.k1 * (1 - self.b + self.b * self.lens / max(self.avgdl, 1e-9))
        for t in _tok(query):
            idf = self.idf.get(t)
            if idf is None:
                continue
            for i in self.postings[t]:
                f = self.tf[i][t]
                out[i] += idf * f * (self.k1 + 1) / (f + norm[i])
        return out


# ---------------------------------------------------------------------------
# Stage 1: pool construction
# ---------------------------------------------------------------------------

def build_pool(args) -> int:
    import torch
    from transformers import AutoModel, AutoTokenizer

    docs, queries, qrels, sig = load_dataset(args.dataset, args.datasets_dir)
    q_ids = [q for q in queries if q in qrels and qrels[q]]
    doc_ids = list(docs)
    print(f"{args.dataset}: {len(doc_ids)} docs, {len(q_ids)} judged queries",
          flush=True)

    tok = AutoTokenizer.from_pretrained(EMBEDDER_ID, trust_remote_code=True)
    model = AutoModel.from_pretrained(
        EMBEDDER_ID, trust_remote_code=True, torch_dtype=torch.float16
    ).to(args.device).eval()

    # --- chunk every doc at index-side granularity -------------------------
    chunk_texts: list[str] = []
    chunk_owner: list[int] = []
    for di, d in enumerate(doc_ids):
        text = docs[d]
        enc = tok(text, add_special_tokens=False, truncation=False,
                  return_offsets_mapping=True, return_tensors=None)
        offs = enc["offset_mapping"]
        if not offs:
            chunk_texts.append(text[:EVIDENCE_CAP_CHARS]); chunk_owner.append(di)
            continue
        stride = CHUNK_TOKENS - CHUNK_OVERLAP
        s = 0
        while s < len(offs):
            e = min(s + CHUNK_TOKENS, len(offs))
            chunk_texts.append(text[offs[s][0]:offs[e - 1][1]][:EVIDENCE_CAP_CHARS])
            chunk_owner.append(di)
            if e == len(offs):
                break
            s += stride
    print(f"chunks: {len(chunk_texts)}", flush=True)

    def embed(texts: list[str], max_len: int) -> np.ndarray:
        out = []
        with torch.no_grad():
            for i in range(0, len(texts), args.batch_size):
                enc = tok(texts[i:i + args.batch_size], padding=True,
                          truncation=True, max_length=max_len,
                          return_tensors="pt").to(args.device)
                h = model(**enc).last_hidden_state[:, 0]
                out.append(torch.nn.functional.normalize(h.float(), dim=-1)
                           .cpu().numpy())
        return np.concatenate(out, axis=0)

    t0 = time.time()
    cvecs = embed(chunk_texts, CHUNK_TOKENS + 16)
    qvecs = embed([queries[q] for q in q_ids], 512)
    print(f"embedded in {time.time() - t0:.0f}s", flush=True)
    del model
    torch.cuda.empty_cache()

    owner = np.asarray(chunk_owner)
    bm25 = BM25([_tok(docs[d]) for d in doc_ids])

    pool_records = []
    for qi, q in enumerate(q_ids):
        csim = cvecs @ qvecs[qi]
        # per-doc max chunk (dense chunk-MaxP) + argmax chunk = evidence preview
        best_score = np.full(len(doc_ids), -1e9, dtype=np.float32)
        best_chunk = np.zeros(len(doc_ids), dtype=np.int64)
        np.maximum.at(best_score, owner, csim)
        hit = csim >= best_score[owner] - 1e-12
        for ci in np.nonzero(hit)[0]:
            best_chunk[owner[ci]] = ci
        bs = bm25.scores(queries[q])
        k = min(args.pool_dense, len(doc_ids))
        dense_top = np.argpartition(-best_score, k - 1)[:k]
        k2 = min(args.pool_bm25, len(doc_ids))
        bm_top = np.argpartition(-bs, k2 - 1)[:k2]
        pool = sorted(set(dense_top.tolist()) | set(bm_top.tolist()))
        pool_records.append({
            "qid": q,
            "query": queries[q],
            "candidates": [
                {
                    "docid": doc_ids[i],
                    "bm25": float(bs[i]),
                    "dense": float(best_score[i]),
                    "evidence": chunk_texts[int(best_chunk[i])],
                }
                for i in pool
            ],
        })

    out = {
        "dataset": args.dataset,
        "corpus_signature": sig,
        "n_docs": len(doc_ids),
        "n_queries": len(q_ids),
        "pool_bm25_k": args.pool_bm25,
        "pool_dense_k": args.pool_dense,
        "embedder": EMBEDDER_ID,
        "chunking": {"tokens": CHUNK_TOKENS, "overlap": CHUNK_OVERLAP,
                     "cap_chars": EVIDENCE_CAP_CHARS},
        "qrels": {q: qrels[q] for q in q_ids},
        "queries": pool_records,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out / f"{args.dataset.replace('/', '_')}.json"
    path.write_text(json.dumps(out), encoding="utf-8")

    sizes = [len(r["candidates"]) for r in pool_records]
    covered = sum(
        1 for r in pool_records
        if any(c["docid"] in qrels[r["qid"]] and qrels[r["qid"]][c["docid"]] > 0
               for c in r["candidates"])
    )
    print(f"wrote {path}  pool mean={np.mean(sizes):.1f} "
          f"min={min(sizes)} max={max(sizes)}  "
          f"queries with >=1 gold in pool: {covered}/{len(pool_records)}",
          flush=True)
    return 0


# ---------------------------------------------------------------------------
# Stage 2: rerank
# ---------------------------------------------------------------------------

class CrossEncoder:
    def __init__(self, recipe: dict, device: str, max_len_override: int | None = None):
        import torch
        self.torch = torch
        recipe = dict(recipe)
        if max_len_override:
            recipe["max_len"] = max_len_override
        self.recipe = recipe
        self.device = device
        hf = recipe.get("load_path", recipe["hf_id"])
        trc = recipe.get("trust_remote_code", False)
        if recipe["kind"] == "st":
            from sentence_transformers import CrossEncoder as STCrossEncoder
            self.st = STCrossEncoder(
                hf, device=device, trust_remote_code=trc,
                model_kwargs={"torch_dtype": torch.float16},
            )
            self.st.max_seq_length = recipe["max_len"]
            self.model = self.st.model
            self.max_len = recipe["max_len"]
        else:
            from transformers import (AutoModelForSequenceClassification,
                                      AutoTokenizer)
            self.tok = AutoTokenizer.from_pretrained(hf, trust_remote_code=trc)
            self.model = AutoModelForSequenceClassification.from_pretrained(
                hf, trust_remote_code=trc, torch_dtype=torch.float16
            ).to(device).eval()
            model_max = getattr(self.tok, "model_max_length", None)
            self.max_len = recipe["max_len"]
            if model_max and model_max < 10 ** 6:
                self.max_len = min(self.max_len, model_max)
        self.n_params = sum(p.numel() for p in self.model.parameters())

    def score(self, query: str, passages: list[str], batch_size: int) -> np.ndarray:
        torch = self.torch
        if self.recipe["kind"] == "st":
            # identity activation: a saturating sigmoid would collapse the top
            # of the candidate list into rank ties and silently flatter/penalize
            # the model relative to raw-logit arms.
            s = self.st.predict(
                [(query, p) for p in passages], batch_size=batch_size,
                activation_fn=torch.nn.Identity(), show_progress_bar=False,
            )
            return np.asarray(s, dtype=np.float64).reshape(-1)
        out = []
        with torch.no_grad():
            for i in range(0, len(passages), batch_size):
                batch = passages[i:i + batch_size]
                # Production truncation semantics (RerankerTokenizer.pack): the
                # pair is encoded WITHOUT truncation and then hard-cut to the
                # FIRST maxLength ids -- so the query survives whole and the
                # passage is head-truncated.  HF's `only_second` raises when the
                # query alone exceeds max_length (real on legal-clerc, whose
                # citation queries run 400+ tokens at 512), so the cut is done
                # here explicitly rather than delegated.
                enc = self.tok([query] * len(batch), batch, padding=True,
                               truncation=False, return_tensors="pt")
                enc = {k: v[:, :self.max_len].to(self.device)
                       for k, v in enc.items()}
                logits = self.model(**enc).logits.float()
                s = logits[:, -1] if logits.shape[-1] > 1 else logits[:, 0]
                out.append(s.cpu().numpy())
        return np.concatenate(out)


def _dcg(rels: list[int]) -> float:
    return sum(r / math.log2(i + 2) for i, r in enumerate(rels))


def ndcg_at_k(ranked_docids: list[str], rel: dict[str, int], k: int) -> float:
    gains = [rel.get(d, 0) for d in ranked_docids[:k]]
    ideal = sorted(rel.values(), reverse=True)[:k]
    idcg = _dcg(ideal)
    return _dcg(gains) / idcg if idcg > 0 else 0.0


def rerank(args) -> int:
    import torch
    pool = json.loads(args.pool.read_text(encoding="utf-8"))
    recipe = RECIPES[args.model]
    torch.cuda.reset_peak_memory_stats()
    ce = CrossEncoder(recipe, args.device, args.max_len)

    per_query = []
    n_pairs = 0
    t0 = time.time()
    for rec in pool["queries"]:
        rel = pool["qrels"][rec["qid"]]
        cands = rec["candidates"]
        # pre-rerank reference order: RRF of the two offline legs
        bm_rank = {c["docid"]: r for r, c in enumerate(
            sorted(cands, key=lambda c: -c["bm25"]))}
        dn_rank = {c["docid"]: r for r, c in enumerate(
            sorted(cands, key=lambda c: -c["dense"]))}
        by_id = {c["docid"]: c for c in cands}
        fused = sorted(
            (c["docid"] for c in cands),
            key=lambda d: -(1 / (60 + bm_rank[d] + 1) + 1 / (60 + dn_rank[d] + 1)),
        )
        # Production reranks only the first `justsearch.rerank.top_k` of the
        # fused order and leaves the tail in fusion order; reranking the whole
        # pool would both flatter and punish a CE beyond what a live swap can do.
        k = len(fused) if args.rerank_top_k <= 0 else min(args.rerank_top_k,
                                                          len(fused))
        head, tail = fused[:k], fused[k:]
        passages = [by_id[d]["evidence"] for d in head]
        t1 = time.time()
        scores = ce.score(rec["query"], passages, args.batch_size)
        elapsed_ms = (time.time() - t1) * 1000.0
        n_pairs += len(passages)
        order = np.argsort(-scores, kind="stable")
        ranked = [head[i] for i in order] + tail
        per_query.append({
            "qid": rec["qid"],
            "n_cand": len(cands),
            "ndcg@10": ndcg_at_k(ranked, rel, 10),
            "p@1": 1.0 if rel.get(ranked[0], 0) > 0 else 0.0,
            "r@10": (len([d for d in ranked[:10] if rel.get(d, 0) > 0])
                     / max(1, len([d for d, r in rel.items() if r > 0]))),
            "ndcg@10_fusion": ndcg_at_k(fused, rel, 10),
            "query_ms": round(elapsed_ms, 2),
            "top10": ranked[:10],
        })
    total_s = time.time() - t0
    peak_vram_mb = torch.cuda.max_memory_allocated() / (1024 ** 2)

    def mean(key):
        return round(float(np.mean([p[key] for p in per_query])), 4)

    lat = sorted(p["query_ms"] for p in per_query)
    result = {
        "experiment": "reranker_bakeoff_f052",
        "model": args.model,
        "hf_id": recipe["hf_id"],
        "kind": recipe["kind"],
        "note": recipe.get("note", ""),
        "n_params": ce.n_params,
        "max_len": ce.max_len,
        "dataset": pool["dataset"],
        "corpus_signature": pool["corpus_signature"],
        "pool_bm25_k": pool["pool_bm25_k"],
        "pool_dense_k": pool["pool_dense_k"],
        "n_queries": len(per_query),
        "n_pairs": n_pairs,
        "rerank_top_k": args.rerank_top_k,
        "device": args.device,
        "batch_size": args.batch_size,
        "metrics": {
            "nDCG@10": mean("ndcg@10"),
            "P@1": mean("p@1"),
            "R@10": mean("r@10"),
            "nDCG@10_fusion_order": mean("ndcg@10_fusion"),
        },
        "latency": {
            "per_query_ms_p50": round(lat[len(lat) // 2], 2),
            "per_query_ms_mean": round(float(np.mean(lat)), 2),
            "per_pair_ms_mean": round(sum(lat) / max(n_pairs, 1), 3),
            "total_s": round(total_s, 1),
        },
        "peak_vram_mb": round(peak_vram_mb, 1),
        "per_query": per_query,
    }
    args.out.mkdir(parents=True, exist_ok=True)
    label = f"{args.model}__{pool['dataset'].replace('/', '_')}{args.tag}"
    (args.out / f"{label}.json").write_text(json.dumps(result, indent=1),
                                            encoding="utf-8")
    m = result["metrics"]
    print(f"{label}: nDCG@10={m['nDCG@10']} P@1={m['P@1']} R@10={m['R@10']} "
          f"(fusion-order {m['nDCG@10_fusion_order']}) "
          f"| {result['latency']['per_query_ms_p50']}ms/query p50, "
          f"{result['latency']['per_pair_ms_mean']}ms/pair, "
          f"{result['peak_vram_mb']}MB peak, {ce.n_params / 1e6:.0f}M params",
          flush=True)
    return 0


# ---------------------------------------------------------------------------
# Stage 3: paired comparison (sign test, mirrors 708)
# ---------------------------------------------------------------------------

def sign_test(deltas: list[float]) -> tuple[int, int, int, float]:
    """Two-sided exact sign test over non-tied paired differences."""
    pos = sum(1 for d in deltas if d > 1e-12)
    neg = sum(1 for d in deltas if d < -1e-12)
    ties = len(deltas) - pos - neg
    n = pos + neg
    if n == 0:
        return pos, neg, ties, 1.0
    k = min(pos, neg)
    tail = sum(math.comb(n, i) for i in range(0, k + 1)) / (2 ** n)
    return pos, neg, ties, min(1.0, 2 * tail)


def compare(args) -> int:
    files = sorted(args.results.glob(f"*{args.tag}.json"))
    by_dataset: dict[str, dict[str, dict]] = {}
    for f in files:
        if args.tag and not f.stem.endswith(args.tag):
            continue
        if not args.tag and "@" in f.stem:
            continue  # tagged conditions are compared on their own
        r = json.loads(f.read_text(encoding="utf-8"))
        by_dataset.setdefault(r["dataset"], {})[r["model"]] = r
    for ds, arms in sorted(by_dataset.items()):
        anchor = arms.get(args.anchor)
        print(f"\n=== {ds} (anchor={args.anchor}) ===")
        if anchor is None:
            print("  anchor missing"); continue
        a_by_q = {p["qid"]: p["ndcg@10"] for p in anchor["per_query"]}
        print(f"{'model':22} {'nDCG@10':>8} {'delta':>8} {'rel%':>7} "
              f"{'+/-/=':>12} {'p':>7} {'ms/pair':>8} {'VRAM':>7} {'params':>8}")
        for name, r in sorted(arms.items(),
                              key=lambda kv: -kv[1]["metrics"]["nDCG@10"]):
            deltas = [p["ndcg@10"] - a_by_q[p["qid"]]
                      for p in r["per_query"] if p["qid"] in a_by_q]
            pos, neg, ties, p = sign_test(deltas)
            d = r["metrics"]["nDCG@10"] - anchor["metrics"]["nDCG@10"]
            rel = 100 * d / max(anchor["metrics"]["nDCG@10"], 1e-9)
            print(f"{name:22} {r['metrics']['nDCG@10']:8.4f} {d:+8.4f} "
                  f"{rel:+6.1f}% {f'{pos}/{neg}/{ties}':>12} {p:7.4f} "
                  f"{r['latency']['per_pair_ms_mean']:8.3f} "
                  f"{r['peak_vram_mb']:7.0f} {r['n_params'] / 1e6:7.0f}M")
    return 0


# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--stage", required=True, choices=["pool", "rerank", "compare"])
    ap.add_argument("--dataset", help="mixed/<name> or beir/<name>")
    ap.add_argument("--datasets-dir", type=Path,
                    default=Path(os.environ.get("JSEVAL_DATASETS_DIR", "datasets")))
    ap.add_argument("--model", choices=sorted(RECIPES))
    ap.add_argument("--pool", type=Path)
    ap.add_argument("--results", type=Path)
    ap.add_argument("--anchor", default="anchor")
    ap.add_argument("--out", type=Path)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--batch-size", type=int, default=16)
    ap.add_argument("--max-len", type=int, default=None,
                    help="override every recipe's max_len (512 = the shipped "
                         "`justsearch.rerank.max_seq_len` default, i.e. the "
                         "production-faithful condition)")
    ap.add_argument("--rerank-top-k", type=int, default=0,
                    help="rerank only the first N of the fused order "
                         "(20 = the shipped `justsearch.rerank.top_k`; "
                         "0 = whole pool)")
    ap.add_argument("--tag", default="",
                    help="suffix for the result filename, to keep conditions apart")
    ap.add_argument("--pool-bm25", type=int, default=30)
    ap.add_argument("--pool-dense", type=int, default=30)
    args = ap.parse_args()

    if args.stage == "pool":
        return build_pool(args)
    if args.stage == "rerank":
        return rerank(args)
    return compare(args)


if __name__ == "__main__":
    sys.exit(main())
