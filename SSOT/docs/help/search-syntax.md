# Search Syntax

JustSearch supports several search modes depending on how you phrase your query.

## Basic search

Type any words to search across all indexed documents. Results are ranked
by relevance using BM25 scoring.

    quarterly report 2025

## Phrase search

Wrap terms in double quotes to require an exact phrase match:

    "machine learning pipeline"

## Field search

Search within specific document fields:

    title:invoice
    path:contracts

## Combining terms

All terms are combined with AND by default. Documents must match all terms:

    budget quarterly review

## How ranking works

Without AI models, results are ranked by keyword frequency and document
structure (BM25). With AI models installed, JustSearch uses hybrid retrieval:

- **BM25** for keyword matching
- **Semantic embeddings** for meaning-based similarity
- **Cross-encoder reranking** for final relevance scoring

The hybrid approach finds documents that match your intent, even when they
use different words than your query.
