"""Deterministic committed query strata for tempdoc 707 corpus members."""
from __future__ import annotations

import json
import re
import shutil
from pathlib import Path

TOOL_VERSION = "jseval.corpus_query_strata/1"


def _short_natural(query: str, language: str) -> str:
    if language == "en":
        match = re.fullmatch(
            r"What is the value associated with the designer of the (.+)\?", query)
        if not match:
            raise ValueError(f"unsupported English verbose query template: {query!r}")
        candidate = f"What value identifies {match.group(1)}'s designer?"
    elif language == "de":
        match = re.fullmatch(
            r"Folgt man den Verknüpfungen ausgehend vom Standort (.+), "
            r"mit welchem Wert ist die letzte Entität verbunden\?",
            query,
        )
        if not match:
            raise ValueError(f"unsupported German verbose query template: {query!r}")
        candidate = f"Welcher Wert folgt ab Standort {match.group(1)}?"
    else:
        raise ValueError("language must be 'en' or 'de'")
    words = candidate.split()
    if not 5 <= len(words) <= 12:
        raise ValueError(
            f"short-natural query must contain 5-12 words, got {len(words)}: {candidate!r}")
    return candidate


def build_short_natural_source(
    source_dir: str | Path,
    output_dir: str | Path,
    *,
    language: str,
) -> dict:
    """Rewrite only query text; preserve answers, evidence, and fabricated docs."""
    source = Path(source_dir)
    output = Path(output_dir)
    queries = json.loads((source / "queries.json").read_text(encoding="utf-8"))
    rewritten = []
    for index, query in enumerate(queries, 1):
        item = dict(query)
        item["query"] = _short_natural(str(query["query"]), language)
        item["query_variant"] = "short-natural"
        item["query_family_id"] = f"q{index:04d}"
        rewritten.append(item)

    output.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / "docs.jsonl", output / "docs.jsonl")
    metadata = json.loads((source / "meta.json").read_text(encoding="utf-8"))
    metadata["query_variant_provenance"] = {
        "variant": "short-natural",
        "language": language,
        "method": "deterministic-natural-template",
        "tool_version": TOOL_VERSION,
        "source": source.name,
    }
    # newline="\n": these become git-committed gold inputs (fabricated-*) under eol=lf
    # normalization — platform-default CRLF here is what baked unmatchable sha256s into
    # the short-natural cells' 2026-07-13 commitment manifests.
    (output / "queries.json").write_text(
        json.dumps(rewritten, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    (output / "meta.json").write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return {
        "variant": "short-natural",
        "language": language,
        "query_count": len(rewritten),
        "output": str(output),
    }
