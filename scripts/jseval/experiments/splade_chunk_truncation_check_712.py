#!/usr/bin/env python3
"""Offline SPLADE truncation check on legal-clerc-200 (tempdoc 712).

QUESTION: is the sparse (SPLADE) leg's near-zero nDCG@10 (~0.059) on
legal-clerc-200 a TRUNCATION artifact? Production SPLADE hard-truncates
every document to maxSeqLen=512 tokens (SpladeEncoder: seqLen =
min(len, maxSeqLen); the SpladeTruncationEvidence sidecar only *records*
how many windows a doc would need, it does NOT window). Median CLERC doc
is ~28.5k chars (~7k tokens), so production SPLADE sees only the first
~7% of each case document. This is the sparse-leg sibling of the dense
window-mean death (F-031/F-032), transposed to hard head-truncation.

This experiment mirrors 691 Phase M's structure (late_chunk_cls_check_691.py)
but for the SPLADE ONNX model, and compares:

  A  (production-mirror, truncated whole-doc) — encode each doc's full text;
     the tokenizer keeps the first maxSeqLen=512 tokens (right-truncation,
     matching production Math.min(len,maxSeqLen) + truncation_side:right);
     ONNX emits top-256 (idx,weight). This IS the production doc sparse vector.
     FIDELITY ANCHOR: A's nDCG@10 should approximately reproduce the register's
     dead splade-mode number (~0.059). If it does not, the harness diverges
     from production and B's delta cannot be trusted.

  B  (per-chunk SPLADE, whole-doc coverage) — split each doc into
     CHUNK_TOKENS/CHUNK_OVERLAP content-token windows over the ENTIRE doc
     (no 8192 cap; SPLADE has no long-context limit per chunk), reconstruct
     each chunk's text from char offsets, encode each chunk exactly like A
     (add specials, <=512 tok, top-256). Merge into doc-level three ways:
       B_max  — per-term MAX weight across the doc's chunks (doc-level
                FeatureField write shape); score = sparse-dot(query, merged).
       B_sum  — per-term SUM weight across chunks; score = sparse-dot.
       B_maxp — chunk-level MaxP: score = max over chunks of sparse-dot(
                query, chunk). (Requires chunk-level sparse fields; this is
                the direct analog of the dense chunk-CLS MaxP that reached
                0.64 offline in 691 Phase M.)

VERDICT LOGIC: if B >> A (e.g. B reaches the 0.3-0.6 range the dense leg
reached once its truncation/RMW deaths were fixed), the truncation
hypothesis is CONFIRMED and 712's design question is the write/merge shape.
If B ~= A (both near-dead), the sparse weakness is encoder-domain
(F-030(678)'s lane / 708's), not representation, and 712 closes null.

Model (production-shipped): models/splade/naver-splade-v3 — actually
opensearch-neural-sparse-encoding-multilingual-v1 (see build.json), the
SAME multilingual SPLADE encoder F-030(678) blamed. ONNX graph has PRESPARSE
ops baked in (ReLU, +1, log, ReduceMax over seq, TopK-256) so its output IS
the sparse vector; no activation reimplementation.

Scoring: sparse dot product over shared term indices, standard SPLADE
scoring. The A-vs-B comparison isolates representation (coverage), not the
exact Lucene FeatureField saturation function; absolute numbers are offline
proxies, the A/B DELTA is the finding.

Run (from the 712 worktree; system python has onnxruntime-gpu 1.24.4):
  PYTHONUTF8=1 python scripts/jseval/experiments/splade_chunk_truncation_check_712.py \
    --dataset-dir datasets/mixed/legal-clerc-200 \
    --model-dir F:/justsearch-public/models/splade/naver-splade-v3 \
    --out tmp/712-splade-check --device cuda
"""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import numpy as np

