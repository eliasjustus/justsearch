# 847 — End-to-end citation correctness: the mark must land, survive reload, and never outrun its evidence

```
status:  PARTIALLY IMPLEMENTED — charter A; rev 2, adversarial review incorporated
         (APPROVE-WITH-AMENDMENTS; every amendment re-verified against source before adoption).
         S1 (one record→evidence authority, both halves gated), S2 (v3 live producer gate) and
         S3 (v3 rehydration + recordId identity + panelSpeaks) are IMPLEMENTED by this PR.
         S0, S4, S5, S6 remain PENDING (S4 still gated on 846 + S0).
         Implementation deviation, S3 item 1: `coverage` / `sourceCoverage` are NOT projected onto
         the restored turn. `Sv3TurnEvidence` carries no such fields and no v3 consumer reads them
         (the live `sv3-ask.ts` path does not read them either), so projecting them would have added
         unconsumed substrate rather than parity — live and restored already agree on this axis.
         Adopted beyond the letter of §1.5b: `UnifiedChatView`'s LIVE `this.citations = p.matches`
         assignment is gated too. Gating only the record path would have made a reloaded render
         stricter than the live one in the shipped window — the 561 P-A divergence in a new place.
created: 2026-08-19
related: 836 (literal-passage verification seam + the §4 producer gate — SHIPPED, #466/#473),
         839 (citation-mark presentation — shipped), 822 (the citation chain: F-047…F-050),
         561 P-A (evidence non-divergence: live and reloaded renders may not disagree),
         846 (parallel charter: Marked factory + typography consolidation in
              MarkdownBlock.ts/DocumentPane.ts — forbidden from touching citation semantics)
```

> **Merge order.** 846 lands first. S1 below rewrites the anchoring internals of
> `MarkdownBlock.decorateCitations`; 846 rewrites the *renderer construction* in the same file.
> Designed against current anchoring semantics; rebase S1 onto 846's `main`.

---

## 0. What this document corrects about its own brief

Four defects were handed to this design by a prior audit. **Three are confirmed; one is refuted, and
the refutation relocates the honesty problem to a worse place.** Recorded here rather than quietly
dropped, because the refuted one is the load-bearing claim about honesty and a design built on it
would have fixed a working path while leaving a broken one alone.

| Briefed | Verdict |
|---|---|
| **D1** Inline `[n]` anchoring fails on list/markdown answers | **CONFIRMED** — mechanism verified, and it is *two* defects, not one (§1.1, §1.2) |
| **D2** Search v3 wipes evidence on reload | **CONFIRMED**, and worse than stated: it is never rehydrated at all, warm or cold (§1.3) |
| **D3** Persisted `claimMatches` carry no scorer, so rehydrated matches cannot pass the honesty gate | **REFUTED** (§1.4). They carry it, and a test pins it. The real gate hole is **live, in search v3** (§1.5) |
| **D4** `applySv3Record` merges by array index | **CONFIRMED**, plus an unbriefed consequence: the merge also *overwrites the turn's id*, silently invalidating id-keyed UI state (§1.6) |

