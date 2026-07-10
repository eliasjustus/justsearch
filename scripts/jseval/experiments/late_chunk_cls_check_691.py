#!/usr/bin/env python3
"""Offline late-chunking-vs-per-chunk-CLS check on legal-clerc-200 (tempdoc 691 Phase M).

QUESTION: on a CLS-pooled embedding model (gte-multilingual-base), do "late
chunking" chunk vectors (span-mean-pooled from ONE long-context forward pass)
retrieve WORSE than today's per-chunk CLS embeddings? Literature (late
chunking, arXiv:2409.04701 — technique cited as prior art, no code copied
from it) predicts a regression on CLS-pooled encoders because a single
[CLS] vector was trained to summarize a whole sequence, not to feed a
downstream span-mean-pool. This experiment measures the regression (or
lack of one) on this corpus, in isolation from every other engine
concern (no reindex, no ANN, no fusion) — it decides whether the engine's
"chunk half" feature ships. No conclusion about shipping is drawn here;
this script only produces the C vs LC measurement.

Conditions (identical chunk boundaries for both — the comparison isolates
vector derivation, not text coverage):
  C   (production-mirror) — each 500-token/50-overlap chunk's text is
      embedded independently: full forward pass per chunk, CLS token
      (position 0), L2-normalize. Mirrors today's per-chunk indexing path.
  LC  (late chunking) — ONE forward pass over the full (<=8192-token) doc;
      for each chunk span, masked-mean-pool the token hidden states whose
      offsets fall inside that chunk's token range from THIS SAME pass
      (special/padding tokens carry a (0,0) offset and are excluded by
      construction), then L2-normalize. The doc-level CLS from this pass
      is NOT used or scored.

Chunking: tokenize each doc with offsets (add_special_tokens=True,
truncation=True, max_length=8192) — this is also literally the LC forward
pass's tokenization, so chunk token-ranges and LC's hidden-state positions
line up exactly by construction (equivalent to a char-offset-intersection
test, since the chunk's char span is defined as the min/max offset of
those same tokens). Content tokens (non-special, i.e. offset != (0,0)) are
split into 500-token windows with 50-token overlap (stride 450), no
tail-merge. A doc is counted "truncated" if its untruncated content-token
count (add_special_tokens=False) exceeds 8192-2.

Queries: embedded once, CLS, no prefix, L2-normalize; shared by both
conditions (queries.jsonl — the "verbose" query set, same file tempdoc 708's
--queries verbose used by default).

Scoring: cosine query->chunk; per-doc score = max over its chunks (MaxP);
rank docs per query; nDCG@10 / R@10 / R@100 via ir_measures. Per-query
win/loss is the per-query nDCG@10 comparison between C and LC.

Model recipe (mirrors production / tempdoc 708's "anchor" recipe): HF
Alibaba-NLP/gte-multilingual-base, trust_remote_code=True, CLS pooling,
NO query/doc prefixes.

Run (from the 691 worktree; HF_HOME below points at a local copy of tempdoc
708's cached model+trust_remote_code snapshot, python.exe below is the
reused 708 bake-off venv):
  HF_HOME=tmp/691-cls-check/hf-cache HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
    <path-to-708-venv>/Scripts/python.exe \
    scripts/jseval/experiments/late_chunk_cls_check_691.py \
    --dataset-dir datasets/mixed/legal-clerc-200 \
    --out tmp/691-cls-check --device cuda --batch-size 16
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

MODEL_ID = "Alibaba-NLP/gte-multilingual-base"
MAX_CTX = 8192
CHUNK_TOKENS = 500
CHUNK_OVERLAP = 50
CHUNK_STRIDE = CHUNK_TOKENS - CHUNK_OVERLAP


# ---------------------------------------------------------------------------
# Data loading (jseval BEIR-style layout — same as tempdoc 708's harness)
# ---------------------------------------------------------------------------

def load_corpus(dataset_dir: Path) -> dict[str, str]:
    docs: dict[str, str] = {}
    for line in (dataset_dir / "corpus.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        text = " ".join(p for p in (obj.get("title", ""), obj.get("text", "")) if p)
        docs[str(obj["_id"])] = text
    return docs


def load_queries(dataset_dir: Path) -> dict[str, str]:
    queries: dict[str, str] = {}
    for line in (dataset_dir / "queries.jsonl").read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        obj = json.loads(line)
        queries[str(obj["_id"])] = obj["text"]
    return queries


def load_qrels(dataset_dir: Path) -> dict[str, dict[str, int]]:
    qrels: dict[str, dict[str, int]] = {}
    path = dataset_dir / "qrels" / "test.tsv"
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        if not line.strip():
            continue
        parts = line.split("\t")
        if i == 0 and not parts[-1].strip().lstrip("-").isdigit():
            continue  # header
        qid, did, rel = parts[0], parts[-2], int(parts[-1])
        qrels.setdefault(str(qid), {})[str(did)] = rel
    return qrels


# ---------------------------------------------------------------------------
# Chunking (identical for both conditions)
# ---------------------------------------------------------------------------

class DocChunks:
    __slots__ = ("doc_id", "spans", "input_ids", "content_positions", "truncated")

    def __init__(self, doc_id, spans, input_ids, content_positions, truncated):
        self.doc_id = doc_id
        self.spans = spans  # list of (char_start, char_end, [token positions in input_ids])
        self.input_ids = input_ids
        self.content_positions = content_positions
        self.truncated = truncated


def build_doc_chunks(tokenizer, doc_id: str, text: str) -> DocChunks:
    # untruncated content-token count, to detect truncation
    untrunc = tokenizer(text, add_special_tokens=False, truncation=False,
                        return_tensors=None)["input_ids"]
    truncated = len(untrunc) > (MAX_CTX - 2)

    enc = tokenizer(text, add_special_tokens=True, truncation=True,
                    max_length=MAX_CTX, return_offsets_mapping=True,
                    return_tensors=None)
    input_ids = enc["input_ids"]
    offsets = enc["offset_mapping"]

    content_positions = [i for i, (a, b) in enumerate(offsets) if not (a == 0 and b == 0)]

    spans = []
    n = len(content_positions)
    start = 0
    while start < n:
        end = min(start + CHUNK_TOKENS, n)
        idxs = content_positions[start:end]
        char_start = offsets[idxs[0]][0]
        char_end = offsets[idxs[-1]][1]
        spans.append((char_start, char_end, idxs))
        if end == n:
            break
        start += CHUNK_STRIDE

    return DocChunks(doc_id, spans, input_ids, content_positions, truncated)


# ---------------------------------------------------------------------------
# Encoder
# ---------------------------------------------------------------------------

class Encoder:
    def __init__(self, device: str):
        import torch
        from transformers import AutoModel, AutoTokenizer

        self.device = device
        self.torch = torch
        self.dtype = torch.float32 if device == "cpu" else torch.float16
        self.tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, trust_remote_code=True)
        self.model = AutoModel.from_pretrained(
            MODEL_ID, trust_remote_code=True, torch_dtype=self.dtype
        ).to(device)
        self.model.eval()

    def l2(self, vecs):
        return self.torch.nn.functional.normalize(vecs.float(), dim=-1)

    def encode_cls_texts(self, texts: list[str], batch_size: int, max_len: int) -> np.ndarray:
        """Condition C / queries: independent forward pass per batch of texts, CLS pool."""
        out = []
        with self.torch.no_grad():
            for i in range(0, len(texts), batch_size):
                batch = texts[i:i + batch_size]
                enc = self.tokenizer(
                    batch, padding=True, truncation=True, max_length=max_len,
                    return_tensors="pt",
                ).to(self.device)
                hidden = self.model(**enc).last_hidden_state
                cls = hidden[:, 0]
                out.append(self.l2(cls).cpu().numpy())
        return np.concatenate(out, axis=0)

    def encode_late_chunk_doc(self, dc: DocChunks) -> np.ndarray:
        """Condition LC: ONE forward pass over the full (<=8192-tok) doc;
        masked-mean-pool each chunk's token positions from that single pass."""
        ids = self.torch.tensor([dc.input_ids], dtype=self.torch.long, device=self.device)
        mask = self.torch.ones_like(ids)
        with self.torch.no_grad():
            hidden = self.model(input_ids=ids, attention_mask=mask).last_hidden_state[0]
        vecs = []
        for (_cs, _ce, idxs) in dc.spans:
            pos = self.torch.tensor(idxs, dtype=self.torch.long, device=self.device)
            pooled = hidden.index_select(0, pos).mean(dim=0, keepdim=True)
            vecs.append(self.l2(pooled).cpu().numpy()[0])
        return np.stack(vecs) if vecs else np.zeros((0, hidden.shape[-1]), dtype=np.float32)


