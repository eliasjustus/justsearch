# Prototype harvest for tempdoc #818 (search-window rewrite, design-first)

Prototype lineage in this directory (a python `http.server` on 127.0.0.1:33280):
`index.html` (v3, "base C + grafts") → `index2.html` (v4, laws + edge states) →
`index3.html` (v5, fragments + gallery + copied sidebar + live commit; `?theme`
adds the identity layer from `identity.css`). Each file supersedes the last;
v3/v4 kept for comparison. `c-state6.html` is an earlier candidate scrap.

## The laws (L1–L12) — the rewrite's behavioural test list

Verbatim in `index3.html`'s header comment. Summary:

1. L1 pill = pure fn of visible facts; ⇥ flip is a one-shot lens, dies with the draft
2. L2 a run claims the alt slot (STEER), nothing else — ASK stays reachable mid-run
3. L3 scope narrows every destination; it changes none
4. L4 frozen blocks are append-only; staleness = labelled "re-run as new", never mutation
5. L5 ask-about-these-N means these N (the frozen snapshot is the retrieval scope)
6. L6 every count derives from the set it describes — enforced by a DOM-walking audit
7. L7 decisions are incompressible; every deck occupant has a minimum honest form
8. L8 transcript records commitments, not attention; corollary: a session is NAMED
   and INDEXED by its first committed record (fixes the state-gated New-chat class)
9. L9 lock gates the session, not a button; identical refusal on every send path;
   draft never swallowed; refusal names its exits
10. L10 empty draft submits nowhere; pill previews dimmed
11. L11 one fragment, many windows — every region renders from one fragment fn;
    states compose, never author (projection-vs-fork at prototype scale)
12. L12 the rail never yields item-by-item: mode A = conventional session sidebar,
    byte-identical in every pre-session state, collapses whole; mode B = session
    index; the flip is the L8 corollary

## Component decomposition (from the v5 gallery)

jf-session-sidebar · jf-session-index · jf-live-search · jf-frozen-search ·
jf-answer-card · jf-approval-card · jf-run-controls · jf-input-band ·
jf-material-rail. Gallery fixtures = initial test fixtures; captions = contracts.

## Sidebar copy-spec (T3/ChatGPT convention) + 5 deliberate divergences

Copied: New session + search-sessions header, Pinned group, time buckets,
wholesale collapse, hover actions, active highlight, never morphs per state.
Divergences (do NOT "fix" these back to the convention):
1. rows wrap, never truncate (recognise the query you wrote)
2. objects are SESSIONS, never "chats"/"threads" (687: chat is not a place)
3. hover = pin + lock; DELETE behind … overflow with confirm (sessions are evidence)
4. New session emphasised but NOT filled (approval keeps the one-filled-element rule)
5. LOCKED badge exists (convention has no lock)
Query trail is NOT a rail occupant — omnibox-style history in the input band.

## The commit choreography (the signature transition)

Causal order is the meaning: (1) the turn lands, (2) the search freezes into
the transcript, (3) deck collapses, (4) rail flips + name appears, (5) answer
placeholder last. Periphery never moves before the record. ~700ms total,
reduced-motion collapses to instant. Implemented live in v5 (⇧↩ in state 2).

## Identity layer (identity.css)

Swap layer only: :root tokens + IBM Plex Sans/Mono + one signature (the
registrar's stamp on record-verdicts: grounding banner, LOCKED, stale,
quoted-from-file, lock seam). Direction: "the registrar's ledger" — ledger
paper, iron-gall ink, evidence green, sealing wax. Structure and laws
untouched; theme togglable via `?theme`.

## Known gaps / open items (deliberate, for #818)

- The static tableaux still author transcript and index separately (a fork);
  only the live commit path derives both from one records array. The rewrite's
  first move is the canonical records/turn model — transcript, index, name,
  context-ledger all projections of it.
- L5 and L9 have one rendered case each; they become real tests in Lit.
- Transitions beyond the commit (deck handoff when a run starts, collapse,
  citation-follow with rail opening) are unprototyped.
- Two-occupant deck drag (state 7) is verified for floor-clamping only; live
  resize behaviour with a streaming list is untested.
- Fixed 1440×900; 816's sizing roles apply at implementation time.
- ⌘↩-as-steer vs ⌘↩-as-delegate context switch may need a stronger visible cue.
- L8 asymmetry (user reading unrecorded, agent reading receipted) is a decision,
  record it as such in the tempdoc.

## Defect classes from the shipped window this design kills by construction

- count-truthfulness recurrence (597/690/817-S5) → L6 + derived labels + audit
- search-results-as-transcript staleness (817-S6) → freeze model (L4/L8)
- state-gated New chat unreachable (obs. UnifiedChatView.ts:2114) → sidebar header
- affordance/schema desync on card fork (obs. :3735) → pure route() + one-shot flip
- locked-path text loss (obs. :5673) → L9 session-level gate
- three parallel conversation models (audit finding) → one records array (L11)
