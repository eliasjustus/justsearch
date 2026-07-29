"""Deterministic real-text host + fabricated-gold corpus assembly (tempdoc 707)."""

from __future__ import annotations

import hashlib
import json
import random
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from jseval.corpus_build import read_jsonl
from jseval.evidence_offset import locate_offset

METHOD = "real-text-injection-v1"

# Abbreviation-aware sentence splitting (tempdoc 767). The naive `(?<=[.!?])\s+`
# split shattered legal citations like "Anderson v. Liberty Lobby, Inc., 477
# U.S. 242, 248 (1986)" at "U.S." because it treats every period+whitespace as a
# sentence boundary regardless of what precedes it. `_interleave` then inserted
# fabricated sentences at those false boundaries, landing injected text INSIDE a
# real citation and corrupting host-document realism (the entire point of the
# real-text-injection corpus). Fix: a candidate boundary is suppressed (not
# treated as a sentence break) when the token immediately before the punctuation
# is a known abbreviation/title/single initial, or when it sits inside a
# digit.digit numeric sequence. Stdlib regex only — no NLP dependency.
#
# Lowercased, period-inclusive tokens as they appear immediately before a
# candidate boundary (e.g. "F." for the spaced reporter form "477 F. 2d 242").
_ABBREVIATIONS = frozenset({
    # legal reporters / court / citation abbreviations
    "u.s.", "u.s.c.", "f.2d.", "f.3d.", "f.", "ct.", "cal.", "n.y.", "inc.",
    "corp.", "co.", "ltd.", "no.", "id.", "ed.", "supp.", "cir.", "dist.",
    "fed.", "ass'n.", "v.",
    # titles
    "mr.", "mrs.", "ms.", "dr.", "hon.", "jr.", "sr.", "st.",
    # email / general prose
    "e.g.", "i.e.", "etc.", "vs.", "approx.",
})
# Single capital-letter initials — "J.", "A.", the middle initial in
# "GORDON J. QUIST" — are abbreviation-shaped independent of any fixed list.
_SINGLE_INITIAL_RE = re.compile(r"^[A-Z]\.$")
_BOUNDARY_RE = re.compile(r"[.!?]\s+")
_TRAILING_TOKEN_RE = re.compile(r"(\S+)$")
# Legal citations are routinely parenthesized/quoted — "(Fed. Cir. 1995)",
# "[Fed. Cir. 1995]", '"Fed. Cir."' — so the token immediately before a
# candidate boundary can carry leading punctuation ("(Fed.") that must be
# stripped before the abbreviation/initial lookup, or the lookup silently
# misses (found live: "(Fed." doesn't match "fed." in `_ABBREVIATIONS`,
# splitting "(Fed. Cir. 1995)" apart at "(Fed."). No trailing-punctuation
# strip is needed: the token slice already ends exactly at the matched
# `[.!?]`, so nothing ever trails it.
_LEADING_PUNCT_RE = re.compile(r"^[(\[{\"'‘’“”«»]+")


def _is_abbreviation_token(token: str) -> bool:
    token = _LEADING_PUNCT_RE.sub("", token)
    return token.lower() in _ABBREVIATIONS or bool(_SINGLE_INITIAL_RE.match(token))


def _split_sentences(text: str) -> list[str]:
    """Split `text` into sentences, suppressing false boundaries after
    abbreviations/titles/initials and inside digit.digit numeric sequences.

    Ambiguous-case tradeoff: "... filed in 1998 in the U.S. The court then
    ruled ..." is genuinely ambiguous for a regex-only splitter — "U.S." could
    end the sentence, or the next capitalized word could start a new one.
    Because `_ABBREVIATIONS` matches on the token alone (not on what follows),
    this implementation always suppresses the boundary after a known
    abbreviation, even when a new sentence legitimately follows. This is a
    deliberate one-sided choice: protecting host-document realism (never
    splitting — and therefore never injecting — INSIDE a citation/title) is
    the corpus's actual goal, and the cost is confined to under-splitting
    (two sentences occasionally get treated as one, which only affects where
    fabricated sentences may land, not host-text integrity) rather than
    over-splitting (which is what corrupted real citations in the first
    place). Perfect disambiguation would need sentence-final part-of-speech
    context, which is out of scope for a stdlib-regex splitter.
    """
    sentences = []
    start = 0
    for match in _BOUNDARY_RE.finditer(text):
        punct_end = match.start() + 1
        token_match = _TRAILING_TOKEN_RE.search(text[start:punct_end])
        if token_match and _is_abbreviation_token(token_match.group(1)):
            continue
        before_char = text[match.start() - 1] if match.start() > 0 else ""
        after_char = text[match.end()] if match.end() < len(text) else ""
        if before_char.isdigit() and after_char.isdigit():
            continue
        sentences.append(text[start:punct_end])
        start = match.end()
    sentences.append(text[start:])
    return sentences


