---
description: "TRIGGER when: editing files in docs/explanation/, docs/reference/, docs/how-to/, docs/decisions/, or after creating/modifying canonical documentation. Loads the post-edit regeneration sequence and doc quality rules."
user-invocable: true
---

# Docs Maintenance

After editing any canonical doc, run this regeneration sequence.

## Post-Edit Regeneration (required)

Run these commands after every canonical doc change:

```bash
# 1. Always — regenerate the docs index
node scripts/docs/llmstxt-generate.mjs

# 2. Always — sync skills from canonical docs (if any synced skill sources changed)
node scripts/docs/skills-sync.mjs

# 3. After module architecture changes — update dependency graph
node scripts/architecture/module-deps.mjs --update-canonical

# 4. After configuration changes — update runtime config matrix
node scripts/docs/generate-runtime-config-matrix.mjs --write-doc docs/reference/configuration/runtime-config-ownership-matrix.md
```

Steps 1 and 2 are always required. Steps 3 and 4 are conditional.

## Verification (CI gate)

```bash
node scripts/docs/llmstxt-generate.mjs --check
node scripts/docs/skills-sync.mjs --check
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/architecture/module-deps.mjs --check-canonical
node scripts/docs/verify-runtime-config-matrix.mjs
```

For prompt-surface or agent-instruction changes, also run:

```bash
node scripts/docs/prompt-surface-inventory.mjs
```

This reports prompt-like surfaces, generated/manual status, size, and
suspicious stale tokens. It is a drift-control report, not an agent-quality
metric.

## Doc Quality Rules

### Canonical vs Noncanonical
- **Canonical (must not drift):** `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/decisions/`
- **Noncanonical (allowed to drift):** `docs/tempdocs/`, `docs/future-features/`, `docs/observations.md`

### Link Rules
- Canonical docs **must not** reference tempdocs (`docs/tempdocs/`). CI lint will reject this.
- Replace tempdoc cross-references with source file references or canonical doc references.

### Frontmatter Requirements
Every canonical doc needs YAML frontmatter:
```yaml
---
title: "Document Title"
type: explanation | reference | how-to | decision
status: stable | draft | deprecated
description: "One-line summary."
---
```

### Writing Style (Agent-Friendly)
- Context-independent paragraphs (each should stand alone in RAG retrieval)
- Flat Markdown (no complex HTML tables, no images with critical text)
- Specific names: "The **IndexingLoop** updates the **SQLite JobQueue**" not "The system updates the database"
- Tables over prose for structured data
- Code blocks with language tags (MD040 lint rule)

### ADR Template
New ADRs use MADR-lite: Status / Context / Decision / Consequences / Alternatives Considered.
Next available number: check `docs/decisions/README.md` Decision Log table.
