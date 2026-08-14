# 822 — Search v3: a dev-gated conversational window + citation-chain remediation

```
status: COMPLETE
created: 2026-08-12
updated: 2026-08-14
related: 818 (Search v2 — route-registration recipe reused; untouched), 820 (feel
  predicates, complementary), 821 (enrichment completeness — parallel arc on main)
```

## What this is

Two coupled workstreams, one containment rule: nothing shipped changes except where a
named defect is fixed.

1. **The Search v3 window** — `modules/ui-web/src/shell-v0/views/search-v3/`, elements
   `jf-sv3-*`, mounted DEVELOPER/DEEPLINK only (no rail entry; unreachable in normal
   use). A from-scratch window hosting conversations and agent runs on the app's
   shared authorities. Parts of its visual system (design tokens, component geometry)
   derive from an MIT-licensed open-source project; see the colocated
   `THIRD-PARTY-NOTICES.md` for attribution.
2. **Shipped-surface remediation** — a series of evidenced defect fixes in the
   citation/rendering chain that both windows consume.

## Architecture decisions (binding)

- **Shared authorities, from-scratch presentation**: the window consumes the same
  conversation store, unified thread record, agent controller, ask stream, citation
  resolver, and availability projection as the shipped window — never forks them.
- **Host-scoped tokens**: the window's design tokens live on its host element, never
  `:root`, so its palette cannot leak into shipped surfaces (test-asserted).
- **Containment rule for shared-component edits**: additive, default-off/false, with
  regression tests proving shipped surfaces render byte-identically (worked examples:
  `MarkdownBlock` `--md-*` tokens + `prose` variant; `CitationsPanel`
  `externalDisclosure`).
- **No dead controls**: every composer control is mutation-probed to change the
  outgoing request payload.
- **Verification is measurement, not vibes**: geometry asserted against recorded
  values; contrast computed from the token graph; live tiers (real backend, active
  GPU model) at every slice; refute-first critics; agent eyes judge nothing.

## What the window has (slices 1–4, F1–F11, all live-verified)

Shell (tokens, sidebar, composer with hero/docked morph, command palette, empty
states) → live search seam (parked; search integration is deferred indefinitely by
owner directive) → conversations (streamed, cited, markdown-rendered answers with an
honest frame line) → agent runs (delegation, tool feed, typed approval holds, steer,
halt, derived receipts, presence recovery across remounts) → session lifecycle
(record-backed persistence, shelves with a blockers-override, pin via slot-swap,
rename, unread) → citation-inspection pane (shared document pane, main column floor
protected with both side surfaces open) → effort control (Quick/Standard/Thorough,
payload-proven) → the one-line answer tail.

## The remediation series (S1–S6)

- **S1 — numbered context sections**: four emitters, one canonical formatter; an
  invariant test pins section/citation/source index equality. Kills invented
  citation ordinals at the root.
- **S2 — citation tier provenance**: claim scores carry their producer; only
  cross-encoder-verified scores reach grounding-tier verdicts (the per-sentence
  underline wall: 98.5% → verdict-only). The summarize surface is now honestly
  markless (its stream never carried verified matches) — owner-accepted.
- **S3 — the index contract**: streaming emits citations-array positions
  (`sourceIndex`, proto field renamed, buf breaking-check clean); refs carry
  provenance; the wrong-target fallback became drop-the-claim — a mis-targeted
  citation mark is now unconstructible. Same defect class fixed in the agent-tier
  resolver.
- **S4/S5 — renderer geometry**: 15 `--md-*` tokens with byte-identical shipped
  defaults (proven property-by-property) + an opt-in `prose` variant (headings,
  tables with truncate/expand, task lists) no shipped call site sets.
- **S6 — answer-shape grammar**: built, registered opt-in on the ask shape,
  default-OFF — refused by its own four-criterion acceptance gate across three
  interleaved 48-dispatch A/B campaigns; the deciding evidence was substantive
  (prompt-driven compression starves per-sentence citation matching). The gate and
  the evidence ship with the code; revisiting is model-upgrade territory.

## Owner decision ledger (2026-08-13/14)

Search integration: deferred indefinitely (explicit owner hold). Parallel concurrent
runs: rejected (local-first product). Summarize markless: accepted; cross-encoder
pass backlogged. Sources disclosure: bare label (count one tested constant away).
Theme: dark-only until cutover. Autonomy control: seam verified, not built.
Sequential handoff cards: out. Tier-B sittings (degradation form → conversation
actions): ratified order, scheduled on owner word. Merge/publication: explicit
per-action authorization only.

## Open items

Deferred (documented, unscheduled): palette command registry + keybindings (also the
double-palette root fix), snooze shelf, banner stack, tooltip primitive, dark-mode
state-fill separation, Detailed-mode gating of frame metadata, composer model-label
availability gate. Backend observations filed, no slices: controller server-reattach
(true reload survival), status-vs-serving mismatch, HTML parse failures in dev
corpus. Phase D (window reconciliation/cutover) is a separate future tempdoc; its
sweep prerequisites are recorded in the governance registers.

## Log (condensed)

- 2026-08-12 — shell slices 1–4 + polish: token sheet, sidebar anatomy, composer
  morph, palette, empty states; each live-measured to recorded values.
- 2026-08-13 — F-series: live search seam (A1/A2, later parked), conversational core
  (F1), agent-run hosting (F2), lifecycle depth + presence (F3), markdown +
  citations (F4), sidebar mechanics (F5), record-backed sessions (F6), honesty pack
  (F7), citation pane (F8), fit pass (F9), effort control (F10), one-line tail
  (F11). Fit audit: resting chrome 14–16 across states; transcript chrome 18.8%.
- 2026-08-13/14 — remediation S1–S6 (above); S1–S3 live round: in-range grounded
  marks, 0% underline on a partly-grounded answer, pane opens the true cited doc,
  zero page errors; 144 A/B dispatches with zero surviving out-of-range brackets.
- 2026-08-14 — publication re-cut onto current main; the working-history corpus
  retained locally per the curated-narrative policy (ADR-0045).
- 2026-08-14 — CLOSED. Merged to main as `fe1344f9` (#444). The Tier-B follow-up
  sittings (degradation form, then conversation actions) proceed as their own
  tempdocs, 830 and 831, on the ratified order. Register updates the close rule
  requires (search-quality; inference-runtime assessed) were done in this
  close-out.
