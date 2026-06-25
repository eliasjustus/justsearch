---
title: "Installer & Distribution Maturity"
status: done
---

# Installer & Distribution Maturity

## Problem

Installer work happened early (tempdocs 09, 96, 97) then stopped. The Tauri bundling presumably works for basic installation, but distribution maturity features are missing.

## Gaps Identified

- **Auto-update**: Tauri supports built-in auto-update — is it configured? Does it work?
- **Code signing**: Is the installer signed? Windows SmartScreen blocks unsigned executables.
- **Uninstaller cleanup**: Does uninstallation remove the index, config files, and registry entries?
- **Portable mode**: Can JustSearch run from a USB drive without installation?
- **Silent install**: Can IT departments deploy JustSearch via MSI/GPO?
- **First-run experience**: What does the user see after installation? Onboarding? Immediate indexing?
- **Multi-user**: Does installation support per-user vs. per-machine installation?
- **Size optimization**: How large is the installer? Can the bundled models be downloaded on demand?

## Existing State

- Tauri bundling produces a Windows installer.
- Early tempdocs (09, 96, 97) explored installer topics but were closed.
- No recent work on distribution maturity.

## Scope for Agent

Audit the current Tauri build configuration for distribution readiness. Check if auto-update, code signing, and uninstaller cleanup are configured. Identify the top 3 distribution improvements for a production release.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 95 days at audit time.

