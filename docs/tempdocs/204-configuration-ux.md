---
title: "Configuration UX"
status: done
---

# Configuration UX

## Problem

The SSOT configuration system was built and migrated to, but the user-facing configuration experience in the Settings UI received minimal feature work beyond description updates. Configuration is a key touchpoint for power users.

## Gaps Identified

- **Config import/export**: No way to save or share configuration profiles.
- **Config reset**: No "reset to defaults" option for individual settings or all settings.
- **Config validation UI**: When a user enters an invalid value, is the error message clear and immediate?
- **Advanced vs. simple settings**: Tempdoc 188 (simple vs. advanced UX audit) is open but not implemented. Power users need access to advanced settings without overwhelming new users.
- **Config file editing**: Can power users edit a config file directly? Is there a documented config file format?
- **Startup configuration**: No way to configure startup behavior (auto-launch, default view, initial search scope).
- **Index location management**: Can users move their index to a different drive? Is this exposed in UI?
- **Exclude patterns UX**: Exclude patterns endpoint exists but the UI for managing patterns may be limited.
- **Config change feedback**: When a setting is changed, is the effect immediate? Does it require restart? Is this communicated?
- **Config migration**: When config schema changes between versions, are user settings preserved?

## Related

- Tempdoc 188: Simple vs. advanced UX audit (related but different focus)
- SSOT configuration system in `modules/app-config` and `SSOT/` directory

## Scope for Agent

Audit the current Settings UI against the SSOT config surface. Identify settings that exist in config but aren't exposed in UI. Propose UX improvements for the top pain points (likely: config reset, exclude patterns management, index location).

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 95 days at audit time.

