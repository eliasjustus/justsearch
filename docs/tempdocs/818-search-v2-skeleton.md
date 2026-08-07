# 818 — Search v2: the from-scratch window skeleton (strangler, dev-gated)

```
status: ACTIVE
created: 2026-08-08
owner-decisions: 817 §4 #4 (rewrite planned, design-first) — sequencing REVISED here (owner, 2026-08-08)
design-inputs: 818-prototype/ (v5 structure prototype + identity layer), NOTES-for-818.md (harvest)
supersedes: nothing yet — UnifiedChatView remains the shipped window until the sunset criterion below
```

## 0. Decision and sequencing revision

817 §4 #4 recorded: *"Search-window rewrite: planned, post-0.2.0, design-first — a rewrite
tempdoc settles the interaction model before any code."* This tempdoc is that design doc.
The design phase happened as an interactive structure prototype (2026-08-07/08, preserved
verbatim under `818-prototype/` — serve the directory statically and open `index3.html`;
`?theme` adds the identity layer). The prototype settled the interaction model to the point
of twelve testable laws, a component decomposition with fixtures, and one live-proven
transition (the commit).

**Sequencing revision (owner, 2026-08-08):** the *skeleton* — a from-scratch, dev-gated
sibling window ("Search v2") — is design-phase work and starts now, pre-0.2.0. It is not
user-reachable: no rail entry, hidden route only. **User-facing cutover remains post-0.2.0**
and requires the sunset criterion in §5. Rationale for building beside rather than editing:
`UnifiedChatView.ts` (6,083 lines) has fan-in of exactly one (a side-effect registration in
`Shell.ts`) and its defects are model-level (three parallel conversation representations,
authored counts, state-gated affordances) — a copy would import the disease; a sibling
window makes the comparison empirical.

## 1. The design (settled by the prototype; authority = 818-prototype/index3.html)

One window, one input, meaning escalates. The core object is **one records array**;
transcript, session index, session name, and context ledger are all projections of it.
A live search is not a record; it lives in the deck and becomes a record only by commit.

### The twelve laws (the behavioural test list — each becomes a unit test)

1. **L1** the destination pill is a pure function of visible facts; the ⇥ flip is a
   one-shot lens that dies with the draft (never a stored affordance).
2. **L2** a run claims the alt slot (STEER), nothing else — ASK stays reachable mid-run.
3. **L3** scope chips narrow every destination; they change none.
4. **L4** a frozen block is append-only; staleness is a labelled "re-run as new", never mutation.
5. **L5** ask-about-these-N means these N — the frozen snapshot is the retrieval scope.
6. **L6** every count on screen derives from the set it describes.
7. **L7** decisions are incompressible; every deck occupant has a minimum honest form;
   only the list body is compressible.
8. **L8** the transcript records commitments, not attention. Corollary: a session is
   *named and indexed by its first committed record*. (Deliberate asymmetry: user reading
   leaves no record; agent reading leaves a receipt.)
9. **L9** lock gates the *session*, not a button: identical refusal on every send path,
   draft never swallowed, refusal names its exits.
10. **L10** an empty draft submits nowhere; the pill previews, dimmed.
11. **L11** one fragment, many windows: every region renders from one component of
    (data, options); screens compose, never author.
12. **L12** the rail never yields item-by-item. Mode A (no committed record) = conventional
    session sidebar, identical in every pre-session state, collapses whole. Mode B
    (records exist) = session index. The flip is the L8 corollary.

### The commit choreography (the signature transition, live-proven in the prototype)

Causal order is the meaning: (1) the turn lands, (2) the search freezes into the
transcript, (3) the deck collapses, (4) the rail flips and the name appears, (5) the
answer arrives last. The periphery never moves before the record. ~700 ms; instant
under reduced-motion.

### The sidebar (rail mode A): copied convention, five protected divergences

Copied from the thread-sidebar convention (New session + search-sessions header, pinned
group, time buckets, wholesale collapse, hover actions, active highlight, never morphs).
Divergences — deliberate, do not "fix" back:
1. rows wrap, never truncate; 2. objects are SESSIONS, never "chats" (687);
3. hover = pin + lock, DELETE behind … + confirm; 4. New session emphasised, not filled
(the approval keeps the one-filled-element rule); 5. LOCKED badge exists.
The query trail is NOT a rail occupant — it is omnibox-style history in the input band.

## 2. Component decomposition (from the prototype gallery)

`jf-search-v2` (host) · `jf-sv2-session-sidebar` · `jf-sv2-session-index` ·
`jf-sv2-live-search` · `jf-sv2-frozen-search` · `jf-sv2-answer-card` ·
`jf-sv2-approval-card` · `jf-sv2-run-controls` · `jf-sv2-input-band` ·
`jf-sv2-material-rail`. Gallery fixtures in the prototype are the initial test fixtures;
gallery captions are the component contracts. `AgentSessionController` is *kept and
hosted*, not rewritten (799/audit: it is a genuine framework-agnostic seam).

## 3. Slice plan