def _canonical_digest(value) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _interleave(host: str, injection: str) -> str:
    host_sentences = [part.strip() for part in _split_sentences(host.strip()) if part.strip()]
    gold_sentences = [part.strip() for part in _split_sentences(injection.strip()) if part.strip()]
    if not host_sentences:
        return " ".join(gold_sentences)
    if not gold_sentences:
        return " ".join(host_sentences)
    positions = [
        max(1, min(len(host_sentences), round(index * len(host_sentences) / (len(gold_sentences) + 1))))
        for index in range(1, len(gold_sentences) + 1)
    ]
    result = []
    gold_index = 0
    for host_index, sentence in enumerate(host_sentences, 1):
        result.append(sentence)
        while gold_index < len(gold_sentences) and positions[gold_index] == host_index:
            result.append(gold_sentences[gold_index])
            gold_index += 1
    result.extend(gold_sentences[gold_index:])
    return " ".join(result)


# --- host (native) title synthesis (tempdoc 781 §B.1) -----------------------
#
# The defect (tempdoc 774 §J.7): `assemble` writes `title: ""` onto every native
# distractor while injected gold docs keep a populated `title`, so a field whose mere
# PRESENCE separates gold from native (`corpus_leak.field_selectivity_report`, J=1.0).
# The production lexical leg boosts title matches 3.0x (`TextQueryOps.TITLE_BOOST`,
# DisMax multi-field), so a gold-only title is an artifactual ranking edge by construction.
#
# Fix: give each native a plausible, non-answer-bearing title derived DETERMINISTICALLY
# from its OWN content plus the cell `seed`. Design constraints (all satisfied below):
#
#   * reacts ONLY to the host's own text + seed, never to corpus identity or the gold
#     text (D-005) — so it cannot become a covert answer-key channel. The gold text is
#     interleaved into the host body at assembly, but the title is synthesized from the
#     distractor's own `text`, which never contains gold, and gold docs are titled
#     separately (they keep the generated title).
#   * content-reactive detection, not host-type branching: an email exposes a `Subject:`
#     header line, a legal opinion exposes an opening sentence / caption. Reading the
#     structure off the text (rather than off "which corpus is this") keeps the routine
#     domain-agnostic and honours D-005.
#   * LF/UTF-8 safe: operates on the decoded `str`; `" ".join(base.split())` collapses any
#     embedded newline/tab so no control character reaches the one-line JSONL title.
#   * length overlaps the gold titles' word-count band (shape-class parity, not merely
#     presence parity): the generated gold titles run ~3-7 words ("The <descriptor>"),
#     so a per-host target in [MIN, MAX] words — seeded + content-hashed, so it varies
#     host-to-host the way real subjects/captions do — keeps the synthesized distribution
#     overlapping gold's rather than pinning every host to one length.
_SUBJECT_LINE_RE = re.compile(r"^[ \t]*subject[ \t]*:[ \t]*(.*)$", re.IGNORECASE | re.MULTILINE)
# A leading run of reply/forward markers ("Re: ", "Fwd: Re: ") is email plumbing, not
# title content — strip it so the synthesized title reads like a caption, not a header.
_REPLY_PREFIX_RE = re.compile(r"^(?:\s*(?:re|fw|fwd)\s*:\s*)+", re.IGNORECASE)
# A single leading "Label: " prefix (e.g. a header line that survives into the sentence
# fallback when a message's Subject is empty). Stripped so the fallback reads as content,
# not as a header token; a legal caption ("Anderson v. Liberty Lobby, Inc., ...") has no
# leading `word:` and is unaffected.
_LEADING_LABEL_RE = re.compile(r"^[A-Za-z][\w-]{0,30}:\s+")

