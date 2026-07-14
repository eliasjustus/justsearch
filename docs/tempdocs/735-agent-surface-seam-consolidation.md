---
title: "Agent-surface seam consolidation: why the MCP/eval/retrieval intersection generates defects, and what structure removes the generator"
type: tempdocs
status: "open — theorized + DESIGNED + DERISKED (2026-07-14): delivery-tier inversion probed (CLI delivers structuredContent JSON when present; text otherwise; 2.1.209); coverage gap list = G3 backlog (hints/facets/coverage-facts/full passages undelivered to CLI cohort, ~2.7KB vs ~10KB packs); confidence 8/10; implementation not started, G3 rides next intentional surface bump"
created: 2026-07-14
author: agent (session 25f8ac5d, chartered at founder direction after the 725 remediation program's root-cause analysis)
related:
  - 725-agent-tool-adoption-legibility   # the program whose ~20 issues this analyzes
  - 729-agent-eval-capture-instrument-integrity
  - 730-worker-lifecycle-integrity
  - 731-retrieval-integrity-mixed-corpora
  - 553-representation-drift             # the register/projection medicine this proposes extending
---

# 735 — Agent-surface seam consolidation

## Why this tempdoc exists

The 725 remediation program (2026-07-14) fixed 15 inventoried issues and, in doing so, surfaced
~20 defects/anomalies total across the MCP surface, the eval harness, and the worker lifecycle.
A root-cause pass over all of them found they collapse into a small number of structural
classes, concentrated where three owned authorities (product MCP surface, retrieval engine,
measurement harness) and two unowned layers (agent CLI, agent SDK) meet. This tempdoc owns the
question: **what structural change removes the defect generator, rather than continuing to
catch its output one incident at a time?**

## The evidence base (condensed; full analysis in tempdoc 725's program sections)

Root-cause classes with counts, from the 725 program:

1. **Seam duplication (~7 issues, dominant):** the same concept implemented twice, drifting.
   Instances: evidence-pack pre-search ran a deprecated sparse-only pipeline while search ran
   hybrid (register F-037); SPLADE vs embedding fingerprint stamping were asymmetric siblings
   (the durability ratchet lived in the asymmetry); response furniture strings hand-copied from
   Java into the Python harness (3 literals, no enforcement); response text vs
   `structuredContent` assembled in parallel; two funnel denominators; measured: 3 independent
   `KnowledgeSearchRequest` construction sites; `SearchTool.java` (536 lines, agent-internal)
   independently renders the same search response `McpToolSurface` renders, with a production
   regex (`AgentContextCompressor`) coupled to its exact text.
2. **Unreachable-seed-green tests (≥5):** test fixtures approximating production shapes and
   missing exactly the shape the bug lived in (SDK stream shapes, generation-layout boots,
   hard-kill semantics, block-list content). This area's defects were caught almost exclusively
   by live probes and adversarial review, almost never by its own tests.
3. **String-assembled contracts:** the agent-visible text is built through 59 conditional
   `append` sites in a 1,501-line class, parsed back by harness regex/markers, described a third
   time in docs prose — a load-bearing contract with no schema. The still-open furniture-marker
   mystery (0/153 fires at campaign scale despite provably-correct extraction) is this class at
   its worst: three layers own parts of the text and only the first is ours.
4. **Distributed lifecycle state:** the fingerprint saga required five state carriers (ECC
   memory, commit userData, loop timing, log rotation, dev lease) to align; the defect was a
   timing hole between carriers.
5. **Multi-agent dev environment masquerading as product bugs (~4):** lease takeovers read as
   backend crashes; concurrent Gradle corrupted shared caches; costs real diagnosis time.
6. **Platform-semantics assumptions (small, recurring):** SQLite RETURNING order, NTFS
   birthtime tunneling, `Set.copyOf` ordering.

The sharpest correlation: **defect density tracks governance reach.** `SearchTrace` — the one
concept here with a register and a build gate — had zero drift issues; its MCP projection
extended cleanly. Every drift-class defect lived on a seam with no register.

---

# THEORIZE (2026-07-14; before any design)

## Framing A — two rival programs: govern the seams vs remove the seams

Everything below sorts under two strategies with very different cost/risk profiles:

- **Govern:** extend the repo's proven register/gate medicine to the ungoverned seams — a
  response-furniture register (every agent-visible line: producer site, harness consumers, doc
  mentions; CI check that literals agree across Java/Python/docs), a retrieval-request register,
  a lifecycle-state map. Cheap, incremental, proven in-repo; guards the seam but leaves the
  duplication (and the string-assembly bug class) in place.
- **Remove:** structural unification — one content model per response with text as a renderer;
  one retrieval-request factory; a single lifecycle authority. Removes the generator, but is a
  deeper intervention on a freshly-measured surface.

These compose: govern first (immediate protection), remove where the governed seam keeps
costing (evidence-driven unification). The failure mode to avoid is the inverse order — a
big-bang restructure justified by incidents a register would have caught for 1% of the cost.

## Framing B — the text tier as a projection (conforms to an existing seam)

The strongest unification candidate has in-repo precedent: `searchTraceExplain` is already a
*renderer over the canonical trace*, and `structuredContent` is already declared a projection of
canonical records. Making the human-readable text the *third projection of one response content
model* (fields: header facts, per-hit entries with match rationale/preview/degradation, hints)
conforms to the established one-authority shape rather than inventing one. Consequences worth
theorizing now:

- The harness would consume the machine tier for its markers instead of grepping text —
  dissolving the Java↔Python literal coupling *and* most of the marker-mystery surface.
- `SearchTool`'s rendering becomes a second renderer profile over the same model (satisfying
  AHA: the two renderings share exactly the provenance-content reason to change, while their
  formatting stays audience-specific).
