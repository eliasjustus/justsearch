---
title: "Corpus provenance integrity: the worker never ingests its own runtime artifacts; counts partition reconcilably"
type: tempdoc
status: open
created: 2026-07-07
related: [585, 687, 690]
---

# 692 — Corpus provenance integrity

## Context

Tempdoc 687's post-implementation follow-up F1 confirmed a trust bug: a runtime
transcript artifact (`<dataDir>/agent-history/<uuid>.md`) was ingested UNTAGGED into the
user corpus by the generic file watcher — it raised the status-bar document count (5→6)
and ranked #2 in real search results. Tempdoc 585 §D Phase 4 (D4b) designed agent-history
as a reserved Lucene collection whose docs are default-excluded from search; that design
assumed every agent-history document arrives TAGGED through `AgentHistoryIndexer`. The
violation path: the same file is also reachable by the generic scanner, which ingests it
as an ordinary untagged document — the MUST_NOT-on-tag exclusion never sees it.

## Decision

1. **Invariant: the worker never ingests anything under its own dataDir** through the
   generic scan/watch path. A prefix guard at the scanner/watcher (the single ingest
   discovery seam) makes the 585-D4b bypass unrepresentable — reserved-collection content
   can only enter through its tagged indexer.
2. **Counts partition reconcilably** (687 principle P3): every document-count surface
   states one auditable population (the user corpus), with reserved collections counted
   separately or not at all. A user must be able to reconcile the number against the
   files they added.
3. **Regression proof**: a test that plants a file under the dataDir inside a watched
   root and asserts it is neither indexed untagged nor counted; plus an assertion that
   the tagged agent-history path still works.

## Supersedes / orphans

Nothing is deleted; 585-D4b's tag-exclusion stays (correct for its layer). This doc
corrects 585's implicit assumption that tagging is the only entry path.