#: Word-count band the synthesized title is truncated into. Calibrated to the generated
#: gold titles' observed 3-7-word span (`corpus_generate` "The <type> in the <place>" /
#: "The <entity>") so the two distributions overlap; a host base shorter than MIN is kept
#: whole (a real one-word subject is legitimately short — presence parity is unaffected).
_HOST_TITLE_MIN_WORDS = 3
_HOST_TITLE_MAX_WORDS = 8


def _synthesize_host_title(host_text: str, seed: int) -> str:
    """A deterministic, non-answer-bearing title for a native host doc (tempdoc 781 §B.1).

    Derived from ``host_text`` (the host's OWN content) and the cell ``seed`` only —
    never the corpus identity or the interleaved gold text (D-005). Returns ``""`` only
    when the host carries no extractable text at all (never, for a host that passed the
    ``host_min_words`` floor). See the block comment above for the design constraints.
    """
    text = host_text or ""
    base = ""
    subject = _SUBJECT_LINE_RE.search(text)
    if subject:
        base = _REPLY_PREFIX_RE.sub("", subject.group(1).strip()).strip()
    if not base:
        for sentence in _split_sentences(text.strip()):
            candidate = _LEADING_LABEL_RE.sub("", sentence.strip()).strip()
            if candidate:
                base = candidate
                break
    if not base:
        for line in text.splitlines():
            candidate = _LEADING_LABEL_RE.sub("", line.strip()).strip()
            if candidate:
                base = candidate
                break
    words = base.split()
    if not words:
        return ""
    # sha256 (not the per-process-randomized builtin `hash()`) keeps the per-host target
    # stable across interpreters, matching the ID-mint stream's domain-separation pattern.
    digest = hashlib.sha256(f"707-host-title:{seed}:{base}".encode("utf-8")).hexdigest()
    span = _HOST_TITLE_MAX_WORDS - _HOST_TITLE_MIN_WORDS + 1
    target = _HOST_TITLE_MIN_WORDS + (int(digest[:8], 16) % span)
    return " ".join(words[:target])


def _native_title(doc: dict, seed: int) -> str:
    """A distractor's title: keep a real host title verbatim when present, else synthesize
    one from the host's own content (the tempdoc 774 §J.7 leak fix). Real enron/CLERC hosts
    carry no title, so the synthesis path is what closes the presence asymmetry."""
    existing = doc.get("title")
    if isinstance(existing, str) and existing.strip():
        return existing
    return _synthesize_host_title(str(doc.get("text", "")), seed)


# --- native-shaped gold ID minting (tempdoc 767 §I.3) -----------------------
#
# The defect: `corpus_generate` derives a fabricated document's `_id` from its minted
# entity surface, so gold IDs read `breldac18` / `rosalint netherbham` while the real
# CLERC hosts around them are bare integers (`1000731`). Measured on the committed
# `datasets/mixed/en-legal-clerc-1k-verbose` cell: `^[a-z]+[0-9]+$` over document IDs
# alone selects 40/40 gold docs with zero false positives among 960 natives — a cleaner
# enumeration channel than the text boilerplate this lane was opened to close, and one
# that needs no document body at all (the agent-utility harness materializes each doc as
# `<doc_id>.txt`, so `ls` exposes it).
#
# Fix: gold IDs are minted AT ASSEMBLY TIME by perturbing the digit runs of a REAL host
# ID drawn from the same cell. Consequences that made this the chosen design over a
# per-domain `id_convention` parameter on `generate()`:
#
#   * collision-free BY CONSTRUCTION, not by luck — assembly is the first (and only)
#     point that sees the real host ID set. A generate-time convention parameter cannot
#     check for collisions, and `assemble`'s host pool silently DROPS a real doc whose
#     id equals a gold id, so a collision would corrupt a cell without any error.
#   * zero per-domain configuration — the convention is READ OFF the hosts, so CLERC
#     (`3578584`), Enron (`dasovich-j/dasovich-j/inbox/276.`) and MIRACL (`141#73`) are
#     each matched exactly, including a future host corpus nobody has parameterized.
#   * character-set safety is inherited, not asserted: a minted ID's characters are a
#     subset of a real ID's, and real IDs already serve as qrels TSV `corpus-id` fields
#     and as percent-encoded `<doc_id>.txt` filenames today. The explicit tab/newline
#     donor rejection below is belt-and-braces for a malformed host corpus.
#
# The tradeoff paid: `generate()`'s emitted (and git-committed) `fabricated-docs.jsonl`
# keeps entity-surface IDs, so the committed fabricated INPUT no longer shares its ID
# space with the assembled OUTPUT. The report's `gold_id_mapping` is what reconnects
# them, and it is part of the recipe a skeptic re-runs. The 635 standalone goldens,
# which have no host corpus at all, are untouched — every document there is fabricated,
# so ID shape separates nothing.
_DIGIT_RUN_RE = re.compile(r"\d+")

