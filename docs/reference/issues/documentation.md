---
title: Documentation Issues
type: reference
status: stable
created: 2026-02-02
description: "Documentation gaps affecting developer/agent effectiveness."
---

# Documentation Issues

Gaps in project documentation that affect developer or agent effectiveness.

---

## Open Issues

### DOC-001: Testing strategy doc missing evidence bundle tiers
**Status: RESOLVED (tempdoc 638)** — the `capture-evidence-bundle.mjs` EBv1 harness was removed, so there is no evidence-bundle tier left to document.
- **Severity:** P4
- **Status:** open
- **Found:** 2026-02-02
- **Component:** `docs/explanation/09-testing-strategy.md`

**Description:** The testing strategy document does not describe evidence bundle capture as a verification tier. The capture script (`modules/ui-web/scripts/capture-evidence-bundle.mjs`, 7414 lines) implements a sophisticated multi-tier assertion system with DOM extraction, screenshot comparison, and determinism budget tracking — none of which is documented in the testing strategy.

**Impact:** Developers and agents don't know evidence bundles exist as a verification tool, what they capture, or when to use them. The capability is underutilized.

**Recommendation:** Add a section to `docs/explanation/09-testing-strategy.md` covering evidence bundle capture: what it produces, when to run it, and how it fits alongside unit/integration/system test tiers.

---

### DOC-002: No evidence interpretation guide for agents
- **Severity:** P4
- **Status:** open
- **Found:** 2026-02-02
- **Component:** `docs/`

**Description:** Evidence bundles produce `manifest.json` with assertions, screenshots, API snapshots, and browser logs. No guide explains how to interpret these artifacts — what a passing vs. failing assertion looks like, what the determinism budget means, or how to use DOM state captures for regression checking.

**Impact:** Agents receiving evidence bundles must reverse-engineer the format from raw JSON. Reduces the value of the evidence capture infrastructure.

**Recommendation:** Create an interpretation guide covering: manifest structure, assertion format (`{ file, dom, checks }`), determinism budget fields, and common failure patterns. Place in `docs/how-to/` or `.claude/docs/`.

---