MAX_SEQ_LEN = 512          # production SpladeConfig default maxSequenceLength
CHUNK_TOKENS = 500         # mirrors 691 Phase M chunking (content tokens/window)
CHUNK_OVERLAP = 50
CHUNK_STRIDE = CHUNK_TOKENS - CHUNK_OVERLAP
SKIP_TOKEN_IDS = {0, 100, 101, 102, 103}  # PAD,UNK,CLS,SEP,MASK (SpladeEncoder.SKIP_TOKEN_IDS)


# ---------------------------------------------------------------------------
# Data loading (jseval BEIR-style layout)
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
# SPLADE ONNX encoder (mirrors SpladeEncoder: truncate to maxSeqLen, top-256)
# ---------------------------------------------------------------------------

class SpladeOnnx:
    def __init__(self, model_dir: Path, device: str):
        import onnxruntime as ort
        from tokenizers import Tokenizer

        model_file = "model_fp16.onnx" if device == "cuda" else "model.onnx"
        model_path = model_dir / model_file
        providers = (["CUDAExecutionProvider", "CPUExecutionProvider"]
                     if device == "cuda" else ["CPUExecutionProvider"])
        so = ort.SessionOptions()
        self.sess = ort.InferenceSession(str(model_path), so, providers=providers)
        self.actual_providers = self.sess.get_providers()
        self.model_file = model_file
        # DJL HuggingFaceTokenizer is loaded with truncation=false, padding=false in
        # production (SpladeEncoder.buildAssembly); we replicate that and truncate manually.
        self.tok = Tokenizer.from_file(str(model_dir / "tokenizer.json"))
        self.tok.no_truncation()
        self.tok.no_padding()

    def _encode_ids(self, texts: list[str]):
        """Tokenize with special tokens, right-truncate to MAX_SEQ_LEN (production mirror)."""
        encs = self.tok.encode_batch(texts, add_special_tokens=True)
        ids, mask, ttype = [], [], []
        maxlen = 0
        rows = []
        for e in encs:
            row = e.ids[:MAX_SEQ_LEN]
            rows.append(row)
            maxlen = max(maxlen, len(row))
        for row in rows:
            pad = maxlen - len(row)
            ids.append(row + [0] * pad)
            mask.append([1] * len(row) + [0] * pad)
            ttype.append([0] * maxlen)
        return (np.asarray(ids, dtype=np.int64),
                np.asarray(mask, dtype=np.int64),
                np.asarray(ttype, dtype=np.int64))

    def encode(self, texts: list[str], batch_size: int = 32) -> list[dict[int, float]]:
        """Returns one sparse vector (dict term_id->weight) per text; top-256, specials filtered."""
        out: list[dict[int, float]] = []
        for i in range(0, len(texts), batch_size):
            batch = texts[i:i + batch_size]
            ids, mask, ttype = self._encode_ids(batch)
            idx, wts = self.sess.run(
                ["output_idx", "output_weights"],
                {"input_ids": ids, "attention_mask": mask, "token_type_ids": ttype},
            )
            for r in range(idx.shape[0]):
                vec: dict[int, float] = {}
                for tid, w in zip(idx[r].tolist(), wts[r].tolist()):
                    if w <= 0.0 or tid in SKIP_TOKEN_IDS:
                        continue
                    vec[int(tid)] = float(w)
                out.append(vec)
        return out

    def chunk_spans(self, text: str):
        """Content-token windows (CHUNK_TOKENS/CHUNK_OVERLAP) over the WHOLE doc.
        Returns list of (char_start, char_end). Uses offsets from a single
        no-special tokenization; text is re-encoded per chunk by encode()."""
        enc = self.tok.encode(text, add_special_tokens=False)
        offsets = enc.offsets
        n = len(offsets)
        spans = []
        if n == 0:
            return spans
        start = 0
        while start < n:
            end = min(start + CHUNK_TOKENS, n)
            cs = offsets[start][0]
            ce = offsets[end - 1][1]
            spans.append((cs, ce))
            if end == n:
                break
            start += CHUNK_STRIDE
        return spans

    def token_count(self, text: str) -> int:
        return len(self.tok.encode(text, add_special_tokens=False).ids)


