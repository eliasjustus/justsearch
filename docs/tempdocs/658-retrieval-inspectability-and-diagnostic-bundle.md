---
title: "Retrieval evidence at the agent surface: project the (already-shipped) search trace and RAG citations onto MCP — the reverse-coverage half the execution-surface register lacks"
type: tempdocs
status: "IMPLEMENTED 2026-07-07 — the MCP evidence projection (search trace + RAG citations onto structuredContent) is built, registered in the execution-surface register, and green across full build + unit + protocol tests + the execution-surface gate (verified to bite) AND live-verified end-to-end: real HTTP /mcp calls returned the structured searchTrace (with degradation reason codes), per-hit trace + legScores, and RAG citations + quality signals. See ## As-built. Scope narrowed from the 2026-06-28 stub; the named non-scope items remain handed off."
created: 2026-06-28
updated: 2026-07-07
category: retrieval / agent-surface / evidence-projection / mcp
related:
  - 553-canonical-search-execution-record     # the kernel this conforms to (SearchTrace anti-fork + discovery oracle)
  - 549-unified-search-trace                  # the shipped SearchTrace record being projected
  - 559-presentation-adjacent-authorities     # the shipped ContextCitation sibling record (559 Authority IV)
  - 575-observed-happening-register           # the sibling register that ALREADY has the reverse-coverage rule (and scopes the trace out)
  - 655-mcp-conformance-and-capability-policy # the MCP structured-output / conformance contract this lands under
  - 663-ai-engine-verdict-single-authority    # precedent: an excluded subsystem re-joined the projection kernel
  - 671-tika-ocr-skip-routing-misclassification # the skipped-file evidence SoT; the predicted 2nd instance of the principle
  - 647-engine-performance-attribution-and-budget-allocation # hands a perf panel INTO the existing explain surface
  - 623-reproducible-benchmark-release        # eval boundary (NOT forked here)
  - 624-agentic-retrieval-eval-rebuild        # eval boundary (NOT forked here)
  - docs/explanation/08-observability.md
  - docs/explanation/23-search-pipeline-overview.md
  - docs/reference/search-quality-register.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 658 - Retrieval evidence at the agent surface

> **⚠️ Framing superseded (2026-07-07).** The original Purpose/Boundary/First-questions below are
> the *stub as written on 2026-06-28* and are kept as history. Investigation found that the
> "retrieval inspector + diagnostic bundle" it asks for **already shipped** (549/553/559/577/297/671).
> The settled scope is much narrower and is defined in **`## Design (settled 2026-07-07)`** near the
> end of this doc; read that section as the current truth. The two sections between — `## Investigation
> & verdict` and `## Theorization` — record how the scope was narrowed.

## Purpose

JustSearch's retrieval stack is a differentiator only if outsiders can understand and debug what it
did. The reports converge on this point: BM25, dense vectors, SPLADE, reranking, OCR, citations, and
degraded readiness are impressive, but opaque sophistication does not build trust by itself.

This tempdoc asks a next agent to design a retrieval inspector and diagnostic bundle: score/fusion-leg
breakdowns where available, skipped-file and parser/OCR status, query-time reason codes, citation
provenance, enrichment readiness, and a bug-report package that contributors can attach without
leaking sensitive content by default.

## Boundary

This is not a new benchmark harness. `reproducible-benchmark-release` and
`agentic-retrieval-eval-rebuild` already own publishable measurement records. This note owns
operational inspectability for one local run: why this query, this result, this citation, or this
degraded state happened.

Avoid inventing a second authority for search traces or pipeline state. The design should project
from existing trace/status/reason-code surfaces where possible and name any missing source-of-truth
explicitly.

## Prior owners to read first

- `docs/explanation/23-search-pipeline-overview.md` for retrieval pipeline stages.
- `docs/explanation/08-observability.md` for telemetry and trace surfaces.
- `docs/reference/search-quality-register.md` for quality ownership and registers.
- `reproducible-benchmark-release` and `agentic-retrieval-eval-rebuild` to avoid forking eval work.
- `engine-performance-budget-latency-throughput-footprint` for performance attribution context.

## First questions

- What can be explained today from existing SearchTrace/status/reason-code data?
- Which missing fields are needed for useful inspection without creating representation drift?
- What should be visible in UI, MCP responses, REST diagnostics, and bug-report bundles?
- How should sensitive local document content be redacted from diagnostic exports?
- What is the smallest inspector that makes retrieval behavior trustworthy without becoming a
  dashboard project?

---

## Investigation & verdict (2026-07-07)

> Autonomous investigation against `main` + canonical docs + live code. Primary-source
> `file:line` evidence throughout. **Verdict up front: do NOT implement 658 as framed. Most of
> its scope shipped *before* 658 was written; the residue is two small, concrete gaps that do not
> need this tempdoc's "retrieval inspector + diagnostic bundle" project framing.**

### The framing is stale by construction

