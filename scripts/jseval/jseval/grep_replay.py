"""Offline grep-replay probe for the agent-utility eval corpora (tempdoc 767).

The agent-utility eval scores a search engine (arm B) against a **grep-only
baseline agent** (arm A: no search tool, only file listing, ``grep`` and
``Read``). A behavioural asymmetry was measured on the 2026-07-21 confirm2
campaign at the 1k scale:

===============================  =============  =============
metric                           email arm A    legal arm A
===============================  =============  =============
accuracy                         0.533          0.033
cells opening NEITHER gold doc   6/60           43/60
mean synthetic docs read / cell  9.33           0.98
===============================  =============  =============

The grep baseline cruises on email and drowns on legal. This module answers
"would a rebuilt corpus change that?" **without spending a paid campaign**, by
replaying the search terms real agents actually issued against a corpus offline.

**The terms are not invented.** :func:`extract_search_terms` recovers them from
the ``tool_calls`` metadata the eval log already records — ``Grep`` patterns,
``grep`` invocations inside ``Bash`` commands, ``Glob`` patterns and PowerShell
``Select-String -Pattern`` arguments. A probe built on a plausible-looking
hand-written grep strategy would measure the author's assumption; this one
measures observed behaviour. Every function here is pure and deterministic given
its inputs.

**Declare your unit — every report records ``matching_mode`` and ``unit``.** A
prior measurement in this project was badly wrong because it used a SUBSTRING
match ("spa" matched "newspaper") and compared one corpus counted per-FILE
against another counted per-LINE. This module matches on TOKEN BOUNDARIES only
(``_TOKEN_RE`` captures whole ``[a-z0-9']+`` runs via ``re.findall``, so a short
term can never match inside a longer token) and counts PER DOCUMENT on both
sides of every ratio, mirroring :mod:`jseval.corpus_leak`.

**The reachability rule is an explicit parameter, not a buried constant.**
:func:`grep_reachability_report` takes ``flood_threshold`` — the largest match
set an agent is modelled as willing to open — and
:func:`reachability_sensitivity_report` sweeps it, so a conclusion can be read
against the threshold that produced it rather than resting on one hard-coded
number.
"""

from __future__ import annotations

import re
import shlex

TOOL_VERSION = "jseval.grep_replay/1"

# Token-boundary word regex — identical convention to corpus_leak._TOKEN_RE.
# Always used via re.findall against a lowercased string, so the whole run of
# [a-z0-9'] characters is captured; "spa" cannot match inside "newspaper".
_TOKEN_RE = re.compile(r"[a-z0-9']+")

# Default flood thresholds swept by reachability_sensitivity_report. A term
# matching 400 of 1000 documents tells an agent nothing it could act on; a term
# matching 3 is an answer. The sweep exists so the verdict is never an artifact
# of one setting.
DEFAULT_FLOOD_THRESHOLDS = (5, 10, 20, 50, 100)

# Tokens that appear only because a search term was lifted out of a shell
# command line: filesystem path components of the staged corpus directory, the
# file extension, and shell/grep noise. Dropping them stops a path fragment from
# being scored as if the agent had searched for it.
_PATH_NOISE = frozenset({
    "c", "users", "elias", "appdata", "local", "temp", "tmp", "jseval",
    "corpus", "stage", "dir", "txt", "md", "json", "file", "files", "path",
    "null", "dev", "head", "tail", "sort", "uniq", "wc", "cat", "ls", "grep",
    "findstr", "select", "string", "get", "childitem", "content", "object",
    "where", "foreach", "name", "fullname", "filter", "pattern", "quiet",
    "erroraction", "silentlycontinue", "unique", "first", "property", "item",
})

# Search-like tools whose input carries a pattern we can recover.
_GREP_TOOLS = frozenset({"Grep", "Glob", "Bash", "PowerShell"})

# Flags that consume the following argv token, so the value after them is an
# option value rather than the grep pattern.
_GREP_VALUE_FLAGS = frozenset({"-e", "-f", "-m", "-A", "-B", "-C", "--include", "--exclude"})

