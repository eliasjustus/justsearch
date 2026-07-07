---
title: "MCP surface obligations — conformance, capability policy, and agent-adoption legibility for JustSearch's production MCP endpoint"
type: tempdocs
status: open — core capability-policy + conformance work shipped and live-verified (see "Current state" at the end); client fixture coverage remains deliberately deferred as a product decision. 2026-07-07: this tempdoc now ALSO owns the adoption/discoverability lever — the 624 Step-1 pilot measured adoption_rate 0.0 with a verified eager-offered surface (see "Adoption-zero finding" at the end); the 624 Step-2 utility run is gated on moving adoption via the product surface (names/descriptions/server instructions), never via the frozen eval prompt. 2026-07-07: the adoption lever now has a settled DESIGN (not built) — an "agent-legibility layer": the absent MCP `initialize.instructions` field populated as a single-sourced projection of the existing `getStatusContext` steering signal, comparative (not feature-forward) guidance placed where 366's eval proved it works (connect-time instructions + tool responses, not longer descriptions), and validated on 624's scale-member corpus via a two-corpus adoption contrast. See "Design pass on the agent-adoption legibility lever" at the end. 2026-07-07: the agent-legibility layer is now IMPLEMENTED and live-verified (instructions field + comparative response hints + TOOL_SURFACE_VERSION 0.2.0 + orphan removal; real `claude` CLI quoted the instructions back over the production HTTP `/mcp`; 11/11 conformance) — see "Implementation of the agent-legibility layer" at the end. The decisive adoption two-corpus contrast stays deferred to 624's not-yet-built scale corpus
created: 2026-06-28
updated: 2026-07-07
category: mcp / agent-safety / contract-testing / capability-policy
related:
  - 500-mcp-protocol-surface
  - 401-mcp-alternatives-considerations
  - 0015-mcp-tool-surface-design
  - 0030-policy-on-operations-vs-mcp-hints
  - 654-local-runtime-contract-and-product-center
  - 656-five-minute-agent-runtime-onramp
  - 657-install-modes-and-model-pack-decomposition
  - 660-plugin-sdk-community-onramp
  - docs/reference/mcp-production-server.md
  - docs/reference/runtime-contract.md
  - docs/reference/security/threat-model.md
---

> NOTE: Noncanonical working tempdoc. Verify against canonical docs and code before
> treating any claim as current truth.

# 655 - MCP surface: conformance, capability policy, and agent-adoption legibility

## STATUS SUMMARY (2026-07-07) — read this first

> This document has a long dated history below (append-only). This block is the current picture;
> the older "Current state (as of 2026-07-02)" section further down is **superseded by this one** (it
> predates the adoption/legibility work). Tempdocs are dated history, not canonical truth — verify
> against `main` before trusting.

**Three bodies of work live under this tempdoc, in order of recency:**

1. **MCP conformance + capability policy** — SHIPPED earlier (see the 2026-07-02 sections). Mutating
   tool wired to the real consent mechanism; boundary schema validation; upstream conformance suite
   adopted; `listChanged` over-declaration fixed; token-vs-gate relationship documented. Only genuinely
   open item from that era: **client fixture coverage (Q2)** — a deliberate, still-open product decision,
   not a technical blocker.

2. **Agent-adoption legibility layer** — IMPLEMENTED and live-verified (2026-07-07), committed on branch
   `worktree-td655-investigation` (not yet a PR). This is the newest work; full detail in "Implementation
   of the agent-legibility layer" and "Post-implementation refute-first review + fix" at the end. What
   shipped: the MCP `initialize` `instructions` field (single-sourced comparative tool-selection
   guidance), comparative response hints on `answer`/`search`, `TOOL_SURFACE_VERSION` 0.1.0→0.2.0, and
   removal of the orphaned feature-forward `getStatusContext` tail. Backend-only — the consumer is an
   autonomous agent, so the MCP protocol *is* the surface; there is no UI to browser-check.

   **Verification claims, each with its evidence pointer:**
   - Unit/logic: `McpProtocolHandlerTest.initialize_returnsCapabilities` (asserts a non-blank comparative
     `instructions`), `…instructionsAndPromptPath_shareSingleSourcedGuidance` (single-source guard),
     `…comparativeAnswerHint_countsDistinctDocuments_notChunks` (the review-fix regression) → PASS via
     `./gradlew.bat :modules:ui:test :modules:app-api:test` → `BUILD SUCCESSFUL`.
   - Compile gate: `./gradlew.bat build -x test -PskipWebBuild=true` → `BUILD SUCCESSFUL`.
   - Live surface: `POST http://127.0.0.1:<port>/mcp` `initialize` returned `serverInfo.version:"0.2.0"`
     and the full comparative `instructions` string; `tools/call justsearch_answer`/`justsearch_search`
     emitted the comparative hints (command + captured output, this session's dev stack).
   - Real-agent end-to-end: the stock `claude` CLI v2.1.202 connected to the production HTTP `/mcp`
     quoted the `instructions` back verbatim (command + output) — closing the stdio-vs-HTTP plumbing
     residual.
   - Conformance: `node scripts/ci/check-mcp-conformance.mjs --url …/mcp` → "All 11 protocol-conformance
     scenarios pass."
   - Evidence nature: the unit/compile/conformance pointers are re-runnable commands with stable output;
     the live-surface and real-agent pointers were captured as command+output in-session (no persisted
     `capture_evidence` bundle — the dev stack was started manually and later held by another session).
     They are reproducible by re-running the listed commands against a fresh stack of this branch's build.

3. **Adoption measurement (the reason the layer exists)** — NOT yet done; the decisive result is still
   open. See "next agent" below.

**For the next agent — remaining work, deferred checks, and unverified assumptions:**
- **Deferred (external dependency, the main open work):** the decisive **two-corpus adoption contrast**
  — re-run 624's Step-1 pilot on a headroom corpus and show adoption rising where the index wins while
  staying low on the grep-able corpus. Blocked on **624's ~2–4k-doc "scale member" corpus**, which does
  not exist (generator `scripts/jseval/jseval/corpus_generate.py` can produce it via `distractor_ratio`/
  `n_chains`, but there is no built corpus and no CLI wrapper). This is 624-owned; 655's surface is ready.
- **Deferred check:** a live re-probe of the *corrected* answer hint was not run (the shared dev stack
  was held by another session and not taken over). The corrected logic is covered by the deterministic
  unit test above; only the live re-observation is outstanding.
- **Unverified assumption:** that comparative instructions/hints actually *move* an agent's tool-choice —
  the plumbing is verified, the behavioral effect is not (it is exactly what the deferred pilot measures).
- **Unverified assumption:** the adoption-zero finding is haiku-only; frontier-model (Sonnet/Opus)
  adoption was never measured. A single frontier-model cell would cheaply disambiguate "small-model
  artifact" from "real surface problem."
- **Follow-up (out of scope, logged):** `RemoteDocumentService.mapRetrieveContextResponse` hardcodes
  `docsUsed=0` on the rich-params retrieve path (violates the `ContextResult` javadoc) — recorded in the
  observations inbox; not this tempdoc's to fix.

## Purpose

JustSearch already ships a production MCP endpoint and a deliberately curated tool surface. The next
strategic question is whether that surface is merely documented, or whether it is certified as an
external contract that agent clients can trust.

This tempdoc asks a next agent to design the conformance and policy layer around MCP: protocol
capability negotiation, tools/resources/prompts behavior, schema validation, structured outputs,
client fixture coverage, session/token behavior, and capability policy for tools that mutate local
state.

## Boundary

Do not start by adding `justsearch_delete`, `justsearch_reindex`, or more lifecycle tools. The reports
make those tools look attractive, but destructive or broad lifecycle tools need capability/default-deny
semantics first. This tempdoc should define the safety and conformance frame that makes future tool
expansion coherent.

Do not re-litigate whether the tool surface should be curated; `mcp-protocol-surface` and
`mcp-tool-surface-design` already established that principle. This note owns the next layer: certify
and govern the curated surface.

## Prior owners to read first

- `mcp-protocol-surface` for the shipped protocol and resources/prompts frontier.
- `mcp-alternatives-considerations` for previous MCP design ideas and tool-shape tradeoffs.
- ADR-0015 for the fewer-task-oriented-tools decision.
- ADR-0030 for JustSearch's policy-vs-hints divergence and trust-tier reasoning.
- `docs/reference/security/threat-model.md` for current localhost and MCP threat assumptions.

## First questions

- What conformance tests should define "supported MCP client behavior" for JustSearch?
- Which MCP clients should have first-class fixtures and documented configs?
- What schema/output guarantees should tools provide to external agents?
- Which tools are read-only, which mutate local state, and how is that reflected in policy?
- Should future lifecycle tools be separate MCP tools, REST-only helpers, or gated operations surfaced
  through MCP only under explicit grant?

## Investigation pass (2026-07-02) — grounding before theorizing

A pass over the shipped code, the shipped docs, and the current MCP ecosystem, done to check
this tempdoc's assumptions against reality before any design work. Findings, not decisions.

**The title above has been revised.** The original framing ("turn it into a certified,
policy-governed external contract") is out of step with tempdoc 654's Runtime Contract v1,
which shipped the same day this pass was written and explicitly states: *"There is no
'certified'/'compliant'/'conformant' claim: there is no MCP server certification program, and
MCP conformance tooling is spec/SDK-facing and still maturing... the honest, checkable status
JustSearch can pursue is 'listed, namespace-verified,' never 'certified.'"* Conformance testing
still has real value here — it just isn't a certificate to earn. Its value is catching silent
protocol drift and client incompatibility before a user hits it. That reframing is carried
through the rest of this pass.

**The declared MCP protocol version (`2025-11-25`) is current**, confirmed against the
upstream spec's own dated releases. No version-lag problem. Three different protocol-version
strings do coexist in the repo, however: the production server (`2025-11-25`), JustSearch's own
MCP-*client* subsystem that consumes external MCP servers for the agent loop (`2024-11-05`), and
the bundled local reference/test server (`2024-11-05`). These are different roles (server vs.
client vs. test fixture) so this isn't necessarily wrong, but it's worth an explicit decision
about whether the client subsystem should also move forward, and worth naming clearly so a
future reader doesn't assume it's one number.

**An official, runnable conformance test suite already exists upstream**
(`modelcontextprotocol/conformance`, a TypeScript project distributed as
`npx @modelcontextprotocol/conformance server --url <endpoint>`). It exercises capability
negotiation, `tools/*`, `resources/*`, `prompts/*`, and (per its own docs) is what gates
Standards Track protocol changes upstream. JustSearch has no fixture-based conformance coverage
today — the one existing test file (`McpProtocolHandlerTest.java`) is a dispatch-level unit
suite (8 tests) that never exercises JSON-RPC error codes, session lifecycle, `resources/*`,
`DELETE /mcp`, or malformed-input handling.

**Concrete conformance gaps found by reading the handler, not by testing it:**
- `initialize` advertises `tools.listChanged: true` and `resources.listChanged: true`, but no
  code path ever emits the corresponding `notifications/*/list_changed` message — an
  over-declared capability a conformance run would likely catch.
- Only 2 of 6 tools (`justsearch_browse`, `justsearch_ingest`) route through the real JSON
  Schema (Draft 2020-12) validator used by the Operations pipeline. The other 4 dispatch
  in-process with unchecked casts on tool arguments — a wrong-typed argument throws an
  unhandled exception rather than a clean schema-validation error.
- Even where real validation exists, the schema is authored twice: once as the MCP-visible
  `inputSchema` in the tool catalog, once as the backend Operation's own declared schema.
  These have already drifted for `justsearch_browse` (one has a field the other doesn't) — a
  live instance of the "no generated contract binds them" risk tempdoc 401 flagged as
  theoretical (its item F3 / D1).
- The one mutating tool, `justsearch_ingest`, hits the trust lattice's `TYPED_CONFIRM` gate on
  every call from an MCP client (`SourceTier.UNTRUSTED`), and its own error message advises
  retrying with a `_confirmationToken` argument that is never read anywhere in the codebase.
  There is currently no way to satisfy that gate through MCP at all — the tool fails closed
  (safe) but advertises a retry path that cannot work (misleading, and itself a conformance
  defect worth naming explicitly rather than leaving implicit).
- Shipped docs have drifted from shipped code in minor ways: `mcp-production-server.md`
  describes 5 tools (there are 6) and documents the confirmation round-trip as if it functions.

None of this is a design conclusion — it's the state a design pass needs to start from.

## Theorization pass (2026-07-02) — directions, framings, and open tensions

Written before any design commitment, to surface options and tradeoffs a design pass should
weigh rather than rediscover. Nothing below is a decision.

### Reframing: this is two separable efforts, not one

The tempdoc's five "first questions" mix two differently-shaped problems that could be scoped,
sequenced, and even owned separately:

1. **Protocol conformance** — does the server correctly speak MCP. This is comparatively
   mechanical: adopt the upstream test suite, run it, fix what it flags (the `listChanged`
   over-declaration is exactly this kind of finding). Bounded effort, low ambiguity, and — per
   the "boundary self-validation" idea below — largely orthogonal to any policy decision.
2. **Capability policy for mutating tools** — what should an untrusted external caller be
   allowed to do, and how does it prove it's allowed. This is open-ended and safety-relevant: it
   is a product and trust decision, not a spec-compliance question. The MCP spec deliberately
   treats tool annotations (`destructiveHint` etc.) as *untrusted hints*, not policy — so nothing
   in the protocol itself will answer this question for JustSearch. ADR-0030 already made this
   same call for JustSearch's own Operation/plugin model (hints are advisory, `OperationPolicy`
   is enforced) — MCP capability policy is the same question applied to external callers.

Splitting the tempdoc's scope along this line would let the conformance half ship first (fixing
a real, already-broken behavior: the dead-end confirmation message) without waiting on the
harder, more consequential policy question to be fully settled.

### Directions for the conformance half

- **Adopt the upstream suite as a gate**, likely a self-hosted/manual CI lane rather than the
  public fact-lane CI (it needs a live running server, similar in shape to other live-stack
  gates already in this repo). Cheap, durable, but tests protocol-generic behavior — it won't
  catch JustSearch-specific regressions like the schema drift between `McpToolSurface` and
  `AgentToolsOperationCatalog`.
- **Fixture/replay coverage for specific real clients** (Claude Desktop, Claude Code, Cursor) as
  a complement, not a replacement — captures client-specific quirks the generic suite won't
  exercise. Real cost: proprietary clients change behavior over time and fixtures rot; this
  needs an honest maintenance-cost argument, not an assumption that more fixtures are strictly
  better.
- **Close the schema-authorship split** rather than living with it. Two shapes worth naming as
  alternatives, not decided here:
  - Generate the MCP-visible schema from the Operation's own schema (single source of truth,
    but risks reintroducing the "optional-param schema bloat" that ADR-0015's eval measured as
    an accuracy regression for small models — the schemas were deliberately hand-minimized for
    a reason).
  - Keep the hand-curated minimal schema for what the model *sees*, but require it to be a
    strict subset of the full enforcement schema (never a superset), checked mechanically. This
    would let ADR-0015's minimalism stand while still guaranteeing the two schemas can't silently
    diverge in a way that lets an invalid call through.