# ---------------------------------------------------------------------------
# Scoring / metrics
# ---------------------------------------------------------------------------

def score_maxp(q_vecs: np.ndarray, doc_ids: list[str], doc_chunk_vecs: list[np.ndarray]) -> np.ndarray:
    n_q = q_vecs.shape[0]
    scores = np.full((n_q, len(doc_ids)), -1.0, dtype=np.float32)
    for j, cv in enumerate(doc_chunk_vecs):
        if cv.shape[0] == 0:
            continue
        scores[:, j] = (q_vecs @ cv.T).max(axis=1)
    return scores


def compute_metrics(scores: np.ndarray, q_ids: list[str], doc_ids: list[str],
                    qrels: dict[str, dict[str, int]]):
    import ir_measures
    from ir_measures import nDCG, R

    run = {}
    order = np.argsort(-scores, axis=1)
    for i, qid in enumerate(q_ids):
        if qid not in qrels:
            continue
        run[qid] = {doc_ids[j]: float(scores[i, j]) for j in order[i][:1000]}
    measures = [R @ 10, R @ 100, nDCG @ 10]
    agg = ir_measures.calc_aggregate(measures, qrels, run)
    per_query_ndcg10 = {}
    for m in ir_measures.iter_calc([nDCG @ 10], qrels, run):
        per_query_ndcg10[m.query_id] = m.value
    return {
        "R@10": round(agg[R @ 10], 4),
        "R@100": round(agg[R @ 100], 4),
        "nDCG@10": round(agg[nDCG @ 10], 4),
        "judged_queries": len(run),
    }, per_query_ndcg10


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset-dir", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--batch-size", type=int, default=16,
                    help="batch size for Condition C's per-chunk forward passes")
    ap.add_argument("--smoke", type=int, default=0,
                    help="limit to N docs + their gold queries (self-check)")
    args = ap.parse_args()

    t_start = time.time()
    docs = load_corpus(args.dataset_dir)
    queries = load_queries(args.dataset_dir)
    qrels = load_qrels(args.dataset_dir)

    if args.smoke:
        doc_ids0 = list(docs.keys())[: args.smoke]
        docs = {d: docs[d] for d in doc_ids0}
        keep_q = [q for q, rels in qrels.items() if any(d in docs for d in rels)]
        queries = {q: queries[q] for q in keep_q if q in queries}
        qrels = {q: {d: r for d, r in qrels[q].items() if d in docs} for q in keep_q}

    enc = Encoder(args.device)

    print(f"tokenizing + chunking {len(docs)} docs...", flush=True)
    doc_ids = list(docs.keys())
    all_chunks: dict[str, DocChunks] = {}
    n_truncated = 0
    n_chunks_total = 0
    for d in doc_ids:
        dc = build_doc_chunks(enc.tokenizer, d, docs[d])
        all_chunks[d] = dc
        n_truncated += int(dc.truncated)
        n_chunks_total += len(dc.spans)
    print(f"docs_truncated={n_truncated}/{len(doc_ids)}  total_chunks={n_chunks_total}"
          f"  avg_chunks/doc={n_chunks_total / max(len(doc_ids), 1):.2f}", flush=True)

    # ---- Condition C: independent per-chunk forward pass, CLS pool ----
    t0 = time.time()
    flat_texts = []
    flat_owner = []  # (doc_idx, chunk_idx)
    for di, d in enumerate(doc_ids):
        dc = all_chunks[d]
        for ci, (cs, ce, _idxs) in enumerate(dc.spans):
            flat_texts.append(docs[d][cs:ce])
            flat_owner.append((di, ci))
    flat_vecs_c = enc.encode_cls_texts(flat_texts, args.batch_size, CHUNK_TOKENS + 16)
    doc_chunk_vecs_c: list[np.ndarray] = [
        np.zeros((len(all_chunks[d].spans), flat_vecs_c.shape[1]), dtype=np.float32)
        for d in doc_ids
    ]
    for (di, ci), vec in zip(flat_owner, flat_vecs_c):
        doc_chunk_vecs_c[di][ci] = vec
    c_embed_secs = time.time() - t0
    print(f"Condition C embedded in {c_embed_secs:.1f}s", flush=True)

    # ---- Condition LC: one forward pass per doc, masked-mean-pool spans ----
    t0 = time.time()
    doc_chunk_vecs_lc: list[np.ndarray] = []
    for d in doc_ids:
        doc_chunk_vecs_lc.append(enc.encode_late_chunk_doc(all_chunks[d]))
    lc_embed_secs = time.time() - t0
    print(f"Condition LC embedded in {lc_embed_secs:.1f}s", flush=True)

    # ---- Queries: CLS, no prefix, shared ----
    q_ids = [q for q in queries if q in qrels]
    q_vecs = enc.encode_cls_texts([queries[q] for q in q_ids], args.batch_size, 512)

    # ---- Score + metrics ----
    scores_c = score_maxp(q_vecs, doc_ids, doc_chunk_vecs_c)
    scores_lc = score_maxp(q_vecs, doc_ids, doc_chunk_vecs_lc)
    metrics_c, pq_c = compute_metrics(scores_c, q_ids, doc_ids, qrels)
    metrics_lc, pq_lc = compute_metrics(scores_lc, q_ids, doc_ids, qrels)

    wins_c = wins_lc = ties = 0
    per_query = {}
    for qid in q_ids:
        if qid not in pq_c or qid not in pq_lc:
            continue
        vc, vlc = pq_c[qid], pq_lc[qid]
        per_query[qid] = {"C_nDCG@10": round(vc, 4), "LC_nDCG@10": round(vlc, 4)}
        if abs(vc - vlc) < 1e-9:
            ties += 1
        elif vc > vlc:
            wins_c += 1
        else:
            wins_lc += 1

    total_secs = time.time() - t_start
    gpu_name = None
    precision = "fp32" if args.device == "cpu" else "fp16"
    if args.device != "cpu":
        try:
            gpu_name = enc.torch.cuda.get_device_name(0)
        except Exception:
            gpu_name = None

    result = {
        "tempdoc": 691,
        "phase": "M",
        "question": (
            "Do late-chunking (span-mean-pool, one long-context pass) chunk "
            "vectors retrieve worse than per-chunk CLS embeddings on a "
            "CLS-pooled encoder?"
        ),
        "model": MODEL_ID,
        "dataset_dir": str(args.dataset_dir),
        "queries_label": "verbose (queries.jsonl, same file as tempdoc 708 default)",
        "n_docs": len(doc_ids),
        "n_queries": len(q_ids),
        "docs_truncated_at_8192tok": n_truncated,
        "total_chunks": n_chunks_total,
        "avg_chunks_per_doc": round(n_chunks_total / max(len(doc_ids), 1), 3),
        "chunking": {"chunk_tokens": CHUNK_TOKENS, "overlap_tokens": CHUNK_OVERLAP,
                     "max_ctx_tokens": MAX_CTX},
        "device": args.device,
        "gpu_name": gpu_name,
        "precision": precision,
        "batch_size_condition_C": args.batch_size,
        "condition_C_embed_seconds": round(c_embed_secs, 1),
        "condition_LC_embed_seconds": round(lc_embed_secs, 1),
        "total_runtime_seconds": round(total_secs, 1),
        "smoke": args.smoke or None,
        "metrics": {
            "C": metrics_c,
            "LC": metrics_lc,
        },
        "per_query_win_loss": {"C_wins": wins_c, "LC_wins": wins_lc, "ties": ties},
        "per_query_ndcg10": per_query,
    }

    args.out.mkdir(parents=True, exist_ok=True)
    out_json = args.out / "results.json"
    out_json.write_text(json.dumps(result, indent=1), encoding="utf-8")

    delta_ndcg = metrics_lc["nDCG@10"] - metrics_c["nDCG@10"]
    delta_r10 = metrics_lc["R@10"] - metrics_c["R@10"]
    delta_r100 = metrics_lc["R@100"] - metrics_c["R@100"]
    summary_md = f"""# Late chunking vs per-chunk CLS — tempdoc 691 Phase M

Model: `{MODEL_ID}` (trust_remote_code, CLS pooling, no prefixes)
Corpus: `{args.dataset_dir}` ({len(doc_ids)} docs, {len(q_ids)} judged queries)
Chunking: {CHUNK_TOKENS}-token windows / {CHUNK_OVERLAP}-token overlap over the
first {MAX_CTX} tokens (identical spans for both conditions).
Docs truncated at {MAX_CTX} tokens: {n_truncated}/{len(doc_ids)}
Device: {args.device} ({gpu_name or 'n/a'}), precision: {precision}
Condition C embed time: {c_embed_secs:.1f}s | Condition LC embed time: {lc_embed_secs:.1f}s
Total runtime: {total_secs:.1f}s

## C (production-mirror: independent per-chunk CLS) vs LC (late chunking: span-mean-pool of one long-context pass)

| Metric | C | LC | Delta (LC - C) |
|---|---|---|---|
| nDCG@10 | {metrics_c['nDCG@10']} | {metrics_lc['nDCG@10']} | {delta_ndcg:+.4f} |
| R@10 | {metrics_c['R@10']} | {metrics_lc['R@10']} | {delta_r10:+.4f} |
| R@100 | {metrics_c['R@100']} | {metrics_lc['R@100']} | {delta_r100:+.4f} |

## Per-query win/loss (nDCG@10)

- C wins: {wins_c}
- LC wins: {wins_lc}
- Ties: {ties}
- Judged queries compared: {len(per_query)}

No shipping conclusion is drawn here — this is a measurement only (tempdoc 691 §Phase M).
"""
    out_md = args.out / "summary.md"
    out_md.write_text(summary_md, encoding="utf-8")

    print(f"C:  nDCG@10={metrics_c['nDCG@10']} R@10={metrics_c['R@10']} R@100={metrics_c['R@100']}")
    print(f"LC: nDCG@10={metrics_lc['nDCG@10']} R@10={metrics_lc['R@10']} R@100={metrics_lc['R@100']}")
    print(f"win/loss (nDCG@10): C={wins_c} LC={wins_lc} ties={ties}")
    print(f"wrote {out_json} and {out_md}")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
