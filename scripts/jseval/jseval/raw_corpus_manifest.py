"""Strict, content-addressed identity for raw-file corpora.

Unlike :mod:`jseval.corpus_identity`, this module is deliberately fail-closed.
It binds every file to its canonical relative path, byte size, and SHA-256 and
rejects filesystem or manifest ambiguity instead of returning a best-effort
answer.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import unicodedata
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping


RAW_CORPUS_MANIFEST_SCHEMA = "jseval.raw-corpus-manifest.v1"
_MANIFEST_FIELDS = frozenset({"schema", "files"})
_FILE_FIELDS = frozenset({"path", "size_bytes", "sha256"})
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_WINDOWS_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_HASH_CHUNK_BYTES = 1024 * 1024
_RAW_ADMISSION_ENV_VARS = (
    "JUSTSEARCH_INGESTION_SKIP_PATTERNS",
    "JUSTSEARCH_INGESTION_SKIP_EXTENSIONS",
    "JUSTSEARCH_INGESTION_SKIP_DIRECTORY_NAMES",
)


class RawCorpusManifestError(ValueError):
    """The raw corpus or its claimed manifest is not a strict identity."""


class _FrozenDict(dict[str, Any]):
    """A JSON-serializable dict that rejects mutation through normal APIs."""

    @staticmethod
    def _immutable(*_args: object, **_kwargs: object) -> None:
        raise TypeError("raw corpus manifests are immutable")

    __setitem__ = _immutable
    __delitem__ = _immutable
    clear = _immutable
    pop = _immutable
    popitem = _immutable
    setdefault = _immutable
    update = _immutable
    __ior__ = _immutable


class _FrozenList(list[Any]):
    """A JSON-serializable list that rejects mutation through normal APIs."""

    @staticmethod
    def _immutable(*_args: object, **_kwargs: object) -> None:
        raise TypeError("raw corpus manifests are immutable")

    __setitem__ = _immutable
    __delitem__ = _immutable
    append = _immutable
    clear = _immutable
    extend = _immutable
    insert = _immutable
    pop = _immutable
    remove = _immutable
    reverse = _immutable
    sort = _immutable
    __iadd__ = _immutable
    __imul__ = _immutable


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return _FrozenDict({key: _freeze(item) for key, item in value.items()})
    if isinstance(value, list):
        return _FrozenList(_freeze(item) for item in value)
    return value


@dataclass(frozen=True)
class RawCorpusIdentity:
    """Immutable strict manifest and its aggregate identity."""

    manifest: Mapping[str, Any]
    digest: str
    file_count: int
    total_bytes: int

    def __post_init__(self) -> None:
        object.__setattr__(self, "manifest", _freeze(dict(self.manifest)))

    def to_corpus_identity(self) -> dict[str, Any]:
        """Public run/cache identity derived from this exact manifest."""

        return {
            "profile_id": None,
            "signature": self.digest,
            "kind": "raw-files",
            "schema": RAW_CORPUS_MANIFEST_SCHEMA,
            "file_count": self.file_count,
            "total_bytes": self.total_bytes,
        }


@dataclass(frozen=True)
class RawCorpusContext:
    """One resolved raw corpus root and the strict identity threaded through a run."""

    dataset_name: str
    root: Path
    identity: RawCorpusIdentity
    manifest_path: Path | None = None
    manifest_pointer: str | None = None
    admission_policy: Mapping[str, str] = field(
        default_factory=lambda: {
            name: "default" for name in _RAW_ADMISSION_ENV_VARS
        }
    )

    def __post_init__(self) -> None:
        object.__setattr__(self, "admission_policy", _freeze(dict(self.admission_policy)))

    def to_corpus_identity(self) -> dict[str, Any]:
        identity = self.identity.to_corpus_identity()
        identity["manifest_pointer"] = self.manifest_pointer
        identity["admission_policy"] = dict(self.admission_policy)
        return identity


def _canonical_bytes(payload: Mapping[str, Any]) -> bytes:
    return json.dumps(
        payload,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
        allow_nan=False,
    ).encode("utf-8")


def _identity(payload: dict[str, Any]) -> RawCorpusIdentity:
    files = payload["files"]
    return RawCorpusIdentity(
        manifest=payload,
        digest=hashlib.sha256(_canonical_bytes(payload)).hexdigest(),
        file_count=len(files),
        total_bytes=sum(row["size_bytes"] for row in files),
    )


def _is_reparse_point(file_stat: os.stat_result) -> bool:
    reparse_flag = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    attributes = getattr(file_stat, "st_file_attributes", 0)
    return bool(reparse_flag and attributes & reparse_flag)


def _checked_root(root: Path | str) -> Path:
    candidate = Path(root)
    try:
        root_stat = candidate.lstat()
    except OSError as exc:
        raise RawCorpusManifestError(f"raw corpus root is unavailable: {candidate}") from exc
    if stat.S_ISLNK(root_stat.st_mode) or _is_reparse_point(root_stat):
        raise RawCorpusManifestError("raw corpus root must not be a symlink or reparse point")
    if not stat.S_ISDIR(root_stat.st_mode):
        raise RawCorpusManifestError("raw corpus root must be a directory")
    try:
        return candidate.resolve(strict=True)
    except OSError as exc:
        raise RawCorpusManifestError(f"raw corpus root cannot be resolved: {candidate}") from exc


def _validate_relative_path(value: object) -> str:
    if not isinstance(value, str):
        raise RawCorpusManifestError("manifest file path must be a string")
    if not value or "\x00" in value:
        raise RawCorpusManifestError("manifest file path must be non-empty and contain no NUL")
    if "\\" in value:
        raise RawCorpusManifestError("manifest file path must use POSIX separators")
    if value.startswith("/") or value.startswith("//") or _WINDOWS_DRIVE_RE.match(value):
        raise RawCorpusManifestError("manifest file path must be relative")
    parts = value.split("/")
    if any(part in {"", ".", ".."} for part in parts):
        raise RawCorpusManifestError("manifest file path contains an empty, dot, or dot-dot component")
    if unicodedata.normalize("NFC", value) != value:
        raise RawCorpusManifestError("manifest file path must be exact NFC")
    try:
        value.encode("utf-8")
    except UnicodeEncodeError as exc:
        raise RawCorpusManifestError("manifest file path must be valid UTF-8") from exc
    return value


def _validate_rows(files: object) -> list[dict[str, Any]]:
    if not isinstance(files, list):
        raise RawCorpusManifestError("manifest files must be a list")
    if not files:
        raise RawCorpusManifestError("raw corpus manifest must contain at least one file")

    normalized: list[dict[str, Any]] = []
    seen_paths: set[str] = set()
    seen_folded: dict[str, str] = {}
    for index, raw_row in enumerate(files):
        if not isinstance(raw_row, Mapping):
            raise RawCorpusManifestError(f"manifest file row {index} must be an object")
        if set(raw_row) != _FILE_FIELDS:
            raise RawCorpusManifestError(
                f"manifest file row {index} must contain exactly {sorted(_FILE_FIELDS)}"
            )
        path = _validate_relative_path(raw_row["path"])
        size_bytes = raw_row["size_bytes"]
        sha256 = raw_row["sha256"]
        if isinstance(size_bytes, bool) or not isinstance(size_bytes, int) or size_bytes < 0:
            raise RawCorpusManifestError("manifest size_bytes must be a non-negative integer")
        if not isinstance(sha256, str) or not _SHA256_RE.fullmatch(sha256):
            raise RawCorpusManifestError("manifest sha256 must be 64 lowercase hexadecimal characters")
        if path in seen_paths:
            raise RawCorpusManifestError(f"duplicate manifest path: {path}")
        folded = path.casefold()
        if folded in seen_folded:
            raise RawCorpusManifestError(
                f"case-folding manifest path collision: {seen_folded[folded]} and {path}"
            )
        seen_paths.add(path)
        seen_folded[folded] = path
        normalized.append({"path": path, "size_bytes": size_bytes, "sha256": sha256})

    paths = [row["path"] for row in normalized]
    if paths != sorted(paths, key=lambda path: path.encode("utf-8")):
        raise RawCorpusManifestError("manifest file rows must be strictly UTF-8 path sorted")
    return normalized


def _normalize_manifest(manifest: object) -> dict[str, Any]:
    if not isinstance(manifest, Mapping):
        raise RawCorpusManifestError("raw corpus manifest must be an object")
    if set(manifest) != _MANIFEST_FIELDS:
        raise RawCorpusManifestError(
            f"raw corpus manifest must contain exactly {sorted(_MANIFEST_FIELDS)}"
        )
    if manifest["schema"] != RAW_CORPUS_MANIFEST_SCHEMA or not isinstance(
        manifest["schema"], str
    ):
        raise RawCorpusManifestError(
            f"manifest schema must be {RAW_CORPUS_MANIFEST_SCHEMA!r}"
        )
    return {
        "schema": RAW_CORPUS_MANIFEST_SCHEMA,
        "files": _validate_rows(manifest["files"]),
    }


def _within_root(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


def _collect_files(root: Path) -> dict[str, Path]:
    files: dict[str, Path] = {}

    def visit(directory: Path) -> None:
        try:
            entries = list(os.scandir(directory))
        except OSError as exc:
            raise RawCorpusManifestError(f"cannot enumerate raw corpus directory: {directory}") from exc
        for entry in entries:
            path = Path(entry.path)
            try:
                entry_stat = entry.stat(follow_symlinks=False)
            except OSError as exc:
                raise RawCorpusManifestError(f"cannot stat raw corpus entry: {path}") from exc
            if stat.S_ISLNK(entry_stat.st_mode) or _is_reparse_point(entry_stat):
                raise RawCorpusManifestError(f"raw corpus entry is a symlink or reparse point: {path}")
            try:
                resolved = path.resolve(strict=True)
            except OSError as exc:
                raise RawCorpusManifestError(f"raw corpus entry cannot be resolved: {path}") from exc
            if not _within_root(resolved, root):
                raise RawCorpusManifestError(f"raw corpus entry escapes its root: {path}")
            if stat.S_ISDIR(entry_stat.st_mode):
                visit(path)
            elif stat.S_ISREG(entry_stat.st_mode):
                try:
                    relative = path.relative_to(root).as_posix()
                except ValueError as exc:
                    raise RawCorpusManifestError(f"raw corpus entry escapes its root: {path}") from exc
                relative = _validate_relative_path(relative)
                if relative in files:
                    raise RawCorpusManifestError(f"duplicate raw corpus path: {relative}")
                files[relative] = path
            else:
                raise RawCorpusManifestError(f"raw corpus entry is not a regular file: {path}")

    visit(root)
    if not files:
        raise RawCorpusManifestError("raw corpus root must contain at least one regular file")
    # Apply the same portability collision rule to builder-produced manifests.
    folded: dict[str, str] = {}
    for relative in files:
        key = relative.casefold()
        if key in folded:
            raise RawCorpusManifestError(
                f"case-folding raw corpus path collision: {folded[key]} and {relative}"
            )
        folded[key] = relative
    return files


def _stat_identity(file_stat: os.stat_result) -> tuple[int, int, int, int]:
    return (
        file_stat.st_size,
        file_stat.st_mtime_ns,
        file_stat.st_dev,
        file_stat.st_ino,
    )


def _hash_stable_file(
    path: Path, root: Path
) -> tuple[int, str, tuple[int, int, int, int]]:
    try:
        before = path.lstat()
        if not stat.S_ISREG(before.st_mode) or _is_reparse_point(before):
            raise RawCorpusManifestError(f"raw corpus path is not a regular file: {path}")
        resolved = path.resolve(strict=True)
        if not _within_root(resolved, root):
            raise RawCorpusManifestError(f"raw corpus path escapes its root: {path}")
        digest = hashlib.sha256()
        with path.open("rb") as stream:
            opened = os.fstat(stream.fileno())
            if _stat_identity(before) != _stat_identity(opened):
                raise RawCorpusManifestError(f"raw corpus file changed before hashing: {path}")
            while chunk := stream.read(_HASH_CHUNK_BYTES):
                digest.update(chunk)
            after_open = os.fstat(stream.fileno())
        after_path = path.lstat()
    except RawCorpusManifestError:
        raise
    except OSError as exc:
        raise RawCorpusManifestError(f"cannot hash raw corpus file: {path}") from exc
    if (
        _stat_identity(before) != _stat_identity(after_open)
        or _stat_identity(before) != _stat_identity(after_path)
        or not stat.S_ISREG(after_path.st_mode)
        or _is_reparse_point(after_path)
    ):
        raise RawCorpusManifestError(f"raw corpus file changed while hashing: {path}")
    return before.st_size, digest.hexdigest(), _stat_identity(before)


def _assert_unchanged_stats(
    files: Mapping[str, Path], expected: Mapping[str, tuple[int, int, int, int]]
) -> None:
    for relative, path in files.items():
        try:
            current = path.lstat()
        except OSError as exc:
            raise RawCorpusManifestError(f"cannot restat raw corpus file: {path}") from exc
        if _stat_identity(current) != expected[relative]:
            raise RawCorpusManifestError(f"raw corpus file changed after hashing: {relative}")


def _manifest_from_root(root: Path) -> dict[str, Any]:
    initial = _collect_files(root)
    rows: list[dict[str, Any]] = []
    stable_stats: dict[str, tuple[int, int, int, int]] = {}
    for relative in sorted(initial, key=lambda path: path.encode("utf-8")):
        size_bytes, sha256, file_stat = _hash_stable_file(initial[relative], root)
        rows.append({"path": relative, "size_bytes": size_bytes, "sha256": sha256})
        stable_stats[relative] = file_stat
    final = _collect_files(root)
    if set(initial) != set(final):
        raise RawCorpusManifestError("raw corpus file set changed while hashing")
    _assert_unchanged_stats(final, stable_stats)
    return {"schema": RAW_CORPUS_MANIFEST_SCHEMA, "files": rows}


def build_raw_manifest(root: Path | str) -> RawCorpusIdentity:
    """Build a strict identity over every regular file below ``root``."""

    checked_root = _checked_root(root)
    return _identity(_manifest_from_root(checked_root))


def validate_raw_manifest(root: Path | str, manifest: object) -> RawCorpusIdentity:
    """Validate ``manifest`` against the complete, current contents of ``root``."""

    payload = _normalize_manifest(manifest)
    checked_root = _checked_root(root)
    actual_files = _collect_files(checked_root)
    expected_paths = {row["path"] for row in payload["files"]}
    actual_paths = set(actual_files)
    if expected_paths != actual_paths:
        missing = sorted(expected_paths - actual_paths)
        extra = sorted(actual_paths - expected_paths)
        raise RawCorpusManifestError(
            f"raw corpus file set mismatch (missing={missing!r}, extra={extra!r})"
        )
    stable_stats: dict[str, tuple[int, int, int, int]] = {}
    for row in payload["files"]:
        size_bytes, sha256, file_stat = _hash_stable_file(
            actual_files[row["path"]], checked_root
        )
        if size_bytes != row["size_bytes"]:
            raise RawCorpusManifestError(f"raw corpus size mismatch: {row['path']}")
        if sha256 != row["sha256"]:
            raise RawCorpusManifestError(f"raw corpus SHA-256 mismatch: {row['path']}")
        stable_stats[row["path"]] = file_stat
    final_files = _collect_files(checked_root)
    if actual_paths != set(final_files):
        raise RawCorpusManifestError("raw corpus file set changed during validation")
    _assert_unchanged_stats(final_files, stable_stats)
    return _identity(payload)


def project_legacy_raw_manifest(manifest: object) -> dict[str, Any]:
    """Project a realdocs-shaped legacy manifest into the strict raw schema.

    Legacy top-level and row metadata is intentionally ignored. Only ``file``,
    ``size_bytes``, and ``sha256`` cross this explicit compatibility boundary.
    The projection is path-sorted and then subjected to all strict structural
    and portability checks; filesystem validation remains a separate step.
    """

    if not isinstance(manifest, Mapping):
        raise RawCorpusManifestError("legacy raw corpus manifest must be an object")
    source_files = manifest.get("files")
    if not isinstance(source_files, list):
        raise RawCorpusManifestError("legacy raw corpus manifest files must be a list")
    projected_rows: list[dict[str, Any]] = []
    for index, row in enumerate(source_files):
        if not isinstance(row, Mapping):
            raise RawCorpusManifestError(f"legacy manifest file row {index} must be an object")
        missing = {"file", "size_bytes", "sha256"} - set(row)
        if missing:
            raise RawCorpusManifestError(
                f"legacy manifest file row {index} is missing {sorted(missing)}"
            )
        path = _validate_relative_path(row["file"])
        projected_rows.append(
            {"path": path, "size_bytes": row["size_bytes"], "sha256": row["sha256"]}
        )
    projected_rows.sort(key=lambda row: row["path"].encode("utf-8"))
    return _normalize_manifest(
        {"schema": RAW_CORPUS_MANIFEST_SCHEMA, "files": projected_rows}
    )


def _effective_signature_override(env_overrides: Mapping[str, str] | None) -> str | None:
    if env_overrides is not None and "JUSTSEARCH_CORPUS_SIGNATURE" in env_overrides:
        return env_overrides["JUSTSEARCH_CORPUS_SIGNATURE"]
    return os.environ.get("JUSTSEARCH_CORPUS_SIGNATURE")


def _effective_admission_policy(
    env_overrides: Mapping[str, str] | None,
) -> dict[str, str]:
    """Canonicalize the Worker's three operator-overridable admission sets.

    Unset and blank values both select Worker defaults. Non-blank CSV values
    are case-insensitive sets in the Worker, so order, duplicates, and spacing
    are normalized before entering cache or run identity.
    """

    policy: dict[str, str] = {}
    for name in _RAW_ADMISSION_ENV_VARS:
        raw = (
            env_overrides[name]
            if env_overrides is not None and name in env_overrides
            else os.environ.get(name)
        )
        tokens = sorted({part.strip().lower() for part in (raw or "").split(",") if part.strip()})
        policy[name] = ",".join(tokens) if tokens else "default"
    return policy


def effective_admission_policy(
    env_overrides: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """Return the canonical Worker admission policy for an external raw-root adapter."""

    return _effective_admission_policy(env_overrides)


def _same_path(left: Path, right: Path) -> bool:
    """Platform-aware equality without accepting a different explicit raw root."""

    return os.path.normcase(os.path.abspath(left)) == os.path.normcase(os.path.abspath(right))


def resolve_raw_corpus_context(
    dataset_name: str | None,
    explicit_dir: Path | str | None = None,
    *,
    base_dir: Path | None = None,
    repo_root: Path | None = None,
    env_overrides: Mapping[str, str] | None = None,
) -> RawCorpusContext | None:
    """Resolve a local ``raw_files`` dataset once, or return ``None`` for legacy data.

    A declared manifest is authoritative and is validated against the complete
    current raw root. The committed realdocs manifests predate the strict schema;
    their ``file`` rows cross one explicit compatibility projection.
    """

    if not dataset_name or not dataset_name.startswith(("golden/", "mixed/")):
        return None
    if base_dir is None:
        from .corpora import _default_base_dir

        base_dir = _default_base_dir()
    dataset_root = Path(base_dir) / dataset_name
    metadata_path = dataset_root / "metadata.json"
    if not metadata_path.is_file():
        return None
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        # Preserve legacy behavior for ordinary local corpora. A malformed file
        # cannot truthfully declare itself raw, so it remains on the old path.
        return None
    if not isinstance(metadata, Mapping) or not metadata.get("raw_files"):
        return None

    raw_root = dataset_root / "corpus-dir"
    if explicit_dir is not None and not _same_path(Path(explicit_dir), raw_root):
        raise RawCorpusManifestError(
            f"raw dataset {dataset_name} requires its declared corpus-dir {raw_root}; "
            f"explicit --corpus-dir was {explicit_dir}"
        )

    manifest_path: Path | None = None
    manifest_pointer: str | None = None
    if "manifest" in metadata:
        pointer = metadata["manifest"]
        if not isinstance(pointer, str) or not pointer.strip():
            raise RawCorpusManifestError("raw dataset metadata manifest pointer must be non-empty")
        if "\\" in pointer or Path(pointer).is_absolute():
            raise RawCorpusManifestError(
                "raw dataset metadata manifest pointer must be a repo-relative POSIX path"
            )
        pointer_parts = pointer.split("/")
        if any(part in {"", ".", ".."} for part in pointer_parts):
            raise RawCorpusManifestError(
                "raw dataset metadata manifest pointer must not escape its repo root"
            )
        if repo_root is None:
            from ._paths import REPO_ROOT

            repo_root = REPO_ROOT
        checked_repo_root = Path(repo_root).resolve(strict=True)
        manifest_path = (checked_repo_root / Path(*pointer_parts)).resolve(strict=False)
        if not _within_root(manifest_path, checked_repo_root):
            raise RawCorpusManifestError(
                "raw dataset metadata manifest pointer must not escape its repo root"
            )
        manifest_pointer = pointer
        try:
            declared = json.loads(manifest_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise RawCorpusManifestError(
                f"raw dataset manifest pointer is missing or invalid: {manifest_path}"
            ) from exc
        if isinstance(declared, Mapping) and declared.get("schema") == RAW_CORPUS_MANIFEST_SCHEMA:
            identity = validate_raw_manifest(raw_root, declared)
        else:
            identity = validate_raw_manifest(raw_root, project_legacy_raw_manifest(declared))
    else:
        identity = build_raw_manifest(raw_root)

    override = _effective_signature_override(env_overrides)
    if override is not None and override != identity.digest:
        raise RawCorpusManifestError(
            "JUSTSEARCH_CORPUS_SIGNATURE disagrees with strict raw corpus digest "
            f"({override!r} != {identity.digest!r})"
        )
    return RawCorpusContext(
        dataset_name,
        _checked_root(raw_root),
        identity,
        manifest_path,
        manifest_pointer,
        _effective_admission_policy(env_overrides),
    )


def validate_raw_corpus_context(
    context: RawCorpusContext,
    env_overrides: Mapping[str, str] | None = None,
    *,
    expected_dataset: str | None = None,
    explicit_dir: Path | str | None = None,
) -> RawCorpusContext:
    """Refuse filesystem drift while preserving the one context object."""

    if expected_dataset is not None and context.dataset_name != expected_dataset:
        raise RawCorpusManifestError(
            f"raw corpus context dataset {context.dataset_name!r} does not match "
            f"requested dataset {expected_dataset!r}"
        )
    if explicit_dir is not None and not _same_path(Path(explicit_dir), context.root):
        raise RawCorpusManifestError(
            f"raw corpus context root {context.root} does not match explicit "
            f"--corpus-dir {explicit_dir}"
        )

    current = validate_raw_manifest(context.root, context.identity.manifest)
    if current != context.identity:
        raise RawCorpusManifestError("raw corpus identity changed after initial resolution")
    override = _effective_signature_override(env_overrides)
    if override is not None and override != context.identity.digest:
        raise RawCorpusManifestError(
            "JUSTSEARCH_CORPUS_SIGNATURE disagrees with strict raw corpus digest "
            f"({override!r} != {context.identity.digest!r})"
        )
    effective_policy = _effective_admission_policy(env_overrides)
    if dict(context.admission_policy) != effective_policy:
        raise RawCorpusManifestError(
            "raw corpus admission policy changed after initial resolution"
        )
    return context
