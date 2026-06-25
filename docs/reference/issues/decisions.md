---
title: Closed Decisions
type: reference
status: stable
updated: 2026-03-19
description: "Evaluated items intentionally closed with rationale preserved."
---

# Closed Decisions

Items evaluated and intentionally closed. Original issue IDs are preserved for traceability — do not reuse these IDs for new issues.

---

## Won't-fix

Items where the limitation is real but fixing is not justified.

| ID | Title | Date | Rationale |
|----|-------|------|-----------|
| UIX-001 | `loadMore` doesn't clear `correctedQuery` | 2026-02-02 | Intentional — matches Google behavior. Correction applies to the entire result set, not a single page. |
| UIX-002 | File type icons limited to 5 types | 2026-02-02 | Backend `file_kind` vocabulary is fixed. Frontend icon mappings for undistinguished types would have no effect. |
| UIX-003 | 2x DPI screenshot capture blocked by Claude API | 2026-02-02 | `deviceScaleFactor: 2` produces 2560×1440, exceeding Claude API's 2000px image limit. External constraint. |
| UIX-004 | Glassmorphism not verifiable in headless screenshots | 2026-02-02 | Playwright renders without desktop compositor — `backdrop-filter` doesn't render. Fundamental limitation. |
| GPU-012 | Hardcoded DLL size thresholds for CUDA detection | 2026-02-02 | Thresholds (400MB static, 200MB dynamic) are stable. Config adds noise for zero benefit. One-line code update if upstream sizes change. |
| INF-003 | Protobuf backward compatibility test | 2026-02-03 | Buf `WIRE` breaking rules in CI (`buf.yaml`). Protobuf wire format handles unknown fields by design. 33 proto commits in 2 months with no compatibility incidents. |

## Deferred

Valid improvements not worth doing now. Each includes a revisit trigger.

| ID | Title | Date | Rationale | Revisit when |
|----|-------|------|-----------|--------------|
| UIX-005 | useSystemStore decomposition into Zustand slices | 2026-02-02 | Store works correctly (778→525 lines). Migration risk outweighs benefit. | Store grows significantly or state interactions become hard to reason about |
| UIX-007 | InspectionPane hook chain prop threading | 2026-02-02 | `useAiCapabilities()` works correctly with arg threading. | AI capabilities move to a dedicated Zustand store |
| INF-001 | MCP tools for Gradle build/test | 2026-02-03 | Bash fallback documented in `mcp-dev-tools.md`. MCP wrappers add maintenance surface for zero capability gain. | MCP server gains sandboxed command execution |
| INF-002 | Central test registry mapping features to test classes | 2026-02-03 | Naming convention (`*Test.java` mirrors source class) works for class-level coverage. Registry needs constant maintenance. | Cross-cutting test discovery becomes recurring friction |
| EXC-001 | Exclude patterns UI hidden behind Advanced mode | 2026-02-06 | Simple mode users benefit from hardcoded Worker skip lists. Custom patterns are a power-user feature. | User research shows Simple-mode users need custom exclusions |
| EXC-002 | File watcher doesn't filter excluded dirs at source | 2026-02-06 | `DirectoryWatcher.Builder` (methvin v0.18.0) has no filter/exclude API. Events are filtered at the handler. | Library adds filter API, or we replace with raw JDK `WatchService` |

## Closed (by design)

Not actually issues — behavior is intentional.

| ID | Title | Date | Rationale |
|----|-------|------|-----------|
| UIX-008 | MCP `api_call` has no separate query param field | 2026-02-02 | Params embed in path string. Adding a field adds schema complexity for marginal ergonomic benefit on a dev-only tool. |
| UIX-009 | MCP `api_call` body schema uses `z.any()` | 2026-02-02 | Permissive by design — some endpoints accept arrays or primitives. Internal dev tool. |
| UIX-015 | Search empty-state assumed a zero-corpus first run | 2026-05-30 | First-run corpus is **not** empty: 5 built-in help docs (`SSOT/docs/help/*.md`) are bundled (`tauri.conf.json` ships `resources/headless/**/*`) and auto-ingested into the `justsearch-help` collection on first worker connect (`KnowledgeServerBootstrap.tryIngestHelpFiles`, marker-guarded, eval-mode-skipped) — and are not excluded from search. So `SearchSurface`'s "Type to search across all indexed files" message is accurate. The original "empty first-run" finding (the `sandbox-validation` tempdoc, 2026-04-02) was real *then* but closed by the help-seeding added in the `app-packaging-and-distribution` alpha series. Briefly opened 2026-05-30 from a static FE-only read (missing `documentCount===0` branch) before the seeding path was verified. |

## Closed (scope boundary)

Environment or platform limitations, not product defects.

