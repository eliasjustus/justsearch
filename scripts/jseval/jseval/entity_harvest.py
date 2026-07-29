"""Offline, stdlib-only entity harvester for the committed entity-bank artifact (tempdoc 767).

ARCHITECTURAL BOUNDARY (load-bearing — do not erode)
----------------------------------------------------
This module runs **offline, exactly once per host corpus**, and its output —
``entity-bank.v2.json`` plus a sha256 ``commitment.v1.json`` — is **committed to the
repo**. The corpus build path MUST NEVER import this module: it only ever *samples the
frozen committed bank*, through :mod:`jseval.entity_bank` (``load_bank`` /
``sample_matched`` / ``validate_entity_bank`` / ``Minter``), which is a strictly
lower layer and does not import this one.

Why: the build path carries a cross-interpreter determinism proof
(``corpus_generate.regenerate_and_diff``-style cross-process regeneration diff). That
proof stays cheap and credible only if the build path's inputs are frozen bytes. If
harvesting ran at build time, every regex, every gazetteer entry and every host-corpus
byte would enter the determinism surface. Harvest once, pin the sha, sample forever.

Determinism contract
--------------------
This project never sets ``PYTHONHASHSEED``. Therefore:

- ``set`` iteration order NEVER reaches output — every emitted collection is ``sorted()``.
- ``hash()`` is never used for anything that reaches output; ``hashlib.sha256`` only.
- The bank is written as UTF-8 bytes with ``\\n`` newlines (never ``Path.write_text``,
  which would emit CRLF on Windows).

Re-running the harvester over the same host bytes with the same parameters yields
byte-identical ``entity-bank.v2.json``.

Purpose of the bank
-------------------
A bank of *real* entity surfaces, typed and length-annotated, so that **fabricated**
entities can later be minted type- and length-matched to real ones (a fake name that
reads like a real name, of plausible length). Bank strings are used for shape/length
matching and collision avoidance — they are **never copied into a generated corpus**.

CLI::

    PYTHONUTF8=1 python -m jseval.entity_harvest --docs-dir <dir> --domain legal --out <dir>
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
import time
from pathlib import Path
from typing import Iterable, Iterator, Sequence

from jseval.corpus_identity import corpus_signature

# The schema constants and quality filters live in the build-path module so the two
# halves cannot drift; the dependency direction is harvester -> bank, never the reverse.
from jseval.entity_bank import (  # noqa: F401  (re-exported: the CLI/report path uses them)
    ALPHA_FILTERED_TYPES,
    BANK_FILENAME,
    BANK_SCHEMA,
    COMMITMENT_FILENAME,
    COMMITMENT_SCHEMA,
    DOMAINS,
    ENTITY_TYPES,
    EXEMPLARS_PER_LENGTH,
    FULL_HARVEST_FILENAME,
    FULL_HARVEST_SCHEMA,
    MAX_LENGTH,
    MIN_ALPHA_CHARS,
    MIN_LENGTH,
    MINTABLE_TYPES,
    LENGTH_BAND_MAX_RATIO,
    LENGTH_BAND_MIN_RATIO,
    MAX_RESAMPLES,
    build_collision_index,
    canonical_bytes,
    normalize_surface,
    passes_quality,
    sha256_bytes,
)

# --------------------------------------------------------------------------------------
# Versioning
# --------------------------------------------------------------------------------------

HARVESTER_VERSION = "entity-harvest.v1"

# --------------------------------------------------------------------------------------
# Lexicons (data, not dependencies)
# --------------------------------------------------------------------------------------

_ORG_CUES = frozenset({
    "inc", "corp", "co", "llc", "llp", "lp", "ltd", "plc", "gmbh", "sa", "nv", "ag",
    "company", "corporation", "incorporated", "department", "commission", "board",
    "bureau", "agency", "authority", "association", "institute", "university",
    "college", "court", "office", "committee", "council", "group", "systems",
    "services", "service", "industries", "bank", "trust", "partners", "holdings",
    "enterprises", "foundation", "society", "union", "ministry", "administration",
    "division", "center", "centre", "laboratories", "laboratory", "technologies",
    "networks", "communications", "energy", "airlines", "motors", "insurance",
    "hospital", "school", "association", "products", "resources", "solutions",
    "capital", "management", "international", "consulting", "pharmaceuticals",
    "limited", "gas", "electric", "power", "utilities", "telecom", "media",
})

_LOC_GAZETTEER = frozenset({
    "alabama", "alaska", "arizona", "arkansas", "california", "colorado",
    "connecticut", "delaware", "florida", "georgia", "hawaii", "idaho", "illinois",
    "indiana", "iowa", "kansas", "kentucky", "louisiana", "maine", "maryland",
    "massachusetts", "michigan", "minnesota", "mississippi", "missouri", "montana",
    "nebraska", "nevada", "ohio", "oklahoma", "oregon", "pennsylvania", "tennessee",
    "texas", "utah", "vermont", "virginia", "washington", "wisconsin", "wyoming",
    "new hampshire", "new jersey", "new mexico", "new york", "north carolina",
    "north dakota", "rhode island", "south carolina", "south dakota", "west virginia",
    "united states", "united kingdom", "canada", "mexico", "france", "germany",
    "japan", "china", "india", "brazil", "australia", "russia", "italy", "spain",
    "houston", "dallas", "austin", "chicago", "boston", "atlanta", "denver",
    "seattle", "portland", "phoenix", "detroit", "philadelphia", "san francisco",
    "san diego", "san jose", "los angeles", "las vegas", "new orleans",
    "kansas city", "salt lake city", "st. louis", "washington d.c.",
    "north america", "south america", "europe", "asia", "africa",
})

_US_STATE_ABBR = (
    "AL|AK|AZ|AR|CA|CO|CT|DE|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|"
    "MT|NE|NV|NH|NJ|NM|NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|DC"
)

#: Capitalized words that routinely start a sentence and would otherwise glue onto a
#: real name ("Appellants Morris", "However Smith"). Kept small and generic.
_LEADING_STOPWORDS = frozenset({
    "the", "this", "that", "these", "those", "a", "an", "and", "but", "for", "nor",
    "or", "so", "yet", "in", "on", "at", "by", "to", "of", "we", "it", "he", "she",
    "as", "such", "his", "her", "their", "our", "your", "its", "not", "all", "any",
    "each", "every", "some", "most", "more", "less", "than", "then", "also",
    "they", "there", "here", "if", "when", "while", "because", "however",
    "accordingly", "according", "after", "before", "under", "both", "see", "id",
    "although", "though", "since", "thus", "therefore", "moreover", "furthermore",
    "first", "second", "third", "finally", "instead", "nonetheless", "nevertheless",
    "appellants", "appellant", "appellee", "appellees", "plaintiff", "plaintiffs",
    "defendant", "defendants", "petitioner", "respondent", "court", "no", "subject",
    "sent", "from", "please", "thanks", "dear", "original", "message", "forwarded",
    "re", "fw", "cc", "bcc",
})

#: Role / institutional nouns that make a capitalized run a title or an institution
#: rather than a person ("Circuit Judge", "Supreme Court", "Chief Justice").
_NON_PERSON_NOUNS = frozenset({
    "judge", "judges", "justice", "justices", "circuit", "district", "appeals",
    "appeal", "chief", "senior", "magistrate", "bankruptcy", "supreme", "federal",
    "state", "county", "city", "act", "amendment", "article", "section", "title",
    "rule", "rules", "code", "statute", "clause", "term", "part", "chapter",
    "exhibit", "table", "figure", "claim", "claims", "patent", "opinion", "order",
    "attorney", "counsel", "clerk", "jury", "trial", "hearing", "motion",
    "complaint", "guidelines", "guideline", "amended", "amicus", "curiae", "brief",
    "memorandum", "summary", "judgment", "evidence", "discovery", "sentencing",
    "agreement", "contract", "record", "records", "report", "reports", "notice",
    "application", "petition", "response", "reply", "affidavit", "testimony",
    "prior", "art", "new", "original", "final", "proposed", "further", "general",
})

_PARTICLES = frozenset({"de", "van", "von", "der", "den", "del", "della", "la", "le", "du", "di"})

#: Trailing-period tokens that are genuine abbreviations, so the period is part of the
#: surface ("Widget Corp.") rather than sentence punctuation ("United States.").
_ABBREVIATIONS = frozenset({
    "inc", "corp", "co", "ltd", "llc", "llp", "lp", "no", "nos", "ser", "jr", "sr",
    "mr", "mrs", "ms", "dr", "st", "mt", "ave", "assn", "bros", "dept", "univ",
})

_CONNECTORS = r"(?:of|the|and|for|de|van|von|der|den|del|della|la|le|du|di|&)"

# --------------------------------------------------------------------------------------
# Patterns
# --------------------------------------------------------------------------------------

#: A run token. A trailing period is allowed ONLY for a single-letter initial or a known
#: abbreviation — otherwise a run bleeds across sentence boundaries ("New York. The United
#: States Patent and Trademark Office" would harvest as one ORG and eat the LOC).
_CAP = (
    r"(?:[A-Z][A-Za-z'’\-]{0,19}"
    r"|[A-Z]\."
    r"|(?:Inc|Corp|Co|Ltd|LLC|LLP|Nos?|Ser|Jr|Sr|Mr|Mrs|Ms|Dr|St|Mt|Univ|Dept|Assn|Bros|Ave)\.)"
)

_MONTHS = (
    "January|February|March|April|May|June|July|August|September|October|November|December|"
    "Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec"
)

_REPORTERS = (
    r"U\.\s?S\.|S\.\s?Ct\.|F\.\s?\d?d|F\.\s?Supp\.(?:\s?\d?d)?|L\.\s?Ed\.(?:\s?\d?d)?|"
    r"U\.S\.C\.|C\.F\.R\.|USPQ\d?d?|C\.C\.P\.A\.|N\.E\.\s?\d?d|N\.W\.\s?\d?d|"
    r"S\.E\.\s?\d?d|S\.W\.\s?\d?d|P\.\s?\d?d|A\.\s?\d?d|So\.\s?\d?d|B\.R\.|Cal\.\s?\d?d"
)

# Ordered by priority: earlier wins an overlap.
_PRIORITY: tuple[str, ...] = (
    "CASE", "CITATION", "DOCKET", "EMAIL", "DATE", "PER", "ORG", "LOC", "CARDINAL",
)

_RE_EMAIL = re.compile(r"\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,12}\b")

_RE_DATE = re.compile(
    rf"\b(?:{_MONTHS})\.?\s+\d{{1,2}},?\s+\d{{4}}\b"
    rf"|\b\d{{1,2}}\s+(?:{_MONTHS})\.?\s+\d{{4}}\b"
    r"|\b\d{1,2}/\d{1,2}/\d{2,4}\b"
    r"|\b\d{4}-\d{2}-\d{2}\b"
)

_RE_CARDINAL = re.compile(r"\b\d{1,3}(?:,\d{3})+(?:\.\d+)?\b|\b\d+\.\d{1,3}\b|\b\d{3,}\b")

#: The pincite tail must not swallow the volume number of the NEXT parallel citation —
#: "477 U.S. 317, 323, 106 S.Ct. 2548" must stop at 323, not eat "106".
_RE_CITATION = re.compile(
    rf"\b\d{{1,4}}\s+(?:{_REPORTERS})\s+\d+"
    rf"(?:,\s*\d+(?:[-–]\d+)?\b(?!\s+(?:{_REPORTERS})))*"
    r"(?:\s*\([A-Za-z0-9.\s]{0,24}\d{4}\))?"
)

_RE_DOCKET = re.compile(
    r"\bSer\.\s*No\.\s*\d{2}/\d{3},\d{3}\b"
    r"|\bNos?\.\s*\d{2}[-–]\d{3,5}(?:,\s*\d{2}[-–]\d{3,5})*\b"
    r"|\bCase\s+No\.\s*[A-Za-z0-9:\-]{4,24}\b"
    r"|\bNo\.\s*\d{1,2}:\d{2}[-–][A-Za-z]{2,4}[-–]\d{3,6}\b"
)

_RE_CASE = re.compile(
    rf"\b{_CAP}(?:,?\s+(?:{_CONNECTORS}\s+)?{_CAP}){{0,5}}"
    rf"\s+v\.\s+"
    rf"{_CAP}(?:\s+(?:{_CONNECTORS}\s+)?{_CAP}){{0,5}}"
    r"(?:,\s*(?:Inc|Corp|Co|LLC|Ltd|L\.P|N\.A)\.?)?"
)

_RE_CAP_RUN = re.compile(
    rf"\b{_CAP}(?:\s+(?:{_CONNECTORS}\s+)?{_CAP}){{1,6}}"
)

_RE_CITY_STATE = re.compile(
    rf"\b[A-Z][a-z]+(?:\s+[A-Z][a-z]+)?,\s+(?:{_US_STATE_ABBR})\b"
)

# --- legal-domain ---------------------------------------------------------------------

_RE_JUDGE_TITLED = re.compile(
    r"\b([A-Z][A-Za-z'’\-]+(?:\s+[A-Z]\.)?(?:\s+[A-Z][A-Za-z'’\-]+){0,2}),\s+"
    r"(?:Circuit|District|Chief|Senior|Bankruptcy|Magistrate|Associate)\s+Judges?\b"
)
_RE_JUDGE_PANEL = re.compile(r"\bBefore\s+([A-Z][A-Z'’\-]{2,}(?:,\s*(?:and\s+)?[A-Z][A-Z'’\-]{2,})*)")
_RE_PANEL_NAME = re.compile(r"[A-Z][A-Z'’\-]{2,}")

# --- email-domain ---------------------------------------------------------------------

_RE_HEADER_PERSON = re.compile(
    r"^(?:From|To|Sender|Cc|Recipients):\s*(.+)$", re.MULTILINE
)
_RE_DISPLAY_NAME = re.compile(
    r"\b[A-Z][a-z]{1,15}(?:\s+[A-Z]\.?)?\s+[A-Z][a-z]{1,15}\b"
)
_RE_DOTTED_LOCALPART = re.compile(r"\b([a-z]{2,15})\.([a-z]{2,15})@[A-Za-z0-9.\-]+\b")


# --------------------------------------------------------------------------------------
# Normalization + filters
# --------------------------------------------------------------------------------------

def _normalize(surface: str) -> str:
    """Collapse whitespace and trim punctuation that regex boundaries drag in."""
    s = re.sub(r"\s+", " ", surface).strip()
    s = s.lstrip(" ,;:.—–-")
    s = s.rstrip(" ,;:—–-")
    return s


def _tokens(surface: str) -> list[str]:
    return surface.split(" ")


def _lexeme(token: str) -> str:
    """Token reduced to its lookup form: possessive dropped, trailing period dropped."""
    core = token.rstrip(".")
    for suffix in ("’s", "'s", "’", "'"):
        if core.endswith(suffix) and len(core) > len(suffix):
            core = core[: -len(suffix)]
            break
    return core.lower()


def _trim_sentence_period(surface: str) -> str:
    """Drop a trailing period that is sentence punctuation, not part of an abbreviation."""
    if not surface.endswith("."):
        return surface
    last = _tokens(surface)[-1].rstrip(".")
    if len(last) == 1 or last.lower() in _ABBREVIATIONS:
        return surface
    return surface[:-1]


def _trim_leading_stopwords(surface: str) -> str:
    """Drop sentence lead-ins glued to the front of a span ("See Celotex Corp. v. Catrett")."""
    toks = _tokens(surface)
    while len(toks) > 1 and _lexeme(toks[0]) in _LEADING_STOPWORDS:
        toks = toks[1:]
    return " ".join(toks)


def _trim_possessive(surface: str) -> str:
    """Drop a trailing possessive so ``Supreme Court's`` folds into ``Supreme Court``."""
    for suffix in ("’s", "'s", "’", "'"):
        if surface.endswith(suffix) and len(surface) > len(suffix) + 1:
            return surface[: -len(suffix)]
    return surface