# A donor ID carrying any of these cannot be used: a tab or newline would corrupt the
# tab-separated `qrels/test.tsv` rows the ID is written into by `corpus_build`.
_ID_FORBIDDEN = ("\t", "\n", "\r")


#: How many trailing digits of each of a donor's digit runs get redrawn. Everything to
#: the left is copied from the real donor verbatim, which is what keeps a minted id
#: inside the host's actual VALUE range and not merely its character class. Redrawing a
#: whole run uniformly instead was measured to leak: CLERC's 8-digit ids all begin `1`
#: (they span 611..12706857), so uniform 8-digit draws produced `92448794` and the
#: id-shape gate correctly caught `not id.startswith('1')` at J=0.265 vs a 0.151 null.
#: Three digits give >=1000 candidates per donor per run — ample headroom for
#: collision-free rejection sampling — while leaving the whole prefix distribution
#: native by construction.
_ID_PERTURB_TAIL = 3


def _perturb_digit_runs(donor_id: str, rng: random.Random) -> str:
    """Redraw the trailing digits of every maximal digit run in ``donor_id``.

    Run length is preserved exactly, and every non-digit character is copied verbatim —
    which is what keeps the Enron path prefix (`dasovich-j/dasovich-j/inbox/`) and the
    MIRACL `#` separator native. A run no longer than :data:`_ID_PERTURB_TAIL` is
    redrawn whole, so its leading digit is drawn rather than inherited; that digit
    reproduces the donor's leading-zero-ness in both directions (see inline).

    tempdoc 748: a WHOLE-run redraw replaces the donor's value with a UNIFORM draw over
    the same digit width, which destroys the donor population's value distribution for
    that run. Measured on MIRACL-de (ids are ``<page-id>#<passage-index>``): the real
    1-digit passage index is Zipf-like (``#1`` 24%, ``#9`` 2.7%) while the redrawn gold
    index came out flat, so gold ids ran a mean trailing value of 28.97 against native
    10.49 and ``id_shape_report`` correctly caught ``not (trailing_int(id) <= 2)`` at
    J=0.208 against a 0.179 null. Fix: a run is only redrawn when the id has NO run with
    an inheritable head; when at least one run does, the short runs are copied verbatim
    from the donor and therefore carry a real value by construction. This is provably a
    no-op for any id with a SINGLE digit run — the whole CLERC id space (bare integers)
    and every Enron id observed (400/400 committed 781 host donors are single-run) — and
    changes only mixed-width multi-run ids, which is the defect's exact footprint.
    """
    runs = [m.group() for m in _DIGIT_RUN_RE.finditer(donor_id)]
    inheritable = any(len(run) > _ID_PERTURB_TAIL for run in runs)
    out = []
    cursor = 0
    for match in _DIGIT_RUN_RE.finditer(donor_id):
        out.append(donor_id[cursor:match.start()])
        run = match.group()
        if inheritable and len(run) <= _ID_PERTURB_TAIL:
            out.append(run)
            cursor = match.end()
            continue
        keep = max(0, len(run) - _ID_PERTURB_TAIL)
        head = run[:keep]
        tail = []
        for index in range(len(run) - keep):
            if index == 0 and not head and len(run) > 1:
                # No real head to inherit from, so the leading digit is drawn here and
                # must reproduce the donor's leading-zero-ness in BOTH directions: a
                # `0`-led run stays `0`-led (same magnitude), and a non-`0`-led run
                # never becomes one (a 7-digit id must not read as a 6-digit `0412993`).
                tail.append("0" if run[0] == "0" else str(rng.randrange(1, 10)))
            else:
                tail.append(str(rng.randrange(10)))
        out.append(head + "".join(tail))
        cursor = match.end()
    out.append(donor_id[cursor:])
    return "".join(out)


