---
title: "331: StatusResponse Proto Restructuring"
type: tempdoc
status: done
superseded_by: 341
created: 2026-03-21
updated: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.
>
> **Superseded by [341](341-proto-field-governance.md)**, which implemented
> proto decomposition into nested sub-messages end-to-end.

# 331: StatusResponse Proto Restructuring

## Purpose

Group the flat `StatusResponse` protobuf (~110 fields) into nested
sub-messages for readability and maintainability. Low urgency — the
Head-side `/api/status` JSON is already structured. This only affects
the internal Worker→Head gRPC wire format.

## Origin

Extracted from tempdoc 330 item 4. Analysis during 330 found that the
user-facing API is already structured, so this is a code quality item,
not an observability fix.

## Problem

`StatusResponse` in `indexing.proto` is a single flat message with ~110
fields spanning embedding, migration, queue, GPU, schema compat, NER,
SPLADE, throughput, and vector quantization domains. The builder chain
in `IndexStatusOps.buildStatusResponse()` is ~300 lines. Every new
feature adds 2-5 more fields.

The flatness doesn't cause breakage (proto is append-only), but it
makes the builder and mapper code harder to navigate. The pain grows
linearly with field count.

## Scope

- Define nested proto messages (e.g., `EmbeddingStatus`,
  `MigrationStatus`, `QueueHealth`, `GpuDiagnostics`).
- Refactor `IndexStatusOps.buildStatusResponse()` into per-subsystem
  helper methods that build the nested messages.
- Update `WorkerStatusMapper` to read from nested messages.
- Keep flat fields as deprecated aliases during a transition period,
  or drop them if no external consumers exist.

## When to do this

- **Trigger:** A second Worker-side consumer appears (e.g., standalone
  Worker debug UI, another process reading the proto), or the field
  count exceeds ~150 and navigation becomes actively painful.
- **Good pairing:** Any larger Worker API revision or gRPC contract
  cleanup.
- **Not worth doing:** As a standalone task today. The single consumer
  (`RemoteKnowledgeClient`) handles the flatness fine.

## Related

- **330 (Worker State Accuracy):** Origin. Items 1-3 and 5 implemented;
  this item deferred.
- **336 (Status Response Composability):** Java-side view decomposition
  (complements proto restructuring with Head-side mapping changes).
- **341 (Proto Field Governance):** Field number management policy for
  `StatusResponse` and other growing proto messages.
