---
title: "Project operations: devcontainer + cross-platform bootstrap, good-first-issue set, on-wire deprecation signal, succession document skeleton, crash-report stance input"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L17
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 656-five-minute-agent-runtime-onramp   # doctor, tiered onramp, CONTRIBUTING sections
  - 631-go-public-publish-machinery / 633-go-public-launch-content / 634-cutover
  - 654-local-runtime-contract-and-product-center / 655-mcp-conformance-and-capability-policy
  - 660-plugin-sdk-community-onramp        # open since 2026-06-28, unstarted
  - 802-release-artifact-provenance        # SPDX/step-3 owner decision pattern
---

# 899 — Project operations

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A10 (§10.1, 10.2, 10.5, 10.6) + A3 §3.6. Work in a
worktree. Root community files (`CONTRIBUTING.md`, `MAINTAINING.md`, `SUPPORT.md`,
`.github/`) are public-facing: `check-root-readme` and the docs-lint checks apply. Anything
outward-facing that is not a file in this repo (creating GitHub labels/issues, npm publish) is
**drafted here for the founder**, not executed. Five small PRs.

## Thesis

Contributor onboarding is real but Windows-only and agent-shaped (656): no devcontainer, a
PowerShell-only bootstrap, "good first issue" as a phrase with no labels or list. Bus factor is
one name in CODEOWNERS with no succession document. The runtime contract has a 90-day
deprecation policy but no on-wire signal — a client learns of a removal by reading a doc. There
is no runtime-contract client SDK in any language, and 660 has sat open since June.

## Decisions made for you

- **Devcontainer:** `.devcontainer/` on `ubuntu` with JDK 25 (Temurin), Node 24, Python 3.12,
  Rust stable; `postCreate` runs `scripts/dev/doctor.mjs`. Tier-0 keyword search must work
  inside it (CI already builds on ubuntu). No GPU expectation.
- **Bootstrap:** replace `scripts/setup/bootstrap-node-win.ps1`'s role with a Node
  `scripts/setup/bootstrap.mjs` that detects OS and delegates; keep the `.ps1` as the Windows
  implementation (do not delete — `retire-with-a-sweep` applies if you do).
- **On-wire deprecation:** HTTP — `Deprecation` + `Sunset` headers (RFC 8594/9745 shapes) emitted
  by the route manifest for any route with a `deprecatedSince` field; MCP — a `deprecated: true`
  + `sunset` entry in each tool's `annotations` and in the `initialize` capabilities descriptor.
  Single source: the route manifest / `McpToolSurface` catalog; document in
  `docs/reference/runtime-contract.md` §Stability policy. Wire gate (`--gate wire`) + contract
  tests.
- **SDK:** decision — **TypeScript first, generated from the OpenAPI snapshot** that lane 893
  commits; **blocked until 893 item 2 lands**. Do not hand-write a client. Record the LangChain /
  LlamaIndex retriever question as a follow-on in 660 and close 660's "unstarted" status by
  pointing here.
- **Crash reporting:** no submit path (NON-GOALS). Instead: a "Copy diagnostic summary" action
  that puts a redacted, size-bounded text summary (from the 658 bundle's index) on the clipboard
  for pasting into a GitHub issue — user-initiated, no network. Draft the issue-template field
  that receives it.

## Scope

1. Devcontainer + bootstrap + CONTRIBUTING update; verify the Tier-0 onramp
   (`scripts/dev/test-onramp-first-success.mjs`) inside the container (Codespaces or local).
2. `good-first-issue`: a `.github/labels.yml` (documented, applied by the founder via
   `gh label create` — draft the commands in §Status) and five curated starter issues drafted
   in §Status with file pointers and acceptance criteria; the founder opens them.
3. On-wire deprecation (headers + MCP annotations + doc + tests).
4. `MAINTAINING.md` §Succession skeleton: what a second maintainer needs (signing credential
   mode, release workflow secrets list by *name* only, npm/GitHub org roles, model-registry
   upstream accounts, the private cutover package's existence). Placeholders for values only the
   founder knows.
5. "Copy diagnostic summary" action (FE + a small Head endpoint reusing 297's redaction) + the
   bug-report template field.

## Acceptance criteria

- Item 1: container builds; onramp test green inside it; link the run.
- Item 3: `node scripts/governance/run.mjs --gate wire --mode gate`, `check-dev-mcp-doc-sync` if
  touched, contract tests green; a deprecated test route emits both headers.
- Item 5: ui-web gates + typecheck/tests; the summary contains no absolute user paths (297
  redaction test extended).
- `node scripts/ci/check-root-readme.mjs` and docs-lint green.

## Constraints

- No outward actions (labels, issues, npm publish, secrets) — draft only.
- Non-goals: KPI/telemetry (NON-GOALS), SDK implementation before 893 lands, per-client MCP
  identity.

## Status

(unstarted)
