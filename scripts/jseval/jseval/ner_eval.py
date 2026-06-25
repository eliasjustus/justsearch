"""Standalone NER quality evaluation against CoNLL-2003.

Runs the JustSearch ONNX NER model + subword aggregation decoder on the
CoNLL-2003 test set and reports entity-level F1. Compares against the
published dslim/distilbert-NER F1=92.2%.

Usage:
    python -m jseval ner-eval --model-dir models/onnx/ner/
"""

from __future__ import annotations

import json
import logging
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

log = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Entity-level F1 (replaces seqeval — incompatible with Python 3.14)
# ---------------------------------------------------------------------------


def _extract_spans(tags: list[str]) -> set[tuple[str, int, int]]:
    """Extract (entity_type, start, end) spans from a BIO tag sequence.

    Returns a set of spans where each span is (type, start_inclusive, end_exclusive).
    """
    spans = set()
    current_type = None
    current_start = 0

    for i, tag in enumerate(tags):
        if tag.startswith("B-"):
            if current_type is not None:
                spans.add((current_type, current_start, i))
            current_type = tag[2:]
            current_start = i
        elif tag.startswith("I-"):
            etype = tag[2:]
            if current_type != etype:
                # I- without matching B- — treat as new entity (lenient)
                if current_type is not None:
                    spans.add((current_type, current_start, i))
                current_type = etype
                current_start = i
        else:
            if current_type is not None:
                spans.add((current_type, current_start, i))
                current_type = None

    if current_type is not None:
        spans.add((current_type, current_start, len(tags)))

    return spans


def _compute_f1(gold_tags_list: list[list[str]], pred_tags_list: list[list[str]],
                entity_types: list[str] | None = None):
    """Compute entity-level precision, recall, F1 (micro-averaged and per-type).

    Args:
        gold_tags_list: list of sentences, each a list of BIO tags
        pred_tags_list: list of sentences, each a list of BIO tags
        entity_types: if set, only count these types; None = all types
    """
    tp_total, fp_total, fn_total = 0, 0, 0
    tp_by_type = defaultdict(int)
    fp_by_type = defaultdict(int)
    fn_by_type = defaultdict(int)

    for gold_tags, pred_tags in zip(gold_tags_list, pred_tags_list):
        gold_spans = _extract_spans(gold_tags)
        pred_spans = _extract_spans(pred_tags)

        if entity_types is not None:
            gold_spans = {s for s in gold_spans if s[0] in entity_types}
            pred_spans = {s for s in pred_spans if s[0] in entity_types}

        tp = gold_spans & pred_spans
        fp = pred_spans - gold_spans
        fn = gold_spans - pred_spans

        tp_total += len(tp)
        fp_total += len(fp)
        fn_total += len(fn)

        for span in tp:
            tp_by_type[span[0]] += 1
        for span in fp:
            fp_by_type[span[0]] += 1
        for span in fn:
            fn_by_type[span[0]] += 1

    def _prf(tp, fp, fn):
        p = tp / (tp + fp) if (tp + fp) > 0 else 0.0
        r = tp / (tp + fn) if (tp + fn) > 0 else 0.0
        f1 = 2 * p * r / (p + r) if (p + r) > 0 else 0.0
        return p, r, f1

    overall_p, overall_r, overall_f1 = _prf(tp_total, fp_total, fn_total)

    all_types = sorted(set(list(tp_by_type.keys()) + list(fp_by_type.keys()) + list(fn_by_type.keys())))
    per_type = {}
    for etype in all_types:
        tp_t = tp_by_type[etype]
        fp_t = fp_by_type[etype]
        fn_t = fn_by_type[etype]
        p, r, f1 = _prf(tp_t, fp_t, fn_t)
        per_type[etype] = {
            "f1": f1, "precision": p, "recall": r,
            "support": tp_t + fn_t,  # gold count for this type
        }

    return {
        "f1": overall_f1, "precision": overall_p, "recall": overall_r,
        "tp": tp_total, "fp": fp_total, "fn": fn_total,
        "per_type": per_type,
    }


# ---------------------------------------------------------------------------
# Model loading and inference
# ---------------------------------------------------------------------------


