---
title: "Continuous fuzzing: Jazzer targets for the extraction path, MCP JSON-RPC, HTTP API parsing, and proto decode, with a committed corpus and a scheduled job"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L12
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 410-adversarial-ingestion-resilience   # invariant 12 "No adversarial suite outside CI forever"; §1350 keeps fuzzing scheduled/manual
  - 767-camouflaged-injection-corpus-lane  # NOT fuzzing (eval payload integrity) — do not confuse
  - 885 (extraction pool / sandbox)        # the process boundary fuzzing must respect
  - docs/reference/security/threat-model.md
---

# 894 — Continuous fuzzing

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A7 §7.1. Work in a worktree. Existing adversarial
coverage is deterministic fixtures (`AdversarialCorpusIngestionTest.java`, `NastyCorpusTest.java`)
plus one random-byte MMF chaos thread (`MmfTestHarness.java:215-232`); there is no
coverage-guided fuzzer, corpus, or job. Public CI is hosted (ADR-0044); a **scheduled or
dispatch** workflow is the right home (410 §1350 already says "keep expensive fuzzing as
scheduled/manual work"). Jazzer's JUnit 5 integration (`@FuzzTest`) fits the existing test
layout; `gradle/libs.versions.toml` has no fuzz engine yet — add one, pin it, and let dependency
verification metadata be updated (`gradle/verification-metadata.xml`). Three PRs: engine +
first target, remaining targets, workflow.

## Thesis

The four surfaces that accept untrusted bytes — Tika extraction, `POST /mcp` JSON-RPC from
external agents, the HTTP API, and gRPC/proto between Head and Worker — are tested only against
hand-written hostile cases. Coverage-guided fuzzing finds the cases nobody wrote.

## Scope and decisions

1. **Engine.** Jazzer (`com.code-intelligence:jazzer-junit`) via a Gradle convention in
   `build-logic/` (`FuzzConventionsPlugin.kt`) that adds a `fuzzTest` source set per module,
   off the default `check` graph (same discipline as `MutationConventionsPlugin.kt`). Regression
   mode (replaying the committed corpus) **does** run under `test` so a found crash stays fixed.
2. **Targets** (one class each, minimal harness, 10-second default budget, timeouts on):
   - `StructuredContentExtractor` / `PolicyDrivenTikaExtractor` in-process with the policy's
     size and time budgets active — assert only "typed outcome, no uncaught throwable, bounded
     time". Respect 885: do not launch the sandbox child in the fuzz loop; fuzz the in-JVM path
     and note that the child is the second line.
   - MCP JSON-RPC dispatcher: raw bytes → the request decoder + tool dispatch with a stub tool
     surface; assert JSON-RPC error envelopes, never a 500 or a hang.
   - HTTP API: Javalin handler invocation through the existing test harness for the search and
     ingest routes with fuzzed query/body; assert `ApiErrorCode`-typed responses.
   - Proto: `SearchRequest`/`SearchResponse` and the status proto decode from fuzzed bytes
     (protobuf is robust; this mainly catches post-decode validation gaps).
3. **Corpus** committed under each module's `src/fuzzTest/resources/corpus/<target>/`, seeded
   from the deterministic fixtures; crashes get a `crash-<hash>` seed and a regression test.
4. **Workflow** `.github/workflows/fuzz.yml`: `workflow_dispatch` + weekly `schedule`, ubuntu,
   30 min per target, uploads findings as artifacts, opens nothing automatically (an agent or the
   owner triages). `check-workflow-triggers` must pass (ADR-0044 constrains triggers — read it).
5. **Threat model link.** Add a "fuzzed surfaces" line to
   `docs/reference/security/threat-model.md` §controls with the target list (canonical doc — load
   `/docs-maintenance`).

## Acceptance criteria

- `./gradlew.bat :modules:<m>:fuzzTest -Pfuzz.seconds=60` runs each target locally; regression
  mode runs under `./gradlew.bat test` and is green.
- At least one real finding or an explicit "0 findings in N minutes per target" table in §Status
  with the run link. A finding → fixed root cause + committed seed, never a catch-all.
- `node scripts/ci/check-workflow-triggers.mjs` green; `gradle/verification-metadata.xml` updated
  (`--write-verification-metadata sha256`), no `verify-signatures` change.
- `./gradlew.bat build -x test` green.

## Constraints

- No sandbox-child spawning inside fuzz loops (885); no dev stack; no GPU.
- Do not widen catch blocks to make a target "pass" (`fix-root-causes-not-symptoms`).
- Non-goals: UI fuzzing (`jseval ui-fuzz` exists), eval-corpus injection (767), Rust fuzzing
  (`updater.rs` verification is covered by unit tests; revisit only if the 4 `unsafe` blocks grow).

## Status

(unstarted)
