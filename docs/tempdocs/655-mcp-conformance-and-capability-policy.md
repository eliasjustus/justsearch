---
title: "MCP conformance and capability policy: define the conformance bar and mutating-tool policy for JustSearch's production MCP endpoint"
type: tempdocs
status: open
created: 2026-06-28
updated: 2026-07-02
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

# 655 - MCP conformance and capability policy

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
was confirmed to reach that boundary correctly; only the final native OS call itself is untested,
consistent with this session's own confidence-building pass identifying that exact gap in advance.

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
`McpProtocolHandlerTest`. Also corrected a small inaccuracy discovered while investigating this:
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