- **Validate at the boundary regardless of internal dispatch shape.** The reason 4 of 6 tools
  skip real validation is architectural, not a policy choice — `answer`/`search`/`status` were
  deliberately routed to direct in-process service calls (tempdoc 500) for latency and richer
  response shaping, bypassing the Operations pipeline where validation happens to live. A
  narrower fix than re-routing those tools through Operations: validate MCP tool arguments
  against the declared `inputSchema` at the MCP handler itself, before dispatch, independent of
  which backend path a given tool takes. That preserves the direct-dispatch architecture while
  still giving every tool the same input guarantee.

### Directions for the capability-policy half

At least four genuinely different shapes exist; none is obviously "the" answer yet:

1. **Fix the existing mechanism rather than replace it.** Make the `TYPED_CONFIRM` gate
   actually satisfiable through MCP. The MCP spec has a purpose-built primitive for exactly this
   — *elicitation* (server asks the client mid-call for user input/confirmation) — which would
   let JustSearch use a spec-native mechanism instead of the current homegrown, non-functional
   `_confirmationToken` convention. Real caveat: elicitation support across hosts is uneven
   today, so this needs an explicit fallback story for hosts that don't implement it (plausibly:
   the tool simply stays unusable for mutation on those hosts, which is close to today's de
   facto behavior anyway).
2. **Expose the existing durable-grant mechanism as a supported, documented policy surface.**
   JustSearch already has an "allow-always" grant store, scoped by source tier, used elsewhere
   in the Operations pipeline. Rather than inventing new capability-policy machinery, the
   MCP-specific work could be: let a user grant a durable, scoped permission (e.g., "MCP clients
   may ingest files under this folder without per-call confirmation") through the existing
   mechanism, and document that as the supported story for repeat mutation. This mirrors a
   pattern already common in third-party MCP-gateway products (destructive tools blocked by
   default, explicit allowlist to unblock).
3. **Tier the tool surface by trust, not just by model capability.** Tempdoc 401's still-open D2
   considered a second, richer MCP surface gated behind capability (for large models that can
   use more advanced parameters). The mutating-tool question suggests the same lever — a second
   surface, opt-in, more capable — could be gated by *trust* (an explicit connection-level grant)
   rather than by model tier. These may turn out to be the same mechanism serving two different
   justifications, worth evaluating together rather than as two separate open questions.
4. **Keep MCP read-only in v1, deliberately and explicitly.** The bluntest option: drop
   `justsearch_ingest` from the MCP tool list until a capability-policy mechanism actually works,
   rather than listing a tool that is currently unusable end-to-end. This costs a capability
   that currently doesn't function anyway, and removes a tool that "looks complete" but silently
   isn't — arguably itself a conformance improvement, since a listed-but-uncallable tool is a
   worse client experience than an absent one.

These aren't mutually exclusive — for instance, (1) could ship as the near-term fix for the
existing dead end, while (2) or (3) is designed as the longer-term policy story.

### Hidden assumptions worth surfacing before they get baked in

- **"MCP client == UNTRUSTED" is currently binary.** All MCP callers share one `SourceTier`
  today, whether that's a locally-launched agent the user started seconds ago or (per tempdoc
  401's D3, if Streamable HTTP transport ever ships) a caller reaching JustSearch from somewhere
  less local. A capability-policy design that assumes "MCP" and "untrusted-but-local" are
  permanently synonymous may need revisiting if the transport story changes; worth not encoding
  that assumption any deeper than necessary.
- **Token possession and trust-lattice gating are orthogonal today** — holding the MCP bearer
  token does not satisfy `TYPED_CONFIRM`, and vice versa. That may be intentional
  defense-in-depth (two independent layers an attacker must clear) or an unreconciled gap. Worth
  a deliberate call either way rather than leaving it as an artifact of how the two mechanisms
  were built independently.
- **Capability policy is being treated as an MCP-specific problem, but the shape is general.**
  ADR-0030 already reserved an `UNTRUSTED_PLUGIN` executor tier for a future sandboxed plugin
  marketplace (tempdoc 660), with the same "don't trust hints, enforce policy, force
  confirmation" shape MCP needs today. If this tempdoc solves "how does an untrusted caller
  satisfy a confirmation gate" as a general, transport-agnostic mechanism (keyed on
  `SourceTier` + `RiskTier`, which is how the trust lattice already models it) rather than an
  MCP-only patch, the same fix likely serves 660 for free later. Worth checking, when the design
  pass happens, whether the fix naturally generalizes or whether MCP has a genuine
  transport-specific wrinkle that forces a narrower answer.
- **Per-client fixture coverage assumes fixtures stay representative.** Worth treating as a
  cost/benefit question rather than an assumed requirement — three hosts' worth of fixtures is a
  real, ongoing maintenance commitment, not a one-time cost.

### A possible recurring principle (not a ruling)

Two shapes above recur elsewhere in this codebase and may be worth naming as general principles
if a future design pass confirms them, rather than treating this as an MCP-only fix:

- **Boundary self-validation**: an external-facing surface should validate inbound calls against
  its own declared contract at the boundary, independent of which internal code path ultimately
  serves the request. Today that guarantee only holds when the internal path happens to route
  through Operations; direct-dispatch paths get a free pass. If true, this isn't unique to MCP —
  it's worth checking whether the same non-uniformity exists on the REST surface.
- **Consent is a source-tier property, not a transport property.** The trust lattice already
  models `SourceTier` as transport-agnostic in principle; the actual mechanics of satisfying a
  confirmation gate have so far only been wired for one transport (the UI) and are broken for
  another (MCP). A fix framed as "how does any `UNTRUSTED`-tier caller satisfy `TYPED_CONFIRM`"
  rather than "how does MCP satisfy it" would likely need to be built once, not once per future
  external-ingress class.

Neither of these should be treated as settled — they're patterns worth testing against the
actual design once one is chosen, not conclusions to design backward from.

## Design pass (2026-07-02) — grounding in the consent architecture that already exists

A further investigation pass, prompted by one question: before designing anything new for MCP's
mutating-tool problem, does JustSearch already have a general confirmation/consent mechanism that
MCP simply isn't wired into? It does, and it is more complete than the earlier passes assumed.

**What already exists, end to end, for the UI caller today:**

- A trust lattice (`TrustLattice` / `CoreTrustEvaluator`) that maps `(SourceTier, RiskTier)` to a
  `GateBehavior` (`AUTO` / `INLINE_CONFIRM` / `TYPED_CONFIRM` / `DENY`). It is built as a genuinely
  generic, N-dimensional lookup — dimensions and cells are declared, not hardcoded in a switch —
  specifically so a new caller class or a new axis is "a longer coordinate through the same
  lookup," not a forked evaluator. `SourceTier.UNTRUSTED`'s own documentation already names
  "external MCP client" as a covered case. No lattice redesign is needed to accommodate MCP.
- When a gate isn't `AUTO`, dispatch throws a typed exception carrying exactly what's needed to
  resolve it later (the operation, the risk tier, the source tier, the gate behavior). Its own
  contract says the throwing caller is responsible for surfacing consent UX and re-dispatching
  with proof of consent — it doesn't assume that caller is a browser.
- Two independent ways to supply that proof, both already built and already keyed by `SourceTier`
  (transport-agnostic, not UI-specific):
  - A **single-use, expiring, cryptographically bound approval** for one exact operation-plus-
    arguments (an HMAC-signed capsule, unforgeable without the server's in-memory signing key,
    minted only after a real approval gesture and consumable exactly once).
  - A **durable "allow always" grant**, scoped by source tier and by operation or capability
    family, persisted across restarts, revocable, and swept automatically for untrusted callers
    on an emergency stop.
- A server-minted, opaque "pending authorization" record that a gated call creates *before* asking
  for consent, so the approval UI can only ever approve a request the backend itself actually
  gated — not an arbitrary operation and arguments an attacker could construct and get rubber-
  stamped.
- A UI ceremony (a browser-native, kernel-rendered dialog, structurally hardened against being
  spoofed by plugin-authored content) that reads a pending record, asks for approval (one-click or
  typed, depending on gate strictness), and on approval calls back to mint a capsule and,
  optionally, write a durable grant.

**The conclusion this supports:** JustSearch does not have a UI-specific consent mechanism with an
MCP-shaped hole next to it. It has one transport-agnostic consent mechanism, with exactly one
caller surface wired to it (the browser UI). MCP's mutating-tool gap is not evidence that a new
capability-policy system needs to be designed — it's evidence that the existing one has a second
caller surface still to wire in. The current MCP behavior (catching the gate's exception and
fabricating a retry hint that nothing in the system reads) is itself a small, ad hoc, parallel
stand-in for the real mechanism — a scaled-down instance of exactly the mistake a long-term design
pass should avoid making at any scale.

### The design: MCP as a second caller surface of the existing mechanism, not a new policy layer

At a general level (not implementation-level — sequencing, exact error shapes, and blocking-vs-
polling semantics belong to whoever implements this):

- On the gate exception, the MCP handler should do what the UI's controller already does: create a
  pending-authorization record carrying the caller's real, already-correct `SourceTier.UNTRUSTED`
  provenance — not fabricate a token convention nothing validates.
- The natural place to *answer* that pending request is the JustSearch desktop app itself, not the
  calling MCP host's own UI. This falls directly out of the loopback-only invariant: an MCP tool
  call can only reach this gate if a JustSearch instance is already running locally, so the same
  hardened approval dialog the UI path already uses is guaranteed to be available. The tool's
  response to the calling agent, while the request is pending, should say that plainly — approval
  is pending in the JustSearch app — rather than implying the agent itself can resolve it by
  retrying with a magic argument.
- Repeat-use friction is already solved by the durable-grant half of the same mechanism. A user who
  grants "allow always" once — from the same dialog, using the affordance that already exists —
  covers future calls from that source tier with no additional design or code needed; MCP already
  reports its source tier correctly today.
- MCP's own protocol has a purpose-built primitive for a server asking a client mid-call for input
  (elicitation). It is worth treating as a *possible protocol-level enhancement layered on top* of
  the local answering surface for hosts that implement it well — never as the primary mechanism.
  Host support for it is uneven, and the local app's own dialog already works unconditionally,
  regardless of which MCP host is calling. Designing the primary path around elicitation would
  repeat the "wait for the ecosystem to catch up" pattern this project already treats as a smell
  elsewhere — the durable, always-available answer is the one that doesn't depend on a third
  party's adoption timeline.

**What this design deliberately leaves unchanged:** the source-tier/risk-tier classification of MCP
callers (already correct), the trust lattice's structure and cell values (already generic enough),
and ADR-0030's reserved `UNTRUSTED_PLUGIN` tier (a different axis — declaration-time trust of an
*operation*, not run-time trust of a *caller* — relevant to a future plugin marketplace, not to
this gap).

### The same shape, applied to schema enforcement

The conformance-side investigation found the same pattern in miniature: a real JSON Schema
validator already exists and is already used for two of the six MCP tools; the other four bypass
it entirely because they were routed to direct in-process service calls for latency and richer
response shaping. The long-term fix is not a second validator for the direct-dispatch tools — it's
routing every tool's arguments through the one validator that already exists, at the MCP boundary,
independent of which backend path a tool ultimately takes. The schema shown to the calling model
should stay hand-curated and minimal (deliberately, per the existing evidence that optional
schema surface hurts small-model accuracy) — but it should be checked to never accept a shape the
enforcement schema would reject, so the two can't silently drift the way they already have once.

### Conformance testing: adopt the existing standard, don't fork it

A JustSearch-authored protocol-conformance suite would be a second instance of a seam that already
exists externally: the MCP project publishes an official, runnable conformance suite maintained
against the spec itself. The durable design is to adopt that suite as the primary conformance gate,
and reserve JustSearch-authored tests for what the generic suite can't see — its own curated tool
set's specific behavior (ordering, hints, the schema-subset property above) — rather than
re-implementing general protocol checks a standard body already owns and maintains.

## Principle and reach (named, not built)

**The principle:** consent satisfaction belongs to the transport-agnostic gate (source tier, risk
tier, the operation being called), not to whichever caller surface happens to trigger it. JustSearch
already built its trust lattice and its consent primitives this way — none of their contracts
mention "browser" or "UI," only source tier, risk tier, and operation identity. The one thing left
unfinished is wiring a second caller surface to the mechanism that was already built generic enough
to take one.

**Where else this applies, without building it now:**

- ADR-0030's reserved tier for a future sandboxed plugin marketplace already anticipates this in its
  own text — the same mechanism, not a parallel one, is expected to eventually cover it.
- A future non-loopback MCP transport would need a different *answering* surface (a human can't be
  assumed to be at the same machine), but should still produce and consume the same pending-record,
  capsule, and durable-grant primitives — only the third leg (how the human is asked) would differ.