def mint_native_shaped_ids(
    native_ids: list[str], n: int, rng: random.Random, *, max_attempts: int = 10_000,
    reserved_ids: set[str] | None = None,
) -> list[str]:
    """Mint ``n`` IDs indistinguishable in shape from ``native_ids`` and disjoint from them.

    Deterministic given ``rng``'s state and the (order-insensitive) content of
    ``native_ids``: the donor pool is ``sorted()`` before any draw, so no ``set``
    iteration order and no ``hash()`` reaches the output — the two failure modes that
    break byte-equality across interpreters when ``PYTHONHASHSEED`` is unset, which this
    project never sets.

    ``native_ids`` is the population the minted ids must be indistinguishable FROM, and
    is therefore the donor pool. ``reserved_ids`` is an additional collision-exclusion
    set that contributes no donors — used by :func:`assemble` to keep a minted id off
    every real host id, including hosts that are not eligible to enter the cell.

    Fails CLOSED rather than degrading: a native ID set with no digit run anywhere offers
    no perturbable donor, and silently falling back to some other ID shape would
    reintroduce exactly the enumeration channel this function exists to close.
    """
    donors = sorted({
        i for i in native_ids
        if _DIGIT_RUN_RE.search(i) and not any(bad in i for bad in _ID_FORBIDDEN)
    })
    if not donors:
        raise ValueError(
            "cannot mint native-shaped gold ids: no host id carries a digit run to "
            "perturb (a fabricated-looking id shape would be an enumeration channel)"
        )
    # Donors are consumed WITHOUT replacement (one shuffled pass, cycling only if more
    # ids are needed than donors exist). Drawing with replacement was measured to leak:
    # two gold ids minted from the same donor share its whole prefix, and a
    # `startswith('15')` rule then separated 4/10 gold from 8/150 native at J=0.347
    # against a 0.264 null. Native ids do not repeat a prefix that way, so neither may
    # minted ones.
    order = list(donors)
    rng.shuffle(order)
    taken = set(native_ids) | set(reserved_ids or ())
    minted: list[str] = []
    attempts = 0
    while len(minted) < n:
        donor = order[len(minted) % len(order)]
        while True:
            attempts += 1
            if attempts > max_attempts:
                raise ValueError(
                    f"could not mint {n} collision-free native-shaped ids in "
                    f"{max_attempts} attempts (host id space too small: "
                    f"{len(donors)} donors)"
                )
            candidate = _perturb_digit_runs(donor, rng)
            if candidate not in taken:
                break
        taken.add(candidate)
        minted.append(candidate)
    return minted