def _load_model(model_dir: Path):
    """Load ONNX model, tokenizer, and label mapping."""
    import onnxruntime as ort
    from tokenizers import Tokenizer

    model_path = model_dir / "model.onnx"
    tokenizer_path = model_dir / "tokenizer.json"
    config_path = model_dir / "config.json"

    if not model_path.exists():
        raise FileNotFoundError(f"model.onnx not found in {model_dir}")
    if not tokenizer_path.exists():
        raise FileNotFoundError(f"tokenizer.json not found in {model_dir}")

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    tokenizer = Tokenizer.from_file(str(tokenizer_path))
    input_names = [i.name for i in session.get_inputs()]
    needs_token_type_ids = "token_type_ids" in input_names

    # Load id2label from config.json
    if config_path.exists():
        with open(config_path) as f:
            config = json.load(f)
        id2label_raw = config.get("id2label", {})
        max_id = max(int(k) for k in id2label_raw)
        labels = ["O"] * (max_id + 1)
        for k, v in id2label_raw.items():
            labels[int(k)] = v
    else:
        labels = ["O", "B-MISC", "I-MISC", "B-PER", "I-PER", "B-ORG", "I-ORG", "B-LOC", "I-LOC"]

    log.info(
        "Loaded NER model: %s, labels=%d, token_type_ids=%s",
        model_path.name, len(labels), needs_token_type_ids,
    )
    return session, tokenizer, labels, needs_token_type_ids


def _predict_sentence(session, tokenizer, labels, needs_tti, words: list[str]) -> list[str]:
    """Run NER on a list of words, return per-word BIO tags.

    Uses the same subword aggregation as BioTagDecoder: only the first
    subword of each word determines the BIO tag.
    """
    enc = tokenizer.encode(words, is_pretokenized=True)
    word_ids = enc.word_ids

    input_ids = np.array([enc.ids], dtype=np.int64)
    attention_mask = np.array([enc.attention_mask], dtype=np.int64)
    inputs = {"input_ids": input_ids, "attention_mask": attention_mask}
    if needs_tti:
        inputs["token_type_ids"] = np.zeros_like(input_ids)

    logits = session.run(None, inputs)[0][0]  # [seq_len, num_labels]

    # Subword aggregation: take prediction from FIRST subword of each word.
    word_preds = {}
    for i, wid in enumerate(word_ids):
        if wid is None:
            continue
        if wid not in word_preds:
            pred = int(np.argmax(logits[i]))
            tag = labels[pred] if pred < len(labels) else "O"
            word_preds[wid] = tag

    result = []
    for idx in range(len(words)):
        result.append(word_preds.get(idx, "O"))

    return result


