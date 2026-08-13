---
title: API Evolution Strategy
type: reference
status: stable
description: "Backward-compatible evolution policy for HTTP and gRPC APIs."
---

# API Evolution Strategy

## Policy

JustSearch uses **backward-compatible evolution** for all APIs. There is no `/api/v1/` path versioning. A version suffix is added only when a breaking change is genuinely needed (as done with `/api/settings/v2`).

## Why No Path Versioning

Frontend and backend are co-shipped in the Tauri bundle. There is no version skew between client and server — the UI always matches the API it was built against. Path-based versioning (`/api/v1/`, `/api/v2/`) adds routing complexity and maintenance burden without benefit for a loopback-only API.

## HTTP API Rules

| Change type | Safe? | Action required |
|-------------|-------|-----------------|
| Add new field to response body | Safe | None |
| Add new endpoint | Safe | None |
| Add new query parameter (optional) | Safe | None |
| Remove a response field | **Breaking** | Add `Deprecation: true` header (RFC 9745) for at least one release, then remove |
| Rename a response field | **Breaking** | Add new field alongside old, deprecate old, remove after one release |
| Change a field's type | **Breaking** | Version the endpoint with a suffix (e.g., `/api/settings/v2`) |
| Remove an endpoint | **Breaking** | Add `Deprecation: true` + `Sunset` header (RFC 8594), remove after one release |

**Endpoint removal policy:** The agent guide states "No legacy endpoints — Don't resurrect removed APIs." Once removed, an endpoint stays removed. Before removal, signal deprecation for at least one release cycle.

## gRPC Rules

| Change type | Safe? | Action required |
|-------------|-------|-----------------|
| Add new field | Safe | Use the next available field number |
| Add new RPC method | Safe | None |
| Deprecate a field | Safe | Add `deprecated = true` option to the field |
| Remove a deprecated field | **After one release** | Replace field definition with `reserved` keyword (both number and name) |
| Reuse a field number | **Never** | Field numbers are permanent identifiers |

**Deprecation lifecycle:** `deprecated = true` option (one release minimum) then `reserved` keyword.

**Currently deprecated items** (add `reserved` when these are removed):
- `vdu_status` (field 3 in `UpdateVduResultRequest`) — replaced by `outcome` (field 6)
- `PruneMissing` RPC — replaced by `SyncDirectory`

**Package naming:** Proto files use `package io.justsearch.ipc.v1;`. Note: `indexing.proto` currently uses `package io.justsearch.ipc;` (missing `v1` suffix) — tracked as API1 in tempdoc 179.

### Compile-time safety

Buf is configured in `modules/ipc-common/src/main/proto/buf.yaml` with `WIRE` breaking change detection. This prevents accidental binary-incompatible changes (field number reuse, type changes, message removal) at build time. The `WIRE` rule is appropriate because Head and Body are co-shipped — there is no multi-version deployment.

Runtime compatibility currently relies on co-shipping, compile-time schema checks, contract tests, and explicit status/degradation signals. Do not document a runtime handshake client unless that client exists in the current codebase.

**Buf lint level:** Currently `MINIMAL` to avoid forcing naming changes on legacy `indexing.proto`. Tighten to `BASIC` for `v1/` protos when convenient — Buf supports per-file exemptions so `indexing.proto` can stay at `MINIMAL`.

## Source of Truth

Contract tests are the authoritative source for API schema expectations:

| Contract test | What it verifies |
|---------------|------------------|
| `LifecycleContractTest` | `/api/status` response shape, field presence, HTTP semantics |
| `TelemetryHealthContractTest` | `/api/telemetry/health` response shape and field types |
| `SchemaMismatchStatusContractTest` | Schema mismatch status reporting contract |
| `GrpcSearchServiceReasonCodeContractTest` | gRPC search reason code allowlist |

Proto files: `modules/ipc-common/src/main/proto/`
Route definitions: `modules/ui/src/main/java/.../routes/*.java`

**Java API surface comparison is deliberately not performed.** A Revapi baseline was scaffolded
here for that purpose and has been retired: the promised surfaces are the three wire-level
constituents of the Runtime Contract (runtime manifest schema, health/status lifecycle subset, MCP
endpoint + curated tools — see `docs/reference/runtime-contract.md`), and the Java API of
`modules/app-api` is not among them. Breakage on the promised surfaces is caught by the contract
tests above, the manifest schema-compatibility test, and constituent version pinning — the mechanism
that in practice detected and versioned the Runtime Contract `0.2.0` break. Adding a Java-API
comparator would guard an unpromised surface, and would additionally require publishing release
artifacts for a baseline to compare against, which this project does not do.

## See Also

- `docs/reference/contracts/search-and-rag-reason-codes.md` — degradation signaling contracts
- `CLAUDE.md` Hard Invariants — architectural invariants (includes "No legacy endpoints")
