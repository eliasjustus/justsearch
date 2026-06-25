---
title: Window Taxonomy Convergence — one task, one window; don't fork the altitude axis
type: tempdocs
status: shipped
created: 2026-06-13
updated: 2026-06-13
---

# 578 — Window Taxonomy Convergence

> **Nature of this doc.** Design-theory genre (same as 557/559/567/570/577):
> the *correct long-term window taxonomy* for the whole app, derived from a
> live audit of every rail surface, with feasibility/phasing deliberately
> disregarded and the argument kept general (not implementation-level). Where
> 577 matures the *two* convergence-bound windows individually (Search, Agent)
> and defers their unification to its Goal 3, **578 is the taxonomy one
> altitude up**: it asks, of *all fifteen* current rail windows, which deserve
> to be their own window and which are facets of one task wrongly given
> separate homes. 577 Goal 3 (Search⊕Agent) is one row in 578's table; 578 is
> the frame Goal 3 lands inside.
>
> Live audit: 2026-06-13, dev stack at `127.0.0.1:5173`, 34-doc index,
> Meta-Llama-3.1-8B online, every rail surface visited and screenshotted via
> browser automation. Findings are recorded as evidence of a defect *class*,
> not a fix-list.

## 0. The thesis in one paragraph

The app presents **fifteen top-level rail windows, twelve of them in one
undifferentiated PRODUCT band** (the 571 altitude axis). That is far too many
for what the product actually is — two jobs (find things; ask the AI about
them) plus their supporting config and observability. The fragmentation is
not random: it is a single defect class — **a window minted per *facet* of a
task instead of per *task*.** Library and Browse are two faces of "the
corpus"; Skins, Theme Editor, Editor and Settings' theme block are four doors
to "how it looks"; Now, Health, Logs and Activity are four answers to "what
is/was the system doing." The correct taxonomy collapses each task to one
window under two governing laws — **one task, one window** and **never fork
the altitude axis** (PRODUCT / DIAGNOSTIC / TRUST stay distinct, 571) — taking
the rail from 15 to ~6. The single deliberate *non*-merge, Search vs Agent, is
non-merge *now* precisely because 577 has it queued as the most important
merge *later*, and only after each contract is matured.

## 1. Why a taxonomy theory now (and why separate from 577)