- **Hidden dependency, must verify before any design:** does the agent SDK/CLI actually deliver
  `structuredContent` to child sessions? If `ToolResultBlock` carries only the text blocks, the
  "harness reads the machine tier" half fails and only the producer-side unification survives.
  This is the single most design-shaping unknown, and it overlaps the open marker mystery — one
  debug-instrumented cell answers both.
- **Byte-compatibility constraint:** the renderer must reproduce the shipped 0.3.1 text exactly,
  or the change rides an intentional surface-version bump — the tools/list hash and measured
  cohorts (Campaigns D/T/U) make silent text drift a measurement event, not just a refactor.
  Sequencing implication: batch any text-affecting refactor with the next deliberate surface
  change, never as "pure cleanup."

## Framing C — recorded reality as the test substrate

The unreachable-seed class recurred five times in one day; prose rules demonstrably don't hold
it. The structural answer is a **golden production-fixture pipeline**: record real shapes once
(SDK stream messages, CLI-delivered tool results, generation-layout directories, hard-kill
states), sanitize through the existing redaction machinery, commit with provenance stamps (SDK/
CLI versions, surface version), and require boundary-layer tests to consume them. Risks to
carry into design: fixture staleness (recorded shapes age with SDK/CLI releases — provenance
stamps plus a refresh procedure are part of the design, not an afterthought); privacy (the
redaction contract exists and was leak-tested in 729); and the temptation to fixture
*everything* (scope: the five boundary layers that actually produced unreachable-seed
incidents, nothing else — AHA applies).

## Framing D — the environment is part of the system

