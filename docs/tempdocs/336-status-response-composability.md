---
title: "336: Status Response Composability"
type: tempdoc
status: done
superseded_by: 341
created: 2026-03-22
---

> NOTE: Noncanonical doc (architecture). May drift.
>
> **Superseded by [341](341-proto-field-governance.md)**, which implemented
> this decomposition end-to-end (proto + Java + frontend).

# 336: Status Response Composability

## Problem

`WorkerOperationalView` is a Java record with 50+ positional
constructor arguments. Adding any field breaks every call site
(fallback factory, mapper, all tests that construct it directly).
In tempdoc 334, adding 2 NER fields (`nerModelPath`, `nerGpuEnabled`)
required fixing 4 separate files.

The flat structure makes extensions fragile and error-prone —
a misplaced argument silently shifts all subsequent field values
with no compile-time error beyond arity mismatch.

## Proposed Solution

Compose `WorkerOperationalView` from typed sub-records:

```
WorkerOperationalView {
  IndexHealth health;           // state, docCount, available
  ModelIdentity models;         // embed/splade/ner paths, fingerprints, GPU
  EnrichmentProgress enrichment; // coverage %, counts, churn
  GpuDiagnostics gpu;           // per-session OrtCuda status
  QueueHealth queue;            // pending, processing, depth
  MigrationState migration;     // existing MigrationEnumeratorView
  ...
}
```

Adding a NER model path means adding one field to `ModelIdentity`
— no other call site breaks.

## Related

- **331** (Status Proto Restructuring): backlog, covers the proto
  side of the same problem. Same decomposition applies to the
  protobuf message.
- **330** (Worker State Accuracy): completed, added `_meta` and
  subsystem groups on the Head side. The Worker-side view was not
  restructured.
- **333** (Status State Provenance): active, added `observedAtMs`
  per group.

## Scope

- Decompose `WorkerOperationalView` into 5-8 sub-records
- Update `WorkerStatusMapper` to construct sub-records
- Update `fallback()` factory
- Update `toMap()` serialization (preserve flat JSON for backward
  compatibility, or adopt nested JSON with migration)
- Update all test call sites
