---
title: "ADR-0011: Distributed Readiness — Remote Shard SPI"
type: decision
status: rejected - never built; retired 2026-09-02, a single-user desktop product has no shard need
description: "Retired 2026-09-02: a Remote Shard SPI was accepted 2026-03-16 and never built. A single-user desktop product has no shard need; the absence is now the decision."
date: 2026-03-16
probes:
  - adr-0011-no-remote-shard
last_reviewed: 2026-09-02
---

# ADR-0011: Distributed Readiness — Remote Shard SPI

> **Retired 2026-09-02 (decision-review lane B, tempdoc 884).** This SPI was accepted on
> 2026-03-16 and **never built** — zero `RemoteShard` symbols exist anywhere in shipped code.
> It is not the design of record and must not be treated as one: no remote-shard interface,
> fan-out/merge path, or partial-result taxonomy exists to implement against. The Context /
> Decision / Consequences below are preserved verbatim as the historical record of what was
> decided in March 2026, not as instructions. See the amendment at the end of this file.

## Status

Rejected - never built (retired 2026-09-02). Original status: Accepted 2026-03-16.

## Context

JustSearch is local-first and single-host. Future needs may require remote shards and cross-host search. The SPI must be defined early so that the app-api boundary remains stable when remote shards are eventually implemented.

## Decision

- Define a Remote Shard SPI with operations: fetch, query, segment stats, health.
- Define search fan-out/merge with partial-result error taxonomy.
- Consistency model: read-your-writes on local shard, eventual consistency across remotes.
- Snapshot/restore compatibility across local/remote shards using SSOT commit metadata fingerprints.

## Consequences

- Current code routes only local shards — no remote implementation exists.
- Plugins can implement remote shards later without breaking `app-api`.
- SSOT commit metadata (field catalog hash, analyzer fingerprint, schema fingerprint) provides the compatibility contract for snapshot portability.

## Amendment 2026-09-02: retired

Re-examined under decision-review lane B (tempdoc 884), outcome **never built**.

**The evidence.** `RemoteShard` has zero occurrences in `modules/` or `contracts/` across
`.java`, `.kts`, `.ts` and `.proto`. Probe `adr-0011-no-remote-shard` is that grep, run by the
`adr-coverage` kernel gate. Nothing named in the Decision section — the SPI operations (fetch,
query, segment stats, health), the fan-out/merge path, the partial-result error taxonomy, the
cross-shard consistency model, the snapshot/restore compatibility contract — has any code behind
it. The 2026-07 verification in tempdoc 742 found the same thing; this amendment converts that
observation into a decision instead of repeating it.

**Why retire rather than leave it accepted.**

- **A single-user desktop product has no shard need.** JustSearch runs local-first on one
  machine, indexing one user's files. Remote shards solve a capacity and locality problem this
  product does not have. The premise the SPI was designed against — "future needs may require
  remote shards" — is a hypothetical, and it has not moved in the intervening period.
- **Five and a half months of nothing shipped.** Accepted 2026-03-16, still empty on 2026-09-02.
  That is not a delayed implementation; it is a decision that was never load-bearing.
- **An aspiration with a green status is exactly the failure mode tempdoc 884 exists to end.**
  An `Accepted` ADR reads as "this is how the system is built." A reader searching for the
  shard SPI finds a design, no code, and no signal about which is authoritative. Retirement
  removes that ambiguity: the absence *is* the decision.

**What is retained.** Context, Decision and Consequences are untouched. If multi-host ever
becomes real, this file is the March 2026 design thinking — a starting point, not a commitment.

### Reopening trigger

**A roadmap item for a second machine or a shared index reopens this ADR.** Until then, probe
`adr-0011-no-remote-shard` keeps the retirement honest: introducing a `RemoteShard` symbol fails
the `adr-coverage` gate, so building the SPI requires deliberately changing this ADR first rather
than quietly resurrecting an accepted-but-dead design.
