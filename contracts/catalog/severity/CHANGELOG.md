# Severity Catalog — CHANGELOG

Per-catalog evolution rules per ADR-09 axis 6 + ADR-09b:

- **Additive enum value** = minor version bump (e.g., 0.1.0 → 0.2.0).
- **Rename / remove enum value** = major version bump (breaking).
- **Per-entry metadata change** (label / i18n_key / deprecation flag /
  since-version) = patch version bump (consumer-presentation only).
- **`since` field on a new entry** documents the catalog version where
  the value was introduced; allows older consumers to detect
  incompatibility.

## 0.1.0 — 2026-05-06 (initial)

Initial spike per slice 3a-1-8d Phase 1 (ADR-09b decision).

Three values:
- `INFO` (`since: 0.1.0`)
- `WARNING` (`since: 0.1.0`)
- `ERROR` (`since: 0.1.0`)

Maps to existing `io.justsearch.app.observability.health.Severity`
Java enum. The Java enum stays canonical at runtime; this catalog is
the contract-side projection. Migration plan: future slice migrates
HealthEvent.severity wire field to reference this catalog (slice
3a-1-8c cross-reference enforcement).