Roughly a fifth of the program's diagnostic cost went to the development environment
impersonating product failure (lease takeovers as "crashes", cache corruption as "build
breaks"). Two cheap directions, both assumptions-checked:

- Lease: a heartbeat during long operations assumes long operations *should* hold the lease; the
  alternative is declaring intent — a lease-duration parameter for campaign-length holds, so
  takeover semantics stay simple and the intent is explicit. Note: once the 730 observability
  work merges to the checkout that drives stops, takeover-driven kills will already preserve the
  victim's logs — the witness gap partially self-resolves at merge.
- Build concurrency: per-worktree Gradle caches or an enforced one-build-at-a-time convention;
  the orchestration cost of serialization is real but small next to a corrupted-cache hunt.

## Hidden assumptions surfaced (beyond Framing B's)

1. "The three request-construction sites want the same semantics" — suggest/autocomplete
   deliberately differs; only unify the sites that share the *reason* (search + pack pre-search),
   per AHA. Verify each site's intent before a factory swallows it.
2. "The marker mystery is a CLI transformation" — plausible, unverified; no design in this
   tempdoc may *depend* on the explanation until the debug cell runs.
3. "Registers scale to text furniture" — execution-surfaces governs *code referencers*; a
   furniture register governs *strings across languages and docs*. The checker is different in
   kind (literal-agreement lint, closer to `check-readiness-reason-codes`' paired-file model
   than to the execution-surface gate). Precedent exists; effort is small but not zero.
4. "Unification reduces defects" — it reduces *drift* defects; it concentrates risk in the
   single implementation. The adversarial-review cadence that caught this program's MAJORs is
   what makes concentration safe; the two disciplines are complements, not alternatives.

## Principle candidates (recorded, not built)

- **"Every load-bearing agent-visible string has one producer and machine-checkable
  consumers."** Instance of the repo's one-authority/projection principle, extended from code
  representations to *rendered text*. Earns its keep the first time a furniture change fails a
  gate instead of silently breaking the harness; retires if/when the text tier is fully
  generated from the content model (the register dissolves into the generator).
- **"Boundary tests consume recorded reality, not authored approximations."** Scope: layers
  whose shapes are produced by systems we don't control (SDK, CLI, OS, DB). Earns its keep via
  a measurable drop in unreachable-seed incidents; retires for any layer that becomes thin
  enough that authored seeds are provably shape-complete.
- **Seam-density as a review heuristic (process, not code):** changes touching ≥2 of the five
  authorities get adversarial review by default. Earns its keep by MAJOR-catch rate (this
  program: 3 MAJORs caught, all at seams); retires if seam count itself drops enough that the
  heuristic selects almost nothing.

## What this tempdoc should NOT become

- A big-bang rewrite of `McpToolSurface` justified by line count — the count is a symptom;
  the design must be reachable in reviewed increments that keep the measured surface stable.
- A governance layer for its own sake — each register must name the incident class it would
  have caught, and the checker that enforces it, or it doesn't ship.
- A fixture museum — recorded fixtures only for the boundary layers with demonstrated
  unreachable-seed incidents.

## Open questions for the design pass

1. SDK/CLI delivery of `structuredContent` to child sessions (the debug cell — also closes the
   marker mystery). **Gate: no Framing-B design without this answer.** → **ANSWERED, below.**
2. Exact scope of the furniture register vs. jumping straight to text-as-projection for the
   MCP surface only (SearchTool deferred to its own evidence). → resolved by the answer to 1.
3. Whether the lifecycle-state map (Framing A applied to the worker) is worth a register or
   whether 730's shipped observability already suffices — measure by whether the next lifecycle
   incident is diagnosable from status alone.
4. Sequencing against the measured cohorts: which structural changes ride the next intentional
   TOOL_SURFACE_VERSION bump.

---

# Settled design (2026-07-14; the gate probe inverted the premise)

## The decisive probe result

A debug cell (direct Claude Agent SDK session against the live 0.3.1 stack, raw
`ToolResultBlock` inspection — no redaction) answered the gate question in the strongest
possible way: **for MCP tools that return `structuredContent`, the Claude Code CLI delivers the
model the serialized `structuredContent` JSON as the tool result — not the human-readable text
tier.** The `justsearch_answer` result arrived as `content: str` beginning
`{"citations":[{"parentDocId":...` (the `McpEvidenceProjection` output). Consequences, in
order of importance:

1. **The furniture-marker mystery (0/153) is fully explained**: the text lines were never
   delivered to campaign agents in this cohort. Extraction was always correct; the contract
   assumption was wrong.
2. **The measured behavioral effects re-attribute to the structured tier**: Reads-per-search
   halved at 0.3.0 because `searchEvidence` gained excerpts/matchedTerms (delivered), not
   because the text previews improved (undelivered in this cohort); concise adoption jumped at
   0.3.1 via the schema description (tools/list IS delivered). The effects are real; their
   carrier was misattributed. A dated correction rides in tempdoc 725.
3. **The text tier's audience is humans and text-rendering clients** (and any client cohort
   whose delivery behavior differs — Desktop/Cursor unverified). It remains a real product
   surface; it is not the measured cohort's contract.
