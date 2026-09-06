"""Private, blinded label state for duplicate-review packets.

The source packet contains sensitive text and stays below ``scripts/jseval/tmp``.
This module persists only pair identifiers and human decisions, bound to the
packet and analyzer hashes.  It deliberately exposes no experimental strata or
similarity values to the presentation layer.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import tempfile
from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

from . import duplicate_review_packet as packet_module


SCHEMA = "jseval.duplicate-review-labels.v1"
TRIAGE_SCHEMA = "jseval.duplicate-review-model-triage.v1"
TRIAGE_METHOD = "codex-blinded-high-confidence-binary-v1"
ORDER_METHOD = "sha256-domain-separated-blinded-v1"
ORIENTATION_METHOD = "sha256-domain-separated-orientation-v1"
LABEL_VOCABULARY = (
    "NEAR_DUPLICATE",
    "NOT_NEAR_DUPLICATE",
    "UNCERTAIN",
    "ABSTAIN",
)
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_PACKET_FIELDS = frozenset(
    {
        "schema",
        "artifact_kind",
        "sensitivity",
        "intended_persistence",
        "source",
        "review_config",
        "partition",
        "sampling",
        "records",
        "label_status",
        "near_duplicate_decision",
        "packet_hash",
    }
)
_PACKET_RECORD_FIELDS = frozenset(
    {
        "pair_id",
        "split",
        "sampling_frame",
        "stratum",
        "inclusion_probability",
        "sampling_weight",
        "similarity",
        "left",
        "right",
        "label",
        "labeler",
        "notes",
    }
)
_PACKET_SIDE_FIELDS = frozenset({"opaque_id", "format_id", "token_count", "text"})
_LABEL_FIELDS = frozenset(
    {"schema", "packet_binding", "presentation", "label_vocabulary", "records", "labels_hash"}
)
_BINDING_FIELDS = frozenset(
    {"packet_schema", "packet_hash", "analyzer_artifact_hash", "record_count"}
)
_PRESENTATION_FIELDS = frozenset({"order", "left_right"})
_TRIAGE_DECISIONS = frozenset(
    {"AUTO_NEAR_DUPLICATE", "AUTO_NOT_NEAR_DUPLICATE", "HUMAN_REVIEW"}
)
_TRIAGE_FIELDS = frozenset(
    {"schema", "packet_binding", "labels_binding", "method", "records", "triage_hash"}
)


class DuplicateReviewLabelsError(ValueError):
    """The packet, label state, or private destination is invalid."""


def _canonical_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise DuplicateReviewLabelsError("label data must be canonical UTF-8 JSON values") from exc


def _digest(value: object) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _labels_digest(labels: Mapping[str, Any]) -> str:
    return _digest({key: value for key, value in labels.items() if key != "labels_hash"})


def default_labels_path(packet_path: Path | str) -> Path:
    """Return the private sibling used when ``--labels-out`` is omitted."""

    packet = Path(packet_path)
    return packet.with_name(f"{packet.stem}.labels.local.json")


def _private_file(path: Path | str, *, role: str) -> Path:
    try:
        destination = packet_module.validate_review_packet_destination(path)
    except packet_module.DuplicateReviewPacketError as exc:
        raise DuplicateReviewLabelsError(str(exc).replace("review packet", role)) from exc
    return destination


def _read_json(path: Path, *, role: str) -> Any:
    try:
        raw = path.read_text(encoding="utf-8")
        return json.loads(raw)
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise DuplicateReviewLabelsError(f"could not read valid UTF-8 JSON from {role}") from exc


def _validate_packet(packet: Any) -> dict[str, Any]:
    if not isinstance(packet, dict) or set(packet) != _PACKET_FIELDS:
        raise DuplicateReviewLabelsError("review packet has an unexpected top-level shape")
    if (
        packet["schema"] != packet_module.SCHEMA
        or packet["artifact_kind"] != "sensitive-local-review-packet"
        or packet["sensitivity"] != "local-review-text"
        or packet["intended_persistence"] != "uncommitted-local-only"
        or packet["label_status"] != "UNLABELED"
    ):
        raise DuplicateReviewLabelsError("review packet provenance or label status is unsupported")
    packet_hash = packet["packet_hash"]
    if not isinstance(packet_hash, str) or _SHA256_RE.fullmatch(packet_hash) is None:
        raise DuplicateReviewLabelsError("review packet hash is malformed")
    unhashed = dict(packet)
    unhashed.pop("packet_hash")
    if _digest(unhashed) != packet_hash:
        raise DuplicateReviewLabelsError("review packet hash mismatch")
    source = packet["source"]
    if not isinstance(source, dict):
        raise DuplicateReviewLabelsError("review packet source binding is malformed")
    analyzer_hash = source.get("analyzer_artifact_hash")
    if not isinstance(analyzer_hash, str) or _SHA256_RE.fullmatch(analyzer_hash) is None:
        raise DuplicateReviewLabelsError("review packet analyzer binding is malformed")
    decision = packet["near_duplicate_decision"]
    if decision != {"status": "UNDECIDED", "selected_threshold": None}:
        raise DuplicateReviewLabelsError("review packet must have an undecided threshold")
    records = packet["records"]
    if not isinstance(records, list) or not records:
        raise DuplicateReviewLabelsError("review packet records must be a nonempty list")
    pair_ids: set[str] = set()
    for record in records:
        if not isinstance(record, dict) or set(record) != _PACKET_RECORD_FIELDS:
            raise DuplicateReviewLabelsError("review packet record shape is malformed")
        pair_id = record["pair_id"]
        if (
            not isinstance(pair_id, str)
            or _SHA256_RE.fullmatch(pair_id) is None
            or pair_id in pair_ids
        ):
            raise DuplicateReviewLabelsError("review packet pair identifiers are malformed or duplicated")
        pair_ids.add(pair_id)
        if record["label"] is not None or record["labeler"] is not None or record["notes"] is not None:
            raise DuplicateReviewLabelsError("source packet labels and notes must remain empty")
        for side_name in ("left", "right"):
            side = record[side_name]
            if not isinstance(side, dict) or set(side) != _PACKET_SIDE_FIELDS:
                raise DuplicateReviewLabelsError("review packet text side is malformed")
            if (
                not isinstance(side["opaque_id"], str)
                or _SHA256_RE.fullmatch(side["opaque_id"]) is None
                or not isinstance(side["text"], str)
                or not side["text"].strip()
            ):
                raise DuplicateReviewLabelsError("review packet text side is not reviewable")
        if record["left"]["opaque_id"] == record["right"]["opaque_id"]:
            raise DuplicateReviewLabelsError("review packet pair must contain two distinct records")
    return packet


def _rank(packet_hash: str, pair_id: str, domain: str) -> str:
    return _digest([domain, packet_hash, pair_id])


def _ordered_records(packet: Mapping[str, Any]) -> list[dict[str, Any]]:
    return sorted(
        packet["records"],
        key=lambda record: (
            _rank(packet["packet_hash"], record["pair_id"], "duplicate-review-label-order-v1"),
            record["pair_id"],
        ),
    )


def _new_labels(packet: Mapping[str, Any]) -> dict[str, Any]:
    artifact: dict[str, Any] = {
        "schema": SCHEMA,
        "packet_binding": {
            "packet_schema": packet_module.SCHEMA,
            "packet_hash": packet["packet_hash"],
            "analyzer_artifact_hash": packet["source"]["analyzer_artifact_hash"],
            "record_count": len(packet["records"]),
        },
        "presentation": {
            "order": ORDER_METHOD,
            "left_right": ORIENTATION_METHOD,
        },
        "label_vocabulary": list(LABEL_VOCABULARY),
        "records": [
            {"pair_id": record["pair_id"], "label": None}
            for record in _ordered_records(packet)
        ],
    }
    artifact["labels_hash"] = _digest(artifact)
    return artifact


def _validate_labels(labels: Any, packet: Mapping[str, Any]) -> dict[str, Any]:
    if not isinstance(labels, dict) or set(labels) != _LABEL_FIELDS:
        raise DuplicateReviewLabelsError("label artifact has an unexpected top-level shape")
    labels_hash = labels["labels_hash"]
    if not isinstance(labels_hash, str) or _SHA256_RE.fullmatch(labels_hash) is None:
        raise DuplicateReviewLabelsError("label artifact hash is malformed")
    unhashed = dict(labels)
    unhashed.pop("labels_hash")
    if _digest(unhashed) != labels_hash:
        raise DuplicateReviewLabelsError("label artifact hash mismatch")
    binding = labels["packet_binding"]
    expected = _new_labels(packet)
    if not isinstance(binding, dict) or set(binding) != _BINDING_FIELDS:
        raise DuplicateReviewLabelsError("label artifact packet binding is malformed")
    if binding != expected["packet_binding"]:
        raise DuplicateReviewLabelsError("label artifact does not match this review packet")
    presentation = labels["presentation"]
    if not isinstance(presentation, dict) or set(presentation) != _PRESENTATION_FIELDS:
        raise DuplicateReviewLabelsError("label artifact presentation binding is malformed")
    if presentation != expected["presentation"]:
        raise DuplicateReviewLabelsError("label artifact presentation method is unsupported")
    if labels["label_vocabulary"] != list(LABEL_VOCABULARY):
        raise DuplicateReviewLabelsError("label artifact vocabulary is unsupported")
    records = labels["records"]
    if not isinstance(records, list) or len(records) != len(expected["records"]):
        raise DuplicateReviewLabelsError("label artifact record count is inconsistent")
    if [row.get("pair_id") for row in records if isinstance(row, dict)] != [
        row["pair_id"] for row in expected["records"]
    ]:
        raise DuplicateReviewLabelsError("label artifact pair order or population is inconsistent")
    for row in records:
        if not isinstance(row, dict) or set(row) != {"pair_id", "label"}:
            raise DuplicateReviewLabelsError("label artifact record shape is malformed")
        if row["label"] is not None and row["label"] not in LABEL_VOCABULARY:
            raise DuplicateReviewLabelsError("label artifact contains an unsupported label")
    return labels


def write_labels_atomic(path: Path, labels: Mapping[str, Any]) -> None:
    """Durably replace label state without exposing a partial JSON file."""

    path.parent.mkdir(parents=True, exist_ok=True)
    payload = _canonical_bytes(labels) + b"\n"
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="wb", prefix=f".{path.name}.", suffix=".tmp", dir=path.parent, delete=False
        ) as handle:
            temporary = Path(handle.name)
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        temporary = None
    finally:
        if temporary is not None:
            try:
                temporary.unlink()
            except FileNotFoundError:
                pass


def _triage_label(decision: str) -> str | None:
    return {
        "AUTO_NEAR_DUPLICATE": "NEAR_DUPLICATE",
        "AUTO_NOT_NEAR_DUPLICATE": "NOT_NEAR_DUPLICATE",
        "HUMAN_REVIEW": None,
    }[decision]


def _validate_triage(
    triage: Any, packet: Mapping[str, Any], labels: Mapping[str, Any]
) -> dict[str, Any]:
    if not isinstance(triage, dict) or set(triage) != _TRIAGE_FIELDS:
        raise DuplicateReviewLabelsError("model-triage artifact has an unexpected top-level shape")
    triage_hash = triage["triage_hash"]
    if not isinstance(triage_hash, str) or _SHA256_RE.fullmatch(triage_hash) is None:
        raise DuplicateReviewLabelsError("model-triage artifact hash is malformed")
    unhashed = dict(triage)
    unhashed.pop("triage_hash")
    if _digest(unhashed) != triage_hash:
        raise DuplicateReviewLabelsError("model-triage artifact hash mismatch")
    expected_packet_binding = {
        "packet_schema": packet_module.SCHEMA,
        "packet_hash": packet["packet_hash"],
        "analyzer_artifact_hash": packet["source"]["analyzer_artifact_hash"],
    }
    if triage["schema"] != TRIAGE_SCHEMA or triage["packet_binding"] != expected_packet_binding:
        raise DuplicateReviewLabelsError("model-triage artifact does not match this packet")
    if triage["method"] != TRIAGE_METHOD:
        raise DuplicateReviewLabelsError("model-triage method is unsupported")
    binding = triage["labels_binding"]
    if (
        not isinstance(binding, dict)
        or set(binding) != {"schema", "before_hash", "after_hash"}
        or binding["schema"] != SCHEMA
        or any(
            not isinstance(binding[key], str) or _SHA256_RE.fullmatch(binding[key]) is None
            for key in ("before_hash", "after_hash")
        )
    ):
        raise DuplicateReviewLabelsError("model-triage label-state binding is inconsistent")
    records = triage["records"]
    if not isinstance(records, list) or not records:
        raise DuplicateReviewLabelsError("model-triage records must be a nonempty list")
    seen: set[str] = set()
    label_by_pair = {row["pair_id"]: row["label"] for row in labels["records"]}
    for row in records:
        if not isinstance(row, dict) or set(row) != {"pair_id", "decision", "label"}:
            raise DuplicateReviewLabelsError("model-triage record shape is malformed")
        pair_id = row["pair_id"]
        decision = row["decision"]
        if pair_id in seen or pair_id not in label_by_pair:
            raise DuplicateReviewLabelsError("model-triage pair population is malformed")
        seen.add(pair_id)
        if decision not in _TRIAGE_DECISIONS or row["label"] != _triage_label(decision):
            raise DuplicateReviewLabelsError("model-triage decision is malformed")
        if decision.startswith("AUTO_") and label_by_pair[pair_id] != row["label"]:
            raise DuplicateReviewLabelsError("model-triage auto-label is absent from label state")
    record_by_pair = {row["pair_id"]: row for row in triage["records"]}
    post_triage = deepcopy(labels)
    for row in post_triage["records"]:
        triage_row = record_by_pair.get(row["pair_id"])
        if triage_row is not None and triage_row["decision"] == "HUMAN_REVIEW":
            row["label"] = None
    if _labels_digest(post_triage) != binding["after_hash"]:
        raise DuplicateReviewLabelsError("model-triage post-state reconstruction failed")
    pre_triage = deepcopy(post_triage)
    for row in pre_triage["records"]:
        if row["pair_id"] in record_by_pair:
            row["label"] = None
    if _labels_digest(pre_triage) != binding["before_hash"]:
        raise DuplicateReviewLabelsError("model-triage pre-state reconstruction failed")
    return triage


def apply_model_triage(
    packet_path: Path | str,
    labels_path: Path | str,
    triage_path: Path | str,
    decisions: Mapping[str, str],
) -> dict[str, int]:
    """Apply complete blinded triage to currently unlabeled pairs and bind a sidecar."""

    session = open_label_session(packet_path, labels_path)
    triage_file = _private_file(triage_path, role="model-triage artifact")
    if triage_file in {session.packet_path, session.labels_path}:
        raise DuplicateReviewLabelsError("packet, labels, and model-triage destinations must differ")
    if not isinstance(decisions, Mapping):
        raise DuplicateReviewLabelsError("model-triage decisions must be a mapping")
    unlabeled_ids = [
        row["pair_id"] for row in session._labels["records"] if row["label"] is None
    ]
    if set(decisions) != set(unlabeled_ids) or len(decisions) != len(unlabeled_ids):
        raise DuplicateReviewLabelsError(
            "model-triage decisions must cover every and only currently unlabeled pair"
        )
    for pair_id, decision in decisions.items():
        if not isinstance(pair_id, str) or decision not in _TRIAGE_DECISIONS:
            raise DuplicateReviewLabelsError("model-triage contains an unsupported decision")

    before_hash = session._labels["labels_hash"]
    updated = deepcopy(session._labels)
    by_pair = {row["pair_id"]: row for row in updated["records"]}
    records = []
    for pair_id in unlabeled_ids:
        decision = decisions[pair_id]
        label = _triage_label(decision)
        if label is not None:
            by_pair[pair_id]["label"] = label
        records.append({"pair_id": pair_id, "decision": decision, "label": label})
    updated["labels_hash"] = _labels_digest(updated)
    triage: dict[str, Any] = {
        "schema": TRIAGE_SCHEMA,
        "packet_binding": {
            "packet_schema": packet_module.SCHEMA,
            "packet_hash": session._packet["packet_hash"],
            "analyzer_artifact_hash": session._packet["source"]["analyzer_artifact_hash"],
        },
        "labels_binding": {
            "schema": SCHEMA,
            "before_hash": before_hash,
            "after_hash": updated["labels_hash"],
        },
        "method": TRIAGE_METHOD,
        "records": records,
    }
    triage["triage_hash"] = _digest(triage)
    created_triage = False
    if triage_file.exists():
        existing = _read_json(triage_file, role="model-triage artifact")
        if existing != triage:
            raise DuplicateReviewLabelsError(
                "model-triage destination exists with different content"
            )
    else:
        write_labels_atomic(triage_file, triage)
        created_triage = True
    try:
        write_labels_atomic(session.labels_path, updated)
    except Exception:
        if created_triage:
            try:
                triage_file.unlink()
            except OSError:
                pass
        raise
    return {
        "auto_labeled": sum(row["label"] is not None for row in records),
        "human_review": sum(row["label"] is None for row in records),
    }


@dataclass
class DuplicateReviewSession:
    """Controller state used by the GUI without exposing review metadata."""

    packet_path: Path
    labels_path: Path
    _packet: dict[str, Any]
    _labels: dict[str, Any]
    _records: list[dict[str, Any]]
    _view_indices: list[int]
    _triage: dict[str, Any] | None
    triaged: bool
    index: int

    @property
    def count(self) -> int:
        return len(self._view_indices)

    @property
    def _record_index(self) -> int:
        return self._view_indices[self.index]

    @property
    def current_label(self) -> str | None:
        return self._labels["records"][self._record_index]["label"]

    @property
    def completed_count(self) -> int:
        return sum(
            self._labels["records"][index]["label"] is not None
            for index in self._view_indices
        )

    def current_texts(self) -> tuple[str, str]:
        record = self._records[self._record_index]
        left_first = int(
            _rank(
                self._packet["packet_hash"],
                record["pair_id"],
                "duplicate-review-label-orientation-v1",
            ),
            16,
        ) % 2 == 0
        first, second = (record["left"], record["right"]) if left_first else (record["right"], record["left"])
        return first["text"], second["text"]

    def move(self, offset: int) -> None:
        self.index = min(max(self.index + offset, 0), self.count - 1)

    def label_current(self, label: str) -> None:
        if label not in LABEL_VOCABULARY:
            raise DuplicateReviewLabelsError("unsupported duplicate-review label")
        record_index = self._record_index
        previous_label = self._labels["records"][record_index]["label"]
        previous_hash = self._labels["labels_hash"]
        self._labels["records"][record_index]["label"] = label
        self._labels["labels_hash"] = _labels_digest(self._labels)
        try:
            write_labels_atomic(self.labels_path, self._labels)
        except Exception:
            self._labels["records"][record_index]["label"] = previous_label
            self._labels["labels_hash"] = previous_hash
            raise
        if self.index < self.count - 1:
            self.index += 1


def open_label_session(
    packet_path: Path | str,
    labels_path: Path | str | None = None,
    triage_path: Path | str | None = None,
    *,
    allow_empty_triage_queue: bool = False,
) -> DuplicateReviewSession:
    """Validate private inputs, create/resume text-free state, and return a controller."""

    packet_file = _private_file(packet_path, role="review packet")
    labels_file = _private_file(
        default_labels_path(packet_file) if labels_path is None else labels_path,
        role="label artifact",
    )
    if packet_file == labels_file:
        raise DuplicateReviewLabelsError("review packet and label artifact destinations must differ")
    packet = _validate_packet(_read_json(packet_file, role="review packet"))
    if labels_file.exists():
        labels = _validate_labels(_read_json(labels_file, role="label artifact"), packet)
    else:
        labels = _new_labels(packet)
        write_labels_atomic(labels_file, labels)
    ordered = _ordered_records(packet)
    view_indices = list(range(len(ordered)))
    triage = None
    if triage_path is not None:
        triage_file = _private_file(triage_path, role="model-triage artifact")
        if triage_file in {packet_file, labels_file}:
            raise DuplicateReviewLabelsError(
                "packet, labels, and model-triage destinations must differ"
            )
        triage = _validate_triage(
            _read_json(triage_file, role="model-triage artifact"), packet, labels
        )
        human_ids = {
            row["pair_id"]
            for row in triage["records"]
            if row["decision"] == "HUMAN_REVIEW"
        }
        view_indices = [
            index
            for index, row in enumerate(labels["records"])
            if row["pair_id"] in human_ids
        ]
        if not view_indices and not allow_empty_triage_queue:
            raise DuplicateReviewLabelsError("model triage left no pairs for human review")
    first_unlabeled = (
        next(
            (
                position
                for position, record_index in enumerate(view_indices)
                if labels["records"][record_index]["label"] is None
            ),
            len(view_indices) - 1,
        )
        if view_indices
        else 0
    )
    return DuplicateReviewSession(
        packet_path=packet_file,
        labels_path=labels_file,
        _packet=packet,
        _labels=labels,
        _records=ordered,
        _view_indices=view_indices,
        _triage=triage,
        triaged=triage_path is not None,
        index=first_unlabeled,
    )


__all__ = [
    "LABEL_VOCABULARY",
    "DuplicateReviewLabelsError",
    "DuplicateReviewSession",
    "TRIAGE_METHOD",
    "TRIAGE_SCHEMA",
    "apply_model_triage",
    "default_labels_path",
    "open_label_session",
    "write_labels_atomic",
]
