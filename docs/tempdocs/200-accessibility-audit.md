---
title: "Accessibility Audit (Beyond Contrast)"
status: done
---

# Accessibility Audit (Beyond Contrast)

## Problem

Accessibility work has been limited to one WCAG AA contrast compliance commit. No broader accessibility audit has been performed. Keyboard navigation, screen reader support, and ARIA semantics are unexplored.

## Gaps Identified

- **Keyboard navigation**: Can all UI features be reached without a mouse? Tab order, focus management, keyboard shortcuts.
- **Screen reader support**: Are search results, status messages, and navigation landmarks announced correctly?
- **ARIA semantics**: Are interactive components (tree view, search results, panels) using correct ARIA roles and properties?
- **Focus management**: After search, after navigation, after modal dialogs — is focus handled correctly?
- **Reduced motion**: Does the UI respect `prefers-reduced-motion`?
- **Font scaling**: Does the UI handle browser font size changes gracefully?
- **Color independence**: Is information conveyed through color alone (e.g., status indicators)?

## Existing State

- One commit for WCAG AA contrast compliance.
- Browse tree view recently added ARIA attributes (role, aria-level, etc.).
- No accessibility testing infrastructure or screen reader testing documented.

## Known Issues (from issue tracker audit, 2026-02-19)

### ACC-001 residual: Context menu has no keyboard trigger — RESOLVED

**Source:** `docs/reference/issues/search-accessibility.md` ACC-001

**Fixed (2026-02-19):** Added `Shift+F10` and `ContextMenu` key handler in `VirtualResultList.tsx` that opens the context menu at the cursor row's position. Wired through `showContextMenuAt` in `useContextMenu` hook → `handleContextMenuAt` in `useAppFileActions` → `onContextMenuAt` prop chain.

## Scope for Agent

Run a manual accessibility audit of the top 3 user flows (search, browse, settings). Test keyboard-only navigation end-to-end. Identify ARIA gaps in interactive components. Propose fixes ranked by impact on usability.

---

## Staleness review (2026-05-18)

Investigation-shape tempdoc (research / audit / findings) — terminal by nature per the README's "investigation log that produced a decision" definition. Body content preserved as design history. Classification: INVESTIGATION. Stale for 88 days at audit time.

