# 859 — Search v3 live-task findings: theorization

```
status:  IMPLEMENTED (2026-08-25) — all five PRs merged: #528 (C PR-2 sessions-list
         join), #529 (B floating glass composer), #530 (C-small evidence projection),
         #532 (D effort-mapped budgets + sized-continue gate), #533 (A chronological
         run timeline). Every PR went design → adversarial review → rev 2 →
         implementation → independent code review → fix pass → merge queue.
         LIVE VERIFICATION (2026-08-25, model-free half): measured audit + browser
         legs RUN against the live stack — B's occlusion/scroll/glass legs PASS
         (last line clears glass; Sources disclosure reachable by wheel — the
         original defect is dead; occlusion var px-valued; both appearance escapes
         opaque; grips clickable), #528 rename-withholding discriminates live,
         #533 record interleave renders true order with frozen durations and no
         "…", #530 evidence + mark-click oracle resolve (document half; line half
         structurally absent on agent cites), axe 0 violations, proportion gate
         13 steps/75 rows clean. Defects found and fixed (#534, #535): the
         composer notice strip floated with no fill/blur (3.34:1 over body
         text, 1.00:1 over a heading; reduced-transparency active on this
         machine) — given the composer's glass recipe; a real J/K pin-release
         defect (the scroller's release-on-keydown listener ate the second
         press; narrowed to scroll keys, mutation-proven both directions);
         and the landmark growth-signal blindness (RO watches the box, not
         content — now watches the transcript wrapper too, freshen-on-read).
         CORRECTION (2026-08-25): the originally-reported "J/K stalls at tool
         cards" oscillation was an INSTRUMENTATION ARTIFACT — the off-screen
         MCP browser tab suspends rAF, latching measureCoalesced's frame flag
         permanently, so the audit and the first re-probe both measured a
         hidden-tab state that self-heals on foreground. In a visible window
         the walk is correct in both directions on pre- and post-fix builds
         (7-item trace recorded). Lesson: browser-automation evidence from an
         off-screen tab is not user-visible truth for anything rAF-gated.
         Baseline calibration numbers measured (40→120, 60→120, 800→780) + the
         sv3 gap in ui-a11y-baseline closed.
         MODEL-DEPENDENT VALIDATION (2026-08-25, SAC unblocked, full run): L5
         PASSED (two context crossings, zero orphaned tool messages, no
         overflow on the successful arm); rung mapping exact (2×/5×/15× ×
         n_ctx); #533 live/record BYTE-IDENTICAL with single pulse + clean J/K
         walk; #530 live evidence + both mark-click halves resolve; think-tag
         split holds on the standard profile (compact CoT-leak hedge CLOSED);
         §9's sv3 prompt-blanking does NOT reproduce. L1 FAILED: measured burn
         ≈3,200 tok/iteration ⇒ ≈7.8×n_ctx per full run — Standard's 5× funds
         6.4 of 10 iterations (run gated at 102.6% without answering); rung
         moving to 8× on this datapoint. Defect cluster found → fix PRs in
         flight: parked gate dies at 40s (dispatch SSE has no heartbeat — the
         gate's fuse); gate renders "Tool calls 0 · Steps 0" mid-run (fields
         only written at onDone); context gate ships the projection not
         pressureTokens (reader shown 60% at an actual 82%); CONTINUE can
         proceed into an unservable >n_ctx prompt (3×400 → ERRORED); cut-short
         badge blames the budget for MAX_ITERATIONS; check-shape-handler-regen
         --live compares schemas by reference (never passable);
         sv3-citation-selected interpolates Windows paths unescaped into CSS
         selectors. Legacy-window leg BLOCKED: /history returns [] for
         delegate conversations (C-persistence on the legacy path → feeds
         tempdoc 863's layer decision). Still unverifiable here: true
         translucent-glass contrast (this machine forces reduced
         transparency); composer-notice contrast (no honest trigger).
         OWNER-BLOCKED (Smart App Control now blocks llama-server.exe itself,
         exit 0xC0E90002 — needs an SAC allow): all model-dependent legs — #532
         in full (no persisted record exercises the new gate), #533's live half +
         legacy-window leg, #530's real-span mark click, #529's context-bar
         contrast proper + pane-grip/hero inversion, the two live-AI ui-shot steps.
         C-deep as its own follow-up tempdoc now that A's timeline projection
         exists.
created: 2026-08-20
follows: 848 (reasoning persistence), 847 (citation correctness), 852 S4-partial (the
         visible delegate tier), 857 (run-step keyboard nav), 853 (measured UX audits)
```

