---
title: "532 — VirtualOperationCatalog: ship with a real consumer, or retract"
type: tempdocs
status: open
created: 2026-05-20
category: substrate-fork / C-018
related:
  - tempdoc 521 §11 (the substrate as designed)
  - tempdoc 521 §14.β2 (the test-only live-verification claim)
  - tempdoc 527 §6.3 Hollow #1 (the audit finding)
  - tempdoc 533 (first plugin scaffold — natural consumer candidate)
  - tempdoc 534 (LANGUAGE_WORKFLOW substrate — another candidate consumer)
---

# 532 — VirtualOperationCatalog: ship or retract

## What's there

521 designed virtual operations as a way for plugins (and the shell itself) to expose agent-visible verbs without backend changes:

- `VirtualOperationCatalog.ts` registers `{ id, label, schema, handler }`.
- `bootVirtualOperationCatalog()` populates the catalog at boot.
- `VirtualToolDispatcher.ts:48` provides `resolveAgentToolCall(name): VirtualOperation | null`.
- `AgentSessionController.ts:303-305` calls `dispatchVirtualToolCall(...)` before falling through to non-virtual dispatch.

The wiring is real. The runtime behavior is: catalog always empty in production → resolver always returns null → dispatcher always falls through → behavior identical to a system where the catalog doesn't exist. Per 527's audit, the live-verification claim ("agent-visible command, `vop_<name>` entry in tool list") was test-only.

## The fork

C-018 forces a decision:

(a) **Ship**: bootstrap the catalog from `Shell.connectedCallback`, and ship at least one real virtual operation. The substrate then has a production consumer at the moment its review next runs.

(b) **Retract**: delete `VirtualOperationCatalog`, `VirtualToolDispatcher`, and the unused dispatch site in `AgentSessionController`. Restore the path to its pre-521 shape.

Both are honest. The bad answer is to leave it where it is — substrate present, no production effect, agent-tool-call resolver carrying dead code.

## Candidate first consumers (for the "ship" branch)

Three shapes plausibly need virtual operations:

### Candidate A — Templated agent commands

521 ships a `TemplateCatalog` with one core template (`core.find-related`). Templates project to commands today. If a user defines a template `summarize-with-questions: "{prompt}\n\nThen list 3 followup questions"` and wants the agent to invoke it, the natural shape is a virtual operation `vop_summarize_with_questions(input: string)` whose handler builds the prompt and dispatches through `ConversationEngine` with the appropriate shape.

Composition: TemplateCatalog → VirtualOperationCatalog projection at boot. Each template becomes a virtual op. The agent's tool list grows with user-authored templates. Tier-attenuation: only `CORE` and `TRUSTED_PLUGIN` templates project (user-authored templates inherit user trust; UNTRUSTED templates don't).

### Candidate B — Plugin-contributed agent verbs

When 533 (first plugin) lands, it may want to expose a verb to the agent without backend changes. E.g., a Markdown-checklist plugin could expose `vop_extract_checklists(docId: string)` → returns structured list. The handler runs in the plugin sandbox (via `PluginHostApi.data` + `ai`). The agent calls it like any other tool; the trust lattice gates on `core.plugin-emitted` source tier × operation risk.

This is the design's original intent. The substrate goes from speculative to consumed at the moment any plugin needs to expose one verb.

### Candidate C — Workflow-defined operations

When 534 (LANGUAGE_WORKFLOW) lands, each user-authored workflow becomes a verb. A workflow `triage-emails` becomes `vop_triage_emails`. The handler invokes the workflow runner. Workflows that pause for human gates surface as agent-visible operations that may return `pending` and resume later.

## Composition with retraction (the "retract" branch)

If none of the candidates above land within a reasonable grace period (per 531), the right move is retraction:

- Delete `VirtualOperationCatalog.ts`, `VirtualToolDispatcher.ts`, the catalog boot stub, and the resolver call.
- `AgentSessionController.dispatchVirtualToolCall` collapses into "dispatch via OperationDispatcher or fail."
- The 521 design retains the *shape* (as a future possibility) but the substrate stops carrying its weight.

Retraction is not a defeat. It is the discipline 512 §F1 named: when speculative substrate doesn't earn a consumer, delete it. ADR-0014 is the precedent (pipeline-engine deleted after the runtime never matched the abstraction).

## What this is NOT

- Not a deferral. The decision is forced by 527's audit; sitting on it is the failure mode.
- Not a relabeling. Renaming the catalog doesn't fix C-018; only a real consumer does.
- Not a forcing function on plugins. If 533 doesn't need virtual ops, that's fine — Candidate A (templates) or Candidate C (workflows) can carry the load instead. The forcing function is on *some* real consumer, not on plugins specifically.

## Recommendation

Pair this decision with 533's choice. If the first plugin needs an agent-visible verb that doesn't have a backend Operation analogue, ship Candidate B as the consumer. Otherwise, ship Candidate A (templates) as the consumer — it's small, plausible, and has multiple template entries already in 521. If neither is ratified, retract.

The honest order is: 533 first, this tempdoc resolved second, on the basis of what 533 needed.

## Open questions

- **What's the trust tier of a virtual operation?** Templated-from-CORE: `CORE`. Plugin-emitted: matches plugin tier. Workflow-derived: `CORE` if the workflow is core; otherwise inherits.
- **Does a virtual op declare `RiskTier`?** Yes; the registration carries it. The trust lattice gates as for any other op.
- **Are virtual ops visible to MCP (500)?** Probably not by default — MCP exposes a curated list. Virtual ops are agent-visible (in-process), not external-visible.
