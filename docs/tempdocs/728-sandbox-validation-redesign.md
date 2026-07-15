# 728 — Sandbox release-candidate validation: derive coverage, split the surface, converge monotonically

- **status:** IMPLEMENTED 2026-07-14 (branch `worktree-release-asset-set`, pre-PR).
  Parts A–C + the capture harness landed and are verified end-to-end (see
  §Implementation). §Design fixes the end-state; framing/diagnosis/theorization
  above it are the record of how it was reached. **Deferred to their own passes
  (flagged, not this PR):** tightening the CI `check-ui-step-coverage` gate to
  DEEPLINK (ui-shot domain), and a release-blocking monotonic-loop CI gate (726
  §Long-term). The acceptance smoke — a real Sandbox round on a CI-built candidate
  — is operator-driven and cannot be self-run.
- **created:** 2026-07-14
- **author-role:** orchestrator (Opus) — diagnosis/framing during the v0.2.0
  release cut (tempdoc 726); design settled 2026-07-14 (see §Design).
- **scope & lifecycle:** a **bounded** design/decision doc for the *validation
  approach and its content architecture*. Closes when a redesign ships **or**
  a deliberate "keep the current shape, here's why" decision is recorded. The
  durable "how a release is cut" stays in `docs/how-to/cut-a-release.md`; this
  doc feeds that page's verification leg, it does not replace it.
- **relation:** continuation of tempdoc **374**'s sandbox-harness slice (§8 of
  the desktop-installer explainer + the `scripts/sandbox/**` harness). Sibling
  to **726**: 726 builds the hash-consistent release *asset set*; this governs
  how a candidate built from it is *verified*. `cut-a-release.md` already owns
  the release *loop* (build → clean-Sandbox verify → fix → converge → finalize)
  — this doc drills into the "clean-Sandbox verify" box, which that page treats
  as a single step. 374 stays dated history; this is the current framing for
  the validation surface.

## Why this exists now

The v0.2.0 cut (726) exists to make one claim true: the app now serves a
`/mcp` endpoint. While preparing that cut, an audit of the in-Sandbox agent
content found that **nothing in the validation surface verifies `/mcp` at
all** — the API endpoint table (`scripts/sandbox/sandbox-CLAUDE.md:387–399`)
omits it, no journey covers it, and the only three `mcp` mentions across all
staged content say *"no MCP dev tools available."* The staged
`.claude/settings.json` even strips MCP servers, so an agent has no guided way
to exercise it.

The MCP miss is the **presenting symptom**, not the disease. The disease is
structural: the validation content is a monolithic, append-only mission doc
that drifts from what actually ships. This doc is the redesign, with the MCP
gap as evidence — **not** "add an MCP section" (a patch that leaves the *next*
new surface to be silently forgotten the same way).

## Diagnosis — the decay mode

The Sandbox mission (`sandbox-CLAUDE.md`, `sandbox-environment.md`, and the
30 KB `sandbox-start-SKILL.md`) is **the same failure mode that 726 /
`cut-a-release.md` just fixed one layer up, left untreated here.**
`cut-a-release.md` explicitly retired "the old rolling packaging log" (374's
decay) by splitting a *single living surface* from *per-round bounded
tempdocs*. Yet the Sandbox mission is itself a rolling append-only log:

- **Dated history mixed with durable how-to.** It has accreted `alpha.5` →
  `alpha.27` (grep: dozens of `alpha.NN` anchors across all three files), and
  interleaves stable harness knowledge (runtime-manifest port discovery, the
  journey method, the API testing approach) with dated per-alpha regression
  notes (alpha.11–16 CUDA/ONNX minutiae).
- **No cutover awareness.** Zero mention of the go-public cutover or the
  `0.1.0`/`0.2.0` scheme. The "current mission" is anchored to *"the alpha.27
  fresh-install pass proved the core path — don't re-prove it."* Applied to a
  **materially different build** (0.2.0, CI-built, new backend jars adding
  `/mcp` and the go-public trust surfaces), that "already proven" instruction
  risks under-testing exactly what changed. Installer-size expectations still
  read `~770 MB` (alpha.15); the real 0.2.0 artifact is 815 MB.
- **Coverage is a hand-maintained fork of the product surface.** Nothing ties
  "what the release ships / claims" to "what the agent verifies." MCP being
  missed is the proof; the go-public trust surfaces (Security & Privacy,
  Agent-delegate, Memory, Skins) *were* added (`sandbox-CLAUDE.md:85–92`), but
  only because a human remembered to. This is the projection-vs-fork problem
  the repo fights elsewhere (execution-surface register, SSOT dual-copy),
  applied to validation.

What *is* sound and worth preserving: the staging mechanics
(`sandbox-launch.py` — docs, sanitized `.claude/`, SciFact corpus,
`bypassPermissions`), runtime-manifest port discovery, the journey-ordered
method, GPU/NVML expectations, and the "cheap sanity ladder, then frontend
truthfulness" philosophy. The redesign should keep the harness and re-architect
the *content and contract*, not throw out the machinery.

## Purpose (general, open)

> **Re-examine, as a whole, how JustSearch validates a release candidate in a
> clean environment** — the Sandbox agent's setup, its mission content, the
> host↔in-Sandbox contract, the human/automation boundary, and how verification
> coverage stays in sync with what actually ships — and decide the right
> end-state architecture, rather than continuing to patch a monolithic,
> alpha-era mission doc.

## Forces / open questions the design turn must resolve (not answered here)

1. **Durable vs. per-round separation.** What is stable harness knowledge vs.
   this-candidate's focus? The `cut-a-release.md` "living surface + bounded
   per-round tempdoc" split is the obvious candidate *shape* to weigh — offered
   as a hypothesis to test, not a foregone conclusion.
