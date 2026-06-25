---
description: "TRIGGER when: performing periodic documentation review, analyzing tempdoc-to-canonical drift, planning documentation updates, or asked to audit doc freshness. Loads the systematic drift analysis procedure."
user-invocable: true
---

# Documentation Audit

Systematic procedure for identifying and resolving drift between tempdocs
(implementation records) and canonical docs (must-not-drift reference).

## Canonical vs Noncanonical

- **Canonical (must not drift):** `docs/explanation/`, `docs/reference/`, `docs/how-to/`, `docs/decisions/`
- **Noncanonical (working notes):** `docs/tempdocs/`, `docs/future-features/`, `docs/observations.md`

If docs disagree, canonical is truth. If canonical is unclear, verify against code + contract tests.

## Audit Procedure

### Step 1: Identify Recent Tempdocs
```bash
ls -lt docs/tempdocs/ | head -N
```

### Step 2: For Each Tempdoc, Analyze Canonical Impact
For each tempdoc, determine:
1. Which canonical docs does it affect? (Check the doc index at `docs/llms.txt`)
2. What specifically changed? (New features, API changes, model swaps, architecture decisions)
3. Does it warrant a new ADR? (Load-bearing decision with alternatives considered)

### Step 3: Categorize Updates
- **Stale facts** — wrong model names, deleted modules still listed, "planned" features now shipped
- **Missing content** — new API endpoints, new pipeline stages, new schema fields undocumented
- **New ADRs needed** — architectural decisions only recorded in tempdocs

### Step 4: Plan Updates by Topic Cluster
Group affected canonical docs by domain (API contract, search pipeline, model inventory, etc.)
to minimize context switching and cross-reference errors.

### Step 5: Execute and Verify
After all edits, run the full docs verification suite:
```bash
node scripts/docs/verify-canonical-doc-links.mjs
node scripts/docs/llmstxt-generate.mjs
node scripts/docs/skills-sync.mjs
node scripts/architecture/module-deps.mjs --check-canonical
```

## Common Drift Patterns

| Pattern | Example | Impact |
|---------|---------|--------|
| Model swap | Tempdoc swaps reranker; model-inventory still shows old model | Agents use wrong model name in prompts/configs |
| Module deletion | Tempdoc deletes `app-ai`; module-architecture still lists it | Agents try to find/modify deleted code |
| Feature activation | Tempdoc ships entity facets; ADR-0007 says "future feature" | Agents treat shipped feature as unimplemented |
| API expansion | Tempdoc adds response fields; contract map is incomplete | Frontend/agent consumers miss new capabilities |
| New invariant | Tempdoc establishes pipeline rule; invariants doc is missing it | Agents violate the rule unknowingly |
