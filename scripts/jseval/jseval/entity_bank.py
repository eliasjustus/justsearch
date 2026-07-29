"""Frozen entity-bank loading, validation, sampling and minting (tempdoc 767 §I.2).

ARCHITECTURAL BOUNDARY (load-bearing — do not erode)
----------------------------------------------------
This module is the **build-path half** of the entity bank. It loads, validates and
samples a *frozen, committed* ``entity-bank.v2.json`` and mints fabricated entities
and gold values from it. It MUST NOT import :mod:`jseval.entity_harvest` — harvesting
is offline-only, and a build path that could re-harvest could silently produce
different output from a different corpus snapshot, which would put every regex,
every gazetteer entry and every host-corpus byte inside the cross-interpreter
determinism surface that :func:`jseval.corpus_generate.regenerate_and_diff` proves.

The dependency direction is therefore ``entity_harvest -> entity_bank``, never the
reverse: the harvester reuses the schema constants and quality filters defined here
so the two halves cannot drift.

Determinism contract
--------------------
This project never sets ``PYTHONHASHSEED``. Therefore:

- ``hash()`` is never used; ``hashlib.sha256`` only.
- ``set`` iteration order never reaches output and never feeds an RNG. Sets are used
  for *membership tests only*; every randomized draw indexes a ``sorted()`` list.
- The caller owns the single seeded ``random.Random``; nothing here constructs one.

Minting
-------
Bank surfaces are **real** entities from a host corpus. They are used for shape,
type and length matching, and as a collision blacklist — they are never copied into
a generated corpus. :class:`Minter` splices fragments of two length-matched real
exemplars into a *fabricated* surface that reads native, then rejects it unless it is
absent from the bank, absent from everything minted so far, digit-free, and inside
the length band.

What the committed bank carries — and why it is not the whole harvest
--------------------------------------------------------------------
The build path consumes exactly three things (tempdoc 767 §O): a pool of real
*exemplar surfaces* per mintable type to splice from, the real *length distribution*
of each mintable type to draw targets from, and an exact *membership test* against
every real surface. It never reads a surface it does not splice, and it never reads
``df``. A bank that stores every harvested surface therefore stores ~10x what it is
read for — 45 MB across the two 707 members, which would make the entity bank the
largest object class in the repository (tempdoc 741).

``entity-bank.v2`` stores those three things and nothing else:

``exemplars``
    Up to :data:`EXEMPLARS_PER_LENGTH` real surfaces per (type, length), stride-selected
    across the sorted surface list so the kept set spans the alphabet rather than
    clustering at "A". Only the mintable types — no other type is ever spliced.
``length_weights``
    The **full, uncapped** per-length unique-surface counts. Every length draw is made
    against these, so capping the exemplar list changes no length statistic at all:
    the target-length and exemplar-length distributions are identical to those of the
    untrimmed bank by construction, not approximately.
``collision_index``
    Truncated digests of the normalized form of **every** real surface, all types.
    See "Collision exactness" below.

Given up, precisely: *which particular* real surface a splice fragment comes from, once
more than :data:`EXEMPLARS_PER_LENGTH` real surfaces share a length. Nothing
distributional, and nothing about collision behaviour.

Collision exactness
-------------------
The membership test is stored as truncated hashes rather than surfaces. This keeps
exclusion **exact in the only direction that can plant a defect**:

- *False negatives are impossible.* Equal strings have equal digests, so a candidate
  that collides with a real surface is always rejected. This holds with probability 1;
  it is not a bound.
- *False positives are possible and harmless.* Two distinct strings can share a
  truncated digest with probability ``N / 2**COLLISION_BITS`` per query — 5.9e-6 for the
  largest committed bank (N = 404,624 at 36 bits). The consequence is that one
  candidate out of ~170,000 is re-drawn although it was in fact free. It can never
  admit a colliding surface.

So the artifact trades a ~1e-5 chance of a wasted re-draw for a 6x smaller membership
structure, and the safety property is unconditional.
"""

from __future__ import annotations

