"""Immutable agent-utility publication lifecycle and zero-cost replay."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import shutil
from pathlib import Path

from jseval.utility_recompose import finalize_evidence, semantic_digest

PUBLICATION_SCHEMA = "agent-utility-publication.v1"
POINTER_SCHEMA = "agent-utility-publication-pointer.v1"


def _require_exact_keys(value: dict, expected: set[str], label: str) -> None:
    unknown = set(value) - expected
    missing = expected - set(value)
    if unknown or missing:
        raise ValueError(
            f"{label} fields differ: missing={sorted(missing)} unknown={sorted(unknown)}"
        )


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _write_new(path: Path, body: str) -> None:
    if path.exists():
        raise FileExistsError(f"immutable publication artifact already exists: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")


def load_pointer(root: str | Path) -> dict:
    path = Path(root) / "current.v1.json"
    if not path.is_file():
        raise FileNotFoundError(f"publication pointer is missing: {path}")
    pointer = json.loads(path.read_text(encoding="utf-8"))
    _require_exact_keys(
        pointer,
        {"schema", "schema_version", "current", "previous", "reason", "selected_at"},
        "publication pointer",
    )
    for field in ("current", "previous"):
        if pointer.get(field) is not None:
            _require_exact_keys(
                pointer[field],
                {"publication_id", "path", "manifest_sha256"},
                f"publication pointer {field}",
            )
    if pointer.get("schema") != POINTER_SCHEMA or "current" not in pointer:
        raise ValueError("publication pointer is malformed")
    return pointer


def build_publication(
    *,
    root: str | Path,
    record_path: str | Path,
    evidence_path: str | Path,
    publication_id: str,
    created_at: str | None = None,
) -> Path:
    """Create one immutable bundle; accepted and rejected outcomes are both durable."""
    root = Path(root)
    pointer = load_pointer(root)
    record_source = Path(record_path)
    evidence_source = Path(evidence_path)
    record = json.loads(record_source.read_text(encoding="utf-8"))
    if record.get("semantic_digest") != semantic_digest(record):
        raise ValueError("record semantic_digest is missing or incorrect")
    recomposed = finalize_evidence(
        [evidence_source],
        composed_at=record.get("composed_at"),
        contamination_class=(record.get("coverage") or {}).get("contamination_class", "unknown"),
        confidence_tier=record.get("confidence_tier", "C"),
    )
    if semantic_digest(recomposed) != semantic_digest(record):
        raise ValueError("record does not semantically match the supplied evidence")
    verdict = record.get("claim_verdict") or {}
    state = "accepted" if verdict.get("accepted") is True else "rejected"

    record_id = record["semantic_digest"]
    record_dir = root.parent / "agent-utility-records" / record_id
    canonical_record = record_dir / record_source.name
    if record_dir.exists():
        if not canonical_record.is_file() or _sha256(canonical_record) != _sha256(record_source):
            raise FileExistsError(f"record id collision or attempted mutation: {record_dir}")
    else:
        record_dir.mkdir(parents=True)
        shutil.copyfile(record_source, canonical_record)

    publication_dir = root / "publications" / publication_id
    if publication_dir.exists():
        raise FileExistsError(f"publication id already exists: {publication_id}")
    publication_dir.mkdir(parents=True)
    copied_evidence = publication_dir / "observations.v1.jsonl"
    shutil.copyfile(evidence_source, copied_evidence)
    record_relative = Path("..") / ".." / ".." / "agent-utility-records" / record_id / canonical_record.name
    manifest = {
        "schema": PUBLICATION_SCHEMA,
        "schema_version": 1,
        "publication_id": publication_id,
        "created_at": created_at or dt.datetime.now(dt.timezone.utc).isoformat(),
        "lifecycle_state": state,
        "record": {
            "path": record_relative.as_posix(),
            "sha256": _sha256(canonical_record),
            "semantic_digest": record_id,
        },
        "observations": {
            "path": "observations.v1.jsonl",
            "sha256": _sha256(copied_evidence),
        },
        "policy": {
            "id": verdict.get("policy_id"),
            "sha256": verdict.get("policy_hash"),
            "status": verdict.get("policy_status"),
        },
        "sanitizer_version": "agent-utility-observation.v1",
        "replay_command": f"python -m jseval utility-replay --publication {publication_id}",
        "supersedes": ((pointer.get("current") or {}).get("publication_id")),
    }
    manifest_path = publication_dir / "publication.v1.json"
    _write_new(manifest_path, json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
    replay_publication(manifest_path)
    return manifest_path


def replay_publication(publication: str | Path) -> dict:
    manifest_path = Path(publication)
    if manifest_path.is_dir():
        manifest_path = manifest_path / "publication.v1.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    _require_exact_keys(
        manifest,
        {
            "schema", "schema_version", "publication_id", "created_at", "lifecycle_state",
            "record", "observations", "policy", "sanitizer_version", "replay_command",
            "supersedes",
        },
        "publication manifest",
    )
    _require_exact_keys(
        manifest.get("record") or {}, {"path", "sha256", "semantic_digest"}, "manifest record"
    )
    _require_exact_keys(
        manifest.get("observations") or {}, {"path", "sha256"}, "manifest observations"
    )
    _require_exact_keys(manifest.get("policy") or {}, {"id", "sha256", "status"}, "manifest policy")
    if manifest.get("schema") != PUBLICATION_SCHEMA:
        raise ValueError("unsupported publication schema")
    record_path = (manifest_path.parent / manifest["record"]["path"]).resolve()
    evidence_path = (manifest_path.parent / manifest["observations"]["path"]).resolve()
    if _sha256(record_path) != manifest["record"]["sha256"]:
        raise ValueError("canonical record byte hash mismatch")
    if _sha256(evidence_path) != manifest["observations"]["sha256"]:
        raise ValueError("observation evidence byte hash mismatch")
    stored = json.loads(record_path.read_text(encoding="utf-8"))
    expected_state = (
        "accepted" if (stored.get("claim_verdict") or {}).get("accepted") is True else "rejected"
    )
    if manifest.get("lifecycle_state") != expected_state:
        raise ValueError("publication lifecycle state disagrees with the canonical claim verdict")
    stored_verdict = stored.get("claim_verdict") or {}
    if manifest.get("policy") != {
        "id": stored_verdict.get("policy_id"),
        "sha256": stored_verdict.get("policy_hash"),
        "status": stored_verdict.get("policy_status"),
    }:
        raise ValueError("publication policy identity disagrees with the canonical claim verdict")
    contamination = (stored.get("coverage") or {}).get("contamination_class", "unknown")
    recomposed = finalize_evidence(
        [evidence_path],
        composed_at=stored.get("composed_at"),
        contamination_class=contamination,
        confidence_tier=stored.get("confidence_tier", "C"),
    )
    if semantic_digest(recomposed) != semantic_digest(stored):
        raise ValueError("recomposed semantic record differs from canonical record")
    if recomposed["semantic_digest"] != manifest["record"]["semantic_digest"]:
        raise ValueError("semantic digest mismatch")
    return {
        "ok": True,
        "publication_id": manifest["publication_id"],
        "lifecycle_state": manifest["lifecycle_state"],
        "semantic_digest": recomposed["semantic_digest"],
    }


def select_publication(
    *,
    root: str | Path,
    publication_id: str | None = None,
    clear: bool = False,
    reason: str,
    selected_at: str | None = None,
) -> Path:
    root = Path(root)
    pointer_path = root / "current.v1.json"
    pointer = load_pointer(root)
    prior = pointer.get("current")
    if clear:
        current = None
    else:
        if not publication_id:
            raise ValueError("publication_id is required unless --clear is used")
        manifest_path = root / "publications" / publication_id / "publication.v1.json"
        replay_publication(manifest_path)
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("lifecycle_state") != "accepted":
            raise ValueError("only an accepted publication can become current")
        current = {
            "publication_id": publication_id,
            "path": f"publications/{publication_id}/publication.v1.json",
            "manifest_sha256": _sha256(manifest_path),
        }
    updated = {
        "schema": POINTER_SCHEMA,
        "schema_version": 1,
        "current": current,
        "previous": prior,
        "reason": reason,
        "selected_at": selected_at or dt.datetime.now(dt.timezone.utc).isoformat(),
    }
    pointer_path.write_text(json.dumps(updated, indent=2) + "\n", encoding="utf-8")
    return pointer_path
