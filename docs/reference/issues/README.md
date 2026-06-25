---
title: Known Issues
type: reference
status: stable
updated: 2026-03-19
description: "Severity levels, status values, and issue format."
---

# Known Issues

This folder tracks verified issues in the JustSearch codebase. Issues are facts about current limitations or bugs, not speculation.

## Severity Levels

| Level | Description |
|-------|-------------|
| **P1** | Blocks core functionality |
| **P2** | Significant UX impact |
| **P3** | Minor inconvenience |
| **P4** | Nice-to-have improvement |

## Status Values

Issue files use these statuses for actionable items:

- **open** — confirmed, not yet addressed
- **in-progress** — work underway
- **blocked** — waiting on a dependency
- **mitigated** — partial fix applied, residual limitation remains

Resolved issues are deleted from tracking. Evaluated-and-closed decisions live in [decisions.md](decisions.md) with their rationale preserved.

## Issue Files

- [GPU Detection](gpu-detection.md) — NVIDIA detection, VRAM, multi-GPU support
- [Installer](installer.md) — Packaging, installation, and sandbox testing
- [UI/UX](ui-ux.md) — Frontend display and interaction issues
- [Search Accessibility](search-accessibility.md) — Result list ARIA, keyboard access, match pills
- [Documentation](documentation.md) — Documentation gaps affecting developer/agent effectiveness
- [Backend Tech Debt](backend-tech-debt.md) — Legacy code and deprecation tracking
- [Closed Decisions](decisions.md) — Evaluated items intentionally closed (won't-fix, deferred, by-design, superseded)

## Retired Issue Files (moved to registers)

The following issue files are superseded by shared decision registers that
better fit domains with empirical findings and design decisions:

- ~~Search Quality~~ (SRQ-) → [Search Quality Register](../search-quality-register.md) — open items triaged to FW-006
- ~~Retrieval Quality~~ (RAG-) → [Search Quality Register](../search-quality-register.md) + [Inference Runtime Register](../inference-runtime-register.md) — open items triaged to FW-003/004/007-010
- ~~Benchmarking~~ (BEN-) → superseded by `scripts/jseval/` — all items retired to [decisions.md](decisions.md)
- ~~Build & Hygiene~~ (BLD-) → superseded by eval consolidation — all items retired to [decisions.md](decisions.md)
- GPU/inference items from [GPU Detection](gpu-detection.md) → [Inference Runtime Register](../inference-runtime-register.md)

## Adding Issues

Use this template when adding new issues:

```markdown
### AREA-NNN: Short Title
- **Severity:** P1/P2/P3/P4
- **Status:** open | in-progress
- **Found:** YYYY-MM-DD
- **Component:** `module/path/to/file.java`

**Description:** What's wrong

**Impact:** Who/what is affected

**Code Evidence:**
\`\`\`java
// relevant code snippet
\`\`\`

**Recommendation:** Proposed fix approach
```

## Issue ID Convention

- `GPU-001`, `GPU-002` — GPU detection issues
- `INS-001`, `INS-002` — Installer issues
- `UIX-001`, `UIX-002` — UI/UX issues
- `ACC-001`, `ACC-002` — Search accessibility issues
- `DOC-001`, `DOC-002` — Documentation issues
- `BKD-001`, `BKD-002` — Backend tech debt issues
- `BEN-001`, `BEN-002` — Benchmarking issues
- `BLD-001`, `BLD-002` — Build & hygiene issues

**Retired prefixes** (superseded, do not reuse):
- `SRQ-` — Search quality → [Search Quality Register](../search-quality-register.md)
- `RAG-` — Retrieval quality → registers
- `BEN-` — Benchmarking → `scripts/jseval/` + [decisions.md](decisions.md)
- `BLD-` — Build & hygiene → [decisions.md](decisions.md)

**Note:** Retired IDs live in [decisions.md](decisions.md) and must not be reused for new issues.

## Allocating IDs

To pick the next ID for a new issue:

1. Find the highest existing number for that prefix in the domain file (e.g., `GPU-014` in gpu-detection.md)
2. Check [decisions.md](decisions.md) for retired IDs with the same prefix (e.g., `GPU-012`)
3. Use N+1 where N is the highest number found across both files

Example: gpu-detection.md has GPU-014, decisions.md has GPU-012 → next ID is GPU-015.

## Workflow

1. **Discovery** — Issue found during development or testing
2. **Document** — Add to appropriate issue file with evidence
3. **Triage** — Assign severity (P1–P4)
4. **Resolve** — Either:
   - **Fix** → delete the entry when resolved
   - **Decide** → move to [decisions.md](decisions.md) with disposition and rationale

## Common Mistakes

- **Don't keep closed items in issue files.** Won't-fix, deferred, accepted, and by-design items belong in [decisions.md](decisions.md), not in domain files. Issue files are for actionable items only.
- **Don't reuse retired IDs.** If `GPU-003` is in decisions.md, the next GPU issue is not GPU-003 — check both files when allocating (see above).
- **Don't create without checking for duplicates.** Before adding a new issue, grep the prefix (e.g., `GPU-`) across all files in this folder to ensure the issue isn't already tracked.