import bisect
import hashlib
import json
import unicodedata
from collections.abc import Iterable
from pathlib import Path

# --------------------------------------------------------------------------------------
# Versioning / schema ids (shared with the offline harvester)
# --------------------------------------------------------------------------------------

BANK_SCHEMA = "entity-bank.v2"
COMMITMENT_SCHEMA = "entity-bank-commitment.v1"

BANK_FILENAME = "entity-bank.v2.json"
COMMITMENT_FILENAME = "commitment.v1.json"

#: The offline analysis dump: the entire harvest, every surface with its ``df``. Not
#: loadable by the build path — :func:`validate_entity_bank` rejects its schema — so an
#: analysis artifact can never be mistaken for a committed generation input.
FULL_HARVEST_SCHEMA = "entity-bank-full.v1"
FULL_HARVEST_FILENAME = "entity-bank.full.v1.json"

ENTITY_TYPES: tuple[str, ...] = (
    "PER", "ORG", "LOC", "CASE", "CITATION", "DOCKET", "EMAIL", "DATE", "CARDINAL",
)

# Host-corpus domains a bank may be harvested from. `legal`/`email` each add their own
# domain-specific extraction patterns in `entity_harvest._candidates`; `wiki`
# (tempdoc 748) deliberately adds NONE — encyclopedic prose (MIRACL/Wikipedia) has no
# citation/docket/header apparatus to key on, so the generic capitalized-run +
# date/cardinal/email extractors are the whole harvest. It exists as its own value rather
# than reusing `legal` because `host.domain` is committed provenance: labelling a German
# Wikipedia bank "legal" would misdescribe the artifact for every later reader.
DOMAINS: tuple[str, ...] = ("legal", "email", "wiki")

# --------------------------------------------------------------------------------------
# Quality-filter parameters
# --------------------------------------------------------------------------------------

MIN_LENGTH = 2
MAX_LENGTH = 100
MIN_ALPHA_CHARS = 3

#: The min-alpha filter is applied ONLY to the alphabetic types. Applying it globally
#: (as a naive reading of the recipe suggests) would delete every CITATION
#: ("477 U.S. 242, 248 (1986)" has 2 alpha chars), every DOCKET, every DATE in numeric
#: form and every CARDINAL — i.e. it would silently empty four of the nine types. For
#: the structural types the anchored regex IS the quality filter.
ALPHA_FILTERED_TYPES: tuple[str, ...] = ("CASE", "LOC", "ORG", "PER")

# --------------------------------------------------------------------------------------
# Length-band matching
# --------------------------------------------------------------------------------------

LENGTH_BAND_MIN_RATIO = 0.3
LENGTH_BAND_MAX_RATIO = 3.0
MAX_RESAMPLES = 5

#: The types a fabricated chain entity is minted from. Restricted to the alphabetic
#: name-like types: a chain entity is rendered inline in a sentence ("The X was designed
#: by Y"), as a document title and as a document ``_id``, so a CITATION / DOCKET / DATE /
#: CARDINAL surface would not read as a subject there. Ordered — the minter cycles this
#: tuple by entity index, so the type mix of a corpus is deterministic and reproducible.
MINTABLE_TYPES: tuple[str, ...] = ("PER", "ORG", "LOC")


def bank_mintable_types(bank: dict) -> tuple[str, ...]:
    """The types :class:`Minter` may draw from for THIS bank, in mint-cycle order.

    Defaults to :data:`MINTABLE_TYPES`. A bank may narrow it via
    ``parameters.mintable_types`` (tempdoc 748): the harvester's classifier is
    English-lexicon, so on a German host corpus it recovers ~2k PER surfaces but only a
    couple of dozen ORG/LOC — and those ORG/LOC surfaces are themselves ENGLISH
    ("American Film Institute", "Los Angeles"), because they entered German Wikipedia as
    foreign proper names. Minting from them would splice English-shaped names into German
    host documents: a language-of-origin signal separating gold from native, i.e. a new
    leak of exactly the class this rebuild removes. Narrowing is a per-bank fact, so it is
    recorded in the committed bank rather than mutating this module-level constant.
    """
    declared = ((bank.get("parameters") or {}).get("mintable_types")) or None
    if not declared:
        return MINTABLE_TYPES
    return tuple(t for t in MINTABLE_TYPES if t in set(declared))