Two further defects were found that the brief did not name: **§1.2** (a sentence supported by two
sources renders only one mark) and **§1.6b** (the merge overwrites the turn's id).

**Rev 2 — what adversarial review changed.** Review confirmed all four verdicts above and every
`file:line` behind them, and then found that rev 1 was wrong in three ways of its own, each recorded
in place rather than silently patched:

- **§1.2 was misdiagnosed one layer too low.** Rev 1 blamed `decorateCitations`'s dedupe; the loss
  actually happens in `claimsToCitations`, which emits one `Citation` per *claim*, not per *ref*. Rev
  1's fix was therefore unreachable and its test T3 could never have gone green.
- **§1.5b — the gate had a second arm rev 1 did not look at.** The `CitationMatch[]` array reaches the
  sources panel ungated on four sites, so rev 1's plan would have shipped a one-screen contradiction:
  no marks, but a panel still claiming "Grounds N sentences" at a cosine-derived tier.
- **§2.1a — rev 1's `≥ 4 tokens` over `/[\p{L}\p{N}]+/gu` was a Hard-Invariant-6 violation.** It makes
  tier 1 structurally unreachable for unspaced scripts (CJK/Thai/Khmer/Lao) — a per-language lever —
  and it also regressed short Latin sentences against today's 4-*character* floor.

And it settled §7 Q2 (the observed surface was Search v3, cold-loaded), which **refutes rev 1's
suggestion that §1.5 could explain the symptom**: §1.5 is fail-open and can only add marks. §1.3
alone explains it. See §7 Q2 for the corrected chain and what that implies for S4's priority.

---

## 1. Verified current state

Every `file:line` below was opened on `main` at `F:\justsearch-public` on 2026-08-19.

### 1.1 D1a — the anchor key and the anchor target are normalized by different rules

`MarkdownBlock.decorateCitations` (`modules/ui-web/src/shell-v0/components/chat/MarkdownBlock.ts:728-810`)
flattens the rendered DOM's text nodes into one string `full` (`:734-744`), then for each `Citation`
builds a whitespace-tolerant regex out of `stripMarkers(cite.sentenceText)` (`:749-753`) and executes
it against `full` (`:757`). A miss is a silent skip (`:758`).

`stripMarkers` (`:915-917`) is:

```ts
return s.replace(/\[(.*?)\]\((.*?)\)/g, '$1').replace(/[*_`~#>]/g, '');
```

A **character blacklist**. It does not remove list bullets (`- `, `* ` at line start survives as
whitespace + nothing since `*` is stripped, but `- ` is not), ordered-list ordinals (`2. `), table
pipes (`|`), or setext underlines. The rendered DOM, meanwhile, contains *none* of the block-level
syntax at all — an `<li>`'s ordinal is a CSS-generated marker, not a text node; a table's pipes are
cell boundaries, not characters.

So the two sides are normalized by different rules and the comparison is asymmetric by construction.
Any block-level markdown shape makes the regex miss.

The `sentenceText` being matched comes from the **Worker**, not the Head:
`CitationMatchOps.splitSentences` (`modules/worker-services/src/main/java/io/justsearch/indexerworker/services/CitationMatchOps.java:475-490`)
runs `BreakIterator.getSentenceInstance(Locale.ENGLISH)` over the raw LLM answer text. (The brief
attributed this to `StreamingCitationMatcher`; that file's `extractCompleteSentences` at `:219-250`
is the *streaming lexical* path feeding `rag.citation_delta`, which mints no marks. The persisted
`sentenceText` is the Worker's.) Segmenting markdown with a prose sentence iterator fuses a list
item's trailing `.` with the next item's ordinal, producing the reported
`**Retrieval Pipeline**: … [1][3].\n\n2. ` — one anchor key containing two list items' worth of text.

**Consequence:** every bulleted, numbered, table-celled or heading-adjacent sentence fails to anchor.
Cross-encoder-verified claims at 0.96+ exist and render nothing.

### 1.2 D1b (unbriefed) — a sentence supported by two sources renders one mark, and the loss happens EARLIER than it looks

**Rev-2 correction (adversarial review, re-verified here).** Rev 1 located this defect in
`decorateCitations`'s dedupe. That is only the second of two gates, and the first one is decisive.

`claimsToCitations` (`modules/ui-web/src/shell-v0/components/chat/citationResolve.ts:55-63`) emits
**exactly one `Citation` per `Claim`**:

```ts
const refIdx = Array.isArray(cl.verifiedRefs) ? cl.verifiedRefs[0] : undefined;   // :55
…
out.push({ …, sourceRefs: cl.verifiedRefs, label: refIdx + 1, … });               // :59-63
```

`label` is `verifiedRefs[0] + 1` — the *first* ref only. `sourceRefs` carries the full list but is
**dead**: it is declared (`MarkdownBlock.ts:48`) and written (`citationResolve.ts:62`), and the only
reads in the repo are assertions in `citationResolve.test.ts:54,189`. No renderer consumes it.

So on the RAG path a sentence supported by sources 1 and 3 produces **one** `Citation` labelled 1,
and the second source is lost *before* `decorateCitations` is ever entered. The `endIndex` dedupe
(`:747`, `:760-761`) is a real second gate that would drop a duplicate if one arrived — but on the
RAG path one never does.

**Consequence for the design:** a per-label dedupe key in `decorateCitations` is necessary but not
sufficient, and alone it is unreachable. `claimsToCitations` must emit per verified ref (or mint a
multi-label marker) for a second mark to exist at all. §2.1 and T3 are specified against that.

(The agent path is unaffected: `resolveAnswerCitations:92-108` already emits one `Citation` per
`AgentSentenceCite`, and a sentence with two sources arrives as two cites.)

### 1.3 D2 — search v3 never rehydrates evidence, warm or cold

`modules/ui-web/src/shell-v0/views/search-v3/sv3-record.ts:158` sets `evidence: null`
unconditionally for every record-projected turn. Its own header comment (`:101-106`) states the
record *does* carry the persisted sources, and delegates recovery to the merge step.

The merge step cannot recover it. `applySv3Record`
(`modules/ui-web/src/shell-v0/views/search-v3/sv3-sessions.ts:652-672`) does
`evidence: prior.evidence ?? recorded.evidence` (`:658`) — and since `recorded.evidence` is always
`null`, this is "keep what the live session already had". On a **cold load** the session starts
`turns: []` (`:593`), so `prior === undefined` for every index and the `if (prior === undefined)
return recorded;` branch passes the record through verbatim, `evidence: null` intact. There is no
other code path that fetches evidence for a restored turn.

The data is not missing from the wire. `GET /api/thread/{id}`
(`modules/ui/src/main/java/io/justsearch/ui/api/InteractionThreadController.java:247-289`) copies
`citations`, `calibration` and `claimMatches` verbatim into each event's `attributes` (`:267-280`),
and the shared projection passes `attributes` straight through
(`modules/ui-web/src/shell-v0/views/unifiedThreadProjection.ts:327`, type at `:150`). `sv3-record.ts`
simply does not read them. The window discards evidence it was handed.

### 1.4 D3 — REFUTED: the scorer *is* persisted, and a test pins it

`StreamingCitationMatcher.toCitationMatchPayload`
(`modules/app-services/src/main/java/io/justsearch/app/services/conversation/spi/StreamingCitationMatcher.java:326-355`)
emits `scorer` at `:331` (`result.scorer().name()` — `CROSS_ENCODER` | `EMBEDDING_COSINE` | `NONE`),
plus `sentencesScored`, `sourceCoverage`, and per-match `textSource` (`:350`). **The same map object**
is returned as both the live SSE event and the done-payload entry (`:150`, `:155-159`):

```java
Map<String, Object> payload = toCitationMatchPayload(result);
return new StreamConsumerResult(
    List.of(new SseEvent("rag.citation_matches", payload)),
    List.of(), List.of(),
    Map.of("claimMatches", payload));
```

`ConversationEngine.persistedAssistant` (`…/conversation/ConversationEngine.java:846-865`) copies
`claimMatches` onto the record unmodified (`:860-863`), and the FE reload path reads it:
`UnifiedChatView.claimsFromRecord` (`modules/ui-web/src/shell-v0/views/UnifiedChatView.ts:5368-5409`)
calls `readScorer(claimMatches)` (`:5377`, defined `:429-435`) → `isVerifiedProducer` (`:5378`) and
nulls the score when the producer is not admitted (`:5383`).
`StreamingCitationMatcherPayloadTest:43,53` asserts `scorer` is on the payload.

So the honesty gate **does** work on reload in `UnifiedChatView`. The briefed shape
(`{…, chunkIndex}` and no scorer) is stale on two counts: the field is `sourceIndex` (`:347`, with a
legacy `chunkIndex` fallback in `readSourceIndex`), and the envelope carries `scorer`.

**What survives the refutation** is a narrower, real fragility. `isVerifiedProducer`
(`modules/ui-web/src/shell-v0/components/chat/evidenceProjection.ts:390-391`) **fails open**:

```ts
return scorer === undefined || scorer === null || scorer === '' || scorer === VERIFIED_SCORER;
```

justified in its own doc comment by a claim about a different module — "the field is emitted on every
response since tempdoc 836 S1, so absence means a record persisted BEFORE the field existed". That
justification is true today and is pinned only on the Java side. Nothing on the FE side would notice
if a *new* producer path started omitting it, at which point a narrow legacy allowance becomes a
blanket bypass. (See §6 principle P2.)

### 1.5 The real gate hole — search v3 bypasses the producer gate on the LIVE path

`modules/ui-web/src/shell-v0/views/search-v3/sv3-ask.ts:349-364`:

```ts
onRagCitationMatches(payload: unknown) {
  const p = payload as { matches?: CitationMatch[] } | null;
  if (!p || !Array.isArray(p.matches)) return;
  matches = p.matches;
  for (const m of p.matches) {
    mergeClaim(claims, m.sentenceIndex ?? 0, m.sentenceText ?? '',
      typeof m.similarity === 'number' ? m.similarity : 0,
      'verified',                                        // ← unconditional
      typeof m.sourceIndex === 'number' ? m.sourceIndex : null);
  }
  publish();
}
```

`p.scorer` is never read. Every match is merged as `'verified'` provenance, and `claimsOf` (`:256-266`)
emits `Claim` objects with **no `scorer` field at all** — which `isVerifiedProducer(undefined)` then
admits. So on a scorer-less or scorer-failing install, search v3 paints embedding-cosine numbers with
cross-encoder-calibrated grounding tiers. 836 §9.7 measured that cosine's supported and unsupported
bands interleave at a 0.0049 margin, which is precisely why that gate exists.

The bypass has a stale rationale in the file (`:213-217`):

> *Tempdoc 822 §3d — which producer scored this sentence. The two citation events are already
> separate handlers, so the provenance needs no wire field: it is the handler that knows.*

That was correct when the only producer distinction was `citation_delta` (lexical) vs
`citation_matches` (authoritative). 836 introduced a second distinction **inside**
`rag.citation_matches` — which scorer wrote the similarity — that the handler cannot know. The comment
is now false and must be swept (`retire-with-a-sweep`), not left as a justification for the hole.

### 1.5b The gate has a second, ungated arm: the `matches` array feeding the sources panel

**Found by adversarial review; re-verified here.** The producer gate today lives only on the
`Claim` → mark path. The **`CitationMatch[]` array travels beside it, ungated, to the sources panel**,
where it is turned into a grounding verdict by an *independent* tier computation:

- `sv3-ask.ts:352` — `matches = p.matches;` verbatim, no scorer check.
- `UnifiedChatView.matchesFromRecord:5418-5431` — maps `similarity: typeof m.similarity === 'number' ? m.similarity : 0`,
  with **no** `isVerifiedProducer` call anywhere in the method (contrast `claimsFromRecord:5377-5378`,
  which does gate).
- Consumers: `UnifiedChatView.ts:5271` (`citations: this.matchesFromRecord(...)`) and
  `Sv3Main.ts:1378` (`.citations=${[...turn.evidence.matches]}`).
- `evidenceProjection.sourceGrounding:611-641` then computes `best` as `max(m.similarity)` over the
  matching sources and returns `tier: evidenceTier(cited ? best : 0)` plus `groundedSentences: count`
  — **straight from the raw similarity**, never through the gate.

**Why this is design-blocking rather than a follow-up:** fixing §1.5 alone produces a *contradiction
on one screen*. Under `EMBEDDING_COSINE` the prose would render markless (correct — the gate fired)
while the sources panel beside it still announces "Grounds N sentences" at a cosine-derived evidence
tier (incorrect — the gate never ran). Two surfaces of one evidence verdict, disagreeing, is strictly
worse than the current uniform-but-wrong state: it teaches the reader that the absence of marks means
nothing. The gate must move with the data, not with one of its two consumers.

### 1.6 D4 — index-keyed merge, and the id overwrite

`applySv3Record` (`sv3-sessions.ts:652-672`) matches `recordTurns[index]` to `local[index]`
positionally; no `.id` comparison exists in the function. Any length skew (a locally-visible turn the
record has not caught up to, an interleaved agent turn, a failed turn recorded differently)
mis-attributes one turn's evidence, status, reasoning and duration to another.

**1.6b (unbriefed).** The merged object is `{...recorded, …}`, so the turn's `id` becomes the
*record's* event id — pinned as intended behaviour by `sv3-sessions.test.ts:833-840` ("taking the
record's id as the stable handle"). But live turns are minted with a **positional** id
(`turnIdFor = (sessionId, index) => \`${sessionId}#t${index+1}\``, `sv3-sessions.ts:216-225`), and
every other write addresses a turn by id-equality (`mapTurn`, `:340-359`). UI state keyed on
`turn.id` — `Sv3Main.ts:889` `expandedSources`, `:1289` `copiedTurnId` — goes stale the instant a
merge swaps the id, and the write silently no-ops rather than erroring. No test covers this.