4. **Delivery tier is a cohort fact**: what a client hands the model is as identity-relevant as
   which tools it exposes. This extends 725's cohort-relativity principle one level down.

## The design (govern-then-remove, re-anchored on the structured tier)

**G1 — Declare the real contract.** `structuredContent` (already a registered projection:
`mcp-evidence-projection` in `governance/execution-surfaces.v1.json`) is promoted in docs and
register-role to *the primary agent-delivered contract for structured-preferring clients*;
`docs/reference/mcp-production-server.md` gets an honest delivery-tier note (its current prose
implies agents read the text lines — for the CLI cohort they do not). Public-claims-safe wording
required (no client-behavior claims beyond what the probe evidences, named client + date).

**G2 — Measure what is delivered.** The 729 capture gains a per-call `delivered_tier` derivation
(structured-JSON vs prose text — derivable from existing digests via a JSON-parse check, no new
raw capture) and the furniture markers are recomputed against **structured fields** (e.g.
`quality`, `matchedTerms`, `degradation` presence) rather than text greps. The Java→Python text
literals dissolve into field names governed by the observation schema. The text-grep markers
remain only as a secondary signal for prose-delivered results.

**G3 — One response content model (the remove step, now REQUIRED rather than optional).** The
probe shows text and structured tiers can silently serve different content to different
audiences; today they are assembled independently in `McpToolSurface` (59 append sites) and
`McpEvidenceProjection`. The design: one content-model builder per tool response (facts:
header counts, per-hit rationale/excerpts/degradation, quality, hints), with **two renderers** —
the structured projection (schema-governed) and the text projection (for humans/prose clients).
This guarantees tier equivalence by construction. Rides the next intentional
TOOL_SURFACE_VERSION bump (renderer must either reproduce 0.3.1 text byte-for-byte or ship as a
declared surface change). `SearchTool`'s rendering joins as a third renderer profile ONLY when
its own evidence demands (AHA; its consumer regex is a live constraint).

**G4 — Recorded-reality fixtures.** Seeded by this probe's output (the serialized-structured
delivery shape is fixture #1), plus the SDK stream shapes, generation-layout boot, and
hard-kill states from the 725/730 incidents. Scope stays: boundary layers with demonstrated
unreachable-seed incidents. Provenance stamps (SDK/CLI/surface versions) mandatory.

**G5 — Request-builder unification** (unchanged from theorization): one factory for the two
shared-reason sites (interactive search + pack pre-search); suggest/autocomplete stay separate
by intent.

**G6 — Environment intent** (unchanged): lease-duration declaration for campaign-length holds;
one-build-at-a-time convention documented where the worktree guide lives.

## Orphans (owned by this design)

1. **The 725/732 text-attribution claims** — corrected by a dated note in tempdoc 725 (effects
   real, carrier re-attributed for the CLI cohort); the text-tier work itself is NOT orphaned
   (other audiences + the same in-hand data feeds both tiers).
2. **Text-grep furniture markers as primary** (729) — superseded by field-based markers (G2);
   kept as secondary for prose-delivered results.
3. **The theorized text-furniture register** — dissolved: G2 removes its consumers; G3 removes
   its producer-side need.

## Design reach

- **Conforms to:** the execution-surfaces register (G1 is a role clarification of an existing
  entry, not a new authority); the projection discipline (G3 is `searchTraceExplain`'s shape
  applied to the MCP response); 725's cohort-relativity (delivery tier joins exposure mode as
  identity); the recorded-fixture idea conforms to the committed-fixture precedent (the 48-row
  evidence fixture).