| ID | Title | Date | Rationale |
|----|-------|------|-----------|
| UIX-010 | System tests not runnable in agent sessions | 2026-02-02 | Require multi-JVM process spawning gated behind `-PincludeSystemTests=true`. |
| UIX-011 | Playwright E2E not runnable in agent sessions | 2026-02-02 | Require dev stack + browser binaries (`npx playwright install`). |
| UIX-012 | SSE streaming not verifiable in agent sessions | 2026-02-02 | Require loaded AI model (llama-server + GGUF). No MCP tool supports streaming. |

## Accepted (trade-off)

Known ARIA/accessibility trade-offs accepted as intentional design choices.

| ID | Title | Date | Rationale |
|----|-------|------|-----------|
| ACC-002 | axe-core `nested-interactive` on result rows | 2026-02-02 | Conditional `tabIndex` on cursor row triggers the warning. Removing buttons degrades UX. Matches VS Code / GitHub patterns. |
| ACC-003 | Agent infrastructure maturity gaps | 2026-03-17 | Local-first single-agent architecture is a deliberate strength (privacy, simplicity). Multi-agent handoffs, model routing, and layered context management are deferred until concrete triggers (tempdoc 211 for multi-agent, compression A/B gate, second model availability). See [Agent System Architecture](../../explanation/22-agent-system-architecture.md). **Update (2026-06-25):** the multi-agent *handoff infrastructure* has since shipped — tempdoc 211 (M0) is `done` and wired (`AgentProfile` / `agentProfiles` on `AgentRequest`, `AgentHandoff` + system-prompt swap in `AgentLoopService`/`AgentStepRunner`). The runtime default remains single-agent (`"primary"`) and there is no default agent roster or UX exposure — multi-agent is an available capability only when a caller supplies `agentProfiles`. So "single-agent by default" still holds, but "deferred until tempdoc 211" is satisfied for M0; full productization (default roster, UI) remains deferred. |
| RAG-010 | Single embedding model per index — upgrade requires full reindex | 2026-03-19 | Blue/green schema migration handles schema changes; vector reindexing is derived data. Acceptable until incremental vector reindexing is feasible. |

## Superseded (infrastructure replaced)

Items whose tracked infrastructure was replaced or deleted, making the original issue moot.

| ID | Title | Date | Rationale |
|----|-------|------|-----------|
| SRQ-002 | No passage-level retrieval for interactive search | 2026-03-19 | Resolved: Stage 3a/3b chunk-level fusion (tempdocs 274, 280). |
| ACC-001 | Result row quick actions unreachable by keyboard | 2026-03-19 | Resolved: Conditional tabIndex + Shift+F10 context menu (2026-02-19). |
| BKD-008 | BurstDetector non-atomic check-then-act | 2026-03-19 | By design: single-thread-per-root access model; `ConcurrentHashMap` handles cross-root safety. |
| BKD-009 | gRPC retry policy gap in 3 non-RKC clients | 2026-03-19 | Resolved: Shared resilience primitives wired (2026-02-19). |
| BKD-010 | Health check coverage gap — AI readiness not checked | 2026-03-19 | Resolved: AI/embedding readiness propagation to `/api/status` (2026-02-19). |
| RAG-007 | Reranker model upgrade blocked on quality evaluation harness | 2026-03-19 | Resolved: GTE-ModernBERT shipped (register D-001). Eval harness exists (`scripts/jseval/`). |
| RAG-008 | BGE-M3 embedding upgrade deferred | 2026-03-19 | Resolved: BGE-M3 integrated (tempdoc 322, commit `c52890dba`). |
| BEN-004 | No repeatable model evaluation harness | 2026-03-19 | Resolved: `scripts/jseval/` provides ir-measures, BEIR loading, statistical testing. |
| BEN-005 | BEIR gate hybrid profile non-functional in CI | 2026-03-19 | Resolved: ANN provenance proof implemented (Phase AE, 2026-02-25). |
| BEN-001 | Query latency methodology invalid for process-based competitors | 2026-03-19 | Moot: `scripts/bench/competitors/` deleted during eval consolidation. |
| BEN-002 | No end-to-end pipeline benchmark adapter | 2026-03-19 | Moot: `scripts/bench/` replaced by `scripts/jseval/` with full pipeline eval. |
| BEN-003 | No filename-only indexing benchmark lane | 2026-03-19 | Moot: `scripts/bench/competitors/` deleted. |
| BLD-001 | Benchmark script duplication | 2026-03-19 | Moot: `scripts/bench/` deleted; replaced by unified `scripts/jseval/` toolchain. |
| BLD-002 | Script directories lack README files | 2026-03-19 | Moot: `scripts/bench/` deleted. Remaining script dirs have sufficient structure. |
| BLD-003 | No HdrHistogram for latency benchmarks | 2026-03-19 | Moot: Original benchmark harnesses in `modules/benchmarks/` superseded by jseval. |
| BLD-004 | Benchmark scripts are PowerShell-only | 2026-03-19 | Moot: Eval stack rewritten in Python (`scripts/jseval/`). |
| BLD-005 | Five different benchmark result JSON schemas | 2026-03-19 | Moot: jseval uses unified `summary.json` schema. |