def _is_initial(token: str) -> bool:
    return len(token) == 2 and token[0].isupper() and token[1] == "."


def _is_titlecase_word(token: str) -> bool:
    core = token.rstrip(".")
    return len(core) >= 2 and core[0].isupper() and core[1:].islower()


def _classify_run(surface: str) -> str | None:
    """Classify a capitalized run as ORG / LOC / PER, or ``None`` if it is junk."""
    toks = _tokens(surface)
    if len(toks) < 2:
        return None
    lowered = [_lexeme(t) for t in toks]
    if any(t in _ORG_CUES for t in lowered):
        return "ORG"
    if " ".join(lowered) in _LOC_GAZETTEER:
        return "LOC"
    if lowered[0] in _LEADING_STOPWORDS:
        return None
    if len(toks) > 4:
        return None
    named = 0
    for tok, low in zip(toks, lowered):
        if low in _PARTICLES or _is_initial(tok):
            continue
        # A non-initial token ending in "." is an abbreviation ("Cong. Rec", "Harv. L.
        # Rev") — citation apparatus, not a person.
        if tok.endswith("."):
            return None
        if not _is_titlecase_word(tok):
            return None
        if low in _LEADING_STOPWORDS or low in _NON_PERSON_NOUNS:
            return None
        named += 1
    return "PER" if named >= 2 else None