# ---------------------------------------------------------------------------
# Sparse merge + scoring
# ---------------------------------------------------------------------------

def merge_max(chunk_vecs: list[dict[int, float]]) -> dict[int, float]:
    merged: dict[int, float] = {}
    for cv in chunk_vecs:
        for t, w in cv.items():
            if w > merged.get(t, 0.0):
                merged[t] = w
    return merged


def merge_sum(chunk_vecs: list[dict[int, float]]) -> dict[int, float]:
    merged: dict[int, float] = {}
    for cv in chunk_vecs:
        for t, w in cv.items():
            merged[t] = merged.get(t, 0.0) + w
    return merged


def sparse_dot(q: dict[int, float], d: dict[int, float]) -> float:
    if len(q) > len(d):
        q, d = d, q
    return sum(w * d.get(t, 0.0) for t, w in q.items())


def score_docs(q_vecs, doc_ids, doc_vecs) -> np.ndarray:
    scores = np.zeros((len(q_vecs), len(doc_ids)), dtype=np.float32)
    for i, q in enumerate(q_vecs):
        for j, d in enumerate(doc_vecs):
            scores[i, j] = sparse_dot(q, d)
    return scores


def score_docs_maxp(q_vecs, doc_ids, doc_chunk_vecs) -> np.ndarray:
    scores = np.full((len(q_vecs), len(doc_ids)), 0.0, dtype=np.float32)
    for i, q in enumerate(q_vecs):
        for j, chunks in enumerate(doc_chunk_vecs):
            best = 0.0
            for cv in chunks:
                s = sparse_dot(q, cv)
                if s > best:
                    best = s
            scores[i, j] = best
    return scores


def compute_metrics(scores, q_ids, doc_ids, qrels):
    import ir_measures
    from ir_measures import nDCG, R
    run = {}
    order = np.argsort(-scores, axis=1)
    for i, qid in enumerate(q_ids):
        if qid not in qrels:
            continue
        run[qid] = {doc_ids[j]: float(scores[i, j]) for j in order[i][:1000]}
    agg = ir_measures.calc_aggregate([R @ 10, R @ 100, nDCG @ 10], qrels, run)
    per_q = {m.query_id: m.value for m in ir_measures.iter_calc([nDCG @ 10], qrels, run)}
    return {"R@10": round(agg[R @ 10], 4), "R@100": round(agg[R @ 100], 4),
            "nDCG@10": round(agg[nDCG @ 10], 4), "judged_queries": len(run)}, per_q