def assemble(
    real_docs: list[dict],
    fabricated_docs: list[dict],
    queries: list[dict],
    *,
    seed: int,
    n_distractors: int,
    style: str = "interleave",
    host_min_words: int = 60,
) -> tuple[list[dict], list[dict], dict]:
    """Pure deterministic assembly; fabricated facts and query text remain unchanged.

    Returns ``(docs, queries, report)``. The returned queries are NEW dicts — the only
    field rewritten is ``evidence_ids``, remapped onto the native-shaped gold IDs minted
    by :func:`mint_native_shaped_ids` (see the block comment above). Query text, answers
    and every other field are copied through untouched; the inputs are not mutated.
    """
    if style not in {"append", "interleave"}:
        raise ValueError("style must be append or interleave")
    gold_ids = {evidence for query in queries for evidence in query.get("evidence_ids", [])}
    gold_docs = sorted(
        (doc for doc in fabricated_docs if doc.get("_id") in gold_ids),
        key=lambda doc: str(doc.get("_id")),
    )
    missing = sorted(gold_ids - {doc.get("_id") for doc in gold_docs})
    if missing:
        raise ValueError(f"fabricated queries reference missing gold docs: {missing}")

    pool = [
        doc for doc in real_docs
        if doc.get("_id") and len(str(doc.get("text", "")).split()) >= host_min_words
        and doc.get("_id") not in gold_ids
    ]
    pool.sort(key=lambda doc: str(doc["_id"]))
    random.Random(seed).shuffle(pool)
    needed = len(gold_docs) + n_distractors
    if len(pool) < needed:
        raise ValueError(f"need {needed} eligible real docs, found {len(pool)}")
    hosts = pool[:len(gold_docs)]
    distractors = pool[len(gold_docs):needed]

    # Gold IDs are drawn from the host corpus's OWN id space (tempdoc 767 §I.3). The
    # mint stream is domain-separated from `seed` by a sha256 digest rather than reusing
    # the pool-shuffle Random, so the two draws cannot be confounded; sha256 (not the
    # per-process-randomized builtin `hash()`) keeps it stable across interpreters.
    mint_seed = int(
        hashlib.sha256(f"707-gold-id-mint:{seed}".encode("utf-8")).hexdigest(), 16
    ) % (2**32)
    # Donors are the ELIGIBLE host population (`pool`), not every id in `real_docs`.
    # The two differ by the `host_min_words` filter, and on CLERC that filter is not
    # id-independent: short opinions skew to high ids, so the eligible natives that
    # actually enter a cell sit LOWER in the id range (median 4.07M) than the corpus at
    # large (4.25M). Minting from the unfiltered corpus therefore placed gold in a
    # slightly different numeric distribution from its own neighbours, and `id_shape`
    # caught it — `not (trailing_int(id) <= 5591422)` separated the 1k legal cell at
    # J=0.177 against a 0.151 null. Same seed, donors restricted to `pool`: J=0.081.
    # `pool` is independent of `n_distractors`, so the 1k and 10k cells still mint the
    # SAME ids and `queries_identical_across_sizes` is preserved. Collision exclusion
    # stays over every real id (`reserved_ids`), which is wider than the donor set.
    minted_ids = mint_native_shaped_ids(
        [str(doc["_id"]) for doc in pool],
        len(gold_docs),
        random.Random(mint_seed),
        reserved_ids={str(doc["_id"]) for doc in real_docs if doc.get("_id")},
    )
    # `gold_docs` is already sorted by original id, so the pairing is order-stable.
    id_map = {str(gold["_id"]): minted for gold, minted in zip(gold_docs, minted_ids)}

    injected = []
    host_mapping = []
    for gold, host in zip(gold_docs, hosts):
        host_text = str(host.get("text", ""))
        gold_text = str(gold.get("text", ""))
        text = host_text + "\n\n" + gold_text if style == "append" else _interleave(host_text, gold_text)
        minted = id_map[str(gold["_id"])]
        injected.append({"_id": minted, "title": gold.get("title", ""), "text": text})
        host_mapping.append({
            "gold_id": minted, "host_id": host["_id"], "fabricated_id": gold["_id"],
        })

    # Native distractors get a synthesized, non-answer-bearing title from their OWN content
    # (tempdoc 781 §B.1): real hosts carry `title: ""`, which made `title` PRESENCE a
    # gold-vs-native separator (J=1.0) that the 3.0x TITLE_BOOST lexical leg would reward.
    docs = injected + [
        {"_id": doc["_id"], "title": _native_title(doc, seed), "text": doc.get("text", "")}
        for doc in distractors
    ]
    remapped_queries = [
        {**query, "evidence_ids": [
            id_map.get(str(evidence), evidence) for evidence in query.get("evidence_ids", [])
        ]}
        for query in queries
    ]
    report = {
        "method": METHOD,
        "seed": seed,
        "style": style,
        "n_gold_docs": len(injected),
        "n_distractors": len(distractors),
        "host_min_words": host_min_words,
        "host_mapping": host_mapping,
        "gold_id_mapping": [
            {"fabricated_id": k, "assembled_id": id_map[k]} for k in sorted(id_map)
        ],
        "assembled_digest": _canonical_digest({"docs": docs, "queries": remapped_queries}),
    }
    return docs, remapped_queries, report