2. **Coverage-follows-shipment.** Can "what gets verified" *derive* from what
   the release ships (surfaces, endpoints, claims) instead of being remembered?
   Even a lightweight "verification checklist generated from a surface register"
   beats prose that silently omits `/mcp`. What is the cheapest mechanism that
   makes a new shipped surface *fail closed* if it has no verification entry?
3. **The host↔Sandbox contract.** Today: a file-staging handoff plus a fresh
   in-Sandbox Claude session driven by a large static `CLAUDE.md`. What is the
   right division of labor between the host release agent (which structurally
   *cannot* drive the Sandbox GUI) and the in-Sandbox verifier, and how is
   independence (verifier ≠ committer, per `slice-execution.md`) operationalized
   across that boundary?
4. **The automation boundary.** How much of install / launch / evidence-capture
   should stay manual vs. become repeatable? Where does artisanal judgment add
   real value, and where is it just friction that makes rounds unrepeatable?
5. **Findings → convergence.** The release loop is "build → verify → fix →
   rebuild at same number → converge." How does a round's finding become a
   regression gate so the loop converges instead of re-finding the same class?
   (`cut-a-release.md` asserts this; the mechanics are undesigned.)

## Scope boundaries (keep it bounded)

- **In scope:** the validation *approach* and its *content/contract
  architecture* — the Sandbox agent's setup, mission content, the host↔Sandbox
  handoff, the automation boundary, and the coverage-follows-shipment question.
