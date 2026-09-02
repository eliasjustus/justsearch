---
title: "Hygiene registers: platform EOL register + check, committed OpenAPI snapshot + CI diff, TODO ratchet over tests/scripts, codebase-health series artifact, product glossary, feature-flag policy draft"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L14
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 792-stack-currency-audit-round-3   # §25 predicted "a record that never fires becomes decoration"
  - 583-localapiserver-structural-remedy  # §D.3c OpenAPI export; "per-route schema authority is a separate charter"
  - 530-class-size-ratchet-automation  # ratchet kernel; todo-fixme gate
  - 378-workaround-inventory           # stale since 2026-04; close it here
  - 509-operation-label-coherence      # operation labels only; F-22/F-25 naming collisions open
  - 754 / 799 (config-surface gate)    # the closest thing to a flag registry
  - 532-virtual-operation-catalog-ship-or-retract  # open since 2026-05 — owner decision, referenced by item 6
---

# 893 — Hygiene registers

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A8 (§8.2, 8.3, 8.6, 8.7) + A6 §6.8. Work in a
worktree. Six independent small items — one PR each, any order; items 1-3 are mechanical, 4-5
need writing judgment, 6 is a draft for the founder. Governance registers live in
`governance/*.v1.json` with a `scripts/ci/check-*.mjs` + `*.test.mjs`; kernel gates in
`scripts/governance/gates/<id>/` (load `/governance` before touching baselines). Canonical docs
edits → load `/docs-maintenance` and run the regen sequence it names.

## Scope and decisions

1. **Platform EOL register.** `governance/platform-eol.v1.json`: one row per pinned runtime
   dependency — JDK (25, LTS window), Gradle, Lucene major, Tauri, WebView2 policy, CUDA
   toolkit/driver floor, ONNX Runtime, Node, llama.cpp pin, Tesseract — with `supportedUntil`
   (ISO date or `null` + `source` URL) and the pin's source file. `scripts/ci/check-platform-eol.mjs`
   warns at 90 days, fails at 0 (gate mode), exits 0 in report mode; wire report mode into
   `ci.yml` as advisory. Seed dates from 792 §1 and vendor pages; cite each.
2. **OpenAPI snapshot.** `GET /api/meta/openapi.json` (`OpenApiController.java:20-33`) is
   composed at runtime and never committed. Add a unit-level exporter (same composition, no
   server) writing `contracts/http/openapi.snapshot.json`, plus `check-openapi-snapshot.mjs`
   that regenerates and diffs (idempotency gate, like the `check-*-regen` family); wire into CI.
   Do **not** attempt per-route request/response schemas (583 named that a separate charter).
3. **TODO ratchet scope + 378.** Extend `gates/todo-fixme` `sourceGlobs` to test sources,
   `scripts/**/*.{mjs,cjs,py,ps1}`, and `modules/shell/src-tauri/**/*.rs`; rebaseline (expect
   ~36 markers) via a changeset under `gates/todo-fixme/.changesets/`. Then close
   `378-workaround-inventory`: verify each of its "17 active" against `main`, mark
   resolved/still-present with `file:line`, set `status: closed (superseded by todo-fixme ratchet
   + 887 §S)`.
4. **Codebase-health series.** Decision: **artifact, not commits** — mirror
   `.github/workflows/ci-walltime-trend.yml`: a dispatch/scheduled job that runs the kernel gates
   in report mode, collects every `gates/*/baseline.txt` current value plus module count, test
   file count, and `cloc`-style LOC per module into `health-series.ndjson`, uploads it as an
   artifact, and appends to a rolling artifact. No complexity tool is added (PMD's `CyclomaticComplexity`
   rule in report mode is enough — include its per-module count).
5. **Product glossary.** `docs/reference/glossary.md`: Head/Body/Brain, run/operation/job,
   passage/chunk/document, leg/lane/stage, surface/window/rail, collection/root, spec/status,
   grant/consent tier — each with the *authoritative* definition site (`file:line` or doc §) and
   the UI label if it differs. Resolve or explicitly list 509's F-22 (multiple Ask entry points)
   and F-25 (Simple/Advanced naming). Link from `docs/llms.txt`; regenerate via
   `node scripts/docs/llmstxt-generate.mjs`. No gate — `writing-docs-for-ai.md:63` prefers
   inline definitions; the glossary is the index of where they live.
6. **Feature-flag policy draft** (doc only, in this tempdoc §F): what distinguishes an
   experimental knob from a permanent one; required fields (owner, introduced-in, expiry or
   promotion criterion); where it is declared (proposal: a `stage` attribute on
   `config-surface`'s matrix rows); the retirement gate. Reference 532 as the first case the
   policy would have resolved. The founder decides; you do not implement.
7. **Canonical-doc claim sweep** (added 2026-09-02; the lens 887 offered and never itemised).
   For every file under `docs/explanation`, `docs/reference`, `docs/how-to`: extract sentences
   that assert a mechanism exists or a behaviour holds ("X is enforced by Y", "Z runs in CI",
   "the walk applies …"), and verify each against `main` with a `file:line` or a run. Output: a
   table in this tempdoc §S of stale claims with the one-line fix, then apply the fixes in one
   docs PR (load `/docs-maintenance`). Known instances to seed the sweep: `03-knowledge-server.md:283`
   (half-stale exclude claim, 889), `05-ai-architecture.md:83-85` (CUDA "deferred", 887 §Z-2),
   `CLAUDE.md` "Build fails on PMD" (888), `check-ui-step-coverage.mjs` header (888),
   `ui-a11y-baseline.v1.json` description (888), `297:26`, `ApiSecurityFilters.java:186`. Do
   not build a doctest mechanism (non-goal); the sweep is a one-time audit plus a short
   "how to keep claims checkable" paragraph in `writing-docs-for-ai.md` pointing at the
   existing drift guards (`check-privacy-claims`, `check-frontend-stack-claims`).

## Acceptance criteria

- Items 1-2: new check + test pass; CI step visible in a PR run; `check-workflow-triggers` green.
- Item 3: `node scripts/governance/run.mjs --gate todo-fixme --mode gate` green at the new
  baseline; 378 frontmatter closed with per-item evidence.
- Item 4: one artifact produced by a dispatch run; link it in §Status.
- Item 5: `node scripts/docs/verify-canonical-doc-links.mjs` green; every glossary row has a
  pointer.
- Repo: `./gradlew.bat build -x test` (item 2 touches `modules/ui` tests).

## Constraints

- Do not add rules to `CLAUDE.md` or `.claude/rules/` (always-loaded budget ratchet).
- Non-goals: bumping any dependency (792 §3-4 is a separate execution), JPMS / encapsulation
  (lane 900), executable doc snippets.

## Status

(unstarted)
