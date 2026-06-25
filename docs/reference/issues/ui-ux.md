---
title: UI/UX Issues
type: reference
status: stable
updated: 2026-02-02
description: "Frontend display and interaction issues."
---

# UI/UX Issues

Issues related to frontend display, user interaction, and experience.

**Key Files:**
- `modules/ui-web/src/components/`
- `modules/ui-web/src/stores/`
- `modules/ui-web/src/hooks/`

---

## Open Issues

*GPU-related frontend issues are tracked in [gpu-detection.md](gpu-detection.md#frontend-display-issues).*

---

### UIX-006: WCAG contrast CI validation not wired into CI pipeline
- **Severity:** P3
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `.github/workflows/ci.yml`, `modules/ui-web/e2e/accessibility-audit.spec.ts`

**Description:** A comprehensive 466-line Playwright accessibility test suite exists (`e2e/accessibility-audit.spec.ts`) using `@axe-core/playwright` (v4.11.0 devDep) covering WCAG 2.1 AA across Search, Settings, and Library views. A standalone bash runner also exists (`scripts/ci/a11y-runner.sh`, 131 lines). Neither is executed in CI — the workflow only runs `./gradlew check`.

**Impact:** Accessibility regressions (contrast, missing labels, keyboard traps) are not caught automatically. Tests only run when a developer remembers to execute them locally.

**Recommendation:** Add a CI job that installs Playwright browsers, starts the dev stack, waits for readiness, and runs `npx playwright test accessibility-audit --project=Desktop`. Main blockers: browser binary caching on the Windows runner, dev stack orchestration (backend + frontend), and port management in GitHub Actions.

---

### UIX-013: Library folder cards lack file type breakdown
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `modules/ui-web/src/components/views/LibraryView.tsx`

**Description:** Library folder cards display path, file count, and last-modified time, but no breakdown of file types within the folder (e.g., "12 PDFs, 3 docs, 5 images").

**Impact:** Users can't see what types of files are in a folder without opening it.

**Recommendation:** Requires backend API changes to expose per-folder file type distribution data before the frontend can display it.

---

### UIX-014: No interactive state capture in evidence screenshots
**Status: RESOLVED (tempdoc 638)** — the `capture-evidence-bundle.mjs` evidence-screenshot harness was removed, so this issue is moot.
- **Severity:** P4
- **Status:** open
- **Found:** 2026-01-30
- **Component:** `modules/ui-web/scripts/capture-evidence-bundle.mjs`

**Description:** Evidence screenshots don't capture hover, focus, or active states. Implementing this requires per-element CDP state forcing via `CSS.forcePseudoState`, taking a screenshot, then clearing with `page.mouse.move(0,0)` between states.

**Impact:** Visual verification of interactive states (hover highlights, focus rings, active press effects) requires manual inspection.

**Recommendation:** ~100 lines of work in the capture script. Use CDP `CSS.forcePseudoState` to cycle through `:hover`, `:focus`, `:active` on key interactive elements.
