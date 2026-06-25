---
title: "Go-public — capability-descriptor truthfulness: the public 'what JustSearch is/does' narrative is an unsourced fork that undercounts shipped reality, and the stack-claim guard's scope stops at the canonical corpus (not the public blast radius)"
type: tempdoc
status: "in-progress — founder decisions taken 2026-06-25. Done: #2/M1 (stack-claim gate scope extended to README + docs/business), #4 (SECURITY.md threat scope), #5 (NON-GOALS/free-chat), #3/M2 (agent-doc prose-tier mitigation). #1 README full-promotion deferred to 634 (minimal React→Lit applied now to keep the extended gate green). See §Decisions & execution log."
principle: "canonical-authority-and-projection, capability-narrative instance — unlike the *factual* projections 633 governs (benchmark table, download-source, CSP, which project from declared sources), the *capability narrative* ('what JustSearch is / does') has NO declared source, so every public doc hand-authors it and they drift in one direction: undercount. The fix is to (a) establish a canonical capability descriptor the public docs project from, and (b) extend the existing stack-claim guard's scope from the canonical corpus to the public blast radius. Conforms to 633 (publication instance) + 579 (canonical-vs-code drift); the new facet: the most public surface (README, funding apps) sits OUTSIDE the one guard that would catch its single live falsehood."
created: 2026-06-25
updated: 2026-06-25
related:
  - 631-go-public-publish-machinery
  - 632-go-public-licensing-legal
  - 633-go-public-launch-content
  - 634-go-public-cutover-transition
  - 579-canonical-doc-drift-remediation
  - 597-search-result-count-truthfulness
---

> NOTE: Noncanonical doc (notes/ideas). May drift. Verify against docs/explanation +
> docs/reference + code before trusting. This is a **plan**, not an implementation —
> no item is started; re-verify if `main` has moved.

# 650 — Go-public: capability-descriptor truthfulness

## Purpose

A drift-class investigation + remediation spec for the **public-facing descriptor of
"what JustSearch is and does."** The starting signal was a from-scratch descriptor
derivation (independent of prose, against code + the live stack): the derived descriptor
disagreed with the descriptors currently shipping across the go-public surface. A
verification pass confirmed the public corpus **under-describes the product** on its own
lead wedge, and carries **one live falsehood** that the existing guard does not reach.

This doc:

1. Names the two distinct representation problems on the public surface (root cause).
2. Records the verified ground-truth capability descriptor — the *source* the public
   docs should project from (it does not exist as a declared artifact today).
3. Inventories the confirmed drift, by severity, with `file:line`.
4. Specifies remediation: content fixes **and** the structural (mechanism) fix.

It is deliberately a **projection of 633's principle**, not a new authority — see
§"Relationship to existing work."

## Decisions & execution log (2026-06-25, founder-directed)

| Decision | Choice + status |
|---|---|
| **1 — README React line** | Full README promotion stays with **634**; but because M1 extends the gate to `README.md`, a minimal in-place `README.md:23` React→Lit fix was applied now (harmlessly overwritten by the 634 promotion) so the gate is green. ✅ |
| **2 / M1 — extend gate scope** | Approved. `check-frontend-stack-claims.mjs` now also scans `docs/business/**` + root `README.md`; added `stale` to the historical-marker exemption so meta-mentions of the staleness stay legal. Gate green (134 files). ✅ |
| **3 / M2 — undercount prevention** | Founder: **agent documentation is the lever, not a sync gate.** The canonical agent-reachable capability descriptor is `docs/explanation/01-system-overview.md` (enriched 2026-06-25 with the agent + MCP-server framing). `agent-guide.md` carries no capability descriptor (nothing to fix); CLAUDE.md left untouched (anti-bloat rule — already points to doc 01). **Honest tier:** prose-tier mitigation (~70%), chosen deliberately over an M2-tier-2/3 gate for a solo-dev launch. ✅ |
| **4 — SECURITY.md threat scope** | Fact-check: canonical `threat-model.md` already covers `POST /mcp` + `justsearch_ingest` + session-token gating (lines 24/78/81/94-96). Gap was only the public `SECURITY.md` scope list → added the MCP server + agent-action surfaces + a pointer to the threat model. ✅ |
| **5 — NON-GOALS vs free-chat** | Founder: update NON-GOALS. Softened "Not a general-purpose chatbot / not an open-domain assistant" → "Not *built to be* a general-purpose chatbot; a free-chat mode is available but the focus/optimization is grounded, cited answers." Now consistent with the shipped `core.free-chat`. ✅ |

