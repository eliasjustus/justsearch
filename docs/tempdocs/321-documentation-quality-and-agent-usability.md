---
title: "321 — Documentation Quality and Agent Usability"
---

# 321 — Documentation Quality and Agent Usability

created: 2026-03-17
status: complete
scope: docs

## Purpose

Comprehensive audit and improvement of canonical documentation (~120 files) for both correctness and structural quality, with a specific focus on making the docs more useful for Claude Code agents working on this project.

---

## Phase 1: Correctness Audit (COMPLETE)

Full read of all canonical docs, cross-referenced against the codebase and 75 tempdocs (250+).

### Completed Fixes

- [x] **ADR index gaps**: Added ADRs 0011-0014 to `decisions/README.md` and regenerated `llms.txt` (was missing 4 ADRs + 2 how-to docs)
- [x] **SSOT/ADR dangling references**: Removed `migrated_from`/`supersedes` frontmatter from ADRs 0011-0014 pointing to deleted `SSOT/ADRs/` directory
- [x] **Pipeline-schema residue**: Regenerated `module-deps.md` (removed `pipeline-schema` from 7 dependency edges + Mermaid diagram), removed stale `JUSTSEARCH_SIMULATE_BUDGET_MS` row from env-vars doc, removed 3 pipeline MDC keys from logging-conventions
- [x] **Lucene codec drift**: Fixed `Lucene103Codec` → `Lucene104Codec` in docs 04, 18, ADR-0003; fixed quantized format name to `Lucene104HnswScalarQuantizedVectorsFormat`
- [x] **Search pipeline evolution in doc 03**: Updated fusion section (CC is now 2-way default, not RRF), added LambdaMART cascade to reranking description
- [x] **CC fusion weight drift**: Fixed weights in docs 03, 18, env-vars from stale 0.35/0.35/0.30 to actual 0.60/0.20/0.20
- [x] **Method signature drift**: Fixed `isRerankerEligible` from 4 to 5 parameters in search-pipeline-invariants
- [x] **Model name corrections**: `Qwen3VL-8B-Instruct` → `Qwen3VL-8B-Thinking` in legal doc and ADR-0004
- [x] **Internal contradictions**: Fixed default slot count (→1) in doc 17, assertion count (→305+) in doc 21
- [x] **ADR-0012 superseded**: Marked as Superseded with note — JavaFX UI was fully replaced by React/Vite/TypeScript
- [x] **Missing frontmatter**: Added YAML frontmatter to `model-inventory.md` and `develop-ui.md` so `llms.txt` generator can index them
- [x] **Schema migration roadmap**: Deleted `docs/reference/schema-migration-roadmap.md` (3 months stale, milestones no longer tracked), removed references from 5 canonical docs
- [x] **LOC counts removed**: Removed fragile LOC counts from docs 17 and 18 (intro sentences, package structure, key classes table)

---

## Phase 2: Structural Improvements (COMPLETE)

### Completed Fixes

- [x] **Advisory contracts marked**: Changed status from `stable` to `advisory` on 3 resilience contracts. Updated `writing-docs-for-ai.md` and `llmstxt-generate.mjs`.
- [x] **Search pipeline deduplication**: Made doc 23 the single authority for tuning parameters. Replaced inline parameter values in doc 18 with cross-references (56 lines removed).
- [x] **Fragile numbers removed**: Removed exact counts from 8 files — LOC counts, line number references, PMD rule breakdowns, hook/signal/assertion counts, endpoint counts.
- [x] **Issue tracking simplified**: Migrated 7 risks from `architectural-risks.md` into domain issue files. Deleted `architectural-risks.md`. Updated `development-philosophy.md` to 2-tier model.
- [x] **ADR lifecycle**: Added review triggers to `decisions/README.md`.
- [x] **Doc 07 JavaFX vestige**: Removed "JavaFX bridge" from port discovery chain.
- [x] **observations.md cleanup**: Removed 7 resolved `[x]` items.
- [x] **Doc 22 agent tools**: Added missing `ingest_files` tool, fixed `file_operations` purpose, added `IngestTool` to module list.
- [x] **system_architecture_docs.txt**: Deleted redundant duplicate of `llms.txt`.

---

## Phase 3: Agent Usability Improvements (PARTIALLY COMPLETE)

### Industry Research (Jan-Mar 2026)

Key findings from recent publications on agent-facing documentation:

- **Instruction budget**: Frontier LLMs follow ~150-200 instructions consistently. CLAUDE.md + rules files must stay minimal. (HumanLayer, Anthropic)
- **Two dense files over many narrative docs**: Ops instructions + architecture map. Not human-friendly guides. (Marmelab)
- **Progressive disclosure**: Root CLAUDE.md ~60-150 lines. Domain knowledge in `.claude/skills/` on-demand. (Anthropic, AGENTS.md spec)
- **Capabilities over file paths**: Describe what modules do, not exact class names. (AGENTS.md best practices)
- **Verification is #1**: Tests and build commands matter more than architectural docs. (Anthropic)
- **Hooks beat instructions**: Deterministic enforcement over advisory rules. (Anthropic)
- **Maintain context files like code**: Review during architectural changes. (Packmind)

Sources: [Anthropic](https://code.claude.com/docs/en/best-practices), [HumanLayer](https://www.humanlayer.dev/blog/writing-a-good-claude-md), [Builder.io](https://www.builder.io/blog/claude-md-guide), [Marmelab](https://marmelab.com/blog/2026/01/21/agent-experience.html), [Cursor](https://cursor.com/blog/agent-best-practices), [Packmind](https://packmind.com/evaluate-context-ai-coding-agent/), [AGENTS.md](https://www.aihero.dev/a-complete-guide-to-agents-md), [Addy Osmani](https://addyosmani.com/blog/ai-coding-workflow/)

### Implemented

- [x] **3a. Workflow cheatsheet** (`.claude/rules/common-workflows.md`): 54 lines, 7 workflows + test commands. Works well but duplicates some test commands from CLAUDE.md — acceptable since this file groups them with workflows for context.
- [x] **3b. Hook reference** (`.claude/rules/hooks-reference.md`): 32 lines, documents all 6 hooks. Strongest addition — this information was previously inaccessible to agents.
- [x] **3d. Governance docs relocated**: 8 files moved from `docs/reference/contracts/` to `docs/governance/`. `llmstxt-generate.mjs` updated. Search results no longer polluted with advisory governance docs.
- [x] **3e. Agent-guide trimmed**: 472 → 400 lines (15% reduction). Removed duplicated philosophy/commands, renumbered sections. Cross-references updated in CLAUDE.md and api-evolution.md.
- [x] **3f. Doc 03 narrowed**: Removed search/fusion/reranking sections (~65 lines), replaced with cross-reference to doc 23. RAG Chunking retained (indexing-time operation).
- [x] **3g. Deep dives → capability descriptions**: Docs 17 and 18 package trees converted from class inventories to capability-oriented descriptions.
- [x] **3h. Domain knowledge skills**: 4 skills created: `search-pipeline`, `ai-inference`, `ipc-coordination`, `index-schema`.

### Self-Critique — Known Issues With Phase 3

1. **LOC counts re-introduced in worktree**: Docs 17 and 18 intro sentences still contain LOC counts (`~17,400 LOC`, `8,288 LOC`) because the worktree branched from main before Phase 2 edits landed. Must be removed.
2. **Skills contain fragile class-name mappings**: The skills list exact class names + module paths (`TextQueryOps in modules/adapters-lucene/`). This is the same fragility the research warns against. Skills should describe capabilities and how to find things, not hardcode class locations.
3. **Agent-guide reduction fell short**: Target was ~320 lines, achieved 400. The subagent was conservative. More transitional text could be trimmed.
4. **No cross-references from explanation docs to skills**: Docs 17, 18, 23 don't mention the skills exist. Progressive disclosure chain is incomplete.
5. **Net doc count increased**: From 92 to 95 indexed docs. Added 2 rules files + 4 skills + governance directory. The fundamental "too many docs" problem is not solved — it requires aggressive consolidation beyond single-session scope.
6. **Skills not tested**: YAML frontmatter format and directory structure not verified against Claude Code's actual skill loading.

### Remaining Work

- [x] **3-fix-a. Remove LOC counts from docs 17 and 18 intro sentences** — removed `~17,400 LOC` and `8,288 LOC`
- [x] **3-fix-b. Rewrite skills to use capability descriptions** — all 4 skills rewritten with grep patterns instead of class names
- [x] **3-fix-c. Add cross-references from explanation docs to skills** — blockquote pointers added to docs 17, 18, 23
- [x] **3-fix-d. Further trim agent-guide** — 401 → 347 lines (removed transitional prose, redundant intros, non-actionable trivia)
- [x] **3c. Delete `module-ownership.md`** — deleted stale doc. Agents grep code faster than reading a manually maintained ownership doc.
