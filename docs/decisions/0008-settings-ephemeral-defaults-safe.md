---
title: "Settings Are Ephemeral, Defaults Are Safe"
type: decision
status: stable
description: "User settings (settings.json) are not migration-protected. Incompatible settings files are silently replaced with defaults."
date: 2026-02-10
---

# ADR-0008: Settings Are Ephemeral, Defaults Are Safe

## Status

Accepted

## Context

`UiSettingsStore` loads and saves `settings.json` via Jackson. The file has no `schemaVersion` field and no migration hooks. `FAIL_ON_UNKNOWN_PROPERTIES` is disabled, so older versions can read newer settings files without crashing (unknown fields are silently ignored).

All settings changes to date have been additive: new fields get default values when absent. But structural changes (renaming a field, changing a type, splitting a setting into sub-settings) would silently drop user preferences with no recovery path.

Index data has a robust migration mechanism: blue/green schema migration with fingerprinting, durable switch buffer, and rollback safety (see `docs/explanation/11-index-schema-migration.md`). User settings have no equivalent.

The question: should settings survive structural changes across upgrades, or is it acceptable to reset to defaults?

## Decision

**Settings are ephemeral. Defaults are always safe.**

If a user's `settings.json` becomes incompatible with the current version, it is silently replaced with defaults. This is acceptable because:

1. Settings control UI preferences (theme, layout, sort order, view state) — not user content.
2. User content lives in the Lucene index, which has its own migration mechanism.
3. Source file paths (the most valuable user configuration) are stored in the index metadata, not in `settings.json`.
4. Resetting preferences is a minor inconvenience, not data loss.

## Consequences

- Any structural settings change may silently drop user preferences. This is acceptable for the current state where settings control only UI behavior.
- Agents working on settings code should treat fields as **additive-only**: add new fields with sensible defaults, never rename or change the type of an existing field.
- If settings begin to control critical behavior (e.g., exclusion rules, privacy settings, API keys), this decision must be revisited. At that point, implement schema versioning (M1) and a migration hook chain (M2) as described in tempdoc 179 §7.
- Jackson's `FAIL_ON_UNKNOWN_PROPERTIES = false` remains the correct setting — it provides forward compatibility (old settings files work with new code) for free.
- **`IN_MEMORY` operational mode** (tempdoc 368): In eval/ephemeral contexts, the settings store operates in-memory with no persistence. The API declares this via `settingsMode: "in_memory"` in the `GET /api/settings/v2` response. `POST /api/settings/v2` returns 409 with error code `SETTINGS_READ_ONLY` when in this mode. The frontend detects `settingsMode === "in_memory"` and hides the "Reset to Defaults" button, skips network flushes, and applies changes locally for the session only. This is an operational mode (not an upgrade scenario) and does not conflict with the ephemeral-defaults principle — settings are still not migration-protected.

## Alternatives Considered

**"Settings must survive upgrades"** — would require:

- Adding a `schemaVersion` field to `settings.json` (M1, ~30 min)
- Implementing a sequential migration chain: on load, check version, apply migrations 1 to 2 to 3 (M2, ~2-4 hours)
- Total: ~3-4 hours of implementation

This was deferred because the cost-benefit ratio is unfavorable while settings control only UI preferences. The migration infrastructure would be straightforward to add later if needed.