# --------------------------------------------------------------------------------------
# Extraction
# --------------------------------------------------------------------------------------

def _candidates(
    text: str, *, domain: str,
) -> tuple[list[tuple[int, int, str, str]], list[tuple[str, str]]]:
    """``(spans, derived)`` candidates.

    ``spans`` are literal text spans and go through overlap resolution. ``derived`` are
    entities *reconstructed* rather than quoted (a display name rebuilt from an email
    localpart) — they have no honest span, so they bypass overlap resolution instead of
    losing to the EMAIL span they were derived from.
    """
    out: list[tuple[int, int, str, str]] = []
    derived: list[tuple[str, str]] = []

    def _clean(entity_type: str, raw: str) -> str:
        surface = _normalize(raw)
        if entity_type in ALPHA_FILTERED_TYPES:
            surface = _trim_possessive(_trim_sentence_period(_trim_leading_stopwords(surface)))
        return surface

    def add(start: int, end: int, entity_type: str, raw: str) -> None:
        surface = _clean(entity_type, raw)
        if surface and passes_quality(surface, entity_type):
            out.append((start, end, entity_type, surface))

    def add_derived(entity_type: str, raw: str) -> None:
        surface = _clean(entity_type, raw)
        if surface and passes_quality(surface, entity_type):
            derived.append((entity_type, surface))

    for m in _RE_EMAIL.finditer(text):
        add(m.start(), m.end(), "EMAIL", m.group(0))
    for m in _RE_DATE.finditer(text):
        add(m.start(), m.end(), "DATE", m.group(0))
    for m in _RE_CARDINAL.finditer(text):
        add(m.start(), m.end(), "CARDINAL", m.group(0))
    for m in _RE_CITY_STATE.finditer(text):
        add(m.start(), m.end(), "LOC", m.group(0))
    for m in _RE_CAP_RUN.finditer(text):
        kind = _classify_run(_normalize(m.group(0)))
        if kind:
            add(m.start(), m.end(), kind, m.group(0))

    if domain == "legal":
        for m in _RE_CITATION.finditer(text):
            add(m.start(), m.end(), "CITATION", m.group(0))
        for m in _RE_DOCKET.finditer(text):
            add(m.start(), m.end(), "DOCKET", m.group(0))
        for m in _RE_CASE.finditer(text):
            add(m.start(), m.end(), "CASE", m.group(0))
        for m in _RE_JUDGE_TITLED.finditer(text):
            add(m.start(1), m.end(1), "PER", m.group(1))
        for m in _RE_JUDGE_PANEL.finditer(text):
            for name in _RE_PANEL_NAME.finditer(m.group(1)):
                start = m.start(1) + name.start()
                add(start, start + len(name.group(0)), "PER", name.group(0))
    elif domain == "email":
        for m in _RE_HEADER_PERSON.finditer(text):
            for name in _RE_DISPLAY_NAME.finditer(m.group(1)):
                start = m.start(1) + name.start()
                add(start, start + len(name.group(0)), "PER", name.group(0))
        for m in _RE_DOTTED_LOCALPART.finditer(text):
            add_derived("PER", f"{m.group(1).title()} {m.group(2).title()}")
    elif domain == "wiki":
        # Encyclopedic prose (tempdoc 748): no domain-specific apparatus. The generic
        # extractors above ARE the harvest. Named explicitly so the absence is a decision
        # on the record rather than a fall-through nobody chose.
        pass

    return out, derived