React present-tense sites fixed this cycle: `README.md:23` (public) + two in a private funding draft (sidecar).

**Explicitly NOT a wait-for-evidence deferral:** M2 escalation to a *gate* is a cost/benefit call deferred for a solo-dev launch — the agent-doc fix (#3) addresses undercount *propagation* now; the defect is not parked, only the heavier enforcement tier is.

## Follow-on findings (autonomous completeness sweep, 2026-06-25)

A falsification pass on the remediation *itself* — "what did the fixes leave inconsistent, and what's the same class I haven't swept?"

1. **Second instance of the stale-technical-claim class — chat-model name (579-class).**
   `docs/explanation/05-ai-architecture.md` (e.g. lines 15/167/226/457) + `06-configuration-ssot.md:82`
   name the **retired `Qwen3VL-8B-Thinking`** as the current/default generative LLM. The actual default
   is `Qwen3.5-9B` — the only chat model on disk, and already correct in `model-inventory.md:177` +
   `legal/ai-runtime-and-model-redistribution.md:79`; **no `Qwen3VL` remains in `modules/*/src/main`**
   (code fully migrated; only docs are stale). This is the React class on a different axis, predicted by
   579. It is **canonical-vs-code fact drift → 579's remit** (not 650's stack-scope/undercount), but it
   **reinforces 650's meta-thesis**: M1's gate is framework-specific (`React`/`Zustand`/`.tsx`); a model
   name has no enum to hardcode, so the general fix is the **M2-tier-2 mechanism — project model-name
   claims from the registry** (`model-registry.v2.json` / `model-inventory.md`), not a second bespoke
   lint. Logged to the observations inbox; remediation = a *careful* reconciliation of 05/06 (the
   reasoning/Thinking discussion may be model-specific — not a blind find-replace).
   **Two confirmed instances now prove the class** (`structural-defects-no-repeat` needs only one).

2. **Maintain-obligation closed.** M1 changed `check-frontend-stack-claims.mjs` scope, so
   `tier-register.md` row 33's scope description was stale → updated to name the public surface;
   `prose-tier-register` meta-gate re-run green.

3. **Free-chat claim verified, not guessed.** Decision 5's NON-GOALS edit rests on `FreeChatShape`
   (`…/conversation/shapes/FreeChatShape.java`): "plain talk to the LLM… bare system prompt… No
   ContextInjectors" — confirms it genuinely does **not** search documents. The softened non-goal is accurate.