def evidence_offsets_for_injection(
    docs: list[dict], report: dict, fabricated_docs: list[dict],
) -> dict:
    """Record where each injected gold sentence lands in its host document (tempdoc 783 §B.1).

    The generator-metadata source (a) for the per-offset recall instrument. For every
    gold doc in ``report["host_mapping"]``, locate the FIRST fabricated gold sentence
    within the assembled (host+injection) text and record its character offset. This is
    the F-040-relevant deep offset — a bridge/answer sentence interleaved into a long real
    host document. Additive: a NEW sidecar artifact, never a change to committed corpus
    bytes (``docs.jsonl`` / ``queries.json`` are untouched).

    A gold sentence that cannot be relocated (should not happen — interleave places it
    verbatim) is omitted rather than recorded at a fabricated offset; the analysis fallback
    then resolves that doc by string-location, so nothing is silently faked.
    """
    fab_text = {str(d.get("_id")): str(d.get("text", "")) for d in fabricated_docs}
    doc_text = {str(d.get("_id")): str(d.get("text", "")) for d in docs}
    offsets: dict[str, dict] = {}
    for entry in report.get("host_mapping", []):
        assembled_id = str(entry.get("gold_id"))
        fabricated_id = str(entry.get("fabricated_id"))
        gold_sentences = [
            part.strip() for part in _split_sentences(fab_text.get(fabricated_id, "").strip())
            if part.strip()
        ]
        if not gold_sentences:
            continue
        first = gold_sentences[0]
        assembled = doc_text.get(assembled_id, "")
        off = locate_offset(assembled, first)
        if off is None:
            continue
        offsets[assembled_id] = {
            "char_offset": off,
            "doc_len": len(assembled),
            "evidence": first,
        }
    return {"schema": "evidence-offsets.v1", "method": "injection-assembly", "offsets": offsets}


def _cross_process_assembly(
    real_path: Path,
    fabricated_path: Path,
    queries_path: Path,
    *,
    seed: int,
    n_distractors: int,
    style: str,
    host_min_words: int,
    expected_docs: list[dict],
    expected_digest: str,
) -> dict:
    """Run the real assembly twice in independent interpreters and compare bytes."""
    request = {
        "real_path": str(real_path.resolve()),
        "fabricated_path": str(fabricated_path.resolve()),
        "queries_path": str(queries_path.resolve()),
        "seed": seed,
        "n_distractors": n_distractors,
        "style": style,
        "host_min_words": host_min_words,
    }
    outputs = []
    with tempfile.TemporaryDirectory() as temporary:
        root = Path(temporary)
        request_path = root / "request.json"
        request_path.write_text(json.dumps(request), encoding="utf-8")
        for index in range(2):
            output_path = root / f"result-{index}.json"
            completed = subprocess.run(
                [sys.executable, str(Path(__file__).resolve()), str(request_path), str(output_path)],
                check=False,
                capture_output=True,
                text=True,
            )
            if completed.returncode != 0:
                raise RuntimeError(
                    "cross-process real-text regeneration failed: "
                    f"{completed.stderr or completed.stdout}"
                )
            outputs.append(output_path.read_bytes())
    regenerated = [json.loads(body.decode("utf-8")) for body in outputs]
    passed = (
        outputs[0] == outputs[1]
        and all(item.get("docs") == expected_docs for item in regenerated)
        and all(
            (item.get("report") or {}).get("assembled_digest") == expected_digest
            for item in regenerated
        )
    )
    return {
        "passed": passed,
        "method": "cross-process-regeneration-diff",
        "digest": expected_digest if passed else None,
    }


def _assembly_worker(request_path: Path, output_path: Path) -> None:
    request = json.loads(request_path.read_text(encoding="utf-8"))
    queries = json.loads(Path(request["queries_path"]).read_text(encoding="utf-8"))
    for index, query in enumerate(queries, 1):
        query.setdefault("query_variant", "verbose")
        query.setdefault("query_family_id", f"q{index:04d}")
    docs, _queries, report = assemble(
        read_jsonl(Path(request["real_path"])),
        read_jsonl(Path(request["fabricated_path"])),
        queries,
        seed=int(request["seed"]),
        n_distractors=int(request["n_distractors"]),
        style=request["style"],
        host_min_words=int(request["host_min_words"]),
    )
    output_path.write_text(
        json.dumps({"docs": docs, "report": report}, sort_keys=True, separators=(",", ":")),
        encoding="utf-8",
    )