_PS_PATTERN_RE = re.compile(r"Select-String\s+(?:-Path\s+\S+\s+)?(?:-Pattern\s+)?([\"'])(.*?)\1", re.IGNORECASE)
_PS_MATCH_RE = re.compile(r"-Pattern\s+([\"'])(.*?)\1", re.IGNORECASE)


def _tokenize(text: str) -> list[str]:
    """Lowercase + extract ``[a-z0-9']+`` runs — the single tokenization rule.

    Token-boundary by construction: ``re.findall`` captures whole runs, so a
    short term is never matched as a substring of a longer unrelated token.
    """
    return _TOKEN_RE.findall((text or "").lower())


def _doc_text(doc: dict) -> str:
    return " ".join(part for part in (doc.get("title") or "", doc.get("text") or "") if part)


def _pattern_tokens(pattern: str) -> list[str]:
    """Content tokens carried by one search pattern.

    Regex metacharacters (``|``, ``.*``, character classes) are simply not
    matched by ``_TOKEN_RE``, so an alternation like ``upper wetlands|power
    station`` decomposes to its four content tokens. Path noise and 1-character
    tokens are dropped; a 1-character grep term is not a term an agent used to
    locate a document.
    """
    return [t for t in _tokenize(pattern) if len(t) > 1 and t not in _PATH_NOISE]


def _bash_grep_patterns(command: str) -> list[str]:
    """Recover the pattern argument of every ``grep``/``findstr`` in a shell command.

    Takes the first non-flag argument after each ``grep`` token — the pattern
    position — so file paths and glob arguments that follow it are excluded
    rather than tokenized as if the agent had searched for them.
    """
    try:
        argv = shlex.split(command, posix=True)
    except ValueError:
        return []
    patterns: list[str] = []
    i = 0
    while i < len(argv):
        head = argv[i].rsplit("/", 1)[-1]
        if head in ("grep", "egrep", "fgrep", "rg", "findstr"):
            j = i + 1
            while j < len(argv):
                arg = argv[j]
                if arg in _GREP_VALUE_FLAGS:
                    # The value of -e IS the pattern; other value-flags are not.
                    if arg == "-e" and j + 1 < len(argv):
                        patterns.append(argv[j + 1])
                    j += 2
                    continue
                if arg.startswith("-") and len(arg) > 1:
                    j += 1
                    continue
                patterns.append(arg)
                break
            i = j
        i += 1
    return patterns


def extract_search_terms(tool_calls: list[dict] | None) -> list[str]:
    """Content tokens of every search term issued in ``tool_calls``.

    ``tool_calls`` is the ``sample.metadata['tool_calls']`` list an
    agent-utility eval log records: dicts of ``{"tool": str, "input": dict}``.
    Recovers patterns from ``Grep`` (``input['pattern']``), ``Glob``
    (``input['pattern']``), ``Bash`` (``grep`` invocations inside
    ``input['command']``) and ``PowerShell`` (``Select-String``). ``Read`` calls
    carry no search term and are ignored — the prior analysis of these logs
    extracted only ``Read``, which is why the terms looked unrecoverable.

    Returns a sorted, de-duplicated token list so the output is deterministic
    and directly usable as a ``terms_by_query`` value.
    """
    out: set[str] = set()
    for call in tool_calls or []:
        tool = call.get("tool")
        if tool not in _GREP_TOOLS:
            continue
        inp = call.get("input") or {}
        if tool in ("Grep", "Glob"):
            out.update(_pattern_tokens(str(inp.get("pattern") or "")))
        elif tool == "Bash":
            for pat in _bash_grep_patterns(str(inp.get("command") or "")):
                out.update(_pattern_tokens(pat))
        elif tool == "PowerShell":
            cmd = str(inp.get("command") or "")
            for rx in (_PS_PATTERN_RE, _PS_MATCH_RE):
                for _, pat in rx.findall(cmd):
                    out.update(_pattern_tokens(pat))
    return sorted(out)