658 was written 2026-06-28 as if the inspectability infrastructure did not exist ("opaque
sophistication does not build trust… design a retrieval inspector and diagnostic bundle"). But the
three campaigns that build exactly this — **549** (unified search trace, `status: done`), **553**
(execution-surfaces register + drift gate), **559** (citation evidence sibling), **577** (audience
altitude cuts + per-hit "Why this result?"), and **297** (diagnostics-export redaction,
`Completed`) — all predate 658. The canonical docs already document these as *shipped*
(`docs/reference/ui/frontend-substrate-state.md:42`: "**G33 + G111 shipped** — explain panel +
always-on 'Why this result?' + LLM narration. None user-visible remaining.").

There is also a structural reason not to "build an inspector": the `execution-surface` gate
(`governance/execution-surfaces.v1.json`) is the single allowlist of code that may describe "what
the search pipeline did." A net-new inspector that re-models the trace would **fail the build** —
the register exists precisely to force *project, don't re-author*. So 658-as-framed is not just
redundant, it is actively guarded against.

### Area-by-area: what already exists vs. what 658 asks for

| 658 ask | Status | Primary-source evidence |
|---|---|---|
| Canonical search-execution trace (fusion legs, per-leg scores, stage timings, reason codes) | **SHIPPED** | `SearchTrace.java:31` (record: `effectiveMode`/`decisionKind`/`qpp`/`degradation`/`stages`); `LegScores` extractor `:159`; REST-emitted at `KnowledgeSearchController.java:387`; governed by `governance/execution-surfaces.v1.json` + `execution-surface` gate |
| Query-time reason codes (UI + FE wording) | **SHIPPED** | `SearchReasonCode.java:32` (25 codes); `searchTraceExplain.ts` explain panel w/ user/diagnostic altitude tiers; degradation contract `docs/reference/contracts/search-and-rag-reason-codes.md` |
| Per-result score / fusion-leg breakdown in UI | **SHIPPED** | `whyThisResult.ts:37` renders per-leg chips ("Sparse (BM25) · #2 · 3.32") from `Hit.trace`; per-query "Explain in words" LLM narration |
| Citation provenance (doc + span) | **SHIPPED** | `DocumentService.ContextCitation:221` (parentDocId/chunk/startChar/endChar/startLine/endLine/headingText); FE `CitationsPanel.ts` + `evidenceProjection.ts`; registered sibling record (`execution-surfaces.v1.json:16`) |
| Enrichment / degraded readiness reason codes | **SHIPPED** | `LifecycleReasonCode.java:17` (closed enum); FE `readinessNotice.ts:61` cause→remedy rows; `/api/debug/state`, `/api/health`, MCP `justsearch_status` coverage counts |
| Redacted bug-report / diagnostic bundle | **SHIPPED** | `POST /api/diagnostics/export` (`StatusRoutes.java:44`) → `DiagnosticsServiceImpl.java:95`; path redaction `:63,:225`; 30-day retention `:100`; UI "Export diagnostics" action `HelpSurface.ts:82`; privacy boundary CI-enforced `scripts/docs/check-privacy-claims.mjs`; tempdoc **297 `Completed`** |
| Skipped-file / parser / OCR status | **PARTIAL** | Typed `OcrSkipReason.java:5` + `IngestionOutcomeJournal.java:169` reason codes exist (tempdoc **671 IMPLEMENTED**, incl. an Inspector-UI fix for zero-content docs) — but **no user-facing "which files were skipped/failed and why" query surface**; metrics + journal only |
| Structured trace/provenance in **MCP** responses | **ABSENT (real gap)** | `McpToolSurface.callSearch:475` calls the adapter that *produces* a full `searchTrace` but **discards it** — MCP returns plain-text `title/path/score/preview` only; no per-leg scores, no reason codes, no `Hit.trace`, no `ContextCitation` spans. `callAnswer:396` returns coarse quality text, not structured citations |
| Content-*body* redaction (not just paths) in the bundle | **GAP** | Bundle redacts filesystem paths only; document content/query bodies rely on the opt-in `SensitiveQuery` log-emit wrapper. 297 item 2 (`SlowRequestDumper` output) still open |

### The genuine residue (and where it belongs)

Only two items are not already shipped, and both are small and concrete:

1. **MCP trace/citation projection** (the strongest candidate). The REST path already carries the
   full structured `searchTrace` + `ContextCitation`; the MCP handler drops them. Since the project
   positions the MCP surface as its "public center" for external agents (Claude Code, Cursor,
   Claude Desktop — the "agents" audience in 658's own title), projecting the *existing* record
   into MCP responses is a real, timely, low-risk gap. It is a *projection*, not a new authority —
   it would register as another consumer in `execution-surfaces.v1.json`, not a fork.
2. **User-facing skipped/failed-file surface** (weaker; demand-gated). The reason-code vocabulary
   and durable journal exist; only a read surface is missing. No evidence of user demand was found.

Adjacent handoffs *into* 658 exist but are owned elsewhere: **647** explicitly hands its
per-component performance breakdown to 658 as "one panel in 658's inspector" (`647:782-788`), and
**671** fixed `OcrSkipReason` as the source-of-truth "a future 658 inspector should project from."
These reinforce that 658 is a *named landing zone*, not that a new inspector must be built — the
panels project into the surface that already ships.

### Cheapest evidence that would validate/invalidate the need

The premise ("outsiders can't understand/debug retrieval → no trust") is testable in ~30 minutes
against the live stack, **before any code**: drive the existing explain panel + "Why this result?"
+ diagnostics bundle + MCP output and ask whether an outsider/agent can answer "why this query,
this result, this citation, this degraded state." The shipped-surface evidence above strongly
predicts the answer is "yes for REST/UI, no for MCP" — which *is* the verdict below. That
walkthrough is the falsifier; it does not require building anything.

### What this displaces / duplicates

549 (searchTrace), 553 (execution-surfaces register), 559 (citations), 577 (altitude cuts /
whyThisResult), 297 (diagnostics redaction bundle), 671 (OcrSkipReason SoT). It is downstream of
647 (perf-panel handoff). No newer tempdoc (659–690) supersedes 658, and none claims to; 671
confirms (2026-07-02) that 658 "is still an open stub with no design."

### Recommendation

- **Do not open the grand "retrieval inspector + diagnostic bundle" project.** It is ~80% shipped
  and the remaining 20% does not share a reason-to-change that would justify one tempdoc.
- **Retire this tempdoc to a thin closure note** that (a) records the inspector + bundle already
  shipped across 549/553/559/577/297/671, and (b) points the two residual gaps at scoped homes:
  - MCP trace/citation projection → the single concrete, public-release-relevant item worth doing
    now; file as its own small work item (or fold into the MCP/runtime-contract track), validated
    by the live walkthrough first.
  - Skipped-file read surface + content-body bundle redaction → observations inbox until there is
    demand / a privacy-hardening pass; the latter is a 297 follow-up, not new retrieval work.
- **Keep 658's name as the handoff anchor** for 647's perf panel only if the team wants a single
  documented "inspector surface" index; that is a doc pointer, not an implementation.

**Bottom line: this should not be done as written. The one piece with a live rationale (MCP
projection) should be validated by a 30-minute walkthrough and, if confirmed, pursued as a small
standalone projection item — not under this tempdoc's dashboard framing, which the tempdoc itself
warned against.**

---

## Theorization (2026-07-07, exploratory — not a design commitment)

> The verdict above says "don't build the grand inspector." This section is the complement: given
> that the *artifacts* mostly exist, what is the residual problem really about, how else could it be
> framed, and what ideas are worth carrying forward even if they are not the final answer? Nothing
> here is decided.

### Reframe 1 — the missing thing is coverage/packaging, not data

The tempdoc assumes the deficit is *explanatory data* ("opaque sophistication"). The investigation
says otherwise: the canonical `SearchTrace`, per-leg scores, reason codes, citation provenance, and
readiness codes all exist and are governed against forking. So the residual problem, if real, is one
of two different classes:

- **Coverage (audience parity):** the same query crosses REST and MCP, but the trace rides only the
  REST boundary — MCP carries the *subject* (the query) and drops the *evidence* (the trace). The
  problem is uneven projection, not absent data.
- **Packaging (unit of inspection):** logs, trace, citations, and index fingerprint each exist but
  live in different surfaces; nothing binds them into one replayable unit for a single decision.

Both are smaller and better-shaped than "build an inspector," and they suggest different work than
the tempdoc implies.

### Reframe 2 — "explainable" and "trustworthy" are not the same lever

658 treats explanation as the trust lever. Worth questioning: for a **local-first** tool, the
stronger trust lever may be *privacy* (evidence never leaves the machine unless the user exports it)
rather than *explanation*. If so, the already-shipped redaction boundary and the diagnostics bundle
are the load-bearing trust artifacts, and "make the ranking legible" is a usability feature, not a
trust feature. This matters because it changes what "done" means: a trust goal is satisfied by the
privacy boundary + a reproducible export; a usability goal is satisfied by the explain panel. They
have different acceptance tests and different audiences.

### Candidate solution directions (with tradeoffs)

**A. Projection-parity as a coverage rule (not a feature).** Rather than a new surface, name the
invariant "a surface that carries a *subject* (a query, a file, an answer) must be able to reach that
subject's *evidence* at its own altitude," and let it flag `context-without-evidence` gaps. The MCP
trace gap becomes a coverage finding, not a design project. *Tradeoff:* risks over-governance
(declaring every surface × every record is bureaucracy, and violates YAGNI). Mitigation: only applies
to surfaces that *already* carry the subject — never invent a surface to satisfy the rule.

**B. The bundle as a reproducible "query capsule."** Reframe the diagnostic export from "attach logs
to a bug report" to "a self-contained, redacted capsule that reproduces one retrieval decision":
query (hashed or redacted), the trace, top-K with per-leg scores, citations, degradation state, index
fingerprint + git sha. An outsider, an agent, or CI could replay it and check whether the *shape* of
the decision reproduces even with content redacted. This matches the tempdoc's own boundary
("operational inspectability for **one local run**") and gives the bundle a unit. *Tradeoff:* real
risk of drifting into a mini-benchmark harness — the exact territory `reproducible-benchmark-release`
and `agentic-retrieval-eval-rebuild` own. The line to hold: a capsule is a single replayable run keyed
by fingerprint, not a dataset or a metric. Privacy risk: a capsule that replays a real query embeds
sensitive content, so structural-replay-under-redaction (does the trace shape match?) must be the
default, with raw content strictly opt-in.

**C. Agent-first rather than human-first.** The explain panel solves the human case. The unsolved
case is the machine consumer: an external agent driving the MCP surface cannot currently see which
legs ran, which hit came from where, or whether the index was degraded — so it cannot decide whether
to trust or re-query. Designing the trace projection for the *agent* consumer first (structured,
machine-readable, minimal) is a genuinely different shape than a human disclosure, and it is where the
coverage gap actually is. *Tradeoff:* building for a consumer that may not exist yet is the
"substrate-without-consumer" trap — so this must be validated against a real agent loop before any
field is added, which is also the cheapest possible evidence.

**D. Explanation on-demand, not always-on.** The full numeric trace is expensive and mostly
diagnostic-tier. The system already does altitude cuts (a user summary line + a collapsed diagnostic
disclosure) and already gates numeric detail behind `include_detail`. Extending that principle —
"cheap summary always; expensive detail only when pulled" — keeps MCP responses lean and avoids
bloating every call with a full trace. *Tradeoff:* two response shapes (summary vs detail) can drift;
already mitigated here because both project from one record.

**E. Close it.** Still credible and possibly strongest: the artifacts exist, the residue is two
tickets, and a live walkthrough validates. Theorizing should not manufacture a project the evidence
does not demand.

### Hidden assumptions worth surfacing

- *"The three audiences (users, agents, contributors) want the same inspector."* They do not: users
  want confidence plus a way to disagree; agents want machine-readable signals to re-plan;
  contributors want a reproducible repro. One surface serving all three is precisely the
  dashboard-project trap. The projection-at-altitude model dissolves this — one record, three
  projections — but only if we resist a shared UI.
- *"Inspectability is a retrieval concern."* The same shape recurs for indexing (skipped/failed
  files), inference (engine readiness/verdict), and boot phases. If a principle exists, it is
  system-wide, not retrieval-local — which argues against solving it as a retrieval feature.
- *"More explanation ⇒ more trust."* Past a point, more surfaced internals can *reduce* trust
  (looks fragile / overwhelming). The altitude discipline already anticipates this; a bigger
  inspector could regress it.

### Possible broader principle / recurring shape

Stated as a candidate invariant, not a decision:

> **Evidence should travel with its subject across every boundary the subject crosses.** When a
> surface renders *what* happened (a query, a file, an answer) but not *why*, that is a
> `context-without-evidence` gap — the dual of the "no-fork" rule the execution-surfaces register
> already enforces. The register guards *don't re-author the evidence*; the missing half is *don't
> drop the evidence where the subject still travels*.

This is the projection-**coverage** axis of the same projection spine that `observed-happening.v1.json`
already instantiates on the authority axis, and that `08-observability.md` notes the search-trace
family currently keeps "on its own spines." Whether to fold the trace/citation family into that
family-wide coverage guarantee, or keep it standalone, is an open architectural question — and per
AHA ("only unify what shares a reason to change"), the unification is only justified if trace,
citation, skip-reason, and engine-verdict genuinely share the reason "a subject crossed a surface and
its evidence must ride along." They plausibly do — but that should be demonstrated on two concrete
instances (e.g. MCP-trace and skipped-file) before any coverage gate is proposed, not asserted.

### What to resolve before a design is settled

1. **Unit of inspection:** response-field vs pull-endpoint/tool vs replayable capsule — three
   different commitments (D vs A vs B). Pick the unit before the fields.
2. **Primary audience:** agent-first vs human-first changes the data shape; human is already solved,
   so the honest default is agent-first — but only after (3).
3. **Consumer evidence:** is there a real agent/user loop that would consume a structured trace it
   cannot get today? The cheapest experiment (drive the live MCP surface from an agent and see what
   it lacks) settles both the need *and* the shape. Run it before adding a field.
4. **Scope of the principle:** retrieval-local fix vs a declared coverage invariant. Demonstrate on
   two instances before generalizing; do not open a governance project on one gap.

---

## Design (settled 2026-07-07)

> This section is the current truth for this tempdoc; it supersedes the 2026-06-28 stub framing at
> the top. It is kept general (structure, not code). Nothing here is implemented yet.

### The problem, stated exactly

The retrieval-evidence records **already exist and are governed** — `SearchTrace` (ranking "why")
and `DocumentService.ContextCitation` (RAG-answer citations), both registered in
`governance/execution-surfaces.v1.json` and projected to REST, the FE explain panel / "Why this
result?" chips, OTel spans, and eval. **One audience is missing: the agent (MCP) surface.** Both
MCP tools carry the *subject* but drop the *evidence*:

- `McpToolSurface.callSearch` obtains a `KnowledgeSearchResponse` that carries `searchTrace` +
  per-hit `Hit.trace`, then formats plain text (`title`, `score`, preview) and discards the trace.
- `McpToolSurface.callAnswer` obtains a `ContextResult` whose `citations` (`List<ContextCitation>`
  with per-span provenance) it never reads; it emits the assembled context string plus an ad-hoc
  `--- Quality ---` text rendering of scalar fields.

Neither method references a canonical evidence type, so MCP is invisible to *both* the anti-fork
scan and any coverage check — it sits precisely in the seam between the two registers (see
"Design reach"). The external agents that drive MCP — the audience named in this tempdoc's own
title — cannot see which legs ran, where a hit came from, whether the index was degraded, or which
document/span a cited passage came from, and so cannot decide whether to trust or re-query.

The diagnostic-bundle, redaction, inspector-UI, reason-code, and perf-panel pieces the 2026-06-28
stub also asked for are **out of scope** (shipped or owned elsewhere) — see "Explicit non-scope".

### The design: re-join the projection kernel; do not invent

The correct shape is the one **tempdoc 663 just applied** to the AI-engine subsystem — a subsystem
"doing by hand what every other surface gets for free" was fixed by *re-joining the existing
anti-drift kernel*, not by building a parallel. The same move here:

1. **Project, don't re-author.** Add a single deriver that projects the *existing* `SearchTrace`
   (for search) and `ContextCitation` (for answer) into a structured MCP payload. It reads the
   canonical records; it computes no new "why". This is a projection surface in the sense
   `execution-surfaces.v1.json` already defines (the OTel span projections are the precedent for
   adding a new projection consumer of the same record).

2. **Carry it on the channel MCP already has.** MCP results already support a dual channel:
   a human-readable `content` text block **and** a machine-readable `structuredContent` object
   (the `runtime_manifest` tool is the in-repo precedent). The evidence rides in `structuredContent`;
   the existing text block stays for text-only clients. No new response mechanism is invented.

3. **Land under the MCP contract layer (tempdoc 655), not a parallel one.** 655 owns MCP conformance
   and is the doc that frames MCP as "an external contract agent clients can trust" — which is why the
   *agent* projection (not the already-solved REST/UI one) is the piece worth doing. **Correction from
   the confidence pass (see appendix):** 655 shipped *input*-argument schema validation only — it
   shipped **no `outputSchema` mechanism and no output validator**, and `structuredContent` is
   free-form today (only `runtime_manifest` uses it). So the payload emits free-form
   `structuredContent` following the `runtime_manifest` precedent; declaring an `outputSchema` for it
   is *optional net-new machinery that belongs to 655*, not a prerequisite for this work.

4. **Adopt REST's altitude law verbatim — do not author a new one.** REST makes the *structural*
   trace always-on and gates only the *numeric detail tier* behind a `debug` request flag. MCP
   mirrors this: the structural evidence (stage list + status/reason codes; per-hit leg
   participation; degradation; citation provenance list) is always present in `structuredContent`;
   the heavy per-hit numeric leg scores are gated behind an opt-in `detail` argument. This keeps the
   default agent response light (a real footprint concern — cf. the connection-budget pressure
   tempdoc 662 documents) while making the full picture reachable on request. The `detail` lever is
   the *existing* `KnowledgeSearchRequest.debug` field (→ `include_detail`), which the adapter path
   already honors — so MCP wires an opt-in `detail` tool argument to that flag; no new request
   plumbing. (Confidence-pass nuance: the input validator does *not* reject undeclared arguments
   today — schemas omit `additionalProperties:false` — but the `detail` property should still be
   declared on the tool schema for contract clarity.)

5. **Respect the external trust boundary — inherit the surface's existing data posture, don't invent
   one.** An MCP response leaves the process to a client, so be explicit about what it carries.
   **Correction from the review (2026-07-07):** MCP tool responses are *not* redacted — path redaction
   applies only to the diagnostics *export* bundle, not to live tool output. The evidence therefore
   carries the same identifiers the surface already exposes: `citations[].parentDocId` is the
   document's absolute file path (its identity, needed for the agent to cite/open the source — the same
   value `justsearch_search` already returns as `path` and the desktop UI already shows), and `excerpt`
   is passage text already returned in the assembled context. This is *not* newly-reachable data at the
   product level, but it *is* more than the `justsearch_answer` tool's own text output previously
   carried — so the earlier "no new leakage / follows existing redaction defaults" framing was
   inaccurate and has been fixed here and in `docs/reference/mcp-production-server.md`. Owner decision
   (2026-07-07): keep `parentDocId` as the path (identity + cite/open, consistent with search); the fix
   is doc-accuracy, not redaction. The one true invariant that makes this safe: the `/mcp` endpoint is
   **loopback-only** (`127.0.0.1`, Hard Invariant #2) — nothing leaves the machine.

6. **Register the new surface (this is a required, not optional, step).** The moment the deriver
   imports `SearchTrace` / `ContextCitation`, the `execution-surface` gate will (correctly) fail
   until `McpToolSurface` (or the dedicated deriver) is added to `execution-surfaces.v1.json` as a
   `projection` surface with a guard test. That registration *is* the anti-fork guarantee and the
   discovery-oracle update; it is part of this tempdoc's work, not a follow-up.

### What this orphans (deletion belongs to THIS tempdoc)

Honest and small — the MCP methods only *read* canonical records today, so there is **no forked
authority to delete**, only redundant renderings and one stale framing:

- **The ad-hoc `--- Quality ---` text block in `callAnswer`** (the hand-built `Sources found` /
  `Coverage` / `Retrieval mode` / truncation-note lines). Once `callAnswer` emits a
  `structuredContent` projection of `ContextResult`, this second, hand-maintained rendering of the
  same canonical scalars is redundant. Resolution owned here: **derive the human summary line from
  the one projection** (single derivation) rather than leaving two hand-kept renderings — or delete
  it if the structured channel suffices for the tool's clients. Not a later cleanup sweep.
- **This tempdoc's own original framing** ("build a retrieval inspector + diagnostic bundle") is
  superseded by the narrowed scope. The rewrite tombstones it in place: the superseded-framing note
  at the top is the tombstone, so a public reader is not misled by the stub.

Nothing else is orphaned. In particular the REST/UI/OTel/eval projections, the diagnostics bundle,
and the reason-code contract are untouched — the design only *adds* a missing consumer.

### Explicit non-scope (owned or shipped elsewhere — named, not silently dropped)

- **Skipped-file / parser / OCR read surface** — a *different* evidence family (the ingestion
  outcome journal / `OcrSkipReason`), whose source-of-truth tempdoc 671 already fixed and whose
  register home is the observability family, not the search-trace register. It is the *second
  instance* of the same principle (below), handed off — not built here.
- **Content-*body* redaction of the diagnostics bundle** — a privacy-hardening item on the 297
  track, not retrieval-evidence coverage.
- **Per-component performance panel** — tempdoc 647 already materialized the record and hands a
  panel *into* the existing FE explain surface; it projects there, not here.
- **The diagnostics bundle, inspector UI, and reason-code taxonomy themselves** — shipped
  (297 / 549 / 553 / 559 / 577 / 671).

### Precondition (cheapest validation before building)

655 already asserts the strategic need (a trustable external agent contract), which is most of the
justification. The remaining cheap confirmation is a single live check: drive the MCP surface from a
real agent loop and confirm which structured fields it actually consumes to decide trust/re-query.
That walkthrough both validates the need and pins the exact field set — run it before finalizing the
output schema, so the schema is shaped by an observed consumer rather than a guessed one
(guards against the "substrate-without-consumer" failure mode).

---

## Design reach — principle, candidate scope, retirement condition

### Is this an instance of an existing seam? Yes — conform to it.

This design does not introduce a new pattern; it applies the repo's existing **projection kernel**:
one canonical record → many governed projections → a gate that forbids re-authoring. The
`execution-surfaces` register (553) is that kernel for retrieval evidence, and tempdoc **663** is a
live precedent for the exact move made here — a subsystem that had been *excluded* from the kernel
and was hand-rolling per-surface state was fixed by re-joining it. The MCP evidence projection is the
same: re-join the kernel, stop dropping the evidence. Conform; do not build a parallel.

### The principle this reveals (named, not built)

> **Reverse-coverage for evidence: a canonical evidence record must reach every audience-surface
> that already carries its subject. A surface that renders *what* happened (a query, an answer, a
> file, a job) but not the governed *why/evidence* for it is a `context-without-evidence` gap.**

This is the dual of the rule the execution-surface register enforces today. That register enforces
the *forward* direction — *don't re-author* (any code referencing the canonical type must register
as a projection). It does **not** enforce the *reverse* direction — *don't drop* (any surface
carrying the subject must project the evidence). MCP fell exactly into that blind spot: it carries
the subject and references no canonical type, so it is invisible to the forward scan and to any
coverage check.

Notably, the **sibling register already has this reverse rule**: `observed-happening.v1.json` (575)
enforces `stream-uncovered` ("every declared stream must be a contributor of some concept or
declared out-of-family") and `live-concept-unprojected` ("a live concept no surface shows is a build
failure"). And that same register **explicitly scopes the 553 search-execution trace out** of its
enforcement. So the principle is not new to the system — it is *already load-bearing on one side of a
boundary and absent on the other*, and this tempdoc's gap lives precisely in that seam.

### Where else it would apply, and where code already violates it

- **MCP `callSearch` (trace) and `callAnswer` (citations)** — the violations this tempdoc fixes.
- **The ingestion-outcome / skipped-file evidence** — plausibly violated: source-of-truth exists
  (671) but no user-facing read projection was found. This is the predicted *second* instance, in
  the observability register's world.
- **Any future headless / CLI product surface** (e.g. the headless-eval product contract, 676) that
  returns results — would need to carry the evidence too, or it re-opens the same gap.
- **Engine-state (663)** was an instance of the broader "excluded subsystem hand-rolls what the
  kernel gives free" shape and has now been conformed — evidence the principle pays off when applied.

### Deliberately NOT building the generalized structure now

One confirmed external subject-carrier gap (MCP) does **not** justify a general coverage gate across
the register. The scope-matched action is: fix the instance (project + register MCP) and *record*
the candidate structural extension — a per-record `subjectCarriers` list in
`execution-surfaces.v1.json` plus a small reverse-coverage check mirroring 575's `stream-uncovered` —
for when the evidence warrants it. Recognizing the principle and building its apparatus are separated
on purpose.

### What would show the principle earning its keep, and when to retire it

- **Earns a gate when:** a *second* surface that carries a query/answer subject ships and would have
  silently dropped the evidence, and a declared `subjectCarriers` reverse-coverage check catches it
  pre-merge — i.e., the gate prevents a real repeat, not a hypothetical one. (A measurable
  agent-behaviour signal — an agent given structured evidence issuing fewer blind re-queries or
  fewer unsupported citations than one given text-only — would independently validate the projection
  itself; that is a hypothesis to test, not a claim to publish.)
- **Retire it when:** after a bounded window (≈ two release cycles) MCP remains the only external
  subject-carrier *and* no agent consumer reads the structured fields. Then keep the single MCP
  projection, drop the coverage apparatus, and strike this principle — it did not earn a gate. A
  principle carried without a retirement test tends to become self-justifying apparatus; this is its
  off-switch.

---

## Pre-implementation confidence pass (2026-07-07)

> A read-only investigation + one live dev-stack observation, run before implementation to retire
> the "field exists ≠ field populated on this path" risk (`unreachable-seed-green`). No feature code
> was written. Evidence is `file:line` against this worktree's tree.

### Data availability — the decisive risk — verified PASS

The projection is **purely additive on objects MCP already receives**:

- **Per-hit `Hit.trace` + query-level `searchTrace` are populated by `adapter.search()`**, below the
  layer MCP calls: `KnowledgeSearchEngine.search()` sets them (`KnowledgeSearchEngine.java:1111-1124`;
  per-hit stages at `SearchResultMapper.java:144-145`). The REST controller adds nothing —
  it serializes the same object (`KnowledgeSearchController.java:346,361,387`). MCP `callSearch`
  already holds this fully-populated `KnowledgeSearchResponse`; it just ignores the fields.
- **RAG `ContextResult.citations` is populated synchronously** on the exact non-streaming
  `retrieveContext` path MCP `callAnswer` uses (`RemoteDocumentService.java:391-394`, mapper
  `:422-439`) — non-empty when the worker chunked (`usedChunks`), empty on full-doc fallback. MCP
  already passes `returnFullDocuments=false`, which keeps the citation-producing path. The streaming
  enrichers only *consume* this list; they do not produce it. Citations do **not** require the LLM.
- **The numeric-detail lever already exists**: the `KnowledgeSearchRequest.debug` field →
  `include_detail` (`KnowledgeSearchEngine.java:653-655`), honored by the adapter path. MCP passes
  `null` today; an opt-in `detail` tool arg maps to it. No new request plumbing.

### Assumptions the pass corrected (design text above updated accordingly)

1. **655 has no output-schema regime.** 655 shipped *input*-arg validation only — no `outputSchema`
   mechanism, no output validator; `structuredContent` is free-form (only `runtime_manifest` emits it,
   `McpToolSurface.java:370`). ⇒ emit free-form `structuredContent` per that precedent; an
   `outputSchema` is optional net-new work owned by 655. **Lowers effort.**
2. **The `execution-surface` gate only detects by import literal.** It flags a Java-main file solely if
   it contains an `import io.justsearch.app.api.knowledge.SearchTrace;` (or `…ContextCitation`) line
   (`enforcer.mjs` Check 1) — an accessor read without that import slips through (the register's own
   §5 honest limit). ⇒ the projection should reference the canonical type *by import* so detection
   fires, and be registered deliberately; don't treat the gate as the sole forcing function.
3. **A `projection` surface's guard test must be conformance/projection-named** (regex
   `/(conformance|projection|searchtrace)/i`, `enforcer.mjs` Check 6) and must resolve to a real file.
   ⇒ name the guard e.g. `McpEvidenceProjectionTest`.

### Live observation (dev stack, port 61604, then stopped)

- Stack started from existing dist; a real corpus (SSOT help docs, 396 docs / 4293 chunks, SPLADE
  100%) was already indexed; search executed and returned scored hits (`data` → 2 hits, `troubleshooting.md`
  score ≈ 0.99). Pipeline is live and functional.
- The index was genuinely in the **DEGRADED** `index.embedding_legacy` state
  (`LEGACY_INDEX_NO_FINGERPRINT`, hybrid→text fallback) — so the degradation reason-code path the
  trace carries is exercised in reality, not hypothetically.
- **Tooling limit (minor finding):** the raw `searchTrace`/`citations` JSON is not exposable through
  the allowlisted dev tools (`search_query` strips the trace; `/api/knowledge/search` and
  `retrieveContext` are not allowlisted for raw `api_call`). This does not weaken the population
  evidence — that is established statically and, independently, by the shipped+tested FE projections
  that already render both records (`searchTraceExplain.ts`, `CitationsPanel.ts` / `evidenceProjection.ts`).

### Residual (non-blocking) unknowns

- **Payload shape / altitude** — *what exactly* an agent needs in the structured evidence is a
  design-taste call best settled by the precondition walkthrough (drive MCP from a real agent loop);
  the fields are known, their selection is not yet fixed.
- **Guard/conformance test is greenfield** — `McpToolSurface` has no dedicated test today; coverage is
  via the mocked-Context `McpProtocolHandlerTest`. The harness pattern exists; the projection test is new.
- Possible `McpContractVersions.TOOL_SURFACE_VERSION` bump for contract hygiene when the structured
  field is added.

### Confidence rating: **8 / 10**

High: the one risk that could have collapsed the design — evidence not being present on the MCP code
paths — is retired by code trace *and* a live run; the change is additive, low-blast-radius, with a
defined registration/test path and no cross-process or schema-migration work. Not higher because
(a) the agent-facing payload shape still wants the real-consumer walkthrough to fix it, (b) the
guard/conformance test is greenfield, and (c) "what an agent actually needs" carries irreducible
product-taste uncertainty until a consumer exercises it.

### Difficulty & recommended model/effort

**Difficulty: low-to-moderate.** Mechanically additive (two pure projection derivers; a
`structuredContent` attach in two methods; one opt-in `detail` arg wired to an existing flag; one
register entry + one conformance-named guard test; assertions in the existing MCP test). No
algorithmic or concurrency complexity. The judgment lives in two spots: the agent-facing payload
selection/altitude, and getting the register-guard conformance exactly right (naming + import so the
gate bites).

**Recommendation (per CLAUDE.md delegation economics):**
- **Design-bearing steps** — final payload/altitude selection and the real-agent validation loop —
  keep on the orchestrating tier: **Opus, medium effort.**
- **Mechanical build** — derivers, wiring, schema arg, register entry, guard + protocol-test
  assertions — delegate to **Sonnet, medium effort**, with a self-contained brief (acceptance
  criteria + the `file:line` seams above + the three corrected assumptions).
- If run by a single model end-to-end: **Opus, medium effort** (the register-conformance subtlety and
  payload taste make a pure-Sonnet solo pass likely to under-design the payload or mis-register the
  guard). **Fable is not warranted** — no reasoning-heavy or novel-algorithm content here.

---

## As-built (2026-07-07)

Implements the settled design: the retrieval-evidence records are now projected onto the agent-facing
MCP surface. Purely additive — REST/FE/worker retrieval behaviour is unchanged; the existing text
`content` blocks are retained and `structuredContent` rides alongside.

### What shipped

- **`McpEvidenceProjection`** (`modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpEvidenceProjection.java`)
  — a pure, mapper-agnostic deriver. `searchEvidence(KnowledgeSearchResponse)` projects the query-level
  `SearchTrace` (mode/decision/qpp/degradation + per-stage status/reason/timing) and per-hit ranking
  provenance (`trace` + fused `legScores` via the shared `SearchTrace.legScores` helper);
  `answerEvidence(ContextResult)` projects the `ContextCitation` list + quality/degradation signals. It
  builds explicit `Map`/`List` structures via record accessors (`StageId.wireId()` etc.), so the wire
  shape is independent of the MCP serializer's Jackson-annotation processing (Jackson-3 handler vs. the
  records' `com.fasterxml` annotations — a real trap avoided).
- **`McpToolSurface`** — `callSearch`/`callAnswer` now attach `structuredContent`; `callSearch` gained an
  opt-in `detail` arg wired to the existing `KnowledgeSearchRequest.debug` (→ `include_detail`) so the
  numeric per-hit tier is on request. Structural evidence is always-on (the numeric tier is
  altitude-gated by construction — it is only populated upstream when `debug=true`).
- **Orphan teardown (rode along):** the hand-built `--- Quality ---` multi-line text block in
  `callAnswer` was the one redundant rendering the design named; it is replaced by a single human
  summary line derived from the same projected `quality` object (one derivation).
- **Register:** added the `mcp-evidence-projection` `projection` surface to
  `governance/execution-surfaces.v1.json`, guarded by `McpEvidenceProjectionTest`. While here, removed a
  pre-existing stale orphan row (`fe-generated-pb` → a `knowledge_pb.d.ts` that tempdoc 683 had deleted)
  that was failing the gate on `main` — logged to the observations inbox.
- **Contract hygiene:** `McpContractVersions.TOOL_SURFACE_VERSION` `0.1.0`→`0.2.0` (the tool output shape
  grew, additively).
- **Docs:** `docs/reference/mcp-production-server.md` documents the structured evidence + `detail` arg.

### Verification

- Full `./gradlew build -x test` green (compile + PMD + Spotless + ArchUnit). Full `:modules:ui:test` +
  `:modules:app-api:test` green.
- **`McpEvidenceProjectionTest`** — exhaustive projection conformance: enum values project by stable
  `wireId`/`wireValue` (not the Java enum name), every record field surfaces, the numeric detail tier
  appears only when present, and the full-doc fallback yields an empty (not null) citation list.
- **`McpProtocolHandlerTest`** — extended with an end-to-end happy path that drives the REAL
  `McpProtocolHandler` + `McpToolSurface` over JSON-RPC (only the data adapter mocked), asserting
  `structuredContent.searchTrace` + per-hit `trace` survive real serialization; plus a `tools/list`
  assertion that the `detail` arg is published.
- **`execution-surface` gate**: passes, and verified to *bite* — with the surface entry's path mismatched,
  the gate fires `undeclared-surface` on `McpEvidenceProjection.java` (it references the canonical
  `SearchTrace` but is unregistered); restored → pass.
- **Live end-to-end (real HTTP `/mcp`, dev stack, 5-doc corpus):** `initialize` returned
  `serverInfo.version 0.2.0` (confirms the new code). `tools/call justsearch_search` returned
  `structuredContent` with the query-level `searchTrace` — `effectiveMode: TEXT`, degradation
  `hybridFallback` with reason `LEGACY_INDEX_NO_FINGERPRINT`, the full stage list by stable `wireId`
  (`sparse-retrieval:executed`, `dense-retrieval:skipped(LEGACY_INDEX_NO_FINGERPRINT)`, …) — plus per-hit
  `trace` (`sparse-retrieval` rank 1, `cross-encoder`) and `legScores` (`sparse 3.54 / fused 3.54`).
  `tools/call justsearch_answer` returned `structuredContent` with 3 `citations` (full provenance:
  parentDocId, char span, startLine, `headingText: "Built-in agent"`, score, excerpt) and `quality`
  (`retrievalMode: BM25`, `retrievalModeReason: EMBEDDING_UNAVAILABLE`, coverage). The human `content`
  text block is retained alongside in both. This is the design working for its intended consumer: an
  external agent can now see *why* it got these results and *where* a cited passage came from.

### Post-review fixes (2026-07-07)

An independent refute-first review reproduced all verification claims and found two Medium defects,
both fixed:

- **Completeness:** `answerEvidence.quality` had silently dropped 3 of the 5 `QualitySignals` fields
  (`scoreGap`, `chunksConsidered`, `chunksIncluded`) — now projected, so the full CRAG-style confidence
  set reaches the agent. The `McpEvidenceProjectionTest` conformance guard was strengthened to assert
  every quality field plus the previously-unasserted `qpp`/`degradation` reason fields, so a future
  silent drop fails the test (this is the gap that let the omission slip).
- **Doc accuracy (privacy):** the earlier "no new leakage / follows existing redaction defaults"
  wording was inaccurate — MCP tool output is not redacted, and `citations[].parentDocId` carries the
  document's absolute path. Corrected in design "point 5" above and in
  `docs/reference/mcp-production-server.md` to state the data posture plainly (paths + excerpts are
  surfaced to the local agent, same identity `justsearch_search`/the UI already expose, loopback-only).
  Owner decision: keep `parentDocId` as the path (identity + cite/open); the fix is documentation, not
  redaction.

### Reproducible verification (each claim → pointer)

Anyone can re-run these against `main` + this branch:

- **Compiles / lints:** `./gradlew build -x test` → BUILD SUCCESSFUL (compile + PMD + Spotless + ArchUnit).
- **Projection conformance:** `./gradlew :modules:ui:test --tests "*McpEvidenceProjectionTest*"` — 4/4
  green; asserts enum wire-ids, every `SearchTrace`/`ContextCitation`/`QualitySignals` field, detail-tier
  gating, and the empty-fallback citation list.
- **Protocol round-trip:** `McpProtocolHandlerTest` — 14/14 green, incl.
  `toolsCall_search_attachesStructuredEvidence` (drives the real handler+surface over JSON-RPC through
  the real serializer) and the `tools/list` assertion that `detail` is published.
- **Register guard:** `node scripts/governance/run.mjs --gate execution-surface --mode gate` → pass;
  verified to *bite* (mismatch the `mcp-evidence-projection` surface path → `undeclared-surface` fires
  on `McpEvidenceProjection.java`).
- **Live HTTP (manual, needs a running dev stack):** `POST /mcp initialize` → `serverInfo.version 0.2.0`;
  `tools/call justsearch_search {query,detail:true}` → `structuredContent.searchTrace` with degradation
  reason codes + per-hit `trace`/`legScores`; `tools/call justsearch_answer` → `structuredContent`
  with `citations` (full span/heading provenance) and a 10-key `quality` object incl. `scoreGap`,
  `chunksConsidered`, `chunksIncluded`. (Verified 2026-07-07 against a real degraded-index corpus.)

## Deferred checks, unverified assumptions & follow-ups

Handed forward so nothing is silently lost:

1. **Durable evidence bundle not produced.** `capture_evidence` hit a Windows libuv fail-fast
   (`UV_HANDLE_CLOSING` in `src/win/async.c`) — a known MCP-capture-path blocker; it captured
   `api-status`/`api-health` before aborting. Live `/mcp` verification was done by manual HTTP calls
   instead. Follow-up: produce a durable bundle once the capture path is fixed.
2. **`detail=true` numeric per-hit tier — live-unconfirmed.** Unit-tested (a `HitStage` carrying a
   `detail` map projects it), but not *live*-observed with a non-empty map: the verification corpus was
   a degraded, single-leg TEXT index that never produced fusion numeric detail. Confirm on a healthy
   hybrid index with `detail:true`.
3. **MCP conformance suite not re-run.** `scripts/ci/check-mcp-conformance.mjs` (manual, not in public
   CI) was not run against the new `structuredContent`. Assumption: `structuredContent` **without** an
   `outputSchema` is accepted — consistent with the pre-existing `justsearch_runtime_manifest` tool,
   which already does exactly this. Worth one confirming run.
4. **No `outputSchema` for the evidence — deliberate.** MCP output-schema machinery does not exist in
   this repo yet; declaring one belongs to tempdoc **655** (MCP conformance). The evidence rides as
   free-form `structuredContent` per the `runtime_manifest` precedent. If 655 adds `outputSchema`
   support, the search/answer evidence shapes should get schemas then.
5. **Conformance test is field-complete but not *mechanically* exhaustive.** `McpEvidenceProjectionTest`
   asserts every field that exists today, but there is no reflective field-count guard (unlike the FE
   `assertFieldRoles` pattern). A new field added to `SearchTrace`/`ContextCitation`/`QualitySignals`
   would be silently dropped until the projection + test are updated. Optional hardening: a reflective
   totality check.
6. **Stale register row fixed in passing (not 658's own work).** The execution-surface register carried
   a dead `fe-generated-pb` → `knowledge_pb.d.ts` row (the file was deleted by tempdoc **683**; absent
   on `main`), which had the `execution-surface` gate red on `main`. Removed here to unblock; logged to
   the observations inbox for the fold step. A 683 follow-up should confirm no other 683 teardown residue
   remains.

**Remaining work in 658's own scope: none** — the MCP evidence projection is complete. The named
non-scope items (skipped-file/OCR read surface, diagnostics-bundle content-body redaction, the 647
performance panel, and the general `subjectCarriers` reverse-coverage gate) remain handed off as
documented above; they are not blockers for closing 658.

