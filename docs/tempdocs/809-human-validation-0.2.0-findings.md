---
title: "Human validation pass, JustSearch 0.2.0 — owner findings, recorded verbatim"
status: "recorded 2026-08-06; triage in tempdoc 810"
created: 2026-08-06
updated: 2026-08-06
related: [734, 810]
---

# 809 — Human validation pass, 0.2.0: the owner's findings (verbatim)

## Provenance — read this before the body

- **Author:** the owner, during a manual validation pass of JustSearch 0.2.0 run 2026-08-05/06,
  *after* Sandbox round 14 had finalized. This is a separate tier from the Sandbox rounds: a
  human using the product, not an agent verifying it against ground truth.
- **Verification:** an agent re-tested each claim against the running build on 2026-08-06 and
  tagged every change **[verified]**, **[corrected]** or **[added]** in place, so the provenance
  of each individual claim stays visible inside the text.
- **Machine state during that verification** (stated in the body's own header, repeated here
  because it differs from round 14's): only root = `docs\tempdocs`, docCount 567, scifact absent,
  agent-history present on disk but not indexed.
- **The body below is reproduced byte-for-byte** from
  `tmp/sandbox-round14/share/human-validation-run-results/findings.md`. Nothing in it has been
  edited, re-ordered, re-severitied or summarized. Severity tags inside it are the author's own
  proposals, explicitly overridable.
- **Triage lives elsewhere.** Routing to lanes, the four serious-thread charters, the
  owner-decision queue and the "why these were missed" analysis are in **tempdoc 810**. This
  document is the raw record only.
- **Screenshots** taken by the owner during the pass are preserved on the host at
  `tmp/sandbox-round14/share/evidence/human/` (5 files: four `Screenshot 2026-08-0*.png` captures
  plus `searchwindow.png`). Round 14's own in-sandbox evidence sits in the same evidence dir; the
  round record is tempdoc 734's Round 14 section.

---

# JustSearch 0.2.0 — owner findings

Owner-authored findings from the manual validation run, with agent verification applied.
Corrections and additions from the 2026-08-06 verification pass are marked **[verified]**,
**[corrected]** or **[added]** so the provenance of each claim stays visible.

Severity tags in brackets are proposals where the original note carried none — override freely.

Machine state during verification: only root = `docs\tempdocs`, docCount 567, scifact absent,
agent-history present on disk but **not** indexed. This differs from the round-14 evidence
state and matches the state most of these findings were made against.

---

## 1. [MEDIUM] Indexing completion claims a capability it does not have

We tell the user indexing is done when only the fast part is done. Text extraction and the
Lucene write finish quickly, but embedding, SPLADE and NER run afterwards on a separate
backfill scheduler. During that window keyword search works but semantic and hybrid search
don't, which is the actual product. So "done" is claiming a capability we don't have yet.

This is fixable on its own and should not wait for the redesign below — the completion
message just has to consult coverage before saying done.

The data already exists: `/api/knowledge/status` carries `embeddingCoveragePercent`,
`spladeCoveragePercent`, `pendingNerCount`, `completedNerCount`, `chunkEmbeddingReady` and
`queueDepth`. It's a wiring problem, not a backend one.

**Regression home:** host test asserting the completion message is gated on coverage, not on
job-queue drain.

## 2. Indexing queue UI — needs its own end-to-end design pass

The current tasks queue is way too uninformative. It's first shown as a small box with the
amount queued, then during active indexing it shows the actively indexed files, and both
types of information just sit next to each other. Showing each actively indexed file is
useless considering indexing only takes about a second. The files should be an opt-in
secondary aspect since we already have the data for it, and there should be something like
an estimate of how long it will take as well as a progress bar. The box itself should also
be discussed in terms of layout, since it currently blocks sidebar elements like settings.

No design decisions yet — this deserves a full end-to-end design investigation.

**Constraints the pass has to work within:**

- **There are genuinely two queues.** `worker.core.pendingJobs` is the job queue; the
  "queue / up to date" card reflects the index-writer queue (`writerQueueDepth`,
  `refreshLagMs`). Seen disagreeing on one screen — "indexing — 0 running · 1218 queued"
  directly above "queue 0 / up to date" — both true about different things. The obvious fix
  of picking one number quietly throws away real information.
- **Throughput varies 6–7×** with document length. SciFact ran ~16 docs/sec; a tempdocs
  batch did 150 docs in 63s ≈ 2.4 docs/sec, because 65 of 116 embedded docs needed windowed
  passes. A doc-count estimate will be badly wrong on mixed corpora and wrong in the
  direction people hate. Needs byte or token weighting.
- **Coverage is index-wide, the notification is per-folder.** The backfill batches documents
  without recording which root they came from, so "this folder is 40% enriched" may not be
  derivable today. Check the source before designing anything per-folder — it decides whether
  progress is scoped to the add or to the index.
- **We already have an honest vocabulary elsewhere**: the degraded banner ("1 cause —
  semantic search degraded" with Open Health) and Health's "what you can do right now" table.
  Staying consistent with that beats inventing a second way of saying the same thing.

**Layout point already has a home:** the sandbox coverage brief's `core.library-surface` entry
lists "toast occlusion" alongside progress visibility. Artifacts:
`evidence/25-health-surface.png`, `evidence/24-library-root-added.png`.

## 3. [MEDIUM] Enrichment is not cancelled when a root is removed, and the cycle budget is unenforceable

Two adjacent bugs in the same subsystem, both belonging to the investigation above.

Removing a root doesn't cancel enrichment already in flight. After the watcher was
unregistered, a full batch still ran 63 seconds of GPU (43s embed, 15s NER) and wrote **zero**
documents — all discarded.

The scheduler has a 5-second cycle budget but only checks it between batches, and a batch is
150 docs ≈ 63s, so it overshoots its own budget by ~12×. That directly limits what the UI can
honestly offer: we can't promise a responsive cancel when the smallest interruption
granularity is a minute.

---

## 4. [MEDIUM] `totalHits` ignores `pathPrefix`, and `appliedFilters` is never echoed

**[corrected]** — this finding was originally written as "a document leaks through the
pathPrefix filter". That specific behaviour did **not** reproduce on re-test; a reproducible
and cleaner defect was found in the same area, and now leads.

`results[]` is filtered correctly. `totalHits` is not:

| query | unfiltered totalHits | filtered totalHits | delta | outside-prefix rows unfiltered |
|---|---|---|---|---|
| keyboard shortcuts | 182 | 180 | 2 | 6 |
| getting started with justsearch | 155 | 152 | 3 | 5 |
| troubleshooting help | 186 | **187** | **−1** | 6 |

Every filtered run returned zero outside-prefix rows, so scoping works on the rows. But the
count moves by 2 and 3 where ≥6 and ≥5 were expected, and in the third case the **filtered
search reported more matches than the unfiltered one**. Narrowing a corpus cannot increase a
match count, so the count is computed from a different population than the rows.

`appliedFilters` is **absent from the response entirely** **[verified]** — top-level keys are
`interactionId, indexCapabilities, searchTrace, totalHits, matchCount, results, tookMs` —
despite `api-contract-map.md:445` specifying it is echoed when filters are active. Together
these give two independent misleading signals about whether filtering happened, which is what
made the original observation ambiguous.

**Also confirmed:** `collection` is accepted and defaulted on `POST /api/indexing/roots` and
echoed by the GET, but is not a member of the search filter set (`mime`, `mimeBase`,
`fileKind`, `language`, `pathPrefix`, `includeChunks`, `modifiedAt`, entity/metadata fields).
A corpus can be labelled but not queried by its label, leaving `pathPrefix` / `doc_ids` as the
only scoping routes.

**Unreproduced observation, kept for the record:** under `pathPrefix=…\docs\tempdocs`, query
`statins cardiovascular` once returned `…\agent-history\8cc3d33f-….md` at rank #1 across two
fresh runs. On re-test the same query returned 183 hits with all rows inside the prefix, and a
direct probe of `pathPrefix=…\agent-history` returned 0 — those documents are no longer
indexed, though the six `.md` files remain on disk. Consistent with transient indexing during
an actively-growing index rather than a filter bypass. Mechanism never identified.

**Regression home:** host live-stack test asserting every hit satisfies the requested
`pathPrefix` **and that `totalHits` equals the filtered result population**, plus a contract
test that `appliedFilters` is echoed whenever filters are sent.

## 5. [MEDIUM] **[added]** The index contains documents from outside every registered root, unlabelled

The only registered root is `docs\tempdocs`, yet unfiltered searches persistently return:

- five SSOT help docs from the **install directory**
  (`…\resources\headless\SSOT\docs\help\` — getting-started, search-syntax, troubleshooting,
  keyboard-shortcuts, ai-features)
- `…\Desktop\mcp-ingest-target-round14.txt`, ingested via MCP, outside any root
- and, per finding 4's unreproduced observation, `agent-history\*.md` — JustSearch's own
  agent transcripts — at least transiently

The help docs are plausibly deliberate. The user-visible consequence is that "search your
files" returns JustSearch's own files intermixed, with no marker distinguishing them, no entry
in Library › Folders, and no way to scope them out (see finding 4: `collection` isn't a search
filter, so `pathPrefix` is the only lever and it requires knowing the path).

This compounds finding 4: the surface reports "Searching 567 documents", a number that
includes documents the user never added and cannot enumerate — and `totalHits` doesn't respect
scoping either, so every count on the search surface describes a corpus the user has no way to
see.

The agent-history case is the sharpest edge: transcripts of AI runs entering the searchable
corpus and surfacing in ordinary queries deserves a deliberate decision rather than emergent
behaviour, and should be settled separately from the scoping question.

**Regression home:** API test asserting the indexed set is a subset of registered roots plus an
explicitly declared internal allowlist; UI assertion that out-of-root documents are labelled as
such in results.

---

## 6. [MEDIUM] Activity timeline projects one row per indexed document, evicting the audit trail it exists to show

`/api/action-ledger` returns capped at 500 entries. Live composition **[corrected — four kinds,
not three]**:

| kind | count | share |
|---|---|---|
| index | 391 | 78% |
| effect | 74 | 15% |
| operation | 24 | 4.8% |
| **grant** | **11** | **2.2%** |

The fourth kind, `grant`, is the TYPED_CONFIRM authorization record — the single most
audit-relevant entry a private-retrieval product keeps, and the first thing a per-document
projection evicts. A full-corpus ingest (scifact = 5,190 docs) emits ~5,190 `index` events and
flushes every `operation` and `grant` entry out of the retrievable window entirely. Per-doc
projection does not merely bury the audit trail, it destroys it — which inverts the priority of
an AUDIT surface.

The rows also carry no actionable information: `Indexed · default (f7e852)` renders the
collection name (constant — every root defaults to `default`) plus a truncated hash, with no
filename or path, and uniform relative timestamps. F8's defect class reproducing on a second
surface (Library › Folders renders `b5ec60937d1a…` where the API and the sibling Browse tab
both hold the path).

Separately, the `effect` entries captured by default are navigations
(`{"kind":"effect","effectKind":"navigate","originator":"user","subject":"justsearch://surface/core.unified-chat-surface"}`)
— the least consequential user event available, state-free and reversible — while the events
that matter (TYPED_CONFIRM approvals, ingest authorizations, encryption unlock, memory
write/forget, root add/remove) are `operation` and `grant` at ~7% combined.

**Suggested fix:** keep per-doc entries in the ledger for audit integrity, but project one
collapsible row per scan/batch (`Indexed 562 documents in default`) expandable to per-doc
detail, with an in-surface filter for "was this file indexed?"; default the user tier to
consequential operations and grants, and move navigation behind a show-all toggle. No new
capture required — `originator` and `kind` are already on every entry and already rendered as
chips.

**Record correction:** F9's caveat that ledger entries were "all `kind: index`, so a narrower
intended class is possible" can be closed — four kinds are present, so F9's empty-state defect
was not a narrow-class artifact.

**Regression home:** ui-shot asserting an N-document ingest yields one batch row not N rows,
plus an API test that `operation`- and `grant`-kind entries survive a full-corpus ingest in the
default retrieval window.

## 7. [LOW-MED] Timeline / History / Inbox are separated by an axis that appears nowhere in the product

The three are genuinely distinct — `/api/thread` = what was said, scoped to one conversation;
`/api/action-ledger` = what was done, machine-wide; `/api/presence?since=` = the
cross-conversation background-run inbox, the AgentRunStore projection filtered to
`background=true`, i.e. detached runs launched via `POST /api/presence/run` that the user is not
watching. Inbox is therefore defined by **attention**, not content type — a queue to return to,
not a record. A coherent boundary, established deliberately (565 §26.D split durable facts into
`core.memory-surface` while the activity half folded into the retrospective drawer's Inbox tab),
but stated only in tempdocs and `api-contract-map.md:276-279`, never in the UI.

Three compounding legibility failures:

1. **Designed overlap** — a background run launched with a `conversationId` joins that
   conversation's `/api/thread` history (rendering as a background segment) *as well as* the
   inbox, so one item correctly occupies two surfaces with no marker of identity or authority.
2. **Live states invert the signal** **[verified]** — `/api/presence` returns `{"runs":[]}` at
   exactly 11 bytes while `/api/action-ledger` returns ~172 KB of which ~78% is per-document
   index noise. The surface with an editorial rule reads as broken; the surface without one
   reads as the system firehose. A user who never launches a background run sees a permanently
   empty tab.
3. **Names fight concepts** — "Inbox" is a mail metaphor implying arrival from others when the
   contents are the user's own detached work; "History" and "Timeline" are near-synonyms in
   plain English mapping to different scopes.

**Suggested fix:** name by the question answered (`Background runs` with a count badge so empty
is legibly zero, `System activity`, `This conversation`); render the thread-side appearance of a
background run as a reference back to the inbox item rather than a peer (one authority, one
pointer); give the empty Inbox a state that names its filling condition.

**Not probed:** `/api/thread` 404s without a conversation identifier **[verified]** — presumed a
required parameter, no claim made about its behaviour.

**Regression home:** ui-shot asserting each surface renders a scope descriptor, plus an
empty-state assertion on the Inbox tab.

---

## 8. [LOW] Health "What you can do right now" — info-tier caveat names no cause, no model, no route to detail

The row renders *"Available — An optional ranking model is unavailable — results are complete,
ranking may be simpler"* (`availability.ts:139`, the `severity==='info'` / `calm=true` branch;
the warn branch is the honest *"Showing keyword-ranked results — semantic ranking is
degraded"*). The copy is truthful by construction on that branch — recall is unaffected, only
optional re-ranking is — and the panel is 593 item 7's fix for buried degradation causes, so
this is not a wording defect.

The gap is that the string identifies neither which optional model nor why, and carries no
remedy affordance, while the actionable detail is already on the wire:
`/api/ai/runtime/status` `onnxFeatures[]` gives per-encoder `status` / `reason` / `modelActive`
/ `executionProvider` / `gpuFallback` / `fallbackReason`, and `/api/status` carries per-encoder
`degraded` + `skipped` booleans plus `encoderProfiles.reranker.calls`. This is the T2
projection-gap pattern tempdoc 600 is named for: data present, dropped by the user-facing
projection.

**[corrected] The model referred to is LambdaMART, not the cross-encoder reranker.** Live state
during verification:

- `reranker`: `status: active`, `modelActive: true`, `executionProvider: cuda`,
  `gpuFallback: false` — fully healthy
- `lambdamartModel`: `state: DEGRADED`, `reasonCode: lambdamart.not_configured`
- `searchTrace` concurrently shows `cross-encoder: executed` and
  `lambdamart: skipped (MODEL_NOT_LOADED)`

The original note worked through the reranker's fields and proposed the fix *"Search reranking
is unavailable"*, which would name the wrong feature. **This strengthens the finding rather than
weakening it**: the string was vague enough that a careful reader with full API access resolved
it to the wrong model. That misresolution is the best available evidence for the defect and
should be stated in the finding.

**Suggested fix:** name the actual feature in the caveat (*"Learned re-ranking is unavailable —
…"*) and link to Health detail.

**Regression home:** ui-shot assertion that the info-tier availability row names the specific
feature — CI-gateable, no sandbox-must-watch entry needed.

## 9. [MEDIUM] Search-surface degradation banner ties visual weight to neither actionability nor lifespan

The banner correctly names its two causes — both truthful — but they have incompatible
lifespans presented as identical bullets under one warning triangle:

- *"Passage embeddings are still being computed"* — transient, self-healed in under 60s
  (sampled live: `chunkEmbeddingPendingCount` 1554 → 154 → 0, `chunkVectorCoveragePercent`
  90.1 → 99.0 → 100, ending `chunkVectorsReady: true`)
- *"Learned re-ranking (LambdaMART) is not configured"* — permanent, with no configuration
  surface

Once the transient cause clears, the banner persists indefinitely for an info-severity optional
gap, occupying ~25% of the vertical space above the fold and compressing results into a nested
scroller (3 hits visible where ~6 would fit) — a standing, unresolvable warning in the same slot
a genuine retrieval failure would use, which trains the alarm to be ignored.

Presentation also outweighs fact: headline *"Reduced search capability"* sits against subtitle
*"results are still fully semantic"* while the result strip independently reports
*Semantic + keyword* (dense executed normally).

**[corrected] Evidence fix — the "zero fields" claim is false.** The original note stated that a
regex sweep of `/api/status` for lambda/LTR/learnedRank returns zero fields. It does not.
`/api/status` carries a first-class capability entry:

```json
"lambdamartModel": { "state": "DEGRADED", "reasonCode": "lambdamart.not_configured",
                     "source": "head_gpl_status", "stale": false }
```

plus `lambdamartModel`, `rerankerModelPath`, `rerankerOrtCuda`. The **conclusion** likely still
holds — `source: head_gpl_status` suggests a licensing-gated capability that is genuinely not
user-configurable — but the claim must be restated as *"a capability field exists; a user-facing
configuration surface does not"*, because anyone who greps will find these fields and discard
the finding along with the false premise.

**Diagnostic trap worth recording separately:** the transient cause is invisible on the counter
a reader would naturally check. `/api/knowledge/status` reported `embeddingCoveragePercent: 100.0`,
`queueDepth: 0`, `pendingJobsCount: 0` — all doc-level, all clean — while the real passage-level
state lived only in `/api/status` (`chunkEmbeddingPendingCount`, `chunkVectorCoveragePercent`,
`chunkVectorsReady`). The doc-level counter reads fully healthy during an active chunk backfill.

**Suggested fix:** transient causes render as a thin inline progress line that self-dismisses on
completion; permanent optional gaps drop out of the banner into Health detail; reserve the
triangle + CTA for causes that are both actionable and retrieval-affecting.

**Regression home:** ui-shot asserting an info-severity-only verdict renders no banner-tier
warning, plus a host test that a cause with no configuration surface is never worded as "not
configured".

## 10. [LOW] Advanced Brain diagnostics footer — `gen` and "mode" are unqualified and collide with other in-product meanings

The footer renders `gen: 1` (backed by `/api/inference/status` `generation`, also
`/api/status` `inference.identity.generationId`) with no expansion, unit, or tooltip.

**[verified] The collision is real and demonstrable:**

```
/api/inference/status   generation                = 1                    (int, engine instance)
/api/knowledge/status   servingSearchGenerationId = g-20260805-155401    (string ID, index)
```

Two unrelated "generation" concepts with different types, so the abbreviation invites the wrong
binding. And `gen: 1` adjacent to `Qwen_Qwen3.5-9B-Q4_K_M.gguf` reads plausibly as
"1st-generation model" — a version/tier claim rather than an engine-instance count. The field's
meaning is also purely differential (it answers "did the engine actually reload?") but is
presented as a static absolute with no baseline or last-reload reference.

Separately, *Recent mode transitions (1)* doesn't say which mode: the same window carries a
Simple | Detailed toggle and the search surface has rungs, while this one means the inference
state machine.

**[corrected] Premise fix:** the original note cited 737 §3a for "offline is not on the wire".
`/api/inference/transitions` returns
`{"fromMode":"OFFLINE","toMode":"ONLINE","reason":"AUTO_START","durationMs":27798}` — **OFFLINE
is on the wire as a mode value.** The narrower claim (that a transition *to* offline will never
appear, since "Shut Down AI" is actually `switchInference('indexing')`) is untested and not
refuted, but the premise as written is wrong. Note also that with exactly one transition
recorded, "Recent mode transitions (1)" currently shows a true count of one real event, which
makes the label ambiguity harder to notice rather than easier.

*Recent spans (10) · click a row to copy trace ID* is correctly worded for its audience and is
not part of this finding — its known issue is placement (F8).

**Not a truthfulness defect; nothing here is false.**

**Suggested fix:** `engine generation: 1` with the transition count or last-reload timestamp
beside it, and qualify the transitions label (*Recent inference transitions*).

**Regression home:** ui-shot assertion on the Advanced Brain footer label text — fully
CI-gateable.

**Caveat:** assessed from a partial crop plus API shape; no evidence screenshot captures the
footer expanded, so framing inside the collapsed sections was not observed.

## 11. [LOW] "Run Offline Processing" — the label overloads "offline" and states its precondition backwards

The control dispatches `core.trigger-offline-processing`, which drains pending enrichment queues
on already-indexed documents (VDU vision transcription via `VduBatchProcessor` → `VduProcessor`,
plus embedding/SPLADE backfill). It re-reads no source files and creates no index generation.

The name conveys none of that, and "offline" is overloaded across at least four in-product
meanings: the Brain surface's *"AI Offline — Start AI to enable chat"* (engine not running),
"Offline Mode" as the idle state during which this work happens automatically, the product's
local-first/no-cloud positioning, and a wire vocabulary in which offline is not a *mode* the
user selects (737 §3a).

Both natural readings are wrong — "processing without a network" describes everything this
product does and discriminates nothing, while "processing while AI is offline" is the inverse of
the truth: the operation is gated on `InferenceOnline` and returns *Required capability
unavailable: inference-online* when AI is down (itself circular, since its own Phase A brings
inference online — `OfflineCoordinator.java:113-124`, 737 §4).

Evidence of live misreading: an in-round reader independently glossed the control as "offline
indexing" — it is neither offline-capable nor indexing. Aggravating: the button renders only
when queues are non-zero, so a user meets an unfamiliar verb exactly once, with no prior
exposure to build meaning from.

Directly adjacent to findings 1–3: whatever that redesign lands on has to name this action too,
and the reindex-vs-enrichment distinction it encodes is the same distinction the completion-
message correctness bug turns on.

**Suggested fix:** name the work, not the mode — *Process pending enrichment (N documents)*,
with pending counts inline; reserve "offline" for the idle-state concept only.

**Regression home:** ui-shot assertion on the control's label + a copy-lint that "offline"
appears in exactly one user-facing sense.

---

## 12. [MEDIUM] Vertical space has no owner — chrome accreted into ~60% of the window

Measured off a ~790px-tall window (an ordinary laptop height, not an edge case): surface header
~42px, degradation banner ~100px, run-telemetry band ~90px, Watch/Assist/Auto + Abilities +
composer + rung row ~160px, status bar ~35px — leaving ~290px (~40%) for the agent's answer plus
its Sources panel, and closer to a quarter of the screen for answer prose alone once the ~310px
Sources column is deducted. The answer consequently opens mid-sentence, clips at the bottom, and
scrolls inside a nested region while the page itself does not.

The mechanism is likely correct (pinned chrome + scrolling content is what a proper flex column
yields); what is missing is **allocation policy** — every band takes its intrinsic height, the
content pane absorbs 100% of the remainder, and no owner budgets the total.

Supporting signature: the layout reasons about width (Sources side column, full-bleed bands,
stretching composer) but nothing responds to height — horizontal breakpoints exist, vertical
ones do not.

Accretion is the root cause and the duplications are its fingerprint — three facts rendered six
times: *Reduced capability* in both the top banner and the bottom status-bar chip (~660px apart,
simultaneously visible), *Over budget +1207 tokens* twice within ~40px, and the source count
three times in ~250px (`Sources · 57` panel header, `Based on 57 sources` footer, `▸ Sources · 57`
disclosure). Each band was independently justified by its own workstream (593/600 truthfulness
banner, 565 agent telemetry, escalation-ladder rung row); none owned the sum, and nothing was
ever removed.

Aggravating: the telemetry band is developer instrumentation expanded by default despite having
a disclosure triangle, and it renders alarm framing on a success — the run reports DONE with a
real answer over 57 sources while showing two budget warnings and a dark-red 93% context meter.
Same severity-untied-to-consequence class as finding 9, twice on one screen.

**Credit where due:** *Based on your documents — per-sentence grounding not verified · 45.7s ·
Qwen_Qwen3.5-9B* is an honest disclaimer worth preserving, and the rung buttons are
self-describing.

**Not verified:** no source code in the sandbox, so the flex structure and absence of height
media queries are inferred from observed behaviour, not code claims. The discriminating test is a
vertical resize sweep asserting which bands hold height while the content region absorbs all
compression.

**Suggested fix:** collapse the telemetry band by default and suppress "Over budget" on
successful completion; render each duplicated fact once; give bands max-heights and the content
region a stated floor (~180px recoverable, ≈60% more reading area, no capability removed).

**Regression home:** ui-shot at a pinned 1366×768 viewport asserting the answer region holds a
minimum share of window height, plus a copy-lint that no status fact renders in two persistent
surfaces simultaneously.

## 13. [MEDIUM] Nested scroll regions — at least three scrollbars stack on one surface, and the idiom is reused for two non-scroll meanings

Identifiable with confidence: the answer pane's scroller (x≈1103, visible up/down arrows), the
Sources panel's own (x≈1285), and an apparent outer scroller at the right edge — "at least
three, likely four" is the honest reading from a static capture, since overlay scrollbars may
render only on hover.

Compounding this, two elements adopt scrollbar geometry without being scrollbars: the run spine
(x≈225, vertical track with node dots — the 565 segment-boundary marker) sits in the exact
position and shape of a scrollbar track, and the telemetry band's purple budget meter and
dark-red context meter both render as thin full-width horizontal tracks indistinguishable from
horizontal scrollbars. The idiom therefore carries three unrelated meanings on one screen —
scroll position, run structure, resource consumption — with no visual cue as to which tracks are
draggable.

Consequences are functional, not cosmetic: **scroll capture** (wheel acts on whatever region the
cursor is over, with inconsistent chaining at boundaries), **invisible focus** (Page Down /
arrows act on the focused scroller and nothing indicates which that is), **no single position**
(three independent offsets mean "where am I in this response" has no answer), and
**accessibility** (nested scroll regions introduced as a layout workaround typically lack the
explicit focus handling needed for keyboard reachability). Desktop context aggravates it — this
is a Tauri/WebView2 shell where users bring desktop expectations, not nested web panes.

**Causally downstream of finding 12, not independent of it:** chrome consuming ~60% of height
forces the answer to scroll internally, which puts the Sources panel inside an already-short
region and forces a second scroller. Each nested scrollbar marks a place the layout ran out of
room and solved it locally, so fixing the height budget removes scrollbars two and three without
touching them directly.

**Reproduces on a second surface:** the Search results list is likewise in a nested scroller
compressed by the same degradation banner, so this is a layout-wide mechanism, not an agent-view
quirk.

**Suggested fix:** one scroll region per surface (page-level), reserve scrollbar geometry
exclusively for scroll affordances, and re-style the run spine and the two meters so they are not
confusable with tracks.

**Regression home:** ui-shot asserting exactly one scrollable region per surface at a pinned
1366×768 viewport.

## 14. [MEDIUM] New chat and Export are mode-gated out of the Delegate rung

GUI-verified in both directions on a live window, three captures one click apart at identical
1462×800 dimensions:

- Delegate rung renders `History  Activity` with `Agent` right-aligned
  (`evidence/80-header-agent-rung.png`)
- clicking *Search — instant, no AI* restores `History  Activity  New chat  Export` with
  `Search` right-aligned (`evidence/81-after-click-search-rung.png`)
- clicking back to Delegate removes them again (`evidence/82-back-to-delegate.png`)

Rung crops confirm each switch landed, so the header difference tracks the rung and nothing else.
Responsive overflow is ruled out — window dimensions are identical across all three and roughly
1,000px of empty header space sits between Activity and the right-hand button in every capture;
the controls are not collapsing for room, they are not rendered.

The state this strands the user in is concrete: the live session read `state:"DONE"`,
`resumable:false`, `iterationsUsed:7`, `toolCallsExecuted:6`, `totalTokensUsed:21431` against
20,224 granted, with the UI showing *Context: 3817 of 4096 tokens (93%)* — a finished,
unresumable, near-full run whose reset and save affordances are both absent, where the only
discoverable recovery is switching rungs, which nothing suggests changes the header.

Export is arguably the worse loss: a Delegate run is the costliest artifact the product generates
(7 iterations, 6 tools, 45.7s, 57 sources) and is the only mode that cannot export its result.

**Not verified:** whether the top-right `Agent` button opens a panel offering an equivalent
new-run or export route — only the header row was captured, so "no route exists" is not claimed,
only that the header route is absent.

**API note:** there is no explicit create-conversation endpoint; a new conversation is minted
implicitly by `POST /api/chat/agent` with fresh `messages[]`, and `DELETE /api/chat/sessions/{id}`
cancels only active sessions so it does not apply to a DONE run.

**Regression home:** ui-shot asserting the header control set is invariant across all four rungs
at a fixed viewport.

## 15. [LOW] Run spine renders workflow-node machinery in plain conversation

The left-gutter spine is the `RunSegmentRef` / `assignRunSegments` node-boundary visualization
("the spine marks node boundaries", 565 §26) rendering unconditionally in ordinary chat, where
there are no node boundaries worth marking. Captured live against four visible content blocks
(assistant answer, user message, 2 events disclosure, TOOLS card) it draws ~10 markers — a
repeating green-dot-then-small-dark-dot pair at ~15px spacing, four times, plus a purple-ringed
and a grey-ringed marker — at roughly 2.5× content density, with three distinct glyph types and
no legend or adjacent labels to decode any of them.

Two colour collisions within one crop: the spine's green dots are near-identical to the
grounded-status dot in *● Grounded · 1 of 6 sentences* ~130px away, and the emphasized marker's
purple ring is the same purple as the user message bubble immediately right of it — so purple
simultaneously means "you said this" and "current position". The track is itself rendered as a
dotted line, so track and marks share a primitive and compete visually.

Alignment is too loose for navigation — the marker beside the user message sits at roughly its
top third, not its top edge — which removes the only function a gutter index has. Crowding is
structural, not incidental: three markers already overlap within ~50px at the bottom, and because
track length is bounded by viewport height while run events grow unbounded, collisions worsen
monotonically with conversation length, with no visible clustering or aggregation rule. It also
consumes ~60px of column inside the answer region finding 12 showed compressed to ~40% of the
window.

**Scrollbar mimicry is the aggravating factor** (see finding 13): vertical track at the left edge
of a scrolling region, full-height, with one visually-emphasized element reading as a thumb —
every affordance cue says draggable scroll control.

**Not verified:** two attempts to establish pinned-vs-scrolls (click into pane + `{PGDN}`)
produced pixel-identical captures, inconclusive since the pane may already be at its scroll
extent. No claim that the spine is non-interactive, only that it could not be moved.

**Fair counter-argument:** for a genuine multi-node workflow run the concept is sound and the
density problem largely dissolves, so the defect is **unconditional rendering**, not the
component.

**Suggested fix (cheapest first):** suppress the spine when a run has no meaningful segmentation;
then add hover labels or a legend, a colour outside the grounded-green / user-purple vocabulary,
a solid track, and a declared aggregation rule for collided markers.

**Regression home:** ui-shot asserting no spine renders for a single-turn conversation, plus a
marker-count-vs-segment-count assertion for runs that do segment.

**Evidence:** `evidence/83-run-spine-context.png`, `85-spine-after-scroll.png`,
`86-spine-wide-crop.png`.