- Any future scheduled or rule-engine-triggered caller (already named as a covered case in the
  source-tier model's own documentation) is the same shape again.

**Where it's already violated:** the MCP handler's current handling of the confirmation gate — a
small, self-contained, already-broken parallel mechanism sitting next to the real one. That's the
concrete, present-tense argument for the principle, not just the future cases.

**What is deliberately not being built as part of this tempdoc:** a generalized, pluggable
"answering surface" abstraction sitting in front of the consent primitives, with the browser
dialog and MCP as two interchangeable implementations under it. Two caller surfaces exist in scope
right now; an abstraction with a single real consumer plus one to be added isn't earning its
keep yet. If a genuine third caller surface is ever designed — a networked MCP transport, or the
plugin marketplace — that is the point to extract the shared interface, not before. Recording it
here so it isn't rediscovered as new when that time comes.

## External landscape check (2026-07-02)

A short research pass, done because two claims the design above rests on are genuinely moving
targets in the wider MCP ecosystem right now, not settled facts safe to assume from memory.

**Elicitation host support is confirmed uneven, which supports treating it as an enhancement, not
the primary mechanism.** As of this pass: Claude Code implements MCP elicitation (interactive
dialog, response passed back to the server); Cursor has recently added it; Claude Desktop does
not yet support it. This is exactly the spread the design above assumed rather than measured —
worth having actually checked before committing to "layer it on top, don't depend on it," since a
uniformly-supported primitive would have changed the recommendation. ([Claude Code MCP
docs](https://code.claude.com/docs/en/mcp), [Cursor/Claude Code MCP
comparison](https://composio.dev/toolkits/cursor/framework/claude-code), general elicitation
support survey via [ZazenCodes MCP elicitation
tutorial](https://zazencodes.substack.com/p/mcp-elicitation-tutorial))

**The design's shape matches converging industry practice, independently arrived at.** Current
guidance for production MCP servers converges on the same three points this design already
reaches from JustSearch's own architecture: destructive tools should require explicit
confirmation rather than relying on client-side trust of tool annotations; annotations are for
policy enforcement at the client/gateway layer, not a substitute for server-side gating; and
every gated call should be audit-logged with caller identity and timestamp, treated as a
pre-launch requirement rather than a nice-to-have. JustSearch already has the audit half of this
covered — dispatch already records gate decisions to an action ledger independent of which
transport triggered them — so the external convergence check surfaces no new gap on that axis,
only confirms the confirmation-gating direction is not idiosyncratic. (Source: 2026 MCP
production-guidance surveys, e.g. [MCP Server Anti-Patterns: Design Mistakes 2026
Guide](https://www.digitalapplied.com/blog/mcp-server-anti-patterns-design-mistakes-2026-developer-guide),
[Human-in-the-Loop in MCP](https://bytebridge.medium.com/human-in-the-loop-in-mcp-safeguarding-autonomous-ai-with-oversight-and-policy-e8f7dbe98aee).
General guidance of this kind informed judgment here; nothing from these sources was copied into
the design or the codebase.)

**The upstream conformance suite is safe to depend on, license-wise, if adopted later.**
`modelcontextprotocol/conformance` is dual-licensed (Apache-2.0 for current contributions, MIT for
carried-over older contributions, CC-BY-4.0 for its documentation) — a standard permissive
combination with no obligation beyond attribution if any of its content were ever copied. Running
it as an external dependency (`npx @modelcontextprotocol/conformance ...`) in CI carries no
license concern at all. The design above only proposes *running* it, not vendoring or adapting its
scenario definitions — if a future implementation pass ever copies or closely mirrors specific
scenario text or fixtures from that repository into JustSearch's own tests, that copy should
credit the source and note the license at the point of copying, per this repo's own
license-and-notices requirement; nothing has been copied as part of this tempdoc.

**Tool-surface versioning (SEP-986 / SEP-1575 naming and semver, SEP-1730 SDK tiering) is
progressing, not stalled.** These proposals — the exact mechanism tempdoc 654's
`mcpToolSurfaceVersion` was designed to pre-conform to rather than compete with — are being
incorporated into the specification and are already showing up in official SDK releases. This is
reassuring rather than actionable for this tempdoc: it confirms 654's choice to track rather than
invent was well-timed, and gives no reason to revisit that decision here. (Source: [MCP tool
versioning/naming
discussion](https://github.com/modelcontextprotocol/modelcontextprotocol/issues/1915), [MCP
2025-11-25 changelog](https://modelcontextprotocol.io/specification/2025-11-25/changelog).)

None of this changes the design above. It narrows two assumptions from "reasoned guess" to
"checked," and turns up nothing that argues for a different direction.

## Implementation status (2026-07-02)

Implemented, following the design above almost exactly, with one refinement discovered during
implementation (recorded above in "Design decision made during investigation" — approval executes
server-side rather than via a stashed-capsule replay):

- **Backend**: `PendingAuthorizationStore`/`ConsentCapsuleAuthority`/`DurableGrantStore` are now
  shared between the REST gate path and `McpToolSurface` via the substrate (previously the pending
  store was constructed locally in `ResourceApiModule` and invisible to MCP). A gated
  `justsearch_ingest` call now creates a real pending record and returns a truthful,
  non-blocking message instead of the dead `_confirmationToken` retry hint. All 6 MCP tools
  validate arguments against their declared schema at the boundary before dispatch (via a new
  schema-string overload on `OperationInputSchemaValidator`). The `justsearch_browse` schema
  drift (`list_files`) is fixed.
- **Approval completion**: `AuthorizationController`'s approve endpoint gained an opt-in
  `execute: true` flag — when the approving caller has no client-side copy of the original
  arguments to replay (the MCP case), the server completes the dispatch itself immediately,
  using the pending record's own stored args. The existing browser-originated flow (which does
  hold its own args) is unchanged.
- **Live announcement**: a new `PendingAuthorizationChangeRegistry` (event-only SSE stream,
  mirroring `IntentEnvelopeChangeRegistry`) broadcasts every pending-record creation, from either
  transport, as a 6th channel on the existing multiplexed shell-events stream.
- **Frontend**: a new `pendingAuthorizationBridge.ts` module subscribes that stream and reuses
  the existing `<jf-authorization-host>` ceremony (via `authorizationBroker.requestAuthorization`)
  — no new modal adopter, no new presentation primitive; only a second trigger origin for one
  that already existed (recorded in `docs/explanation/27-frontend-presentation-kernel.md`).
- **Conformance**: `scripts/ci/check-mcp-conformance.mjs` locks in the 11 protocol-level scenarios
  confirmed to pass against the real server (of the upstream suite's 30), with the 19
  fixture-requiring scenarios explicitly recorded as excluded and why — no parallel fixture-tool
  surface was built, per the tempdoc's own scope discipline. Manual/dev-stack-driven, not wired
  into public CI (ADR-0044).
- **Docs**: `docs/reference/mcp-production-server.md`'s tool count and Trust Model section now
  match shipped behavior.

**Verification status**: backend changes compile and pass unit tests (`McpProtocolHandlerTest`
extended with gated-ingest, durable-grant, and boundary-validation cases); frontend changes pass
unit tests (`pendingAuthorizationBridge.test.ts`, new) and the existing `ui-web` suite (one
pre-existing, unrelated flaky test excluded — confirmed via isolation with the changes stashed
away, logged to observations). `./gradlew.bat test` is green repo-wide except one pre-existing,
already-documented, environment-specific failure (`VduEligibilityPdfFixturesTest`, local
Tesseract/OCR gap, unrelated module).

**Live dev-stack end-to-end browser validation — run and confirmed (2026-07-02).** With the dev
stack running this worktree's build (`distFrom`) and a real browser open on the chat surface:
1. A real `POST /mcp` `tools/call` for `justsearch_ingest` (no prior grant) returned the truthful,
   non-blocking message immediately — no `_confirmationToken` mentioned.
2. The `<jf-authorization-host>` dialog appeared live in the already-open browser (no page action
   taken), with the correct operation id, gate, risk tier, source tier, and args summary — proving
   the MCP → pending record → SSE broadcast → frontend bridge → existing ceremony path end to end.
3. Approving (typed confirm) closed the dialog and the action ledger's "N operations" toast
   incremented — confirming the server executed the operation itself using the pending record's
   own stored args, with no round trip of arguments through the browser.
4. Checking "Always allow this action" on a subsequent gated call, then issuing a further MCP
   `justsearch_ingest` call, completed with **zero prompt** — confirming the pre-existing
   durable-grant mechanism already covers MCP callers with no MCP-specific code, exactly as the
   design predicted.
No console errors were observed at any point in the flow.

## Post-implementation critical-analysis pass and fix (2026-07-02)

A critical review against this tempdoc's own stated design, run immediately after the above was
committed (nothing pushed in between), found two privacy/security-relevant regressions and three
other real gaps. Recorded here because the miss is worth keeping as history, not scrubbed.

**The finding.** `PendingAuthorizationEvent`'s original shape carried `argsSummary` and
`rationale` — the SAME privacy-bounded summary `ArgsSummary` computes for the point-to-point 428
response — onto the new multiplexed SSE broadcast, which every locally-connected subscriber
receives. This directly violated `ArgsSummary`'s own documented scope (tempdoc 444b: never put
argument-derived content in a broadcast; tempdoc 550 F3's summary is a *named, deliberate
exception* scoped to the one point-to-point response to the one human deciding that one action —
not to a multicast channel). The existing action-ledger stream already respects that boundary by
omitting args; this tempdoc's own new broadcast didn't. Compounding it: `execute: true` (added in
the same pass) lets a bare `pendingId` complete a mutation without the caller separately knowing
the original args — bounded by the loopback/single-user threat model and 128-bit-UUID
unguessability, but a real narrowing that's worse when the id travels with its content attached.

**The fix.** `PendingAuthorizationEvent` now carries routing info only (`pendingId`,
`operationId`, `sourceTier`, `riskTier`, `gateBehavior`, `createdAt`, `expiresAt`) — no decision
content. A new point-to-point `GET /api/authorizations/pending/{id}` endpoint
(non-mutating — `PendingAuthorizationStore.peek`, not `consume`) serves the summary/rationale to
a caller that already holds the id, mirroring the 428's own scoping. The frontend bridge now
fetches this by id before presenting the ceremony (`OperationClient.peekPending`), skipping
presentation if the id no longer resolves (already expired/consumed). Re-verified live in the
browser afterward: the dialog renders identically, sourced from the new fetch instead of the
broadcast (confirmed via network-request inspection), and the full approve → server-execute →
action-ledger-increment flow still works end to end.

Also fixed in the same pass: added `AuthorizationControllerTest.java` (zero automated coverage
previously existed for `execute: true`, the most security-sensitive new code in this tempdoc —
verified only by manual browser clicks until now); declared `filters`' nested schema shape
(`path_prefix`, `meta_source`, etc.) so a malformed nested field is now caught by the boundary
validator instead of reaching an unchecked cast in `McpToolSurface#parseFilters`; and actually ran
`check-mcp-conformance.mjs` live for the first time — found the CLI's `--scenario` flag only keeps
the last value when repeated (not documented, discovered by testing), so the script was rewritten
to run the full active suite and filter results to the 11 asserted scenarios itself, since the
tool's own process exit code is 1 whenever ANY scenario fails (including the 19 expected ones).

## Post-implementation practicality review and future directions (2026-07-02)

A research pass done after the design shipped and was fixed up, asking a different question than
the earlier passes: not "is this correct," but "is this actually pleasant and useful for a real
person, and what's the smallest next step worth taking." Grounded in re-reading the shipped code
with fresh eyes, plus targeted research into how comparable products handle the same problem
(agent tool-approval UX) and what's technically available in this stack already. Nothing here is
committed to — the user asked for a menu of ideas, not a decision.

### What's already good — worth confirming, not fixing

**The friction model matches established practice, and one thing that looked like an oversight on
first read turned out to be deliberate.** `core.ingest-files` itself declares `ConfirmStrategy.Inline`
(one-click) as its normal confirmation style — but every live test this session showed a **typed**
confirmation ("type `core.ingest-files` to confirm"). That's not a bug: the trust lattice computes
`GateBehavior` from `(SourceTier, RiskTier)`, and for an `UNTRUSTED` source (which MCP callers
correctly are) at `MEDIUM` risk, the lattice's `TYPED_CONFIRM` supersedes the operation's own
milder preference — deliberate amplification for a never-vetted external caller, not a UX
miscalibration. And once a user grants "allow always," friction drops to zero for that operation
going forward. That arc — high friction for an unknown caller's first request, zero friction after
one explicit trust decision — is close to what current UX research on destructive/consequential
actions recommends: "friction is better than regret, but undo is better than both," and typed
confirmation specifically reserved for cases warranting real deliberation, not routine actions.
JustSearch's three-tier model (AUTO / INLINE / TYPED, plus the durable-grant escape hatch) also
lines up with the increasingly common "Suggest → Co-pilot → Autopilot" framing used elsewhere for
agentic tool permissions — the design wasn't invented in a vacuum; it happens to already match
where the field has converged. Also confirmed while re-reading: the approval ceremony already has
FIFO queue handling for multiple concurrent pendings, so a burst of MCP calls doesn't produce
overlapping or lost dialogs. Recording this as confirmed-good so a future pass doesn't "fix" what
isn't broken.

### A concrete, low-effort idea: an already-built capability that's currently unused

**`sendDesktopNotification(title, body)`** (`modules/ui-web/src/utils/notify.ts`) already exists,
already wired to Tauri's native OS notification plugin with permission handling, already no-ops
gracefully outside the desktop shell — and has **zero callers anywhere in the codebase** (present
since the initial public release, never wired to anything). This directly addresses a real gap:
today, if a user has JustSearch minimized or isn't looking at the window when an MCP tool call
gets gated, they have no way to know a pending approval exists until they happen to check — and it
expires in 5 minutes. Wiring this existing utility into `pendingAuthorizationBridge.ts` (fire an
OS toast when a pending arrives, before or alongside the in-app dialog) would close that gap with
a small, self-contained change — the hard part (the plugin integration, the permission dance) is
already done and already tested elsewhere in the app; this would just be its first real caller.

### A concrete, low-effort idea: MCP client identity is already available, and discarded

Every MCP client sends a self-reported `clientInfo: {name, version}` as part of the spec's own
`initialize` handshake — `McpProtocolHandler` receives this today and never stores or surfaces it
anywhere. Two independent, small wins follow from simply keeping it:

1. **Show it in the approval dialog.** Today the dialog says "an action requires your approval" —
   a user watching JustSearch has no way to tell from the dialog itself that the request came from
   MCP at all, let alone from which client. Surfacing `clientInfo.name` ("Claude Code wants to
   ingest...") is pure transparency — no trust-model change, just better information at the moment
   a human is asked to decide something.
2. **Longer-term: scope durable grants per-client-name, not per-whole-MCP-bucket.** Today "allow
   always" for an operation trusts *every* MCP caller forever, because `SourceTier.UNTRUSTED` has
   no finer identity concept. If a user has two different MCP clients connected (say, Claude Code
   for coding work and some other agent for something else), granting "always allow" because they
   trust one silently extends that trust to the other too. Splitting the grant key by client name
   would close that. **Caveat, stated plainly:** `clientInfo` is self-reported by the client, same
   as any handshake-declared identity — it's a meaningful UX/audit label and a coarse signal, not
   a hard security boundary on its own (a malicious client could claim to be "Claude Code"). Worth
   doing for the transparency and blast-radius reduction it buys, not as a security fix by itself.

### Ideas worth having on record, not worth committing to yet

- **MCP elicitation as a progressive enhancement.** Host support keeps improving since this
  tempdoc's earlier research pass (Claude Code and VS Code + GitHub Copilot both support it now;
  Claude Desktop's status is still unclear/likely absent). The "don't depend on it, layer it on
  top of the local-app answering surface" call from earlier in this tempdoc still holds — but the
  tradeoff keeps shifting in elicitation's favor. Worth another look in a few months, not now.
- **A visible "pending approvals" indicator in the app chrome** (a badge on wherever the existing
  activity surface lives — the same place that already shows the "N operations" toast observed
  live this session) so a dismissed or missed dialog is still findable before its 5-minute TTL
  runs out. Complements the desktop-notification idea above rather than replacing it — the
  notification is the "someone is asking," the badge is "you can still find it."
- **Everything the original design section already named as future scope** (the plugin-marketplace
  generalization, a networked/non-loopback MCP transport needing a different answering surface) —
  still correctly out of scope for now; nothing in this pass changes that call.

### Overall practicality read

The core mechanism holds up under a second look aimed specifically at "would a real person like
using this" rather than "is it correct." Nothing found here is a flaw in the shipped design — the
friction model, the queueing, the durable-grant escape hatch are all already sound. The genuine
gaps are both about *awareness*: a user who isn't looking at the app has no signal that something
needs them, and a user who is looking has no way to tell what's asking. Both have small, concrete
fixes using capabilities the codebase already has sitting unused, which is a better find than
"needs a new subsystem" — consistent with this tempdoc's own recurring theme (extend what exists
before building something new).

## Long-term design for the two practicality-review ideas (2026-07-02)

A design pass on the two concrete ideas from the section above, grounded in investigating what
already exists — with one correction to the earlier, less-researched framing of each. Design
level only (not implementation-level); nothing here has been built.

### The desktop-notification idea, corrected

The practicality-review section above suggested wiring `sendDesktopNotification` directly into
`pendingAuthorizationBridge.ts`. Investigation found that would have been a mistake: JustSearch
already collapsed exactly this class of problem once before. Tempdoc 559 explicitly retired a
second, parallel toast mechanism in favor of **one client-message channel** —
`emitEphemeralToast()` → `AdvisoryStore` → `AdvisoryToastHost` — with the doc comment on
`ephemeralToast.ts` stating plainly: "THE single client-originated message channel... Before 559
a SECOND toast system ran parallel to the advisory model; this collapses it." A direct,
feature-local call to `sendDesktopNotification` from `pendingAuthorizationBridge.ts` would
reopen exactly that seam — a second, parallel "tell the user something" path existing alongside
the one already-collapsed model, for no reason other than that the new feature didn't know the
old model was there.

**The corrected design:** route the pending-authorization signal through the existing single
channel, as a new *client-originated* emission from `pendingAuthorizationBridge.ts` — the same
way any other locally-detected event already reaches the user, not a bespoke path. Two further
pieces of existing infrastructure make this cleaner than starting fresh, rather than harder:

- The backend Advisory system already has a render-hint vocabulary
  (`EmissionPolicy`/`RenderHint`) with an `EPHEMERAL` (toast, vanish) / `PERSISTED` (inbox) /
  `REQUIRES_ACK` (inbox, must-acknowledge) distinction — and `REQUIRES_ACK`'s own doc comment
  already names **"destructive-action confirmations the user dismissed"** as its intended future
  use, unused by any class today. A pending MCP approval is close to a textbook instance of what
  that hint was reserved for. This gives the "pending approvals indicator" idea from the
  practicality-review section for free, via whatever inbox/badge surface already renders
  `REQUIRES_ACK` records — no separate indicator needs designing.
- **Desktop-notification escalation belongs at the render layer, not the feature layer.** The
  correct place for "if the user isn't looking, also fire an OS notification" is a single
  conditional in the shared dispatch point (wherever `AdvisoryToastHost` — or whatever ends up
  rendering `REQUIRES_ACK`-classed records — decides to show something), keyed on document
  visibility/window focus and the record's severity/render-hint. Placed there, MCP's pending
  approval becomes the *first* caller to benefit, but the capability is reusable by any future
  must-acknowledge event (a health failure, an install failure — tempdoc 663 shipped a
  completion/failure toast for exactly that shape only a day earlier) without additional
  plumbing. This is not "build a general notification framework" — it is choosing the one
  correct existing integration point for a real, present need, which happens to generalize
  because that point was already the right altitude for this kind of decision. The distinction
  matters: nothing broader is being built now, only placed correctly.

### The MCP client-identity idea, corrected

The practicality-review section proposed showing `clientInfo.name` in the dialog and, longer
term, scoping durable grants per client name. Investigation found the first half straightforward
but the second half needs a real correction, not just a caveat.

**What's actually true today:** `McpProtocolHandler.handleInitialize` does not merely discard
`clientInfo` — it never parses the `initialize` request body at all; the session object
(`McpSession`) holds only a last-activity timestamp and a subscription set. Capturing
`clientInfo.name`/`version` at `initialize` and carrying it as an optional field on the session,
then into `PendingAuthorization` and the existing `GET /api/authorizations/pending/{id}` response
built in the fix pass, is a small, additive, well-scoped change — a new nullable field flowing
through structures that already exist, not a redesign.

**Where the earlier framing needs correcting:** `DurableGrantStore`'s grant identity today
(`OperationKey`/`FamilyKey`) is composed exclusively of structural, enforced fields — the
operation/family id (declared by the Operation registry) and `SourceTier` (derived by the trust
lattice from transport, not asserted by the caller). There is no precedent anywhere in that store
for keying a grant by caller-self-reported data, and this codebase already has a canonical,
explicit line between "hint" and "enforced policy": ADR-0030, which draws it for MCP tool
annotations specifically (`ToolAnnotations` are untrusted hints; `OperationPolicy` is enforced,
diverging deliberately from raw MCP discipline for CORE/TRUSTED_PLUGIN tiers). `clientInfo.name`
is exactly a hint in that same sense — asserted by the client, unverifiable, no different in kind
from a tool annotation. Baking it into `DurableGrantStore`'s key — even as a narrowing, not a
widening, of trust — would blur a line this codebase has already deliberately drawn once and
documented. The corrected design: **`clientInfo` stays a display/audit-only field, full stop, in
this pass.** If per-client trust scoping is ever wanted, it needs its own justification and its
own mechanism (most plausibly: a real paired/registered client credential, not a self-reported
name) — a materially bigger, separate problem this tempdoc does not need to solve now, and
naming it here is enough; building toward it isn't warranted by anything currently in scope.

## Principle and reach, second pass (2026-07-02)

**The principle, stated plainly:** *a cross-cutting "the user should know" signal belongs to the
one existing dispatch mechanism for that concern, not a new one invented per feature — and where
a decision needs to escalate (e.g. to an OS-level notification), that decision belongs at the
shared dispatch point, keyed on general properties (severity, focus state), not duplicated
per-caller.* This is not a new discovery — it's the same shape as this tempdoc's very first
principle ("consent satisfaction belongs to the transport-agnostic gate, not the caller
surface") and as tempdoc 559's own retirement of the second toast system, applied a third time.
The pattern in this codebase, now confirmed three times over, is: when a new feature needs a
cross-cutting capability (consent, user-notification), the correct move is almost always to make
that feature the Nth caller of an existing single mechanism, not the 1st caller of a new one.

**Where else this applies:** any future feature needing to alert the user asynchronously — a
plugin-marketplace update, a background corpus sync finishing, a scheduled-trigger result —
should route through the same single messaging model, and would get OS-level escalation for free
once the render-layer hook above exists, without inventing its own.

**Where existing code already brushes against violating it:** `sendDesktopNotification` itself,
sitting outside the collapsed messaging model since the initial public release with zero callers,
is a small, latent instance of exactly the "second channel" risk tempdoc 559 was written to
prevent — not yet a violation (nothing calls it), but the practicality-review section's original,
uncorrected suggestion would have created one. Recording this here is the point of separating
"recognize the principle" from "build the general structure": the fix is to route the one new
caller through the existing model correctly, not to leave the orphaned function as a standing
invitation for the next feature to wire it up ad hoc instead.

**The second principle — reapplied, not new:** self-reported/unverified caller metadata
(`clientInfo`, MCP tool annotations, and by the same logic any future handshake-declared
identity from an external protocol) is display/audit data, never trust-key data. ADR-0030 already
states this for MCP tool annotations; this pass confirms it extends cleanly to MCP client
identity, and would extend the same way to any future external-caller metadata this codebase
ever receives. No new structure is needed to enforce this — the existing line already covers it;
the discipline is simply to keep recognizing new instances of the same shape rather than
re-litigating the question each time.

## Implementation of the long-term design (2026-07-02)

Both ideas from the design pass above are now implemented and live-verified end to end.

**MCP client identity (display-only).** `McpProtocolHandler.handleInitialize` now parses
`params.clientInfo.name` (previously not parsed at all) and stores it on `McpSession`.
`McpToolSurface.callTool`/`callOperation`/`handleConfirmationRequired` gained an additive
`requestedBy` parameter threading it to `PendingAuthorizationStore.create(...)`.
`PendingAuthorization` gained a `requestedBy` record component (null for a browser-originated
gate). `GET /api/authorizations/pending/{id}` now includes `requestedBy` when present (omitted,
not null, when absent). `AuthorizationPrompt`/`PendingAuthorizationDetail` gained a matching
optional field; `AuthorizationHost`'s dialog renders "Requested by: `<name>`" when present.
`requestedBy` deliberately never reaches `DurableGrantStore` or any other trust-relevant code —
confirmed by the implementation, not just designed that way.

**Missed-approval notification.** A new backend Advisory class, `authorization.pending`
(`PendingAuthorizationAdvisoryProjector`), uses `EmissionPolicy.requiresAck()` — the
previously-unused REQUIRES_ACK render hint, now with its first real caller. It's wired as a
bootstrap-time `subscribeTyped` listener on `PendingAuthorizationChangeRegistry` (mirroring
`HealthRecoveryProjector`'s wiring shape, not `OperationCompletionProjector`'s inline-callback
shape), so both the MCP and browser gate paths get advisory coverage from one subscription with
no edits to either call site. `PendingAuthorizationEvent` deliberately stays privacy-scoped
(routing info only, per tempdoc 444b) — `classExtras` carries `pendingId`/`operationId`/
`riskTier`/`gateBehavior`, never `requestedBy`. This became the 7th channel on the
`/api/shell-events/stream` multiplexer (a new `AdvisoryStreamController` + `ChannelSource`,
mirroring the two existing advisory classes' construction).

On the frontend, a new `windowFocus.ts` utility (sibling to `tauriRuntime.ts`/`notify.ts`)
resolves focus correctly per environment: Tauri's native `getCurrentWindow().isFocused()` inside
the desktop shell (confirmed correct via the Tauri-version-pinned API, since `document.hidden` is
confirmed unreliable there), `document.hasFocus()` in plain browser dev mode. The desktop-
notification trigger lives in `AdvisoryToastHost.onSnapshot`'s existing new-record loop — the one
place a genuinely new live record is already distinguished from reconnect replay — gated on
`sourceRenderHint === 'REQUIRES_ACK'` and an unfocused window. This is the one new conditional at
the correct existing integration point, not a new dispatch mechanism; any future REQUIRES_ACK
class gets the same treatment for free.

**A real bug found and fixed during live verification, worth recording as a lesson.** The
implementation initially passed every unit test (backend and frontend) but failed live: the
approval dialog didn't show "Requested by" and the new toast never appeared. Two independent
causes, both instructive:

1. The dev stack was started via `distFrom` without first running `./gradlew.bat
   :modules:ui:installDist` — the exact stale-jar pitfall already named in this repo's CLAUDE.md
   pitfall table. Unit tests exercise the compiled classes directly and can't catch "the running
   process is loading an old jar." Rebuilding the dist and restarting fixed the `requestedBy` gap.
2. The new advisory class was registered in `AdvisoryClassRegistry` (backend dispatch) and given
   its own SSE stream controller — but `AdvisoryResourceCatalog`'s `DEFINITIONS` list, which is
   what the frontend's `AdvisoryStore` uses to *discover* advisory Resources at all, is a separate,
   hand-maintained static list that doesn't derive from `AdvisoryClassRegistry`. A third class
   needs an entry in **both** places; the plan and every unit test exercised the projector/registry
   path directly and never caught the missing catalog entry, because nothing in that path depends
   on Resource discovery. Live-verification (a genuine browser hitting a genuine backend) is what
   surfaced it — a `[MultiplexedStream] frame for unregistered streamId` console warning gone once
   `AdvisoryResourceCatalog` got its third `advisoryResource(...)` entry. Fixed in
   `AdvisoryResourceCatalog.java`; both gaps are closed and re-verified live end to end (gated MCP
   call → live "Approval requested" toast with an unread badge → dialog shows "Requested by: Claude
   Code" → clicking the toast acknowledges it and clears the badge).

Not independently live-verifiable in this environment: the actual OS-level desktop-notification
popup, since the dev-stack browser flow runs in a plain Chrome tab, not the packaged Tauri webview
(`isTauriRuntime()` is false there by construction). Everything up to that boundary — the focus-
detection logic, the gating condition, the call into `sendDesktopNotification` — is unit-tested and
was confirmed to reach that boundary correctly; only the final native OS call itself is untested —
this gap was identified and accepted before implementation began, not discovered as a surprise.

## Critical-analysis fix: suppress the missed-approval advisory for non-MCP gates (2026-07-02)

A post-implementation critical-analysis pass on the work above found one substantive bug:
`PendingAuthorizationAdvisoryProjector` projected an advisory (toast + inbox badge, and — when
unfocused — a desktop notification) for **every** `PendingAuthorizationEvent`, but that event is
broadcast by both `McpToolSurface` (MCP) and `OperationsController` (the browser's own REST 428
path) onto the same shared `PendingAuthorizationChangeRegistry`. `pendingAuthorizationBridge.ts`
already documents and defends against this exact overlap for the *ceremony dialog* ("a gate that
ALSO reached a live REST caller... always true for the browser's own 428s... would otherwise be
presented twice" — hence its `handledIds` dedup); the new advisory path had no equivalent guard.
Confirmed both by reading `CoreTrustEvaluator`'s lattice (TRUSTED+HIGH and MEDIUM+MEDIUM/HIGH cells
still gate — this isn't an UNTRUSTED-only concern) and live: a direct `POST
/api/operations/core.rebuild-index/invoke` call (a HIGH-risk op, gates even for a TRUSTED source)
produced the ceremony dialog as expected but — before the fix — also produced a redundant
"Approval requested" toast for an action nothing outside that HTTP request had asked for.

**Fix:** threaded `TransportTag` (an existing enum already carrying an `MCP` value for exactly
this) onto `PendingAuthorization` and `PendingAuthorizationEvent`. `McpToolSurface` passes
`TransportTag.MCP`; `OperationsController` passes its already-resolved `provenance.transport()`.
`PendingAuthorizationAdvisoryProjector.project()` now returns `Optional.empty()` unless
`event.transport() == TransportTag.MCP` — MCP has no in-page synchronous responder, so it's the
only transport that needs the passive discoverability signal; a browser gate's ceremony dialog is
already the complete, sufficient signal for the user who just triggered it. Backend-only change;
the frontend has no visibility into transport and needed none. Re-verified live: the MCP flow
still produces the toast/badge/dialog exactly as before; the same `core.rebuild-index` REST call
now produces only the dialog, no toast.

## Two remaining first-questions closed (2026-07-02)

A follow-up confidence-building pass on the state of the whole tempdoc found two of the original
"first questions" still genuinely open, plus one small inaccuracy in shipped documentation. Both
are now closed; a third (client fixture coverage) remains an open product decision, deliberately
not resolved here — see below.

**The `listChanged` over-declaration (named by this tempdoc's own investigation pass, never
fixed) is now fixed.** Confirmed both candidate fixes before picking one: the 6-tool list
(`McpToolSurface#listTools`) and the 3-class advisory-resource list
(`AdvisoryResourceCatalog#DEFINITIONS`) are both fixed at compile time with no runtime-mutable path
today — `resources/subscribe` is a different capability (live updates within one already-known
resource's stream) and doesn't imply the resource *list* itself can change. The honest fix is
declaring `listChanged: false` for both `tools` and `resources` in `handleInitialize`
(`McpProtocolHandler.java`), not building a `notifications/*/list_changed` emission mechanism for a
capability that doesn't exist. Locked in with an explicit assertion in
`McpProtocolHandlerTest`. **This is only true as of today, not permanently**: tempdoc 660 (plugin
SDK community onramp, still an open design question as of this writing — "How should
plugin-contributed actions relate to MCP and operation policy?") could eventually let a plugin
contribute a runtime-registered tool or resource, which would make one or both lists genuinely
dynamic. If that ships, `listChanged` needs to flip back to `true` *and* the corresponding
`notifications/*/list_changed` emission needs to be built for real — worth checking this tempdoc
whenever 660's design lands, rather than rediscovering the same investigation. Also corrected a
small inaccuracy discovered while investigating this:
`check-mcp-conformance.mjs`'s own header comment claimed this exact bug was an example of what its
11 locked-in scenarios would catch — checking all 30 upstream scenario names (both
`PASSING_SCENARIOS` and `EXCLUDED_FIXTURE_SCENARIOS`) confirmed none of them exercise
list-changed-notification behavior at all. The comment now says so honestly instead of overclaiming
coverage the adopted suite doesn't have.

**"Token possession and trust-lattice gating are orthogonal" (theorization pass, "worth a
deliberate call either way") is now explicitly decided: intentional defense-in-depth, not a gap.**
The mechanism is `GET /api/mcp/token` plus session-token enforcement on `POST`/`DELETE /mcp`
(`LocalApiServer.java:559-562`, already documented in `docs/reference/security/threat-model.md`'s
"Session token on mutations" row) — a single startup-generated secret delivered only via the Tauri
bridge, proving a caller is a legitimate local process. That is authentication (is this caller
allowed to reach the API at all); the trust lattice's per-action consent is authorization (is this
specific action approved). These are legitimately different axes by design, the same shape as any
standard authn/authz separation — not an artifact of the two mechanisms having been built
independently without reconciliation, as the open question worried. Recorded explicitly in
`threat-model.md` itself (a new note directly under its mitigations table) so a future reader
doesn't read the existing token documentation as implying the token alone authorizes mutations.

**Client fixture coverage (Q2) remains deliberately unresolved.** Investigation confirmed no
record/replay or cassette-style test infrastructure exists anywhere in this repo to extend —
building this would be new test infrastructure from scratch, not a small addition to something
existing. More importantly, this tempdoc never committed to building it; the theorization pass
explicitly named it as an open cost/benefit question (which clients, how fixtures stay
representative as proprietary clients drift, whether the ongoing maintenance is worth it) rather
than a promised deliverable. That question is a product decision, not something further codebase
investigation can resolve — it stays open on purpose.

## Current state (as of 2026-07-02) — SUPERSEDED by the STATUS SUMMARY at the top of this file

> This section covers only the conformance + capability-policy era and predates the 2026-07-07
> agent-adoption legibility work. Read the "STATUS SUMMARY (2026-07-07)" at the top for the current
> picture; this remains as dated history.

This tempdoc has accumulated many dated passes above; this section is the up-to-date summary so a
future reader doesn't have to reconstruct it by reading the whole history top to bottom.

**Shipped and live-verified, against a real running server and a real browser, not just unit
tests:**
- MCP's mutating tool (`justsearch_ingest`) is wired into JustSearch's existing, transport-agnostic
  consent mechanism (pending-authorization records, single-use capsules, durable "allow always"
  grants) instead of the old dead-end `_confirmationToken` hint. All 6 MCP tools validate their
  arguments against a real JSON Schema at the boundary before dispatch.
- The upstream `@modelcontextprotocol/conformance` suite is adopted (not forked) as a manual/
  dev-stack-driven regression gate (`scripts/ci/check-mcp-conformance.mjs`), locking in the 11 of
  30 scenarios that measure real protocol behavior against JustSearch's curated tool surface.
- A pending MCP approval surfaces two independent ways: the same hardened approval dialog the
  browser UI already uses (triggered live even though the frontend never made the original
  request), and — for MCP calls specifically — a passive "Approval requested" toast/inbox badge
  plus an OS-level desktop notification when the window isn't focused, so a user who stepped away
  still finds out. The calling MCP client's self-reported name is shown in the dialog, display-only
  (it is never used for any trust or grant decision).
- Two real regressions were found by post-implementation critical review (not by the original
  design) and fixed: a privacy leak where argument content briefly reached a broadcast channel it
  shouldn't have, and a redundant notification firing for ordinary browser-triggered approvals (not
  just MCP ones). Both fixes are described in their own sections above with the reasoning.
- The `tools.listChanged`/`resources.listChanged` over-declaration this tempdoc's own first
  investigation pass found (a capability advertised but never actually honored) is fixed.
- The relationship between the MCP session token (`GET /api/mcp/token`, enforced on `POST`/`DELETE
  /mcp`) and the trust-lattice consent gate — previously an open question — is now explicitly
  documented as intentional, correct defense-in-depth (`docs/reference/security/threat-model.md`),
  not a gap.

**Deliberately not built, and why:**
- Client-specific fixture/replay test coverage (Claude Desktop, Claude Code, Cursor) — a real,
  named, ongoing maintenance cost with no committed decision that the benefit clears it. This is a
  product call, not a technical blocker; pick it up only after that call is made.
- MCP elicitation (the spec's own mid-call user-input primitive) as anything beyond a future
  possibility — host support is still uneven across MCP clients, so the local approval dialog stays
  the primary mechanism, with elicitation as a potential later enhancement layered on top.
- A generalized "answering surface" abstraction over the consent mechanism — only two caller
  surfaces (browser UI, MCP) exist today; the extraction point is a genuine third surface (a
  networked MCP transport, or the plugin marketplace in tempdoc 660), not before.

**Known limitation, not yet hit:** the `listChanged: false` fix above assumes both the MCP tool
list and the resource list are permanently static. That's true today. If tempdoc 660 (plugin SDK)
ever lets a plugin register a tool or resource at runtime, this decision needs revisiting — the
capability declaration would need to flip to `true` and an actual `notifications/*/list_changed`
emission path would need to be built, which does not exist today in any form.

**Unverified in this environment, disclosed rather than silently skipped:** the actual OS-level
desktop notification popup. The dev-stack browser flow used for all live verification in this
tempdoc runs the frontend in a plain browser tab, not the packaged Tauri desktop application, so
the native notification API is never actually exercised end to end — only up to the point of
calling it. Verifying the real popup requires running the built desktop app itself.

**If continuing this work:** the only genuinely open original question is client fixture coverage.
Everything else this tempdoc set out to do has a recorded, verified conclusion above.


---

# Adoption-zero finding (2026-07-07) — this tempdoc now owns the discoverability/steering lever

> Source: tempdoc 624 pass 25 (Step-1 adoption pilot; artifacts
> `scripts/jseval/624-run-2026-07-07-pilot/`). Routed here by the pass-24 pre-registered
> interpretation tree ("null with low adoption → not a utility finding — an adoption finding;
> route to 655").

## The finding

With the full `mcp__justsearch__*` surface **verifiably offered** (per-cell init-event assertion,
eager surfacing at CLI 2.1.202, neutral non-priming prompt), a haiku-class agent holding ordinary
file tools made **zero MCP calls in 10/10 cells (174 tool calls total)** on a 390-doc multi-hop
corpus, brute-forcing 9/10 answers via Bash/Grep/Read at 8–31 calls per cell. Offering the
retrieval surface changed nothing about agent behavior. Corroborating texture from the 2026-07-07
smoke (util-smoke, condition C): even when *forced* onto the MCP-only surface, cells reached
`justsearch_search` only after ToolSearch flailing, and **no cell has ever called
`justsearch_answer`** — the surface's own designated primary QA tool — in any run on record.

## Why this is a 655 subject

Adoption is a property of the tool surface as presented to the model at decision time — names,
descriptions, server `instructions`, prompts/resources — which is exactly the surface this tempdoc
certifies and governs. Candidate mechanisms to investigate (hypotheses, not conclusions):
- **Descriptions may not reach the decision point.** Deferred-tool clients see descriptions only
  after loading; even eager clients may weigh names over long description bodies. The
  `justsearch_answer` description's "primary tool for question-answering" guidance has never once
  produced a `justsearch_answer` call.
- **Naming semantics vs. habit priors.** `Grep` is a verb the model uses reflexively;
  `justsearch_search` is a branded noun. The agent's prior (file tools solve file questions) may
  dominate any wording.
- **No server-level steering surface is exercised.** MCP `initialize` carries an `instructions`
  field; the onboarding prompts/resources exist but nothing in a cell ever surfaces them.

## Boundary (measurement integrity — hard constraint)

The eval-side cell prompt is FROZEN (pre-registered neutral text, 624 pass 24 §2). Adoption must be
moved **only via the product surface** (tool names/descriptions/annotations, server instructions,
prompts/resources) — that is the product improvement being measured. Any eval-prompt steering is
outcome-reshaping and invalidates the comparison. Success metric: the before/after
`adoption_rate` / `first_mcp_call_index` / `mcp_call_share` delta on a re-run of the 624 Step-1
pilot (~$3/iteration, machinery ready on main), reported as its own result regardless of direction.
The 624 Step-2 utility run stays gated until adoption is measurably non-zero.

---

# Takeover investigation of the adoption lever (2026-07-07) — grounding + verdict, no code written

A fresh takeover pass focused on the adoption-zero finding this tempdoc now owns. Goal: verify the
finding against its own artifacts, test its stated hypotheses against the code and the run data, and
end with an explicit "should this be done, and now" verdict — not to begin implementation. Every
claim below is anchored to a primary source read this pass.

## What was verified free, from artifacts already on disk

- **The finding reproduces exactly.** `scripts/jseval/624-run-2026-07-07-pilot/logs/…agent-utility-task….json`,
  parsed cell-by-cell: 10/10 B cells, **174 tool calls (75 Grep, 61 Read, 38 Bash), 0 `justsearch_*`
  calls**, 8–31 calls/cell, accuracy 0.9. `mcp_tools_offered=6` and `mcp_servers=[{justsearch,
  connected}]` on all 10 cells. The finding's headline numbers are accurate, not rounded or narrated.
- **The "deferral" sub-hypothesis is empirically false for this cohort.** The finding's candidate
  mechanism #1 ("deferred-tool clients see descriptions only after loading") does not apply here:
  `mcp_tools_deferred=False` on every cell (cohort `tool_surfacing_mode: eager`, CLI 2.1.202). The
  six tools *and their hand-written descriptions were in-context at decision time* and the agent
  chose Grep anyway. So the live lever is not "fix deferral" — it is the harder "descriptions were
  read and lost to a habit prior," which is a weaker thing for copy edits to move.
- **The server `instructions` field is genuinely absent.** `McpProtocolHandler.handleInitialize`
  (lines 133–147) returns `protocolVersion` / `capabilities` / `serverInfo` and **no `instructions`
  key**. The finding's candidate #3 is real and code-confirmed.
- **`prompts/*` and `resources/*` handlers exist** (`McpProtocolHandler` 80–85, `McpToolSurface#listPrompts`
  734) — so the "onboarding prompt/resource surface" the finding references is present but unexercised.

## Two corrections to the finding's own framing, from reading the evidence

1. **Lever triage: two of the three named candidates are structurally weak; one is both untried and
   capable of reaching an autonomous agent.** MCP *prompts* are, by protocol design, **user-invoked**
   (they surface to the human as slash-commands/templates; an autonomous agent client never calls
   `prompts/get` unprompted). So "surface onboarding via prompts/resources" cannot move autonomous
   adoption on its own — it addresses a human-in-the-loop path this eval doesn't exercise. Of the
   three levers, tool *descriptions* were already eagerly surfaced and ignored (above), which leaves
   the server **`instructions` field** as the single lever that is both (a) genuinely untried and (b)
   structurally injected into the agent's system context by real clients at connect time. If any
   description-tuning campaign is run, `instructions` is the higher-leverage, lower-cost thing to try
   first, and copy-tuning descriptions that were just shown to be read-and-ignored is the lower-leverage
   half.

2. **The adoption metric on this corpus has near-zero accuracy headroom, so "adoption unlocks utility
   measurement" is weak here.** The A-arm/grep baseline already scores **0.9** on battlefield-en-v1
   (390 docs, English, multi-hop). Even *perfect* MCP adoption leaves ≤1/10 of accuracy to win on
   this corpus; the only axis with room is cost/call-count. Steering work validated **solely** against
   condition-B `adoption_rate` on this corpus can therefore move the proxy (adoption) without any way
   to show it moved the goal (better/cheaper answers). This is the classic proxy-optimization trap the
   measurement-integrity boundary (freeze the eval prompt) does *not* protect against.

## The one signal that survives every confound

The 2026-07-07 smoke's condition-C texture — even when *forced* onto the MCP-only surface, cells
reached `justsearch_search` only after ToolSearch flailing and **no cell has ever called
`justsearch_answer`**, the surface's own designated primary QA tool, in any run on record — is not a
choice artifact. When a tool is the *only* option and still isn't found/used, that is a genuine
interface-discoverability defect, independent of corpus scale or grep-competitiveness. This part is
squarely 655's domain and is worth fixing regardless of how the adoption question resolves.

## The competing explanation the finding does not rule out

The finding, and 624's pre-registered tree, both route "null + low adoption" straight to "steering
problem → 655." But there are two live explanations for zero adoption and only one is a defect:
- **(a) Discoverability/steering defect** — the agent would benefit but doesn't realize it. Fixable
  by 655 surface work.
- **(b) Rational non-adoption at this scale** — at 390 grep-able docs an agent that scores 0.9 with
  Grep is making a *correct* call to ignore a branded search tool. Not a defect; 655 copy work would
  be optimizing a proxy.
The default JustSearch deployment (Claude Code, which ships Grep/Read/Bash, + the JustSearch MCP) is
exactly condition B, so (b) is not an artificial scenario — but neither is it ruled out. 624's tree
only reaches for the **scale** lever on the *high*-adoption null branch; it never tests scale against
the *low*-adoption branch, which is where it would actually discriminate (a) from (b). The cheapest
experiment that separates them is a corpus-hardness variant of the same pilot (grep baseline pushed
down to ~0.5 by a larger / less-grep-able corpus): if adoption stays 0 at scale → (a), a real defect,
now measurable on a corpus with accuracy headroom; if adoption rises at scale → (b), and the honest
product move is positioning ("JustSearch for corpora too big to grep"), not copy edits. That machinery
is 624-owned and ~$3, the same envelope as the steering re-run this tempdoc already budgets.

## What this displaces or duplicates

- **tempdoc 366** (tool-interface-design eval) authored the current descriptions — 655's copy-tuning
  is a *second iteration on 366's output*, justified only because 366's descriptions now measure at 0
  adoption. Not new territory; a re-open of settled work with a new reason.
- **tempdoc 656** (five-minute agent runtime onramp) and **654** (runtime contract / product center)
  own the onboarding-surface story; the "onboarding prompt/resource" lever named in the finding is
  closer to 656's remit than 655's.
- The **server `instructions` field** is the only genuinely net-new surface — it duplicates nothing.

## Verdict

**Should this be done at all?** Partly. Split the owned scope in two:
- **Do now, narrowly (real defect, no confound):** fix the interface-discoverability defect that shows
  even under *forced* use — `justsearch_answer` never invoked, `justsearch_search` reached only after
  flailing. Prime candidate lever: add the absent server `instructions` field. This is defensible on
  its own evidence and does not depend on the adoption metric being sound.
- **Do NOT yet, as framed:** a description-copy-tuning campaign validated against condition-B
  `adoption_rate` on battlefield-en-v1. That target has near-zero accuracy headroom (0.9 grep
  baseline) and has not been separated from the rational-non-adoption explanation.

**Should it be done now?** The narrow interface-defect half: yes. The broad adoption-steering half:
not before one cheap discriminating experiment.

**Cheapest evidence that validates/invalidates the need — does it exist?** Two tiers:
- *Already exists, free:* the pilot's own 0.9 grep baseline. It alone establishes that condition-B
  `adoption_rate` on this corpus cannot demonstrate accuracy utility, which invalidates using it as the
  *sole* success metric. No new spend needed to know that.
- *Cheap, does not yet exist (~$3, 624 machinery on main):* the corpus-hardness variant of the pilot
  that separates "discoverability defect" from "rational non-adoption." This is the single highest-value
  next spend, and it is a 624 experiment, not a 655 code change — worth running *before* 655 commits
  iterations to copy tuning.

**What it displaces/duplicates:** the description-tuning half re-opens tempdoc 366; the onboarding-prompt
lever overlaps 656; only the server `instructions` field is net-new.

**Net:** this tempdoc's *original* MCP conformance + capability-policy body is shipped and sound and
needs nothing. The *newly-inherited* adoption lever is a real finding but is framed one hypothesis too
narrowly: it pre-commits to "steer the agent via copy" before ruling out "the agent is right to ignore
a search tool on a grep-able corpus." Recommend (1) ship the `instructions`-field / interface-defect fix
now on its own merit, and (2) gate the description-tuning campaign behind the ~$3 corpus-hardness pilot
that tells us whether adoption-zero is a defect or an artifact. Neither step is authorized to begin until
the owner says go.

---

# Theorization pass on the adoption lever (2026-07-07) — directions, reframings, tensions (nothing decided)

Written before any design commitment, to surface options and hidden assumptions a design pass should
weigh rather than rediscover. Everything below is a possibility or a tension, not a ruling.

## Reframing the problem — three different problems wear one label

1. **From "persuade the agent" to "signal the boundary honestly."** The current frame ("adoption is
   zero; move it via the surface") implicitly treats non-adoption as a persuasion failure. A more
   durable frame: the surface's job is to make the *decision boundary* legible — the conditions under
   which the tool actually beats grep (corpus larger than the agent can scan, paraphrase/semantic
   queries keyword-grep misses, cross-lingual, entity/facet-scoped retrieval). A description that
   states "prefer me WHEN X; grep is fine WHEN Y" makes a rational agent adopt *exactly when adoption
   helps* — which, crucially, re-couples `adoption_rate` to utility and rescues it as a valid proxy
   (the decoupling flagged in the verdict above is a symptom of feature-forward, not
   boundary-forward, copy). Today's descriptions are feature-forward ("supports hybrid/text/vector
   modes, querySyntax LUCENE, facets…"); none states when *not* to use the tool.

2. **From "documentation problem" to "competition-under-a-prior problem."** An autonomous agent
   selects tools under a strong training prior in which "grep the files" is overwhelmingly
   represented, and under first-call uncertainty (grep is a known-safe quantity; a branded tool might
   return nothing, be slow, or need setup). You are not competing on description quality against a
   blank slate — you are competing against the model's prior and against zero-risk familiarity. That
   reframes the levers: naming that *rides* the prior rather than fighting it, position in the tool
   list, and lowering the perceived cost/risk of the *first* call may all matter more than richer
   prose. Once an agent commits to a grep path it rarely revisits, so the whole contest is decided in
   the first one or two tool selections.

3. **From "did it call the tool" to "did the tool change the outcome."** `adoption_rate` (cells with
   ≥1 MCP call) is binary and hollow-able: an agent that calls `justsearch_search` once, gets
   confused, and falls back to grep counts as "adopted" while deriving nothing. A metric shape worth
   pre-registering alongside it: *adoption that changed the trajectory* (tool call followed by an
   answer the agent kept, or a measurable drop in total calls). Otherwise a copy edit can move the
   headline number without moving anything real.

## Hidden assumptions worth surfacing before they get baked in

- **Model tier.** The finding is haiku-only. Smaller models lean harder on habitual tool priors and
  under-explore; a frontier agent (the product's actual blast radius — Claude Code on Sonnet/Opus with
  the JustSearch MCP) may adopt spontaneously. If so, "adoption is zero" is partly a small-model
  artifact and the surface work should target the tier that actually underperforms. The cheapest
  disambiguation is a single Sonnet cell: if it adopts, the problem is "steer small models"; if it
  ignores the tool too, the surface itself is implicated. This assumption is currently untested and
  cheap to test.
- **Single-harness overfitting.** Adoption is measured through one stack (inspect-ai + one CLI version
  + haiku). The MCP surface is a *public contract* consumed by many clients (Claude Code, Cursor,
  Claude Desktop) and future models. Tuning copy until *this* harness adopts risks a local optimum
  that doesn't transfer and may rot as clients/models change. Mitigation principle: change only things
  that are principled in their own right (an honest decision boundary, the absent `instructions`
  field) rather than harness-specific incantations that happen to move one number.
- **More copy is not free.** ADR-0015 measured that *optional-parameter schema bloat hurt small-model
  accuracy* — the schemas were hand-minimized deliberately. Persuasive description prose is the same
  category of cost: more tokens in the tool surface can degrade the very small models the campaign
  targets. "Add more convincing copy" may be self-defeating; shorter, front-loaded, decision-first
  descriptions are the hypothesis more consistent with the existing evidence than longer ones.
- **The grep baseline is the north star, not the enemy.** Fighting to win adoption on corpora where
  grep is genuinely adequate optimizes against the product's own honesty. Defining the regimes where
  JustSearch *structurally* wins (scale beyond the agent's scan budget, semantic/cross-lingual,
  entity-scoped) and making those the eval strata, the tool descriptions, AND the product narrative —
  one aligned story — is likely more valuable than any copy tuned to a grep-competitive corpus.

## A menu of surface levers (ordered by leverage-per-cost, not decided)

- **Server `instructions` field (untried, structurally reaches the agent).** Most clients inject it
  into system context at connect time. Content should be a decision rule, not a plea: "this machine
  has a local index of the user's files; for questions over the indexed corpus, `justsearch_answer`
  returns cited passages across documents in one call — prefer it to reading files individually when
  the corpus is large or the question spans multiple documents."
- **Front-load / compress the tool descriptions** so the first sentence is the decision boundary, and
  test *shorter* against *longer* rather than assuming richer wins (per the ADR-0015 caution).
- **Naming.** `justsearch_answer` never gets called; a name that evokes the action at the name level
  may outweigh a 300-word body for tool selection — but names are a versioned contract surface (654),
  so naming churn has downstream cost and should be weighed, not done reflexively.
- **MCP tool annotations** (`readOnlyHint`, etc.) — cheap, honest, MCP-native, and may nudge
  client-side tool-preference heuristics without touching prose.
- **Progressive disclosure in the first response / status** — teach the agent what the tool is good
  for via the first result it sees, lowering first-call uncertainty.
- **Explicitly NOT a lever for autonomous adoption:** the `prompts/*` surface. MCP prompts are
  *user-invoked* by protocol (they surface to the human as slash-commands/templates and inject a
  directive + status context — see `McpToolSurface#getPrompt`); an autonomous agent never calls
  `prompts/get`. Improving them helps the human-in-the-loop onboarding path (closer to tempdoc 656's
  remit) but cannot move the autonomous `adoption_rate` this finding measures.

## Candidate principle / recurring system shape (named, not built)

- **The public-surface triad: correct + safe + legible.** This tempdoc's original spine is conformance
  (the surface speaks the protocol correctly) and capability policy (the surface is safe for an
  untrusted caller). The adoption finding exposes a third, co-equal property the same surface must
  have to matter: **legibility** — it must be *legibly better, at the moment of choice, exactly when
  it is better*. Correct + safe + ignored is commercially inert. The three are one surface's three
  obligations, not three projects.
- **A tool description should encode a decision boundary, not a feature list.** Candidate invariant,
  worth testing against the other tools and the REST surface before asserting: an external-facing
  capability's self-description should tell a rational caller *when to choose it and when not to*, so
  that adoption tracks utility by construction. This is the same "align the metric with the goal"
  move, expressed at the surface layer.
- **Latent capability, unwired surface — a repeating pattern in this codebase.** The consent mechanism
  existed but MCP wasn't wired to it; `sendDesktopNotification` existed with zero callers; the
  protocol's `instructions` field exists but `handleInitialize` never sets it; prompts/resources exist
  but no agent path surfaces them. The recurring corrective (this tempdoc's own thesis, now a fourth
  time) is *wire the existing capability to the surface that exercises it* before building anything
  new. Adoption is the same shape one level up: the value exists; the surface doesn't yet make it
  reachable at decision time.

## Ideas worth banking for later (not now)

- **The eval harness is standing A/B infrastructure for the surface, not a one-off.** Once the
  before/after adoption pilot exists, *any* future tool-surface change (naming, a new tool, a
  description edit) can be regression-tested for adoption impact. That reusability is itself a durable
  asset worth naming — the surface acquires a measurement rig, so surface evolution stops being
  guesswork.
- **Client-conditional `instructions`.** 655 already captures `clientInfo.name` (display-only today).
  A future `instructions` field could be tailored per client without new plumbing — but note the same
  discipline the earlier passes settled: `clientInfo` is a hint, never a trust key; conditioning
  *guidance* on it is fine, conditioning *policy* on it is not.
- **Adoption as a positioning question, not only a copy question.** If the corpus-hardness experiment
  shows non-adoption is rational at small scale, the honest deliverable is not a copy campaign but a
  documented statement of when the tool earns its place — which then flows back into the description
  boundary, the eval strata, and the product narrative as one coherent story.

## Cross-tempdoc boundary (to prevent the same question being half-answered in five places)

The adoption question sits where 624 (measurement / corpus strata), 654 (product-center thesis), 656
(human onboarding onramp), 366 (tool-interface eval that authored today's descriptions), and 660
(plugin surface) meet. A crisp division keeps the copy work from sprawling: **655 owns the surface as
presented to an autonomous agent at decision time** (names, descriptions, annotations, server
`instructions`); **624 owns whether and when the tool actually helps** (utility, corpus hardness);
**656 owns the human onboarding path** (prompts/resources, first-run). The corpus-hardness experiment
that discriminates "defect" from "rational non-adoption" is a 624 experiment whose *result* gates
655's copy work — the two are sequenced, not merged.

---

# External research pass on the adoption lever (2026-07-07) — checking the moving targets

The conformance/capability half of this tempdoc had a landscape check (see "External landscape check
2026-07-02"); the newly-inherited adoption lever had none, and my theorization above rested on several
claims about externally-owned, fast-moving targets (MCP client behavior, agent tool-selection research,
the grep-vs-RAG debate) asserted from training knowledge rather than checked. This pass checks them. It
**corrects two of my own earlier claims** — recorded rather than scrubbed. Nothing external was copied
into the repo; sources are cited by URL for attribution only (no code/text/assets vendored, so the
license-and-notices check has nothing to flag here).

## Finding 1 — the `instructions` field is real and reaches the flagship client, but is client-uneven

The MCP spec defines an optional `instructions` field on the `initialize` result — "instructions
describing how to use the server and its features," which a client "may add to the system prompt." The
spec does **not** mandate how a client uses it (implementation-defined). In practice: **Claude Code
injects it into the system prompt and respects it consistently**; VS Code (Copilot Chat) and Goose
inject it; **Claude Desktop does not yet consume it** (tracked as an open anthropics/claude-code issue
to add exactly that); Cursor's handling varies. This is the same uneven-support shape the elicitation
research earlier in this tempdoc found — but it lands *favorably* for the adoption lever, because the
624 eval runs through the **Claude Code CLI**, the one client that honors `instructions` reliably. So
of the three candidate levers, the server `instructions` field is confirmed both untried *and*
structurally effective **in the exact client the measurement uses** — the strongest single reason to
try it first. (Sources: [MCP spec — lifecycle/initialize & prompts](https://modelcontextprotocol.io/specification/2025-06-18/server/prompts),
[cline discussion #3114 on injecting server instructions into the system prompt](https://github.com/cline/cline/discussions/3114),
[anthropics/claude-code issue #43749 — Claude Desktop should consume the `instructions` field](https://github.com/anthropics/claude-code/issues/43749),
[MCP server-instructions primer](https://sudoall.com/mcp-server-instructions/).)

## Finding 2 — "prefer grep over RAG" is the frontier consensus; it strongly grounds the rational-non-adoption reading

This is the most consequential finding and it *strengthens the verdict's caution*. Anthropic itself
**replaced Claude Code's original vector-RAG pipeline with agentic grep** because the agentic approach
"outperformed everything. By a lot" (attributed to Claude Code's creator); Cursor and Devin are
reported to make the same choice. The analytical framing that best fits JustSearch's situation is the
**cost-curve** model: `total_cost = build_cost + maintain_cost × time + per_query_cost × queries`, in
which for "most small-to-mid-size repos the crossover is never reached" — the index's build/maintenance
cost never amortizes, so grep wins. The same source names the three regimes where **indexed retrieval
does win**:
1. **Very large corpora** (its example is 100M+ line monorepos) where index cost amortizes over query volume;
2. **Semantic queries where there is no specific symbol to grep** — conceptual/edge-case/"what discusses X" questions;
3. **Context-scarce models**, where one-shot retrieval preserves context that multi-round grep would burn.

Three implications for this tempdoc, in order of importance:
- **The battlefield-en-v1 pilot sits squarely in the "crossover never reached" regime** (390 docs, keyword-findable). An agent choosing grep there is behaving as the cost curve predicts is *optimal*, not making a discoverability mistake — this is external, independent corroboration of the "rational non-adoption" branch the verdict said 624's tree never tests.
- **It gives a precise, externally-grounded definition of when JustSearch structurally wins**, which is exactly the decision boundary a description should encode: *large corpus, OR semantic/no-exact-keyword query, OR a context-scarce agent.* JustSearch is a **document** product, and natural-language questions over prose hit the "no specific symbol" regime far more often than code lookups do — so its structural advantage is real, but it lives on the **query-type and scale axes, not on keyword lookup over a small corpus.**
- **A new eval-design critique falls out:** the battlefield corpus tests recall of *fabricated proper-noun facts*, which are precisely the keyword-findable case grep is best at — so the eval may be adversarially easy for grep and understate JustSearch's semantic/paraphrase advantage. A corpus whose questions are semantic (no literal keyword present in the target passage) would test the regime where adoption *should* rationally occur. This is a 624 corpus-design point, surfaced here because it directly shapes whether the adoption metric can ever move for the right reason. (Sources: [Anthropic replaced their RAG pipeline with agentic search — analysis](https://robertheubanks.substack.com/p/anthropic-replaced-their-rag-pipeline),
[HarrisonSec — "Agent Retrieval Is a Cost Curve Problem: Why Claude Code Doesn't Use RAG"](https://harrisonsec.com/blog/agent-retrieval-cost-curve-claude-code-grep-vs-rag/),
[Milvus — a dissenting "grep burns too many tokens" cost argument](https://milvus.io/blog/why-im-against-claude-codes-grep-only-retrieval-it-just-burns-too-many-tokens.md).
The dissent matters: the token-cost-of-grep counterargument is itself the "context-scarce model" regime above, and it is the axis on which a retrieval tool most plausibly beats grep even at modest scale.)

## Finding 3 — Anthropic's tool-writing guidance corrects one of my earlier claims and qualifies another

I fetched Anthropic's [*Writing effective tools for AI agents*](https://www.anthropic.com/engineering/writing-tools-for-agents) (the authoritative primary source) against the theorization pass's hypotheses:
- **Correction — "shorter/front-loaded descriptions" is NOT what Anthropic recommends.** Their guidance
  favors *richer* descriptions: "think of how you would describe your tool to a new hire… Consider the
  context that you might implicitly bring… and make it explicit." My theorization floated *shorter*
  descriptions as the better-supported hypothesis; against Anthropic's general guidance that is
  backwards. **But it does not simply flip**, because ADR-0015's *own* small-model eval measured that
  optional-parameter surface *hurt* accuracy — so the honest position is a genuine, model-tier-dependent
  tension: frontier-model guidance says richer; JustSearch's small-model evidence says leaner. This
  reinforces the model-tier hidden assumption (the pilot is haiku-only) rather than resolving it — and
  means "shorten the descriptions" must not be adopted as an assumed win; it is a hypothesis to test on
  the actual target tier, exactly as Anthropic says naming choices must be.
- **Qualification — the "decision-boundary description" principle is half-endorsed, half mine.** Anthropic
  does say to "be explicit about when to use each tool" (the positive half). It does **not** address
  stating when *not* to use a tool / when to prefer the alternative — that "and grep is fine when Y"
  refinement, which is what makes `adoption_rate` track utility, is my own extension, not established
  guidance. Recording that honestly so a future reader doesn't over-credit it.
- **Confirmations:** naming/namespacing has "non-trivial effects on tool-use evaluations… Effects vary
  by LLM… choose a naming scheme according to your own evaluations" — directly supports both the
  eval-driven approach *and* the single-harness-overfitting caution (you must eval on your target, yet
  your target is many clients). And "more tools don't always lead to better outcomes… a few thoughtful
  tools" endorses JustSearch's already-curated surface (ADR-0015). There is also active academic work on
  exactly this problem — [*MCP Tool Descriptions Are Smelly! …Augmented MCP Tool Descriptions*](https://arxiv.org/html/2602.14878v1)
  (2026) — signalling that "how tool descriptions drive selection" is a live research area, not settled,
  which is itself a reason to treat any copy change as a measured experiment rather than a fix.

## Net effect of the research pass on the earlier passes

- The **verdict stands and is strengthened**: the "rational non-adoption at grep-able scale" reading is
  now backed by the frontier labs' own published choice and a cost-curve model, not just internal
  reasoning. The corpus-hardness experiment the verdict called the highest-value next spend is confirmed
  as the right discriminator, and gains a sharper design target (semantic/no-literal-keyword questions,
  not just "more docs").
- The **`instructions`-field-first recommendation is strengthened**: confirmed effective in the exact
  client the eval uses.
- **Two theorization claims are corrected/qualified**: "shorter descriptions" is against general guidance
  (kept only as a small-model-specific, must-test hypothesis), and the "when-not-to" half of the
  decision-boundary principle is my extension, not industry-established.
- No direction is reversed; the option space is the same, with two hypotheses down-weighted and two
  recommendations more firmly grounded.

---

# Design pass on the agent-adoption legibility lever (2026-07-07) — settled at the design level, not built

A design pass that grounds the adoption lever against what already exists in the codebase and in the
adjacent tempdocs (654, 656, 366, 624), so the design extends existing seams rather than forking new
ones. Design level only — exact copy, field wiring, and version mechanics belong to whoever implements
this. Two prior claims of mine are corrected by the codebase evidence; recorded, not scrubbed.

## The problem, corrected by evidence — the surface is not broken

The single most important correction: **the MCP tool surface is not broken, and zero adoption is not
proof that it is.** Tempdoc 366 (which authored the current descriptions, via a 50-question Haiku
agentic eval) measured **~38% adoption of `justsearch_answer`** in its own harness. The 624 pilot
measured **0%** — but in a materially different setting: the agent also holds file tools (Grep/Read/
Bash) and the corpus (390 keyword-findable docs) is one where, per the external cost-curve research
above, grep is the rational choice. The gap between 38% and 0% is precisely the *competition-under-a-
prior on a grep-competitive corpus* effect, not a regression in the descriptions. This reframes the
whole lever: the job is **not** "fix the broken surface." It is "make the surface's *comparative*
advantage legible, and measure adoption where that advantage actually exists." Two coupled deficiencies
follow, and the design must address both or it will optimize a proxy:

- **(A) Legibility deficiency (655 owns).** No server-level steering reaches an autonomous agent's
  decision context at all — the MCP `initialize.instructions` field is absent (`McpProtocolHandler`
  returns only `protocolVersion`/`capabilities`/`serverInfo`). The steering content that *does* exist
  (`McpToolSurface#getStatusContext` — doc count, enrichment coverage, and a guidance tail) is wired
  **only into the user-invoked prompts**, which an autonomous agent never calls. And the guidance that
  exists is *feature-forward* ("Use justsearch_answer for questions…"), not *comparative* ("prefer the
  index over reading files one-by-one WHEN the corpus is large or the question is semantic").
- **(B) Measurement-validity deficiency (624 owns; gates 655).** Adoption is being measured on a
  grep-competitive corpus where a rational agent *should* decline. On that corpus the metric cannot
  distinguish "legibility fixed" from "corpus finally favors the tool," and worse, it can be moved by
  copy that manufactures adoption a rational agent shouldn't give.

## The design: an agent-legibility layer, built by wiring and reframing what exists

Name it the **agent-legibility layer** — the third obligation of the MCP surface alongside conformance
(speaks the protocol correctly) and capability policy (safe for an untrusted caller). It is deliberately
a *wiring + framing* design, not new machinery, because the deficiency is that existing signals aren't
connected to the surface the agent reads — the same shape this tempdoc has hit three times already.
Three coordinated moves, each conforming to an existing seam:

**Move 1 — Populate the `initialize.instructions` field as a single-sourced projection of the steering
signal that already exists.** This is the one server-level slot that reaches an autonomous agent's
system context, and the research pass confirmed the eval's own client (Claude Code) injects and honors
it. Its content is the *comparative decision boundary* — when the local index beats brute-force file
reading (large corpus / semantic or paraphrase questions with no exact keyword / when preserving the
agent's context budget matters) — plus the live index-status line `getStatusContext` already assembles.
Critically, this **extends** `getStatusContext` (promotes its output from the user-only prompt path to
the agent-visible initialize path), and per 654's "projection, not fork" principle it must be
**single-sourced** so the prompt path and the instructions field cannot drift into two hand-authored
copies.

**Move 2 — Make the guidance *comparative*, and place it where 366's eval proved placement works.**
366 established, with measured evidence, that (a) rich descriptions and extra schema params **hurt** the
small target model (a severe accuracy regression; "invisible to Haiku"), and (b) *"agents read
descriptions once at session start and forget specifics by turn 5 — put workflow guidance in tool
responses, not descriptions."* So the comparative framing must NOT be crammed into longer descriptions
(that repeats 366's proven mistake). It belongs at the two placements 366's evidence endorses: the
**connect-time `instructions` field** (Move 1 — the natural home for one-time orientation) and **tool
responses** (re-read every turn — where a hint like "this answer drew on N documents; reading them
individually would have been M file calls" makes the comparative advantage self-evident *after* first
use). Description text stays minimal and leads with the comparative "prefer me when…" clause; schemas
are untouched (ADR-0015 / 366 forbid additions without eval proof). This conforms to and extends 366's
progressive-disclosure pattern rather than replacing it.

**Move 3 — Validate on a corpus where the advantage is real, using 624's existing machinery.** The
surface change is measured by 624's pre-registered adoption triple (`adoption_rate`,
`first_mcp_call_index`, `mcp_call_share`) with the mandated dual-view (intention-to-treat + per-protocol)
reporting — but the decisive run is on **624's already-designed "scale member" corpus** (§1 Step 3 of
624: ~2–4k docs, where the file-tool agent's search-output budget explodes), not only the grep-able
battlefield-en-v1. The honest success signal is a **two-corpus contrast**: legibility should *raise*
adoption on the scale corpus (where the index wins) while *not* inflating it on the grep-able corpus
(where a rational agent still declines). That selective movement — not a raw adoption bump — is what
shows the layer works *for the right reason*. This is coordination with 624, not new eval code: 655
authors the surface, 624 owns the corpus and harness, and the before/after delta is the shared,
pre-registered, publishable deliverable.

## Conformance to existing seams (what this design does NOT reinvent)

- **Tool-surface versioning is 654's, already shipped.** The surface change is material, so it bumps
  `McpContractVersions.TOOL_SURFACE_VERSION` (currently `0.1.0`, explicitly pre-1.0 / still-settling per
  654's under-promise stance) — which by construction propagates to MCP `serverInfo.version` and the
  runtime manifest's `mcpToolSurfaceVersion`. No new version field; conform to the single-sourced one.
- **"Projection, not fork" is 654's principle** — the steering content is single-sourced (Move 1).
- **Progressive disclosure is 366's pattern** — extended, not replaced (Move 2).
- **The adoption metrics, neutral-prompt freeze, dual-view reporting, interpretation tree, and
  surface-verification assertions are 624's** — reused wholesale (Move 3). The "move adoption only via
  the product surface, never the eval prompt" boundary is 624's pre-registered rule and is honored.
- **Conformance framing stays honest** — per 654's D8 fence, nothing here claims "certified" or
  "conformant"; the legibility layer is a product-quality change measured by its own adoption run, not a
  compliance claim.

## Deliberately out of scope (structure the present problem does not require)

- **Tool renaming.** Names carry semantic weight (366), are keyed by the eval's surface-verification
  assertions, and are part of 654's versioned surface. The evidence does not isolate names as the
  blocker (descriptions were shown and the agent had a *rational* reason to decline). Renaming stays a
  hypothesis to test *only if* Moves 1–3 fail to move the scale-corpus adoption — not a committed change.
- **`/.well-known/mcp/server-card.json` auto-discovery (SEP-1649) and an agent-facing doctor via
  `justsearch_status`** — both are real, 655-assigned levers handed over from 656's backlog, but they
  address *connection/readiness* friction (finding and health-checking the server), a different problem
  than the *choose-to-use* deficiency this design targets. Recorded as adjacent 655 work, not folded in.
- **Per-client `instructions`, MCP elicitation as a primary path, and any generalized "steering
  framework."** No third consumer and no present need; earlier passes already fenced these.

## What this design orphans (removal belongs to THIS tempdoc, not a later sweep)

- **The feature-forward guidance tail inside `McpToolSurface#getStatusContext`** — `"Use
  justsearch_answer for questions, justsearch_search for exploration, justsearch_status for detailed
  health."` Once the comparative decision-boundary steering becomes the single-sourced canonical string
  (Move 1), this imperative-enumeration phrasing is superseded. If it is left in place, the prompt path
  and the instructions field become two divergent copies — exactly the fork 654's projection principle
  and this tempdoc's own "second channel" lesson (the 559 toast-collapse) warn against. Its
  replacement-by-single-source is part of this design's work, not a follow-up.
- **Nothing else is displaced.** Position-bias ordering (`justsearch_answer` first) is a committed,
  evidence-backed lever (366 / ToolTweak) and stays. Descriptions are reframed, not removed. Schemas are
  untouched. `serverInfo.version` was already de-literalized by 654.

## Reach and principle

**This design is an instance of a shape this tempdoc has already named — conform, don't re-name it.**
It is the fourth occurrence of *latent capability, unwired surface*: the consent mechanism existed but
MCP wasn't wired to it; `sendDesktopNotification` existed with no caller; and now the steering signal
(`getStatusContext`) and the decision-boundary knowledge both exist but aren't wired to the surface an
autonomous agent reads. The corrective is always the same — wire the existing capability to the surface
that exercises it, single-sourced — and it composes with 654's "projection, not fork." No parallel
principle is needed.

**But the design also reveals a sharper principle worth naming plainly, because it reaches past MCP:**

> **A capability surface must make its *comparative* advantage legible at the consumer's decision point,
> in the consumer's decision terms — and an adoption metric for that surface is only valid on inputs
> where the advantage actually exists.** Correct + safe is not sufficient; a surface whose advantage is
> not legible *where and when the consumer chooses* is functionally invisible, and a surface measured
> where it has no advantage measures the input, not the surface.

Two halves, deliberately joined: the **legibility** half (say *when to prefer me over the alternative
the consumer already holds*, at the decision point, not a feature list) and the **measurement-validity**
half (measure uptake only where the advantage exists, or the metric will read rational decline as
failure — or be "fixed" by copy that manufactures adoption the consumer shouldn't give). The second half
is what keeps the first from degenerating into persuasion.

**Where else it applies (named, not built):**
- The **REST API surface** — endpoints enumerate what they do, not when to prefer one over another; the legibility half applies, though REST consumers are humans reading docs, which softens the "decision point" urgency.
- The **runtime contract / capability descriptor (654)** — it enumerates capabilities; whether it *positions* them is the same question one layer up.
- A **future plugin surface (660)** — plugins competing for an agent's attention face the identical legibility problem; this is the most likely next instance.
- The **public docs / README (human consumer)** — the same shape at the human layer, but here the public-claims CI lane binds the honesty constraint directly: legibility must not become an unbacked comparative claim (no "beats grep" without a citable measured run).

**Where existing code already violates it:** today's tool descriptions and `getStatusContext` guidance
are feature-forward, not comparative (legibility half); the `instructions` field is absent; and the
adoption pilot currently measures on a grep-competitive corpus (measurement-validity half — violated
right now, which is exactly why the pilot read 0.0 and could not interpret it without this analysis).

**What would show the principle earning its keep:** on 624's two-corpus contrast, honest comparative
legibility *raises* adoption on the scale corpus while *not* inflating it on the grep-able corpus — i.e.
adoption visibly moves toward tracking utility rather than uniformly up. That selective movement is the
observable evidence the principle is doing real work rather than restating "write good docs."

**Retirement condition (stated so the principle can't become self-justifying apparatus):** if adoption
on the headroom corpus turns out to be dominated by factors the surface cannot touch — concretely, if
two well-designed `instructions`/description variants produce statistically indistinguishable adoption
across ≥2 model tiers on that corpus — then surface-legibility is not the operative lever (the mover is
model priors, client-side tool-preference config, or human-first invocation), and this principle should
be retired in favor of whatever does move the metric. Continuing to tune the surface past that point
would be optimizing apparatus, not outcomes.

## Design status

Settled at the design level; nothing built. The design is a wiring/reframing change (instructions field
as single-sourced projection + comparative response-hints + a version bump), scoped to conform to 654's
version and projection seams, 366's progressive-disclosure evidence, and 624's measurement contract, and
it orphans exactly one thing (the feature-forward `getStatusContext` tail) whose removal is part of the
same work. Not authorized to begin until the owner says go, and Move 3's decisive run depends on 624's
scale-member corpus existing.

---

# Implementation-readiness / risk register (2026-07-07) — de-risking pass before any build

A pre-implementation confidence pass that resolved the reducible uncertainties in the design above with
primary-source evidence (no feature code written, no paid eval run). Each row: the check, the finding
with citation, and the effect on confidence.

| # | Uncertainty | Finding | Effect |
|---|---|---|---|
| T1 | Fork risk — how many production MCP surfaces? | **One.** The Java `McpProtocolHandler.handleInitialize` (`McpProtocolHandler.java:133-147`) is the sole production surface. The three `.mjs` servers are all non-production: `scripts/prod/justsearch-mcp/server.mjs` is *deprecated legacy* (per `docs/reference/mcp-production-server.md:165-176`, despite its own stale "Production" header), `scripts/mcp/reference-server.mjs` is a test stub, `scripts/dev/justsearch-dev-mcp/server.mjs` is dev-stack tooling. None proxies `POST /mcp`. | ↑ The `instructions` field lands in exactly one place; no cross-surface sync. |
| T2 | Move 2 feasibility — does a response-hint mechanism exist to extend? | **Yes, already live.** `McpToolSurface` builds a per-response `hints` list (`McpToolSurface.java:548-560`) and has `appendEnrichmentHint`/`appendEnrichmentHintToList` (`:986-1011`); the class header names "response-level progressive disclosure hints" (`:35`). Hints already cross-reference other tools. | ↑ Move 2's response half is a small extension, not new structure. |
| T3 | Single-source safety — is `getStatusContext` safe to call at `initialize`? | Static: it is null-tolerant and non-throwing (try/catch → "status unknown", `:788,:803-805`) but makes a worker `status()` round-trip. **Refinement found:** single-source the *static comparative guidance* (the adoption-driving part — no worker call) and let each surface *optionally* append live status; this keeps a blocking cross-process call off the connect path. | ↑ Cleaner factoring than "call getStatusContext at initialize"; residual worker-latency concern removed by design. |
| T4 | Breakage surface — what tests/gates pin the initialize shape or version literal? | **Zero forced changes.** `McpProtocolHandlerTest.initialize_returnsCapabilities` asserts `serverInfo.version` against the *constant* `McpContractVersions.TOOL_SURFACE_VERSION` (projection, `:76-79`), so bumping the constant stays green; it reads keys via `.get()` (not exact-map-match), so an added `instructions` key doesn't break it; the conformance gate runs only the optional-field-tolerant upstream `server-initialize` scenario (`check-mcp-conformance.mjs:54`). | ↑ Version bump + instructions field need only *additive* test coverage, no rewrites. |
| T5 | Validation dependency — does 624's scale corpus exist? | **Generator-only.** Both on-disk corpora are 390 docs (`battlefield-en-v1`, `battlefield-de-v1`); no ~2-4k-doc corpus exists. The parametric generator `corpus_generate.py::generate()` *can* produce one via `distractor_ratio`/`n_chains` levers, but has no wired CLI command and no scale preset. Corpus selection itself is pure config (`--corpus-dir`/`--dataset`, `commands/utility.py:132,142`), not code. | → Moves 1–2 are unblocked and smoke-measurable on the existing corpus; Move 3's *decisive two-corpus contrast* is a genuine sequencing dependency on 624 generating + certifying the scale corpus. |
| T6 | Plumbing (load-bearing) — does the eval's CLI actually inject server `instructions` into the model? | **Confirmed empirically.** The harness delegates the whole MCP handshake to the stock `claude` CLI subprocess and adds no system-prompt override (`agent_utility_inspect.py:74-101,115,138`), so it cannot strip instructions. Direct experiment with CLI **v2.1.202** (the exact eval version, present locally) against a throwaway MCP server whose `instructions` told the model to emit a codeword: the model emitted the codeword `ZANZIBAR-QUILL-7734`, which existed nowhere else. So this CLI reads + injects + the model obeys server instructions. | ↑↑ The biggest risk is now largely closed. Residual: tested via stdio, production is HTTP — very low, since `instructions` lives in the transport-independent InitializeResult. |

## Residual uncertainties after this pass

1. **The empirical adoption outcome is irreducible pre-implementation** — whether *comparative* instructions/hint copy actually shifts a model's tool-choice prior on a headroom corpus is exactly what the design measures; it cannot be known before the change exists. This is not a knowledge gap to close now; it is the question the ~$3 pilot answers cheaply. The de-risking above ensures the *plumbing* that carries the copy works, so a null result would be a real product signal, not a wiring artifact.
2. **Move 3's decisive validation is sequenced behind 624** generating and certifying the ~2-4k-doc scale corpus (T5). Moves 1–2 can ship and be smoke-measured on the existing corpus first; the two-corpus contrast that proves "adoption tracks utility" waits on that corpus.
3. **Copy quality is the craft, not a surprise** — the instructions/response-hint text must be accurate, honest under the public-claims CI lane (no unbacked "beats grep" comparative), and minimal per 366. High-leverage judgment work, but well-understood and bounded.

## Confidence rating for the remaining work

**Implementation readiness: 8/10.** The change is small and single-surface, extends mechanisms that
already exist (response hints, the versioning seam, the status string), forces no test rewrites, and its
one load-bearing external assumption (instructions reach the model) is now empirically confirmed on the
exact CLI version. Surprises have been substantially removed. The two points held back from a higher
score are both *outcome* uncertainties, not *build* uncertainties: the irreducible "does the copy move
the metric" question (by design, answered by the pilot) and the 624 scale-corpus sequencing dependency
for decisive validation — neither is closable before implementation, and both are correctly external to
655's own build.

## Implementation difficulty and model/effort recommendation

**Difficulty: low-to-moderate mechanically, with one disproportionately high-leverage judgment part.**
The mechanical work — add the `instructions` field to `handleInitialize`, refactor the shared static
guidance so the prompt path and instructions field read one source, add one comparative entry to the
existing response-hint list, bump `TOOL_SURFACE_VERSION`, remove the orphaned feature-forward tail, add
test coverage — is bounded, verifiable, and low-risk (the "delegate a bounded chunk" profile). The crux
is the **copy**: the comparative decision-boundary text is what the entire adoption outcome rides on, and
it must thread accuracy, public-claims honesty, effectiveness, and 366's no-bloat constraint at once.

**Recommendation: Opus at medium effort for the whole change.** The change is small enough that running
it entirely on the stronger tier costs little, and the copy + the single-sourcing factoring + the
required critical-analysis/public-claims pass all benefit from the stronger model's judgment; splitting
tiers would save marginal tokens while adding coordination overhead on a small diff. If cost-optimizing,
the acceptable split is Sonnet for the mechanical wiring/version-bump/tests and Opus (or main-loop tier)
for authoring the instructions/hint copy and the final critical-analysis pass. Medium — not high/max —
effort is right: the design is settled and de-risked and no architectural ambiguity remains; the
remaining demand is craft and honesty in a few sentences of user-facing text, not structural problem-
solving. (Fable would serve the mechanical parts well; the copy benefits from deliberation over speed.)

---

# Implementation of the agent-legibility layer (2026-07-07) — shipped and live-verified

The design above is implemented, following it exactly. Backend-only change (no `modules/ui-web` /
frontend surface — the consumer is an autonomous agent, so the MCP protocol *is* the surface). All three
moves plus the version bump and the orphan removal landed in one change.

**What shipped:**
- **Move 1 (instructions field).** `McpProtocolHandler.handleInitialize` now returns the MCP-spec
  `instructions` field, sourced from a new `McpToolSurface#instructions()` (Layer 1 asks Layer 2 for the
  content, per the tempdoc-500 layering). The content is the new single-sourced `TOOL_SELECTION_GUIDANCE`
  constant — a *comparative* decision boundary (prefer the index over reading files one-by-one when the
  corpus is large / the question is semantic with no exact keyword / it spans documents / to conserve
  context; file tools are equally good for a small or exact lookup). `instructions()` makes no worker
  `status()` call, keeping the connect handshake free of a cross-process round-trip (the de-risking T3
  refinement).
- **Move 2 (comparative response hints).** Extended the *existing* response-hint mechanism (not a new
  one): `callAnswer` appends a factual, count-based comparative line when `chunksFound > 1`; `callSearch`
  adds a comparative entry to its existing `hints` list on a productive search. Placement follows tempdoc
  366's evidence (guidance in responses, re-read each turn — not in longer descriptions). Schemas and
  tool descriptions were left untouched (ADR-0015 / 366).
- **Version bump (654 seam).** `McpContractVersions.TOOL_SURFACE_VERSION` `0.1.0 → 0.2.0`; it projects by
  construction into MCP `serverInfo.version` and the runtime manifest's `mcpToolSurfaceVersion`.
- **Orphan removal (teardown rode along).** The feature-forward tail in `getStatusContext`
  (`"Use justsearch_answer for questions, justsearch_search for exploration, …"`) is deleted and replaced
  by a reference to the single-sourced `TOOL_SELECTION_GUIDANCE`, so the prompt path and the instructions
  field can no longer fork (654 "projection, not fork"). A new test asserts both surfaces read the one
  source.
- **Docs.** `docs/reference/mcp-production-server.md` updated (the connect-time `instructions` field under
  Tool Selection; the comparative hint under Progressive Disclosure) — reference doc only, no public
  adoption claim.

**Verification — every tier green, including the real agent-facing surface (there is no UI to
browser-check; the MCP protocol is validated instead):**
1. `spotlessApply` + full `build -x test` compile gate: green.
2. Unit: extended `McpProtocolHandlerTest` (asserts `initialize` returns a non-blank, comparative
   `instructions` string, and a single-source guard that the prompt path and `instructions()` share one
   guidance string) plus the full `:modules:ui:test` and `:modules:app-api:test` suites: green (the
   projection-based version assertions stayed green through the bump, as predicted).
3. Live MCP protocol probe against the running production HTTP `/mcp`: `initialize` returned
   `serverInfo.version: "0.2.0"` and the full comparative `instructions` string; `tools/call` for
   `justsearch_answer` showed the answer comparative hint ("Assembled evidence from 3 sources in a single
   retrieval call — …"), and `justsearch_search` showed the search hint ("Searched the entire index in one
   call. …").
4. **Real-agent behavioral check (closes the de-risking HTTP residual):** the actual `claude` CLI
   (v2.1.202, the eval's version), connected to the production HTTP `/mcp`, quoted the server's
   `instructions` back verbatim — confirming the field reaches the model's context through the real
   client over the real transport, not just stdio.
5. Conformance: the upstream `@modelcontextprotocol/conformance` suite via
   `check-mcp-conformance.mjs` — all 11 asserted protocol scenarios pass (the optional `instructions`
   field did not perturb `server-initialize`).

**What is deliberately NOT done in this change, and why (unchanged from the design):** the *decisive*
adoption measurement — the two-corpus contrast that shows adoption rising where the index wins and staying
low where grep is adequate — is deferred. It depends on tempdoc 624's ~2–4k-doc "scale member" corpus,
which does not yet exist (the generator `corpus_generate.py` can produce it via `distractor_ratio`/
`n_chains` levers, but no such corpus is built and no CLI wrapper exists). Running the paid pilot only on
the existing 390-doc grep-competitive corpus cannot show the positive result by design (a rational agent
should still decline there), so it was not spent. The surface is shipped and proven to carry the signal
end-to-end; whether that signal moves adoption is the pre-registered 624 pilot's result once its scale
corpus exists.

## Post-implementation refute-first review + fix (2026-07-07)

An adversarial, refute-first review (independent subagent, default stance "each claim is wrong until the
code proves it") of the shipped change found two **accuracy defects in the comparative response hints** —
text an autonomous agent reads, where a false comparative claim breaks the honesty this whole layer is
built on. Both fixed; the rest of the change reviewed clean (instructions field, single-sourcing, version
projection, Map.of usage, no golden/test-literal breakage).

- **Answer hint overstated document span (fixed).** It gated on `chunksFound() > 1` and asserted evidence
  "spanning multiple documents", but `chunksFound` counts *chunks*, not documents. The review traced two
  real worker paths where it lies: the virtual-chunk fallback sets `chunksFound` to every sub-chunk across
  all docs (40 chunks for 2 docs → "40 sources … multiple documents"), and the `CHUNKS_BELOW_THRESHOLD`
  path has `chunksFound > 1` with `chunksUsed = 0` and no assembly at all. Fix: a pure, unit-tested helper
  `McpToolSurface.comparativeAnswerHint(citations)` that counts **distinct `parentDocId`** from the actual
  citations and only claims "N documents" when N > 1 — which also self-suppresses the fallback-dump case
  (empty citations → 0). `docsUsed` was rejected as the basis because the rich-params retrieve path reports
  it as 0 regardless (a pre-existing bug logged to the observations inbox, out of 655's scope).
- **Search hint claimed "entire index" under filters (fixed).** Changed "Searched the entire index in one
  call" → "Searched the index in one call", accurate whether or not `filters` narrowed the query.

**Verification of the fix:** new regression test `comparativeAnswerHint_countsDistinctDocuments_notChunks`
(single-doc multi-chunk → no claim; 3 distinct docs → "3 documents"; empty/null → no claim) plus the full
`:modules:ui:test` and `:modules:app-api:test` suites and the `build -x test` gate: all green. The live
anchor for the review is the earlier real `tools/call justsearch_answer` this session, which showed the
*pre-fix* buggy output in genuine agent-visible response text — confirming the defect was real behavior,
not a code-reading hypothesis. A live re-probe of the corrected hint was deferred (the shared dev stack was
held by another active session and taking it over was declined per branch-safety); the deterministic helper
test is the authoritative check for the corrected logic, and the hint delivery *mechanism* was already
proven live end to end earlier in this session.
