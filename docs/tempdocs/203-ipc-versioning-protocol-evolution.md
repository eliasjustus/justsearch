---
title: "IPC Versioning & Protocol Evolution"
status: done
---

# IPC Versioning & Protocol Evolution

## Problem

The gRPC + MMF IPC layer between Head and Body was designed and implemented but has no versioning or backward-compatibility strategy. As the system evolves, protocol changes risk breaking Head/Body communication.

## Gaps Identified

- **Proto versioning**: Are `.proto` files versioned? What happens when a field is added or removed?
- **Backward compatibility**: If Head is updated but Body is running an older JAR (e.g., during rolling update or partial install), does communication gracefully degrade?
- **MMF format stability**: Memory-mapped file layouts for port discovery and health signaling — are they documented and versioned?
- **Schema evolution strategy**: No documented policy for additive vs. breaking changes in the gRPC API.
- **Contract testing**: Are there contract tests ensuring Head and Body can communicate across versions?
- **Error handling for version mismatch**: What happens if Head sends a message Body doesn't understand? Silent failure? Crash? Diagnostic message?
- **Migration path**: When a breaking change is needed, how is the transition managed? Dual-write? Version negotiation?

## Existing State

- gRPC proto files exist in the repo.
- MMF format is used for port discovery and health signaling.
- One early tempdoc on IPC surface versioning was created and closed.
- No version field in current IPC messages (to be verified).

## Scope for Agent

Audit the current proto files and MMF layouts for versioning readiness. Determine whether any version mismatch scenarios are currently possible (e.g., stale Worker JAR). Propose a lightweight versioning scheme appropriate for a single-machine, single-user application.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 68 days at audit time.