## 1. The findings (owner-reported, live session)

- **A1 — chronology lost.** A delegate run interleaves thinking and tool calls in time, but
  the window renders all reasoning blocks as one stack under the prompt and all tool cards
  as a separate feed below. Seven "Thought for Ns" bars in a wall, then the tool cards.
- **A2 — presentation weight.** The disclosure bars are full-width, tall, and sit flush
  against the prompt bubble.
- **A3 — false liveness.** Finished reasoning blocks show a streaming "…" affordance while
  a later region is still thinking; everything looks in-progress until the run ends.
- **A4 — off-system glyph.** The reasoning copy control uses a character glyph, not the
  product icon set.
- **B — composer clips the transcript.** The composer sits in document flow, so transcript
  content is cut off hard at its top edge. The licensed design basis (see
  `views/search-v3/THIRD-PARTY-NOTICES.md`) floats the composer over the scroll content
  with a backdrop blur and lets content glide beneath it.
- **D — budget policy.** Delegate runs get a flat initial token budget and the gate offers
  a flat +4000 raise. Right for some tasks, badly wrong for others. Owner direction:
  effort-mapped initial budgets + an informed sized-continue gate (progress facts, burn
  rate, stepped size choices, a per-run don't-ask-again), with sizing denominated in
  outcome terms, not raw tokens.
- **C — agent-tier citations absent.** (Under live investigation; framed in §5, designed
  in its own follow-up.)

## 2. What the findings share — three deep structures

### 2.1 The run feed is a projection that discards time
The run journal is strictly ordered: reasoning chunks, tool events, and text arrive
interleaved with timestamps, and the record keeps that order. The window then splits the
stream into **per-type accumulators** — a reasoning-block list, a tool-card feed, an
answer buffer — and renders each lane separately. A1 is not a bug in any lane; it is the
representation choice itself. The chronology exists everywhere except the screen.

Principle candidate: **a transcript is a chronology; typed lanes are projections of it,
and rendering should project from the ordered stream, not from per-type accumulators.**
A naive fix (weave reasoning into the tool feed at render time) would add a *fourth*
ad-hoc merge beside the three accumulators. The structural fix is one ordered
run-timeline projection — from the journal on the record path, from event arrival order
on the live path — that every item type renders from. This is the same
one-authority/projection discipline the repo already applies to evidence
(`execution-surfaces`) and that the 852 program applies to the turn record.

Side benefit worth naming: 857's J/K landmarks index feed items. If reasoning runs become
timeline items, keyboard navigation walks thoughts and tool calls **in true order** for
free — the a11y capability compounds.

### 2.2 Live and record must project from one shape
A3 is a live-path-only defect (record renders finished blocks correctly). Every major
finding-family this program has hit — reasoning ephemeral-vs-persisted, citations
live-vs-reload, marks live-vs-record — is the same invariant violated: **the live render
and the record render must be projections of the same state shape.** Any interleaving
design that fixes the record path without unifying the live path (or vice versa)
re-creates the divergence class. The A3 mechanism (hypothesis: the "thinking" affordance
is scoped to the turn/controller, not to the newest block) must be diagnosed in code
before the fix is written.

### 2.3 Decision surfaces need evidence
The budget gate is a decision surface, and today it is context-free: "budget exhausted,
+4000 or stop." The same honesty argument that drove citations (an answer must carry its
evidence) applies: **a gate must carry the facts the decision needs.** The journal already
holds tokens used, iterations, tool calls, elapsed time, and the last tool's identity —
mechanical facts that can be shown without new substrate. Three escalating shapes:

1. **Mechanical facts only** (tokens, calls, elapsed, last action) + stepped sized
   continues. Cheap, honest, no new substrate. *Recommended now.*