#: Attempts before the minter widens its escape suffix. See :meth:`Minter.mint_entity`.
MINT_ATTEMPTS_PER_WIDTH = 8
MINT_MAX_ATTEMPTS = 64

# --------------------------------------------------------------------------------------
# Committed-bank trimming (tempdoc 767 §O)
# --------------------------------------------------------------------------------------

#: Real surfaces kept per (mintable type, length). Splicing draws two exemplars and a cut
#: point, so K exemplars of one length yield O(K**2 * len) distinct fabrications — 64 is
#: already far past what a 50-chain corpus (~150 minted names) can exhaust, and the
#: uncapped lists are 60,644 surfaces deep for a single type.
EXEMPLARS_PER_LENGTH = 64

#: 36-bit truncated sha256, 6 bits per character, so a digest is exactly 6 characters and
#: string order equals numeric order (the alphabet below is ASCII-ascending).
COLLISION_ALGORITHM = "sha256-trunc36-base64url"
COLLISION_BITS = 36
COLLISION_CHARS = 6
#: Digests per JSON string. Concatenating them avoids ~5 bytes of JSON punctuation per
#: key (which would cost more than the key itself) while keeping lines readable.
COLLISION_CHUNK_KEYS = 256

_DIGEST_ALPHABET = "-0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdefghijklmnopqrstuvwxyz"


# --------------------------------------------------------------------------------------
# Quality filters (shared with the harvester — one definition, two consumers)
# --------------------------------------------------------------------------------------

def _alpha_count(surface: str) -> int:
    return sum(1 for ch in surface if ch.isalpha())


def passes_quality(surface: str, entity_type: str) -> bool:
    """The recipe's quality filters. See :data:`ALPHA_FILTERED_TYPES` for the scoping note."""
    if not (MIN_LENGTH <= len(surface) <= MAX_LENGTH):
        return False
    if entity_type in ALPHA_FILTERED_TYPES and _alpha_count(surface) < MIN_ALPHA_CHARS:
        return False
    return True


# --------------------------------------------------------------------------------------
# Canonical bytes / digests
# --------------------------------------------------------------------------------------

