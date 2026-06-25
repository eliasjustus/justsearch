"""
Phase 1 eval: Metadata filtering ground truth verification.

Parses frontmatter from the MultiHop-RAG corpus, builds expected counts,
then verifies against a live JustSearch backend via facet and filter queries.

Usage:
  # Build ground truth only (no backend needed):
  python -m jseval.metadata_eval ground-truth --corpus-dir tmp-multihop-corpus

  # Verify against live backend:
  python -m jseval.metadata_eval verify --corpus-dir tmp-multihop-corpus \
    --base-url http://127.0.0.1:33221
"""

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path

import requests


def parse_frontmatter(content: str) -> dict[str, str]:
    """Extract YAML frontmatter key-value pairs from markdown content."""
    if not content.startswith("---"):
        return {}
    end = content.find("\n---", 3)
    if end < 0:
        return {}
    raw = content[4:end]
    result = {}
    for line in raw.split("\n"):
        line = line.strip()
        if not line or ":" not in line:
            continue
        key, _, value = line.partition(":")
        key = key.strip()
        value = value.strip().strip('"').strip("'")
        if value:
            result[key] = value
    return result


def build_ground_truth(corpus_dir: Path) -> dict:
    """Parse all articles and build expected metadata counts."""
    sources: Counter = Counter()
    authors: Counter = Counter()
    categories: Counter = Counter()
    dates: list[str] = []
    total = 0
    skipped_authors = 0

    for f in sorted(corpus_dir.glob("article_*.md")):
        content = f.read_text(encoding="utf-8")
        fm = parse_frontmatter(content)
        if not fm:
            continue
        total += 1

        src = fm.get("source", "")
        if src:
            sources[src.lower()] += 1

        author = fm.get("author", "")
        if author and author.lower() != "none":
            authors[author.lower()] += 1
        elif author.lower() == "none":
            skipped_authors += 1

        cat = fm.get("category", "")
        if cat:
            categories[cat.lower()] += 1

        pub = fm.get("published_at", "")
        if pub:
            dates.append(pub)

    # October 2023 articles
    oct_2023 = sum(1 for d in dates if d.startswith("2023-10"))

    return {
        "total_articles": total,
        "sources": dict(sources.most_common()),
        "authors": dict(authors.most_common()),
        "categories": dict(categories.most_common()),
        "skipped_none_authors": skipped_authors,
        "october_2023_count": oct_2023,
        "distinct_sources": len(sources),
        "distinct_authors": len(authors),
        "distinct_categories": len(categories),
    }