def _resolve_overlaps(cands: Sequence[tuple[int, int, str, str]]) -> list[tuple[str, str]]:
    """Greedy, priority-ordered, fully deterministic overlap resolution."""
    rank = {t: i for i, t in enumerate(_PRIORITY)}
    ordered = sorted(cands, key=lambda c: (rank[c[2]], c[0], -(c[1] - c[0]), c[3]))
    taken: list[tuple[int, int]] = []
    kept: list[tuple[int, int, str, str]] = []
    for start, end, entity_type, surface in ordered:
        if any(start < t_end and t_start < end for t_start, t_end in taken):
            continue
        taken.append((start, end))
        kept.append((start, end, entity_type, surface))
    kept.sort(key=lambda c: (c[0], c[1], c[2]))
    return [(entity_type, surface) for _, _, entity_type, surface in kept]


def extract(text: str, *, domain: str = "legal") -> list[tuple[str, str]]:
    """Typed entity spans for one document, in document order (deterministic)."""
    if domain not in DOMAINS:
        raise ValueError(f"unknown domain {domain!r}; expected one of {DOMAINS}")
    spans, derived = _candidates(text, domain=domain)
    return _resolve_overlaps(spans) + sorted(set(derived))


# --------------------------------------------------------------------------------------
# Bank construction
# --------------------------------------------------------------------------------------

