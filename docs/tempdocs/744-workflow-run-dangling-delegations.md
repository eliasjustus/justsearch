---
title: core.workflow-run is non-functional — bundled workflow has dangling delegations to an uninstalled plugin
status: "FIXED 2026-07-16 (sibling code PR, worktree-v020-followups). Originally tracked as a known issue in the v0.2.0 release (owner decision 2026-07-16) while the diagnosis was still black-box; source investigation the same day found the real, small root cause and fixed it before that framing was ever needed for a release. Spawned from tempdoc 734 round 5 finding #1; full evidence and repro live there."
created: 2026-07-16
relation: split out of docs/tempdocs/734-0.2.0-sandbox-convergence.md (round 5, finding #1). Originally by explicit owner decision to ship as a known issue rather than block the release on a single non-core chat shape — superseded same-day once the actual fix turned out to be small and well-scoped (see "Fixed" below).
---

# core.workflow-run is non-functional — bundled workflow has dangling delegations to an uninstalled plugin

## Problem

Every invocation of the `core.workflow-run` chat shape fails with the identical error, regardless
of prompt content or which of the two documented entry points is used:

```
POST /api/chat/agent {"shapeId":"core.workflow-run", ...}
POST /api/chat/dispatch {"shapeId":"core.workflow-run", ...}

{"error":"Workflow core.demo-compose has dangling delegations: [act: ToolStep delegates to
unknown operation vendor.mcphost.reference-add, show-image: ToolStep delegates to unknown
operation vendor.mcphost.reference-get-image]","errorCode":"BAD_REQUEST"}
```

Reproduced 3× via 2 independent endpoints in Sandbox round 5 (tempdoc 734), with AI fully
online — this is not an offline gate, it fails unconditionally.

## Root cause (as understood from round 5's evidence)

`core.workflow-run` appears to unconditionally resolve to a single bundled workflow definition,
`core.demo-compose`, rather than routing to either of the two workflow tools that are actually
registered (`core_workflow_demo_compose`, `core_workflow_research_brief`). `core.demo-compose`'s
definition references two `ToolStep` delegations — `vendor.mcphost.reference-add` and
`vendor.mcphost.reference-get-image` — belonging to what looks like an optional/uninstalled
MCP-host plugin. The workflow fails validation before any work happens.

This needs verification against source before design — round 5 was a black-box Sandbox
validation with no repo checkout, so the above is an evidence-based hypothesis, not a
source-verified diagnosis. **First implementation step should be locating and reading:**
- The `core.demo-compose` workflow definition (wherever bundled workflow definitions live).
- Wherever `shapeId: "core.workflow-run"` is dispatched, to confirm it really does pin to one
  workflow rather than selecting among registered ones.
- What `vendor.mcphost.*` is — a real optional plugin this candidate simply doesn't bundle, or a
  stale/phantom reference that should never have shipped.

## Also open from round 5

No GUI entry point for `core.workflow-run` was found either (see tempdoc 734 round 5,
`chat-shapes-coverage-note.txt` / `retrospective.md`), so it's unknown whether typical end users
can even reach this broken path via the UI, or whether exposure is API/MCP-only. Worth
establishing before or alongside the fix — it changes the actual user-facing severity.

## Suggested fix shape (from round 5's routing recommendation, not yet designed)

- Fix or remove `core.demo-compose`'s dangling delegations so the bundled workflow is either
  functional or absent, not broken-by-default.
- Confirm `shapeId` dispatch actually offers a choice among registered workflow tools rather than
  pinning to one bundled definition.
- **Regression home**: a build-time check that every `ToolStep` delegation in a bundled workflow
  definition resolves to a registered operation — this class of defect should fail the build
  before it can ship again, not just get caught by a Sandbox round.

## Why this is a known issue, not a release blocker (owner decision 2026-07-16)

`core.workflow-run` is one chat shape among several (search, RAG-ask, extract, free-chat,
agent-run all work); it has no established GUI entry point yet either, so its real-world blast
radius is likely small. The owner's call: track and fix this on its own timeline rather than
holding the v0.2.0 release hostage to one non-core shape. The release's GitHub Release notes
carry a Known Issues entry pointing at this tempdoc; `docs/tempdocs/734-...` (the release's
convergence tempdoc) records the reclassification alongside the rest of round 5's findings.

This is a scoping decision about *whether it blocks the release*, not a judgment that the defect
itself is unimportant or that it's fine to leave broken indefinitely — `structural-defects-no-repeat`
still applies once this is picked up: it should be fixed with a regression test, not silently
tolerated forever because it shipped once as a known issue.

## Fixed (2026-07-16, same day) — the diagnosis above was directionally right, in more detail than expected

Read the actual source before implementing, per this doc's own "first implementation step" list
above. The real picture was more precise than the black-box hypothesis:

- **`core.demo-compose` is not a mistake — it's a deliberate reference workflow.** Its own javadoc
  (`CoreWorkflowCatalog.java`) states it "exercises all three node kinds in the canonical
  think → confirm → act shape, proving the substrates compose" against a real external MCP tool.
  It is *supposed* to need `vendor.mcphost.*` — that's the point of it existing.
- **The catalog already had a second, fully self-contained workflow**: `core.research-brief`
  ("think → draft", two `LlmStep`s delegating only to `core.free-chat`) — its own javadoc already
  said "unlike demo-compose, which needs the vendor.mcphost.* pack to validate, it runs in any
  stack with the chat model loaded." The fix wasn't building something new; it was routing to what
  already existed.
- **`shapeId` dispatch already supported an explicit choice** (`WorkflowShapeRunner.resolveWorkflowId`
  reads an optional `workflowId` from the request body) — this doc's "confirm shapeId dispatch
  offers a choice" question was already yes. The actual bug was narrower: the *fallback* when no
  `workflowId` is given was hardcoded to `CoreWorkflowCatalog.DEMO_ID`, guaranteeing failure for
  every caller who didn't know to pass one explicitly — exactly the round's own repro.
- **A GUI entry point does exist** (`UnifiedChatView.renderWorkflowTrigger`, a `<select>` workflow
  picker) — this doc's "also open" question is answered: not API/MCP-only. But its default
  selection (`this.workflows[0]`) inherited the same underlying mistake, since the catalog listed
  `demoCompose()` before `researchBrief()` — a user who found the picker and just clicked "Run
  workflow" without changing the dropdown would have hit the identical failure.

**Fix** (commit in `worktree-v020-followups`, sibling to this tempdoc's release-doc PR): reordered
`CoreWorkflowCatalog.catalog()` to list `research-brief` first (fixing both the FE picker's default
and, combined with the second change, the API fallback), and made `resolveWorkflowId`'s fallback
explicit (`RESEARCH_BRIEF_ID`, not order-dependent on `DEMO_ID`).

**Verified, not just green tests:**
- New regression test (`WorkflowShapeRunnerTest.noWorkflowIdDefaultsToResearchBriefNotTheMcpDemo`)
  uses the REAL `CoreWorkflowCatalog`, not a synthetic one, and asserts the run actually executes
  (two `core.free-chat` dispatches observed in the test log, matching research-brief's two
  `LlmStep`s) — not merely that validation passes.
- Live-verified against a real dev stack: the round's exact repro
  (`POST /api/chat/dispatch`, `shapeId:"core.workflow-run"`, no `workflowId`) no longer returns
  "dangling delegations" — `workflow_started` now reports `workflowId: "core.research-brief"`, and
  the run proceeds to `node_started`. The only remaining failure ("AI service unavailable") is the
  separate, already-documented `ai_activate`-unavailable-in-a-worktree limitation (tempdoc 734),
  not a reproduction of this bug.
- Full `app-services` test suite green, full pre-merge build (`gradlew build -x test`) green.

**Regression home**: the new `WorkflowShapeRunnerTest` case is the regression home for the default
specifically. The build-time "every `ToolStep` delegation resolves to a registered operation"
check this doc originally suggested is still worth having independently (it would catch a
*genuinely* broken bundled workflow, as opposed to a wrong default pointing at an
intentionally-external-dependent one) — not built here, since that wasn't the actual defect; left
as a separate, smaller possible follow-up if `core.demo-compose` itself ever needs hardening.
