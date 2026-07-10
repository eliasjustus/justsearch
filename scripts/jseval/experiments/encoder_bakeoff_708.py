#!/usr/bin/env python3
"""Offline encoder bake-off on legal-clerc-200 (tempdoc 708).

Embeds the corpus + queries with candidate multilingual encoders and scores
exact-NN cosine retrieval, WITHOUT the engine (no reindex, no ANN, no fusion).
Decides FIX vs SCOPE for the F-030 encoder-domain-mismatch verdict.

Conditions (tempdoc 708 §Design):
  W1  production-mirror whole-doc: tokenize full text once (special tokens),
      slice raw 512-token id windows with 128 overlap + the tail-merge rule
      (OnnxEmbeddingEncoder.createChunks), pool each window, L2-norm, unweighted
      mean, L2-norm.  For the anchor this must reproduce F-030's dense R@10.
      Candidates embed window TEXT via their own recipe (slightly favorable to
      candidates — recorded, deliberate: a candidate that loses even with
      favorable treatment is a clean SCOPE datapoint).
  W2  native-long-context whole-doc: one forward pass, truncate at --w2-ctx.
  C   chunk-MaxP: 500-token text chunks / 50 overlap (index-side
      ChunkDocumentWriter granularity), doc score = max chunk cosine.

Run in the tmp/708-bakeoff venv (torch + sentence-transformers + transformers
+ ir_measures + numpy).  Results: one JSON per (model x condition x queryset)
under tmp/eval-results/708-bakeoff/.  This script is an experiment-grade
screen, NOT a register baseline producer (708 anti-fork rule).

Usage:
  python encoder_bakeoff_708.py --model anchor --conditions W1,W2,C \
      --dataset-dir <datasets>/mixed/legal-clerc-200 \
      --queries verbose --out tmp/eval-results/708-bakeoff
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Model recipe registry (tempdoc 708 §Design — per-candidate correct recipe)
# ---------------------------------------------------------------------------

RECIPES: dict[str, dict] = {
    # Incumbent, production recipe mirrored exactly (CLS, no prefixes, raw id
    # windows).  pooling/prefixes verified against models/onnx/gte-multilingual-base
    # configs + OnnxEmbeddingEncoder.java (tempdoc 708 takeover SSB).
    "anchor": {
        "hf_id": "Alibaba-NLP/gte-multilingual-base",
        "trust_remote_code": True,
        "pooling": "cls",
        "query_prefix": "",
        "doc_prefix": "",
        "native_ctx": 8192,
        "prod_ctx": 2048,  # EmbeddingConfig.contextLength default
        "raw_id_windows": True,  # exact production W1 path
    },
    "qwen3-0.6b": {
        "hf_id": "Qwen/Qwen3-Embedding-0.6B",
        "trust_remote_code": True,
        "pooling": "last",
        # default retrieval instruction from the model card (ship-shape condition)
        "query_prefix": (
            "Instruct: Given a web search query, retrieve relevant passages "
            "that answer the query\nQuery: "
        ),
        "doc_prefix": "",
        "native_ctx": 8192,  # capped for VRAM; model supports 32k
        "padding_side": "left",
    },
    # diagnostic-only variant: task-shaped (citation) instruction, uniform string
    "qwen3-0.6b-cite": {
        "hf_id": "Qwen/Qwen3-Embedding-0.6B",
        "trust_remote_code": True,
        "pooling": "last",
        "query_prefix": (
            "Instruct: Given a passage that cites a document, retrieve the "
            "document it cites or discusses\nQuery: "
        ),
        "doc_prefix": "",
        "native_ctx": 8192,
        "padding_side": "left",
        "shares_doc_embeddings_with": "qwen3-0.6b",
    },
    "arctic-l-v2": {
        "hf_id": "Snowflake/snowflake-arctic-embed-l-v2.0",
        "trust_remote_code": False,
        "pooling": "cls",
        "query_prefix": "query: ",
        "doc_prefix": "",
        "native_ctx": 8192,
    },
    "arctic-m-v2": {
        "hf_id": "Snowflake/snowflake-arctic-embed-m-v2.0",
        "trust_remote_code": False,
        "pooling": "cls",
        "query_prefix": "query: ",
        "doc_prefix": "",
        "native_ctx": 8192,
    },
    "bge-m3": {
        "hf_id": "BAAI/bge-m3",
        "trust_remote_code": False,
        "pooling": "cls",
        "query_prefix": "",
        "doc_prefix": "",
        "native_ctx": 8192,
    },
    "me5-large": {
        "hf_id": "intfloat/multilingual-e5-large",
        "trust_remote_code": False,
        "pooling": "mean",
        "query_prefix": "query: ",
        "doc_prefix": "passage: ",
        "native_ctx": 512,
    },
    "granite-278m": {
        "hf_id": "ibm-granite/granite-embedding-278m-multilingual",
        "trust_remote_code": False,
        "pooling": "cls",
        "query_prefix": "",
        "doc_prefix": "",
        "native_ctx": 512,
    },
}

W1_WINDOW = 512
W1_OVERLAP = 128
CHUNK_TOKENS = 500
CHUNK_OVERLAP = 50


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
    qpath = dataset_dir / "queries.jsonl"
    queries: dict[str, str] = {}
    for line in qpath.read_text(encoding="utf-8").splitlines():
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


def corpus_signature(dataset_dir: Path) -> str:
    h = hashlib.sha256()
    h.update((dataset_dir / "corpus.jsonl").read_bytes())
    h.update((dataset_dir / "qrels" / "test.tsv").read_bytes())
    return h.hexdigest()


# ---------------------------------------------------------------------------
# Embedding backends
# ---------------------------------------------------------------------------

class Encoder:
    """Thin wrapper over AutoModel with explicit pooling — uniform across
    candidates so W1/W2/C conditions control tokenization, not the library."""

    def __init__(self, recipe: dict, device: str, batch_size: int):
        import torch
        from transformers import AutoModel, AutoTokenizer

        self.recipe = recipe
        self.device = device
        self.batch_size = batch_size
        self.tokenizer = AutoTokenizer.from_pretrained(
            recipe["hf_id"], trust_remote_code=recipe.get("trust_remote_code", False)
        )
        if recipe.get("padding_side"):
            self.tokenizer.padding_side = recipe["padding_side"]
        self.model = AutoModel.from_pretrained(
            recipe["hf_id"],
            trust_remote_code=recipe.get("trust_remote_code", False),
            torch_dtype=torch.float32 if device == "cpu" else torch.float16,
        ).to(device)
        self.model.eval()
        self.torch = torch

    def _pool(self, hidden, mask):
        pooling = self.recipe["pooling"]
        if pooling == "cls":
            return hidden[:, 0]
        if pooling == "last":
            seq_lens = mask.sum(dim=1) - 1
            return hidden[self.torch.arange(hidden.shape[0]), seq_lens]
        # mean: attention-mask-weighted
        mask_f = mask.unsqueeze(-1).to(hidden.dtype)
        return (hidden * mask_f).sum(dim=1) / mask_f.sum(dim=1).clamp(min=1e-9)

    def encode_texts(self, texts: list[str], max_len: int, prefix: str = "") -> np.ndarray:
        """Standard path: text (+prefix) -> tokenize w/ special tokens -> pool -> l2."""
        out = []
        with self.torch.no_grad():
            for i in range(0, len(texts), self.batch_size):
                batch = [prefix + t for t in texts[i : i + self.batch_size]]
                enc = self.tokenizer(
                    batch, padding=True, truncation=True, max_length=max_len,
                    return_tensors="pt",
                ).to(self.device)
                hidden = self.model(**enc).last_hidden_state
                pooled = self._pool(hidden, enc["attention_mask"])
                pooled = self.torch.nn.functional.normalize(pooled.float(), dim=-1)
                out.append(pooled.cpu().numpy())
        return np.concatenate(out, axis=0)

    def encode_raw_id_windows(self, text: str, window: int, overlap: int,
                              max_ctx: int) -> np.ndarray:
        """EXACT production W1 path (OnnxEmbeddingEncoder.embed + createChunks):
        tokenize once with special tokens, no truncation; if <= max_ctx single
        pass; else raw id-slice windows (window/overlap) with the tail-merge
        rule; pool token 0 of each window AS-IS (later windows lack [CLS] —
        production behavior); per-window l2; unweighted mean; l2."""
        ids = self.tokenizer(text, add_special_tokens=True, truncation=False,
                             return_tensors=None)["input_ids"]
        if len(ids) <= max_ctx:
            windows = [ids]
        else:
            windows = []
            stride = max(1, window - overlap)
            start = 0
            while start < len(ids):
                end = min(start + window, len(ids))
                windows.append(ids[start:end])
                start += stride
                if start < len(ids) and len(ids) - start < window // 4:
                    last_start = start - stride
                    last_end = min(len(ids), last_start + max_ctx)
                    windows[-1] = ids[last_start:last_end]
                    break
        vecs = []
        with self.torch.no_grad():
            for i in range(0, len(windows), self.batch_size):
                batch = windows[i : i + self.batch_size]
                maxlen = max(len(w) for w in batch)
                pad_id = self.tokenizer.pad_token_id or 0
                input_ids = self.torch.full((len(batch), maxlen), pad_id, dtype=self.torch.long)
                mask = self.torch.zeros((len(batch), maxlen), dtype=self.torch.long)
                for j, w in enumerate(batch):
                    input_ids[j, : len(w)] = self.torch.tensor(w, dtype=self.torch.long)
                    mask[j, : len(w)] = 1
                enc = {"input_ids": input_ids.to(self.device),
                       "attention_mask": mask.to(self.device)}
                hidden = self.model(**enc).last_hidden_state
                pooled = self._pool(hidden, enc["attention_mask"])
                pooled = self.torch.nn.functional.normalize(pooled.float(), dim=-1)
                vecs.append(pooled.cpu().numpy())
        allv = np.concatenate(vecs, axis=0)
        mean = allv.mean(axis=0)
        return mean / max(np.linalg.norm(mean), 1e-9)

    def window_texts(self, text: str, window_tokens: int, overlap_tokens: int) -> list[str]:
        """Token-offset text windowing: slice the ORIGINAL text at token
        boundaries so each window re-tokenizes cleanly with special tokens +
        per-model prefix (candidate-favorable W1 / the C chunk condition)."""
        enc = self.tokenizer(text, add_special_tokens=False, truncation=False,
                             return_offsets_mapping=True, return_tensors=None)
        offsets = enc["offset_mapping"]
        if not offsets:
            return [text]
        stride = max(1, window_tokens - overlap_tokens)
        out = []
        start = 0
        while start < len(offsets):
            end = min(start + window_tokens, len(offsets))
            c0, c1 = offsets[start][0], offsets[end - 1][1]
            out.append(text[c0:c1])
            if end == len(offsets):
                break
            start += stride
        return out


# ---------------------------------------------------------------------------
# Conditions
# ---------------------------------------------------------------------------

def embed_docs(enc: Encoder, docs: dict[str, str], condition: str, w2_ctx: int,
               anchor_prod: bool) -> tuple[list[str], np.ndarray | list[np.ndarray]]:
    ids = list(docs.keys())
    if condition == "W1":
        if anchor_prod and enc.recipe.get("raw_id_windows"):
            vecs = [enc.encode_raw_id_windows(docs[d], W1_WINDOW, W1_OVERLAP,
                                              enc.recipe["prod_ctx"]) for d in ids]
            return ids, np.stack(vecs)
        # candidate-favorable W1: text windows through the model's own recipe
        vecs = []
        for d in ids:
            wins = enc.window_texts(docs[d], W1_WINDOW, W1_OVERLAP)
            wv = enc.encode_texts(wins, max_len=W1_WINDOW + 16,
                                  prefix=enc.recipe["doc_prefix"])
            mean = wv.mean(axis=0)
            vecs.append(mean / max(np.linalg.norm(mean), 1e-9))
        return ids, np.stack(vecs)
    if condition == "W2":
        vecs = enc.encode_texts([docs[d] for d in ids], max_len=w2_ctx,
                                prefix=enc.recipe["doc_prefix"])
        return ids, vecs
    if condition == "C":
        per_doc: list[np.ndarray] = []
        for d in ids:
            chunks = enc.window_texts(docs[d], CHUNK_TOKENS, CHUNK_OVERLAP)
            per_doc.append(enc.encode_texts(chunks, max_len=CHUNK_TOKENS + 16,
                                            prefix=enc.recipe["doc_prefix"]))
        return ids, per_doc
    raise ValueError(condition)


def score_condition(doc_ids: list[str], doc_vecs, q_vecs: np.ndarray,
                    condition: str) -> np.ndarray:
    """Returns [n_queries, n_docs] cosine score matrix."""
    if condition == "C":
        n_q = q_vecs.shape[0]
        scores = np.zeros((n_q, len(doc_ids)), dtype=np.float32)
        for j, chunk_mat in enumerate(doc_vecs):
            scores[:, j] = (q_vecs @ chunk_mat.T).max(axis=1)
        return scores
    return q_vecs @ np.asarray(doc_vecs).T


# ---------------------------------------------------------------------------
# Metrics
# ---------------------------------------------------------------------------

def compute_metrics(scores: np.ndarray, q_ids: list[str], doc_ids: list[str],
                    qrels: dict[str, dict[str, int]]) -> dict:
    import ir_measures
    from ir_measures import nDCG, R

    run = {}
    gold_ranks: dict[str, int | None] = {}
    order = np.argsort(-scores, axis=1)
    for i, qid in enumerate(q_ids):
        if qid not in qrels:
            continue
        run[qid] = {doc_ids[j]: float(scores[i, j]) for j in order[i][:1000]}
        golds = set(qrels[qid])
        rank = None
        for r, j in enumerate(order[i], start=1):
            if doc_ids[j] in golds:
                rank = r
                break
        gold_ranks[qid] = rank
    measures = [R @ 10, R @ 20, R @ 100, nDCG @ 10]
    agg = ir_measures.calc_aggregate(measures, qrels, run)
    return {
        "R@10": round(agg[R @ 10], 4),
        "R@20": round(agg[R @ 20], 4),
        "R@100": round(agg[R @ 100], 4),
        "nDCG@10": round(agg[nDCG @ 10], 4),
        "judged_queries": len(run),
        "gold_ranks": gold_ranks,
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, choices=sorted(RECIPES))
    ap.add_argument("--dataset-dir", required=True, type=Path)
    ap.add_argument("--queries", default="default",
                    help="label recorded in the result (e.g. verbose/kw)")
    ap.add_argument("--queries-file", type=Path, default=None,
                    help="override queries.jsonl (e.g. the -kw variant dir)")
    ap.add_argument("--conditions", default="W1,W2,C")
    ap.add_argument("--w2-ctx", type=int, default=None,
                    help="override W2 context (default: recipe native_ctx)")
    ap.add_argument("--out", required=True, type=Path)
    ap.add_argument("--device", default="cuda")
    ap.add_argument("--batch-size", type=int, default=8)
    ap.add_argument("--smoke", type=int, default=0,
                    help="limit to N docs + their gold queries (self-check)")
    args = ap.parse_args()

    recipe = RECIPES[args.model]
    docs = load_corpus(args.dataset_dir)
    qdir = args.queries_file.parent if args.queries_file else args.dataset_dir
    queries = load_queries(qdir)
    qrels = load_qrels(args.dataset_dir)
    sig = corpus_signature(args.dataset_dir)

    if args.smoke:
        doc_ids = list(docs.keys())[: args.smoke]
        docs = {d: docs[d] for d in doc_ids}
        keep_q = [q for q, rels in qrels.items() if any(d in docs for d in rels)]
        queries = {q: queries[q] for q in keep_q if q in queries}
        qrels = {q: {d: r for d, r in qrels[q].items() if d in docs}
                 for q in keep_q}

    enc = Encoder(recipe, args.device, args.batch_size)
    q_ids = [q for q in queries if q in qrels]
    t0 = time.time()
    q_vecs = enc.encode_texts([queries[q] for q in q_ids], max_len=512,
                              prefix=recipe["query_prefix"])

    args.out.mkdir(parents=True, exist_ok=True)
    for condition in args.conditions.split(","):
        condition = condition.strip()
        w2_ctx = args.w2_ctx or recipe["native_ctx"]
        t1 = time.time()
        doc_ids, doc_vecs = embed_docs(enc, docs, condition, w2_ctx,
                                       anchor_prod=(args.model == "anchor"))
        embed_secs = time.time() - t1
        scores = score_condition(doc_ids, doc_vecs, q_vecs, condition)
        metrics = compute_metrics(scores, q_ids, doc_ids, qrels)
        result = {
            "tempdoc": 708,
            "model": args.model,
            "hf_id": recipe["hf_id"],
            "recipe": {k: v for k, v in recipe.items() if k != "hf_id"},
            "condition": condition,
            "w2_ctx": w2_ctx if condition == "W2" else None,
            "queries_label": args.queries,
            "n_docs": len(doc_ids),
            "n_queries": len(q_ids),
            "corpus_signature": sig,
            "smoke": args.smoke or None,
            "device": args.device,
            "doc_embed_seconds": round(embed_secs, 1),
            "docs_per_second": round(len(doc_ids) / max(embed_secs, 1e-9), 2),
            "metrics": {k: v for k, v in metrics.items() if k != "gold_ranks"},
            "gold_ranks": metrics["gold_ranks"],
        }
        label = f"{args.model}_{condition}_{args.queries}"
        if condition == "W2":
            label += f"_ctx{w2_ctx}"
        if args.smoke:
            label += f"_smoke{args.smoke}"
        out_path = args.out / f"{label}.json"
        out_path.write_text(json.dumps(result, indent=1), encoding="utf-8")
        m = result["metrics"]
        print(f"{label}: R@10={m['R@10']} R@20={m['R@20']} R@100={m['R@100']} "
              f"nDCG@10={m['nDCG@10']} ({result['docs_per_second']} docs/s)",
              flush=True)
    print(f"total {time.time() - t0:.0f}s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
