---
status: stable
description: "JustSearch Runtime Contract — versions, compatibility matrix, stability policy, and the public-contract / reference-client / internal surface classification. Reference companion to docs/explanation/28-runtime-contract.md. v1 is deliberately under-promised — only the runtime manifest, the health/status lifecycle subset, and the MCP endpoint + curated tools are promised."
---

# Runtime Contract — versions, policy, and classification

Reference companion to
[The JustSearch Runtime Contract](../explanation/28-runtime-contract.md) (the
positioning and definition). This page is the durable statement of *what is
promised, at what version, and how it may change*.

## Contract version and constituents

The runtime advertises a coarse **contract version** on the manifest
(`runtimeContract.version`) plus the pinned versions of its constituent
surfaces. Source of truth:
`modules/app-api/src/main/java/io/justsearch/app/api/runtime/RuntimeContract.java`
(`RuntimeContract.current()` projects each constituent from its own single
source — it invents no version except the umbrella).

| Constituent | Meaning | Single source (constant) |
|---|---|---|
| `manifestSchemaVersion` | Runtime manifest document schema | `RuntimeManifest.CURRENT_SCHEMA_VERSION` |
| `lifecycleSchemaVersion` | Health/status v1 subset schema | `LifecycleSnapshotV1.SCHEMA_VERSION` |
| `mcpProtocolVersion` | MCP spec version this build speaks | `McpContractVersions.PROTOCOL_VERSION` |
| `mcpToolSurfaceVersion` | JustSearch's own curated-tool-surface version | `McpContractVersions.TOOL_SURFACE_VERSION` |

The MCP `initialize` response reports `protocolVersion` and
`serverInfo.version` from the **same** `McpContractVersions` constants, so the
manifest and the MCP handshake cannot desync.

## Compatibility matrix

The contract version is the single value an external tool targets. Its meaning
is the row of constituent versions below. The current build:

| Runtime Contract | manifest schema | lifecycle schema | MCP protocol | MCP tool surface |
|---|---|---|---|---|
| `0.1.0` | `1` | `1` | `2025-11-25` | `0.1.0` |

**Skew rule.** A client built for Runtime Contract vN works against a runtime
advertising vN. Older clients degrade gracefully: the manifest is
forward-compatible (unknown fields are ignored by tolerant readers;
`RuntimeManifestSchemaCompatibilityTest`), and MCP negotiates its protocol
version in `initialize`. There is no per-release compatibility table to
maintain — the manifest advertises the live versions, and a client reads them.

> On the MCP tool-surface version: the MCP protocol version says nothing about
> the stability of a server's tool set (MCP has no shipped tool-surface
> versioning; proposals point at per-tool SemVer). JustSearch versions its own
> tool surface here, SemVer-shaped, so the promise is explicit.

## Stability policy

- **Scope.** Only the surfaces classified *public-contract* below are promised.
  Everything else may change without notice.
- **Additive within a major.** New optional manifest fields, new tools, and new
  optional response fields do not break the contract and do not bump the
  contract version. A removal or a required-shape change is a breaking change.
- **Bump-only-on-break.** The coarse contract version is bumped only on a
  backward-incompatible change to a constituent — never on an additive change
  (mirroring MCP's own dated-version rule).
- **Pre-1.0 by design.** `0.x` means the surface may still change while we
  settle it (SemVer clause 4). A scoped `1.0` — "we will not break this" — is
  declared only when it is true, and covers only the enumerated public-contract
  surfaces (SemVer clause 5).
- **Deprecation window.** Anything promised is kept for **at least 90 days**
  after a deprecation notice before removal, so a JustSearch consumer never
  faces a shorter fuse than MCP's own expedited-removal floor.

## Changelog

The contract version bumps only on a backward-incompatible change to a
constituent (never on an additive change), so this log has one entry per bump —
not per release. Mirrors the internal `contracts/wire/CHANGELOG.md` convention.

| Contract | Date | Change |
|---|---|---|
| `0.1.0` | 2026-07-02 | Initial contract. Names the three public-contract surfaces (runtime manifest, health/status lifecycle subset, MCP endpoint + curated tools) and pins their constituent versions (manifest schema `1`, lifecycle schema `1`, MCP protocol `2025-11-25`, MCP tool surface `0.1.0`). Pre-1.0 — the surface may still change while it settles. |

## Surface classification

The v1 boundary. This table is authored here (not yet mechanically projected
per-route — the manifest's `audience` axis already classifies the contract's
discovery transports, and a per-route classification gate is a possible future
step, not required for a three-surface core).

| Tier | Surfaces | Promise |
|---|---|---|
| **Public-contract** | Runtime manifest + its standard transports (`GET /api/runtime/manifest`, the `/.well-known/justsearch/manifest.json` mirror, the manifest SSE stream, the `GET /api/runtime/ready`/`live` probes); the health/status **lifecycle subset** (`GET /api/health`, and the schema-v1 minimum fields of `GET /api/status`); the **MCP** endpoint (`POST /mcp`) + the curated tool set. | Versioned + deprecation-clocked (above). |
| **Reference-client** | Surfaces the desktop shell (and the manifest's `full` audience) use but that are **not** promised to third parties: the *extended* `/api/status` fields, `/api/knowledge/*` (search/suggest/status/ingest), boot-phase traces, health-event streams, governance state, the operation/agent-action substrate, retrieve-context and chat/conversation APIs, folder-browse, and the OpenAI-compatible `/v1/*` shim. | May change; not promised. Demonstrated by the reference client, not defined by it. |
| **Internal** | Not for external callers: `/api/debug/*`, the Head↔Worker gRPC IPC and `contracts/wire` protos, the MMF signalling layer, filesystem-only manifest fields (`head.sessionToken`), and the `/infra/capabilities` FE↔Head capability handshake. | No stability, no external audience. |

## What the contract does not claim

The Runtime Contract is a **defined, versioned local surface** — not a
certification. There is no "certified", "compliant", or "conformant" claim:
there is no MCP server certification program, and MCP conformance tooling is
spec/SDK-facing and still maturing. The official MCP registry offers
**discovery + namespace/provenance verification** (it authenticates *who
published* a server, not its quality) — so the honest, checkable status
JustSearch can pursue is *"listed, namespace-verified"*, never *"certified."*
Externally verifiable trust evidence is a separate, ongoing effort (tempdoc
659). The contract is loopback-only by construction — "external" means agents
on the same machine, not remote access.

## See also

- [The JustSearch Runtime Contract](../explanation/28-runtime-contract.md) — positioning + definition.
- [Runtime manifest](../explanation/23-runtime-manifest.md) — the discovery object the contract rides on.
- [API contract map](api-contract-map.md) — the full surface inventory.
- [Production MCP server](mcp-production-server.md) — MCP client setup.