def build_source(
    real_corpus_dir: str | Path,
    gold_source_dir: str | Path,
    output_source_dir: str | Path,
    *,
    seed: int,
    n_distractors: int,
    style: str,
    real_source_id: str,
    license_id: str,
    host_min_words: int = 60,
) -> dict:
    """Build transient source shape and prove same-input assembly determinism twice."""
    real_root = Path(real_corpus_dir)
    real_path = real_root / "corpus.jsonl"
    if not real_path.is_file():
        real_path = real_root / "docs.jsonl"
    gold_root = Path(gold_source_dir)
    real_docs = read_jsonl(real_path)
    fabricated_docs = read_jsonl(gold_root / "docs.jsonl")
    queries = json.loads((gold_root / "queries.json").read_text(encoding="utf-8"))
    for index, query in enumerate(queries, 1):
        query.setdefault("query_variant", "verbose")
        query.setdefault("query_family_id", f"q{index:04d}")
    gold_meta = json.loads((gold_root / "meta.json").read_text(encoding="utf-8"))

    docs, queries, report = assemble(
        real_docs, fabricated_docs, queries, seed=seed, n_distractors=n_distractors,
        style=style, host_min_words=host_min_words,
    )
    determinism = _cross_process_assembly(
        real_path,
        gold_root / "docs.jsonl",
        gold_root / "queries.json",
        seed=seed,
        n_distractors=n_distractors,
        style=style,
        host_min_words=host_min_words,
        expected_docs=docs,
        expected_digest=report["assembled_digest"],
    )
    deterministic = determinism["passed"]
    provenance = {
        **report,
        "real_source_id": real_source_id,
        "real_source_sha256": hashlib.sha256(real_path.read_bytes()).hexdigest(),
        "license": license_id,
        "fabrication_provenance": gold_meta.get("generation_provenance"),
        "assembly_determinism": determinism,
    }
    if not deterministic:
        raise RuntimeError("real-text injection assembly is nondeterministic")

    output = Path(output_source_dir)
    output.mkdir(parents=True, exist_ok=True)
    (output / "docs.jsonl").write_text(
        "".join(json.dumps(doc, ensure_ascii=False) + "\n" for doc in docs), encoding="utf-8"
    )
    (output / "queries.json").write_text(
        json.dumps(queries, ensure_ascii=False, indent=1), encoding="utf-8"
    )
    meta = {
        "version": "1.0",
        "type_axis": gold_meta.get("type_axis", "prose"),
        "suite": "707-real-text-injection",
        "contamination_class": "private-synthetic",
        "generation_provenance": provenance,
    }
    (output / "meta.json").write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    # tempdoc 783 §B.1: additive evidence-offset sidecar (generator-metadata source for the
    # per-offset recall instrument). Records where each injected gold sentence lands in its
    # host doc; NOT committed by write_commitment and never touches docs.jsonl/queries.json.
    side = evidence_offsets_for_injection(docs, report, fabricated_docs)
    (output / "evidence_offsets.json").write_text(
        json.dumps(side, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    return meta


def write_commitment(
    commitment_dir: str | Path,
    gold_source_dir: str | Path,
    provenance: dict,
) -> Path:
    """Commit only recipe, fabricated inputs, and host IDs; never real host text."""
    root = Path(commitment_dir)
    root.mkdir(parents=True, exist_ok=True)
    gold_root = Path(gold_source_dir)
    recipe = dict(provenance)
    recipe.pop("fabrication_provenance", None)
    # newline="\n": these files are git-committed under eol=lf normalization — a
    # platform-default CRLF write makes the recorded sha256 unmatchable from any
    # fresh checkout forever (the 2026-07-13 manifests baked this in on all 8 cells).
    (root / "recipe.json").write_text(
        json.dumps(recipe, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    for name in ("docs.jsonl", "queries.json", "meta.json"):
        (root / f"fabricated-{name}").write_bytes((gold_root / name).read_bytes())
    committed = [
        "recipe.json", "fabricated-docs.jsonl", "fabricated-queries.json",
        "fabricated-meta.json",
    ]
    manifest = {
        "schema": "707-corpus-commitment.v1",
        "files": {
            name: hashlib.sha256((root / name).read_bytes()).hexdigest()
            for name in committed
        },
    }
    (root / "commitment.v1.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    return root


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("internal usage: python -m jseval.corpus_inject REQUEST RESULT")
    _assembly_worker(Path(sys.argv[1]), Path(sys.argv[2]))
