#!/usr/bin/env python3
"""Paraphrase-bridging reliability suite (tempdoc 788 §3.B.10).

Measures, OFFLINE and without the engine, how reliably the shipped semantic
stack bridges a query-side paraphrase to the document-side surface form it was
generated from -- the capability the 2026-07-28 hero campaign showed is the
tool's unique demonstrated value AND unreliable (bridged ``coin-striking works
-> mint`` once at B|q16|s2, failed ``power station -> reactor`` 6/6 cells).

Why this can be measured at all: the fabricated-chain corpora ship their
paraphrase mapping BY CONSTRUCTION.  ``jseval.corpus_generate`` renders the head
document with the doc-side member of a synonym pair and the query with the
query-side member, and the pools guarantee the two members share NO token
(``test_sem_pools_are_root_disjoint``).  So the pair set is extractable, not
hand-authored, and the lexical arm is a control that must fail by construction.

Three measurement tiers, cheapest first -- each one a strictly weaker ceiling
claim than the next:

  P  pair-isolated.  Within one synonym pool (types / places / qualifiers), rank
     the doc-side surfaces against each query-side surface.  Pure bridging with
     same-pool hard negatives, no corpus, no dilution.  A pair that fails here
     cannot bridge anywhere.  Runs from the pools alone.
  S  sentence-isolated.  Rank a member's fabricated head/tail sentences against
     its generated questions.  Adds question phrasing + chain structure, still
     no host-document dilution.  Runs from the COMMITTED 781 corpora.
  D  in-corpus.  Rank the real assembled dataset (injected sentences inside real
     host documents) at production doc- and chunk-granularity.  The setting the
     hero observation was made in.  Needs the local dataset dirs.

Arms per tier:
  dense        incumbent gte-multilingual-base ONNX, production recipe
               (CLS pooling, no prefixes, 512/128 raw id windows + tail merge,
               unweighted mean of L2-normed windows -- ``OnnxEmbeddingEncoder``)
  dense-chunk  same encoder at ChunkDocumentWriter granularity (500-token text
               chunks / 50 overlap), doc score = max chunk cosine  [tier D only]
  splade       naver-splade-v3 ONNX both sides, log1p, maxseq 512 -- the shipped
               ``justsearch.splade.query_mode=onnx`` default
  splade-idf   the inference-free alternative query encoder
               (``query_mode=idf``): tokenize + IDF lookup, no expansion
  lexical      BM25 over ICU-ish analysis (NFC + lowercase + unicode word
               split) -- the CONTROL, expected to fail on a token-disjoint pair

This is an experiment-grade screen, NOT a register baseline producer (708's
anti-fork rule).  It runs no live backend and calls no paid API.

Usage:
  python paraphrase_bridge_suite.py pairs   --out tmp/paraphrase-bridge
  python paraphrase_bridge_suite.py tier-p  --out tmp/paraphrase-bridge
  python paraphrase_bridge_suite.py tier-s  --out tmp/paraphrase-bridge
  python paraphrase_bridge_suite.py tier-d  --member en-email-enron-raw-1k-verbose \
      --datasets F:/justsearch-public/tmp/781-v2-datasets/mixed --out tmp/paraphrase-bridge
  python paraphrase_bridge_suite.py report  --out tmp/paraphrase-bridge
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import sys
import time
import unicodedata
from collections import Counter
from pathlib import Path

# ---------------------------------------------------------------------------
# Defaults (this machine; every one is a CLI flag)
# ---------------------------------------------------------------------------

REPO_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CORPORA = REPO_ROOT / "scripts" / "jseval" / "781-corpora"
DEFAULT_MODELS = Path("F:/justsearch-public/models")
DEFAULT_DATASETS = Path("F:/justsearch-public/tmp/781-v2-datasets/mixed")
DEFAULT_OUT = REPO_ROOT / "tmp" / "paraphrase-bridge"
#: The committed pair register (small, deterministic, derived from committed inputs only).
DEFAULT_REGISTER = (REPO_ROOT / "scripts" / "jseval" / "796-paraphrase-pairs"
                    / "paraphrase-pairs.v1.json")

#: Production embedding recipe constants, mirrored from the Java side.
#: ``OnnxEmbeddingEncoder.createChunks`` (512 window / 128 overlap, tail merge),
#: ``EmbeddingConfig.contextLength`` default 2048, pooling_config.json = cls.
EMBED_WINDOW = 512
EMBED_OVERLAP = 128
EMBED_CTX = 2048
#: ``ChunkDocumentWriter`` index-side chunk granularity (708 condition C).
CHUNK_TOKENS = 500
CHUNK_OVERLAP = 50
#: ``justsearch.splade.max_seq_len`` default.
SPLADE_MAXSEQ = 512
#: BERT specials excluded from a SPLADE expansion -- ``SpladeIdfQueryEncoder``
#: SKIP_TOKEN_IDS: [PAD] [UNK] [CLS] [SEP] [MASK].
SPLADE_SKIP_IDS = frozenset({0, 100, 101, 102, 103})

#: Ranks reported as "bridged" at each tier.
BRIDGE_KS = (1, 3, 5, 10)

#: Fixed bucket edges for the reliability curve's PRIMARY axis
#: (``dense_pair_cosine``).  Fixed rather than quantile so the curve is
#: comparable across reruns and members.
DENSE_COS_EDGES = (0.0, 0.55, 0.65, 0.75, 0.85, 1.01)


# ---------------------------------------------------------------------------
# 1. Pair extraction -- the pools, read from the generator, never hand-typed
# ---------------------------------------------------------------------------

AXES = ("type", "place", "qual")


def load_pools(lang: str = "en") -> dict[str, list[tuple[str, str]]]:
    """The (doc_surface, query_surface) synonym pools, imported from the module
    that renders the corpora -- `jseval.corpus_generate`.  No pair in this suite
    is ever typed by hand; drift between suite and generator is impossible."""
    sys.path.insert(0, str(REPO_ROOT / "scripts" / "jseval"))
    from jseval import corpus_generate as cg  # noqa: PLC0415

    if lang == "de":
        return {"type": list(cg._SEM_TYPE_DE),
                "place": list(cg._SEM_PLACE_DE),
                "qual": list(cg._SEM_QUAL_DE)}
    return {"type": list(cg._SEM_TYPE),
            "place": list(cg._SEM_PLACE),
            "qual": list(cg._SEM_QUAL)}


def pair_id(axis: str, index: int, lang: str = "en") -> str:
    return f"{lang}:{axis}:{index:02d}"


def member_dirs(corpora_root: Path) -> list[Path]:
    """Every committed 781 corpus member directory (corpus/variant leaf)."""
    return sorted(p.parent for p in corpora_root.glob("*/*/fabricated-queries.json"))


def member_name(member_dir: Path) -> str:
    return f"{member_dir.parent.name}/{member_dir.name}"


def _contains_phrase(haystack: str, phrase: str) -> bool:
    """Case-insensitive whole-phrase containment on word boundaries."""
    return re.search(r"(?<![\w-])" + re.escape(phrase) + r"(?![\w-])",
                     haystack, flags=re.IGNORECASE) is not None


def extract_observed_pairs(corpora_root: Path, lang: str = "en") -> dict:
    """Join every committed member's queries to their head documents and record
    which pool pairs are actually exercised.

    The join is by SURFACE MATCH, not by re-deriving the generator's index
    arithmetic: for each gold query we find the pool pairs whose QUERY-side
    member occurs in the question and whose DOC-side member occurs in the head
    document (``evidence_ids[0]``).  Both halves must match the SAME pool index
    or the observation is recorded as a mismatch -- a loud failure rather than a
    silent mis-attribution.
    """
    pools = load_pools(lang)
    observations: list[dict] = []
    mismatches: list[dict] = []

    for md in member_dirs(corpora_root):
        docs = {}
        for line in (md / "fabricated-docs.jsonl").read_text(encoding="utf-8").splitlines():
            if line.strip():
                o = json.loads(line)
                docs[str(o["_id"])] = o
        queries = json.loads((md / "fabricated-queries.json").read_text(encoding="utf-8"))
        for qi, q in enumerate(queries):
            head_id = q["evidence_ids"][0]
            head = docs.get(head_id)
            if head is None:
                mismatches.append({"member": member_name(md), "qidx": qi,
                                   "reason": "head_evidence_doc_missing", "head_id": head_id})
                continue
            head_text = f"{head.get('title', '')} {head.get('text', '')}"
            for axis, pool in pools.items():
                q_hits = [i for i, (_d, qs) in enumerate(pool)
                          if _contains_phrase(q["query"], qs)]
                d_hits = [i for i, (ds, _q) in enumerate(pool)
                          if _contains_phrase(head_text, ds)]
                both = sorted(set(q_hits) & set(d_hits))
                if not q_hits and not d_hits:
                    continue  # axis not used by this corpus regime (e.g. qual)
                if len(both) != 1:
                    mismatches.append({
                        "member": member_name(md), "qidx": qi, "axis": axis,
                        "reason": "query_side_and_doc_side_disagree",
                        "query_side_indices": q_hits, "doc_side_indices": d_hits,
                        "query": q["query"],
                    })
                    continue
                idx = both[0]
                observations.append({
                    "pair_id": pair_id(axis, idx, lang),
                    "axis": axis, "index": idx,
                    "doc_surface": pool[idx][0],
                    "query_surface": pool[idx][1],
                    "member": member_name(md),
                    "qidx": qi,
                    "query_family_id": q.get("query_family_id"),
                    "head_evidence_id": head_id,
                })

    counts = Counter(o["pair_id"] for o in observations)
    pairs = []
    for axis, pool in pools.items():
        for idx, (ds, qs) in enumerate(pool):
            pid = pair_id(axis, idx, lang)
            pairs.append({
                "pair_id": pid, "axis": axis, "index": idx, "lang": lang,
                "doc_surface": ds, "query_surface": qs,
                "n_observations": counts.get(pid, 0),
                "observed_in_committed_corpora": counts.get(pid, 0) > 0,
            })
    return {
        "lang": lang,
        "pairs": pairs,
        "observations": observations,
        "mismatches": mismatches,
        "n_members": len(member_dirs(corpora_root)),
    }


# ---------------------------------------------------------------------------
# 2. Paraphrase-distance proxies -- several cheap observables, no magic scale
# ---------------------------------------------------------------------------

_WORD = re.compile(r"\w+", re.UNICODE)
#: Function words dropped for the "content token" proxies.  Deliberately tiny:
#: this is a proxy, not an analyzer, and Invariant #6 forbids shipping a
#: per-language stopword artifact -- this list lives in the measurement only.
_FUNCTION_WORDS = frozenset({"the", "a", "an", "of", "to", "in", "on", "at", "for", "and", "or"})


def analyze(text: str) -> list[str]:
    """ICU-ish analysis mirror: NFC, lowercase, unicode word split.  This is the
    lexical arm's analyzer and the token-overlap proxy's tokenizer."""
    return _WORD.findall(unicodedata.normalize("NFC", text).lower())


def content_tokens(text: str) -> set[str]:
    return {t for t in analyze(text) if t not in _FUNCTION_WORDS}


def _stem(token: str) -> str:
    """Crude suffix stripper -- enough to catch a shared root that survives a
    token-disjointness check (the leak class tempdoc 767 closed)."""
    for suf in ("ing", "ers", "er", "ies", "es", "s", "ed", "al", "ic"):
        if len(token) > len(suf) + 3 and token.endswith(suf):
            return token[: -len(suf)]
    return token


def _char_ngrams(text: str, n: int = 3) -> set[str]:
    s = " " + re.sub(r"\s+", " ", unicodedata.normalize("NFC", text).lower().strip()) + " "
    return {s[i:i + n] for i in range(max(0, len(s) - n + 1))}


def _jaccard(a: set, b: set) -> float:
    if not a and not b:
        return 1.0
    return len(a & b) / max(1, len(a | b))


def _levenshtein(a: str, b: str) -> int:
    if a == b:
        return 0
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def lexical_proxies(doc_surface: str, query_surface: str) -> dict:
    """Cheap, model-free observables of how far the query-side phrasing is from
    the doc-side one.  Recorded per pair; NONE of them is claimed to be *the*
    paraphrase distance -- the reliability curve declares which axis it buckets
    on and reports the rest alongside."""
    dt, qt = analyze(doc_surface), analyze(query_surface)
    dc, qc = content_tokens(doc_surface), content_tokens(query_surface)
    ds, qs = {_stem(t) for t in dc}, {_stem(t) for t in qc}
    a = unicodedata.normalize("NFC", doc_surface).lower()
    b = unicodedata.normalize("NFC", query_surface).lower()
    return {
        "token_jaccard": round(_jaccard(set(dt), set(qt)), 4),
        "content_token_jaccard": round(_jaccard(dc, qc), 4),
        "shared_content_tokens": sorted(dc & qc),
        "stem_jaccard": round(_jaccard(ds, qs), 4),
        "shared_stems": sorted(ds & qs),
        "char3_jaccard": round(_jaccard(_char_ngrams(a), _char_ngrams(b)), 4),
        "norm_edit_similarity": round(1.0 - _levenshtein(a, b) / max(1, len(a), len(b)), 4),
        "doc_words": len(dt),
        "query_words": len(qt),
        "word_count_delta": len(qt) - len(dt),
    }


# ---------------------------------------------------------------------------
# 3. Lexical arm -- BM25 over the ICU-ish analysis
# ---------------------------------------------------------------------------

class Bm25:
    def __init__(self, docs: list[list[str]], k1: float = 1.2, b: float = 0.75):
        self.k1, self.b = k1, b
        self.docs = docs
        self.n = len(docs)
        self.lens = [len(d) for d in docs]
        self.avgdl = (sum(self.lens) / self.n) if self.n else 0.0
        self.tf = [Counter(d) for d in docs]
        df = Counter()
        for d in docs:
            df.update(set(d))
        self.idf = {t: math.log(1 + (self.n - c + 0.5) / (c + 0.5)) for t, c in df.items()}

    def scores(self, query: list[str]):
        out = [0.0] * self.n
        for t in query:
            idf = self.idf.get(t)
            if idf is None:
                continue
            for i, tf in enumerate(self.tf):
                f = tf.get(t)
                if not f:
                    continue
                denom = f + self.k1 * (1 - self.b + self.b * self.lens[i] / max(self.avgdl, 1e-9))
                out[i] += idf * f * (self.k1 + 1) / denom
        return out


# ---------------------------------------------------------------------------
# 4. ONNX backends -- the SHIPPED models, production recipes
# ---------------------------------------------------------------------------

def _sha256_head(path: Path, limit: int = 1 << 22) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        h.update(fh.read(limit))
    return h.hexdigest()[:16]


class DenseEncoder:
    """gte-multilingual-base ONNX, CPU, production recipe.

    Mirrors ``OnnxEmbeddingEncoder``: tokenize once WITH special tokens and no
    truncation; a document at or under ``EMBED_CTX`` is one forward pass, longer
    ones are raw id-slice windows (``EMBED_WINDOW``/``EMBED_OVERLAP``) with the
    tail-merge rule; CLS-pool token 0 of every window as-is (later windows carry
    no [CLS] -- production behaviour, deliberately reproduced); L2-norm each
    window, unweighted mean, L2-norm.  Same path as 708's W1 condition, with the
    shipped ONNX graph in place of 708's HF mirror.
    """

    def __init__(self, model_root: Path, threads: int = 10, batch: int = 8):
        import numpy as np  # noqa: PLC0415
        import onnxruntime as ort  # noqa: PLC0415
        from tokenizers import Tokenizer  # noqa: PLC0415

        self.np = np
        d = model_root / "onnx" / "gte-multilingual-base"
        self.model_path = d / "model.onnx"
        self.tok = Tokenizer.from_file(str(d / "tokenizer.json"))
        self.tok.no_truncation()
        self.tok.no_padding()
        pooling = json.loads((d / "pooling_config.json").read_text(encoding="utf-8"))
        prefixes = json.loads((d / "prefix_config.json").read_text(encoding="utf-8"))
        if pooling.get("pooling_mode") != "cls":
            raise RuntimeError(f"unexpected pooling_mode {pooling!r}; suite mirrors CLS only")
        self.query_prefix = prefixes.get("query_prefix", "")
        self.doc_prefix = prefixes.get("document_prefix", "")
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(str(self.model_path), so,
                                         providers=["CPUExecutionProvider"])
        self.batch = batch
        self.pad_id = self.tok.token_to_id("<pad>") or 0
        self.n_forward = 0

    def fingerprint(self) -> dict:
        return {"model": str(self.model_path), "sha256_head": _sha256_head(self.model_path),
                "pooling": "cls", "query_prefix": self.query_prefix,
                "doc_prefix": self.doc_prefix, "provider": "CPUExecutionProvider"}

    def _forward(self, windows: list[list[int]]):
        np = self.np
        vecs = []
        for i in range(0, len(windows), self.batch):
            chunk = windows[i:i + self.batch]
            maxlen = max(len(w) for w in chunk)
            ids = np.full((len(chunk), maxlen), self.pad_id, dtype=np.int64)
            mask = np.zeros((len(chunk), maxlen), dtype=np.int64)
            for j, w in enumerate(chunk):
                ids[j, :len(w)] = w
                mask[j, :len(w)] = 1
            out = self.sess.run(["token_embeddings"],
                                {"input_ids": ids, "attention_mask": mask})[0]
            self.n_forward += len(chunk)
            pooled = out[:, 0, :].astype(np.float32)
            pooled /= np.maximum(np.linalg.norm(pooled, axis=1, keepdims=True), 1e-9)
            vecs.append(pooled)
        return np.concatenate(vecs, axis=0)

    def _windows(self, text: str) -> list[list[int]]:
        ids = self.tok.encode(text).ids
        if len(ids) <= EMBED_CTX:
            return [ids]
        windows, stride, start = [], max(1, EMBED_WINDOW - EMBED_OVERLAP), 0
        while start < len(ids):
            windows.append(ids[start:min(start + EMBED_WINDOW, len(ids))])
            start += stride
            if start < len(ids) and len(ids) - start < EMBED_WINDOW // 4:
                last = start - stride
                windows[-1] = ids[last:min(len(ids), last + EMBED_CTX)]
                break
        return windows

    def embed_doc(self, text: str):
        v = self._forward(self._windows(self.doc_prefix + text)).mean(axis=0)
        return v / max(float(self.np.linalg.norm(v)), 1e-9)

    def embed_query(self, text: str):
        return self.embed_doc_like(self.query_prefix + text)

    def embed_doc_like(self, text: str):
        v = self._forward(self._windows(text)).mean(axis=0)
        return v / max(float(self.np.linalg.norm(v)), 1e-9)

    def text_chunks(self, text: str, window: int = CHUNK_TOKENS,
                    overlap: int = CHUNK_OVERLAP) -> list[str]:
        """Index-side chunk granularity: slice the ORIGINAL text at token
        offsets so each chunk re-tokenizes cleanly with its own [CLS]/[SEP]."""
        enc = self.tok.encode(text, add_special_tokens=False)
        offs = enc.offsets
        if not offs:
            return [text]
        out, stride, start = [], max(1, window - overlap), 0
        while start < len(offs):
            end = min(start + window, len(offs))
            out.append(text[offs[start][0]:offs[end - 1][1]])
            if end == len(offs):
                break
            start += stride
        return out

    def embed_chunks(self, chunks: list[str]):
        return self._forward([self.tok.encode(c).ids[:EMBED_WINDOW] for c in chunks])


class SpladeEncoder:
    """naver-splade-v3 ONNX (opensearch-neural-sparse-encoding-multilingual-v1).

    The shipped graph bakes the PRESPARSE tail -- ReLU, +1, log, ReduceMax over
    the sequence, TopK(256) -- so a forward pass returns the log1p expansion
    directly, matching ``justsearch.splade.activation=log1p``.  ``query_mode``
    defaults to ``onnx`` (neural both sides); ``idf`` is the inference-free
    alternative query encoder, implemented here too because it CANNOT expand and
    therefore cannot bridge -- a arm worth having explicit.
    """

    def __init__(self, model_root: Path, threads: int = 10, batch: int = 8):
        import numpy as np  # noqa: PLC0415
        import onnxruntime as ort  # noqa: PLC0415
        from tokenizers import Tokenizer  # noqa: PLC0415

        self.np = np
        d = model_root / "splade" / "naver-splade-v3"
        self.model_path = d / "model.onnx"
        self.tok = Tokenizer.from_file(str(d / "tokenizer.json"))
        self.tok.no_truncation()
        self.tok.no_padding()
        self.idf = json.loads((d / "idf.json").read_text(encoding="utf-8"))
        self.vocab = [ln.rstrip("\n") for ln in
                      (d / "vocab.txt").read_text(encoding="utf-8").splitlines()]
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.sess = ort.InferenceSession(str(self.model_path), so,
                                         providers=["CPUExecutionProvider"])
        self.batch = batch
        self.n_forward = 0

    def fingerprint(self) -> dict:
        return {"model": str(self.model_path), "sha256_head": _sha256_head(self.model_path),
                "activation": "log1p(baked)", "max_seq_len": SPLADE_MAXSEQ,
                "provider": "CPUExecutionProvider"}

    def expand(self, texts: list[str]) -> list[dict[int, float]]:
        np = self.np
        out: list[dict[int, float]] = []
        for i in range(0, len(texts), self.batch):
            chunk = texts[i:i + self.batch]
            encs = [self.tok.encode(t).ids[:SPLADE_MAXSEQ] for t in chunk]
            maxlen = max(len(e) for e in encs)
            ids = np.zeros((len(encs), maxlen), dtype=np.int64)
            mask = np.zeros((len(encs), maxlen), dtype=np.int64)
            for j, e in enumerate(encs):
                ids[j, :len(e)] = e
                mask[j, :len(e)] = 1
            tt = np.zeros_like(ids)
            idx, wts = self.sess.run(None, {"input_ids": ids, "attention_mask": mask,
                                            "token_type_ids": tt})
            self.n_forward += len(encs)
            for j in range(len(encs)):
                out.append({int(t): float(w) for t, w in zip(idx[j], wts[j])
                            if w > 0.0 and int(t) not in SPLADE_SKIP_IDS})
        return out

    def expand_idf(self, text: str) -> dict[int, float]:
        """``query_mode=idf``: tokenize with the same WordPiece vocab, assign the
        pre-computed IDF weight.  No expansion by construction."""
        vec: dict[int, float] = {}
        for tid in self.tok.encode(text).ids:
            if tid in SPLADE_SKIP_IDS or tid >= len(self.vocab):
                continue
            w = self.idf.get(self.vocab[tid])
            if w:
                vec[tid] = max(vec.get(tid, 0.0), float(w))
        return vec

    @staticmethod
    def dot(a: dict[int, float], b: dict[int, float]) -> float:
        if len(a) > len(b):
            a, b = b, a
        return sum(w * b.get(t, 0.0) for t, w in a.items())


# ---------------------------------------------------------------------------
# 5. Ranking helpers
# ---------------------------------------------------------------------------

def rank_of(scores: list[float], target: int) -> int:
    """1-based competition rank of ``target`` (ties resolved pessimistically --
    a tie counts against the target, so a bridge is never credited to a draw)."""
    t = scores[target]
    return 1 + sum(1 for i, s in enumerate(scores) if s > t or (s == t and i != target))


def bridge_flags(rank: int) -> dict:
    return {f"top{k}": rank <= k for k in BRIDGE_KS}


# ---------------------------------------------------------------------------
# 6. Tier P -- pair-isolated bridging inside one synonym pool
# ---------------------------------------------------------------------------

def tier_p(pools: dict[str, list[tuple[str, str]]], dense: DenseEncoder,
           splade: SpladeEncoder, lang: str) -> list[dict]:
    np = dense.np
    rows: list[dict] = []
    for axis, pool in pools.items():
        docs = [d for d, _ in pool]
        queries = [q for _, q in pool]
        dv = np.stack([dense.embed_doc(d) for d in docs])
        qv = np.stack([dense.embed_query(q) for q in queries])
        dsp = splade.expand(docs)
        qsp = splade.expand(queries)
        qidf = [splade.expand_idf(q) for q in queries]
        bm = Bm25([analyze(d) for d in docs])
        for i in range(len(pool)):
            dense_scores = (dv @ qv[i]).tolist()
            splade_scores = [SpladeEncoder.dot(qsp[i], d) for d in dsp]
            idf_scores = [SpladeEncoder.dot(qidf[i], d) for d in dsp]
            lex_scores = bm.scores(analyze(queries[i]))
            row = {
                "tier": "P", "pair_id": pair_id(axis, i, lang), "axis": axis, "index": i,
                "lang": lang, "doc_surface": docs[i], "query_surface": queries[i],
                "pool_size": len(pool),
                "dense_pair_cosine": round(float(dv[i] @ qv[i]), 4),
                "splade_pair_dot": round(float(SpladeEncoder.dot(qsp[i], dsp[i])), 4),
                "splade_idf_pair_dot": round(float(SpladeEncoder.dot(qidf[i], dsp[i])), 4),
                "arms": {},
            }
            for name, sc in (("dense", dense_scores), ("splade", splade_scores),
                             ("splade-idf", idf_scores), ("lexical", lex_scores)):
                r = rank_of(sc, i)
                row["arms"][name] = {"rank": r, **bridge_flags(r)}
            row.update(lexical_proxies(docs[i], queries[i]))
            rows.append(row)
    return rows


# ---------------------------------------------------------------------------
# 7. Tier S -- sentence-isolated bridging over a committed member
# ---------------------------------------------------------------------------

def load_member(member_dir: Path) -> tuple[list[str], list[str], list[dict]]:
    ids, texts = [], []
    for line in (member_dir / "fabricated-docs.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            o = json.loads(line)
            ids.append(str(o["_id"]))
            texts.append(" ".join(p for p in (o.get("title", ""), o.get("text", "")) if p))
    queries = json.loads((member_dir / "fabricated-queries.json").read_text(encoding="utf-8"))
    return ids, texts, queries


def tier_s(member_dir: Path, dense: DenseEncoder, splade: SpladeEncoder,
           pair_index: dict) -> list[dict]:
    np = dense.np
    ids, texts, queries = load_member(member_dir)
    pos = {d: i for i, d in enumerate(ids)}
    dv = np.stack([dense.embed_doc(t) for t in texts])
    dsp = splade.expand(texts)
    bm = Bm25([analyze(t) for t in texts])
    rows = []
    for qi, q in enumerate(queries):
        target = pos[q["evidence_ids"][0]]
        qv = dense.embed_query(q["query"])
        qsp = splade.expand([q["query"]])[0]
        qidf = splade.expand_idf(q["query"])
        arms = {
            "dense": (dv @ qv).tolist(),
            "splade": [SpladeEncoder.dot(qsp, d) for d in dsp],
            "splade-idf": [SpladeEncoder.dot(qidf, d) for d in dsp],
            "lexical": bm.scores(analyze(q["query"])),
        }
        row = {
            "tier": "S", "member": member_name(member_dir), "qidx": qi,
            "query_family_id": q.get("query_family_id"), "query": q["query"],
            "candidates": len(ids), "target_id": q["evidence_ids"][0],
            "pair_ids": pair_index.get((member_name(member_dir), qi), []),
            "arms": {},
        }
        for name, sc in arms.items():
            r = rank_of(sc, target)
            row["arms"][name] = {"rank": r, **bridge_flags(r)}
        rows.append(row)
    return rows


# ---------------------------------------------------------------------------
# 8. Tier D -- in-corpus bridging at production doc / chunk granularity
# ---------------------------------------------------------------------------

def load_dataset(dataset_dir: Path) -> tuple[list[str], list[str], list[dict]]:
    ids, texts = [], []
    with (dataset_dir / "corpus.jsonl").open(encoding="utf-8") as fh:
        for line in fh:
            if not line.strip():
                continue
            o = json.loads(line)
            ids.append(str(o["_id"]))
            texts.append(" ".join(p for p in (o.get("title", ""), o.get("text", "")) if p))
    queries = json.loads((dataset_dir / "queries.json").read_text(encoding="utf-8"))
    return ids, texts, queries


#: Documents per checkpoint block.  Tier D is hours of CPU encoding; a killed
#: process (this campaign lost one to an environment-level kill at doc ~300)
#: must cost one block, not the run.  Blocks also make the encoding phase
#: resumable across sessions and re-runnable without re-encoding.
CHECKPOINT_DOCS = 100


def _encode_blocks(kind: str, texts: list[str], cache: Path | None, member: str,
                   encode_block, load_block, save_block, t0: float):
    """Run ``encode_block`` over ``texts`` in `CHECKPOINT_DOCS`-sized blocks,
    persisting each block under ``cache`` and reusing any block already on disk."""
    out = []
    n_blocks = (len(texts) + CHECKPOINT_DOCS - 1) // CHECKPOINT_DOCS
    for b in range(n_blocks):
        lo, hi = b * CHECKPOINT_DOCS, min((b + 1) * CHECKPOINT_DOCS, len(texts))
        path = (cache / member / kind / f"block-{b:04d}") if cache else None
        if path is not None and (loaded := load_block(path)) is not None:
            out.append(loaded)
            continue
        block = encode_block(texts[lo:hi])
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            save_block(path, block)
        out.append(block)
        print(f"  [{member}] {kind} {hi}/{len(texts)} ({time.time() - t0:.0f}s)", flush=True)
    return out


def query_forms(question: str, pair_ids: list[str], pairs: dict[str, dict]) -> dict[str, str]:
    """The query SHAPES a bridge has to survive, all derived from the pair
    register — none hand-authored.

    The hero census recorded what agents actually type: median 4 content tokens,
    and for the failing q0 the two dominant shapes were verbatim
    ``power station in the upper wetlands`` and ``upper wetlands power station``.
    F-034's secondary finding says dense retrieval is markedly weaker on
    keyword-shaped queries than sentence-shaped ones, so measuring bridging only
    on the generated question would measure a shape no agent issues.

      question    the corpus's own generated question (sentence-shaped)
      descriptor  ``<type-synonym> in the <place-synonym>`` — the noun phrase
      keyword     ``<place-synonym> <type-synonym>`` — the bag-of-terms shape
    """
    by_axis = {pairs[p]["axis"]: pairs[p]["query_surface"] for p in pair_ids if p in pairs}
    t, pl = by_axis.get("type"), by_axis.get("place")
    forms = {"question": question}
    if t and pl:
        forms["descriptor"] = f"{t} in the {pl}"
        forms["keyword"] = f"{pl} {t}"
    return forms


def tier_d(dataset_dir: Path, dense: DenseEncoder, splade: SpladeEncoder,
           pair_index: dict, member: str, arms: set[str],
           cache: Path | None = None, pairs: dict[str, dict] | None = None) -> list[dict]:
    np = dense.np
    ids, texts, queries = load_dataset(dataset_dir)
    pos = {d: i for i, d in enumerate(ids)}
    t0 = time.time()

    def _load_npy(p):
        f = p.with_suffix(".npy")
        return np.load(f) if f.exists() else None

    def _save_npy(p, arr):
        np.save(p.with_suffix(".npy"), arr)

    doc_vecs = chunk_vecs = chunk_owner = doc_sp = None
    if "dense" in arms:
        blocks = _encode_blocks(
            "dense-doc", texts, cache, member,
            lambda ts: np.stack([dense.embed_doc(t) for t in ts]),
            _load_npy, _save_npy, t0)
        doc_vecs = np.concatenate(blocks, axis=0)
    if "dense-chunk" in arms:
        def _chunk_block(ts):
            vecs, counts = [], []
            for t in ts:
                cs = dense.text_chunks(t)
                vecs.append(dense.embed_chunks(cs))
                counts.append(len(cs))
            return {"vecs": np.concatenate(vecs, axis=0), "counts": np.array(counts)}

        def _load_chunk(p):
            f = p.with_suffix(".npz")
            if not f.exists():
                return None
            z = np.load(f)
            return {"vecs": z["vecs"], "counts": z["counts"]}

        blocks = _encode_blocks("dense-chunk", texts, cache, member, _chunk_block, _load_chunk,
                                lambda p, b: np.savez(p.with_suffix(".npz"), **b), t0)
        chunk_vecs = np.concatenate([b["vecs"] for b in blocks], axis=0)
        owner = []
        doc_i = 0
        for b in blocks:
            for c in b["counts"]:
                owner.extend([doc_i] * int(c))
                doc_i += 1
        chunk_owner = np.array(owner)
    if "splade" in arms or "splade-idf" in arms:
        def _splade_block(ts):
            return [splade.expand(dense.text_chunks(t, SPLADE_MAXSEQ - 2, CHUNK_OVERLAP))
                    for t in ts]

        def _load_splade(p):
            f = p.with_suffix(".json")
            if not f.exists():
                return None
            return [[{int(k): v for k, v in c.items()} for c in d]
                    for d in json.loads(f.read_text(encoding="utf-8"))]

        blocks = _encode_blocks("splade", texts, cache, member, _splade_block, _load_splade,
                                lambda p, b: p.with_suffix(".json").write_text(
                                    json.dumps(b), encoding="utf-8"), t0)
        doc_sp = [d for b in blocks for d in b]
    bm = Bm25([analyze(t) for t in texts]) if "lexical" in arms else None

    out = []
    for qi, q in enumerate(queries):
        target = pos[q["evidence_ids"][0]]
        tail = pos.get(q["evidence_ids"][1]) if len(q["evidence_ids"]) > 1 else None
        pids = pair_index.get((member, qi), [])
        forms = query_forms(q["query"], pids, pairs or {})
        for form, qtext in forms.items():
            scored: dict[str, list[float]] = {}
            if doc_vecs is not None or chunk_vecs is not None:
                qv = dense.embed_query(qtext)
            if doc_vecs is not None:
                scored["dense"] = (doc_vecs @ qv).tolist()
            if chunk_vecs is not None:
                best = np.full(len(ids), -1e9, dtype=np.float32)
                np.maximum.at(best, chunk_owner, chunk_vecs @ qv)
                scored["dense-chunk"] = best.tolist()
            if doc_sp is not None:
                qsp = splade.expand([qtext])[0]
                qidf = splade.expand_idf(qtext)
                if "splade" in arms:
                    scored["splade"] = [max((SpladeEncoder.dot(qsp, c) for c in cs_), default=0.0)
                                        for cs_ in doc_sp]
                if "splade-idf" in arms:
                    scored["splade-idf"] = [max((SpladeEncoder.dot(qidf, c) for c in cs_),
                                                default=0.0) for cs_ in doc_sp]
            if bm is not None:
                scored["lexical"] = bm.scores(analyze(qtext))
            row = {
                "tier": "D", "member": member, "qidx": qi, "query_form": form,
                "query_family_id": q.get("query_family_id"), "query": qtext,
                "candidates": len(ids), "target_id": q["evidence_ids"][0],
                "pair_ids": pids, "arms": {},
            }
            for name, sc in scored.items():
                r = rank_of(sc, target)
                entry = {"rank": r, **bridge_flags(r)}
                if tail is not None:
                    entry["tail_rank"] = rank_of(sc, tail)
                row["arms"][name] = entry
            out.append(row)
    return out


# ---------------------------------------------------------------------------
# 9. Reporting -- the reliability curve
# ---------------------------------------------------------------------------

def bucket_label(value: float, edges=DENSE_COS_EDGES) -> str:
    for lo, hi in zip(edges, edges[1:]):
        if lo <= value < hi:
            return f"[{lo:.2f},{hi:.2f})"
    return f">={edges[-1]:.2f}"


def reliability_curve(rows: list[dict], pair_cos: dict[str, float], arm: str,
                      k: int = 10) -> list[dict]:
    """Bridge rate vs paraphrase-distance bucket.

    The bucketing axis is DECLARED: ``dense_pair_cosine`` -- the isolated cosine
    between the two members of the pair, measured by the incumbent encoder with
    no corpus around it.  A row that exercises several pairs is bucketed on its
    HARDEST (lowest-cosine) pair, because a query only bridges if every
    descriptor axis it names bridges.
    """
    buckets: dict[str, list[bool]] = {}
    for r in rows:
        pids = r.get("pair_ids") or ([r["pair_id"]] if "pair_id" in r else [])
        cos = [pair_cos[p] for p in pids if p in pair_cos]
        if not cos:
            continue
        b = bucket_label(min(cos))
        buckets.setdefault(b, []).append(bool(r["arms"].get(arm, {}).get(f"top{k}")))
    out = []
    for b in sorted(buckets, key=lambda s: (s.startswith(">"), s)):
        v = buckets[b]
        out.append({"bucket": b, "n": len(v), "bridged": sum(v),
                    "bridge_rate": round(sum(v) / len(v), 4)})
    return out


def arm_summary(rows: list[dict]) -> dict:
    arms: dict[str, dict] = {}
    for r in rows:
        for name, e in r["arms"].items():
            a = arms.setdefault(name, {f"top{k}": 0 for k in BRIDGE_KS} | {"n": 0, "mrr": 0.0})
            a["n"] += 1
            a["mrr"] += 1.0 / e["rank"]
            for k in BRIDGE_KS:
                a[f"top{k}"] += int(e[f"top{k}"])
    for a in arms.values():
        n = max(a["n"], 1)
        a["mrr"] = round(a["mrr"] / n, 4)
        for k in BRIDGE_KS:
            a[f"top{k}_rate"] = round(a[f"top{k}"] / n, 4)
    return arms


# ---------------------------------------------------------------------------
# 10. CLI
# ---------------------------------------------------------------------------

def _write(out: Path, name: str, doc) -> Path:
    out.mkdir(parents=True, exist_ok=True)
    p = out / name
    p.write_text(json.dumps(doc, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {p}")
    return p


def build_pair_index(pairs_doc: dict) -> dict[tuple[str, int], list[str]]:
    idx: dict[tuple[str, int], list[str]] = {}
    for o in pairs_doc["observations"]:
        idx.setdefault((o["member"], o["qidx"]), []).append(o["pair_id"])
    return idx


def dataset_member_index(pair_index: dict) -> dict[tuple[str, int], list[str]]:
    """Committed-member keys look like ``en-email-enron-raw/1000-verbose``; the
    dataset dirs are ``en-email-enron-raw-1k-verbose``.  Both are the SAME
    generated chains (same seed, same variant), so a query index in one is the
    same chain in the other -- remap so tier D can reuse tier-P/S pair ids."""
    remap: dict[tuple[str, int], list[str]] = {}
    for (member, qidx), pids in pair_index.items():
        corpus, variant = member.split("/")
        n_docs, suffix = variant.split("-", 1)
        scale = f"{int(n_docs) // 1000}k"
        remap[(f"{corpus}-{scale}-{suffix}", qidx)] = pids
    return remap


def cmd_pairs(args) -> None:
    docs = {}
    for lang in args.langs.split(","):
        d = extract_observed_pairs(Path(args.corpora), lang)
        for p in d["pairs"]:
            p.update(lexical_proxies(p["doc_surface"], p["query_surface"]))
        docs[lang] = d
        print(f"[{lang}] {len(d['pairs'])} pool pairs, "
              f"{sum(1 for p in d['pairs'] if p['observed_in_committed_corpora'])} observed, "
              f"{len(d['observations'])} observations, {len(d['mismatches'])} mismatches")
    _write(Path(args.out), "pairs.v1.json", {
        "note": "Synonym pairs imported from jseval.corpus_generate and joined by surface "
                "match to the committed 781 corpora. No pair is hand-authored.",
        "corpora_root": str(args.corpora), "langs": docs,
    })
    # The COMMITTED register: the same pairs + distance proxies + observation counts, minus
    # the 800 raw observations (recomputable in seconds from committed inputs).  Small enough
    # to live in the repo so a later execution pass -- or a reviewer -- can read the pair set
    # without re-deriving it or having the datasets.
    register = Path(args.register) if args.register else DEFAULT_REGISTER
    register.parent.mkdir(parents=True, exist_ok=True)
    new_langs = {lang: {"pairs": d["pairs"], "mismatches": d["mismatches"],
                        "n_observations": len(d["observations"]),
                        "n_members": d["n_members"]}
                 for lang, d in docs.items()}
    # Merge-preserve (tempdoc 832 §4): a bare `pairs` run only regenerates --langs (default
    # "en"). Overwriting the register with `new_langs` alone would silently DELETE every
    # language section this run didn't touch -- measured once losing the committed German
    # half (1,807 lines). Load whatever is already committed and keep any language section
    # this run did not regenerate byte-intact.
    merged_langs = dict(new_langs)
    preserved = []
    if register.exists():
        try:
            existing_langs = json.loads(register.read_text(encoding="utf-8")).get("langs", {})
        except (json.JSONDecodeError, OSError) as exc:
            print(f"WARNING: could not read existing register {register} ({exc}); "
                  "prior language sections will NOT be preserved this run", file=sys.stderr)
            existing_langs = {}
        for lang, section in existing_langs.items():
            if lang not in merged_langs:
                merged_langs[lang] = section
                preserved.append(lang)
    if preserved:
        print(f"NOTE: preserved {len(preserved)} existing register language section(s) not "
              f"regenerated this run: {', '.join(sorted(preserved))} "
              f"(pass --langs {','.join(sorted(preserved))} to regenerate them instead)",
              file=sys.stderr)
    # Bytes, not write_text: on Windows text mode rewrites "\n" to "\r\n", which would make
    # regenerating this committed file show up as a diff on every run.
    register.write_bytes(json.dumps({
        "schema": "paraphrase-pairs.v1",
        "note": "Regenerate with: paraphrase_bridge_suite.py pairs --langs en,de. Pairs are "
                "imported from jseval.corpus_generate (_SEM_TYPE/_SEM_PLACE/_SEM_QUAL and the "
                "DE siblings) and joined to scripts/jseval/781-corpora by surface match; "
                "n_observations counts (member, query, axis) hits across the 8 committed "
                "members. token_jaccard and stem_jaccard are 0 for every pair by construction "
                "(test_sem_pools_are_root_disjoint) -- that is what makes the suite's lexical "
                "arm a control rather than a weak baseline.",
        "tempdoc": 796,
        "langs": merged_langs,
    }, indent=1, ensure_ascii=False).encode("utf-8") + b"\n")
    print(f"wrote {register}")


def _encoders(args):
    dense = DenseEncoder(Path(args.models), threads=args.threads, batch=args.batch)
    splade = SpladeEncoder(Path(args.models), threads=args.threads, batch=args.batch)
    return dense, splade


def cmd_tier_p(args) -> None:
    dense, splade = _encoders(args)
    rows = []
    for lang in args.langs.split(","):
        rows.extend(tier_p(load_pools(lang), dense, splade, lang))
    _write(Path(args.out), "tier-p.v1.json", {
        "tier": "P", "encoders": {"dense": dense.fingerprint(), "splade": splade.fingerprint()},
        "summary": arm_summary(rows), "rows": rows,
    })


def cmd_tier_s(args) -> None:
    pairs_doc = json.loads((Path(args.out) / "pairs.v1.json").read_text(encoding="utf-8"))
    pair_index = build_pair_index(pairs_doc["langs"]["en"])
    dense, splade = _encoders(args)
    rows = []
    for md in member_dirs(Path(args.corpora)):
        t = time.time()
        rows.extend(tier_s(md, dense, splade, pair_index))
        print(f"  {member_name(md)} done ({time.time() - t:.0f}s)", flush=True)
    _write(Path(args.out), "tier-s.v1.json", {
        "tier": "S", "encoders": {"dense": dense.fingerprint(), "splade": splade.fingerprint()},
        "summary": arm_summary(rows), "rows": rows,
    })


def cmd_tier_d(args) -> None:
    pairs_doc = json.loads((Path(args.out) / "pairs.v1.json").read_text(encoding="utf-8"))
    pair_index = dataset_member_index(build_pair_index(pairs_doc["langs"]["en"]))
    pairs = {p["pair_id"]: p for p in pairs_doc["langs"]["en"]["pairs"]}
    dense, splade = _encoders(args)
    arms = set(args.arms.split(","))
    cache = None if args.no_cache else Path(args.cache or (Path(args.out) / "cache"))
    rows = []
    for member in args.member.split(","):
        t = time.time()
        rows.extend(tier_d(Path(args.datasets) / member, dense, splade, pair_index,
                           member, arms, cache, pairs))
        print(f"  {member} done ({time.time() - t:.0f}s)", flush=True)
    _write(Path(args.out), f"tier-d.{args.tag}.v1.json", {
        "tier": "D", "members": args.member.split(","), "arms": sorted(arms),
        "query_forms": ["question", "descriptor", "keyword"],
        "encoders": {"dense": dense.fingerprint(), "splade": splade.fingerprint()},
        "summary": arm_summary(rows), "rows": rows,
    })


#: The two hero anchor cases, pinned as regression rows.  Both are gold chains
#: of the enron verbose strata; ``expected`` is the BEHAVIOURAL outcome the hero
#: campaign observed, not a prediction about any single offline arm.
ANCHORS = [
    {"name": "works->mint (B|q16|s2)", "qidx": 16, "pair_ids": ["en:type:16", "en:place:16"],
     "hero_outcome": "bridged", "hero_cell": "en-email-enron-raw-1k-verbose|B|q16|s2"},
    {"name": "power station->reactor (q0)", "qidx": 0, "pair_ids": ["en:type:00", "en:place:00"],
     "hero_outcome": "unbridged (0/6 cells, both arms)", "hero_cell": "…|q0|s*"},
]


def cmd_report(args) -> None:
    out = Path(args.out)
    pairs_doc = json.loads((out / "pairs.v1.json").read_text(encoding="utf-8"))
    tp = json.loads((out / "tier-p.v1.json").read_text(encoding="utf-8"))
    pair_cos = {r["pair_id"]: r["dense_pair_cosine"] for r in tp["rows"]}

    report = {
        "note": "Paraphrase-bridging reliability suite (tempdoc 788 §3.B.10). Bucketing axis "
                "is dense_pair_cosine (isolated pair cosine, incumbent encoder); a multi-pair "
                "row buckets on its lowest-cosine pair.",
        "bucket_edges": list(DENSE_COS_EDGES),
        "pair_census": {
            "n_pool_pairs": len(pairs_doc["langs"]["en"]["pairs"]),
            "n_observed": sum(1 for p in pairs_doc["langs"]["en"]["pairs"]
                              if p["observed_in_committed_corpora"]),
            "n_observations": len(pairs_doc["langs"]["en"]["observations"]),
            "n_mismatches": len(pairs_doc["langs"]["en"]["mismatches"]),
        },
        "tiers": {},
    }
    files = {"P": "tier-p.v1.json", "S": "tier-s.v1.json"}
    for p in sorted(out.glob("tier-d.*.v1.json")):
        files[f"D:{p.name.split('.')[1]}"] = p.name
    for tier, fname in files.items():
        doc = json.loads((out / fname).read_text(encoding="utf-8"))
        rows = doc["rows"]
        entry = {"file": fname, "n_rows": len(rows), "summary": doc["summary"], "curves": {}}
        for arm in sorted({a for r in rows for a in r["arms"]}):
            entry["curves"][arm] = reliability_curve(rows, pair_cos, arm, k=args.k)
        if tier != "P":
            per_member: dict[str, list[dict]] = {}
            for r in rows:
                key = r["member"] + (f"|{r['query_form']}" if "query_form" in r else "")
                per_member.setdefault(key, []).append(r)
            entry["per_member"] = {m: arm_summary(v) for m, v in per_member.items()}
            if any("query_form" in r for r in rows):
                by_form: dict[str, list[dict]] = {}
                for r in rows:
                    by_form.setdefault(r["query_form"], []).append(r)
                entry["per_query_form"] = {f: arm_summary(v) for f, v in by_form.items()}
                entry["curves_by_form"] = {
                    f: {arm: reliability_curve(v, pair_cos, arm, k=args.k)
                        for arm in sorted({a for r in v for a in r["arms"]})}
                    for f, v in by_form.items()}
            entry["anchors"] = [
                {**a, "observed": [
                    {"member": r["member"], "query_form": r.get("query_form"),
                     "query": r["query"],
                     "arms": {n: e["rank"] for n, e in r["arms"].items()}}
                    for r in rows if r["qidx"] == a["qidx"]]}
                for a in ANCHORS
            ]
        else:
            entry["anchors"] = [
                {**a, "observed": [
                    {"pair_id": r["pair_id"], "dense_pair_cosine": r["dense_pair_cosine"],
                     "arms": {n: e["rank"] for n, e in r["arms"].items()}}
                    for r in rows if r["pair_id"] in a["pair_ids"]]}
                for a in ANCHORS
            ]
        report["tiers"][tier] = entry
    _write(out, "report.v1.json", report)
    print(json.dumps({t: e["summary"] for t, e in report["tiers"].items()}, indent=1))


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    def common(p):
        p.add_argument("--out", default=str(DEFAULT_OUT))
        p.add_argument("--corpora", default=str(DEFAULT_CORPORA))
        p.add_argument("--models", default=str(DEFAULT_MODELS))
        p.add_argument("--threads", type=int, default=10)
        p.add_argument("--batch", type=int, default=8)
        p.add_argument("--langs", default="en")

    p = sub.add_parser("pairs", help="extract + score the synonym pair register")
    common(p)
    p.add_argument("--register", default=None,
                   help=f"committed pair-register path (default {DEFAULT_REGISTER})")
    p.set_defaults(func=cmd_pairs)

    p = sub.add_parser("tier-p", help="pair-isolated bridging inside each pool")
    common(p)
    p.set_defaults(func=cmd_tier_p)

    p = sub.add_parser("tier-s", help="sentence-isolated bridging over committed members")
    common(p)
    p.set_defaults(func=cmd_tier_s)

    p = sub.add_parser("tier-d", help="in-corpus bridging over a local dataset dir")
    common(p)
    p.add_argument("--datasets", default=str(DEFAULT_DATASETS))
    p.add_argument("--member", required=True, help="comma-separated dataset dir names")
    p.add_argument("--arms", default="dense,dense-chunk,splade,splade-idf,lexical")
    p.add_argument("--tag", default="run")
    p.add_argument("--cache", default=None,
                   help="checkpoint dir for encoded blocks (default <out>/cache)")
    p.add_argument("--no-cache", action="store_true",
                   help="disable block checkpointing (re-encodes everything)")
    p.set_defaults(func=cmd_tier_d)

    p = sub.add_parser("report", help="reliability curves + anchor rows")
    p.add_argument("--out", default=str(DEFAULT_OUT))
    p.add_argument("--k", type=int, default=10)
    p.set_defaults(func=cmd_report)

    args = ap.parse_args(argv)
    args.func(args)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
