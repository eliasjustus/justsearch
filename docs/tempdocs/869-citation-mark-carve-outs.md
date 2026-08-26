# 869 — Citation mark carve-outs: the four mechanical defects that do not wait for 867

```
status:  IMPLEMENTED + PRODUCT-VALIDATED (2026-08-26) — PR #569; §4.4 before/after on the live product; tier-2 idiom still unit-only (see §4.4).
         implementation-eligible remainder; 867 itself stays no-implementation.
created: 2026-08-26
follows: 867 (species theorization — the decision this doc is NOT), 847 (marks follow the
         verifier; tier-1/tier-2 anchoring), 577 Move 3 (`.pseudo-cite` neutralizer),
         839 (mark presentation + measured-audit regime), 865 §7.3 (no state without a producer)
```

## Why a separate doc

867 is a theorization thread with an explicit no-implementation boundary. Its investigation
surfaced four defects whose fix is **independent of the species decision** — each is a bug in
existing behaviour, not a choice about new vocabulary. 867's own Q4 asked whether the
prose-mangling defect "should be carved out as a standalone slice regardless of the outcome";
the answer was yes, and three siblings qualify on the same test. Leaving them inside 867 would
block bug fixes behind an owner decision they do not depend on.

Inclusion test for this doc: *would the fix be the same under every outcome of 867 (§3.1
families A–D)?* If not, it belongs in 867.

## The four items (mechanisms verified at source, 2026-08-26, `main`)

### C1 — the glued digit (`trade-offs4`, `retrieval5`)

`normalizeLiteralCitationTokens` uses `/\s?\[(\d+)\]/g` (`MarkdownBlock.ts:965`); the `\s?`
consumes the space before the model's literal on both the strip branch (`:976`) and the upgrade
branch (`:978`), so the following verified mark or upgraded marker abuts the previous word with
only `margin-left:0.1em` (`:644`). Pure mechanics; no policy. Watch: the `endIndex` extension over
trailing non-whitespace (`:323-325`) may currently depend on the digit being glued — check the
`MarkdownBlock.test.ts` shapes around `word [4].` before changing.

### C2 — muting unreachable in the frame that needs it

`neutralizePseudoCitations` (→ `.pseudo-cite`, `:712-715`) runs only when
`frame === 'ungrounded'` (`:508`). `answerFrame` returns `'ungrounded'` only for a declared
ungrounded-LLM shape or `sourceCount === 0` (`evidenceProjection.ts:131`); the fail-closed
producer-gate case (sources present, `citations=[]`) settles to `'sourced'` (`:139-142`). So the
one existing vocabulary for "citation-shaped text that is not a verifiable reference" never fires
where 859 §7 observed the dead literals. 577 Move 3 chartered the zero-sources frame only.
**This is the one item with a presentation consequence** (muted refs start appearing on sourced
answers) — and it partially pre-empts 867 family B. Boundary question for theorization: fix the
gate only (mute what is *already* the ungrounded idiom, in the frame where refs are provably
unverified), and leave the *species* idiom to 867.

### C3 — tier-2 marks wear an unearned colour

A tier-2 mark (model-placed literal upgraded because its label is a verified source elsewhere,
`:947-955`) renders with `groundingClass(cite.similarity)` (`:994`) — the cross-encoder score of a
*different sentence*. 847 H2 says "a mark's tier is the cross-encoder score"; for tier 2 that score
does not belong to the sentence the mark sits in. Honest options: no tier colour on tier 2, or a
neutral idiom. `Citation` (`:56-80`) has no origin field, so the renderer cannot currently tell
tier 1 from tier 2 after the fact — the fix likely needs one boolean, which is a projection of a
render-time fact, not a new wire field. 847 §7 Q4 deferred exactly this.

### C4 — the renderer is unregistered

