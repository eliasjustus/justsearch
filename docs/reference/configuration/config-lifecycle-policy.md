---
title: Runtime Configuration Lifecycle Policy
type: reference
status: stable
description: "Source-declared lifecycle stages and review metadata for permanent, experimental, and deprecated runtime configuration."
---

# Runtime Configuration Lifecycle Policy

Every `EnvRegistry` and `ConfigKey` declaration has one compiler-backed
lifecycle stage:

- `PERMANENT` is the default for a supported configuration contract.
- `EXPERIMENTAL` is a bounded option awaiting evidence for promotion or removal.
- `DEPRECATED` is retained temporarily while consumers migrate to its named replacement.

The generated [runtime configuration ownership matrix](runtime-config-ownership-matrix.md)
projects the declaration and stage; it does not become a second authority.

## Non-permanent metadata

`governance/config-lifecycle.v1.json` supplies the richer review record for
each experimental or deprecated declaration: owner, rationale, introduction
and review dates, next review deadline, promotion/removal criteria, and a
repository evidence link. The `config-surface` gate enforces an exact join:
missing, duplicate, orphaned, incomplete, incoherent, overdue, or
unresolvable-evidence rows fail closed.

## Promotion, deprecation, and removal

Promotion changes the source declaration to `PERMANENT` and removes its
non-permanent metadata in the same change that establishes the supported
contract. Deprecation marks the source declaration `DEPRECATED` and records
the replacement and removal criterion in its lifecycle metadata. Removal
deletes the declaration, reader, documentation, tests, and lifecycle row in
one sweep.

This policy governs runtime configuration declarations only. Dormant code,
consumerless substrates, and other implementation experiments need their own
evidence and are not made compliant by adding a configuration-lifecycle row.

## Verification

Regenerate and check the projection after changing either declaration source:

```text
node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md
node scripts/docs/verify-runtime-config-matrix.mjs
node scripts/governance/run.mjs --gate config-surface --mode gate
```