def harvest_documents(
    documents: Iterable[tuple[str, str]], *, domain: str = "legal",
) -> tuple[dict[str, list[dict]], dict[str, dict[str, int]], int]:
    """Harvest ``(doc_id, text)`` pairs into per-type entity lists + counts.

    Returns ``(entities, counts, n_docs)``. ``entities[type]`` is sorted by surface;
    each record is ``{"s": surface, "len": int, "df": int}``. ``counts[type]`` carries
    ``{"total": occurrences, "unique": distinct surfaces}``.
    """
    occurrences: dict[tuple[str, str], int] = {}
    doc_freq: dict[tuple[str, str], int] = {}
    n_docs = 0
    for _doc_id, text in documents:
        n_docs += 1
        seen_in_doc: dict[tuple[str, str], None] = {}
        for entity_type, surface in extract(text, domain=domain):
            key = (entity_type, surface)
            occurrences[key] = occurrences.get(key, 0) + 1
            seen_in_doc[key] = None
        for key in seen_in_doc:
            doc_freq[key] = doc_freq.get(key, 0) + 1

    entities: dict[str, list[dict]] = {}
    counts: dict[str, dict[str, int]] = {}
    for entity_type in ENTITY_TYPES:
        rows = sorted(
            (
                {"s": surface, "len": len(surface), "df": doc_freq[(t, surface)]}
                for (t, surface) in doc_freq
                if t == entity_type
            ),
            key=lambda r: r["s"],
        )
        if not rows:
            continue
        entities[entity_type] = rows
        counts[entity_type] = {
            "total": sum(occurrences[(entity_type, r["s"])] for r in rows),
            "unique": len(rows),
        }
    return entities, counts, n_docs