4. **Existing user-facing undercount is unrepaired (decision-3 boundary).** The agent-doc lever (#3)
   prevents *future* undercount propagation but does **not** repair already-shipped docs. The worst is
   `SSOT/docs/help/ai-features.md` — a doc titled "AI Features" that omits the entire LLM surface
   (Q&A/chat/summarize/extract/agent/MCP). Recommended as the top remaining **content** fix before
   launch; founder call (C2 in §Remediation).

**Update (2026-06-25, proceeding as directed):**
- **#1 reconciled in `05`/`06`** — model name → `Qwen_Qwen3.5-9B`; the `05:226` measurement was kept
  but **re-attributed** to "the prior `Qwen3VL-8B-Thinking` default" (provenance preserved, not
  falsified). Broader same-class sites left with explicit disposition: `environment-variables.md:296`
  + `14-ai-pack-spec.md:190` (stale example paths — minor, not done this pass);
  `reference/inference-runtime-register.md:129` (a **measured** Qwen3VL baseline — must NOT be
  relabeled; re-measuring on `Qwen3.5-9B` is the register owner's job);
  `legal/ai-runtime-and-model-redistribution.md:215` (internally inconsistent with its own `:79`/`:178`
  — left for founder/legal review).
- **#4 partially done (C2)** — `ai-features.md` + `getting-started.md` enriched with the LLM / agent /
  MCP surface; `CHANGELOG` still undercounts.
- **ACC-003 reconciled** — verified the multi-agent question: tempdoc 211 (M0) is `done` and the handoff
  *infrastructure* is wired (`AgentProfile` / `agentProfiles` on `AgentRequest`, `AgentHandoff` + prompt
  swap), but profiles are caller-supplied and the runtime default stays single-agent (`"primary"`) with
  no default roster / UX. Added a dated clarification to `decisions.md:68` ("single-agent by default"
  holds; "deferred until 211" satisfied for M0; productization still deferred). The earlier inbox flag is
  resolved.
- **Model-drift sweep tail** — `environment-variables.md:296` example path → `Qwen3.5-9B`.
  `14-ai-pack-spec.md:190` left as-is (its `sha256`/`sizeBytes` are the old file's — renaming the
  filename alone would create an inconsistent example, so it needs the real Qwen3.5-9B hash/size or
  nothing); `legal/…:215` left for founder/legal review.

**All five gated items resolved (2026-06-25 — founder confirmed "all"):**
1. **M2 gate BUILT** — `scripts/docs/check-model-freshness.mjs`: registry-projected, flags the retired
   `Qwen3VL` family presented as current; skips code fences; exempts historical / reconciliation /
   baseline / current-model lines; excludes `/reference/issues/` + `model-inventory.md` (the model
   ledger, self-policing). Wired into `.github/workflows/docs-lint.yml` next to the frontend-stack gate.
   Detection logic unit-proven (flags stale-current, exempts the legit cases); green at 133 files.
   Deliberately scoped to model-name **freshness** only — NOT capability-coverage (decision 3 chose
   prose-tier for the undercount class, so a coverage gate would contradict it). Placed as a
   docs-hygiene lint (sibling of `check-privacy-claims`), not a Hard Invariant — no tier-register row.
2. **legal `:215`** → `Qwen3.5-9B` (was internally inconsistent with its own `:79`/`:178`).
3. **pack-spec `:190`** — left as an illustrative, internally-consistent *historical* manifest example
   (fence-skipped by the new gate; renaming without the matching sha256/size would be worse, and no
   current hash was available).
4. **CHANGELOG `:17`** — enriched to include the on-device LLM (Q&A / chat / summarize / agent) + the
   production MCP server.
5. **search-quality skill** — synced + committed separately (the pre-existing register→skill drift the
   owner had left unsynced).

Broader model-name sweep also finished: `08-observability:1057` (alias example) + `how-to/ai-pack-authoring:37`
(command example) → `Qwen3.5-9B`. The new gate now prevents recurrence of this entire class.

## Root cause (the headline): two representation problems, two fix shapes

### A. The stack-claim falsehood survives a guard **scope gap** (verified)

The single load-bearing architectural fact — the frontend is **Lit**, not React
(ADR-0032) — is asserted *falsely* in present tense on the **most public files**:

- `README.md` — "a **React/Vite** web UI" / "Provides a **React/Vite** web UI packaged
  through a Tauri desktop shell." (the #1 public front door)
- a **private funding draft** (sidecar) — two present-tense **React** stack claims (one in
  prose, one in the tech-stack line).

A guard for exactly this exists (`scripts/docs/check-frontend-stack-claims.mjs`, Hard
Invariant #5, tier-register row 33). **Verified at source** (`scripts/docs/check-frontend-stack-claims.mjs:78-88`):
it walks only `docs/explanation`, `docs/reference`, `docs/how-to` (decisions and
`reference/issues/` deliberately excluded). It does **not** scan `README.md`,
`docs/business/**`, `SSOT/docs/help/**`, or root `*.md`. So the lint is **green while the
front door lies** — the guard's scope stops at the canonical corpus and never reaches the
public blast radius. This is one documented instance proving a *scope-class* defect; per
`structural-defects-no-repeat`, it does not need a second.

Fix shape: a declared fact (ADR-0032) **and** a guard (579) already exist — the only gap
is **scope**. Cheap, unambiguous: extend the walk roots.

### B. The capability narrative is an **unsourced fork** → systemic undercount

633 governs the *factual* public claims (benchmark table from `release.v1.json`,
download-source from `model-registry.v2.json`, CSP/privacy) as **projections of declared
facts** — "never a hand-copied fork." But **"what JustSearch is / does"** — the capability
narrative — has **no declared source**. There is no canonical "set of shipped
capabilities" artifact. So every public doc hand-authors its own descriptor, and they
drift in **one consistent direction: undercount.** Three independent audits of the
public + sidecar surfaces each found the same
dominant defect: the docs sell *"search + cited RAG + MCP retrieval backend"* and omit:

- the **agent's gated actions** (ingest / browse / file-ops; logged, risk/trust/consent-gated);
- the **conversation modes beyond RAG** (free chat, extract, summarize, agent-run, workflow-run);
- in the user-facing help docs, even the **production MCP server**.

The strategic cost: the lead positioning is *"a private retrieval backend for your AI
agents."* The undercounted surface (agent actions + MCP server + multi-mode) **is** that
wedge — so the under-description blunts the exact differentiator the launch leads with.

Fix shape: **no source exists** to project from. Remediation = (a) establish the canonical
capability descriptor (this doc seeds it, §"Verified ground truth"), then (b) bring the
public capability docs up to it, and (c) decide whether a coverage check is worth
building (design option, §Remediation-M2).

## How this was found — and why first-principles derivation is not self-correcting

The descriptor was derived bottom-up (dependency graph + live API + wire contracts + a
real query and agent chat), deliberately isolated from prose. That method produced a
correct *core* but **two of its own drift errors**, which are themselves evidence for §B:

- **False negative (undercount, wrong-direction):** the derivation concluded the outbound
  MCP server was "latent / not shipped — the arrow points in, not out." It is in fact a
  **stable, wired production feature** (`modules/ui/.../api/mcp/McpToolSurface.java`, 907
  LOC; route `POST /mcp` at `LocalApiServer.java:561`; doc `reference/mcp-production-server.md`,
  `status: stable`). Root cause: a negative asserted from a search that never grepped
  `modules/ui` (where the Head's API routes live). The self-imposed "no docs" rule —
  correct for *deriving* — blinded the derivation to a *capability* only the doc + the
  ui-module code reveal.
- **Overclaim:** "a multi-agent system (planner/executor/organizer)." Reality: handoff
  *infrastructure* present (`AgentHandoff`, `handoff_to_*`, agent profiles,
  `AgentTurnPolicy`) but **primary-centric by default** (`activeAgentId` defaults to
  `"primary"`). More than "single-agent," less than "a multi-agent system."

Lesson (agent-discipline, recorded here as motivation — not a CLAUDE.md rule): a "what is
X" derivation needs an explicit **actors-and-surfaces** pass and a **falsification** pass
("what does this descriptor fail to predict?"), or it under/over-counts on its own. This
is why a *declared* capability source matters: derivation, prose, and the audit all drift;
the code is the only non-forking authority, and nothing projects the public narrative from
it.

## Verified ground truth — the capability descriptor to project from

This is the source the public capability docs should align to (each claim verified against
code/live stack this cycle; see §Appendix for citations):

- **Local-first, private desktop app** (Tauri shell + **Lit** web components; loopback-only
  127.0.0.1; 3-process Head/Body/Brain). Supported inputs: text, Markdown, PDF, DOCX, HTML,
  source code.
- **Hybrid search:** Lucene BM25 + SPLADE learned-sparse + dense embeddings + cross-encoder
  rerank; multilingual by construction (ICU, no per-language levers — ADR-0043).
- **Optional on-device LLM** (Llama-3.1-8B / Qwen, GPU-accelerated, ~8 GB install), exposed
  as **one interaction window with several modes**: cited RAG Q&A, free chat, extract,
  summarize, agent-run, workflow-run.
- **Agent that takes gated actions, not just answers:** ingest, browse folders, file
  operations — logged, conflict-handled, behind a risk/trust/consent lattice. Primary-centric
  with handoff infrastructure for sub-agents (NOT a headline "multi-agent system").
- **Ships a production MCP server** (`POST /mcp`, `status: stable`): external agents — Claude
  Code, Cursor, Claude Desktop, VS Code Copilot — connect and drive `justsearch_search`,
  `justsearch_answer` (RAG), `justsearch_browse`, `justsearch_ingest`, `justsearch_status`.
- **Also an MCP host** (consumes external MCP servers' tools into its unified operation
  registry) — **opt-in, off by default** (`justsearch.mcp.host.config` unset ⇒ no servers).
- Architecturally: every capability is one `Operation` (= MCP Tool) on a unified substrate;
  the UI, the local LLM, and external MCP tools are all invokers of the same surface
  ("AI is just another contributor").

## Findings — public-surface audit (by severity)

P0 = live falsehood (credibility/legal risk at launch). P1 = undercount of the lead wedge.

| # | Sev | Surface | `file:line` | Class | Note |
|---|-----|---------|-------------|-------|------|
| 1 | P0 | Root README | `README.md` ("React/Vite" ×2) | STALE/FALSE | Front door; outside lint scope (see §A) |
| 2 | P0 | Private funding draft (sidecar) | _(sidecar, 2 sites)_ | STALE/FALSE | Already self-flagged in other sidecar docs |
| 3 | P1 | **AI help doc** | `SSOT/docs/help/ai-features.md` (whole doc) | MISSING | A doc titled "AI Features" omits the entire LLM surface (Q&A/chat/summarize/extract/agent/MCP) — worst user-facing offender |
| 4 | P1 | Getting-started | `SSOT/docs/help/getting-started.md:3-5` | UNDERCOUNT | "knowledge search engine"; no Q&A/agent/MCP in the first doc a user reads |
| 5 | P1 | **SECURITY.md** | `SECURITY.md:30-34` | MISSING | Threat-scope list omits the two largest local attack surfaces: the MCP server (exposes `ingest` to external agents) + the agent's file/browse actions. Not cosmetic — verify against the 633 M2 threat-model doc's coverage |
| 6 | P1 | CHANGELOG | `CHANGELOG.md:17` | UNDERCOUNT | "hybrid search + MCP retrieval backend" — omits LLM modes + agent actions |
| 7 | P1 | Sidecar strategy / README draft | _(sidecar)_ | UNDERCOUNT | The capability undercount is present even in the *intended new* public README (otherwise accurate, incl. Lit) |
| 8 | tension | NON-GOALS (sidecar draft) | _(sidecar)_ | OVERCLAIM (inverse) | "Not a general-purpose chatbot … not an open-domain assistant" contradicts the shipped `core.free-chat` mode — reconcile (positioning choice vs factual error) |

Good news (balance): the **intended new public README** (a sidecar draft) is
already **correct on stack (Lit)** and **accurate on the MCP server** (names the five tools
+ the `claude mcp add justsearch` line). No `Zustand` claims; no fabricated overclaims; no
"multi-agent" overclaim anywhere — drift is consistently *under*, the safer direction. The
team is already self-aware about the React staleness (633 item #4 fixed `CONTRIBUTING.md`;
other sidecar docs already flag the funding draft).

## Remediation

### Content (the descriptor fixes)

- **C1 (P0) — React → Lit, 3 sites.** Decision required: patch live `README.md` in place,
  **or** rely on 633 item #5 (promote the sidecar README draft to root, which is
  already Lit-correct) and only fix the funding draft here. Recommend: fix the funding draft
  now; let the README promotion close #1 (avoid editing a file the cutover plan replaces).
- **C2 (P1) — bring capability docs up to the ground truth.** Priority order by reader:
  `SECURITY.md` (threat-scope, #5) → `ai-features.md` (#3) → `getting-started.md` (#4) →
  `CHANGELOG.md` (#6). Each projects from §"Verified ground truth", adding the
  agent-actions + conversation-modes + MCP-server lines its audience needs.
- **C3 (tension) — reconcile NON-GOALS vs free-chat (#8).** Founder/product decision:
  either soften the non-goal or scope `core.free-chat` in the public copy.

### Mechanism (the structural fix — the durable part)

- **M1 (recommended, cheap, justified by proof-by-example) — close the stack-claim scope
  gap.** Extend `check-frontend-stack-claims.mjs` walk roots
  (`scripts/docs/check-frontend-stack-claims.mjs:78-80`) to include `README.md`,
  `docs/business/**`, and root `*.md` (with the same `reference/issues/`-style historical
  exclusions for known archived drafts if needed). This converts the React-class from
  honor-system on the public surface to gate-enforced — the front door can no longer lie
  while CI is green. Aligns with 633's "irreversibility premium": blast radius scales with
  audience, so the most public files deserve the *strongest* guard, not none.
- **M2 (design option, decide — do not default-build) — capability-coverage.** §B's root
  cause is that the capability narrative has no declared source. Options, smallest-first:
  1. **Seed-only:** treat §"Verified ground truth" here as the reference and update by hand
     (no gate). Lowest cost; relies on discipline (the thing that just failed).
  2. **Single canonical descriptor + headline-sync:** author one canonical capability list
     (e.g. a `capabilities.v1` projection, or promote §"Verified ground truth" into a
     canonical doc) and reuse 633's `register-headline-sync` / doc-claim-lint pattern so
     public hero descriptors must mention the declared capability set (catches *undercount*,
     not just falsehood).
  3. **Full coverage gate:** a lint asserting each public capability-bearing doc references
     the canonical capability set. Highest cost; risks over-DRY of authored prose (AHA:
     only unify what shares a reason to change — a NON-GOALS narrative and a help doc may
     legitimately differ). Likely over-engineering for a solo-dev launch; flagged, not
     recommended yet. *(This is a cost/benefit judgment on the gate, not a
     wait-for-more-evidence deferral of the defect — the undercount is already proven across
     8 surfaces; M1 + a chosen M2 tier should land regardless.)*

## Relationship to existing work (projection, not fork)

- **633 (go-public launch content)** owns the *factual* public projections (benchmarks,
  download-source, CSP) and the principle. This doc **applies that same principle** to the
  capability narrative — the representation 633 did not cover because it has no declared
  source. Reuses 633's `register-headline-sync` / doc-claim-lint as the M2 mechanism, not a
  new one.
- **634 (cutover transition)** owns the README root-promotion flip (closes finding #1 if
  executed). C1 must coordinate with 634, not race it.
- **579 (canonical-doc drift)** is the same defect class one scope inward: it remediated
  `docs/{explanation,reference,how-to,decisions}` and built the stack-claim lint. **M1 is
  literally extending 579's guard to the surface 579 left out.** This doc does not
  re-audit the canonical corpus.
- **597 / 649 (truthfulness family)** and **557–559, 565, 569 (presentation authority)**
  are the *runtime/UI* truthfulness axis (the app presenting truthful state). This doc is
  the *documentation/descriptor* axis (public prose vs shipped code) — sibling, distinct,
  no overlap.

## Open decisions (for the founder)

1. **C1 README:** patch in place, or defer to the 634 promotion flip and only fix the funding draft now?
2. **M1 scope-gap gate:** approve extending the stack-claim lint to README + `docs/business/**`?
   (Recommended — closes the one live-falsehood class.)
3. **M2 tier:** seed-only (1), canonical descriptor + headline-sync (2), or none-for-now?
   (Recommend tier 2; tier 3 likely over-engineering.)
4. **C2 ordering / scope:** is `SECURITY.md` (#5) already covered by the 633 M2 threat-model
   doc, or does its own scope list need the MCP + agent-action surfaces added?
5. **C3 NON-GOALS vs free-chat:** soften the non-goal, or constrain the public free-chat copy?

## Appendix — ground-truth evidence (primary-source)

- Outbound MCP server: `modules/ui/src/main/java/io/justsearch/ui/api/mcp/McpToolSurface.java`
  (907 LOC, tools `justsearch_{search,answer,browse,ingest,status}`); route
  `modules/ui/src/main/java/io/justsearch/ui/api/LocalApiServer.java:561` (`app.post("/mcp", …)`);
  `docs/reference/mcp-production-server.md` (`status: stable`).
- Conversation modes: `modules/app-services/.../conversation/CoreConversationShapeCatalog.java`
  (`core.rag-ask`, `core.free-chat`, `core.extract`, `core.agent-run`, `core.workflow-run`,
  + summarize shapes).
- Agent actions: `modules/app-services/.../agenttools/` (`SearchTool`, `BrowseTool`,
  `IngestTool`, `FileOperationsTool` + `FileOperationExecutor` + `FileOperationLog` +
  `ConflictStrategy`); risk-gated tool approval observed live (`agent_chat` toolCalls).
- Multi-agent (primary-centric + handoff): `AgentHandoff.java`, `handoff_to_*`,
  `AgentTurnPolicy.java` (`"primary"` defaults); ACC-003 in
  `docs/reference/issues/decisions.md:68` (frames multi-agent as deferred — possible drift,
  separately flagged to the observations inbox this cycle).
- Unified substrate: `modules/app-agent-api/.../registry/Operation.java` ("Operation = MCP
  Tool … exposed to UI, AGENT, CLI"); MCP host (inbound, opt-in):
  `modules/app-services/.../mcphost/` (`McpHostConfig` — empty default ⇒ off; live worker
  log `justsearch.mcp.host.config=<unset>`).
- Lint scope gap: `scripts/docs/check-frontend-stack-claims.mjs:78-88` (walks only
  explanation/reference/how-to).