### 1.7 The turn-kind flip hides evidence from turns that have it

`sv3-record.ts:150-151` flips a turn to `kind: 'agent'` when *any* activity entry is not `text` —
which includes a `progress` or `error` note on an otherwise ordinary ask. `Sv3Main.panelSpeaks`
(`:1345-1348`) then short-circuits:

```ts
if (turn.kind !== 'ask' || turn.status === 'streaming' || turn.evidence === null) return false;
```

So a grounded ask that emitted one progress note shows no sources — the turns most likely to have
notes are exactly the long, retrieval-heavy ones most likely to have evidence.

### 1.8 Deliberate degradations that must stay deliberate (unchanged by this design)

- Cosine-fallback suppression, `CitationMatchOps.java:269-273`.
- gRPC failure → `ScorerKind.NONE`.
- The 5 s matcher timeout swallow, `StreamingCitationMatcher.java:160-163` (`catch (Exception)` →
  DEBUG log → `StreamConsumerResult.empty()`), i.e. a slow match degrades to *no* marks, never to
  unverified ones. This design touches none of them, and every change below preserves the property
  they exist for: **the absence of a mark is always an acceptable outcome; a mark that outruns its
  evidence never is.**
- `RAGContext.java:374-386` emitting `rag.citations` for passages truncated out of the model's
  context (`docs/observations.md` ~:1810) is **out of scope**. This design does not worsen it: it adds
  no new consumer of `rag.citations` cardinality and changes no source-list construction. §1.3's fix
  makes those citations *visible after a reload in search v3* exactly as they already are live — the
  same population, not a larger one.

---

## 2. The design

### 2.0 The shape of the problem

One answer text exists in two representations: the **raw markdown** the model emitted (what the
Worker segments and the cross-encoder scores) and the **rendered DOM** (what the reader sees and
where a mark must physically land). The system bridges them with a hand-maintained character
blacklist applied to one side only.

That is the representation-drift class this repo already names — two representations of one fact,
reconciled by discipline instead of by construction. The fix is not a better blacklist. It is to make
the bridge **independent of the markdown grammar** so no markdown shape can desync it.

### 2.1 Anchoring — word-sequence matching, with a verified-label literal fallback

**Chosen design: three tiers, strictly ordered.**