def canonical_bytes(obj: object) -> bytes:
    """Deterministic, platform-independent JSON bytes (LF newlines, UTF-8)."""
    return (json.dumps(obj, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path | str) -> str:
    return sha256_bytes(Path(path).read_bytes())


# --------------------------------------------------------------------------------------
# Collision index
# --------------------------------------------------------------------------------------

def collision_digest(key: str) -> str:
    """The stored form of one normalized surface. See "Collision exactness" in the module docstring."""
    value = int.from_bytes(hashlib.sha256(key.encode("utf-8")).digest()[:5], "big")
    value >>= 40 - COLLISION_BITS
    return "".join(_DIGEST_ALPHABET[(value >> (6 * i)) & 63]
                   for i in range(COLLISION_CHARS - 1, -1, -1))


def build_collision_index(keys: Iterable[str]) -> dict:
    """Sorted, de-duplicated, chunked digests of ``keys`` (already normalized)."""
    digests = sorted({collision_digest(key) for key in keys})
    chunks = ["".join(digests[i:i + COLLISION_CHUNK_KEYS])
              for i in range(0, len(digests), COLLISION_CHUNK_KEYS)]
    return {
        "algorithm": COLLISION_ALGORITHM,
        "bits": COLLISION_BITS,
        "chars_per_key": COLLISION_CHARS,
        "chunk_keys": COLLISION_CHUNK_KEYS,
        "count": len(digests),
        "chunks": chunks,
    }


def collision_digests(index: dict) -> list[str]:
    """The flat digest list an index encodes, in stored (ascending) order."""
    stream = "".join(index.get("chunks") or [])
    return [stream[i:i + COLLISION_CHARS] for i in range(0, len(stream), COLLISION_CHARS)]


# --------------------------------------------------------------------------------------
# Loading / validation
# --------------------------------------------------------------------------------------

def load_bank(root: Path | str) -> dict:
    """Load the frozen bank. The build path uses this — never the harvester."""
    return json.loads((Path(root) / BANK_FILENAME).read_text(encoding="utf-8"))


def bank_sha256(root: Path | str) -> str:
    """The sha256 of the committed bank bytes — the value recorded in provenance."""
    return sha256_file(Path(root) / BANK_FILENAME)


_REQUIRED_HOST_FIELDS = ("corpus", "domain", "n_docs", "source_revision", "raw_source_signature")


def _validate_exemplars(bank: dict) -> dict | None:
    """Exemplar pools agree with the length distribution they were capped from.

    The last check is the load-bearing one: a length's exemplar count must be exactly
    ``min(EXEMPLARS_PER_LENGTH, weight)``. That ties the trimmed artifact back to the real
    pool it was trimmed from — a bank cannot silently drop exemplars, pad them, or claim a
    length distribution it does not carry surfaces for.
    """
    exemplars = bank.get("exemplars") or {}
    weights = bank.get("length_weights") or {}
    if not exemplars:
        return {"passed": False, "reason": "bank contains no exemplars"}
    cap = (bank.get("parameters") or {}).get("exemplars_per_length")
    if cap != EXEMPLARS_PER_LENGTH:
        return {"passed": False, "reason": "exemplar cap disagrees with the harvester"}
    if set(exemplars) != set(weights):
        return {"passed": False, "reason": "exemplars and length_weights cover different types"}
    for entity_type, by_length in sorted(exemplars.items()):
        if entity_type not in MINTABLE_TYPES:
            return {"passed": False, "reason": f"unknown entity type {entity_type}"}
        if not by_length:
            return {"passed": False, "reason": f"{entity_type} exemplar pool is empty"}
        try:
            parsed = {int(k): int(v) for k, v in weights[entity_type].items()}
            kept = {int(k): list(v) for k, v in by_length.items()}
        except (TypeError, ValueError, AttributeError):
            return {"passed": False, "reason": f"malformed entity record in {entity_type}"}
        if any(length <= 0 or weight <= 0 for length, weight in parsed.items()):
            return {"passed": False, "reason": f"non-positive length_weights for {entity_type}"}
        for length, surfaces in sorted(kept.items()):
            if any(not isinstance(s, str) or len(s) != length for s in surfaces):
                return {"passed": False,
                        "reason": f"length disagrees with surface in {entity_type}"}
            if any(not passes_quality(s, entity_type) for s in surfaces):
                return {"passed": False,
                        "reason": f"entity fails quality filters in {entity_type}"}
            if surfaces != sorted(surfaces):
                return {"passed": False, "reason": f"{entity_type} entities are not sorted"}
            if len(set(surfaces)) != len(surfaces):
                return {"passed": False, "reason": f"{entity_type} entities are not unique"}
        if set(kept) != set(parsed):
            return {"passed": False,
                    "reason": f"{entity_type} exemplar lengths disagree with length_weights"}
        unique = ((bank.get("counts") or {}).get(entity_type) or {}).get("unique")
        if unique is not None and sum(parsed.values()) != unique:
            return {"passed": False,
                    "reason": f"{entity_type} length_weights disagree with harvested counts"}
        if any(len(surfaces) != min(EXEMPLARS_PER_LENGTH, parsed[length])
               for length, surfaces in kept.items()):
            return {"passed": False,
                    "reason": f"{entity_type} exemplar counts disagree with the cap"}
    return None


def _validate_collision_index(bank: dict) -> dict | None:
    index = bank.get("collision_index") or {}
    if not index:
        return {"passed": False, "reason": "bank contains no collision index"}
    if index.get("algorithm") != COLLISION_ALGORITHM:
        return {"passed": False, "reason": "unsupported collision-index algorithm"}
    if (index.get("bits"), index.get("chars_per_key"), index.get("chunk_keys")) != (
            COLLISION_BITS, COLLISION_CHARS, COLLISION_CHUNK_KEYS):
        return {"passed": False, "reason": "collision-index parameters disagree with the harvester"}
    chunks = index.get("chunks")
    if not isinstance(chunks, list) or not chunks:
        return {"passed": False, "reason": "collision index is empty"}
    if any(len(chunk) != COLLISION_CHUNK_KEYS * COLLISION_CHARS for chunk in chunks[:-1]):
        return {"passed": False, "reason": "collision-index chunk is not fully packed"}
    if any(len(chunk) % COLLISION_CHARS for chunk in chunks):
        return {"passed": False, "reason": "malformed collision-index digest"}
    if not set("".join(chunks)) <= set(_DIGEST_ALPHABET):
        return {"passed": False, "reason": "malformed collision-index digest"}
    digests = collision_digests(index)
    if len(digests) != index.get("count"):
        return {"passed": False, "reason": "collision-index count disagrees with its chunks"}
    if any(digests[i] >= digests[i + 1] for i in range(len(digests) - 1)):
        return {"passed": False, "reason": "collision index is not sorted"}
    # Every exemplar is a real surface, so the index must already exclude it. This catches
    # an index built from a different harvest than the exemplars beside it.
    present = set(digests)
    for by_length in (bank.get("exemplars") or {}).values():
        for surfaces in by_length.values():
            if any(collision_digest(_normalize_surface(s)) not in present for s in surfaces):
                return {"passed": False, "reason": "collision index does not cover its exemplars"}
    return None


def validate_entity_bank(root: Path | str) -> dict:
    """Verify the committed bank bytes and their provenance.

    Returns ``{"passed": True, "manifest_sha256": ..., "bank_sha256": ...}`` or
    ``{"passed": False, "reason": ...}`` — the shape of
    ``corpus_certify._validate_commitment``.
    """
    root = Path(root)
    manifest_path = root / COMMITMENT_FILENAME
    if not manifest_path.is_file():
        return {"passed": False, "reason": f"missing {COMMITMENT_FILENAME}"}
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        return {"passed": False, "reason": "unreadable commitment manifest"}
    if manifest.get("schema") != COMMITMENT_SCHEMA:
        return {"passed": False, "reason": "unsupported commitment schema"}
    files = manifest.get("files") or {}
    if set(files) != {BANK_FILENAME}:
        return {"passed": False, "reason": "commitment file matrix is incomplete"}
    if any(not (root / name).is_file() or sha256_file(root / name) != digest
           for name, digest in files.items()):
        return {"passed": False, "reason": "committed bank digest mismatch"}
    try:
        bank = load_bank(root)
    except (ValueError, OSError):
        return {"passed": False, "reason": "unreadable entity bank"}
    if bank.get("schema") != BANK_SCHEMA:
        return {"passed": False, "reason": "unsupported bank schema"}
    if not bank.get("harvester_version"):
        return {"passed": False, "reason": "missing harvester_version"}
    host = bank.get("host") or {}
    if any(field not in host for field in _REQUIRED_HOST_FIELDS):
        return {"passed": False, "reason": "incomplete host provenance"}
    if host.get("domain") not in DOMAINS:
        return {"passed": False, "reason": "unknown host domain"}
    params = bank.get("parameters") or {}
    if not params.get("types"):
        return {"passed": False, "reason": "missing harvest parameters"}
    failure = _validate_exemplars(bank) or _validate_collision_index(bank)
    if failure is not None:
        return failure
    return {
        "passed": True,
        "manifest_sha256": sha256_file(manifest_path),
        "bank_sha256": files[BANK_FILENAME],
    }


# --------------------------------------------------------------------------------------
# Provenance references
# --------------------------------------------------------------------------------------

#: The directory that directly contains the ``jseval`` package — the root a relative
#: bank reference recorded in ``generation_provenance`` resolves against, so a committed
#: corpus's provenance stays machine-independent.
PACKAGE_ROOT = Path(__file__).resolve().parent.parent


def bank_reference(root: Path | str) -> str:
    """The machine-independent string recorded in ``generation_provenance``.

    A bank under the jseval package root is recorded POSIX-relative to it (so the
    provenance of a committed corpus reproduces on any checkout); anything else — a
    test's ``tmp_path`` bank — is recorded absolute, which is honest about the fact
    that such a corpus is not reproducible elsewhere.
    """
    resolved = Path(root).resolve()
    try:
        return resolved.relative_to(PACKAGE_ROOT).as_posix()
    except ValueError:
        return resolved.as_posix()


def resolve_bank_reference(reference: str) -> Path:
    """Inverse of :func:`bank_reference`."""
    candidate = Path(reference)
    return candidate if candidate.is_absolute() else (PACKAGE_ROOT / candidate)


# --------------------------------------------------------------------------------------
# Sampling
# --------------------------------------------------------------------------------------

def length_band(target_length: int) -> tuple[float, float]:
    """Accepted absolute length window for a target length."""
    return (LENGTH_BAND_MIN_RATIO * target_length, LENGTH_BAND_MAX_RATIO * target_length)


def in_length_band(candidate_length: int, target_length: int) -> bool:
    lo, hi = length_band(target_length)
    return lo <= candidate_length <= hi


def length_distribution(bank: dict, entity_type: str) -> tuple[list[int], list[int], int]:
    """``(lengths, cumulative_weights, total)`` for a mintable type — the REAL distribution.

    Built from ``length_weights``, which is uncapped, so a draw against it is distributed
    exactly as a uniform draw over the untrimmed surface list would have been.
    """
    weights = ((bank.get("length_weights") or {}).get(entity_type)) or {}
    lengths = sorted(int(k) for k in weights)
    cumulative: list[int] = []
    running = 0
    for length in lengths:
        running += int(weights[str(length)])
        cumulative.append(running)
    return lengths, cumulative, running


def draw_length(bank: dict, entity_type: str, rng) -> int | None:
    """A length drawn with real-corpus frequency. One ``rng`` call."""
    lengths, cumulative, total = length_distribution(bank, entity_type)
    if total <= 0:
        return None
    return lengths[bisect.bisect_right(cumulative, rng.randrange(total))]


def _draw_exemplar(bank: dict, entity_type: str, rng) -> dict | None:
    """A real exemplar of ``entity_type``: length by real frequency, then uniform within it.

    Two ``rng`` calls. Length-wise this is exactly the untrimmed uniform draw; within a
    length it is uniform over the kept exemplars rather than over all real surfaces of
    that length — the one thing trimming gives up.
    """
    length = draw_length(bank, entity_type, rng)
    if length is None:
        return None
    surfaces = ((bank.get("exemplars") or {}).get(entity_type) or {}).get(str(length)) or []
    if not surfaces:
        return None
    return {"s": surfaces[rng.randrange(len(surfaces))], "len": length}


def sample_matched(bank: dict, *, type: str, target_length: int, rng) -> dict | None:  # noqa: A002
    """Draw a real entity of ``type`` whose length is within 0.3x-3.0x of ``target_length``.

    Up to :data:`MAX_RESAMPLES` draws; returns ``None`` (reject) if none land in band.
    ``rng`` is a ``random.Random`` — the caller owns the seed, so the build path stays
    reproducible. Reads only the frozen bank; never touches the harvester.
    """
    if target_length <= 0:
        return None
    for _ in range(MAX_RESAMPLES):
        row = _draw_exemplar(bank, type, rng)
        if row is None:
            return None
        if in_length_band(row["len"], target_length):
            return row
    return None


# --------------------------------------------------------------------------------------
# Minting (fabricated entities + gold values)
# --------------------------------------------------------------------------------------

def _normalize_surface(surface: str) -> str:
    """The collision key: NFKD → lowercase → non-alphanumeric collapsed to single spaces.

    Deliberately the same shape as ``context_coverage.normalize_evidence_text``, which is
    what the scorer compares with — two surfaces that normalize alike are the SAME string
    as far as scoring is concerned, so they must not both exist.
    """
    folded = unicodedata.normalize("NFKD", surface).lower()
    return " ".join("".join(ch if ch.isalnum() else " " for ch in folded).split())


#: Public name for the collision key — the harvester builds the committed index with it,
#: so both halves normalize identically by construction.
normalize_surface = _normalize_surface


#: Gold-value shapes. Format-DIVERSE by construction (bare pair, prefixed, suffixed,
#: hyphen-grouped, glued) so no single regex or shape-grep selects the value set, and
#: none of them shares a shape with a minted entity name (which is alphabetic-only).
#: Every shape is exact-matchable after ``normalize_evidence_text`` — no trailing period
#: (``agent_retrieval_eval._score_answer`` does ``gt.lower().strip().rstrip(".")`` then a
#: substring test, so a value must survive verbatim), no '/' or internal '.' that a model
#: might re-render differently.
_VALUE_SHAPES = (
    lambda rng, L, D: f"{L(2)}-{D(4)}",
    lambda rng, L, D: f"lot {D(7)}",
    lambda rng, L, D: f"{L(3)} {D(3)}",
    lambda rng, L, D: f"grade {D(2)}-{L(2)}-{D(1)}",
    lambda rng, L, D: f"{D(4)}{L(1)}",
    lambda rng, L, D: f"ref-{D(2)}-{D(4)}",
)

_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"  # no I/O — they read as 1/0 in an alphanumeric code
_LOWER = "abcdefghijklmnopqrstuvwxyz"


class Minter:
    """Mints fabricated chain entities and gold values against a frozen bank.

    One instance per ``generate()`` call. It owns the uniqueness ledgers (used for
    membership tests only — never iterated, never fed to the RNG) so that every minted
    surface is globally distinct across gold *and* distractor chains, which is what the
    deleted monotonic uid counter used to guarantee.
    """

    def __init__(self, bank: dict) -> None:
        self._bank = bank
        exemplars = bank.get("exemplars") or {}
        allowed = bank_mintable_types(bank)
        self._types: tuple[str, ...] = tuple(t for t in allowed if exemplars.get(t))
        if not self._types:
            raise ValueError(
                f"entity bank carries none of the mintable types {allowed}; "
                "a bank harvested from a host corpus always does"
            )
        #: Truncated digests of every real surface in the bank, all types — the "must not
        #: exist in any real doc" blacklist. Membership only; never iterated, never fed to
        #: an RNG. Exact against false negatives; see "Collision exactness" in the module
        #: docstring for the false-positive bound and why it is harmless.
        self._real: frozenset[str] = frozenset(collision_digests(bank.get("collision_index") or {}))
        self._minted_names: set[str] = set()
        self._minted_values: set[str] = set()
        self._n_minted = 0

    # -- entities ----------------------------------------------------------------------

    def mint_entity(self, rng) -> str:
        """A fabricated, type- and length-matched surface, unique across the corpus.

        Splices a head fragment of one real exemplar with a tail fragment of another of
        the same type and comparable length. The result is rejected unless it is
        digit-free (so an entity name can never share a numeric suffix with a gold
        value — the answer-leak class this replaces), inside the exemplar's length band,
        quality-passing, absent from the bank, and not already minted.
        """
        entity_type = self._types[self._n_minted % len(self._types)]
        self._n_minted += 1
        target = draw_length(self._bank, entity_type, rng)

        for attempt in range(MINT_MAX_ATTEMPTS):
            a = self._exemplar(entity_type, target, rng)
            b = self._exemplar(entity_type, target, rng)
            surface = _splice(a, b, rng)
            width = attempt // MINT_ATTEMPTS_PER_WIDTH
            if width:
                # Escape hatch for a saturated fragment space (a high distractor ratio
                # needs thousands of distinct names): widen with rng-drawn letters, never
                # a counter — a monotonic suffix is exactly the leak this change removes.
                surface += "".join(_LOWER[rng.randrange(len(_LOWER))] for _ in range(width))
            if self._acceptable(surface, entity_type, target):
                self._minted_names.add(_normalize_surface(surface))
                return surface
        raise RuntimeError(
            f"could not mint a unique {entity_type} entity in {MINT_MAX_ATTEMPTS} attempts; "
            "the bank is too small for this corpus size"
        )

    def _exemplar(self, entity_type: str, target: int, rng) -> str:
        row = sample_matched(self._bank, type=entity_type, target_length=target, rng=rng)
        if row is None:
            row = _draw_exemplar(self._bank, entity_type, rng)
        return row["s"] if row else ""

    def _acceptable(self, surface: str, entity_type: str, target: int) -> bool:
        if not surface or any(ch.isdigit() for ch in surface):
            return False
        if not passes_quality(surface, entity_type):
            return False
        if not in_length_band(len(surface), target):
            return False
        key = _normalize_surface(surface)
        if not key or len(key) < MIN_ALPHA_CHARS:
            return False
        return collision_digest(key) not in self._real and key not in self._minted_names

    # -- gold values -------------------------------------------------------------------

    def mint_value(self, rng) -> str:
        """A gold value drawn from a format-diverse space, independent of any entity uid.

        Uniqueness is enforced against every other minted value AND against every minted
        entity name and every real bank surface, so an answer string can never coincide
        with an entity — the property the old ``adjective noun {uid:04d}`` scheme got by
        deriving the value from the same counter as the name, at the cost of leaking the
        answer from the name.
        """
        def letters(n: int) -> str:
            return "".join(_UPPER[rng.randrange(len(_UPPER))] for _ in range(n))

        def digits(n: int) -> str:
            return "".join(str(rng.randrange(10)) for _ in range(n))

        for _ in range(MINT_MAX_ATTEMPTS):
            shape = _VALUE_SHAPES[rng.randrange(len(_VALUE_SHAPES))]
            value = shape(rng, letters, digits)
            key = _normalize_surface(value)
            if key and key not in self._minted_values and key not in self._minted_names \
                    and collision_digest(key) not in self._real:
                self._minted_values.add(key)
                return value
        raise RuntimeError(
            f"could not mint a unique gold value in {MINT_MAX_ATTEMPTS} attempts"
        )


def _splice(a: str, b: str, rng) -> str:
    """Head fragment of ``a`` + tail fragment of ``b``, token by token, ``a``'s casing kept.

    Operates per token so a two-word exemplar yields a two-word fabrication — the
    type-shape match ("a fake name that reads like a name") rather than a length match
    alone.
    """
    a_tokens = a.split()
    b_tokens = b.split()
    if not a_tokens or not b_tokens:
        return ""
    out = []
    for i, token in enumerate(a_tokens):
        other = b_tokens[i % len(b_tokens)]
        core = _core(token)
        other_core = _core(other)
        if len(core) < _MIN_SPLICE_TOKEN or len(other_core) < _MIN_SPLICE_TOKEN:
            # Too short to split into two recognizable halves — a state abbreviation
            # ("TX"), an initial ("J."). Splicing these yields unreadable junk ("Ml"), so
            # the exemplar's own token is kept: a generic short token carries no entity
            # identity, and the full-surface collision check still applies.
            out.append(token)
            continue
        merged = core[: _cut(core, rng)] + other_core[_cut(other_core, rng):]
        if not merged:
            continue
        cased = merged[0].upper() + merged[1:].lower() if core[:1].isupper() else merged.lower()
        out.append(cased + token[len(token.rstrip(_TRAILING_PUNCT)):])
    return " ".join(out)


#: Below this, a token is kept whole rather than spliced (see :func:`_splice`).
_MIN_SPLICE_TOKEN = 4
_TRAILING_PUNCT = ",.;:"


def _core(token: str) -> str:
    """The token's name-bearing characters — trailing punctuation dropped."""
    return "".join(ch for ch in token.rstrip(_TRAILING_PUNCT) if ch.isalpha() or ch in "'-")


def _cut(token: str, rng) -> int:
    """A split point strictly inside ``token`` (so neither side is the whole token)."""
    if len(token) <= 2:
        return 1
    return 1 + rng.randrange(len(token) - 1)