**Slice 1 — the skeleton (this tempdoc's implementation scope):**
- New directory `modules/ui-web/src/shell-v0/views/search-v2/` — from scratch, no code
  copied from UnifiedChatView.
- The records core: a typed records array + projection functions (transcript items,
  index nodes, session name). This is the load-bearing novelty; everything else reads it.
- `route()` as a pure function + the pill (L1/L2/L10), input band with one-shot flip.
- Live search wired to the real backend (the thing the prototype cannot validate:
  real latency, real result sets, real counts) with derived count labels (L6).
- Commit: live search → frozen record with derived header + the choreography (L8 corollary
  live: name + index appear at first commit).
- Sidebar mode A (sessions may be stubbed or read from the existing conversation list —
  whichever the integration recipe makes cheap) and index mode B as projections.
- Static placeholders only: agent run, context ledger, lock, material rail.
- The laws as unit tests from day one — the from-scratch payoff: L1/L2/L3/L6/L8/L10/L12
  are directly testable in slice 1; L4/L5 partially; L7/L9/L11 as structure permits.
- Mounted at a hidden route, dev-only. **No rail entry** (578: one task, one window —
  a visible peer would recreate the exact defect 687 deleted).

**Slice 2+ (future, separate passes):** agent-run hosting (deck occupancy per L7 with the
computed floor), context ledger + meter, lock semantics end-to-end (L9 across all send
paths), material rail + citation channels, identity layer application, comparison
campaign vs UnifiedChatView, then sunset per §5.

## 4. Guardrails (repo-law compliance)

- **From-scratch components, shared authorities**: consume the same stores/APIs as the
  shipped window. If/when a search-v2 component consumes `SearchTrace`, it must be
  registered in `governance/execution-surfaces.v1.json` (the execution-surface gate fails
  the build on unregistered referencers). Slice 1 consumes the search response only;
  register at the moment a trace referencer appears.
- Custom-element prefix per `customElementPrefix.test.ts` convention.
- ui-web gate set fires on `modules/ui-web/src/**` edits — run the pushed recipe.
- `check-ui-step-coverage` triggers on new RAIL surfaces; the hidden route adds none.

## 5. Sunset criterion (written up front, per retire-with-a-sweep)

Two windows is a phase, not a state. The comparison ends when **either**:
- Search v2 passes the full law suite + a feature-parity checklist (to be enumerated in
  the cutover tempdoc) + a measured UX audit → it is promoted, and **the same PR** sweeps
  UnifiedChatView and its fingerprints (grep names/paths across code, config, gates,
  baselines, docs — label or delete every hit); **or**
- the comparison falsifies the model → search-v2 is deleted in one PR and this tempdoc
  records why.
Predictable evasion, pre-named: "we'll keep both for a while" / "a follow-up PR will
sweep it." 742's corpus is follow-ups that never came.

## 6. Defect classes in the shipped window this design kills by construction

| Shipped-window defect (evidence) | Killed by |
|---|---|
| count-truthfulness recurrence (597 → 690 I1 → 817 S5) | L6: derived labels + count audit |
| stale search-results-as-transcript (817 S6) | freeze model (L4/L8) |
| New chat state-gated/unreachable (obs. `UnifiedChatView.ts:2114`, seen 6×) | sidebar header: always-visible New session |
| affordance/schema desync on card fork (obs. `:3735`) | pure `route()` + one-shot flip (L1) |
| locked-path text loss (obs. `:5673`) | L9 session-level gate |
| three parallel conversation models (`mergedTimeline:3816`) | one records array (L11) |
| triplicated reset protocol (`settleTransients:1077` et al.) | projections have no state to reset |

## 7. Log

- 2026-08-08 — tempdoc created from the prototype harvest; worktree `818-search-v2`;
  prototype v5 (+identity layer) preserved under `818-prototype/`. Slice 1 delegated.
- 2026-08-08 — **Slice 1 implemented** (opus worker + independent orchestrator review).
  `views/search-v2/`: `records.ts` (the one records array + projections), `route.ts`
  (pure routing + flip lens), `SearchV2View.ts` (host `jf-search-v2`), 27 law-named
  tests (route 8 / records 11 / view 8). Mounted DEEPLINK/DEVELOPER at
  `#justsearch://surface/core.search-v2-surface`; registration = lazySurfaceRegistry +
  CorePlugin + registry-surface properties (label catalog); component-vocabulary
  regenerated. Full ui-web gate recipe green (one root-cause fix: the destination pill's
  class renamed `.rung-pill` — it is not a status-badge atom, per the atom-fork ratchet).
  Typecheck clean, 4329 unit tests green, `gradlew build -x test` green.
  Accepted worker deviations: strict L10 (empty-draft commit is a no-op, stricter than
  the prototype's default-text fallback); no `setConversationApiBase`/`setQuery('')`
  writes into shared singletons (correct — shared-state hygiene).
  **Live-verified** against the real dev stack via `serve-worktree-fe`: mount, no rail
  peer, real search (backend hits), commit → derived name + index flip + frozen block +
  pending answer, all on screen. Live finding logged to the observations shard:
  backend returned 5 result rows with `matchCount=4` (597-class count inversion at the
  API level — pre-existing, not a search-v2 defect; the frozen header derived honestly
  from its captured set).
