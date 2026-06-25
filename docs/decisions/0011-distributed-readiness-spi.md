---
title: Distributed Readiness — Remote Shard SPI
type: decision
status: stable
description: "Define a Remote Shard SPI for future multi-host search without breaking local-first architecture."
date: 2026-03-16
---

# ADR-0011: Distributed Readiness — Remote Shard SPI

## Status

Accepted

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