def build_index(docs: list[dict]) -> dict[str, frozenset[str]]:
    """Token -> set of document ``_id``s containing it (token-boundary, per-document).

    Built once and shared across every query in a report: a term's match set is
    a dict lookup rather than a corpus rescan, which is what makes sweeping
    several thresholds over 20 queries cheap. A token absent from the index has
    an empty match set, so callers need no separate "unknown token" branch.
    """
    index: dict[str, set[str]] = {}
    for d in docs:
        doc_id = str(d.get("_id"))
        for tok in set(_tokenize(_doc_text(d))):
            index.setdefault(tok, set()).add(doc_id)
    return {t: frozenset(ids) for t, ids in index.items()}


def term_match_sets(
    docs: list[dict], terms: list[str], *, index: dict[str, frozenset[str]] | None = None
) -> dict[str, frozenset[str]]:
    """Map each term to the set of document ``_id``s whose title+text contains it.

    Token-boundary, per-document: a document is counted once no matter how many
    times the term occurs in it. This is the unit used on both sides of every
    ratio in this module. Pass a prebuilt ``index`` from :func:`build_index` to
    avoid rescanning the corpus per call; the result is identical either way.
    """
    idx = index if index is not None else build_index(docs)
    return {t: idx.get(t, frozenset()) for t in sorted(set(terms))}


def query_reachability(
    docs: list[dict],
    query: dict,
    terms: list[str],
    *,
    flood_threshold: int,
    require_all_evidence: bool = False,
    index: dict[str, frozenset[str]] | None = None,
) -> dict:
    """Would a grep-only agent issuing ``terms`` plausibly reach this query's gold docs?

    The reachability rule, stated in full:

    1. A term's **match set** is the set of documents containing it
       (token-boundary, per-document).
    2. A term is **usable** if its match set is non-empty and no larger than
       ``flood_threshold`` — the modelled cap on how many documents an agent is
       willing to open. A term matching 400 of 1000 documents is a flood: it is
       technically a hit and practically useless.
    3. A gold document is **reachable** if at least one usable term matches it.
    4. The query is reachable if ANY of its gold documents is reachable, or ALL
       of them when ``require_all_evidence`` is set.

    ``flood_threshold`` is required and has no default here on purpose — the
    caller must state the rule it is measuring under.
    """
    match_sets = term_match_sets(docs, terms, index=index)
    evidence = [str(e) for e in (query.get("evidence_ids") or [])]

    usable = {t: m for t, m in match_sets.items() if 0 < len(m) <= flood_threshold}
    flooded = {t: len(m) for t, m in match_sets.items() if len(m) > flood_threshold}

    reached: list[str] = []
    for gold_id in evidence:
        if any(gold_id in m for m in usable.values()):
            reached.append(gold_id)

    if not evidence:
        verdict = None
    elif require_all_evidence:
        verdict = len(reached) == len(evidence)
    else:
        verdict = len(reached) > 0

    return {
        "query_id": query.get("query_family_id"),
        "n_evidence": len(evidence),
        "n_evidence_reached": len(reached),
        "evidence_reached": sorted(reached),
        "n_terms": len(match_sets),
        "n_terms_usable": len(usable),
        "n_terms_flooded": len(flooded),
        "n_terms_zero_hit": sum(1 for m in match_sets.values() if not m),
        "match_set_sizes": {t: len(m) for t, m in sorted(match_sets.items())},
        "reachable": verdict,
        "flood_threshold": flood_threshold,
        "require_all_evidence": require_all_evidence,
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "grep-replay-query-reachability",
    }


def _distribution(values: list[int]) -> dict:
    if not values:
        return {"min": 0, "median": 0, "max": 0, "mean": 0.0, "n": 0}
    s = sorted(values)
    mid = len(s) // 2
    median = s[mid] if len(s) % 2 else (s[mid - 1] + s[mid]) / 2
    return {"min": s[0], "median": median, "max": s[-1], "mean": sum(s) / len(s), "n": len(s)}