def _load_conll2003_test(data_dir: Path | None = None):
    """Load CoNLL-2003 test set from local JSON files.

    Expects tner/conll2003 format: one JSON object per line with "tokens"
    (list[str]) and "tags" (list[int]).  Label mapping comes from label.json
    in the same directory.

    Default data directory: ``<jseval>/data/conll2003/``.
    Download with::

        curl -sL https://huggingface.co/datasets/tner/conll2003/resolve/main/dataset/test.json  -o data/conll2003/test.json
        curl -sL https://huggingface.co/datasets/tner/conll2003/resolve/main/dataset/label.json -o data/conll2003/label.json
    """
    if data_dir is None:
        data_dir = Path(__file__).parent.parent / "data" / "conll2003"

    test_path = data_dir / "test.json"
    label_path = data_dir / "label.json"

    if not test_path.exists():
        raise FileNotFoundError(
            f"CoNLL-2003 test.json not found at {test_path}. Download it with:\n"
            f"  curl -sL https://huggingface.co/datasets/tner/conll2003/resolve/main/dataset/test.json -o {test_path}"
        )

    # Load label mapping: {"O": 0, "B-ORG": 1, ...} → id2label list
    if label_path.exists():
        with open(label_path) as f:
            label_map = json.load(f)
        max_id = max(label_map.values())
        id2label = ["O"] * (max_id + 1)
        for name, idx in label_map.items():
            id2label[idx] = name
    else:
        # Fallback: standard CoNLL-2003 ordering (eriktks)
        id2label = ["O", "B-PER", "I-PER", "B-ORG", "I-ORG", "B-LOC", "I-LOC", "B-MISC", "I-MISC"]

    log.info("Label mapping: %s", id2label)

    sentences = []
    gold_tags = []
    with open(test_path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            words = row["tokens"]
            tags = [id2label[t] for t in row["tags"]]
            if words:
                sentences.append(words)
                gold_tags.append(tags)

    log.info("Loaded %d sentences from %s", len(sentences), test_path)
    return sentences, gold_tags


# ---------------------------------------------------------------------------
# Main evaluation
# ---------------------------------------------------------------------------


def run_ner_eval(model_dir: Path, max_sentences: int = 0, verbose: bool = False,
                 data_dir: Path | None = None):
    """Run NER evaluation and return results dict."""
    session, tokenizer, labels, needs_tti = _load_model(model_dir)
    sentences, gold_tags = _load_conll2003_test(data_dir)

    if max_sentences > 0:
        sentences = sentences[:max_sentences]
        gold_tags = gold_tags[:max_sentences]

    log.info("Running inference on %d sentences...", len(sentences))
    pred_tags = []
    t0 = time.time()

    for i, words in enumerate(sentences):
        preds = _predict_sentence(session, tokenizer, labels, needs_tti, words)
        pred_tags.append(preds)

        if verbose and i < 5:
            log.info("Sentence %d: %s", i, " ".join(words[:15]))
            for w, g, p in zip(words, gold_tags[i], preds):
                if g != "O" or p != "O":
                    marker = "OK" if g == p else "XX"
                    log.info("  %s %-20s gold=%-8s pred=%-8s", marker, w, g, p)

    elapsed = time.time() - t0
    docs_per_sec = len(sentences) / elapsed if elapsed > 0 else 0

    # All entity types (including MISC)
    all_metrics = _compute_f1(gold_tags, pred_tags)

    # Without MISC (production config filters MISC)
    gold_no_misc = [["O" if "MISC" in t else t for t in s] for s in gold_tags]
    pred_no_misc = [["O" if "MISC" in t else t for t in s] for s in pred_tags]
    no_misc_metrics = _compute_f1(gold_no_misc, pred_no_misc)

    results = {
        "model_dir": str(model_dir),
        "dataset": "conll2003-test",
        "sentences": len(sentences),
        "elapsed_sec": round(elapsed, 1),
        "sentences_per_sec": round(docs_per_sec, 1),
        "labels": labels,
        "all_types": {
            "f1": round(all_metrics["f1"], 4),
            "precision": round(all_metrics["precision"], 4),
            "recall": round(all_metrics["recall"], 4),
        },
        "no_misc": {
            "f1": round(no_misc_metrics["f1"], 4),
        },
        "per_type": {},
        "published_f1": 0.9217,
    }

    for etype in ["PER", "ORG", "LOC", "MISC"]:
        if etype in all_metrics["per_type"]:
            t = all_metrics["per_type"][etype]
            results["per_type"][etype] = {
                "f1": round(t["f1"], 4),
                "precision": round(t["precision"], 4),
                "recall": round(t["recall"], 4),
                "support": t["support"],
            }

    return results


def print_ner_eval_results(results: dict):
    """Pretty-print NER eval results."""
    print(f"\nNER Evaluation: {results['dataset']}")
    print(f"Model: {results['model_dir']}")
    print(f"Sentences: {results['sentences']} ({results['sentences_per_sec']} sent/sec)")
    print()
    print(f"  Overall F1 (all types):  {results['all_types']['f1']:.4f}")
    print(f"  Overall F1 (no MISC):    {results['no_misc']['f1']:.4f}")
    print(f"  Published F1:            {results['published_f1']:.4f}")
    delta = results["all_types"]["f1"] - results["published_f1"]
    print(f"  Delta vs published:      {delta:+.4f}")
    print()
    print(f"  {'Type':<6s} {'F1':>7s} {'Prec':>7s} {'Rec':>7s} {'Support':>8s}")
    print(f"  {'-'*38}")
    for etype in ["PER", "ORG", "LOC", "MISC"]:
        if etype in results["per_type"]:
            t = results["per_type"][etype]
            print(f"  {etype:<6s} {t['f1']:7.4f} {t['precision']:7.4f} {t['recall']:7.4f} {t['support']:8d}")