- **Principle (promoted from theorization, sharpened by the probe):** **"What the model is
  delivered is a cohort fact, distinct from what the server authored — capture it, never assume
  it."** Instances already visible: tool visibility (deferred vs eager — 725), and now content
  tier (structured vs prose). Candidate scope: any client-mediated surface (future MCP
  resources/prompts, agent-api HTTP responses through gateways). Earns its keep each time a
  delivery-tier split explains a cohort behavior difference (twice already: adoption regime,
  marker mystery); retires if clients converge on a spec-mandated delivery behavior.
- **Violation noted, not fixed here:** every historical interpretation that assumed text-tier
  delivery (tempdoc 655's response-hint attributions may partially share the misattribution —
  flagged for that lineage's next revisit, not relitigated now).


---

# De-risking pass (2026-07-14; probes + static reads)

- **U1 (delivery generality) — SETTLED.** Broadened child-session probe (CLI 2.1.209 pinned):
  `search` also delivers the serialized structuredContent JSON; tools WITHOUT structuredContent
  (`status`) deliver prose text blocks; the text tier is entirely absent when structured exists.
  Delivery rule confirmed: structured-if-present, text-otherwise. (One result-attribution wobble
  between search/browse in the probe harness — the shapes themselves are unambiguous.)
- **U2 (structured coverage) — GAP LIST CONFIRMED, and it is a live product gap.** Text-tier
  content with NO structured counterpart, i.e. currently UNDELIVERED to CLI-cohort agents:
  the 655 response hints (all of them), the facet-values block, totalHits/"showing K" coverage
  facts, and truncation remedies. Also QUANTIFIED: delivered answer packs are ~2.7KB of
  citation metadata+excerpts vs ~10KB of full passages in the text tier — CLI agents receive a
  substantially thinner evidence pack than the text tier ships. This list IS G3's content-model
  field backlog, priority-ordered by what the measured cohort has been missing.
- **U3 (record-time tier detection) — CONFIRMED feasible** (raw content in hand at the digest
  site, ephemeral tier); historical records cannot be re-tiered (sha-only) — stated.
- **U4 (renderer fidelity) — declared choice, not a risk**: golden-fixture comparisons (G4)
  serve either byte-identical reproduction or a declared surface-bump path.

## Confidence: 8/10

The design's central fact is now triple-probed (answer/search/status, version-pinned) and the
gap list converts the design from restructuring-for-hygiene into closing a measured delivery
gap. Held back from 9+: the coverage gaps' behavioral impact is inferred (thin packs →
file-Reads) not yet A/B-measured, and other clients' delivery behavior (Desktop/Cursor) remains
uncharacterized — both are measurement items the design already carries.

## Difficulty and staffing recommendation

Moderate, well-partitioned. G1/G2 (docs role + delivered-tier capture + field markers):
**sonnet, medium** — bounded, pattern-following. G4/G5/G6 (fixtures, builder factory, lease
intent): **sonnet, medium-high**. G3 (one content model, two renderers): the only genuinely
design-sensitive increment — **sonnet implementation with the content-model field contract
fixed main-loop first, plus an opus refute-first review before commit** (the level-2/725
staffing pattern, which caught every MAJOR this program produced). No increment warrants
opus-tier implementation; the ordering constraint (G3 rides an intentional surface bump,
after G1/G2 land the measurement that will judge it) matters more than model tier.


---

# Research pass (2026-07-14; one question — delivery-tier stability; cited summaries only)

