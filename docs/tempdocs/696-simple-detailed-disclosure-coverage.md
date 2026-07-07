---
title: "696 — Simple/Detailed disclosure coverage: bring user-facing technical strings under the one uiMode authority"
type: tempdoc
status: in-progress
created: 2026-07-08
related:
  - 557 (uiMode / Simple-Advanced authority origin, Q8)
  - 586 (uiMode rail-trim consumer, F-2)
  - 687 (Search Thread — degradation banner + collapsed pill)
  - 565 (run-step presentation authority)
  - docs/explanation/27-frontend-presentation-kernel.md (the authority pattern this conforms to)
---

# 696 — Simple/Detailed disclosure coverage

## Problem

The shipped UI shows internal/system vocabulary to every user regardless of their Simple/Advanced
preference: the search-degradation banner names its raw cause ("Learned re-ranking (LambdaMART) is
not configured"); the results meta line shows a millisecond timing and an internal mode token
("… · 178ms · Keyword"); result rows show a raw filesystem path; the agent surface shows the model's
raw reasoning body, the model name, and a raw "Paused — awaiting budget" state. None of these consult
the user's Simple/Advanced preference.

This is **incomplete coverage of an existing authority**, not a missing feature — the
representation-drift class the frontend presentation kernel exists to prevent
(`docs/explanation/27-frontend-presentation-kernel.md`). `state/uiModeState.ts` is the app-wide
Simple/Advanced authority (tempdoc 557 Q8); today its only live consumer is the `Shell` rail-trim
(tempdoc 586 F-2). The technical strings above were never brought under it.

The related "banner is oversized" observation is a **downstream symptom**: the degradation banner is
tall only because it renders its raw causes expanded. Gating the causes on disclosure makes it a slim
pill by default — no size-budget structure is needed.

## Approach

Complete the coverage. Each user-facing string that encodes an internal fact becomes a
**plain-or-technical projection gated by `uiMode`**, defaulting to plain (Simple). This is the
disclosure sibling of the kernel's existing single authorities (tone, originator, display-name,
display-fact, availability). No new disclosure mechanism is introduced — consumers project from the
existing `uiModeState`.

Concretely:

- **Surface a `Simple | Detailed` toggle** in the shell chrome so hidden detail is always
  recoverable (dispatching the existing journaled `set-ui-mode` action seam that Settings uses).
- **Degradation banner:** default to the collapsed one-line pill (the `renderCollapsedDegradationBanner`
  form already shipped in 687); render the raw `causes` only in Detailed or via the notice's local
  "See details" expand. A `severity: error` verdict opens expanded even in Simple. Plain-language
  `headline`/`body` copy (`state/readinessNotice.ts` already separates a plain headline/body from the
  technical, severity-tagged `causes[]`).
- **Search results:** in Simple, translate the retrieval-mode token and the latency to plain wording,
  and render a humanized breadcrumb location (derived from the result's existing `path` + `collection`
  fields) instead of the raw path; Detailed restores the raw mode/ms/path.
- **Agent surface:** the authored run-step labels (the run-step presentation authority, tempdoc 565
  §17) stay in both modes; the raw model-reasoning body, the model name, and the raw budget state are
  gated to Detailed / translated to plain in Simple.

## What this supersedes (teardown rides along)

The banner's default-expansion is now decided by disclosure + severity, which supersedes tempdoc
687's "expand once, then remember the seen cause-set" machinery. Keeping both would be two mechanisms
deciding one thing (the drift this kernel forbids). Removed in the same change: the per-cause-set
`seenDegradationCauseHash` persistence (userConfig field + setter), the cause-hash + arming guard, and
the seen-state default logic. The collapsed renderer and the local expand affordance are kept — they
become the default form.

## Scope

In: the degradation banner, the search meta line + result location, the agent raw-body/model-name/
budget strings, the toggle, and the teardown above. Out (tracked separately, not entangled here): the
leaked GUID-filename search result (a filed indexing/content-hygiene bug), export-button emphasis, the
agent-run-completion behaviour, the onboarding-card dismissal, and the agent-bubble proportion
question.

## Principle (recorded; not built as generalized structure)

**Detail level is a presentation authority, not a per-surface choice** — every user-facing string
that encodes an internal fact is a plain↔technical projection gated by the one Simple/Detailed
authority, defaulting to plain. It applies beyond this change (Health, Security, AI Brain, Library);
the search "why this result?" evidence already conforms, so the principle is half-applied and these
leaks are where it isn't. It earns its keep if future UI review surfaces fewer "raw jargon shown to a
Simple-mode user" findings; it should be retired if the product ever collapses Simple/Advanced into a
single always-plain mode. The generalized structure is deliberately not built now — the present
problem only needs the specific sites wired.

## Rollout / verification

Ship behind the existing `uiMode` preference, reversible per surface. Verify with unit tests over
both `uiMode` states, and — because this is user-visible — live in-browser validation on both the
search and agent surfaces in both Simple and Detailed, with the local model active for the agent
run. Independent review (reviewer ≠ implementer) before merge, per the slice-execution rules.
