---
title: UI Stack and Documentation Tooling
type: decision
status: Superseded
description: "JavaFX theming via design tokens, TestFX + Monocle for testing, Vale/markdownlint/Lychee for docs CI."
date: 2026-03-16
---

> **Note:** This ADR is superseded. The JavaFX UI layer it describes was replaced by React/Vite/TypeScript (`modules/ui-web`). It is retained for historical context only.

# ADR-0012: UI Stack and Documentation Tooling

## Status

Superseded

## Context

The project needed decisions on JavaFX theming strategy, UI testing approach, and documentation quality tooling for CI.

## Decision

- **Theming**: Adopt JavaFX looked-up tokens generated from DTCG design tokens via Style Dictionary. Looked-up values are the idiomatic JavaFX theming primitive and resolve at apply time.
- **UI testing**: TestFX for UI catalog with ApprovalTests snapshots. Headless rendering on CI via Monocle (today) and the built-in Headless Platform when JavaFX 26+ is available (`-Dglass.platform=Headless`).
- **Documentation CI**: Vale + markdownlint + Lychee for consistent docs quality. Optional MkDocs Material for site generation.

## Consequences

- Design token outputs land under `modules/ui/src/main/resources/css/`.
- Snapshot baselines under `src/test/resources/approvals/` in UI modules.
- Transition to JavaFX Headless Platform when the toolchain upgrades to 26+.
