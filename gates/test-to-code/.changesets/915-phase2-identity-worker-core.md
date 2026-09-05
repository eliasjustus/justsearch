---
classification: declared-regression
tempdoc: 915
---
`modules/worker-core` dips 632 → 631 because Phase 2 PR-A adds the **contract half** of durable
document identity to the module that every Worker service shares — `DocumentIdentityStore`
(interface, +90), `PathHash` (+26) and the `InfraContext` wiring that hands the store to the
services (+46, −2) — while the behaviour those lines describe is tested where it runs:

| worker-core production added | where it is exercised |
|---|---|
| `DocumentIdentityStore` + `PathHash` | `indexer-worker` `SqliteDocumentIdentityStoreTest` (the one implementation), `DocumentIdentityBootImportTest` (Blue import → Green reuse, rename, paused migration), `GrpcIngestServiceDocumentIdentityTest` (the wire path); `app-services` `ResolvePathHashHandlerTest` |
| `InfraContext` store wiring | `DocumentIdentityBootImportTest` asserts through the production gRPC service that "the production gRPC service must be wired to the durable identity store" |

`indexer-worker` moves 1166 → 1294 in the same diff (`rebalance-available`), which is the same
lines counted on the side of the module that owns the behaviour. An in-module worker-core test of
an interface and a hash helper would be padding. The row is repinned to its measured value in this
commit per `declared-regression-without-repin`; the improved rows are left for `--rebalance`.