**Tier 1 — word-sequence anchoring (primary).** Replace regex-on-stripped-text with token matching:

1. Tokenize the flattened DOM text `full` with
   **`new Intl.Segmenter(undefined, { granularity: 'word' })`**, keeping only `isWordLike` segments
   and recording each segment's character offsets in `full`.
2. Tokenize `cite.sentenceText` the same way, after collapsing inline links (`[text](url)` → `text`)
   so a URL's words never enter the key. **No other stripping** — every markdown syntax character is
   non-word-like and therefore never a token.
3. Find the run in the DOM token array that matches the **longest prefix** of the sentence token
   array. Longest run wins; ties resolve to the earliest. Acceptance floor: §2.1a.
4. `startIndex` / `endIndex` are the char offsets of the run's first and last token (the end extended
   over immediately following non-whitespace punctuation, so the mark still lands after the period).
   The existing insert machinery (`:774-808`) is unchanged from there.
5. **Advance rule (§2.1b).** Consume the matched DOM range; the next citation searches only from the
   previous match's `endIndex` onward.

Why this closes the class: bullets, ordinals, pipes, `#`, `>`, `*`, `_`, backticks and blockquote
markers are all non-word-like, so they are absent from *both* token streams by construction —
symmetry by construction rather than by a maintained list. And **longest-prefix** (rather than full)
matching is exactly what dissolves the fused-tail case: the key's tokens are `… search 1 3 2` while
the DOM's are `… search 1 3`; the prefix run covers everything but the stray ordinal.

#### 2.1a Why `Intl.Segmenter`, and why the floor is not a token count

**Rev-2 correction (adversarial review).** Rev 1 specified `/[\p{L}\p{N}]+/gu` with a **≥ 4-token**
floor. Both halves were wrong, and the first is an invariant violation:

- `\p{L}+` has no word boundary inside scripts that do not space their words. A full CJK, Thai,
  Khmer, Lao or Japanese clause collapses into **one to three** tokens, so a ≥ 4-token floor makes
  tier 1 **structurally unreachable** for those scripts — every CJK answer would silently fall to
  tier 2 or to no mark. That is a per-language behavioural lever in the search/answer path, which
  **Hard Invariant 6** forbids (ADR-0043: analysis is locale-invariant, ICU-based).
- Even in Latin script it *regresses* against today: today's floor is **4 characters**
  (`MarkdownBlock.ts:750`, `norm.length < 4`), so a legitimately short sentence like `"It does not."`
  (3 tokens) anchors today and would stop anchoring under a 4-token floor.

`Intl.Segmenter(undefined, { granularity: 'word' })` is ICU's own word segmentation — the same
authority ADR-0043 already commits the engine to, applied locale-invariantly (`undefined` locale,
no per-language configuration, nothing to author or maintain per script). It segments CJK/Thai by
dictionary rather than by spaces, so the token counts become comparable across scripts.

**The acceptance floor is therefore expressed in characters, not tokens:** accept a run when the
matched span covers **≥ 60 % of the key's word-like characters** (see §2.1c on re-deriving that
number) *and* **≥ 4 word-like characters**, preserving today's short-sentence behaviour exactly. A
token-count floor is not used at all.

*Environment note:* `Intl.Segmenter` requires a full-ICU runtime. Node 18+ ships full-icu by default,
so vitest/happy-dom is fine; confirm in S4's first commit rather than assuming.

#### 2.1b The advance rule (repeated sentences)

**Rev-2 addition (adversarial review).** Per-label dedupe alone has a failure mode worse than today's:
if an answer repeats an identical sentence (common in list-shaped answers — "See above.", a repeated
bullet stem), every citation for every occurrence resolves its longest run at **occurrence 1**, and
the per-label key permits each to insert there. Marks would **stack** on the first occurrence while
later occurrences stay bare — strictly worse than today's single-mark behaviour.

Rule: matched DOM ranges are consumed. Citations are anchored in ascending `sentenceIndex` order
(the backend's own sentence order), and each search begins at the previous accepted match's
`endIndex`. This makes anchoring monotone in the answer's own sentence order, which is the fact the
ordering actually derives from — not an incidental scan order.

#### 2.1c Multi-source sentences

Two changes, both required (§1.2):

1. **`claimsToCitations` emits one `Citation` per verified ref**, not per claim — iterate
   `cl.verifiedRefs` instead of taking `[0]` (`citationResolve.ts:55-63`), each with its own `label`.
   The now-redundant `sourceRefs` field is **deleted** (`MarkdownBlock.ts:48`,
   `citationResolve.ts:62`, and its two test assertions) rather than left as a second, unread
   representation of the same fact — it is dead today and this change is what makes it dead
   *provably*, so it goes in the same PR (`retire-with-a-sweep`).
2. **`decorateCitations` dedupes on `` `${endIndex}:${label}` ``** instead of `endIndex`, groups
   insertions sharing an `endIndex`, and emits their markers in ascending label order in one split.

Alternative considered and rejected: a single multi-label marker (`[1,3]`). It needs its own
presentation, contrast and legend work under 839's closure rules, for no evidentiary gain over two
adjacent single-label marks that each carry their own source identity and deep link.

**Tier 2 — verified-label literal `[n]` upgrade (fallback).** When tier 1 finds no run for a
citation, `normalizeLiteralCitationTokens` (`:819-845`) already upgrades a literal `[n]` to a real
marker when `n` is the label of a gate-passed `Citation`. Keep it, unchanged in mechanism, and name
what it asserts: **source-level attribution placed by the model**, versus tier 1's **sentence-level
attribution placed by the cross-encoder**. That difference is why a tier-2 mark gets no
`.cite-sentence` underline — the sentence was never identified, so no span may claim it was. This is
already the behaviour; the design's contribution is to make it an *invariant with a test* rather than
an accident of control flow.

**Tier 3 — no mark.** The source still appears in the sources panel. Never invent a location.

**Multi-source sentences.** See §2.1c — the fix is upstream in `claimsToCitations` plus the dedupe
key, not the dedupe key alone.

**Ordering against 846 (assert, do not assume).** 846 adds a `highlightCodeBlocks(root)` pass over
the same rendered root. If it runs *after* `decorateCitations`, or replaces subtree HTML, it can wipe
`.cite-sentence` spans and `.cite-ref` markers inside fenced regions. S4 must assert the ordering in
`updated()` explicitly and cover it with a test (T19). Separately, 846 gates
`stripTrailingCitationBlock` on `citations.length` — S3 flips that input from 0 to N on restored v3
turns, so a restored answer's trailing model-authored "Citations:" list starts being stripped where it
previously was not. That is the intended behaviour, but it is a behaviour change S3 causes in 846's
code and must be verified there, not discovered later.

**Streaming.** Unchanged and deliberately so: `updated()` gates decoration on `!this.isStreaming`
(`:278`), so `rag.citation_delta` mid-stream mints no marks and this design adds no mid-stream DOM
work. The anchoring pass stays one O(tokens) sweep per settled render.

**Honesty invariants (each gets a test, §4):**

- **H1** A mark exists only for a `Citation` minted by `claimsToCitations` — structurally, since
  `this.citations` is the only input. Unchanged.
- **H2** A mark's tier is `groundingClass(cite.similarity)`, the cross-encoder score. Unchanged.
- **H3** A tier-2 mark carries no `.cite-sentence` underline.
- **H4** A tier-1 `.cite-sentence` span covers only the matched token run, never a whole block.

#### Rejected alternatives

**A — anchor on the model's literal `[n]` as the *primary* key.** Rejected. (i) It makes the mark's
*position* the model's assertion while its *colour* remains the cross-encoder's — mixing two
authorities at the one surface whose whole job is telling them apart. (ii) It makes marks contingent
on prompt compliance: `RAGQAStyle.java` asks for inline `[1]`, `[2]`
(`modules/app-services/…/conversation/style/RAGQAStyle.java`, FRAGMENT), but local models routinely
omit them, and an answer whose every sentence the cross-encoder verified would then render markless.
(iii) An uncontrolled model input becomes load-bearing for a trust surface. Retained as tier 2 only,
where it is strictly better than nothing *and already constrained* to verified labels.

**B — extend `stripMarkers` to cover bullets, ordinals and pipes.** Rejected as the root-cause fix.
A character blacklist is a hand-maintained fork of the markdown grammar; every markdown feature 846
or a future `marked` upgrade enables re-opens the identical defect. It would make the *observed* case
pass without making the *class* unconstructible — the shape `fix-root-causes-not-symptoms` forbids.

**C — segment sentences from rendered-normalized text on the backend.** Rejected. It puts a markdown
renderer in the Head or Worker and couples backend segmentation to the FE renderer's version (two
renderers, one contract — drift by construction). It also changes the text the cross-encoder scores,
perturbing 836's measured envelope and the `sentencesScored`/`sentencesTotal` coverage denominators,
for no anchoring benefit tier 1 does not already provide.