def grep_reachability_report(
    docs: list[dict],
    queries: list[dict],
    terms_by_query: dict[str, list[str]],
    *,
    flood_threshold: int = 20,
    require_all_evidence: bool = False,
    index: dict[str, frozenset[str]] | None = None,
) -> dict:
    """Corpus-level grep-replay summary: what fraction of queries' gold is reachable?

    ``terms_by_query`` maps a query key to the terms real agents issued for it.
    Keys are matched against ``query['query_family_id']`` first and then the
    query's positional id ``q<index>`` (the ``arm|qid`` form eval sample ids
    use), so either convention works without the caller re-keying.

    ``passed`` is ``True`` when a majority of queries are reachable — i.e. the
    corpus does not structurally defeat a grep-only agent. It is ``None`` when
    there is nothing to judge (no queries, or no terms recovered), never
    ``True``: an absent measurement is not a passing one.
    """
    idx_map = index if index is not None else build_index(docs)
    per_query: list[dict] = []
    for idx, q in enumerate(queries):
        key = q.get("query_family_id")
        terms = terms_by_query.get(key)
        if terms is None:
            terms = terms_by_query.get(f"q{idx}")
        if terms is None:
            terms = []
        per_query.append(query_reachability(
            docs, q, terms,
            flood_threshold=flood_threshold,
            require_all_evidence=require_all_evidence,
            index=idx_map,
        ))

    judged = [r for r in per_query if r["reachable"] is not None]
    n_reachable = sum(1 for r in judged if r["reachable"])
    all_sizes = [n for r in per_query for n in r["match_set_sizes"].values()]
    covered = [r for r in per_query if r["n_terms"] > 0]

    gold_ids = {str(e) for q in queries for e in (q.get("evidence_ids") or [])}
    n_native = sum(1 for d in docs if str(d.get("_id")) not in gold_ids)

    return {
        "n_queries": len(queries),
        "n_queries_with_terms": len(covered),
        "n_gold": len(gold_ids),
        "n_native": n_native,
        "n_docs": len(docs),
        "n_reachable": n_reachable,
        "reachable_fraction": (n_reachable / len(judged)) if judged else 0.0,
        "match_set_size_distribution": _distribution(all_sizes),
        "mean_terms_per_query": (
            sum(r["n_terms"] for r in per_query) / len(per_query) if per_query else 0.0),
        "mean_usable_terms_per_query": (
            sum(r["n_terms_usable"] for r in per_query) / len(per_query) if per_query else 0.0),
        "per_query": per_query,
        "flood_threshold": flood_threshold,
        "require_all_evidence": require_all_evidence,
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "grep-replay-reachability",
        "passed": (n_reachable / len(judged) > 0.5) if (judged and covered) else None,
    }


def reachability_sensitivity_report(
    docs: list[dict],
    queries: list[dict],
    terms_by_query: dict[str, list[str]],
    *,
    flood_thresholds: tuple[int, ...] = DEFAULT_FLOOD_THRESHOLDS,
    require_all_evidence: bool = False,
) -> dict:
    """:func:`grep_reachability_report` swept across ``flood_thresholds``.

    A reachability verdict that flips between neighbouring thresholds is an
    artifact of the threshold, not a property of the corpus. This report exists
    so that distinction is visible in the output rather than left to the reader.
    """
    idx_map = build_index(docs)
    by_threshold = {
        t: grep_reachability_report(
            docs, queries, terms_by_query, flood_threshold=t,
            require_all_evidence=require_all_evidence, index=idx_map)
        for t in flood_thresholds
    }
    fractions = {t: r["reachable_fraction"] for t, r in by_threshold.items()}
    values = sorted(fractions.values())
    return {
        "flood_thresholds": list(flood_thresholds),
        "reachable_fraction_by_threshold": fractions,
        "reachable_fraction_spread": (values[-1] - values[0]) if values else 0.0,
        "by_threshold": by_threshold,
        "require_all_evidence": require_all_evidence,
        "matching_mode": "token-boundary",
        "unit": "per-document",
        "method": "grep-replay-reachability-sensitivity",
    }
