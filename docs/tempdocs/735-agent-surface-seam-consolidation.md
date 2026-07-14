---
title: "Agent-surface seam consolidation: why the MCP/eval/retrieval intersection generates defects, and what structure removes the generator"
type: tempdocs
status: "open — chartered + theorized (2026-07-14); no design settled, no implementation"
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
   marker mystery). **Gate: no Framing-B design without this answer.**
2. Exact scope of the furniture register vs. jumping straight to text-as-projection for the
   MCP surface only (SearchTool deferred to its own evidence).
3. Whether the lifecycle-state map (Framing A applied to the worker) is worth a register or
   whether 730's shipped observability already suffices — measure by whether the next lifecycle
   incident is diagnosable from status alone.
4. Sequencing against the measured cohorts: which structural changes ride the next intentional
   TOOL_SURFACE_VERSION bump.