2. **Model-authored progress note** at the gate ("1 of 3 files summarised"). Costs
   tokens/latency at the worst moment and can be wrong — a gate that lies is worse than a
   gate that is terse.
3. **Plan substrate** — the agent declares a task list; the gate shows plan progress.
   The right long-term shape, and it is *shared* substrate with agent citations (§5) and
   any future plan UI. Do not build it as a side effect of a budget fix.

## 3. Framings considered and rejected

- **"Just CSS" for B.** The overlay is a viewport-ownership change: the scroller must own
  the full column, with the composer in a higher layer. That moves scroll-anchoring math,
  `scrollIntoView` targets for 857's `jumpTo` (landmarks must land in the *visible* region,
  not under the glass), scroll-to-bottom affordances, and focus-visibility under blur.
  Also accessibility: backdrop blur needs a reduced-transparency fallback and the text
  boundary under the glass edge needs the measured (axe/contrast) audit — happy-dom can
  see none of this, so ui-shot + audit are the only oracles.
- **"Slider = user control" for D.** Raw tokens are an illegible unit; a continuous slider
  over rough estimates is false precision and needs a new input primitive. Stepped sized
  continues denominated in outcome terms, with the token figure as fine print, deliver the
  same control honestly.
- **"Interleave in Sv3Main at render time" for A1.** Adds a fourth merge; see §2.1.

## 4. Hidden assumptions and derisk items

- **Live event ordering.** Does the agent session controller expose reasoning and feed
  events as one sequenced stream, or two callbacks with no shared ordinal? If two, the
  live timeline needs a monotonic sequence stamped at arrival — small but load-bearing.
- **Region granularity under think-tag-leaking builds.** 848 measured a five-region
  interleave from the tag filter. Rendered inline, many small regions could produce more
  bars, not fewer. The display layer needs a coalescing rule (e.g. merge regions not
  separated by a rendered item); tune against the measured shapes, not intuition.
- **Handoff attribution.** Reasoning that precedes an agent handoff attaches across the
  boundary in the current fold; a timeline projection must decide what the reader sees.
- **Composer height is dynamic** (multiline drafts, notices, the tier menu). The
  scroller's occluded-zone padding must track it (observer → CSS variable), or content
  hides under the glass exactly when the composer grows.
- **Effort semantics fork.** For ask, the effort menu maps to completion size; for
  delegate it would map to run budget — one control, two quantities. The menu copy must
  say which meaning is active, or the control misleads.
- **Budget wire capabilities.** Does the raise endpoint accept an amount, and does the
  delegate dispatch carry effort today? Both are probably small backend changes; verify
  before design freeze.
- **Auto-continue vs the dropped autonomy dial.** A per-run "don't ask again" is a
  budget-scoped autonomy control. That is deliberate and small, but name the relation so
  it is not read as re-introducing the dropped dial by the back door.

## 5. The agent-citation frame — CORRECTED by the live investigation (2026-08-20)

The "absence by construction" hypothesis was **wrong**. The live browser investigation
proved the backend already grounds delegate answers: the owner's own run persisted a
`done` payload carrying **28 sources and 12 sentence-level citations** (similarities to
0.9998). The legacy chat window consumes them; **Search v3 throws them away** — the
controller captures `payload.sources`/`payload.citations`, but every consumer lives in
the legacy view, and `SearchV3View.delegate()` never projects them into `setTurnEvidence`
(the resolver `resolveAnswerCitations`, the panel, and the record projection are all
tier-agnostic and already exist). So C splits:

