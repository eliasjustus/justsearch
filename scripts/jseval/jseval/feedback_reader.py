"""Reader over the tempdoc 580 §17 ResultDisposition feedback stream (tempdoc 778).

This is the *one interface* future learned-ranking work consumes for real
implicit-feedback labels. It reads the ALREADY-canonical consolidation stream
the Head persists — it does NOT define a second store (580 §17.3 anti-fork:
"the agentic-citation harvest and the search-UI interaction both land here, not
in two stores"). Two on-disk files under ``<dataDir>/feedback/``:

* ``result-dispositions.ndjson`` — one ``ResultDisposition`` per line:
  ``{interactionId, docId, kind, contributor, occurredAtMs}``. ``contributor`` is
  ``SEARCH_INTERACTION`` (user click/open/dwell), ``AGENT_CITATION`` (the LLM's
  grounding/citation harvest), or ``EXPLICIT_RATING``.
* ``feature-snapshots.ndjson`` — one ``FeatureSnapshot`` per line:
  ``{interactionId, query, occurredAtMs, hits:[{docId, rank, sparse, dense,
  splade, fused, parentTokenCount}]}`` — the per-query ranking features to join
  a disposition back to "what we ranked" (the §17.4 join).

Encryption caveat (tempdoc 778 store hardening): when Head at-rest encryption is
ENABLED, these files are sealed line-by-line with the ``JSEv1:`` prefix and the
data key lives Head-side — Python cannot decrypt them. Such lines are counted
and skipped (``sealed_skipped``), never mis-parsed. Eval/ranking runs use a
plaintext data dir, so this reader consumes them directly; an encrypted profile
must read through a Head endpoint instead.
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

log = logging.getLogger(__name__)

# Mirrors io.justsearch.agent.api.encryption.StoreCipher.MAGIC — a sealed line's
# prefix. Python holds no data key, so a sealed line is uncounted-as-data.
SEALED_PREFIX = "JSEv1:"

_FEEDBACK_DIR = "feedback"
_DISPOSITIONS_FILE = "result-dispositions.ndjson"
_SNAPSHOTS_FILE = "feature-snapshots.ndjson"

# The graded positive tiers (mirrors ResultDisposition.Kind); REFINED_WITHOUT_OPENING
# and the derived SHOWN are the negatives. Kept here so a ranking consumer can bucket
# signals without re-deriving the label polarity.
POSITIVE_KINDS = frozenset({"OPENED", "DWELLED", "CITED", "ACTED_ON"})
NEGATIVE_KINDS = frozenset({"SHOWN", "REFINED_WITHOUT_OPENING"})


class FeedbackReadResult:
    """Records read from one ndjson file plus how many sealed lines were skipped."""

    __slots__ = ("records", "sealed_skipped")

    def __init__(self, records: list[dict], sealed_skipped: int) -> None:
        self.records = records
        self.sealed_skipped = sealed_skipped


def _read_ndjson(path: Path) -> FeedbackReadResult:
    """Parse an ndjson file, skipping blank/unparseable/sealed lines.

    Missing file → empty result (not an error): capture is best-effort and the
    stream may not exist yet (cold start).
    """
    records: list[dict] = []
    sealed = 0
    if not path.is_file():
        return FeedbackReadResult(records, sealed)
    try:
        with path.open("r", encoding="utf-8") as f:
            for line_no, line in enumerate(f, start=1):
                line = line.strip()
                if not line:
                    continue
                if line.startswith(SEALED_PREFIX):
                    sealed += 1
                    continue
                try:
                    records.append(json.loads(line))
                except json.JSONDecodeError:
                    log.debug("Skipping unparseable line %s:%d", path.name, line_no)
    except OSError as e:
        log.debug("Failed to read feedback file %s: %s", path, e)
    if sealed:
        log.warning(
            "%s: skipped %d sealed line(s) — Head at-rest encryption is on; "
            "read feedback through a Head endpoint, not the raw file.",
            path.name,
            sealed,
        )
    return FeedbackReadResult(records, sealed)


def read_dispositions(data_dir: Path) -> FeedbackReadResult:
    """All persisted ``ResultDisposition`` records under ``<data_dir>/feedback/``."""
    return _read_ndjson(Path(data_dir) / _FEEDBACK_DIR / _DISPOSITIONS_FILE)


def read_feature_snapshots(data_dir: Path) -> FeedbackReadResult:
    """All persisted ``FeatureSnapshot`` records under ``<data_dir>/feedback/``."""
    return _read_ndjson(Path(data_dir) / _FEEDBACK_DIR / _SNAPSHOTS_FILE)


def read_feedback_signals(data_dir: Path) -> list[dict]:
    """The unified feedback-signal stream (the one interface, tempdoc 778).

    Each disposition normalized to ``{source, contributor, kind, polarity, docId,
    interactionId, occurredAtMs}`` where ``source`` is ``"user-event"`` for a
    ``SEARCH_INTERACTION`` and ``"agent-citation"`` otherwise, and ``polarity`` is
    ``"positive"`` / ``"negative"`` / ``"unknown"``. Ordered by ``occurredAtMs``.
    """
    signals: list[dict] = []
    for d in read_dispositions(data_dir).records:
        kind = d.get("kind")
        contributor = d.get("contributor")
        signals.append(
            {
                "source": "user-event" if contributor == "SEARCH_INTERACTION" else "agent-citation",
                "contributor": contributor,
                "kind": kind,
                "polarity": (
                    "positive" if kind in POSITIVE_KINDS
                    else "negative" if kind in NEGATIVE_KINDS
                    else "unknown"
                ),
                "docId": d.get("docId"),
                "interactionId": d.get("interactionId"),
                "occurredAtMs": d.get("occurredAtMs"),
            }
        )
    signals.sort(key=lambda s: s.get("occurredAtMs") or 0)
    return signals


def read_labeled_examples(data_dir: Path) -> list[dict]:
    """Ranking-ready examples: each explicit disposition joined to its ranked features.

    Mirrors ``LabelProjection`` pass 1 (the §17.4 join) read-only: a disposition
    joins the ``FeatureSnapshot`` sharing its ``interactionId`` on ``docId``. A
    disposition with no joinable snapshot hit is dropped (it cannot become a
    featured example) — the same honest limit the Java projection surfaces. This
    is the labelled feature-vector interface a trainer/eval consumes; it does not
    write anything.
    """
    # interactionId -> docId -> features (first-seen wins, mirroring the Java union).
    by_interaction: dict[str, dict[str, dict]] = {}
    for snap in read_feature_snapshots(data_dir).records:
        iid = snap.get("interactionId")
        if iid is None:
            continue
        by_doc = by_interaction.setdefault(iid, {})
        for hit in snap.get("hits") or []:
            doc_id = hit.get("docId")
            if doc_id is not None:
                by_doc.setdefault(doc_id, hit)

    examples: list[dict] = []
    for d in read_dispositions(data_dir).records:
        iid = d.get("interactionId")
        doc_id = d.get("docId")
        features = by_interaction.get(iid, {}).get(doc_id)
        if features is None:
            continue
        kind = d.get("kind")
        examples.append(
            {
                "interactionId": iid,
                "docId": doc_id,
                "kind": kind,
                "contributor": d.get("contributor"),
                "polarity": (
                    "positive" if kind in POSITIVE_KINDS
                    else "negative" if kind in NEGATIVE_KINDS
                    else "unknown"
                ),
                "occurredAtMs": d.get("occurredAtMs"),
                "features": {
                    "rank": features.get("rank"),
                    "sparse": features.get("sparse"),
                    "dense": features.get("dense"),
                    "splade": features.get("splade"),
                    "fused": features.get("fused"),
                    "parentTokenCount": features.get("parentTokenCount"),
                },
            }
        )
    return examples


def summarize(data_dir: Path) -> dict:
    """A compact roll-up for the live-verify / at-a-glance interface.

    Counts by contributor + polarity, joinable-example count, and any sealed-line
    skips (which flag an encrypted profile Python cannot read directly).
    """
    disp = read_dispositions(data_dir)
    snaps = read_feature_snapshots(data_dir)
    signals = read_feedback_signals(data_dir)
    by_contributor: dict[str, int] = {}
    by_polarity: dict[str, int] = {}
    for s in signals:
        by_contributor[s["contributor"]] = by_contributor.get(s["contributor"], 0) + 1
        by_polarity[s["polarity"]] = by_polarity.get(s["polarity"], 0) + 1
    return {
        "dispositions": len(disp.records),
        "featureSnapshots": len(snaps.records),
        "labeledExamples": len(read_labeled_examples(data_dir)),
        "byContributor": by_contributor,
        "byPolarity": by_polarity,
        "sealedSkipped": disp.sealed_skipped + snaps.sealed_skipped,
    }