def win_loss(q_ids, pq_a, pq_b):
    a = b = t = 0
    for qid in q_ids:
        if qid not in pq_a or qid not in pq_b:
            continue
        va, vb = pq_a[qid], pq_b[qid]
        if abs(va - vb) < 1e-9:
            t += 1
        elif va > vb:
            a += 1
        else:
            b += 1
    return {"A_wins": a, "B_wins": b, "ties": t}


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset-dir", required=True, type=Path)
    ap.add_argument("--model-dir", required=True, type=Path)
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--batch-size", type=int, default=32)
    ap.add_argument("--smoke", type=int, default=0)
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

    enc = SpladeOnnx(args.model_dir, args.device)
    print(f"providers={enc.actual_providers} model={enc.model_file}", flush=True)

    doc_ids = list(docs.keys())

    # token-length distribution (truncation evidence)
    tok_counts = [enc.token_count(docs[d]) for d in doc_ids]
    n_truncated = sum(1 for c in tok_counts if c > MAX_SEQ_LEN)
    med_tok = int(np.median(tok_counts))
    max_tok = int(np.max(tok_counts))

    # ---- Condition A: truncated whole-doc (production mirror) ----
    t0 = time.time()
    doc_vecs_a = enc.encode([docs[d] for d in doc_ids], args.batch_size)
    a_secs = time.time() - t0
    print(f"A encoded {len(doc_ids)} docs in {a_secs:.1f}s", flush=True)

    # ---- Condition B: per-chunk over whole doc ----
    t0 = time.time()
    flat_texts, flat_owner = [], []
    doc_span_counts = []
    for di, d in enumerate(doc_ids):
        spans = enc.chunk_spans(docs[d])
        doc_span_counts.append(len(spans))
        for (cs, ce) in spans:
            flat_texts.append(docs[d][cs:ce])
            flat_owner.append(di)
    flat_chunk_vecs = enc.encode(flat_texts, args.batch_size)
    doc_chunk_vecs: list[list[dict]] = [[] for _ in doc_ids]
    for owner, cv in zip(flat_owner, flat_chunk_vecs):
        doc_chunk_vecs[owner].append(cv)
    b_secs = time.time() - t0
    n_chunks_total = len(flat_texts)
    print(f"B encoded {n_chunks_total} chunks in {b_secs:.1f}s", flush=True)

    doc_vecs_bmax = [merge_max(c) for c in doc_chunk_vecs]
    doc_vecs_bsum = [merge_sum(c) for c in doc_chunk_vecs]

    # ---- Queries ----
    q_ids = [q for q in queries if q in qrels]
    q_vecs = enc.encode([queries[q] for q in q_ids], args.batch_size)

    # ---- Score ----
    metrics = {}
    per_q = {}
    for name, sc in [
        ("A_truncated_wholedoc", score_docs(q_vecs, doc_ids, doc_vecs_a)),
        ("B_max_merge", score_docs(q_vecs, doc_ids, doc_vecs_bmax)),
        ("B_sum_merge", score_docs(q_vecs, doc_ids, doc_vecs_bsum)),
        ("B_maxp_chunk", score_docs_maxp(q_vecs, doc_ids, doc_chunk_vecs)),
    ]:
        m, pq = compute_metrics(sc, q_ids, doc_ids, qrels)
        metrics[name] = m
        per_q[name] = pq
        print(f"  {name}: {m}", flush=True)

    gpu_name = None
    if args.device == "cuda":
        try:
            import onnxruntime as ort
            gpu_name = ort.get_device()
        except Exception:
            pass

    result = {
        "tempdoc": 712,
        "question": "Is SPLADE's ~0.059 on legal-clerc-200 a 512-token truncation artifact?",
        "model_dir": str(args.model_dir),
        "model_file": enc.model_file,
        "providers": enc.actual_providers,
        "dataset_dir": str(args.dataset_dir),
        "corpus_sha_note": "byte-identical to F-032 A/B (corpus.jsonl sha256 630f5376…)",
        "n_docs": len(doc_ids),
        "n_queries": len(q_ids),
        "max_seq_len": MAX_SEQ_LEN,
        "chunk_tokens": CHUNK_TOKENS,
        "chunk_overlap": CHUNK_OVERLAP,
        "docs_truncated_gt_512tok": n_truncated,
        "median_doc_tokens": med_tok,
        "max_doc_tokens": max_tok,
        "total_chunks": n_chunks_total,
        "avg_chunks_per_doc": round(n_chunks_total / max(len(doc_ids), 1), 2),
        "device": args.device,
        "gpu": gpu_name,
        "cond_A_encode_seconds": round(a_secs, 1),
        "cond_B_encode_seconds": round(b_secs, 1),
        "total_runtime_seconds": round(time.time() - t_start, 1),
        "smoke": args.smoke or None,
        "metrics": metrics,
        "win_loss_A_vs_Bmax": win_loss(q_ids, per_q["A_truncated_wholedoc"], per_q["B_max_merge"]),
        "win_loss_A_vs_Bmaxp": win_loss(q_ids, per_q["A_truncated_wholedoc"], per_q["B_maxp_chunk"]),
    }

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "results.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
    print(json.dumps(result["metrics"], indent=1), flush=True)
    print(f"\nWROTE {args.out / 'results.json'}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
