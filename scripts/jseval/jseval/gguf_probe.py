"""Dependency-free GGUF file-metadata prober (tempdoc 674 remaining-work slice).

Reads a GGUF file's 24-byte header and metadata key-value store -- never the tensor
data -- to answer "roughly what is this model" before it is ever loaded. Verified
against the GGUF spec (github.com/ggml-org/ggml/blob/master/docs/gguf.md) AND against
a real, current file on disk (``Qwen_Qwen3.5-9B-Q4_K_M.gguf``, 45 KV pairs, zero parse
errors) before this module was written -- see tempdoc 674 §Pre-implementation
confidence probes (remaining work), Probe A.

Deliberately stdlib-only (``struct``/``io``): the upstream ``gguf-py`` package is the
authoritative reader but is a heavier dependency than the few metadata keys this
prober needs justify adding to this project's lockfile.

**``general.size_label`` is spec-optional, not guaranteed present** -- callers must
treat a ``None`` result as "unknown," never as a proxy for "small" or "incompetent."
This module never raises on an unparseable or absent field; it fails open.
"""

from __future__ import annotations

import struct
from dataclasses import dataclass
from pathlib import Path

_MAGIC = b"GGUF"

# GGUF metadata value-type tag -> fixed byte width (STRING=8 and ARRAY=9 are variable-
# length and handled separately below).
_FIXED_VALUE_SIZES = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 1, 10: 8, 11: 8, 12: 8}

_INTEREST_KEYS = (
    "general.architecture",
    "general.name",
    "general.size_label",
    "general.quantization_version",
    "general.file_type",
)


@dataclass(frozen=True)
class GgufInfo:
    """Best-effort facts about a GGUF file, read from its header/metadata only.

    Every field is ``None`` when the underlying key was absent or unparseable --
    never guessed. ``size_bytes`` is the one field that is always known (a plain
    filesystem stat, no GGUF parsing needed) and is the most reliable signal a
    caller has when the metadata fields are missing.
    """

    size_bytes: int
    architecture: str | None
    name: str | None
    size_label: str | None


def probe_gguf(path: str) -> GgufInfo:
    """Read a GGUF file's header + metadata KV store; never its tensor data.

    Raises only if the file cannot be opened or does not start with the GGUF magic
    bytes -- both real, actionable errors a caller should see (wrong path, wrong file
    type). Any individual metadata key being absent, or the file cursor tripping over
    an as-yet-unhandled value-type tag partway through the KV walk, degrades that one
    field to ``None`` rather than aborting the whole probe -- most fields this module
    doesn't care about are irrelevant to answering "what model is this."
    """
    size_bytes = Path(path).stat().st_size
    found: dict[str, object] = {}
    with open(path, "rb") as f:
        magic, _version, _n_tensors, n_kv = struct.unpack("<4sIQQ", f.read(24))
        if magic != _MAGIC:
            raise ValueError(f"{path}: not a GGUF file (magic={magic!r}, expected {_MAGIC!r})")
        for _ in range(n_kv):
            try:
                key = _read_string(f)
                (value_type,) = struct.unpack("<I", f.read(4))
                value = _read_value(f, value_type)
            except Exception:
                # A value type this prober doesn't recognize, or a truncated file --
                # stop the walk rather than guess at the remaining byte offsets.
                break
            if key in _INTEREST_KEYS:
                found[key] = value
    return GgufInfo(
        size_bytes=size_bytes,
        architecture=_as_str(found.get("general.architecture")),
        name=_as_str(found.get("general.name")),
        size_label=_as_str(found.get("general.size_label")),
    )


def _as_str(value: object) -> str | None:
    return value if isinstance(value, str) else None


def _read_string(f) -> str:
    (n,) = struct.unpack("<Q", f.read(8))
    return f.read(n).decode("utf-8", errors="replace")


def _read_value(f, value_type: int):
    if value_type == 8:  # STRING
        return _read_string(f)
    if value_type == 9:  # ARRAY
        (elem_type,) = struct.unpack("<I", f.read(4))
        (count,) = struct.unpack("<Q", f.read(8))
        if elem_type == 8:
            return [_read_string(f) for _ in range(count)]
        size = _FIXED_VALUE_SIZES.get(elem_type)
        if size is None:
            raise ValueError(f"unhandled array element type {elem_type}")
        f.read(size * count)  # skip -- this prober has no use for array values today
        return None
    size = _FIXED_VALUE_SIZES.get(value_type)
    if size is None:
        raise ValueError(f"unhandled value type {value_type}")
    raw = f.read(size)
    if value_type == 4:
        return struct.unpack("<I", raw)[0]
    if value_type == 5:
        return struct.unpack("<i", raw)[0]
    if value_type == 6:
        return struct.unpack("<f", raw)[0]
    if value_type == 7:
        return bool(raw[0])
    if value_type == 10:
        return struct.unpack("<Q", raw)[0]
    if value_type == 11:
        return struct.unpack("<q", raw)[0]
    if value_type == 12:
        return struct.unpack("<d", raw)[0]
    return None