1. **577 proves the convergence loop but scopes itself to two windows.** Its
   own §0 names the broader surface ("one surface with one entry point and
   escalating intent tiers") but its Goals 1–2 deliberately touch only Search
   and Agent, and Goal 3 is held as a placeholder. The other thirteen windows
   have never been examined as a *set*. The rail bloat they create is the
   first thing a new user sees and the context every per-window design lives
   in — it deserves its own theory, not a footnote in Goal 3.
2. **The altitude axis already exists to govern this and is being
   under-used.** 571 derives PRODUCT / DIAGNOSTIC / TRUST from consumed
   authorities and the rail already bands by it — but with 12/15 windows in
   PRODUCT, the axis is doing almost no separating work *within* the band that
   matters most. The taxonomy question is largely "what is the right
   intra-PRODUCT structure," which 571 by construction does not answer.
3. **Unify-first would repeat the mistake one level up.** Just as 577 refuses
   to collapse Search+Agent before their contracts are understood, 578 refuses
   to prescribe a single mega-surface. It prescribes *task boundaries* and
   lets each task's window mature behind them — the same sequencing discipline,
   applied to the rail.

Related prior work (cite by slug per tempdoc README):
- `search-and-agent-window-convergence` (577) — matures Search + Agent
  individually; its Goal 3 is the Search⊕Agent row of 578's table.
- `surface-altitude-governing-axis` (571) — PRODUCT/DIAGNOSTIC/TRUST
  derivation; the law 578's diagnostics merge must not violate.
- `functional-core-user-authored-presentation` (569) — the presentation
  authoring surfaces (Editor/Theme Editor) whose rail status 578 questions.
- `llm-interaction-correct-structure` (561) — one interaction surface; the
  frame the Search⊕Agent row resolves inside.
- `per-instance-vs-global-frontend-scope` (574) — shared presentation
  primitives the consolidated windows compose.

## 2. Live inventory — fifteen windows, three bands (2026-06-13)

Generated from `RAIL`-placement surfaces in `CoreSurfaceCatalog.java`, grouped
by altitude. Each row records the window's *real job* and the overlap the
audit exposed.

| # | Window (surface id) | Band | Real job | Overlap tell |
|---|---|---|---|---|
| 1 | Search (`core.search-surface`) | PRODUCT | Instant hit-list (577 Goal 1, matured) | "Ask AI" button leaks into Chat |
| 2 | Chat (`core.unified-chat-surface`) | PRODUCT | Documents/Structured/**Agent**/History (577 Goal 2) | Agent tab *renders search results* — worse than #1 |
| 3 | Library (`core.library-surface`) | PRODUCT | Manage indexed folders, reindex, excludes | "the corpus" control plane |
| 4 | Browse (`core.browse-surface`) | PRODUCT | File-tree of indexed files | "the corpus" content view — same noun as #3 |
| 5 | AI Brain (`core.brain-surface`) | PRODUCT | Configure LLM runtime, model packs | duplicates Health GPU/VRAM; is config → Settings |
| 6 | Memory (`core.memory-surface`) | PRODUCT | AI's learned facts ("what it knows") | thin (one input); it is *the agent's* store |
| 7 | Now (`core.system-self-view`) | PRODUCT | "What's running right now" | near-empty when idle; it is status, not product |
| 8 | Skins (`core.presentation-gallery-surface`) | PRODUCT | Theme preset gallery | 1 of **four** theming doors |
| 9 | Editor (`core.presentation-editor-surface`) | PRODUCT | JSON declaration authoring (569) | theming/authoring |
| 10 | Theme Editor (`core.token-editor-surface`) | PRODUCT | Token editing (routes to #9) | theming/authoring |
| 11 | Settings (`core.settings-surface`) | PRODUCT | Mode, theme, a11y, density, default action | already *contains* a Theme section |
| 12 | Help (`core.help-surface`) | PRODUCT | FAQ + shortcuts + troubleshooting (static) | reference, not a workspace |
| 13 | Health (`core.health-surface`) | DIAGNOSTIC | Status + fixable issues + quick actions | **already embeds Logs as a tab** |
| 14 | Logs (`core.logs-surface`) | DIAGNOSTIC | Raw log stream | duplicate of #13's Logs tab; "see Activity" |
| 15 | Activity (`core.activity-surface`) | TRUST | Structured audit ledger (who did what) | "for raw output see Logs" |

**The clinching evidence is the cross-references the surfaces print about
each other.** Logs says "for action audit, see Activity"; Activity says "for
raw diagnostic output, see Logs"; Health embeds Logs as a tab; Help points at
Library and Health for fixes. The system already knows these are one concern
split across windows — it is telling the user to navigate between them.

## 3. The defect class — a window per facet, not per task

A *task* is a thing the user is trying to accomplish ("control what's
indexed," "make it look how I want," "find out why search is degraded"). A
*facet* is one view or sub-step of that task. The current rail mints a
top-level window per facet:

- "the corpus" → **Library** (control) + **Browse** (content) = 2 windows.
- "how it looks" → **Skins** (presets) + **Theme Editor** (tokens) +
  **Editor** (declarations) + **Settings**'s theme block = 4 doors.
- "what the system is/was doing" → **Now** (live) + **Health** (snapshot) +
  **Logs** (raw) + **Activity** (audit) = 4 windows that footnote each other.
- "the AI itself" → **AI Brain** (runtime config) + **Memory** (learned
  facts) = 2 windows, one of them a single input.

The cost is not merely visual. Every facet-window invents its own chrome,
header, empty state and naming — which is exactly the drift class 577 §2.5
caught *inside* the agent window ("three things named Activity"). 578 is the
same finding at rail scope: **facet-windows are the macro form of the
authority-fork the 553/561/571 register family exists to kill.** One task
with one window is one authority; one task scattered across N windows is N
authorities that will disagree (Library's root list vs Browse's tree;
Health's GPU readout vs AI Brain's; Now vs Health vs Activity on "what's
happening").

## 4. The two governing laws

**Law I — one task, one window.** A window earns rail status iff it is a
distinct *task* the user works *in*. Facets of one task are views/tabs/tiers
*within* that task's window, never siblings on the rail. Reference content
(Help) is not a task and does not earn a window at all.

**Law II — never fork the altitude axis.** A merge may collapse facets across
DIAGNOSTIC sub-views, but it may **not** blur a TRUST surface into a
DIAGNOSTIC one (571: TRUST ⟹ CORE provenance, must home adjacent-but-distinct).
The one place this bites is Activity (TRUST) vs Health/Logs (DIAGNOSTIC): they
may share a *window* only if Activity keeps its trust framing as a visually
distinct tab; if that distinction cannot be held, Activity stays its own
window and only Now/Logs fold into Health.

These two laws are deliberately the rail-scale restatement of 577's own two
moves: *bind to the canonical authority, not the surface identity* (→ one task
one authority one window) and *respect declared altitude* (→ don't fork the
axis).

## 5. The correct taxonomy — 15 → 6 (+ overlays)

### 5.1 Keep separate, by design — Search and Agent (rows 1–2)

This is 577's domain and 578 does not re-open it. Two windows **now** (the
2ms reactive throwaway loop vs the seconds-to-minutes stateful thread are
opposite interaction contracts — 577 §1); **one window in 577 Goal 3**, as
escalation tiers (type → instant hits → "Ask" → cited answer → "Delegate" →
run), *not* mode tabs, with the ephemeral hit-list never entering thread
history. 578's only contribution here: this is the *first* row of the
taxonomy, and its merge is the highest-value one — so the other merges should
share its primitives (evidence/citation render, the canonical record →
projection discipline) rather than invent parallel ones.

### 5.2 Merge — Library ⊇ Browse (rows 3–4)

"The corpus" is one noun. Library is the control plane (what's indexed,
reindex, excludes); Browse is the content view (navigate what's in it).
**How:** one **Library** window, two views — `Folders` (current Library) over
`Browse` (the tree) — reading the *same* root dataset two ways (Library's root
list and Browse's tree are one authority). The Browse tree is additionally a
scope filter for Search, so it needs no independent rail status.

### 5.3 Merge — AI Brain ⊇ Memory (rows 5–6)

Both are "the AI itself": Brain configures the runtime, Memory holds what it
learned. Memory is one input today — far below window weight. **How:** fold
Memory in as a section of AI Brain (defensible alternative: a settings panel of
Chat, since it governs agent behavior). Separately, AI Brain must stop
duplicating Health's GPU/VRAM/runtime *status* — Brain owns the *controls*,
the System window (5.5) owns the *readout*; the readout is a projection, not a
second copy.

### 5.4 Merge — one Appearance home (rows 8–11, theme parts)

The worst-fragmented family: **four** doors to how the app looks. **How:** a
single Appearance surface — cleanest as a section *inside Settings*, which
already carries a Theme block — with progressive tiers: pick a **skin** →
tweak **tokens** → (advanced, disclosed) author a **declaration** in the
editor. The 569 editor/token tooling is power-user authoring and belongs
behind an "Advanced" disclosure, never as primary rail icons in a search app.
Settings itself remains a window (preferences is a real task) and absorbs the
Simple/Advanced mode + a11y + density it already owns.

### 5.5 Merge — one System hub (rows 7, 13–15), altitude-banded as tabs

All four answer "what is/was the system doing" and already footnote each
other. **How:** one **System** window:
- **Health** (DIAGNOSTIC) absorbs **Now** — the live "what's running right
  now" becomes Health's top live-strip; the snapshot + fixable-issues + quick
  actions sit below. Now is too empty to stand alone.
- **Logs** (DIAGNOSTIC) — *remove the standalone rail item*; it is already a
  Health tab. Pure duplication today.
- **Activity** (TRUST) stays a **distinct, trust-framed tab** (Law II). This
  is the one merge to execute carefully; if the trust distinction cannot
  survive as a tab, Activity stays its own window and only Now/Logs fold into
  Health.

### 5.6 Demote — Help (row 12) is not a window

Static reference (FAQ, shortcuts, privacy). It needs no workspace or rail
slot; surface it as the existing "?" affordance / slide-over / command-palette
entry. The rail is for things you *work in*.

### 5.7 Net result

| Today (15) | Proposed (6 rail + overlays) |
|---|---|
| Search, Chat | **Search**, **Chat** (→ unify in 577 Goal 3) |
| Library, Browse | **Library** (Folders · Browse) |
| AI Brain, Memory | **AI Brain** (+ Memory) |
| Skins, Editor, Theme Editor, Settings(theme) | **Settings** (+ Appearance: Skin → Tokens → Declaration) |
| Now, Health, Logs, Activity | **System** (Health+Now · Logs · Activity-as-trust-tab) |
| Help | "?" overlay |

The real fix is the **PRODUCT band: 12 → ~4**. The diagnostic/trust
consolidation is secondary cleanup. Every merge is one of the two laws
applied; the only law that *constrains* a merge (rather than enabling it) is
Law II on Activity.

## 6. Relationship to 577 (and which doc owns what)

- **577 owns rows 1–2** (Search, Agent): their internal maturation (Goals
  1–2, shipped) and their unification (Goal 3, deferred). 578 treats the
  Search⊕Agent merge as a *given* and does not design it.
- **578 owns the other thirteen** and the rail-level frame Goal 3 lands
  inside. When 577 Goal 3 is theorized, it should consume 578's taxonomy:
  the unified Search⊕Agent window is *one* of the ~6 windows 578 prescribes,
  and the escalation-tier design must coexist with (not re-fragment) Library /
  AI Brain / Settings / System / the Help overlay.
- **Shared discipline:** both docs use the same two moves (authority-not-
  identity binding; respect declared altitude) and the same sequencing rule
  (mature behind a boundary; do not collapse before contracts are understood).

## 7. Boundaries and honest limits

- **This is a taxonomy theory, not a layout.** It prescribes *task
  boundaries* and which windows exist — not grid composition, density, or
  per-window visual design (that is 559/565/570 judgment-tier, honor-system
  measured-UX-audit work per tier-register rows 30–31).
- **Plugin-contributed surfaces are out of scope.** The rail admits plugin
  RAIL surfaces (Shell dispatcher); 578 governs the *core* catalog only. A
  plugin window's task-membership is the plugin author's call.
- **The Activity merge is a genuine open decision, not a foregone one**
  (§5.5 / Law II). Tab-within-System vs standalone is a 571 judgment the
  implementer must make against the live trust framing; 578 names both
  outcomes as acceptable and forbids only the blurred middle.
- **"Now" as PRODUCT is itself arguably a mis-derivation** (it consumes a
  live-status channel; 571 would lean DIAGNOSTIC). 578 folds it into Health
  regardless, which dissolves the question — but if Now ever stays standalone,
  its altitude should be re-derived, not assumed PRODUCT.
- **Verification of any eventual implementation** runs the full presentation
  gate suite (557/574/559) plus the 571 `surface-altitude` gate — and, because
  removing/merging rail surfaces changes the catalog, the `surface-altitude`
  and any rail-coverage gates must be re-green after each merge.

## 8. Open decisions for whoever implements

1. **Activity:** tab-within-System (Law II via visual trust framing) vs
   standalone TRUST window. (§5.5)
2. **Memory home:** section of AI Brain vs settings panel of Chat. (§5.3)
3. **Appearance home:** section of Settings vs its own (single) window with
   the three tiers. (§5.4)
4. **Help delivery:** "?" overlay vs command-palette entry vs both. (§5.6)
5. **Ordering vs 577:** whether the System/Library/Settings consolidations
   ship before, after, or alongside 577 Goal 3 (they are independent of the
   Search⊕Agent merge and could land first as low-risk rail decluttering).

---

## 9. The correct long-term design — what the merges are *made of* (theorized 2026-06-13)

> §§0–8 are the *taxonomy* (which windows merge). This section is the
> *substrate* (what a merge **is**, mechanically) — the correct long-term
> structure for the remaining work, derived after a source-true investigation
> of what already exists. Genre unchanged (design-theory: feasibility/phasing/
> mount-shape disregarded). Current-behavior claims verified against `main`
> 2026-06-13 (citations inline); re-verify before relying.

### 9.1 Investigation verdict — the substrate is ~⅔ already designed, and 578 is the documented trigger to build the missing third

The merges look like one operation ("put related windows together") but
decompose into **three distinct mechanisms**, two of which already have
shipped substrate and one of which is *designed-but-deliberately-unbuilt* —
and 578 is the exact condition that authorizes building it.

Source-true findings (the explore-before-implementing step):

- **The host/member relationship does not exist on the manifest.** `Surface`
  (`modules/app-agent-api/.../registry/Surface.java`) is a 10-field record —
  `id × presentation × audience × placement × consumes × mountTag ×
  provenance × stateSchema × riskTier × altitude` — with **no host / member /
  parent field** (grep confirms absent). `SurfaceConsumes` carries
  `conversationShapes` (561's chat-specific mechanism) but no member-*surface*
  concept. So "surface B lives inside surface A" is still unrepresentable.
- **571 §11 already designed exactly this** — a declared `members:
  List<SurfaceRef>` host/member relationship + an extracted tab primitive + a
  one-home integrity gate — then **§11.8 deferred it on proportionality**
  ("a single host does not earn a general mechanism — YAGNI"), recording the
  precise resumption condition: *"trigger-on-second-host: if a second surface
  ever needs to host members, build the declared `members` primitive then."*
  The single case (System Health hosts Logs) was instead done as a manual
  hand-rolled tab embed — §11.8's deliberate, contained "fake #2"
  (`HealthSurface.ts:25-28,267-549`).
- **The 561 "one interaction window" register already exists and is the
  Search⊕Agent substrate.** `governance/interaction-surfaces.v1.json`: *at
  most one visible USER RAIL/STAGE surface may consume a core interaction
  shape; DEEPLINK surfaces route into it.* The canonical surface is
  `core.unified-chat-surface`. Search is **not** yet a consumed interaction
  shape — it is a peer RAIL surface — which is exactly why Search⊕Agent is a
  pending merge, not a finished one.
- **Settings, Library, Help and Health are already 569 declaration-default
  surfaces** (`governance/declared-surfaces.v1.json`: each renders its
  region(s) through the DeclaredSurface engine, `activeBodyFor(regionId) →
  <jf-declared-surface>`). Four of 578's five hosts already render through a
  declaration engine — so composition must *compose with* declaration, not
  replace it.
- **565's `composition-surfaces` register is intra-surface, not host/member.**
  `composeGridStyles` governs multi-*zone* layout *within* one surface (the
  agent window's trace|reading|evidence frame). It is the governance template
  571 §11 borrows, but it does not express containment of one surface by
  another.

**Verdict:** the correct long-term design is **not new invention** — it is
(a) finishing the Search⊕Agent merge onto the *existing* 561 interaction-shape
substrate (577's job), (b) **building 571 §11's already-designed composition
primitive, now that 578 has met its own stated trigger**, and (c) extending
that primitive along the three axes 578's *real* multi-host workload forces
that the single-member case explicitly punted.

### 9.2 Three merge mechanisms, mapped

| 578 merge | Mechanism | Substrate today |
|---|---|---|
| Search ⊕ Agent (rows 1–2) | **Intent-tier unification** — Search becomes a consumed *interaction shape* of the one interaction window, intent-graded (type → ask → delegate) | 561 `interaction-surfaces` register **EXISTS**; Search not yet a shape. 577 Goal 3. |
| Library ⊇ Browse | **Host/member** (peer views) | 571 §11 primitive — **DESIGNED, NOT BUILT** |
| AI Brain ⊇ Memory | **Host/member** (embedded subordinate section) | 571 §11 — NOT BUILT |
| Settings ⊇ Appearance{Skin·Token·Declaration} | **Host/member** + progressive tiers | 571 §11 — NOT BUILT; members are 569 declared surfaces |
| System ⊇ {Now, Logs, Activity} | **Host/member, CROSS-ALTITUDE** | 571 §11 — NOT BUILT; Health⊇Logs is the §11.8 manual fake |
| Help → overlay | **Demotion** = `Placement` → non-rail/DEEPLINK + overlay affordance | `Placement` field **EXISTS** |

The shape of the remaining work falls out of the table: **one merge rides
finished substrate it isn't yet using (Search⊕Agent), four merges need the one
deferred primitive (host/member), one merge is a one-field placement change
(Help).** The whole of 578's "remaining work" is therefore *building 571 §11,
extended* — everything else is application of substrate that already ships.

### 9.3 The dominant mechanism: 571 §11 composition, now legitimately triggered

571 §11.8 deferred the primitive for one honest reason — **proportionality**
(§10's lesson: don't promote a single placement task into a general mechanism
on one proof-by-example). 578 does not re-argue against that lesson; it
**satisfies the resumption condition the lesson itself wrote down.** There are
now *four* independent hosts (`Library`, `AI Brain`, `Settings`, `System`),
each with one-to-three members, plus the §11.8 manual embed already accruing
as drift debt. "Trigger-on-second-host" is met four times over. This is the
difference between *inflating* one example into an axis (what §10 reverted) and
*amortizing* a designed primitive across the workload that finally demands it
(what §11.8 explicitly licensed). The §11 design is adopted wholesale:

1. a declared `members: List<SurfaceRef>` field on `Surface` (field-flow
   mirrors `altitude`: Java authority → Jackson → FE `surface.ts` →
   `CorePlugin.ts` omits, wire wins — §11.7 D4);
2. one extracted tabbed/section composite primitive the host *iterates its
   declared members* into (never hard-codes — the 561 anti-pattern);
3. a light integrity gate: **one home per surface** (a member may not also be
   a rail surface, nor a member of two hosts).

### 9.4 What 578's workload forces *beyond* 571 §11 — the genuinely new theory

571 §11 was scoped to **one host, one same-altitude member** (and said so).
578's five hosts break each of those bounds. These are the new structural
questions, and their correct resolutions:

**9.4.A — Multi-member hosts need a declared member *order*.** System has three
members (Now-as-Health-strip aside: Health, Logs, Activity); Settings'
Appearance has three tiers. The iteration mechanism must render N members in a
**declared order** (the host names `members` as an ordered list — the list
*is* the order), so member sequence is authored once, not re-derived per
render. This is a trivial extension (it falls out of `List<SurfaceRef>`), but
it must be *stated* so the tab order is an authority, not an accident.

**9.4.B — Cross-altitude composition: the operationalization of Law II, and
the bridge 571 §11.5 explicitly punted.** This is the deep one. System hosts
**Logs (DIAGNOSTIC) + Activity (TRUST)** in one window. 571 §11.5 deliberately
scoped the altitude/trust interaction *out* ("a separate reckoning"); §1–§9's
altitude axis assumed one altitude per *surface*; neither covers *a host
presenting members of different altitudes*. 578 cannot avoid it, and the
correct structure is:

> **A member carries its own derived altitude into the composite; the host may
> not flatten members to a single altitude. The composite's chrome must
> preserve each member's altitude framing — a TRUST member renders with trust
> framing even when it is a tab beside a DIAGNOSTIC member.**

This is precisely Law II (§4) made mechanical. It is the *missing weld* between
571's two halves: **composition is declared (§11), altitude is derived
per-member (§1–§9), and the composite is their union that preserves both.** It
extends the §11.3 one-home integrity gate with a second clause: *a member's
altitude is its own (derived from what it projects), never inherited from its
host* — so "Activity flattened into a diagnostics tab and losing its trust
status" becomes unrepresentable, not merely discouraged. The §8 open decision
(Activity as tab vs standalone) is then *dissolved*, not chosen: Activity may
be a member of System **iff** the composite preserves its TRUST framing; if the
primitive cannot guarantee that, the gate forbids the membership and Activity
stays standalone. The structure decides it, not taste.

**9.4.C — Composition relationship vs disclosure shape: is one `members` field
enough, or does composition need a *kind*?** 571 §11.3 ruled "tabs vs panes vs
accordion is mount-shape, out of scope." 578's hosts show the *relationships*
differ in ways that may be more than visual:
- **peer** (Library/Browse; System/{Health,Logs,Activity}) — co-equal members,
  switch between;
- **subordinate section** (AI Brain ⊇ Memory) — a member always-present beneath
  the host's primary content;
- **progressive tier** (Settings ⊇ Appearance: skin → token → declaration) — a
  *depth ladder* where each member discloses the next.

The honest resolution: **the relationship is one — containment; the disclosure
shape (tab/section/tier) is host-local presentation per 559 and stays out of
the manifest** — *unless* a shape changes integrity semantics. Progressive-tier
plausibly does (a tier should not be reachable without its parent), as does the
escalation-tier of Search⊕Agent (§9.4.D). 578's position: ship `members` as a
flat ordered list first (covers peer + section), and treat *tier* as a
candidate second relationship-kind only if the integrity gate needs to
distinguish it — do not pre-add a `kind` enum speculatively (the §10 lesson
again). **Genuinely open**, flagged not decided.

**9.4.D — Two iteration mechanisms over two declaration kinds — do they
unify?** This is the deepest structural question 578 surfaces. There are now
*two* "a host presents its declared parts" mechanisms:
- the **interaction window iterates its `conversationShapes`** (561) — except
  it doesn't: `UnifiedChatView` hard-codes the Documents/Schema affordance bar
  and never iterates the declaration (`UnifiedChatView.ts`; the 571 §11.7 D1
  finding), so it is *declaration without iteration* — the same drift the §11
  primitive exists to prevent;
- the **host iterates its `members`** (571 §11, to be built).

These are two iteration mechanisms over two declaration kinds (shapes vs member
surfaces). 578 asks — and does not answer — **whether they are one primitive
("a host iterates its declared parts, each part mounted through the shared
stage-mount path") or two.** The pull toward unification is strong (both are
"declaration → iterate → mount"); the pull apart is that a conversation shape
is a *mode of one surface* while a member is *another whole surface*. Resolving
this is likely 577 Goal 3's and 578's joint frontier. Naming it is the
contribution; deciding it needs both merges in hand.

### 9.5 Composition × the 569 declaration engine — orthogonal layers that compose

Four of five hosts are already 569 declaration-default. The two mechanisms are
**orthogonal and compose cleanly**: 569 governs *how a single surface's body
renders* (declaration → `<jf-declared-surface>`); §11 composition governs
*which surfaces nest inside which*. A member may itself be a declaration-default
surface; a host may render its own body via 569 and its members via the
composition primitive. The only interaction to state: the `declared-surfaces`
register's positive-coverage gate must keep firing on a surface *after* it
becomes a member (membership changes its home, not its render path). No
redesign of 569 is implied — composition sits one layer above it.

### 9.6 A side effect, not a goal: building the primitive retires existing drift debt

Two live fakes disappear once the primitive exists — which is corroboration the
primitive is real substrate, not scaffolding:
- **Health §11.8's manual Logs embed** (the hand-rolled tab strip, the
  explicit `<jf-log-surface>` tag, the SSE-suspend coupling) collapses into a
  declared `members: [core.logs-surface]` + the shared composite.
- **Chat's hard-coded mode bar** (if §9.4.D unifies the mechanisms) becomes an
  iteration over declared parts instead of a hand-coded affordance row.
578 does not *justify* the primitive by the debt (that would be the §10
inflation error in reverse); it notes the debt as evidence the trigger is
genuine.

### 9.7 The end-state in one picture

```
ONE interaction window  ── consumes interaction shapes ──▶  [hits ▸ ask ▸ delegate]   (561 substrate; 577 Goal 3)
Library      ── members ──▶ [Folders · Browse]                                         (571 §11 composition)
AI Brain     ── members ──▶ [Runtime · Models · Memory]                                (571 §11 composition)
Settings     ── members ──▶ [Preferences · Appearance{skin▸token▸declaration}]         (571 §11 + tier kind?)
System       ── members ──▶ [Health(+Now) · Logs(DIAG) · Activity(TRUST)]              (571 §11 + cross-altitude, §9.4.B)
Help         ── placement ──▶ overlay / command-palette (no rail slot)                 (existing Placement field)
```

Read top to bottom, the remaining work is: **one merge onto finished 561
substrate, four onto the one deferred-and-now-triggered 571 §11 primitive
(extended by §9.4.A–B, possibly C–D), one onto the existing `Placement` field.**
The correct long-term design is not a new axis and not a mega-surface — it is
*the declared composition primitive the codebase already designed, built at the
moment its own trigger condition is finally met, and welded to the altitude
axis it was originally (and wrongly) conflated with.*

### 9.8 Refined open decisions (superseding §8 where they overlap)

- §8.1 (Activity tab vs standalone) is **dissolved by §9.4.B**: it is a
  consequence of whether the composite preserves TRUST framing, gate-enforced —
  not a free choice.
- New: **§9.4.C** (flat `members` vs a `kind` enum for progressive/escalation
  tiers) — ship flat first, add `kind` only if integrity needs it.
- New: **§9.4.D** (unify member-iteration with shape-iteration, or keep two) —
  the joint 577-Goal-3 / 578 frontier; name now, decide with both merges built.
- New: **build-order** — the host/member primitive should be built against the
  *cheapest* host first to validate it (Library⊇Browse: two same-altitude peer
  members, no 569/trust complications), then System exercises §9.4.B
  (cross-altitude), then Settings exercises the tier question (§9.4.C). This is
  the §11 single-member case promoted to a deliberate multi-host validation
  ladder — the `audit-without-test` discipline applied to a substrate claim
  ("one host, one member works" is a hypothesis until N×M exercises it).

## 10. Confidence ledger — pre-implementation de-risk pass (2026-06-13)

A read-only de-risk pass (the 571 §8/§11.7 + 577 §1.8 discipline) re-verified
§9's load-bearing claims against `main` and the live dev stack *before* any
implementation — specifically to re-test the claims §9 carried from 571's
2026-06-10 design-era de-risk, and the two assumptions 571 never tested
(cross-altitude composition; embeddability of surfaces beyond Health/Logs).
Where this ledger corrects §9, **the ledger wins** (dated-history rule). Net:
**the substrate is present and lighter than feared on every mechanical axis;
two genuine design unknowns remain, both now named and scoped; neither is a
blocker.**

**U1 — cross-altitude composition: RESOLVED FAVORABLY (mechanically).**
`SurfaceAltitude.derive` (`SurfaceAltitude.java`) reads **only the surface's
own `consumes` + `placement`** — never members; the `surface-altitude` gate
(`scripts/governance/gates/surface-altitude/enforcer.mjs`) re-derives per
surface and its own remedy text says *"split the responsibilities into
separate surfaces"* (= the composition design). So a `System` host with
**empty `consumes`** + members `[Logs(DIAGNOSTIC), Activity(TRUST)]` produces
**no §4c conflict**. The FE `RESERVED_COMPONENTS` foreclosure on
`jf-action-ledger` (`authorableComponents.ts`) restricts only **user-authored
skins**, not kernel hosts. **New hard constraint:** the `members` field must
**not** be an input to `SurfaceAltitude.derive` — the host must not aggregate
member `consumes`. **Residual (the real §9.4.B work):** the rail expresses
TRUST as a *band* (`Shell.ts:2264-2267`); a TRUST member moved off-rail must
have its trust framing **relocated into the composite chrome** — a
visual/governance design, no longer a foreclosure fight.

**U2 — embeddability: RESOLVED (mechanism confirmed).** Surfaces are **lazily
registered**; `views/lazySurfaceRegistry.ts` exposes `ensureSurfaceLoaded(tag)`
(idempotent dynamic `import()` → `customElements.define`) and already
enumerates every candidate member. A host iterates members via that same path
the Stage uses (Shell `renderOneSurface`) — exactly 571 §11.3's "shared
stage-mount path." (A raw `createElement` probe returned `defined:false` for
all members, which *confirms* the lazy mechanism.) `WorkflowSurface` is already
a **shipped** precedent of "surface → mode of the one window."

**U3 — `members` field-flow: CONFIRMED LIGHTWEIGHT.** `RegistryController`
`handleSurfaces` serializes surfaces via a thin `projectSurfaceForWire`
raw-Jackson pass-through — **not** a typed `UISurfaceView` projection (560/564
left surfaces alone). "Add a record field" (§9.3 / 571 §11.7-D4) holds.

**U4 — rail/router: CONFIRMED.** Rail filters `placement === 'RAIL'`
(`Shell.ts:1757`); the router resolves by id/synonym with **no placement
gate**, so DEEPLINK members stay URL-routable. `Placement.DEEPLINK` is already
used in `CoreSurfaceCatalog.java` (line 471) — a working off-rail precedent.

**U5 — gate fallout: MOSTLY LOW, ONE NEW RISK.** No gate asserts a required
rail-surface *set*. **New:** the a11y-closure gate enforces exactly one `<h1>`
and one `main`, so a host embedding N members each rendering its own heading/
`main` will trip axe. **Composition needs a heading/landmark-demotion
contract** (an embedded member subordinates its heading, emits no second
`main`) — a previously-unnamed implementation requirement, likely an a11y-gate
extension.

**U6 — composition × 569: CONFIRMED orthogonal.** `check-declared-surfaces.mjs`
keys on **mount-engine presence**, not rail placement, so a declared surface
(Settings/Library/Help/Health) becoming a member keeps passing. §9.5 holds.

**U7 — tab primitive: STRONGER THAN ASSUMED.** Three `role="tablist"` forks
exist (`RetrospectivePanel`, `HealthSurface` §11.8 strip, **and the 569
`renderers/layouts/CategorizationLayout`**), no `jf-tab-group`. **Opportunity:**
a host might render members through 569's existing `CategorizationLayout`
(declared tabs) instead of a new component — unifying composition with the
declaration engine; decide before committing either path.

**Confidence rating: 7.5 / 10** for the four host/member merges + Help
demotion (up from ~5). Every mechanical blocker dissolved; the two residuals
(trust-framing-in-composite §9.4.B; a11y heading/landmark demotion) are named
and scoped. The Search⊕Agent merge (§9.4.D, 577 frontier) is the
lowest-confidence item and is **excluded** from this rating.

**Validated build-order:** Library⊇Browse (cheapest: same-altitude PRODUCT
peers) → System⊇{Health(+Now), Logs, Activity} (exercises §9.4.B + the a11y
contract; retires the §11.8 manual embed + the duplicate rail Logs) →
Settings⊇Appearance / AI Brain⊇Memory (the tier/section question §9.4.C) →
Help→overlay (pure `Placement`).

## 11. Implementation approach (planned 2026-06-13, approved)

The §9 design + §10 de-risk resolve into a concrete, approved implementation
plan. Genre shift: this section is build-level, not design-theory. Scope IN:
the composition primitive + the four host/member merges + Help demotion. Scope
OUT: Search⊕Agent (577 Goal 3) and §9.4.D (member-vs-shape iteration unification).
The full step-by-step plan with file paths lives in the session plan file; this
is its durable summary.

### 11.1 The substrate (Phase 0) — one primitive, built from existing parts

The merges are all one new capability: **a host surface presents member
surfaces as tabs.** Six pieces, only one genuinely new:

1. **`members: List<SurfaceRef>` on `Surface`** (`Surface.java`, last record
   component, compact-constructor null-guard, back-compat constructor so the 16
   `CoreSurfaceCatalog` sites compile unchanged). Wire is unchanged (raw Jackson
   → `string[]`); FE adds `members?: SurfaceRef[]` to `surface.ts` + threads it
   through `mergePluginSurfaceContributions`. This mirrors the `altitude`
   field-flow exactly (§10 U3). **Load-bearing constraint:** `members` must NOT
   feed `SurfaceAltitude.derive` (§10 U1) — pinned by a test.
2. **`<jf-surface-tabs>` (NEW component)** generalizing the proven HealthSurface
   §11.8 embed: an ordered tablist (host body = tab 0, members = tabs 1..N) that
   mounts the active member via the *existing* `ensureSurfaceLoaded(tag)` +
   `mountSurface(surface)` path. Only the active tab mounts → each member's
   SSE/streams are scoped for free. Adds arrow-key a11y (§11.2-D5). Each tab
   reads its member's `altitude` and applies altitude framing — this is how a
   cross-altitude host preserves Law II in-composite (§9.4.B).
3. **Rail member-exclusion** (`Shell.ts` rail build): filter out any id that
   appears in some host's `members`. Derived from `members`, not a hand-set
   per-member Placement.
4. **Member→host deep-link resolution** (`catalogResolver.ts`): a derived
   `memberId → hostId` map (built from `members` at boot) redirects a member
   deep-link to its host and selects that tab — generalizing the existing
   `RETIRED_SURFACE_ALIASES`, so membership is the single home-authority.
5. **One-home integrity gate** (`scripts/ci/check-surface-composition.mjs` +
   `governance/surface-composition.v1.json`, on the `check-composition-surfaces`
   template): fails if a member is also a RAIL surface, is hosted twice, or is
   unresolvable. The teeth that make Logs-as-a-stray-rail-icon unrepresentable.
6. **a11y demotion** (§10 U5): demote Memory/Activity/SystemSelfView `<h1>` →
   `<h2>` (host owns the single `<h1>`); extend `check-a11y-closure.mjs` to
   forbid a member emitting `</h1>` or `role="main"`.

`CategorizationLayout` (569) was evaluated for reuse and rejected — it is
JSON-Forms-specific and cannot host surfaces, so `<jf-surface-tabs>` is the one
honestly-new piece; everything else extends shipped code.

### 11.2 The merges (Phases 1–4), in the §10 validation ladder

- **Library ⊇ Browse** — `library` host, `members:[browse]`; tabs Folders/Browse.
  Cheapest case (same-altitude PRODUCT peers) → proves the primitive.
- **System ⊇ {Health, Logs, Activity}** — a **NEW `core.system-surface`**, made
  **DIAGNOSTIC** by consuming the head-log channel (a diagnostics hub is itself
  diagnostic, so it bands with diagnostics), hosting Activity (TRUST) as a
  trust-framed member tab. No derivation conflict (host has one signal).
  Retires the §11.8 manual Health|Logs embed and the duplicate rail Logs. The
  "Now"-into-Health live-strip fold is a content move (not composition) and may
  fast-follow.
- **Settings ⊇ Appearance** (`members:[gallery, editor, token-editor]`,
  progressive skin→tokens→declaration) **and AI Brain ⊇ Memory**
  (`members:[memory]`, subordinate section). Ships `members` flat — a `kind`
  enum is added only if integrity needs to distinguish tier from peer (§9.4.C).
- **Help → overlay** — pure `Placement.DEEPLINK` (precedent in the catalog) +
  the existing "?" affordance; no composition.

### 11.3 Key decisions baked in
1. **System host = a new DIAGNOSTIC `core.system-surface`** (resolves §9.4.B
   without a conflict; Activity keeps TRUST framing in its tab).
2. **Disclosure shape is host-local; `members` ships flat** (no `kind` yet).
3. **Member→host redirect is derived from `members`**, not hand-aliased.
4. **Standalone `scripts/ci` gate tier** (proportionate, per the §10/§11.8 lesson).

### 11.4 Validation discipline
Each user-visible phase (1–4) is **not done until validated in the real browser**
against the running dev stack (the worktree FE on ui-shot Vite :5174 → shared
backend, as 577 did): rail icon count drops, the host's tabs each mount, member
deep-links resolve to the host+tab, and axe confirms one `<h1>` / one `main`.
Plus, per phase: the 557/574/559 presentation suite + `surface-altitude` +
`check-declared-surfaces` + the new `surface-composition` gate; FE
typecheck/unit; Java `app-observability`/`app-agent-api`/`ui` tests.
No LLM needed (chrome/nav changes); `ai_activate` available if a member needs
live data. On completion: flip 571 §11 status (deferred → implemented).

## 12. Implementation outcome (2026-06-13) — primitive + 4 merges shipped & browser-validated

The composition primitive (§11.1) and **all four host/member merges** (§11.2) are implemented on
`worktree-578-window-convergence` and **browser-validated end-to-end** on a worktree-local dev stack
(`scripts/dev/dev-runner.cjs`, FE :5174). This **builds 571 §11** (deferred → implemented; flip its
status) — `members` is now a first-class declared relationship.

| Piece | Shipped | Live-validated |
|---|---|---|
| Primitive (§11.1) | `members: List<SurfaceRef>` (Java→raw-Jackson→`surface.ts`, `withMembers` wither); `<jf-surface-tabs>` (lazy `ensureSurfaceLoaded`+`mountSurface`, roving-tabindex a11y, per-tab altitude framing, host-own-body via `<slot>`); rail member-exclusion; derived member→host redirect (`memberHostAliases`, intercepts before exact-match) + one-shot `memberTabIntent` tab-select; `check-surface-composition` one-home gate; `check-a11y-closure` member no-`h1`/`main` rule; `members`-not-in-derive invariant test | unit + 7 component tests + gates |
| Library ⊇ Browse | Folders (slot) + Browse member | rail −1, tabs, tree mounts, deep-link → host+tab ✅ |
| System ⊇ {Health, Logs, Activity} | new DIAGNOSTIC `core.system-surface`; §11.8 manual embed retired | rail (−3+1), Activity TRUST tab keeps "Audit" framing **cross-altitude**, audit ledger mounts, one `<h1>` ✅ |
| Settings ⊇ Appearance | `[gallery, editor]` (Preferences + 2 tabs) | rail −2, tabs render ✅ |
| AI Brain ⊇ Memory | `[memory]` — **cross-source** (Memory is FE CorePlugin) | bare `core.memory-surface` deep-link → Java Brain host, Memory tab auto-selected, FE member mounts inside Java host ✅ |

**Rail: 15 → 9** product/diagnostic icons. Two decisions resolved during build:
- **§9.4.B host identity** → System is a NEW DIAGNOSTIC surface (consumes head-log channel); derivation
  stays per-surface so the TRUST Activity member raises no altitude conflict (the `members`-not-in-derive
  invariant). Confirmed live: Activity tab keeps trust framing.
- **§9.4.D cross-source (578 Option A)** → composition spans the Java-catalog / FE-CorePlugin boundary.
  The FE half (member→host resolution, rail-exclusion) already ran over the merged `listSurfaces()`; only
  the one-home gate needed merged-id awareness. Confirmed live: AI Brain (Java) hosts Memory (FE).

**Not done:** Phase 4 (Help→overlay) — deferred-with-rationale (a bare DEEPLINK flip risks command-palette
unreachability; needs a dedicated "?" affordance, a focused chrome change; Help is already a separated
bottom-rail utility). Optional follow-ups: the "Now"→Health live-strip content fold (§5.5, content not
composition); member-tab label polish (humanized "Presentation Gallery" vs the i18n "Skins" — route tab
labels through `present()`); consolidating the source-chip template. Nothing committed yet.

## 13. Post-review correction (2026-06-13) — System host is PRODUCT, not DIAGNOSTIC

A critical-analysis pass after §12 **reverses the §9.4.B / §11.3 / §12 "System = DIAGNOSTIC" decision.**
Making the hub DIAGNOSTIC required declaring `consumes.diagnosticChannels = [core.head-log]` that the
host never subscribes to (the Logs *member* does). That (a) registered a **false consumer hook** on
`core.head-log` via `SurfaceConsumerIndex` (the hub appeared as a head-log consumer it isn't), and (b)
violated 571 §8's load-bearing precondition that `consumes` is a *truthful manifest of what a surface
projects* — the altitude derivation would have rested on a fiction.

**Correction:** `core.system-surface` has **empty `consumes` ⟹ derives PRODUCT** — the honest "composes,
not fuses" pattern `core.system-self-view` already uses. It remains the single RAIL hub hosting
Health/Logs/Activity; the DIAGNOSTIC/TRUST distinction is preserved **inside** the composite via
`<jf-surface-tabs>` per-tab altitude framing (Activity keeps its "Audit" marker), which is where Law II
actually needs to hold. Cost: the hub bands in the product region rather than a diagnostics band — which
is moot, since the diagnostics band is otherwise empty (Health/Logs are members). Where §13 corrects
§9.4.B/§11.3/§12, **§13 wins** (dated-history rule). Rejected alternative: deriving host altitude from
members would reverse the §10-U1 "members-not-in-derive" invariant and re-open the cross-altitude
conflict. (Other post-review fixes: member→host tab-intent made a pub/sub so an already-active host still
switches tabs on a member deep-link; Settings rail-customization list now excludes members.)

## 14. Closure (2026-06-13) — Phase 4 + independent verification + status: shipped

All five phases are implemented and **browser-validated end-to-end** (worktree stack :5174); rail
**15 → 8** product/diagnostic icons.
- **Phase 4 (Help → overlay):** Help is `DEEPLINK` (off the rail catalog + rail-customization); a
  dedicated bottom-rail "?" affordance deep-links to it. Validated: rail has no Help surface, "?" opens
  Help & Support, URL routes.
- **Independent review (reviewer ≠ committer):** verdict **GO**, no blocking issues; invariants
  (members-not-in-derive in both Java + gate, honest PRODUCT System hub, member-gated `onRedirect`,
  one-home gate parsing, back-compat across 31 construction sites) verified. Its non-blocking items were
  fixed: (#1) CorePlugin placement drift — all 7 member surfaces set to `DEEPLINK` to match the wire
  (restores the single-home-authority invariant; closes the §13/#5 tech-debt); (#2) a real stale-pending
  wrong-tab bug — `requestMemberTab` now stores `pending` only if no live host handled it (+ test);
  (#3) wire round-trip regression tests added (FE `SurfaceCatalogClient.test` members-preserved; Java
  `RegistryControllerTest` members-on-the-wire); (#6) tab↔panel ARIA association (`aria-controls`/
  `aria-labelledby`).
- **Measured UX audit (axe across the composed windows):** caught + fixed a critical `aria-valid-attr`
  (the tablist attribute was wrongly named `aria-label-text` → renamed `tablist-label`). It also
  surfaced a **pre-existing** 569 issue (the `jf-declared-surface` engine renders declared regions as
  `<section role="main">`, duplicating the shell STAGE `main`) — confirmed on standalone Help, so it is
  independent of 578; logged to observations for a 569-engine follow-up.

**Verification:** `./gradlew build -x test` (compile + build-tier gates), affected Java module tests,
full FE suite (2883), and the scripts/ci gate sweep (surface-composition, a11y-closure, layout-purity,
declared-surfaces, composition-surfaces, presentation-purity, controls-a11y) all green.

**Known follow-ups — all CLOSED (2026-06-14, §15).** The four §14 follow-ups are implemented,
unit-tested, and browser/axe-validated on a worktree stack:
1. `<jf-surface-tabs>` `surfaceId`-mount unit test — added (covers `getSurface`→`mountSurface` + the
   unknown-id empty state).
2. 569 duplicate-`main` engine fix — `DeclaredSurface` now derives the NESTED landmark role
   (`nestedLandmarkRole`: STAGE→named `region`, never a 2nd `main`); measured axe = **zero**
   `landmark-no-duplicate-main`/`landmark-main-is-top-level`/`landmark-one-main` across
   Search/System/Settings/Help/Library, `mainCount == 1` each.
3. "Now"→Health content fold — `SystemSelfView` gained a `variant="strip"` (no own heading); Health
   embeds it at the top of its body. The standalone `core.system-self-view` RAIL surface is RETIRED
   (removed from the Java catalog + CorePlugin + lazy registry; deep-link aliases to the System hub →
   Health). Rail dropped one more icon; the wire ships 16 surfaces (was 17). Supersedes 575 §17 Face
   B's "standalone RAIL surface" — the compose-by-altitude capability lives on as Health's strip.
4. member-tab label polish — the 4 duplicated host `memberLabel` humanizers are replaced by the one
   display authority `present({kind:'surface', id})`; Settings tabs now read "Skins"/"Editor" (i18n),
   not "Presentation Gallery"/"Presentation Editor".

## 15. Remaining-work closure (2026-06-14)

The §14 follow-ups landed as three workstreams (C: labels; B: duplicate-`main`; A: Now-fold) plus the
prior surfaceId-mount test. Verification: `./gradlew build -x test` + affected Java module tests green;
full FE suite (2953) + 3 new test files (`catalogResolver`, `SystemSelfView`, `DeclaredSurface`); the
`scripts/ci` gate sweep (surface-composition, a11y-closure, declared-surfaces, layout-purity,
search-issuance, controls-a11y) + `surface-altitude` + `prose-tier-register` all green; and a live
worktree-stack browser + measured-axe pass confirming the rail reduction, the alias redirect, the
Health "Now" strip, the i18n tab labels, and one top-level `main` per surface. The Health "reading
'data'" empty-index banner seen during validation is a pre-existing Phase-2 member-mount/empty-index
issue (logged to observations), independent of this work.

## 16. Future directions — from tabs to true fusion (research, 2026-06-15)

> **Nature of this section.** Forward-looking research, not shipped work. §1–§15 merged windows as
> *tabs inside a host* (the 571 §11 host/member primitive) — the **interim** step. The long-term move
> is genuine **fusion**: merged windows stop being adjacent tabs and dissolve into one coherent surface
> (canonical example: 577 Goal 3, Search⊕Agent → one intent-graded entry point). Captured here after a
> multi-round internal+external research pass: *what we could do next* to polish, simplify, extend, or
> add UX on top of the taxonomy. Nothing is scoped or committed; the app is pre-production with no
> users, so all of it is optional and unranked. The tab *declaration mechanism* is deliberately out of
> scope (a stepping stone we expect to replace, not refine).

### 16.1 Research basis
Three parallel external surveys (search⊕chat "answer-engine" fusion; unified single-window / command-
driven workspaces; AI-native local-first knowledge tools) plus targeted reads on spatial/canvas UIs and
keyboard-first onboarding. Exemplars: Perplexity, Arc Search, Kagi (FastGPT / Quick vs Research
Assistant), Raycast (Quick AI), Linear / Superhuman / VS Code (palette + status bar + panes), NotebookLM,
DEVONthink 4, Obsidian Smart Connections, Khoj, AnythingLLM, LM Studio, Heptabase/Muse/tldraw (canvas).
Key sources: Perplexity citation pipeline (ziptie.dev), Arc "Browse for Me" (aiverse.design), Kagi
Assistants (blog.kagi.com), Smashing "Designing Agentic AI — UX patterns" (smashingmagazine.com), Calm
Technology principles (principles.design), VS Code status-bar UX (code.visualstudio.com), NN/g Progressive
Disclosure (nngroup.com), DEVONthink 4 AI review (macstories.net), Smart Connections (github.com/
brianpetro), NotebookLM (notebooklm.google), "Canvas UIs: A Critical Review" (joodaloop.com/canvas-ui).

### 16.2 The throughline
The app has **two real jobs** — *find things* and *ask the AI about them* — plus supporting config and
observability. Every tool that beat fragmentation did two things: (a) collapsed the core jobs into **one
intent-graded entry point**, and (b) turned supporting functions into **ambient signals, contextual
controls, or command-palette verbs** instead of destinations. The unique leverage is **locality**: because
the corpus and model live on the user's machine, we can honestly show the whole knowledge boundary, make
citations openable real files, and run ambient retrieval for free — wins cloud tools structurally cannot
match. Validated spine: **one input → a commit affordance that names the tier → hits that double as the
answer's evidence → an explicit, plan-gated, undoable jump to agent work.**

### 16.3 SIMPLIFY — collapse windows by fusion (the headline)
- **Search ⊕ Ask → one surface, one input, three verbs** (this *is* 577 Goal 3). A single field grades
  intent: `↵ Find` (instant local hits) → `↵ Ask` (cited answer) → `↵ Delegate` (agent run); the submit
  affordance *names the tier* the user is about to commit to. **Hits ARE the answer's evidence**: the cited
  answer renders *above the same hit list*, each citation deep-linking a result already on screen; collapse
  the answer and you are back in raw hits. Deletes the Search-vs-Chat split at the root rather than tabbing it.
- **System → ambient status bar** (calm tech). A thin always-present strip: health dot, index progress,
  model status (loaded/offline/VRAM), worker connectivity; green-and-silent by default, click a segment to
  expand a transient panel (VS Code "click error count → Problems panel"). "Everything is fine" costs zero
  screen space. Deletes a whole window.
- **Settings / Theme / Help → palette + contextual + `?` overlay.** "Windows whose only job is to expose
  controls" become **Cmd-K verbs** (`Toggle theme`, `Re-index folder`, `Open settings: model`), with each
  control *also* living where its effect is (model settings in the Ask header; index settings on the folder
  row). Help → a `?` shortcuts overlay + inline hints + a `Help:` palette namespace. Always keep a palette
  route to every setting as the findability safety net.
- **Library → a dockable pane beside results**, not a destination — folders + file browser as a left pane,
  per-folder actions (re-index / exclude / status) inline.
- **Plausible end-state rail:** *one* Find/Ask surface + a status bar + the command palette + two toggleable
  panes (Library, Logs/Activity). The PRODUCT band goes from twelve to ~one.

### 16.4 EXTEND — deepen primitives already built
- The expensive substrate already exists (577): one ordered-run projection (`projectUnifiedThread`), the one
  tool-call render primitive, the steering seam (`dispatchRunControl`), canonical `SearchTrace` + execution-
  surfaces register, the 569 declaration-default engine, the 561 posture/autonomy store. The fused surface
  should *consume* these, not refork them — most of the cost of unification is paid.
- Make member content **fuse, not tab**: Health's panels distribute into the status bar + an on-demand panel;
  Memory folds into the Ask context; Browse docks beside results — the 578 host/member tabs are the scaffold
  the fusion replaces.
- Extend citations into **live, openable objects**: a citation opens the real file at the cited passage, and
  a one-click "search from here" re-issues retrieval scoped to that document/neighborhood.

### 16.5 NEW FEATURES — the unification × locality payoffs
- **Ambient retrieval rail ("See Also").** While the user reads/browses any file, a side rail surfaces
  semantic neighbors with zero query (DEVONthink See Also / Smart Connections), driven by embeddings we
  already compute — free because on-device. Browsing becomes discovery.
- **"What's indexed" trust view + answer-scope + on-device badge.** Show which folders/files are indexed /
  excluded / stale / pending; let the user scope an answer to a subset and *see* it on each answer ("answered
  from 4 of 1,212 docs"); a persistent "on-device · offline · model: <name>" badge keeps privacy legible.
- **Conversational refinement of search.** Follow-ups reinterpret against history ("now only from last
  year"), so a search becomes a dialogue that narrows the result set — one loop instead of two.
- **Standing queries / promote-a-run-to-a-task.** A one-off query or agent run saved as a recurring task /
  smart rule (DEVONthink smart rules, Khoj automations) — the observe → automate capstone.
- **Calm agent legibility.** Approve-the-plan gate before any file-mutating run, a readable step timeline
  (searched X → read Y → wrote Z), an action audit log with undo + an irreversibility marker, and an autonomy
  dial (suggest-only ↔ act) — much of which 565/561 already scaffolds; the new part is wiring it into the one
  surface.

### 16.6 POLISH — near-term coherence of the current (tab-merged) windows
Low-effort, even though tabs are interim: make the host's own body and member tabs read as one window
(consistent headers, the "Activity AUDIT" cross-altitude marker, the folded "Now" strip sitting natively atop
Health), and keep tab labels routed through the one display authority (`present()` → i18n). Presentation-
coherence touches, not mechanism changes.

### 16.7 Honest pitfalls / constraints (carry into any future work)
- **Never auto-escalate into compute- or file-mutating agent runs.** On a local machine an escalation spends
  the user's CPU/GPU and can change files — tier 3 must always be a deliberate, opt-in verb.
- **Citation precision is load-bearing.** Anchor citations to exact passages and never fabricate one; users
  verify against their own files, and a vague/wrong citation erodes trust faster than none.
- **Ambient ≠ invisible for failures.** Calm-by-default, but a failed index or offline model is center-stage;
  reserve loud escalation for genuine attention-needed states.
- **Surface index staleness.** A drifted index makes the AI confidently answer from old data; freshness/
  coverage must be visible (mirrors the repo's existing "stale index after field changes" pitfall).
- **Keep click + keyboard paths.** Palette-only navigation excludes novices; this is a casual-user file tool,
  so visible affordances (the surface, status segments, `?`) stay clickable — the palette is the accelerator,
  not the sole door. First-run-critical actions (add a folder, start indexing) must not be palette-only.
- **Not a full canvas.** Search is structured, list-driven, keyboard-first; an infinite canvas encourages
  mess, needs manual layout, and degrades on small screens ("Canvas UIs: A Critical Review"). Borrow
  *selective* side-by-side coexistence (result · thread · related), not a freeform board.

### 16.8 Relationship to other tempdocs
577 Goal 3 owns the Search⊕Ask fusion (the headline simplify) and §9.4.D owns whether member-iteration and
shape-iteration unify into one mechanism. 559/565/561/569 own the presentation/run/posture/declaration
substrate the fused surface consumes. 578 remains the **rail-level frame** these land inside; this section is
its forward map from tabs to fusion.

### 16.9 What already exists — codebase audit (2026-06-15)
A read-only investigation mapped every §16 idea to the code. **Headline: ~80% is already built as
substrate; the gap is almost entirely *fusion/composition at the window level*, not missing capability.**
This strongly confirms the 577 thesis ("the expensive prerequisite is mostly paid; what's left is
presentation"). Status legend: ✅ present · 🟡 partial (mechanism exists, fusion/realization incomplete) ·
❌ absent.

**Simplify (collapse windows):**
- 🟡 **Search ⊕ Ask one input, three verbs** — the MECHANISM is shipped: `UnifiedChatView` +
  `unifiedChatState` affordances `retrieve → documents → agent` (one input, intent-graded), the base
  retrieve tier reuses the one `buildSearchIntent` seam (`searchState.ts`), `intentRouter` lowers
  query→search / answer→unified-chat. **Gap:** `core.search-surface` AND `core.unified-chat-surface` are
  STILL two separate rail windows — Search isn't folded into the unified surface. So 577 Goal 3 is built
  *inside one window* but not yet *the only window*.
- 🟡 **System → ambient status bar** — `StatusDeck` + `StatusBarRegistry` + `OverflowController` already
  show CONN · files · size · memory · model · queue ambiently. **Gap:** the System/Health window still
  exists; health detail isn't fully pushed into the bar (click-to-expand from the bar).
- 🟡 **Settings/Theme/Help → palette + contextual + "?"** — `CommandPalette` (Cmd-K) + `CommandRegistry`
  are full; the "?" affordance exists (578). **Gap:** Settings/Theme/Help are still windows; controls not
  yet relocated into context.
- 🟡 **Library → dockable pane** — a Search⊕Library `splitPairing` exists. **Gap:** Library is still a
  destination window, not a pane docked beside results.

**Extend (deepen primitives):**
- ✅ **Shared run/projection/steering/declaration/posture primitives** — `unifiedThreadProjection`,
  `runStepPresentation`, `ToolCallCard`, `dispatchRunControl`, `SearchTrace`/execution-surfaces, the 569
  declaration engine, the 561 autonomy store — all present and single-authority.
- ✅ **Live, openable citations** — `evidenceProjection` + `citationResolve` + `MarkdownBlock` inline `[n]`;
  `citation-select` → open the real local file at the passage (`SourcesPane`/`CitationsPanel` "Open file",
  `RetrieveContextController`); `core.find-related` re-searches from a selection ("search from here").
- 🟡 **Member content fuse, not tab** — not started (tabs are the current scaffold; expected).

**New features (unification × locality):**
- ❌ **Ambient "See Also" retrieval rail** — the one genuinely MISSING idea: no zero-query, embeddings-based
  related-docs surfacing while viewing a file (`core.find-related` is a palette command, not an ambient rail).
- 🟡 **"What's indexed" trust + answer-scope + on-device badge** — coverage view present at FOLDER level
  (`LibrarySurface` / `core.indexed-roots`: file counts, status, last-indexed); answer-scope present
  (`docIds` on RAG-ask); on-device/offline/model badge present (`aiStateStore` → StatusDeck "Online —
  Qwen…"/"Offline"). **Gaps:** no per-file staleness, no explicit "answered from N of M docs" count.
- 🟡 **Conversational refinement of search** — conversation history + branching present
  (`conversationListStore`), but follow-ups don't auto-reuse prior results to narrow the set.
- ✅/🟡 **Standing queries / promote-to-task** — pinned searches with run history (`pinnedSearchState`) ✅,
  user macros (`substrates/macros`) ✅, backend workflows (`CoreWorkflowCatalog`) ✅. **Gap:** no explicit
  "promote this agent run into a recurring task" affordance (the pieces exist to build it).
- ✅ **Calm agent legibility** — all of it shipped: ordered thread projection + tool-call cards;
  approve-the-plan / `AuthorizationHost` + gated effects; action audit log + undo (`ActionLedgerClient` /
  `ActionLedgerController`, two-tier reversibility); autonomy dial (`AutonomyDial` Watch/Assist/Auto);
  live-run steering (`dispatchRunControl`: interject/halt/cancel, CI-guarded).

**Polish:** the current tab-merged windows already route labels through `present()` (i18n) and carry the
cross-altitude "AUDIT" marker; the "Now" strip is folded into Health — i.e., §16.6 is largely done.

**So the remaining work, in priority order, is:** (1) the rail-level FUSION — fold Search into the unified
surface, dissolve System/Settings/Help/Theme into the existing status-bar + palette + contextual controls,
dock Library — i.e., *delete windows by wiring already-built pieces together*; (2) the one net-new feature,
the ambient See-Also rail; (3) small realizations on top of existing mechanisms (per-file staleness,
"N of M docs" scope label, follow-up search narrowing, promote-run-to-task).