**D — true character-offset anchoring via a `marked` token walker maintaining raw→rendered offsets.**
Rejected *for this problem*, not in principle: it is the highest-fidelity answer, but it needs a
custom walker kept in sync with the sanitizer's rewrites, and word-sequence matching buys the same
robustness across markdown shapes for a fraction of the surface. Named here as the escalation path if
§4's shape matrix ever shows tier 1 failing on a shape word matching cannot reach.

### 2.2 Backend segmentation — stop segmenting markdown as prose

Tier 1 tolerates the fused key, but the fusion is an independent defect with an independent cost: the
Worker hands the cross-encoder a "sentence" containing the next list item's ordinal, so a scored
sentence is not the sentence, and the persisted evidence text is not what the reader will see marked.

`CitationMatchOps.splitSentences` (`:475-490`) gets two changes: **a blank line is a hard sentence
boundary**, and **leading block markers are stripped per line** (`- `, `* `, `+ `, `1. `, `#…`, `> `)
before segmentation. Both strictly reduce noise in the scored text.

This is sequenced last (§3, S5) and carries a known consequence: it **changes `sentencesTotal`**, the
denominator of 836 §3.6's coverage-honesty line. That is a correctness improvement (fused sentences
were undercounting), but the coverage tests move with it and must be updated in the same slice, not
after.

### 2.3 One record→evidence authority

`UnifiedChatView.claimsFromRecord` / `matchesFromRecord` (`:5368-5431`) are today the only place a
persisted `claimMatches` envelope becomes `Claim[]`, **and they carry the producer gate**. Search v3
needs the same conversion (§2.4). Writing a second one would fork the gate across two render paths —
the exact divergence `isVerifiedProducer`'s own doc comment says must not happen, and the 561 P-A
failure mode.

**Extract both into a shared module beside `citationResolve.ts`** (the existing home of the *one*
claim→Citation resolver) as the one record→evidence authority; `UnifiedChatView` delegates. It is a
projection of the persisted envelope, not a second authority over it.

**The extraction gates the `matches` array too (§1.5b).** `matchesFromRecord` is ungated today, and
moving it as-is into the module *named as the gate authority* would launder the hole into the very
place a reader will trust — the worst possible outcome of this refactor. The extracted authority
applies `isVerifiedProducer` once, at the envelope, and returns **both** halves already gated:
`Claim[]` and `CitationMatch[]`. A non-admitted producer yields an empty match array, so
`sourceGrounding:611-641` computes `cited: false` / `tier: evidenceTier(0)` without needing its own
gate — the tier stays a pure function of what it is handed, and the gate stays in one place.

Named orphans, deleted in this slice rather than left as wrappers: the private
`claimsFromRecord`/`matchesFromRecord` at `UnifiedChatView.ts:5368-5431`.

### 2.4 Search v3 rehydration

1. **`sv3-record.ts:158` stops hardcoding `null`.** Project `attributes.claimMatches` and
   `attributes.citations` off the assistant `UnifiedTurnItem` through §2.3's shared authority plus
   `claimsToCitations`, producing a real `Sv3TurnEvidence` (`sources` / `matches` / `marks` /
   `retrievalMode`). Also project `coverage` and `sourceCoverage` so v3's honesty frame reads the same
   facts live and restored. Delete the `:101-106` comment that justifies the discard.
2. **"Never told" is preserved.** A record turn with no citation attributes still projects
   `evidence: null` — the distinction `sv3-honesty.ts` depends on (absent ≠ zero) is not collapsed.