def verify_facets(base_url: str, ground_truth: dict) -> list[str]:
    """Verify facet counts against ground truth. Returns list of failures."""
    failures = []

    # Query facets
    resp = requests.post(
        f"{base_url}/api/knowledge/search",
        json={
            "query": "*:*", "querySyntax": "LUCENE",
            "limit": 1,
            "facets": {
                "include": True,
                "fields": [
                    {"field": "meta_source", "size": 100},
                    {"field": "meta_category", "size": 20},
                    {"field": "meta_author", "size": 500},
                ],
            },
        },
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()
    facets = data.get("facets", {})

    # Verify source facet
    source_facets = facets.get("meta_source", {})
    gt_sources = ground_truth["sources"]
    if len(source_facets) != len(gt_sources):
        failures.append(
            f"meta_source distinct count: got {len(source_facets)}, "
            f"expected {len(gt_sources)}"
        )
    for value, expected_count in gt_sources.items():
        actual = source_facets.get(value, 0)
        if actual != expected_count:
            failures.append(
                f"meta_source '{value}': got {actual}, expected {expected_count}"
            )

    # Verify category facet
    cat_facets = facets.get("meta_category", {})
    gt_cats = ground_truth["categories"]
    if len(cat_facets) != len(gt_cats):
        failures.append(
            f"meta_category distinct count: got {len(cat_facets)}, "
            f"expected {len(gt_cats)}"
        )
    for value, expected_count in gt_cats.items():
        actual = cat_facets.get(value, 0)
        if actual != expected_count:
            failures.append(
                f"meta_category '{value}': got {actual}, expected {expected_count}"
            )

    return failures


def verify_filter_precision(base_url: str, field: str, value: str, expected_count: int) -> list[str]:
    """Verify that a filter returns exactly the expected count with 100% precision."""
    failures = []
    resp = requests.post(
        f"{base_url}/api/knowledge/search",
        json={
            "query": "*:*", "querySyntax": "LUCENE",
            "limit": expected_count + 10,
            "filters": {field: [value]},
        },
        timeout=15,
    )
    resp.raise_for_status()
    data = resp.json()
    actual = data.get("totalHits", 0)
    if actual != expected_count:
        failures.append(
            f"Filter {field}=['{value}']: got {actual} hits, expected {expected_count}"
        )
    return failures


def verify_case_insensitive(base_url: str) -> list[str]:
    """Verify that original-case and lowercase produce same results."""
    failures = []
    for original, lower in [("The Verge", "the verge"), ("TechCrunch", "techcrunch")]:
        r1 = requests.post(
            f"{base_url}/api/knowledge/search",
            json={"query": "*:*", "querySyntax": "LUCENE", "limit": 1, "filters": {"meta_source": [original]}},
            timeout=15,
        )
        r2 = requests.post(
            f"{base_url}/api/knowledge/search",
            json={"query": "*:*", "querySyntax": "LUCENE", "limit": 1, "filters": {"meta_source": [lower]}},
            timeout=15,
        )
        r1.raise_for_status()
        r2.raise_for_status()
        h1 = r1.json().get("totalHits", -1)
        h2 = r2.json().get("totalHits", -2)
        if h1 != h2:
            failures.append(
                f"Case sensitivity: '{original}' got {h1} hits, '{lower}' got {h2}"
            )
    return failures


def verify_cross_filter(base_url: str, ground_truth: dict) -> list[str]:
    """Verify that combining two filters produces the intersection."""
    failures = []
    # We can't easily compute the exact intersection from our counter-based
    # ground truth, so just verify the count is <= min of both individual counts
    # and > 0 (sanity check that the intersection isn't empty for known-overlapping values)
    resp = requests.post(
        f"{base_url}/api/knowledge/search",
        json={
            "query": "*:*", "querySyntax": "LUCENE",
            "limit": 1,
            "filters": {"meta_source": ["the verge"], "meta_category": ["technology"]},
        },
        timeout=15,
    )
    resp.raise_for_status()
    combined = resp.json().get("totalHits", 0)
    verge_count = ground_truth["sources"].get("the verge", 0)
    tech_count = ground_truth["categories"].get("technology", 0)

    if combined > min(verge_count, tech_count):
        failures.append(
            f"Cross-filter: {combined} > min({verge_count}, {tech_count})"
        )
    if combined == 0 and verge_count > 0 and tech_count > 0:
        failures.append(
            f"Cross-filter: 0 results for the verge + technology (both have docs)"
        )
    return failures


def cmd_ground_truth(args):
    corpus_dir = Path(args.corpus_dir)
    gt = build_ground_truth(corpus_dir)
    print(json.dumps(gt, indent=2))
    print(f"\n--- Summary ---")
    print(f"Total articles: {gt['total_articles']}")
    print(f"Distinct sources: {gt['distinct_sources']}")
    print(f"Distinct authors: {gt['distinct_authors']}")
    print(f"Distinct categories: {gt['distinct_categories']}")
    print(f"Skipped 'None' authors: {gt['skipped_none_authors']}")
    print(f"October 2023 articles: {gt['october_2023_count']}")

    if args.output:
        Path(args.output).write_text(json.dumps(gt, indent=2))
        print(f"\nGround truth written to: {args.output}")


def cmd_verify(args):
    corpus_dir = Path(args.corpus_dir)
    base_url = args.base_url.rstrip("/")

    print("Building ground truth from corpus...")
    gt = build_ground_truth(corpus_dir)
    print(f"  {gt['total_articles']} articles, {gt['distinct_sources']} sources, "
          f"{gt['distinct_categories']} categories")

    all_failures = []

    print("\n1. Verifying facet counts...")
    failures = verify_facets(base_url, gt)
    all_failures.extend(failures)
    print(f"   {'PASS' if not failures else f'FAIL ({len(failures)} issues)'}")
    for f in failures:
        print(f"   - {f}")

    print("\n2. Verifying filter precision (top 5 sources)...")
    for source, count in list(gt["sources"].items())[:5]:
        failures = verify_filter_precision(base_url, "meta_source", source, count)
        all_failures.extend(failures)
        status = "PASS" if not failures else "FAIL"
        print(f"   {status}: meta_source='{source}' (expected {count})")
        for f in failures:
            print(f"     - {f}")

    print("\n3. Verifying case insensitivity...")
    failures = verify_case_insensitive(base_url)
    all_failures.extend(failures)
    print(f"   {'PASS' if not failures else f'FAIL ({len(failures)} issues)'}")
    for f in failures:
        print(f"   - {f}")

    print("\n4. Verifying cross-filter intersection...")
    failures = verify_cross_filter(base_url, gt)
    all_failures.extend(failures)
    print(f"   {'PASS' if not failures else f'FAIL ({len(failures)} issues)'}")
    for f in failures:
        print(f"   - {f}")

    print(f"\n{'=' * 50}")
    if all_failures:
        print(f"FAILED: {len(all_failures)} issues found")
        return 1
    else:
        print("ALL CHECKS PASSED")
        return 0


def main():
    parser = argparse.ArgumentParser(description="Metadata filtering eval (Phase 1)")
    sub = parser.add_subparsers(dest="command")

    gt_parser = sub.add_parser("ground-truth", help="Build ground truth from corpus")
    gt_parser.add_argument("--corpus-dir", required=True, help="Path to MultiHop-RAG corpus")
    gt_parser.add_argument("--output", help="Write ground truth JSON to file")

    verify_parser = sub.add_parser("verify", help="Verify against live backend")
    verify_parser.add_argument("--corpus-dir", required=True, help="Path to MultiHop-RAG corpus")
    verify_parser.add_argument("--base-url", default="http://127.0.0.1:33221")

    args = parser.parse_args()
    if args.command == "ground-truth":
        cmd_ground_truth(args)
    elif args.command == "verify":
        sys.exit(cmd_verify(args))
    else:
        parser.print_help()


if __name__ == "__main__":
    main()