**Verdict: UNDOCUMENTED-OBSERVED, with an active churn signal.** The structured-preferred
delivery we probed is documented NOWHERE official (code.claude.com/docs/en/mcp: zero mentions
of structuredContent); it is user-discovered in a cluster of GitHub issues
(anthropics/claude-code #9962 — "undocumented breaking change", a prior SILENT FLIP from
text-preferred to structured-preferred between v2.0.10–2.0.22; #55677 — text dropped when
structured present, closed "not planned"). VS Code Copilot MCP exhibits the identical behavior
as an OPEN bug (microsoft/vscode #290063), while per issue reports Claude.ai web forwards text —
the ecosystem has not converged. Spec-side, client-forwarding guidance exists only as a DRAFT
(SEP-1624, unmerged): tiers "MUST be semantically equivalent"; clients "SHOULD NOT forward both."
The 2026-07-28 RC adds nothing on client forwarding.

## Design amendments this forces (recorded here; the design sections above stand otherwise)

1. **G1 rewritten in intent:** do NOT declare structuredContent "the contract" — declare
   **tier equivalence** as the product's contract ("both tiers carry the same information;
   which one a client delivers is a client fact we capture, not assume"). This is precisely
   draft SEP-1624's direction — G3's one-content-model design becomes conformance to the
   emerging spec guidance, not just internal hygiene.
2. **G2 promoted from useful to non-negotiable:** delivery flipped silently once already;
   without per-cell delivered-tier capture, any cross-CLI-version measurement comparison is
   silently confounded. Delivered tier joins cohort identity.
3. **The U2 gap list reframes as tier-equivalence VIOLATIONS** (hints/facets/coverage-facts/full
   passages exist in text only) — under SEP-1624's draft MUST, these are spec-conformance gaps
   in waiting, which prioritizes closing them regardless of which tier any client prefers.
4. Public-docs wording (G1) must present the delivery behavior as observed-and-versioned
   ("observed with Claude Code CLI 2.1.209, 2026-07-14; behavior is undocumented upstream and
   has changed before"), never as a stable platform guarantee.

## Implementation log

**W1 (G1 docs honesty) — landed 2026-07-14, commit `360a0a6`.** `docs/reference/mcp-production-server.md`
gained the "Delivery tiers" section: tier equivalence stated as the product contract; client
forwarding behavior stated as observed-and-versioned (Claude Code CLI 2.1.209, CC issue #9962
cited), never a guarantee. `governance/execution-surfaces.v1.json` `mcp-evidence-projection`
description extended with its delivered-contract role. Regen + link check + `execution-surface`
gate green. Wording authored main-loop (public-claims lane).

**W4 (G5 request-builder factory) — landed 2026-07-14.** `SearchPipelinePresets.defaultHybridProtoConfig()`
(app-services, package-private static) is now the single authority for the default agent-path
pipeline request; `RemoteDocumentService.preSearchForDocIds` consumes it (byte-identical drop-in,
wire-shape pin test green). Deliberate deviation from G5's literal wording: `KnowledgeSearchEngine.doSearch`
is NOT a consumer — its mode is dynamic (default AUTO with denseAuto=true, not fixed hybrid), and it
threads the intermediate `PipelineConfig` through expansion gating before proto conversion, so literal
two-site consumption would be wrong. Both sites still land on the same `expandPreset` definition, which
is the unification G5 sought; the factory Javadoc records the reasoning + F-037. Refute-first review:
5/5 claims confirmed, no refutations, merge-ready.

**W5 (G6 environment intent) — landed 2026-07-14.** `justsearch_dev_start` gained optional
`leaseDurationSec` (clamped [30,7200] once at dev-runner parse time; both lease write-sites use it;
omitted = byte-identical behavior). `ownership.lease.remainingSec` + `leaseFresh` surfaced from the
single `buildOwnershipProjection` site, liveness-qualified (a crashed supervisor shows 0, not hours —
review MINOR, fixed pre-commit). Review confirmed the zombie-lease concern is structurally mitigated:
takeover verdicts key off session activity + supervisor PID liveness, and the resource reaper keys off
activity age — none of them off the TTL. Docs in `mcp-dev-tools.md` (skill regenerated), shared-stack
convention lines in `branch-safety.md` (prose-tier gate green). Tests 10/10 + 9/9. Refute-first review:
7/7 confirmed, merge-ready.
