---
title: core.workflow-run is non-functional — bundled workflow has dangling delegations to an uninstalled plugin
status: "open — tracked as a known issue in the v0.2.0 release (owner decision 2026-07-16), not a release blocker. Spawned from tempdoc 734 round 5 finding #1; full evidence and repro live there, not duplicated here."
created: 2026-07-16
relation: split out of docs/tempdocs/734-0.2.0-sandbox-convergence.md (round 5, finding #1) by explicit owner decision — the release is not blocked on fixing a single non-core chat shape; it ships with this documented in the GitHub Release's Known Issues instead.
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