- **Explicitly out (named so it doesn't sprawl):** the installer *build* path
  (owned by 726/374); GPU/CUDA runtime mechanics; the product surfaces
  themselves (this doc governs *whether/how they're verified*, not their
  behavior); and the release *loop's* outer shape (owned by `cut-a-release.md`).
- **What it would supersede:** the append-only-mission-doc pattern for Sandbox
  validation — the same way `cut-a-release.md` superseded the rolling packaging
  log. It does **not** supersede the `scripts/sandbox/**` staging machinery.

## Open investigation before design (do first)

- **Read `sandbox-start-SKILL.md` in full** (30 KB; only sampled via grep so
  far). It is part of the same surface and almost certainly carries the same
  alpha-era decay; scope depends on what it actually contains.
- **Inventory the current validation surface** end-to-end: every staged file,
  what each asserts, and which product surfaces (endpoints, journeys, trust
  surfaces) it does and does not cover — the raw material for the
  "coverage-follows-shipment" question. The `/mcp` omission is one known cell;
  enumerate the rest before designing.
- **Confirm the independence contract** actually practiced today (who runs the
  Sandbox round vs. who committed the candidate) so the redesign codifies
  reality, not an aspiration.

## Investigation notes (2026-07-14) — premises confirmed

A read-through of the four staged files, `cut-a-release.md`, and the shipped
route/surface artifacts confirms the framing above and adds primary-source
anchors the design turn can build on:

- **`/mcp` is a shipped `AUDIENCE_PUBLIC` product surface, unverified.**
  `LocalApiServer.java` registers `POST`/`DELETE /mcp` plus `GET
  /api/mcp/token`; `RuntimeApiRoutes.java` marks `/mcp` public. None of the four
  staged files exercise it; the only `mcp` mentions across them are about
  *dev-tooling* MCP being absent (`sandbox-CLAUDE.md`, `sandbox-environment.md`,
  `sandbox-start-SKILL.md`), and `sandbox-launch.py` actively strips MCP servers
  from the staged settings. So the miss is not an oversight in one file — the
  whole surface omits the endpoint.
- **It is already a live contract gap, not a future risk.**
  `cut-a-release.md` *names* MCP as part of the whole-product Sandbox pass, but
  the operational skill agents actually run never mentions it. The how-to
  promises coverage the operator brief does not deliver.
- **The miss is a two-instance class, not a one-off.** The go-public trust
  surfaces (Security & Privacy, Agent-delegate, Memory, Skins) were *also*
  omitted until a human hand-added them (`sandbox-CLAUDE.md`). Two documented
  silent omissions of the same kind ⇒ under `structural-defects-no-repeat`, the
  bug-class is proven; a point-patch would leave the next surface to fall the
  same way.
- **The fix-shape already exists one layer up.** `cut-a-release.md` retired the
  rolling packaging log by splitting a *single living surface* from *per-round
  bounded artifacts* — the exact shape §Forces Q1 offers as a hypothesis. This
  is "apply a proven sibling pattern," not an invention.
- **"What ships" is already machine-tracked — in more than one place.**
  `route-manifest.snapshot.json` lists every endpoint with a `cohort` (`/mcp`
  and `/api/mcp/token` are present under `cohort: "mcp"`); UI/trust surfaces are
  tracked separately by `CoreSurfaceCatalog.java`, `check-ui-step-coverage.mjs`,
  and `check-surface-composition.mjs`. The launcher also already *generates* a
  per-instance authority file (`validation-mode.md`) that overrides static
  prose. So both the coverage sources and the "inject a generated brief" seam
  exist today.

## Theorization (2026-07-14) — directions to weigh before design

Options and framings, not decisions. Recorded so the design turn starts from a
wider field than "split the doc and add an MCP section."

### T1. Four ways to frame the problem (pick the generative one)

1. **Content-decay** (the doc's default): the mission is a rolling append-only
   log; split durable method from dated history. *True but shallow* — a clean,
   well-split doc would still silently omit the next new surface.
2. **Coverage-as-fork**: "what we verify" is a hand-maintained second copy of
   "what we ship," with no link. The append-only decay is a *symptom* of the
   fork. This is the repo's projection-vs-fork problem (execution-surface
   register, SSOT dual-copy) applied to validation.
3. **Boundary information-loss**: the host release agent knows exactly what
   changed (diff, new endpoints, new claims) but structurally *cannot* drive the
   Sandbox GUI; the in-Sandbox verifier *can* drive the GUI but starts from a
   stale static brief blind to what changed. Surfaces fall through the gap
   between "what the host knows changed" and "what the Sandbox is told to check."
4. **E2E test-coverage wearing prose clothing**: this is manual/agentic
   end-to-end testing with *no coverage instrument*. In normal software "did we
   test the new endpoint?" is answered by tooling, not memory.

Framings 2–4 are more generative than 1 and are not mutually exclusive: the
fork (2) is *why* it decays, the boundary (3) is *where* it leaks, and the
missing coverage instrument (4) is *what* would catch it.

### T2. Coverage-follows-shipment is a cost spectrum, not a yes/no

From cheapest/weakest to heaviest, with the recommended zone marked:

| Rung | Mechanism | Verdict |
|---|---|---|
| 0 | Prose reminder (status quo) | Fails silently — the disease. |
| 1 | Static "public surfaces" checklist in the durable skill | Still a fork; drifts. Marginal. |
| 2 | **Brief derived at stage-time from the existing surface artifacts** (launcher filters route-manifest + surface catalogs to the public/verifiable set and emits a per-round "must-touch surfaces" file) | **Recommended floor.** Fork eliminated (derived), cheap (artifacts + `validation-mode.md` seam exist). |
| 3 | **Empirical coverage diff at finalize** (the round emits which cohorts/surfaces it actually exercised, from the evidence bundle; finalize diffs against the shipped public set; a gap = a blocking finding *for that round*) | **Recommended pair with rung 2.** Cannot be gamed by editing prose — you must actually touch the surface. |
| 4 | CI fail-closed register gate (execution-surfaces-style build gate) | **Likely over-engineering / mis-placed** — CI has no Sandbox; a build gate can only check that an *entry exists*, not that a surface was *exercised*. Reserve for later only if the rung-2 brief is shown to drift. |

The sweet spot is **2 + 3**: rung 2 *prescribes* (tells the verifier what to
hit) and rung 3 *verifies* (fails the round on an untouched public surface).
Rung 2 alone is prescription the agent could skip; rung 3 alone doesn't tell the
agent where to look. Together they are prescribe-and-verify, both built from
artifacts/evidence that already exist, neither adding a CI register.

### T3. The route-manifest is necessary but not sufficient (critical limit)

A coverage source drawn *only* from `route-manifest.snapshot.json` would re-miss
exactly the class that motivated this doc: the trust surfaces (Security &
Privacy, Skins, Memory) are **not HTTP routes** — they are UI journeys/claims
tracked by `CoreSurfaceCatalog` / `check-ui-step-coverage`. So "derive coverage
from what ships" must union *endpoints* (routes) with *UI/trust surfaces*
(surface catalogs) and, arguably, *published claims* (README/docs MCP + privacy
sections). Inventorying which registries feed the coverage set — and resisting a
new bespoke register when three sources already exist — is a design-turn task.

### T4. Tier-routing as the connective principle (Q2 + Q4 + Q5 unified)

The deepest reframing: the question is **not** "does the Sandbox cover every
surface." It is "does *some* verification tier cover every surface, and is the
Sandbox spending itself on the surfaces only it can reach?" `/mcp` *protocol
correctness* may be better owned by a host-side integration test (one already
exists — `StdioMcpTransportIntegrationTest`); the Sandbox's unique, non-
duplicable value is the *clean-install, real-GUI, real-external-agent* view —
e.g. "can a freshly-installed user actually discover and connect an external
agent to `/mcp`," and "is the privacy panel honest on a machine with real disk-
encryption state." This gives two routing rules the design can adopt:

- **Route each *surface* to its cheapest sufficient tier** (unit / live-stack
  API / Sandbox), so the Sandbox is not a dumping ground.
- **Route each *finding* to its cheapest sufficient gate tier** — Q5's
  "convergence" isn't one mechanism but a *router*: backend/API regressions →
  host unit/live-stack test; UI-truthfulness → ui-shot/RAIL step assertion;
  Windows-trust/first-run findings (SmartScreen, scary panel) → *not* CI-gateable
  (no clean Windows in CI), so they stay durable must-watch items in the coverage
  set. The contract: every confirmed finding is *either* promoted to an automated
  gate in its natural tier *or* recorded as a durable Sandbox must-watch — and the
  choice is explicit, so the loop stops re-finding the same class.

### T5. The host↔Sandbox handoff: facts, not conclusions (Q3)

The Sandbox's fresh-session-no-source-code design is *already* a structural
independence mechanism stronger than "a different human looks" — the in-Sandbox
agent cannot see the committer's reasoning, git history, or memory. The design
tension is precise: the verifier should stay **ignorant of the committer's
*reasoning*** (independence) while becoming **informed of what *changed***
(coverage). A generated per-round brief resolves both — it carries *facts*
(surfaces/claims that shipped or changed this candidate) without *narrative*
(why, or what to conclude), reusing the `validation-mode.md` generation seam.
Keep the open-ended "organize by user journey, find where a user is confused"
exploration; add the derived coverage set as a *must-touch floor*, not a script.

### T6. Automation boundary: mechanize the ladder, keep the judgment (Q4)

The install click-through, Windows security prompts, and GUI trust-surface
judgment are irreducibly manual — they are the Sandbox's whole point. But
install → port-discovery → health/status/runtime snapshot → evidence-hash is
mechanical and repeated every round with no in-Sandbox helper (the host has
`capture_evidence`/`validate_evidence`; the Sandbox has none). A small staged
evidence harness that runs the ladder and emits a *machine-readable coverage +
evidence bundle* would make rounds repeatable and comparable and would *produce
the rung-3 coverage signal for free*. Principle: **mechanize capture, keep
interpretation.** Today's Rules 1–18 blur these — "how to capture X"
(mechanizable) is interleaved with "what a good/bad result looks like"
(judgment).

### T7. The content structure is three-part, not two

Q1 frames the split as durable-vs-per-round (two parts). Investigation suggests
three: **(a)** durable method/rules (the skill, de-dated); **(b)** a *generated
per-round input brief* (what shipped/changed + the must-touch coverage set —
rung 2); **(c)** a *per-round findings/convergence record* (the output, mirroring
`cut-a-release.md`'s convergence tempdoc). The alpha.NN history currently welded
into (a) is, post-cutover, pure noise for the *public* release path (there were
no public alpha users, so upgrade-from-alpha coverage is moot) — it belongs in
(c)-style dated history, not the durable surface.

### T8. Broader invariant this points to

The repo has run an anti-decay campaign across layers: doc-decay
(docs-maintenance / consult-register), rule-decay (tier-register meta-loop),
packaging-log decay (`cut-a-release.md`). **The validation surface is the last
untreated rolling-log holdout.** The recurring shape: *any hand-maintained list
meant to mirror a canonical/generated source drifts silently — make it derive,
or fail-closed on drift, or split durable-from-dated.* 728 is projection-vs-fork
applied to verification, and closing it extends the invariant to its last corner:
**a verification surface decays exactly like the artifact it verifies, so it
needs the same decay control.**

### T9. Honest alternatives and scope guards

- **Minimal patch (the floor):** add `/mcp`, fix the installer size, add a
  cutover note. Rejected as the *end-state* by the two-instance argument, but it
  is the correct *first commit* — it de-risks the imminent 0.2.0 round while the
  larger split lands.
- **Radical: retire the 30 KB skill**, keep a thin durable method, and let the
  generated per-round brief carry everything candidate-specific. Cleanest
  end-state; bets on the generator being built. Worth holding as the north star.
- **Unattended-scripted round:** Windows Sandbox is scriptable (`.wsb` +
  logon command); the 374 harness already points here. How much of the round
  (scripted install + API ladder + evidence bundle) could run *unattended*,
  leaving only GUI-judgment for the interactive agent? Connects T6 and Q4.
- **Scope guards:** the generated-brief idea sits on the boundary of the release
  *loop* (owned by `cut-a-release.md`) and the *launcher* (host tooling). 728
  should specify the brief's *contract* (what facts it carries) and let the
  loop/launcher own the *generation step* — do not re-open the release loop's
  outer shape or 726's asset-set build.

## Design (settled 2026-07-14)

The end-state mirrors, **at the validation layer**, what `cut-a-release.md`
already did at the release-loop layer: a single durable surface that does not
accrete, per-round bounded artifacts, and coverage that *derives from what
ships* instead of being remembered. It is deliberately the sibling of the
release-loop redesign, not a parallel invention (see §Reach). Three parts plus
one cross-cutting boundary. Kept general — mechanism, not implementation.

### Part A — Split the surface: durable method vs. dated history

The Sandbox docs stop being a rolling log. Stable **harness knowledge** — staging
mechanics, runtime-manifest port discovery, the journey-ordered method, the cheap
sanity-ladder-then-truthfulness philosophy, GPU/NVML *characteristics*, and the
capture/judgment rules — becomes a **single durable "how a candidate is verified"
surface** that does not accrete per round. Everything dated — the `alpha.5`→
`alpha.27` regression minutiae, the "alpha.27 already proved the core path" mission
anchor, round-N citations, and the stale installer-size numbers — moves **out** into
**per-round bounded records** (one per candidate, mirroring 726's "each release's
convergence tempdoc"). Post-cutover this dated content has no public-path value
(there were no public alpha users, so upgrade-from-alpha coverage is moot), so it is
history, not method. Resolves Q1.

### Part B — Coverage follows shipment (projection, not fork)

Two coupled mechanisms, tier-aware — the theorized rungs 2 + 3, never rung 4:

- **B1 — Derived per-round coverage brief (prescribe).** At stage/cut time,
  *generate* a "surfaces this candidate must exercise" brief by **unioning the
  surface sources that already exist** (derisked 2026-07-14 — the shapes are
  confirmed; see §Derisk): endpoint **cohorts** from `route-manifest.snapshot.json`
  (every route carries `cohort`; a *new cohort with no coverage entry* is the
  drift signal) plus `/mcp`'s real `audience` from the runtime manifest; the
  user-facing UI/trust surfaces from `CorePlugin.ts` (all `id:`/`placement:` entries
  — RAIL **and** the DEEPLINK panels Skins/Memory, one committed TS file, no build)
  and the `CORE_USER_INTERACTION_SHAPES` set (the escalation ladder); and published
  claims mapped to surfaces via a small curated table (the claim-bearing files are
  root `README.md`, `docs/reference/security/threat-model.md`,
  `docs/reference/mcp-production-server.md`, `SECURITY.md`, `packaging/mcpb/README.md`
  — prose is not parsed). Because it is *derived*, a newly-shipped surface appears
  automatically — the hand-maintained fork dissolves. Reuses the launcher's existing
  generated-authority seam (`validation-mode.md`), so it is an extension of staging,
  not new machinery. **The union is load-bearing:** a route-only brief would re-miss
  exactly the trust panels (not routes) that were hand-added last time — and derisk
  found *why*: the existing coverage gate (`check-ui-step-coverage.mjs`) walks **only
  RAIL** ids, leaving DEEPLINK/member surfaces structurally invisible. So B1 **reuses
  that gate's `CorePlugin.ts` enumeration but generalizes it to all placements**,
  covering the DEEPLINK panels the gate can't see, rather than building a parallel
  register (extending the CI gate itself is a flagged follow-on — different domain,
  ui-shot step coverage; see §Derisk). The brief is **tier-filtered** — surfaces a host integration test
  already owns (e.g. MCP *protocol* correctness, via the official MCP Inspector CLI
  — see §Research) are marked "covered elsewhere," so the Sandbox spends itself only
  on what it uniquely validates (clean-install, real GUI, real external agent). The
  brief carries **facts, not conclusions** (what shipped/changed, not why or what to
  conclude), preserving the fresh-session verifier's independence while curing its
  coverage-blindness. Resolves Q2, Q3.

- **B2 — Empirical coverage assertion at finalize (verify).** The round emits which
  surfaces it *actually* exercised; for **endpoints** this is empirical, not
  self-reported — derisk confirmed a structured per-request signal exists
  (OpenTelemetry `http.<method>.<path>` spans → `traces.ndjson` with `http.route`),
  which the validation round captures by running with
  `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed` (opt-in; default `none` — the launcher
  sets it via a `setx` in the `.wsb` LogonCommand). UI/trust surfaces
  stay evidence-backed (screenshot per surface). Finalize diffs exercised-vs-must-
  touch, and an untouched public surface is a **blocking finding for that round**.
  This cannot be satisfied by editing prose — the surface must be touched. It is the
  fail-closed half, placed **where coverage actually happens (the round)**, not in
  CI: a CI build gate has no Sandbox and could only check that an *entry exists*, not
  that a surface was *exercised* — rung 4 is rejected as mis-placed over-engineering.
  Reserve a CI register only if B1 is later shown to drift.

### Part C — Monotonic convergence: every finding gets a regression home

This part **is 726 §Long-term's deferred "monotonic Sandbox loop" design pass**,
absorbed here (726 owns the *asset* register; 728 owns the *loop*). A confirmed
finding cannot be marked resolved without a linked regression home — but the home
is **tier-routed**, not always a CI test:

- backend/API regression → host unit / live-stack test — for `/mcp`, an official
  MCP Inspector CLI conformance step (e.g. "`/mcp` answers `tools/list` on a clean
  install"; see §Research), not a hand-written assertion;
- UI-truthfulness finding → ui-shot / RAIL step assertion;
- Windows-trust / first-run finding (SmartScreen, a scary panel) → **not**
  CI-gateable (no clean Windows in CI) → recorded as a **durable must-watch** that
  B1 re-injects into every future brief.

The contract: every confirmed finding is *either* promoted to a gate in its natural
tier *or* becomes a durable must-watch — explicitly one or the other. A class, once
found, is thereafter either gated or always-re-checked; it cannot silently return.
This is `audit-without-test` promoted from prose to a process invariant on the
release-convergence loop, exactly as 726 framed it. Resolves Q5.

### Cross-cutting — the automation boundary: mechanize capture, keep judgment

Resolves Q4. The mechanical ladder (install → port discovery → health/status/
runtime snapshot → evidence hash → **coverage bundle**) becomes a small staged
in-Sandbox harness that *also emits the B2 coverage signal for free*. The
irreducibly manual parts — install click-through, Windows security prompts, and the
honesty judgment on GUI trust surfaces — stay with the interactive agent. This
de-blurs today's Rules 1–18, which interleave "how to capture X" (mechanizable)
with "what a good/bad result looks like" (judgment).

### Scope, and what this design orphans (teardown owned by THIS tempdoc)

Per the design discipline, the orphaned content's removal is 728's own work, not a
later sweep:

- **Orphaned — remove/relocate when Part A lands:** the `alpha.5`–`alpha.27`
  rolling-log content across `scripts/sandbox/sandbox-CLAUDE.md`,
  `sandbox-environment.md`, and `sandbox-start-SKILL.md` — the dated per-alpha
  regression notes, the "alpha.27 proved the core path" mission anchor, the round-N
  citations, and the stale ~770 MB installer-size expectations. These move to
  per-round history or are deleted; they do not stay welded into the durable surface.
- **Extended, not replaced:** the `scripts/sandbox/**` staging machinery
  (`sandbox-launch.py`, the SciFact/`.claude` staging, `validation-mode.md`
  generation) — sound; B1/the harness extend it.
- **Explicitly out of scope (not orphaned by this doc):** the release *loop* outer
  shape (`cut-a-release.md`); 726's asset-set build **and its two-mode `generate|
  verify` asset register** (a different domain — asset-hash provenance, not
  validation coverage); GPU/CUDA runtime mechanics; the product surfaces' own
  behavior (728 governs *whether/how they are verified*, not what they do).

## Decided vs. still-open

**Decided (this doc):** the three-part end-state above, the automation boundary,
and the orphan list. The mission becomes a durable surface + per-round records
(A); coverage is **derived-and-empirically-verified**, not prose or a hand
checklist, and **not** a CI register (B); convergence is monotonic via
tier-routed regression homes (C).

**Still open — implementation-level, for the plan/implementation turn:** the exact
file layout of the durable surface vs. per-round record; the precise union/format of
the derived brief and which claim-sources it reads; the shape of the evidence/
coverage bundle the harness emits; and how B2's "exercised set" is captured. These
are realization details, not architecture, and do not reopen the end-state.

## Design reach & principles

This design is **not** novel apparatus — it is the repo's existing anti-drift shape
applied to the one layer that still lacked it. Recorded as a principle (recognized),
not built as generalized structure (deliberately deferred).

### P1 — Projection-not-fork, applied to verification coverage

**Statement.** Any hand-maintained list meant to mirror what the system ships or does
will drift silently; make the list *derive* from the canonical surface source and
*fail closed* when the two diverge. "What we verify" must be a projection of "what we
ship," never a second authority that drifts.

**This is an instance, not a new principle.** It is the same shape as the
execution-surface register (`SearchTrace` referencers), the SSOT catalog dual-copy
sync gate, the UI-step-coverage gate, and 726's two-mode `{artifact → generated-from}`
asset register. 728 conforms to that family; B's coverage register is a *sibling* of
726's asset register (shared shape, disjoint domain — coverage vs. asset-hash
provenance), so they must **not** be merged into one mechanism.

**Where else it applies (candidate scope, not to be built now):** any place a
"what-to-cover" list is hand-kept against a moving target — API-contract test
coverage vs. the route manifest; eval-corpus coverage vs. the shipped field set;
docs coverage (the consult-register already does this). **Existing violations:** the
Sandbox mission is the current one (this doc's motivation); 726 already flags
`model-registry.v2.json` drift (409 D-series) as the asset-domain instance, deferred.

**Earns its keep if:** after B lands, a newly-shipped public surface appears in a
round's must-touch brief with *no edit to the Sandbox docs*, and B2 catches at least
one real omission prose would have missed. **Retire if:** across several releases the
derived brief and a plain hand-checklist never diverge (then derivation is ceremony —
collapse to the checklist), or the surface sources themselves become unreliable enough
that garbage-in defeats the projection.

### P2 — Mechanize capture, keep judgment

**Statement.** In an agent-driven verification harness, separate the mechanical,
repeatable capture (install → probe → snapshot → hash → coverage bundle) from the
irreducible human/agent judgment (is this UI honest? is this prompt scary?). Automate
the first; never pretend to automate the second.

**Where else it applies:** any verification surface with both mechanical and
judgment halves. The host already embodies it (`capture_evidence` / `validate_evidence`
MCP tools); the Sandbox is the gap this design closes. **Earns its keep if:**
round-to-round capture becomes repeatable and comparable and per-round setup shrinks.
**Retire if:** the harness needs bespoke per-round tweaking anyway — that would prove
capture and judgment were never cleanly separable here.

### P3 — Monotonic convergence = every finding gets a regression home

**Statement.** A qualification loop converges only if a confirmed finding cannot be
closed without a linked regression home (a gate in its natural tier, or an explicit
durable must-watch). This is `audit-without-test` promoted from a prose rule to a
process invariant. Already named by 726 §Long-term; 728 conforms rather than
reinventing. **Earns its keep if:** the same finding-class stops recurring across
rounds. **Retire if:** the "durable must-watch" escape hatch becomes where every
finding lands (then the invariant is theater, catching nothing that a checklist
wouldn't), or it blocks legitimate resolution of findings that genuinely admit no
regression home more often than it prevents recurrence.

## Research (2026-07-14) — MCP validation is externally tooled and mid-flight

A bounded external pass, scoped to the one fast-moving surface the design touches:
how an MCP server is validated end-to-end from an external client. (The rest of the
design — the durable/dated split, projection-not-fork coverage, monotonic
convergence — is internal architecture or mature practice where global research
would not move it, so it was not researched.) Three findings sharpen Parts B/C:

1. **The mechanism for verifying `/mcp` is a standard tool, not bespoke.** The
   **official MCP Inspector** (`@modelcontextprotocol/inspector`, MCP-team-
   maintained, **MIT**) has a non-interactive `--cli` mode that connects to a remote
   Streamable-HTTP endpoint by URL and runs protocol methods — e.g.
   `npx @modelcontextprotocol/inspector --cli <base-url>/mcp --transport http
   --method tools/list` (and `--method tools/call --tool-name … --tool-arg …`). It is
   `npx`-invoked, so it is **not vendored** — no code enters the repo and the
   license-and-notices check is not implicated. This becomes the concrete realization
   of Part B's tier split: **host tier** = an Inspector-CLI conformance step in CI
   against a running backend; **Sandbox tier** = the same tool proving a *freshly-
   installed* app's `/mcp` is reachable and answers `tools/list` as a real external
   client would — the clean-install view only the Sandbox has (complemented by the
   MCPB→Claude-Desktop path that 726 already owns). Part C's `/mcp` regression home is
   this conformance step.
2. **Prefer an external tool + capability discovery over spec-revision-specific
   assertions — because the spec is mid-flight.** Streamable HTTP (single POST/GET
   endpoint) landed in the 2025-03-26 spec and was retained in 2025-11-25, but the
   2026-07-28 release-candidate makes MCP **stateless at the protocol layer**
   (removes protocol-level sessions / the `Mcp-Session-Id` header). Hand-coded
   session-header assertions would rot across that change; discovering capabilities
   via `tools/list` through the Inspector is version-resilient. This is a robustness
   input, not a scope change: it argues *for* the external-tool choice in finding 1.
3. **An emerging community conformance standard to align the MCP coverage entry
   with.** A "MCP Server Conformance Checklist" proposal (modelcontextprotocol
   discussion #2682) is converging on pre-publish verification criteria. The coverage
   set's MCP row (Part B) should track that checklist rather than invent its own
   criteria — keeping our verification a projection of the ecosystem standard, the
   same projection-not-fork discipline one level out.

**Out-of-scope conformance observation (logged, not fixed — product/655 territory).**
The spec's Streamable-HTTP endpoint contract (single path supporting POST **and**
GET, plus a MUST to validate the `Origin` header against DNS-rebinding) is exactly
the kind of gap an Inspector run would surface on a clean install — an illustration
of *why* B2's empirical check earns its place. The product's own conformance to it is
owned by 655/the product, not by this doc; noted in the observations inbox.

**Sources:** [MCP Inspector (GitHub, MIT)](https://github.com/modelcontextprotocol/inspector) ·
[Inspector docs](https://modelcontextprotocol.io/docs/tools/inspector) ·
[Transports spec (2025-11-25)](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports) ·
[Conformance-checklist proposal #2682](https://github.com/modelcontextprotocol/modelcontextprotocol/discussions/2682)

## Derisk (2026-07-14) — Part B's artifacts confirmed; conform, don't build parallel

Before implementing, the load-bearing assumptions in Part B (which all reduce to
"does the artifact the derivation assumes exist, as data, in the shape I need?")
were probed read-only. All answered **yes**, with two mechanism reframes and one
simplification already folded into §Design above:

- **The trust surfaces are derivable, and an existing fail-closed gate is the seam
  to conform to.** `scripts/ci/check-ui-step-coverage.mjs` already implements the
  exact "every surface has a coverage row or is exempt" pattern — but it walks **only
  RAIL** ids, which is the *mechanistic root cause* of why the DEEPLINK panels (Skins,
  Memory) were the silently-forgotten ones. **In scope:** the brief generator reads
  `CorePlugin.ts`'s `id:`/`placement:` entries generalized to *all* placements, so it
  enumerates the DEEPLINK panels the RAIL-only gate can't see — no Java parse needed
  (`CorePlugin.ts` carries DEEPLINK ids in the same shape). **Follow-on (not this
  PR):** tightening the CI `check-ui-step-coverage` gate itself to DEEPLINK — a
  different domain (ui-shot step coverage) with a downstream tail (authoring ui-shot
  steps for those panels).
- **Route audience is not broadly available as data** (only ~8 runtime-axis
  transports carry it; `/mcp` is one), so B1's route half is **cohort-drift**, not an
  audience filter — matching what the manifest actually carries.
- **B2 is empirical for endpoints** via `traces.ndjson` (with
  `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed` set on the round), self-reported for UI
  surfaces.
- **`route-manifest.snapshot.json` is committed + freshness-gated**; the new
  coverage register (which doubles as the must-watch/exempt list) is thin (most of the
  set piggybacks on existing artifacts).

**Confidence for the remaining work: ~7.5/10** — no unknown can now invalidate the
approach; the cost is integrating several committed artifacts (route-cohort JSON,
`CorePlugin.ts` surface ids, the shape set) into a new coverage register + generator,
plus the durable/dated split's judgment risk. Integration-heavy, no novel algorithms.

**Recommended for implementation:** Opus (medium–high effort) for the integration
design + the coverage register + the durable/dated split; delegate bounded mechanical
chunks (the brief generator, the `traces.ndjson` coverage-diff, the capture harness,
Part A prose relocation) to Sonnet.

## Implementation (2026-07-14)

Landed on `worktree-release-asset-set` (pre-PR). What shipped, mapped to the design:

- **Part A — durable/dated split + teardown.** `scripts/sandbox/sandbox-CLAUDE.md`,
  `sandbox-environment.md`, and `sandbox-start-SKILL.md` rewritten to durable harness
  method only. All `alpha.5–27` regression minutiae, the "alpha.27 proved it" mission
  anchor, round-N citations, and the stale ~770 MB size are **removed** (tombstoned —
  pre-0.2.0 alpha history stays in git, not the durable surface). Added: the `/mcp`
  product endpoint to the mission + endpoint table, an MCP-Inspector-CLI validation
  journey, go-public/0.2.0 awareness, and pointers to the generated brief. The
  18 durable validation rules are kept, de-dated.
- **Part B1 — derive coverage.** New `governance/sandbox-coverage.v1.json` (modeled on
  `ui-step-coverage.v1.json`) classifies every shipped cohort/surface/shape.
  `scripts/sandbox/gen_coverage_brief.py` derives the per-candidate must-touch set from
  the committed artifacts (route-manifest cohorts, `CorePlugin.ts` surfaces incl.
  DEEPLINK, `coreInteractionShapes.ts`) and **fails closed** on an unclassified surface.
  Wired into `sandbox-launch.py` (`stage_coverage_brief`), which aborts staging on drift.
- **Part B2 — verify empirically.** `scripts/sandbox/check_coverage.py` diffs the
  must-touch manifest against exercised endpoints (`traces.ndjson`) + evidence
  screenshots, exiting non-zero on an untouched `sandbox`-tier surface. Tracing enabled
  for the round via `JUSTSEARCH_HEAD_TRACING_LEVEL=detailed` `setx` in the `.wsb`
  LogonCommand.
- **Part C — convergence.** The must-watch entries live in the same register and are
  re-injected into every brief; the routing contract (finding → gate-in-tier *or*
  must-watch) is documented in the durable mission doc.
- **Cross-cutting — capture harness.** `scripts/sandbox/collect-evidence.ps1` (staged)
  does port discovery + the API ladder + the `/mcp` Inspector check + snapshots.

**Verification (all green):** `gen_coverage_brief.py --check` classifies 22 cohorts /
16 surfaces / 5 shapes (exit 0); removing the `mcp` register row trips the fail-closed
drift error (exit 1). Full `sandbox-launch.py --no-launch` dry-run stages
`coverage-brief.md` + `coverage-manifest.json` + `collect-evidence.ps1` and writes the
tracing `setx` into the `.wsb`. Docs regenerated (`llmstxt-generate`, `skills-sync`);
no lingering `alpha.NN`/stale-size/wrong-env-var refs. No Java/FE/gradle changes → no
build impact; no discipline-gate registration needed (the register follows the
standalone-script-read precedent of `ui-step-coverage.v1.json`).

**Post-review hardening (2026-07-14).** A refute-first review found the first-pass
coverage check could pass *without* the surface being exercised — four fixes landed and
are verified:
- **F1 — surface coverage credits only screenshots.** The harness writes `api-*.json`
  snapshots into the evidence dir; substring-matching a surface token (`health`) against
  those falsely marked `core.health-surface` covered with zero screenshots. `check_coverage.py`
  now matches surface/shape tokens against image files only. *Verified:* api-json-only
  evidence → `core.health-surface` UNCOVERED; a real `01-health.png` → COVERED.
- **F2 — critical cohorts require their product route.** Cohort matching was any-route OR,
  so `cohort:mcp` passed on `GET /api/mcp/token` alone. Added `requiredRoutes` (register:
  `mcp → ["POST /mcp"]`; propagated to the manifest; AND-checked). *Verified:* token-only
  trace → `cohort:mcp` UNCOVERED (`required route(s) not exercised: ['POST /mcp']`);
  `POST /mcp` → COVERED. **This is the corrected form of "the presenting symptom is caught":**
  a round that hits MCP's token flow but never the endpoint itself now fails.
- **F3 — fail closed on partial extraction drift.** A register id no longer derived from
  source was a non-fatal warning, so a regex under-match could silently shrink the
  must-touch set. `STALE` is now a hard error (exit 1) — register↔source drift fails in both
  directions. *Verified:* a bogus extra register cohort → exit 1; the clean tree still exit 0.
- **F4 — traces survive teardown + finalize is documented.** `collect-evidence.ps1` now
  copies `traces.ndjson` into the mapped evidence dir; the host-side finalize command is
  documented in `sandbox-CLAUDE.md` and `cut-a-release.md`. *Verified:* PS parses clean;
  the command appears in both docs.

## Long-term viability & residual risks (2026-07-14)

An honest assessment of *why* this design should age well, and where it does not — so a
future maintainer inherits the limits, not just the claims.

### The core durable property: a silent failure became a loud one

The predecessor failed the worst way — **silently**. Coverage was a hand-copied list of
what to check; when `/mcp` shipped, nothing updated it and nothing complained. The
design's central long-term property is that **it detects its own staleness and stops**:
coverage *derives* from the artifacts the app ships, and the check fails closed in **both**
directions — a shipped surface with no classification fails, and a classified surface no
longer present in source fails (F3). Drift stops being "a gap someone eventually notices"
and becomes "a failure that blocks the round." A system that notices when it is out of date
outlives one that depends on sustained human vigilance, which erodes across time and staff
turnover.

### Reinforcing strengths

- **Derivation can't fork.** A hand-maintained second list drifts by construction — the
  exact failure that killed this surface, `cut-a-release.md`'s rolling log, and others. One
  canonical source removes the second copy. This is the repo's already-proven
  projection-not-fork move (P1), not a novel one-off — a pattern validated at several other
  layers is a safer long-term bet than cleverness.
- **It rides on already-fresh artifacts.** The route manifest is regenerated and gated
  elsewhere; the surface file is maintained by frontend work regardless. The coverage tool
  inherits their freshness rather than adding a new thing to remember to update.
- **Verification is empirical, not a checkbox.** B2 asserts what was *actually exercised*
  (traces + screenshots), so it can't be satisfied by editing prose — resisting the
  "checklist exists but nobody runs it" decay.
- **Bounded size + named retirement conditions.** Dated history goes to throwaway per-round
  notes, so the durable surface can't re-bloat. And P1–P3 each carry an explicit "retire
  if…" — the off-ramp that stops a mechanism from becoming self-justifying apparatus.

### Residual risks (where it is *not* strong)

- **Judgment is relocated, not eliminated.** Derivation catches *that* a surface exists; a
  human still writes *how* to validate it and *which tier* it belongs to. The fail-closed
  check forces a *decision*, not a *good* one — e.g. lazily marking a new surface `exempt`
  passes the gate while defeating its intent. This residual half still rests on review
  discipline (weaker than mechanism). It is the smallest defensible fork, not zero fork.
- **Caught at the next round, not instantly.** The check runs at release-staging, not on
  every commit (a CI register gate was deliberately deferred as over-engineering). A surface
  can sit unclassified until the next round; if releases are infrequent, that window is long.
- **The UI-surface check is heuristic.** It proves a screenshot *tokened* for a surface
  exists — not that the surface was genuinely, correctly validated. Automating a
  fundamentally human judgment ("is this panel honest?") has a hard ceiling; the endpoint
  half (traces) is firmer than the UI half (screenshots).
- **Machinery has its own maintenance cost.** Three moving parts (generator, checker,
  register) can grow their own bugs — the refute-first review found four on the first pass.
  The bet is that mechanical drift-detection is cheaper to keep correct than human vigilance.
  Probably right, but a bet, not a certainty.

### The honest framing

Strong **in proportion**: the part that actually decayed — the mechanical "did we remember
every shipped surface" — is now derived and fail-closed, so it cannot silently rot. The part
that stays fragile — human validation judgment — was always irreducibly human, and the design
funnels it into a small spot guarded by a fail-closed check. It does not make bad validation
impossible; it makes *forgetting to validate a shipped surface* impossible-to-do-silently.
**Falsifier:** if, over several releases, the derived must-touch set never diverges from what
a hand-kept checklist would have contained, the derivation is ceremony — collapse it back to a
checklist (per P1's retirement condition).