3. **`applySv3Record` matches by identity, not position.** `Sv3Turn` gains
   `recordId: string | null`; `id` is minted once and **never overwritten**. The merge indexes local
   turns by `recordId`, matches recorded turns to them, and for turns not yet reconciled falls back to
   ordered position — stamping `recordId` when it does, so each turn is reconciled at most once. A
   position fallback that would attribute a record turn to a local turn already bearing a *different*
   `recordId` merges nothing.
   This fixes §1.6b in the same move. The id-keyed consumers are **not merely cosmetic** — the full
   set is `Sv3Main.ts:889` (`expandedSources`), `Sv3Main.ts:1289` (`copiedTurnId`), and
   `SearchV3View.ts:1632` + `:2020`, both of which resolve a live run back to its turn via
   `turns.find((t) => t.id === local.turnId)`. An id swap mid-run therefore breaks **run
   reattachment**: `runRepresented` returns `false` and `turnState` degrades to `'settled'` for a
   turn that is still streaming. That is a live-turn correctness bug, and it raises this from a
   tidy-up to a required fix. The `sv3-sessions.test.ts:833-840` expectation ("takes the record's
   id") inverts — that test is rewritten, deliberately, because it pins the defect.
4. **`panelSpeaks` gates on evidence, not on kind** (§1.7): drop `turn.kind !== 'ask'`, keep the
   streaming and null-evidence guards. **In scope**, and minimal: `kind` keeps governing the activity
   feed, which is what it is actually about. This conforms to an existing principle in this repo
   rather than inventing one — 839 F2 fixed the same shape (a selection state was allowed to
   overwrite a grounding tier). Gating a fact on a *correlated classification* rather than on the fact
   itself is the recurring error; see §6 P3.

### 2.5 Search v3 producer gate (§1.5)

`sv3-ask.ts` reads `scorer` off the `rag.citation_matches` payload through the **same**
`isVerifiedProducer` authority the other two paths use, refuses `'verified'` provenance to a
non-admitted producer, gates the `matches` array assignment at `:352` (§1.5b), and stamps `scorer`
onto every emitted `Claim` (`claimsOf:256-266`) so `claimsToCitations`'s own gate
(`citationResolve.ts:45`) sees it too — defence in depth through one authority, not two checks. The
stale rationale at `:213-217` is corrected in the same PR.

**Reference implementation to mirror, not to reinvent:** `UnifiedChatView.ts:6116-6188` already does
exactly this on the shipped window — `readScorer(p)` → `isVerifiedProducer` (`:6119-6120`), gate the
similarity into `sim` (`:6152-6153`), gate the `verifiedRefs.add` (`:6160-6162`), stamp `scorer` on
the emitted claim (`:6182`). v3 should read as the same three moves in its own accumulator shape. A
divergence between the two handlers here is the 561 P-A divergence in a new place.

---

## 3. Slice plan

| Slice | Scope | Primary targets | Blocked by |
|---|---|---|---|
| **S0** | Segmentation probe (§7 Q1) — a JUnit case printing `splitSentences` output for a bulleted/numbered answer | `CitationMatchOps.java:475-490` (read-only probe) | — |
| **S1** | One record→evidence authority, **gating both halves** | extract `claimsFromRecord` + `matchesFromRecord` from `UnifiedChatView.ts:5368-5431` → new shared module beside `components/chat/citationResolve.ts`; gate the match array (§1.5b); delete originals | — |
| **S2** | v3 live producer gate | `sv3-ask.ts:349-364` (read `scorer`), `:352` (gate `matches`), `:256-266` (`claimsOf` stamps it), `:213-217` (sweep the stale comment); mirror `UnifiedChatView.ts:6116-6188` | — |
| **S3** | v3 rehydration + identity | `sv3-record.ts:158,101-106,150-151`; `sv3-sessions.ts:652-672` (recordId merge), `:95` (+`recordId`), `:216-225`; `Sv3Main.ts:1345-1348` (`panelSpeaks`); rewrite `sv3-sessions.test.ts:833-840` | S1 |
| **S4** | Anchoring rewrite | `MarkdownBlock.ts:728-810` (`Intl.Segmenter` + prefix match + advance rule), `:747/:760` (per-label dedupe), `:750` (char floor), `:915-917` (`stripMarkers` → link collapsing only); `citationResolve.ts:55-63` (per-ref emission) + delete dead `sourceRefs` (`MarkdownBlock.ts:48`); assert ordering vs 846's `highlightCodeBlocks` | **846 merged**; S0 (threshold, §2.1c) |
| **S5** | Backend markdown-aware segmentation | `CitationMatchOps.java:475-490`; coverage-denominator test updates | S0, S4 (so anchoring regressions are already pinned) |
| **S6** | Live validation | ui-shot step for a list-shaped grounded answer; L1–L3 (§4) | S1–S5 |

**S1–S3 are cleared for immediate implementation** and are the whole of the reload-correctness fix;
S1 and S2 are independent of each other and of S4/S5, so they can run in parallel. S0 is a
minutes-scale probe that unblocks S4's threshold and S5's premise — run it first regardless of who
takes S4.

**S4 is the highest-priority *remaining* defect, not a tail item** (§7 Q2): S3 restores marks only at
**tier-2 fidelity** (source-level, model-placed, no sentence underline, and nothing at all when the
model omitted `[n]`). Sentence-level marks on list-shaped answers — the shape the observed defect
occurred on — do not come back until S4. It is merge-order-gated behind 846; do not start it in a
worktree branched before 846 lands, but do not treat the gate as a reason to defer it.

---

## 4. Tests

Each entry names the defect it makes unconstructible. **Every unit test marked ✗ fails on `main`
today** — that is the acceptance criterion for the slice, not an afterthought.

### Unit — `MarkdownBlock.test.ts` (happy-dom; extends the existing suites at `:155-220`, `:252-296`, `:421-505`)

| # | Test | Catches |
|---|---|---|
| T1 ✗ | A citation whose `sentenceText` is `- **Foo**: bar baz qux [1].\n\n2. ` anchors a `.cite-ref` inside the rendered `<li>` | §1.1, both halves (blacklist + fusion) in one fixture |
| T2 ✗ | Shape matrix, parameterized: unordered list, ordered list, table cell, blockquote, ATX heading, nested list, **and a CJK bulleted answer** | the *class*, not the observed case. The CJK case is the HI-6 guard: it fails under any token-count floor (§2.1a) and is the evidence P1 (§6) earns its keep |
| T3 ✗ | A `Claim` with `verifiedRefs: [0, 2]` yields **two** `Citation`s from `claimsToCitations` (labels 1 and 3), and `MarkdownBlock` renders **two** marks at the boundary in ascending label order | §1.2 — **both** gates. Asserted at the resolver level *and* the render level, because the resolver half is what makes the render half reachable at all |
| T3b ✗ | An answer containing the same sentence twice, each cited, marks **each occurrence once** — never two marks on occurrence 1 | §2.1b. Without the advance rule the per-label dedupe makes this *worse* than today |
| T3c | Short-sentence floor: a 3-token sentence `"It does not."` still anchors | §2.1a — the char-floor must not regress today's `norm.length < 4` behaviour |
| T4 | An answer containing **no** literal `[n]` still anchors tier-1 marks | rejected-alternative A's failure mode; guards against a future "just use the tokens" regression |
| T5 | Tier-1 miss + literal `[1]` present → marker upgraded **and** no `.cite-sentence` span on it | H3 |
| T6 | Literal `[7]` with no gate-passed citation for label 7 stays inert plain text | regression on `:829` label filter |
| T7 | A sentence whose first 3 words repeat earlier in the answer anchors at the *right* occurrence | the min-token/longest-run rule; the wrong-reason pass this design most invites |
| T8 | The 822 §3d underline-density bound (`:421-505` fixture, unedited) still holds | that the new anchorer did not buy coverage by over-marking |
| T19 ✗ | A cited sentence inside a fenced code block keeps its `.cite-ref` and `.cite-sentence` after 846's `highlightCodeBlocks(root)` has run | the ordering hazard in §2.1 — a pass that rewrites subtree HTML after decoration silently wipes marks |

### Unit — evidence authority and v3

| # | Test | Catches |
|---|---|---|
| T9 | Shared record→evidence authority: envelope `scorer:'EMBEDDING_COSINE'` → zero citations; `'CROSS_ENCODER'` → citations; absent → citations (the legacy allowance) | the gate surviving extraction from `UnifiedChatView` |
| T9b ✗ | **Agreement test.** For one `EMBEDDING_COSINE` envelope, the same authority yields zero marks **and** zero `CitationMatch`es, so `sourceGrounding` reports `cited: false` / `tier: evidenceTier(0)` — the prose and the sources panel say the same thing. Twin assertion under `CROSS_ENCODER`: both non-empty | §1.5b — the one-screen contradiction. This is the test that makes "gate the marks only" impossible to ship |
| T10 | `StreamingCitationMatcherPayloadTest` (`:43,:53`) extended to assert `scorer` is present for **every** `ScorerKind`, including the `NONE`/`EMBEDDING_UNAVAILABLE` return | §1.4 — the producer-side test the FE's fail-open allowance rests on (P2) |
| T11 ✗ | `sv3-record`: a record turn carrying `attributes.claimMatches` + `attributes.citations` projects non-null evidence whose `marks` equal `claimsToCitations`'s output | §1.3 |
| T12 | `sv3-record`: a record turn with no citation attributes projects `evidence: null` | "never told ≠ zero" not collapsed by the fix |
| T13 ✗ | `sv3-ask`: with **non-empty `rag.citations` supplied first**, a `rag.citation_matches` payload with `scorer:'EMBEDDING_COSINE'` produces zero marks **and** zero `matches` — paired with a `CROSS_ENCODER` twin over the identical fixture that produces marks | §1.5 + §1.5b. The twin is mandatory: without sources the run returns empty at `citationResolve.ts:32` and the cosine arm would pass for the wrong reason |
| T13b ✗ | `sv3-ask`: a payload with **no `scorer` key** yields `Claim`s with `scorer` absent and marks identical to the `CROSS_ENCODER` case | pins the fail-open allowance *deliberately*, so a future regression that stops stamping `scorer` cannot silently revert v3 to §1.5's behaviour while T13 stays green |
| T14 ✗ | `applySv3Record` with local `[A,B]` and record `[B']` (length skew) does not attribute B's evidence to A | §1.6 |
| T15 ✗ | Cold load (`turns: []`) yields turns with non-null evidence from the record | §1.3 cold path, which no existing test covers |
| T16 ✗ | A merge never changes an existing turn's `id`; `recordId` is stamped instead | §1.6b (`sv3-sessions.test.ts:833-840` is rewritten here) |
| T17 ✗ | `panelSpeaks` is true for an `agent`-kind turn carrying evidence | §1.7 |
| T17b | `panelSpeaks` under a **gated** producer: a turn whose `evidence.matches` is empty because the producer was not admitted does not open a panel asserting grounding | that §2.4(4)'s loosened gate did not open a path for §1.5b's contradiction to reappear on the restored path |
| T18 ✗ | `CitationMatchOps.splitSentences` on a bulleted/numbered markdown answer emits one sentence per item, with no ordinal fused from the next | §2.2 |

### Live stack / browser — not substitutable by unit tests

- **L1** Real ask over an indexed corpus, prompted toward a list-shaped answer: interactive marks
  render on list items and click-through selects the source. Only the browser exercises the real
  `marked` → DOMPurify → text-node path that tier 1 anchors against; happy-dom is a model of it.
  Capture as a **ui-shot step** (a list-shaped grounded answer), which `check-ui-step-coverage`
  then keeps required.
- **L2** Reload a search-v3 conversation: inline marks and the sources panel come back. End-to-end
  proof for §1.3 across the real `/api/thread/{id}` round trip.
- **L3** Cosine-fallback arm — rename the citation-scorer model per 836 §9.7.3, ask in search v3,
  confirm the answer renders **markless**. This is the honesty proof for §1.5 and cannot be done at
  unit level: it exercises real producer selection in the Worker, not a hand-built payload.
  (`green-masked-destructive`: the gate must be tested in the environment it exists for.)

---

## 5. Wire-gate and governance impact

**The `wire` gate does not fire.** Its spec dir is `contracts/wire`
(`scripts/governance/gates/wire/enforcer.mjs:54-56`; no override in `governance/registry.v1.json:350-367`),
which contains only `capabilities/contract_events/health/knowledge/metrics/operation_history/runtime/status/stream.proto`.

Per-change:

| Change | Gate impact |
|---|---|
| No proto change is required by this design | `scorer`/`textSource`/`sentencesScored` already ship (`indexing.proto:526-530, 551-565`, landed #466/#473) |
| Conversation-record JSON shape (`persistedAssistant`) | **unchanged** by this design — and it is gate-invisible regardless: both `ConversationEngine.java` and `InteractionThreadController.java` work through untyped `Map<String,Object>` and import none of the `execution-surface` canonical types, so the auto-scan cannot see them. Recorded as a finding, not fixed here |
| No shape `EVENT_SCHEMA` change | no `scripts/codegen/gen-shape-handlers.mjs` regen, no `check-shape-handler-regen` |
| `modules/ui-web/src/**` edits (S1–S4) | the ui-web gate set pushes via the consult hook — authority is the `ui-web-gates` recipe in `governance/consult-register.v1.json` |
| New/changed ui-shot step (S6) | `check-ui-step-coverage` |
| `CitationMatchOps.java` (S5) | no register hit — not a search-execution surface referencer |

**Two findings for the observations inbox** (out of scope, do not fix here): `check-shape-handler-regen`
exists as an npm alias in `modules/ui-web/package.json:31` but is wired into no workflow, so shape-handler
regen is unenforced in CI; and the `claimMatches` persistence hop is a stringly-keyed boundary invisible
to the `execution-surface` scan.

---

## 6. Reach — principles this reveals

**P1 — Two representations of one text need one normalizer, applied symmetrically to both sides.**
The anchoring defect is not "the stripper missed bullets"; it is that only one side was normalized, by
a rule forked from the markdown grammar. Where else this already applies **and is already violated**:
`evidenceProjection.countSentences` (`:360-361`, a regex terminator count) versus the backend's
`BreakIterator` — two sentence-count authorities, one of which is the denominator of a user-facing
honesty claim. 836 §3.6 named this fork and it is **still open**; §2.2 moves the backend count without
closing it, so it should be closed next.
*Evidence it earns its keep:* T2's shape matrix stays green as `marked` features are enabled without
edits to the anchorer. *Retirement condition:* if one canonical answer representation is ever
established end-to-end (backend segments the same normalized artifact the FE renders), the symmetry
apparatus is redundant and should be deleted rather than maintained.

**P2 — An allowance conditioned on another module's behaviour needs a test on that module.**
`isVerifiedProducer` fails open on an absent `scorer`, justified by a prose claim about what
`StreamingCitationMatcher` emits. The justification happens to be true and pinned (T10 makes it
exhaustively so) — but a fail-open default whose safety lives in a comment about a different file is
one refactor from becoming a blanket bypass. Candidate scope: every fail-open default in the FE
honesty chain. *Retirement condition:* when no pre-836 records can survive, delete the allowance and
its producer-side test together — the allowance has an expiry, and should not outlive it silently.

**P3 — Gate a capability on the fact it is about, not on a correlated classification.**
`panelSpeaks` gates *evidence display* on `kind === 'ask'`; 839 F2 gated a *grounding tier* on a
selection state. Both hide a true fact behind a proxy that merely correlates with it. Candidate scope:
any `if (kind === X) show(evidenceOfY)` in the shell. *Evidence:* T17-shaped tests pass without
loosening any honesty assertion. *Retirement:* if a taxonomy is ever introduced where kind genuinely
*determines* evidence availability, this collapses into a tautology and should be dropped.

No generalized structure is built for any of these now; only P1's symmetric normalizer is constructed,
and only because §1.1 requires it.

---

## 7. Open questions

1. **Does `BreakIterator` really fuse across `\n\n`?** §1.1's mechanism is inferred from the observed
   `sentenceText`, not measured. Probe: a JUnit case printing `splitSentences` output for a bulleted
   answer. **Promoted to slice S0, ahead of S4** (rev 1 put it before S5 only). Two things depend on
   it, not one: whether blank-line-as-boundary is S5's fix or a no-op, **and** §2.1c's 60 % coverage
   threshold — a heavily fused key can push the matched prefix to roughly half the key's characters,
   which would put a real sentence under the floor. Re-derive the threshold from the measured worst
   fusion, and consider expressing the ratio over the **DOM-side candidate span** (how much of the
   matched region the key accounts for) rather than over the key, since it is the key that carries the
   foreign material.

2. **~~Which surface produced the live "zero marks with 0.96 matches" observation?~~ ANSWERED —
   Search v3, deep-linked / cold-loaded** (orchestrator-confirmed). Rev 1 offered §1.5 as the
   alternative explanation. **That was wrong, and the error is worth keeping visible:** §1.5 is a
   *fail-open* hole — it can only ever admit marks that should have been suppressed, never suppress
   marks that should have rendered. It cannot produce "zero marks" under any input.

   **§1.3 alone explains the symptom exactly**, and the chain is fully mechanical:
   `sv3-record.ts:158` sets `evidence: null` → `Sv3Main.ts:1128` passes
   `.citations=${[...(turn.evidence?.marks ?? [])]}` = `[]` → `MarkdownBlock.ts:278`
   (`this.citations.length > 0`) is false → `decorateCitations` never runs → and because
   `normalizeLiteralCitationTokens` is only reachable *from inside* it (`:768`, `:809`), the model's
   literal `[1]`/`[2]` are never upgraded and stay plain text. Every element of the reported
   observation follows, with no further defect required.

   **Corollary, and the reason §1.2/§1.1 are not thereby demoted:** S4's and S5's defects were
   *concurrently live in that same turn and masked by S3's* — the fused
   `**Retrieval Pipeline**: … [1][3].\n\n2. ` sentence text is from that very answer, and it carries
   both the §1.1 fusion and a `[1][3]` two-source sentence (§1.2). Fixing S3 will make that turn
   render marks; it will render them at **tier-2 fidelity only**, and the list-shaped sentences will
   still fail tier 1. Do not read "S3 fixed the repro" as "the anchoring defect was hypothetical".

3. **Is `Intl.Segmenter` available in the vitest/happy-dom environment?** Node 18+ ships full-icu, so
   expected yes; confirm in S4's first commit rather than discovering it in CI. Fallback if not: a
   `Intl.Segmenter`-when-present / grapheme-count-otherwise split would be a per-environment lever,
   which is nearly as bad as a per-language one — prefer fixing the test runtime's ICU.
4. **Should a tier-2 mark be visually distinguishable from a tier-1 mark?** They assert different
   things (source-level model-placed vs sentence-level cross-encoder-placed). This design keeps them
   identical in colour and distinguishes them only by the absence of the sentence underline (H3),
   because 839 already settled that grounding lives in content colour and adding a fourth mark
   idiom needs its own legend entry and contrast work. Flagging for the orchestrator: if the answer is
   "yes", it is presentation-authority work and inherits 839's measured-UX-audit closure requirement.
5. **`.cite-claimed`** — 839 §7 deferred it as "blocked on the literal-citation disposition". 836 has
   since shipped. If the class is still unminted, S4 is where it would be decided; this design does
   **not** mint it, so 839's deferral stands unless the orchestrator says otherwise.