- **C-small (the owner's actual bug):** project the run's terminal sources/citations
  through the existing resolver into the turn's evidence, exactly as `runAsk` does via
  its `onEvidence` callback. Likely a small symmetric fix, not a charter.
- **C-persistence (open, HIGH if confirmed):** delegate conversations were absent from
  `GET /api/chat/conversations` and returned empty `/history` while their agent sessions
  existed server-side — the record path for delegate turns may be missing entirely, which
  would mean delegate answers (and their evidence) are lost on reload. The one blocked
  check (`/api/thread/{id}` for an agent conversation) must run before this is chartered.
- **C-deep (the original frame, now an enhancement):** grounding currently rides only on
  the terminal `done` — there is no evidence event during the run, and answers grounded
  purely on tool reads (no retrieval call) get nothing. The "tool reads become sources"
  substrate remains the long-term shape, should consume the run-timeline projection
  (§2.1), and inherits 849's retrieved-vs-received inclusion idiom.

## 6. Grouping and order (SETTLED after design review, 2026-08-20)

Review outcomes: A NEEDS-REDESIGN (narrow — the interleave rule named a flush carrier
that never projects; rewritten to flush-onto-next-projecting-event, with the corollary
*a projection must name a carrier that exists in the projected stream, not the source
stream*); B/C/D approve-with-amendments. C split into PR-1 (evidence projection, carries
the done-descriptor change D extends) and PR-2 (sessions-list join), file-disjoint and
parallel. Landing order: C-small → D → A; B independent; C-deep after A.

Original proposal, kept for history:

| Slice | Contents | Depends on |
|---|---|---|
| A | Run-timeline projection (record + live from one shape) + interleaved rendering + A2 styling + A3 fix + A4 glyph | — |
| B | Floating composer (overlay, blur, occluded-zone padding, scroll math) + measured audit | independent; audit shared with A |
| D | Effort-mapped initial budgets + evidence-bearing sized-continue gate (shape 1 of §2.3) | small backend verify first |
| C | Agent-tier evidence (own tempdoc after the live investigation) | A's timeline projection |

A before C is structural (C consumes A's projection). B and D are independent of both.

## 7. Additional live findings (browser investigation, 2026-08-20)

Ask tier verified WORKING end-to-end live (9 refs / 19 marked sentences, panel, anchored
pane, reload rehydration) — no regression. New defects and frictions, for triage:

- **Mark-UX cluster (ask tier):** unverified model-authored `[n]` refs render as dead
  literal text beside working superscripts (fail-closed is right; the *presentation* of
  the failure reads as broken); the sources panel says "not cited" about documents the
  answer's own text names; a superscript can point at the matcher's source where the
  model wrote a different ref (silent override); the marker is extracted from
  mid-sentence and appended at the end, mangling prose. These are all consequences of
  "marks follow the cross-encoder, not the model" — correct policy, but the rendering
  must stop *pretending the model's refs and the matcher's marks are the same thing*.
  Design question: render model-refs and verified-marks as visually distinct species.
- **Composer focus steal (severe):** typing into an unfocused composer fires global
  keybindings and can navigate the reader off the window entirely (hash unchanged, view
  swapped; Escape also exits). Recoverable only by reload. Likely interacts with the
  keybinding registry, not 857's guarded J/K.
- **Budget reality check:** initial 3840 exhausted after two tool calls on a
  read-three-files task; the decline path then produced a confidently-formatted,
  content-free answer ("I don't have access…") — the gate's decline arm needs honesty
  work in the same slice as D (§2.3).
- **Cite-ref hit target 13×16px** (under the 24×24 minimum — same criterion as 853 F-09).
- Sources disclosure renders below the composer and is unreachable by wheel-scroll —
  direct evidence for B's occluded-zone problem.
- CoT leakage into the ask answer body on the compact profile (starts mid-thought at
  "3. Synthesize…"); the reasoning/answer split caught one region and let the rest
  through. Hedged: 4B compact model; re-check on the standard profile before chartering.
- Environment: the investigation (and the owner's original task) ran against a sibling
  worktree's frontend serving :5173 — all citation PRs are ancestors of its HEAD so the
  findings stand, but presentation drift is possible; the backend it used died mid-run
  after its heap climbed 607 MB → 1.27 GB in ~15 minutes of light use (correlation
  logged, cause unknown).

## 8. Open questions for the owner

1. Budget sizing vocabulary: outcome-denominated steps ("about one more file / finish
   everything / generous") vs plain small/medium/large — which reads better to you?
2. B's glass: full blur like the design basis, or a cheaper translucent scrim (blur has a
   GPU cost on low-end machines and needs the reduced-transparency fallback either way)?
3. Should "don't ask again this run" persist as a per-conversation preference or reset
   every run? (Recommend: reset every run; standing autonomy was deliberately dropped.)