`MarkdownBlock.ts` decides what a citation artifact claims and is not in
`governance/execution-surfaces.v1.json` (named only inside another entry's note, `:213`);
`recordEvidence.ts` likewise (`:244`). Register both as projections so the `execution-surface`
gate can protect whatever 867 adds. Pure governance; zero runtime change.

## Constraints

- 847 policy untouched. No new tier, no new state (865 §7.3). No species vocabulary — that is 867's.
- Strip only on the DOM side, never in the sentence key (847 §2.1 anchoring symmetry; 867 §1.3).
- C2 is presentation-authority → measured audit at closure (axe + contrast, auditor ≠ committer).
  C1/C3/C4 are not.
- Keep diffs scoped; 867 may later replace C2/C3's idiom — fix the *bug* minimally, do not
  pre-decide the *species*.

## Questions for theorization

1. Is C2 really 867-independent, or does un-gating the muting *choose* family B by default?
   Where is the honest line between "mute what is provably not a reference" and "render a
   claimed-only species"?
2. Does C3 need an origin flag on `Citation`, or can tier 2 be made colourless structurally
   (e.g. the upgrade path minting a marker without a tier class)?
3. Should a stripped literal (C1) leave its whitespace, or should the mark inherit the literal's
   position instead of the run's `endIndex`? (The latter moves 847 §2.1 placement — probably out.)
4. Ordering and slicing: one PR or two (C4 + C1 trivially safe; C2 + C3 audit-bearing)?
5. What test shapes make each defect *unconstructible* rather than merely fixed?

---

## 1. Theorization (2026-08-26)

Read at source before writing: `MarkdownBlock.ts:265-327` (`matchWordRun` + `endIndex` extension),
`:502-510` (the two gates), `:518-545` (`neutralizePseudoCitations`), `:708-716` (`.pseudo-cite`
CSS), `:958-985` (tier 2), `:988-1010` (`makeMarker`); `MarkdownBlock.test.ts:255-275`,
`:609-621`.

### 1.0 One reframing that changes the slicing

The brief lists four *defects*. Read as *invariants*, they are two:

- **I-1 Position honesty.** A mark's position asserts "this sentence"; a mark's colour asserts
  "the verifier scored this sentence this strongly". C1 (glue) breaks the *typography* of position;
  C3 (tier-2 colour) breaks the *semantics* of colour. Both are the renderer making an assertion it
  did not earn, in the same function family.
- **I-2 Vocabulary reachability.** C2 is not "the muting is wrong", it is "the muting idiom exists
  and cannot reach the frame that needs it". C4 is the governance twin: the surface that mints the
  vocabulary is not in the register that would protect it.

That split is the natural PR boundary (§5), and it also states the *principle* this doc points
at: **the renderer is a projection authority, and every class it mints must be traceable to a fact
that authority actually holds.** Tier colour ← `cite.similarity` of *this* sentence; muting ←
"provably not a reference"; register entry ← "this file mints citation vocabulary". C1–C4 are four
places the trace is broken.

### 1.1 C1 — the glued digit: three candidate fixes, one of them a trap

**F-a (minimal): drop the `\s?`.** The literal is stripped or replaced, the preceding space
survives, the marker follows a space. Risk: a *double* space where the model wrote `word [4] .`
— cosmetic, and HTML collapses it. Cost: one character, tests T-glue.

**F-b: strip the literal *and* its space, but insert the marker with a leading space when the
previous character is a word character.** More code for the same visible result; rejected unless
F-a produces a measurable double-space case.

**F-c (the trap): move the verified mark to the literal's position instead of the run's
`endIndex`.** This "fixes" mid-sentence extraction by letting the model's placement win — which is
847's rejected alternative A ("make the mark's position the model's assertion while its colour
remains the verifier's"). Out of scope by policy, recorded so nobody re-derives it as the obvious
fix. The mark *belongs* at the sentence end; the defect is only that the corpse of the literal
leaves no space.

**Hidden coupling to verify, not assume.** The `endIndex` extension (`:323-325`) walks over
trailing non-whitespace so the mark lands after the period. The model's `[4]` digits are
word-tokens in both the key and the DOM (867 §1.3), so for `…trade-offs [4].` the last matched
token may be `4` and the extension covers `].`, placing the mark after the literal — which tier 2
then deletes. With F-a the sequence becomes: mark inserted after `].`, literal ` [4]` → `[4]`
deleted, leaving `trade-offs .5`? No — `splitText` removes only the token range; the period is
outside it. Expected result `trade-offs .⁵` vs desired `trade-offs.⁵`. **This is the case the
glue was accidentally hiding**: stripping the literal can orphan a trailing period *before* the
mark. So F-a needs a second rule: when a stripped literal is followed by punctuation and preceded
by whitespace, remove the whitespace *instead* of the literal's own leading space. This is exactly
the kind of two-sided edit that must be pinned by a test shape, not reasoned about. Test shapes:
`word [4].`, `word [4] .`, `word[4].`, `word [4], next`, `[4] leading`, and a run whose last
token *is* the digit.

**Why C1 is not merely cosmetic.** Screen readers read `trade-offs4` as one token ("trade-offs
four"); the mark's `aria-label` (`:1001`) is on the span, but the preceding text node's word is
polluted. A space is an accessibility boundary, not decoration.

### 1.2 C2 — un-gating the muting is NOT a one-line flip, for three reasons

**(i) The regex is too greedy for a sourced answer.** `neutralizePseudoCitations` mutes any
`[n]` *or* `(n)` for n ≤ 999 (`:527`, `:532`). In the ungrounded frame that over-reach is
tolerable (nothing in the answer is a reference). In a *sourced* answer it would mute "see step
(2)", "(3 items)", list-like prose, and — critically — any literal `[n]` that tier 2 is *about* to
upgrade if the two passes ever reorder. The honest set for the sourced frame is narrower:
`[n]` only, where `1 ≤ n ≤ sourceCount` (it *resolves* to a source — 867 §3.2's pre-check) and
`n ∉ byLabel` (the verifier did not confirm it — 867 §1.1 row 3). Everything else is prose.
So C2 is a **new branch**, not a gate change: `muteUnverifiedRefs(sourceCount, verifiedLabels)`
running after tier 2 in the `sourced`/`partially-grounded`/`grounded` frames, with the existing
`neutralizePseudoCitations` left exactly as it is for `ungrounded`.

**(ii) It needs a fact the renderer does not currently receive.** `MarkdownBlock` gets
`citations` and `frame`; it does not get `sourceCount`. The pre-check "resolves to a source"
needs it. Adding a `sourceCount` (or a `sourceLabels: number[]`) property is a projection of a
fact the parent already holds (`sourceCount` is an input to `answerFrame`), not a new authority —
but it *is* a new prop on a surface that C4 says is unregistered. Order: C4 before C2.

**(iii) `.pseudo-cite` is colour-only and silent.** `color: var(--text-secondary); opacity: .7`
(`:712-715`); no `aria-label`, no `title`, a bare span. For the ungrounded frame that was
acceptable (the whole answer is already framed as ungrounded). For a sourced answer, a muted
`[2]` sitting next to a live `⁵` is *the* signal the reader needs explained — and Gemini's
documented failure (867 §5b) is exactly "meaning carried by colour alone". Minimum honest
rendering: `title="The model cited source 2; the verifier did not confirm it"` and an
`aria-label`, and an opacity that still passes contrast against the answer background (839's
audit found 0.7 on `--text-secondary` borderline in the selected-card wash — re-measure).

**The boundary question (Q1), answered as far as theorization can.** There is a line between
"mute" and "species", and it is *interactivity*: a muted ref is non-interactive text with an
explanation; a species mark is a clickable artifact with its own legend entry. C2 stays on the
mute side. It does *pre-empt* 867 family B in the sense that B *is* "mute + explain" — but B was
the zero-new-producer option anyway, and if 867 later chooses C (clickable claimed species) the
muted span is the node C would upgrade. C2 forecloses nothing; it removes the dead-text state that
every 867 outcome also removes.

**What C2 must NOT do:** mute a literal in the *grounded* frame that tier 2 would have upgraded
(ordering: tier 2 first, then mute the survivors), mute inside `pre`/`code` (the tier-2 skip at
`:967` must be mirrored), or mute during streaming (`!isStreaming`, same as the other passes).

### 1.3 C3 — tier-2 colour: the structural fix beats the flag

Q2 asked whether `Citation` needs an origin flag. Theorized answer: **no, make the upgrade path
structurally colourless.** `normalizeLiteralCitationTokens` calls `makeMarker(cite)` (`:978`),
which computes `cite-${groundingClass(cite.similarity)}` (`:994`). Give `makeMarker` a
`tier: 'sentence' | 'source'` parameter (or a separate `makeSourceMarker`); the source-tier
marker gets a neutral class (`cite-source`, or no tier class) and a `title` that says what it
asserts ("the model cited this source; open it"), while the sentence-tier path is unchanged. No
type change, no persisted field, and the invariant is enforced where the mark is minted rather
than checked downstream.

What the neutral idiom *looks like* is presentation-authority and belongs to 867's audit — but
"no tier colour" is the honest default because it claims nothing: 847 H2 says tier ← verifier
score, and tier 2 has no score for this sentence. A colourless tier-2 mark also makes 847 §7 Q4
("should tier 2 be visually distinguishable?") answered by construction: yes, by the absence of
the tier colour, the same way H3 distinguishes it by the absence of the underline.

**Cost to name:** today a tier-2 mark *reads* verified; after C3 some marks the reader has been
seeing in colour will go neutral. That is a visible change on the standard profile, and it is a
*correction* — the colour was borrowed. It needs the audit but not a decision.

**Hidden assumption:** that tier 2 is rare. 847 S0 measured anchoring coverage, not the tier-1 /
tier-2 split per answer. If tier 2 is common on local models (plausible — they cite by source, the
verifier anchors elsewhere), C3 changes a lot of ink. One counter per render (`insertedLabels`
before vs after `normalizeLiteralCitationTokens`) into the existing `groundingCoverage` path would
weight it; that same counter is 867 §4.8's S1–S4 instrument. Worth building once, here.

### 1.4 C4 — register both, and note what the register will then catch

`MarkdownBlock.ts` as a projection of `ContextCitation` (it consumes `Citation`, which
`claimsToCitations` projects from the claim record); `recordEvidence.ts` as the projection that
`sv3-record-evidence`'s note already describes. Pure governance. Two things the registration then
makes *visible*: C2's new `sourceCount` prop is a new field on a registered projection (the gate
sees it), and any 867 species field lands on a protected surface. Do C4 first; it is the cheapest
PR in the set and it is the one that makes the others reviewable by machine.

### 1.5 Test shapes that make each defect unconstructible (Q5)

| item | invariant test |
|---|---|
| C1 | For every fixture in the glue matrix (§1.1), the text node preceding a `.cite-ref` ends in whitespace **or** the marker is at block start; no text node contains `\s\.` created by a strip (orphaned period). |
| C2 | In a `sourced` frame with `sourceCount=5`, `citations` labels `{4,5}`: literal `[2]` → `.pseudo-cite` (or the new class) with a non-empty `title`; literal `[7]` and `(2)` → untouched; literal `[4]` → tier-2 `.cite-ref`, never muted. In `grounded` with all labels verified: zero muted spans. During streaming: zero mutations. |
| C3 | A tier-2 marker has no `cite-strong/weak/…` class and no `.cite-sentence`; a tier-1 marker has both. Pin by walking the DOM after decoration on a fixture that exercises both tiers in one answer. |
| C4 | `execution-surface` gate green with both entries; a fixture PR adding a field to `Citation` without touching the register fails the gate (run once locally, don't commit). |

### 1.6 Slicing (Q4)

Two PRs, by invariant not by item:

- **PR-A — I-2 reachability (C4 → C2).** Register first, then the sourced-frame mute branch with
  its `sourceCount` prop and the explanatory `title`/`aria-label`. Presentation-authority;
  measured audit at closure (auditor ≠ committer). Also carries the S1–S4 counter (§1.3) since it
  is in the same function.
- **PR-B — I-1 position/colour honesty (C1 + C3).** Renderer-internal; `MarkdownBlock.test.ts`
  only. C3 changes visible colour on tier-2 marks, so it rides in the *audited* PR if the owner
  prefers one audit — otherwise it is small enough to audit alone.

Order: PR-A's C4 half first (unblocks machine review of everything else), then either.

### 1.7 Risks and hidden assumptions

1. **The two-pass ordering is load-bearing and unpinned.** `decorateCitations` (tier 1 → tier 2)
   then `neutralizePseudoCitations` (`:502-510`) — the new mute must run after tier 2 or it
   eats the literals tier 2 would upgrade. Pin with a test, don't rely on call order.
2. **`sourceCount` vs label space.** "Resolves to a source" assumes labels are dense `1..N`.
   They are (`sourceIndex+1`), but nothing pins the density; a de-duplicated source list would
   leave holes. Use the explicit label set, not a count.
3. **Muting in `partially-grounded`.** Some sentences verified, some not; a literal `[2]` in an
   unverified sentence is exactly the S4 case, so mute applies. But a literal `[2]` in a *verified*
   sentence where the verifier said 5 is S3 (the override) — muting it is correct under this doc's
   boundary; *annotating* it is 867 D. The muted span carries enough (`data-claimed-label`) for D
   to find later without a re-parse.
4. **Restored turns.** 847 §2.5 flipped the trailing-list strip on rehydration; the mute has the
   same restore surface — a cold-loaded sv3 turn must mute identically. One test on the restore
   path.
5. **The compact profile lies about frequency.** The 4B model emits refs differently from the
   standard profile; measure the S1–S4 counter on standard before drawing any conclusion.
6. **Scope creep vector:** "while we're in `makeMarker`, add the species idiom". No — the neutral
   tier-2 class is the *absence* of a claim, not a new vocabulary. Anything with a legend entry is
   867.

### 1.8 Broader shape

This is the third time the mark renderer has had to be told "you may only assert what your
input established" (847 H1–H4, 865 §7.3, now C1–C3). The recurring shape: **a presentation
surface with more than one upstream authority (verifier score, model literal, frame, budget)
accretes classes whose provenance is control-flow rather than data.** The durable fix is not
another invariant test; it is that every class `makeMarker` emits is derived from a field on the
`Citation` it was given, and `Citation` carries only verifier-established fields plus an explicit
`tier`. C3 moves one step toward that; 867 will decide whether "claimed" becomes such a field.
Not designed here — recorded so the design pass starts from the shape, not the symptom.

---

## 2. Design (2026-08-26)

### 2.0 Corrections to §1 from the source survey

Three of §1's premises were wrong or incomplete; each changes the design, so they are recorded
rather than silently fixed.

1. **The Search v3 window never sets `frame`.** `Sv3Main.ts:1871-1880` (ask turn) and
   `:2646-2653` (delegate terminal) pass `.citations` but no `frame`, so both render at
   `MarkdownBlock`'s default `'grounded'`. `projectSv3AnswerFrame` (`sv3-honesty.ts:307-319`)
   *does* compute the frame — for the tail line only. §1.2's "the mute never reaches the sourced
   frame" is true in sv3 for a stronger reason: sv3 has no frame at the block. The tail line and
   the block are two projections of one frame that can disagree — a fork by construction, and a
   pre-existing gap in 577 Move 3's reach.
2. **"No tier class" is the strongest tier.** `makeMarker` emits `cite-grounded` for the high
   tier (`:994`) but no CSS rule styles it; the bare `.cite-ref` colour (`var(--text-tint)`,
   `:639-653`) *is* the strong idiom, with `cite-weak` / `cite-ungrounded` overriding it
   (`:680-693`). §1.3's "mint tier 2 without a tier class" would render tier 2 as *strongest*,
   not neutral. The class vocabulary is strongest-by-absence — the exact trap §1.8 names.
3. **C4 is not free, and it protects less than §1.4 claimed.** Neither file references the
   scan pattern (`SearchTrace|RetrievalCitation|AnswerEvidenceSource`), so the gate does not
   auto-detect them; registration is voluntary. Once registered as `kind:"projection"`,
   check 6 (`enforcer.mjs:163`) requires a guard whose *filename* matches
   `conformance|projection|searchtrace` — `test:MarkdownBlock` fails it. And no check inspects TS
   fields, so registration does not "see" a new prop. What registration buys is discovery
   (the file appears in the surface oracle) plus a named conformance test.

Also confirmed: no per-render telemetry carrier exists in `MarkdownBlock` (only interaction
events, `:1009/:1030/:1048`), so §1.3's S1–S4 counter has nowhere to ride. **Dropped from 869** —
only 867 needs it, and building a channel for a consumer that has not been chartered is the
speculative-abstraction shape this repo forbids. Recorded in §2.6 for 867.

### 2.1 Thesis

`MarkdownBlock` is the projection surface where three upstream authorities meet — the verifier
(`Citation.similarity`, sentence position), the model (literal `[n]` text), and the frame
(577 Move 3). Every class it mints must be traceable to exactly one of those, and the surface
must receive enough of each to make the trace. Today it receives the verifier's facts and a
frame the observed surface never sets, and it mints one class (`cite-grounded`-by-absence) and
one silence (`.pseudo-cite` without a name) that trace to nothing. The design closes the four
gaps by **extending the existing frame→renderer contract** (577 Move 3's seam) — not by adding a
species vocabulary (867's), and not by a parallel muting mechanism.

### 2.2 C2 — one neutralizer, a frame-derived predicate, one new input

**Reuse, don't duplicate.** `neutralizePseudoCitations` (`:518-545`) already owns "wrap
citation-shaped text in `.pseudo-cite`"; its walker, idempotency guard and `pre/code/.cite-ref`
skip are correct. What is wrong is that its *predicate* is hard-wired to the ungrounded arm
("any `[n]` or `(n)`") and its *gate* is the frame. The design parameterizes the predicate and
keys the pass on what it actually needs:

- **Predicate by frame.** `ungrounded` → unchanged: any `[n]`/`(n)`, n ≤ 999 (the whole answer
  is framed; nothing in it is a reference). Every other frame → *resolvable-and-unverified*:
  `[n]` only, `1 ≤ n ≤ sourceCount`, and `n` not a rendered label. `(n)` and out-of-range `[n]`
  are prose and stay untouched — the over-reach §1.2(i) names is confined to the frame where it
  is harmless.
- **Gate by data, not by frame.** The sourced-arm predicate is meaningful whenever
  `sourceCount > 0` — including `grounded` and `partially-grounded` answers, where an S3/S4
  sentence can sit beside verified ones. Keying on `frame === 'sourced'` would (a) miss those and
  (b) be inert in sv3 (§2.0-1). So the pass runs post-settle for every frame; the frame only
  chooses the predicate; `sourceCount = 0` makes the sourced arm vacuous by construction.
- **Order is pinned, not assumed.** The pass runs *after* `decorateCitations` (tier 1 then tier
  2) so a literal tier 2 would upgrade is never muted — today's call order (`:502-510`) already
  does this, and the design makes it a tested invariant (§2.5).
- **One new input: `sourceCount: number` (property, default `0`).** §1.2(ii) asked for a label
  set; the survey shows labels are `arrayIndex + 1` over the *same* `sources` array every call
  site holds (`citationResolve.ts:76/:155`), so the count *is* the label set with no dedup hole
  (§1.7 risk 2 is structurally closed). Every site that passes `.citations` can pass
  `sources.length` from a variable already in scope (survey table §2: sites A, B, C, D, F, I, J).
  Sites that pass no citations (H, K, L) pass nothing; the default keeps them inert.
- **A muted ref must say what it is.** `.pseudo-cite` gains `title` and `aria-label`
  ("The model cited source n; the verifier did not confirm it" in the sourced arm; the
  ungrounded arm's wording says the answer is not grounded), a `data-claimed-label`, and its
  contrast is re-measured — `opacity: 0.7` on `--text-secondary` (`:712-715`) is the value 839's
  audit found borderline. Colour-alone meaning is the documented failure of the only shipped
  prior art (867 §5b); this design does not repeat it. The span stays non-interactive: mute is
  text-with-a-name, species is a mark-with-a-legend (867).

**C2b — wire the frame into the v3 block (the orphan this design must not leave).** Sites A and
B get `frame` from the *same* `projectSv3AnswerFrame` result the tail line uses — one frame
authority, two projections, no fork. This is a pre-existing 577 Move 3 gap, in scope because it
is the reachability invariant (I-2) on the exact surface 859 §7 observed, and because leaving it
would let the tail line say "sourced" while the block frames itself "grounded". Not a new
computation; a second consumer of an existing one.

**What the default `'grounded'` frame now means.** With `sourceCount` defaulting to `0`, a
block that receives neither frame nor count mutes nothing and colours by verifier only — the
same as today. The strongest-by-default frame remains a latent trap (a parent that forgets
`frame` silently claims grounded); the design does not change the default (Lit needs one and
three citation-less sites rely on it) but pins that every site passing `.citations` also passes
`frame` and `sourceCount` (§2.5 T-sites).

### 2.3 C3 — positive tier classes; tier 2 minted without one

Given §2.0-2, "colourless by absence" requires inverting the CSS vocabulary so that **absence is
neutral and every tier states itself**:

- Move the tint from the base `.cite-ref` rule to an explicit `.cite-ref.cite-grounded` rule.
  Zero visible change for tier-1 marks: every tier-1 mark already carries one of the three
  `cite-*` classes (`:994`), so each keeps its colour by its own rule. The base `.cite-ref`
  keeps size, superscript, hit-area, hover — everything that is *mark*, not *tier* — and takes a
  neutral colour (`--text-secondary` without opacity, or the theme's neutral mark token; the
  audit chooses the value).
- `makeMarker` takes a `tier: 'sentence' | 'source'` argument. The sentence tier (weave, `:858`)
  is unchanged. The source tier (upgrade, `:978`) emits no `cite-*` grounding class and a
  `title` that states what it asserts ("The model cited this source; open it") instead of
  "open the cited passage" — there is no passage. `citeAriaLabel` stays state-free by its own
  contract (`:424-437`); the tier distinction rides on `title` and on a `data-cite-tier`
  attribute, which is also what the test reads.
- No `Citation` field. The tier is a fact of *which path minted the mark*, known at the mint
  site; putting it on `Citation` would persist a render decision (a fork of the verifier's
  record). 847 §7 Q4 is answered by construction: tier 2 is distinguishable by the absence of
  tier colour and the absence of the underline (H3), not by a fourth idiom.

**Orphan named:** the phantom `cite-grounded` class (emitted, never styled) becomes real; the
implicit "base colour = strong" rule is deleted, not left beside the new one.

### 2.4 C1 — the whitespace rule, stated once

`normalizeLiteralCitationTokens` stops consuming the literal's leading whitespace. The rule for
what a strip or upgrade leaves behind:

- Strip (`:976`): remove the literal only. If the literal was preceded by whitespace **and**
  followed by punctuation (`word [4].`), remove the preceding whitespace instead, so the period
  closes on the word. Otherwise leave the whitespace (`word [4] next` → `word next`).
- Upgrade (`:978`): replace the literal only; the marker inherits the literal's position and the
  whitespace before it survives. A marker directly after a word is correct *only* when the model
  wrote it that way (`word[4]`), which is the model's typography, not ours.
- The verified mark's position (run `endIndex`, `:826/:850`) does not move — §1.1 F-c stays
  rejected.

The `endIndex` extension (`:323-325`) is unchanged; the survey confirmed no existing fixture
covers `word [4].`, so §1.1's orphaned-period case is pinned by the new matrix, not by reasoning.

### 2.5 C4 — register, and let the conformance file be where the invariants live

Register `MarkdownBlock.ts` and `recordEvidence.ts` as `kind:"projection"` of `ContextCitation`
(`MarkdownBlock` consumes `Citation`, which `claimsToCitations` projects from the claim record;
`recordEvidence` is the projection `sv3-record-evidence`'s note already describes). Check 6
forces a guard named for conformance — so the design makes that file the *home* of this doc's
invariants rather than paperwork beside them:

`MarkdownBlock.citationProjection.test.ts` (guard `test:MarkdownBlock.citationProjection`):

| invariant | assertion |
|---|---|
| I-1 position (C1) | for each shape in {`word [4].`, `word [4] .`, `word[4].`, `word [4], next`, `[4] leading`, run ending in the digit}: the text node before a `.cite-ref` ends in whitespace or the marker is at block start, and no text node contains whitespace-then-period created by a strip |
| I-1 colour (C3) | after decoration on a fixture exercising both tiers: every sentence-tier marker has exactly one `cite-*` grounding class and a `.cite-sentence`; every source-tier marker has neither, has `data-cite-tier="source"`, and a `title` naming source attribution |
| I-2 reachability (C2) | `sourceCount=5`, labels `{4,5}`, default frame: `[2]` → `.pseudo-cite` with non-empty `title` and `data-claimed-label="2"`; `[7]`, `(2)` untouched; `[4]` → tier-2 marker, never muted. `frame='ungrounded'`: existing broad behaviour unchanged. Streaming: zero mutations. Restored turn (847 ×846 §2.5 fixture): identical result cold |
| I-2 order | with a literal tier 2 would upgrade, the muted-span count is 0 — pins tier-2-before-mute |
| T-sites | a source-level test (not DOM): every non-test `jf-markdown-block` template that sets `.citations` also sets `frame` and `sourceCount` — greps the views once, fails on a new site that forgets |

The existing `MarkdownBlock.test.ts` blocks (`:252`, `:282`, `:1161`) keep their assertions;
the new file adds the invariants they were not asked to hold.

### 2.6 What this design supersedes, orphans, or hands on

- **Supersedes** the implicit "base `.cite-ref` colour is the strong tier" (deleted, §2.3) and
  the hard-wired ungrounded predicate inside `neutralizePseudoCitations` (parameterized, §2.2).
- **Orphans closed in-doc:** sv3's frame-less block (C2b); the phantom `cite-grounded` class.
- **Hands to 867:** the S1–S4 counter (needs a render→parent channel that does not exist); the
  species idiom, legend entry, and any per-source "claimed" panel state. The muted span's
  `data-claimed-label` is the hook 867 family D would read — left as data, not as a promise.
- **Not touched:** 847 placement and tiers; the ungrounded arm's broad regex; `Citation`'s shape;
  the wire.

### 2.7 Slicing (supersedes §1.6)

Two PRs, by invariant. Both are `MarkdownBlock`-internal plus call-site props; both extend the
conformance file.

- **PR-A — reachability (C4 + C2 + C2b).** Register; add `sourceCount`; parameterize the
  neutralizer; name the muted span; wire `frame` + `sourceCount` at the seven citation-passing
  sites; T-sites test. Presentation-authority (muted refs appear on sourced answers, contrast
  re-measured) → measured audit at closure, auditor ≠ committer.
- **PR-B — position and colour honesty (C1 + C3).** Whitespace rule; tier argument on
  `makeMarker`; CSS inversion; the two I-1 rows. C3 changes tier-2 marks from strong-blue to
  neutral, so it is audited too — either its own small audit or folded into PR-A's if the owner
  prefers one pass.

C4 first inside PR-A (the register entry + empty conformance file is the first commit); the
rest is order-free.

### 2.8 Reach

**The seam this conforms to.** 577 Move 3: *the LLM must not borrow the index's credibility* —
the frame is the authority that decides whether citation-shaped text may render as a credible
marker. C2 is that rule applied to the sourced frame; C2b is the rule reaching a surface it was
declared for and never wired to. No new seam.

**The principle this reveals — stated, not built.** *A presentation surface that mints classes
from more than one upstream authority must derive every class from a fact it was explicitly
given, and "absence of a class" must never be the strongest claim.* Evidence it earns its keep:
after PR-B, adding a fourth tier or a species (867) requires touching one mint site and one CSS
block and cannot silently inherit the strong colour; and the T-sites test catches the next
`Sv3Main`-shaped omission at PR time instead of in a live session. Where else it already
applies: `CitationsPanel`'s badge family (four examination states — is any of them the
"default" branch?), `ToolCallCard`'s raw `tc.output` rendering (577 finding 18: tool text and
agent prose share typography — an absence-is-authoritative shape one level up), and the
`answerFrame` default `'grounded'` on `MarkdownBlock` itself (still a strongest-by-default; pinned,
not removed). Retirement condition: if 867 lands a `Citation.tier`/species field and the mint
site becomes a pure switch over that field, the "no class = strongest" hazard is unconstructible
and this principle has nothing left to guard — delete it from this doc's reach section then.

**What this does not generalize into.** It does not argue for a global "every default is
suspicious" rule; defaults that assert the *weakest* state (`sourceCount = 0`, `citations = []`)
are exactly right and the design adds one.

---

## 3. Derisk (2026-08-26, worktree `869-cite-carve-outs` @ `ebfabcd7`)

Seven probes (scratch vitest fixtures, a governance-gate trial with a temporary register entry,
a CSS-assertion survey, a contrast computation). Scratch artefacts deleted; worktree clean.
Each finding below changes §2 where marked **→ design change**.

### 3.1 R1 — C1's real shapes (scratch fixtures, verbatim `.md-content` text)

| input | strip arm (key anchors) | upgrade arm (no anchor) |
|---|---|---|
| `substrate [1].` | `substrate1.` | `substrate1.` |
| `substrate [1] .` | `substrate1 .` | same |
| `substrate[1].` | **`substrate[11].`** — corrupted | `substrate1.` |
| `substrate [1], and more.` | `substrate1, and more.` | same |
| `[1] The kernel …` | **` The kernel … .1`** — orphaned leading space | `1 The kernel …` |
| backend-shaped: key = `Trade-offs matter here [1].` | `here.1 Next sentence.` — clean | — |

Findings:
- The `\s?` glue is confirmed on the **upgrade** arm (the marker replaces the whole
  ` [n]` match) — that is the observed `trade-offs4` when a literal sits mid-sentence and no
  mark was woven for its label.
- On the strip arm, §2.4's "remove the preceding whitespace when followed by punctuation" is
  **already shipped behaviour** — `\s?` does it. §2.4 restated the present.
- **Two shapes §2.4 did not name are actually broken:** (i) `word[1].` (no space) — the tier-1
  weave's `endIndex` extension walks over `[` and inserts the marker *inside* the literal, the
  text node is split, the tier-2 regex no longer matches, and the digit is duplicated with
  orphaned brackets; (ii) a block-leading `[1] ` leaves an orphaned space.
- When the key carries the literal (the real backend shape, 867 §1.3), the woven mark lands
  after the period and the literal is stripped cleanly. The mark-before-period `substrate1.`
  appears when the key ends before the literal — reachable when the scorer's sentence and the
  literal's sentence differ.

**→ design change (§2.4 rev 2).** C1 becomes three rules, all in the renderer:
1. **The extension is literal-transparent.** In `matchWordRun`'s trailing extension, an
   immediately-following `\s?\[\d+\]` is skipped as if it were whitespace-free punctuation, and
   the extension then continues over real punctuation. The mark therefore lands after
   `].`/`.`, never inside a literal, and never before a period the literal precedes.
2. **Strip removes the literal and one adjacent whitespace, side chosen by context:** the
   preceding whitespace when the literal is followed by punctuation, whitespace or end (today's
   behaviour, now stated); the following whitespace when the literal is at node/block start;
   neither when the literal is flanked by words (`a [1] b` → `a b`).
3. **Upgrade replaces the literal only** — the model's whitespace survives, so an upgraded
   mid-sentence ref reads `word ⁴`, not `word⁴`. (Whether a space before a superscript is the
   house style is the audit's call; the *bug* is that the renderer decides typography the model
   did not write.)

### 3.2 R2 — the frame is not retrievable from `projectSv3AnswerFrame`

`projectSv3AnswerFrame(turn, currentModelLabel, detailed): Sv3AnswerFrame | null`
(`sv3-honesty.ts:289-324`) computes `answerFrame(...)` at `:305` and discards it after
`answerFrameLabel` — the return type (`:232-239`) is labels only. It is called once, in
`Sv3Main.tailFacts` (`:2348`), a different method from both block sites.

**→ design change (C2b).** Export the frame computation as its own projection
(`sv3AnswerFrame(turn): AnswerFrame | null`) in `sv3-honesty.ts`; `projectSv3AnswerFrame` calls
it (one computation, two consumers), and the two block sites bind `frame=${…}` from it. No new
inputs — `evidence.sources`, `marks`, `turn.answer` are all on `turn`.

### 3.3 R3 — the register trial (verbatim gate output)

- Two entries + no test file → `execution-surface/dangling-guard` (fail).
- Same + trivial `MarkdownBlock.citationProjection.test.ts` → **pass**.
- Control with `guard: test:MarkdownBlock` → `execution-surface/non-conformance-guard` (fail),
  confirming §2.0-3.
- `recordId`/`guardKind` **not required**: check 7 is satisfied per record by
  `evidence-fe-projection`'s reflective guard on `ContextCitation`; `evidence-fe-citations-panel`
  is the shape precedent (projection, no recordId).

Design unchanged; the file name and entry shape are now verified.

### 3.4 R4 — three assertions re-base, none is wrong

Moving `color: var(--text-tint)` from `.cite-ref` to `.cite-ref.cite-grounded` breaks exactly:
`MarkdownBlock.test.ts:574` (`shippedInk('.cite-ref')`, and `:577` transitively), `:727` and
`:750` (the "tier ink ≠ base ink" non-vacuity guards). All three encode "there is a base ink to
fall through to" — the premise the design deletes; they re-base to `.cite-ref.cite-grounded`
with intent intact. `822 F6` (:799) asserts the accessible name `Citation 1 — open the cited
passage` — unaffected as long as tier 2 changes only `title`, which §2.3 already specifies.
`MarkdownBlock.geometry.test.ts` has no `.cite-ref` colour assertion.

Bonus: `MarkdownBlock.geometry.test.ts:320-365` already implements a source-level
`SHIPPED_CONSUMERS` inventory of `<jf-markdown-block` sites (asserting 10; the two sv3 sites are
not counted — 12 exist). **→ design change (§2.5 T-sites):** extend that inventory, don't fork
it; the new row asserts that every site binding `.citations` also binds `frame` and
`.sourceCount`.

### 3.5 R5/R6 — sites and the prop

All seven citation-passing sites verified on this worktree with the length expression in scope
(`turn.evidence?.sources.length ?? 0`; `agentSources.length`; `(m.sources ?? []).length`;
`this.sources.length`; `(this.agentCtrl?.answerSources ?? []).length`). Citation-less sites are
**five** (`UnifiedChatView.ts:5655/:5667/:5772`, `NavigateView.ts:143`, `ReasoningBlock.ts:261`),
not three — §2.2's "H, K, L" corrected. Prop: `sourceCount: { type: Number }`, bound as
`.sourceCount=${…}`, with `declare` + constructor default `0` — matches `ReasoningBlock.durationMs`
and `CitationHoverCard.x/y`.

### 3.6 R7 — `.pseudo-cite` fails contrast in the v3 window today

`.pseudo-cite` is `--text-secondary` at `opacity: .7`. The `.sv3-markdown` bridge re-points
`--text-secondary` to `--muted-foreground` (`Sv3Main.ts:609-614`), so in v3 the composite is
≈ **3.0:1 dark / 2.7:1 light** — a failure, not "borderline" (839's 4.4:1 reading was the
legacy shell). Without the opacity, `--muted-foreground` over `--background` is ≈ 5.0:1 dark /
≈ 4.4:1 light — the light arm still misses AA for small text. Pre-existing in the ungrounded
frame; C2 would widen it to every sourced answer.

**→ design change (§2.2).** The muted idiom drops `opacity` and takes its colour from a
dedicated hook, `--md-pseudo-cite-color` (default `var(--text-secondary)`, mirroring the
existing `--md-cite-weak-color` pattern at `:680`), which the sv3 bridge sets to a lifted
mix of `--muted-foreground` toward `--foreground`. The exact value is the measured audit's;
the structure is the design's. **This is a PR-A blocker, not a follow-up.**

### 3.7 Confidence and effort

**Confidence 7/10** for the remaining work. What was unknown and is now known: the exact
broken shapes for C1 (two more than theorized, one a text corruption), the missing frame
carrier in v3, the gate's file-name requirement, the three test re-bases, the contrast failure.
What remains uncertain: (a) C1 rule 1 touches `matchWordRun`, the 847 anchoring core — a
regression there is the highest-cost failure in this doc and the 18-shape matrix
(`MarkdownBlock.anchoring.test.ts`) must stay green; (b) the audit may reject the muted colour
value or the `word ⁴` typography — both are value choices, not structure; (c) end-to-end
verification needs the standard chat profile to produce an S4 literal on demand, which is
model-dependent.

**Difficulty:** moderate. ~8 files, one subtle function (`matchWordRun`), seven call-site
edits, one CSS inversion, one register entry, one new conformance test file, three test
re-bases. **Recommended: opus for PR-B's C1 (anchoring core) and for the conformance test;
sonnet is adequate for C4, the call-site wiring, and the CSS move.** Effort: high on C1,
medium elsewhere.

**Slicing amendment.** Implemented as one branch, two commits (A: reachability, B: honesty),
one PR, one measured audit — §2.7 allowed folding the audits and the owner asked for end-to-end.

---

## 4. Implementation record (2026-08-26)

One branch (`worktree-869-cite-carve-outs`), one PR, four code commits — two design slices and
two review-fix rounds. Every commit was written by a delegated worker and reviewed refute-first by
an independent agent (reviewer ≠ committer) before the next round.

| commit | scope |
|---|---|
| A `1def52b9` | C4 register (+ `MarkdownBlock.citationProjection.test.ts` conformance guard) · C2 one neutralizer with a frame-derived predicate + `sourceCount` · muted spans carry `title`/`aria-label`/`data-claimed-label` · `--md-pseudo-cite-color` hook replaces `opacity` · C2b `sv3AnswerFrame` exported, both v3 block sites bind `frame` + `.sourceCount` · seven call sites wired · T-sites inventory extended |
| B `ab326053` | C1 literal-transparent extension, `closeLiteralGap` whitespace rule, upgrade keeps the model's whitespace · C3 positive tier classes (`cite-grounded` gets its rule; base `.cite-ref` neutral), `makeMarker(cite, tier)`, `data-cite-tier`, source-tier title · three §3.4 assertions re-based |
| review 1 fixes `9e91e64e` | neutralizer skips `pre/code` · sentence span ends before a skipped literal (`spanEnd` vs `endIndex`) · literal skip and punctuation walk are code-aware · wording "this citation was not verified" (no examination implied) · live agent block: run-scoped count + `is-streaming` (and `agentAnswerCitations()` gated by the same verdict) · base `.cite-ref` ink `--text-primary` (distinct from weak) · `closeLiteralGap` never treats a newline as removable · cold-load parity test · `recordEvidence` guard exercises its surface (`evidence-fe-record-evidence`) |
| review 2 fixes `a36ad822` | settled DOM re-derives from inputs when `citations`/`sourceCount`/`frame` change post-settle (late evidence mints marks; withdrawn evidence removes them); `unmutePseudoCitations` retired as subsumed · in-sentence override rendering pinned · no-space code-span shape pinned |

### 4.1 What the reviews caught that the design and derisk did not

Recorded because each is a gap in §2/§3's reasoning, not in the worker's execution:

1. **§2.2 asserted the neutralizer skipped `pre/code`. It never had.** Un-gating it would have muted
   every `argv[1]` in a programming answer. (Design error; the derisk did not probe code spans.)
2. **The literal skip widened the verified sentence span onto the literal** — a "not verified" span
   nested inside a "scored" span, and a tier-2 mark nested inside a tier-1 span. The fix separates
   *where the span ends* from *where the mark lands*. (The §3.1 rule was stated in terms of the
   mark only.)
3. **"Pure function of inputs" was claimed by a pass that could not deliver it** — the weave's own
   idempotency early-return meant a late citation withdrew the mute but minted nothing. The root
   fix rebuilds the settled DOM from `text` when a decoration input changes. (A `verdict-is-gate`
   shape: the first fix's test covered only the fixture where the invariant happened to hold.)
4. **The live agent block counted the previous run's sources mid-stream.** The run-scoped verdict
   existed (`evidenceIsThisRun`) and was not applied to the count or, it turned out, to the
   citations either.
5. **Source-tier ink collided with weak-tier ink** outside the v3 bridge — the neutral default had
   been chosen from the same token as the weakest verified tier.
6. **A mid-sentence literal inside a verified sentence renders muted inside the grounded span.**
   Judged *correct* and pinned: the span asserts the sentence was scored (it was — the literal is
   in the key), the mute asserts the ref was not verified (it was not). This is the S3 override made
   visible, which 867 §3.2 wanted; 867 may later annotate it (family D) — the `data-claimed-label`
   is the hook.

### 4.2 Verification

- `npm run typecheck` clean; full `npm run test:unit:run` green after each commit (known flakes
  under load, unrelated: `PluginLoader.test.ts`, `EnvelopeStream.test.ts:488` — logged).
- ui-web gate set (34 check scripts + 6 kernel gates), `execution-surface`,
  `register-guard-resolution`: pass.
- `./gradlew.bat build -x test`: exit 0.
- C1 non-vacuity: 6 conformance tests fail on pre-869 code, reproducing §3.1 R1 verbatim; the
  847 anchoring matrix (54 tests) unchanged and an instrumented probe counted the new skip branch
  firing zero times across it.
- **Live (worktree FE on :5176 borrowing the running backend, compact profile, dark theme):**
  `jseval ui-shot sv3-citation-selected` → axe **0 violations**, console errors identical to the
  same step on `main`'s FE (pre-existing, not 869). A shadow-DOM probe of the **cold-loaded**
  session (restore path): block `frame="grounded"`, `sourceCount=5`, three verified marks
  (`data-cite-tier="sentence"`, `cite-grounded`), eleven literal `[4]` muted with
  `title`/`aria-label` "The model cited source 4; this citation was not verified",
  `data-claimed-label="4"`, `opacity 1`, none inside code — the S4 case from 859 §7, rendered as
  designed. Re-probed on the final commit (`a36ad822`) against a fresh stack: identical facts;
  a fresh ask on the final code rendered 6 verified marks, axe 0. Muted ink over the v3 background: **7.8:1** measured (dark). Light arm: ~7.7:1
  computed from the bridge's tokens, **not measured** — the window's persisted user state pinned
  dark and the probe's `color_scheme` emulation did not override it.
- The `citation-highlight` chain step was unreachable in this window (`search-results` fails on
  `main`'s FE too — environmental); the v3 surface is where 859 §7 observed the defect, so it is
  the measured surface.

### 4.3 Honest limits / not done

- The §2.7 measured whole-screen UX audit by an auditor ≠ committer is **partially** satisfied:
  axe-clean and contrast-measured on the v3 surface (dark), by this orchestrator, not the
  implementing worker. Light theme and the legacy chat surface are un-measured. Owner's call at
  closure.
- `word [1], and more.` renders the mark after the comma (`word,¹ and more.`) — consistent with
  how the weave already treats punctuation without a literal; recorded, not changed.
- Tier-2 marks in v3 now use body ink (`--foreground`); a clickable `role=button` in body colour
  is a presentation choice for the audit.
- `.pseudo-cite` ink (`--text-secondary`) equals the weak-tier mark ink; distinguished by shape
  (bracketed, body size vs superscript digit). Recorded.
- `[a]` (non-digit bracket) still lets the punctuation walk cross a bare `[` — pre-existing,
  logged to the inbox.
- `MarkdownBlock`'s default `frame='grounded'` remains strongest-by-default (§2.2); inert with
  `sourceCount=0`, pinned by the T-sites test for every citation-passing site.
- Handed to 867 unchanged: the S1–S4 counter, the species idiom, any per-source "claimed" state.

### 4.4 Product validation — same prompts, `main`'s FE vs this branch's FE (2026-08-26)

Owner asked whether the fixes were validated in the real product, not just unit tests. Method:
one borrowed dev stack (compact profile), four prompts designed to elicit mid-sentence refs,
multi-source sentences and over-citation, driven by a Playwright script through the v3 composer
on **`main`'s FE** (`:5173`) and on **this branch's FE** (`:5174`), then a shadow-DOM probe of the
settled block. Model output differs per ask, so the comparison is by shape, not by string.

| | `main` FE (before) | this branch (after) |
|---|---|---|
| block `frame` / `sourceCount` | `grounded` (default) / `undefined` on every ask | `partially-grounded` / `5` |
| unverified model literals | rendered as dead prose (`Document [3]: …`, `… [5].`) — 0 muted across 4 asks, 25 literals in raw | 16 muted across 3 asks, each with `data-claimed-label`, `title`/`aria-label` "…not verified"; none inside code; none nested in a mark |
| marks | no `data-cite-tier`; base ink = strongest | `data-cite-tier="sentence"`, positive `cite-grounded`/`cite-weak` classes |
| the 859 override shape (`[j]` + mark `k`) | not observed in this run | observed: `…confidence⟨[3]⟩⟨2⟩` — muted model ref inside the verified sentence, verified mark after it |
| strip arm of C1 | — | `writer [1].` → `writer.¹` (mark after the period, literal stripped, no glue) on three asks |
| tier-2 (source-tier) mark | not produced | **not produced** in 5 asks — the compact model's refs either anchored (tier 1) or were unverified (muted); the colourless tier-2 rendering remains unit-tested only |
| **answer truncation** | **ask 2 rendered 87 of 2116 chars; ask 3 738 of 1861** | after the §4.4 fix below: ask-3-shaped answer rendered 1942 of 2116 (delta = stripped literals) |

**New live finding, fixed in this PR (commit `a9b50c77`).** `stripTrailingCitationBlock`'s
regex (565 §13.8 / 846) matched any paragraph that *starts with* "citation/source/reference" and
cites anything later, and deleted it — and everything after it — as if it were a trailing
bibliography. A prose paragraph "Citation verification scores supplied text … [4]." lost half
the answer; on `main` one answer lost 96 % of its text. The heading word must now be followed by
a colon, line break, `[n]` or list marker; test pinned with the live shape, old regex shown
truncating it. Pre-existing and silent — it is in this PR because it was found validating this
PR and lives in the function the PR already touched (`contentSource()`), and because it made C2
un-validatable on exactly the answers users asking about citations get.

**Still not validated in the product:** the tier-2 colourless mark (no compact-model ask produced
one — needs a standard-profile run or a seeded record); light theme; the legacy chat surface.
Two things noticed and logged, not fixed: a verified sentence the model quoted inside backticks
gets its mark woven inside the `<code>` (847 anchoring into code spans — pre-existing); an ask
with 8 verified refs rendered 1 mark (847 anchoring failure class on list-shaped answers).