def select_exemplars(rows: Sequence[dict]) -> dict[str, list[str]]:
    """Up to ``EXEMPLARS_PER_LENGTH`` surfaces per length, stride-spread across the sorted list.

    ``rows`` arrives sorted by surface, so a head slice would keep only names starting
    with "A". Striding by ``i * n // K`` keeps an evenly spaced sample of the alphabet,
    which is what makes the trimmed pool a *sample* of the real one rather than a corner
    of it. Deterministic — no RNG, no set iteration.
    """
    by_length: dict[int, list[str]] = {}
    for row in rows:
        by_length.setdefault(row["len"], []).append(row["s"])
    out: dict[str, list[str]] = {}
    for length in sorted(by_length):
        surfaces = by_length[length]
        n = len(surfaces)
        if n <= EXEMPLARS_PER_LENGTH:
            kept = list(surfaces)
        else:
            kept = [surfaces[i * n // EXEMPLARS_PER_LENGTH]
                    for i in range(EXEMPLARS_PER_LENGTH)]
        out[str(length)] = kept
    return out


def length_weights(rows: Sequence[dict]) -> dict[str, int]:
    """The UNCAPPED per-length unique-surface counts — what every length draw uses."""
    counts: dict[int, int] = {}
    for row in rows:
        counts[row["len"]] = counts.get(row["len"], 0) + 1
    return {str(length): counts[length] for length in sorted(counts)}


def trim_bank(full: dict, *, mintable_types: Sequence[str] | None = None) -> dict:
    """Derive the committed ``entity-bank.v2`` from a full harvest (pure; no IO).

    Keeps exemplars + length weights for the mintable types and a collision index over
    every real surface of every type; drops the surfaces the build path never reads.

    ``mintable_types`` narrows the draw for THIS bank (tempdoc 748) — see
    :func:`jseval.entity_bank.bank_mintable_types` for why a German host corpus needs it.
    The narrowed set is recorded in ``parameters.mintable_types`` so the build path reads
    it off the committed artifact rather than being told out of band.
    """
    entities = full["entities"]
    allowed = MINTABLE_TYPES if mintable_types is None else tuple(
        t for t in MINTABLE_TYPES if t in set(mintable_types))
    if not allowed:
        raise ValueError(f"mintable_types must name at least one of {MINTABLE_TYPES}")
    mintable = [t for t in allowed if entities.get(t)]
    parameters = dict(full["parameters"], exemplars_per_length=EXEMPLARS_PER_LENGTH)
    if mintable_types is not None:
        parameters["mintable_types"] = list(allowed)
    return {
        "schema": BANK_SCHEMA,
        "harvester_version": full["harvester_version"],
        "host": full["host"],
        "parameters": parameters,
        "counts": full["counts"],
        "exemplars": {t: select_exemplars(entities[t]) for t in mintable},
        "length_weights": {t: length_weights(entities[t]) for t in mintable},
        "collision_index": build_collision_index(
            normalize_surface(row["s"]) for rows in entities.values() for row in rows
        ),
    }


def build_bank(
    documents: Iterable[tuple[str, str]],
    *,
    domain: str,
    host_corpus: str,
    source_revision: str | None = None,
    raw_source_signature: str | None = None,
    corpus_sig: str | None = None,
    mintable_types: Sequence[str] | None = None,
) -> dict:
    """The committed, trimmed bank for ``documents`` — what the build path loads."""
    return trim_bank(build_full_harvest(
        documents, domain=domain, host_corpus=host_corpus,
        source_revision=source_revision, raw_source_signature=raw_source_signature,
        corpus_sig=corpus_sig,
    ), mintable_types=mintable_types)


def build_full_harvest(
    documents: Iterable[tuple[str, str]],
    *,
    domain: str,
    host_corpus: str,
    source_revision: str | None = None,
    raw_source_signature: str | None = None,
    corpus_sig: str | None = None,
) -> dict:
    """Every harvested surface with its ``df`` — the offline-analysis artifact.

    The build path cannot load this: :func:`jseval.entity_bank.validate_entity_bank`
    rejects its schema. It exists because an analysis may legitimately want the full
    distribution, and because :func:`trim_bank` is defined as a function of it.
    """
    entities, counts, n_docs = harvest_documents(documents, domain=domain)
    return {
        "schema": FULL_HARVEST_SCHEMA,
        "harvester_version": HARVESTER_VERSION,
        "host": {
            "corpus": host_corpus,
            "domain": domain,
            "n_docs": n_docs,
            "source_revision": source_revision,
            "raw_source_signature": raw_source_signature,
            "corpus_signature": corpus_sig,
        },
        "parameters": {
            "min_length": MIN_LENGTH,
            "max_length": MAX_LENGTH,
            "min_alpha_chars": MIN_ALPHA_CHARS,
            "alpha_filtered_types": list(ALPHA_FILTERED_TYPES),
            "types": list(ENTITY_TYPES),
            "priority": list(_PRIORITY),
            "length_band": {
                "min_ratio": LENGTH_BAND_MIN_RATIO,
                "max_ratio": LENGTH_BAND_MAX_RATIO,
                "max_resamples": MAX_RESAMPLES,
            },
        },
        "counts": {t: counts[t] for t in ENTITY_TYPES if t in counts},
        "entities": {t: entities[t] for t in ENTITY_TYPES if t in entities},
    }


def write_full_harvest(full: dict, out_path: Path | str) -> Path:
    """Write the offline-analysis dump. Never written into a committed bank directory."""
    path = Path(out_path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_bytes(full))
    return path


def write_bank(bank: dict, out_dir: Path | str) -> dict:
    """Write ``entity-bank.v2.json`` + its sha-pinned ``commitment.v1.json``."""
    root = Path(out_dir)
    root.mkdir(parents=True, exist_ok=True)
    payload = canonical_bytes(bank)
    (root / BANK_FILENAME).write_bytes(payload)
    commitment = {"schema": COMMITMENT_SCHEMA, "files": {BANK_FILENAME: sha256_bytes(payload)}}
    (root / COMMITMENT_FILENAME).write_bytes(canonical_bytes(commitment))
    return commitment


# --------------------------------------------------------------------------------------
# Offline driver / CLI
# --------------------------------------------------------------------------------------

def iter_text_files(docs_dir: Path | str, *, limit: int | None = None) -> Iterator[tuple[str, str]]:
    """``(doc_id, text)`` for ``*.txt`` under ``docs_dir``, in sorted-name order."""
    paths = sorted(Path(docs_dir).glob("*.txt"), key=lambda p: p.name)
    if limit is not None:
        paths = paths[:limit]
    for path in paths:
        yield path.name, path.read_text(encoding="utf-8", errors="replace")


def length_stats(rows: Sequence[dict]) -> dict:
    lengths = sorted(r["len"] for r in rows)
    if not lengths:
        return {}
    def pct(p: float) -> int:
        idx = min(len(lengths) - 1, max(0, int(round(p * (len(lengths) - 1)))))
        return lengths[idx]
    return {
        "min": lengths[0],
        "p5": pct(0.05),
        "median": int(statistics.median(lengths)),
        "p95": pct(0.95),
        "max": lengths[-1],
    }


def _main(argv: Sequence[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="jseval.entity_harvest", description=__doc__)
    ap.add_argument("--docs-dir", required=True)
    ap.add_argument("--domain", required=True, choices=list(DOMAINS))
    ap.add_argument("--host-corpus", default=None)
    ap.add_argument("--out", required=True)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--source-revision", default=None)
    ap.add_argument("--raw-source-signature", default=None)
    ap.add_argument(
        "--emit-full", default=None, metavar="PATH",
        help=(f"also write the full harvest ({FULL_HARVEST_FILENAME}-shaped) for offline "
              "analysis; not loadable by the build path"))
    ap.add_argument(
        "--mintable-types", default=None, metavar="T1,T2",
        help=(f"narrow the types the build path may mint from (subset of {MINTABLE_TYPES}); "
              "recorded in the committed bank's parameters.mintable_types. Omit to keep all."))
    args = ap.parse_args(argv)
    mintable_types = None
    if args.mintable_types:
        mintable_types = [t.strip().upper() for t in args.mintable_types.split(",") if t.strip()]
        unknown = sorted(set(mintable_types) - set(MINTABLE_TYPES))
        if unknown:
            ap.error(f"--mintable-types: unknown type(s) {unknown}; expected {MINTABLE_TYPES}")

    docs_dir = Path(args.docs_dir)
    paths = sorted(docs_dir.glob("*.txt"), key=lambda p: p.name)
    if args.limit is not None:
        paths = paths[: args.limit]
    started = time.perf_counter()
    full = build_full_harvest(
        iter_text_files(docs_dir, limit=args.limit),
        domain=args.domain,
        host_corpus=args.host_corpus or docs_dir.name,
        source_revision=args.source_revision,
        raw_source_signature=args.raw_source_signature,
        corpus_sig=corpus_signature(docs_dir, files=paths),
    )
    bank = trim_bank(full, mintable_types=mintable_types)
    elapsed = time.perf_counter() - started
    commitment = write_bank(bank, args.out)
    report = {
        "out": str(Path(args.out)),
        "n_docs": bank["host"]["n_docs"],
        "elapsed_sec": round(elapsed, 3),
        "docs_per_sec": round(bank["host"]["n_docs"] / elapsed, 2) if elapsed else None,
        "counts": bank["counts"],
        "length_stats": {t: length_stats(rows) for t, rows in sorted(full["entities"].items())},
        "exemplars_kept": {t: sum(len(v) for v in by_length.values())
                           for t, by_length in sorted(bank["exemplars"].items())},
        "collision_index_keys": bank["collision_index"]["count"],
        "commitment": commitment,
    }
    if args.emit_full:
        report["full_harvest"] = str(write_full_harvest(full, args.emit_full))
    sys.stdout.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(_main())
