---
title: "799 — Structural health: assertion channels, ratchet gaps, unexercised substrate, corpus topology (theorization)"
type: tempdocs
status: "theorization (2026-07-30) — no design settled, no implementation licensed. Source: a measured code-structure/health pass over `main` at 4d94d034. Every number here is reproducible by the command cited next to it. The Hard-Invariant/ArchUnit item from the same pass is deliberately OUT OF SCOPE (owner set it aside); so is release/distribution work (owned elsewhere). UPDATED same day with §K owner decisions (3 of the 4 §J questions answered) and a §F.1 self-correction that downgrades this document's own headline idea."
created: 2026-07-30
updated: 2026-07-30 (§K owner decisions; §F.1 confidence corrected down and split; §E.1 resolved as early-bet)
category: structural / governance / dx / context-engineering
related:
  - 530-class-size-ratchet-automation.md      # the discipline-gate kernel; §What's-already-shipped ledger
  - 620-always-loaded-agent-doc-audit-and-prose-to-infrastructure.md  # Moves 1-3; the residence rule
  - 618-agent-developer-velocity-friction.md  # §13 always-loaded-budget --init/--bump, still OPEN
  - 742-history-survivorship-audit.md         # "unevaluated assertion channel" as a named class
  - 754-config-surface-triage.md              # 70/70 classified, 31 deleted, 28 shadowed left, no regrowth gate
  - .claude/rules/tier-register.md            # the prose-tier register
  - scripts/ci/check-always-loaded-budget.mjs # the worked example
---

## §0 How to read this

This is **theorization, not design**. It collects directions, reframings, tradeoffs and
hidden assumptions surfaced by one measured health pass. Nothing here is decided; several
ideas are recorded specifically because they may be useful later even if they are not the
answer. Where a claim is uncertain it says so.

The pass covered six items. Sorted by *what kind of thing is failing* they collapse into
**four classes**, and the first class is the keystone — it explains why the other three
persisted rather than being caught.

---

## §A The measured baseline

| Layer | Files | LOC |
|---|---:|---:|
| Java production | 1,516 | 240,819 |
| Java tests | 912 | 174,288 |
| TypeScript production | 514 | 112,798 |
| TypeScript tests | 369 | 64,577 |
| **Product (production only)** | **2,030** | **353,617** |
| `scripts/jseval` | 345 | 107,602 |
| `scripts/ci` | 140 | 25,003 |
| `scripts/agent-analytics` | 104 | 22,129 |
| `scripts/governance` | 162 | 15,430 |
| `scripts/{dev,search,docs}` | 75 | 19,140 |
| **Tooling / harness** | **826** | **189,304** |
| `docs/tempdocs` | 545 | 390,567 |
| `docs/{explanation,reference,how-to,decisions}` | 157 | 33,337 |

Plus 34 registered gates, 57 hooks, 51 `governance/*.json`, 75 SSOT files, 85
`scripts/ci/check-*.mjs`.

Conventional debt markers are **near zero** and should be recorded as such, because the
rest of this document is critical and would otherwise read as a general indictment:
0 `FIXME`, 23 `TODO`, 7 `@Deprecated`, 11 `@Disabled` across 240k LOC of Java; 0
`@ts-ignore`, 16 `@ts-expect-error`, 12 ` as any` across 113k LOC of TypeScript. Java
test:production ratio 0.72. Production module graph acyclic (the apparent
`adapters-lucene ↔ indexing` cycle is test-scope only —
`modules/indexing/build.gradle.kts:67`). Governance kernel 33/34 pass. CI median ~7 min,
green.

**The health problem here is not rot. It is that some controls do not run.**

---

## §B The four classes

1. **Assertion channels with no evaluator** — a control exists, is believed to be
   enforcing, and is invoked by nothing. (742 already named this class; this pass finds
   it is not a one-off.)
2. **Ratchet gaps precisely where churn is highest** — config surface, class size.
3. **Substrate whose consumer count never grew** — the plugin framework, the
   `app-agent-api` vocabulary layer.
4. **Corpus topology** — design history 12× canonical docs; tooling 54% of product.

Classes 2-4 are ordinary engineering tradeoffs and could be argued either way. Class 1 is
not a tradeoff — it is a system believing something false about itself, and it is what
allowed 2-4 to persist unremarked.

---

## §C Class 1 — assertion channels without evaluators (keystone)

### C.1 The worked example: the always-loaded budget

620 Move 3 designed a byte ratchet over the always-loaded agent docs, and its
implementation log records it landing green on 2026-06-20 at an 82,432 B baseline. The
mechanism is real and well-built: `scripts/ci/check-always-loaded-budget.mjs`, with a
one-way `--rebalance` that only ever shrinks the ceiling.

Today (`node scripts/ci/check-always-loaded-budget.mjs`):

```
OVER  CLAUDE.md                          25805 / 22850 B
OVER  .claude/rules/tier-register.md     19286 / 15725 B
OVER  .claude/rules/agent-lessons.md     13010 /  9680 B
OVER  .claude/rules/branch-safety.md     13193 / 10581 B
OVER  .claude/rules/hooks-reference.md    2942 /  2740 B
---- total 80837 B (~20209 tok) / ceiling 68198 B
```

5 of 7 files over; **+18.5%**. And:

- `git log --all -S 'always-loaded-budget' -- .github/workflows/` → **empty**. It has
  never been wired into a workflow in this repo's history.
- `node scripts/governance/run.mjs --gate always-loaded-budget` →
  *"gate id 'always-loaded-budget' not found in governance/registry.v1.json."*

The ratchet worked exactly as designed *during* 620 — the ceiling ratcheted down from
82,432 to 68,198 as content migrated out — and then the files regrew, because after 620
ended nothing ran it again.

The drift is monotone and accelerating, and the repo watched it happen. Five separate
observations, four sessions: **+1.6 KB** over (2026-07-15) → **~9 KB** (2026-07-16) →
**+12.6 KB** (today). One of them states the diagnosis exactly:

> "the check isn't wired into the public CI workflow so nothing catches the drift. The
> ratchet only bites the honest agent who runs it locally"

and another files it under the right class: *"742-class: unevaluated assertion channel."*

The irony is load-bearing rather than decorative. `CLAUDE.md`'s `before-appending-to-rules`
rule opens by citing this ratchet as what caps its bytes, and quotes Anthropic's
"bloated CLAUDE.md files cause Claude to ignore your actual instructions." That rule is
in the file that is 2,955 B over its own ceiling, enforced by nothing.

### C.2 The gate deadlock (the genuinely new finding)

Two gates in the same kernel impose contradictory obligations over the same file:

- **`prose-tier-register`** (registered, passing): every new `<!-- rule:slug -->` anchor
  MUST have a row in `.claude/rules/tier-register.md`, or the build fails with
  `prose-tier-register/new-untagged-rule`.
- **`always-loaded-budget`** (unregistered, red): `tier-register.md` MUST NOT grow, and
  the ceiling **never ratchets up** — by design, per 620 Move 3.

Adding a correctly-registered rule therefore *must* push the budget further red, unless
the author simultaneously migrates unrelated content out of an always-loaded file.
**Neither gate asks for that migration; neither gate can see the other.** The only
conforming move lives outside both gates' scope.

A session hit this in 2026-07-14 and wrote it down verbatim: *"Tension between two of this
repo's own gates... Needs a real trim/reconciliation pass by whoever owns these files'
content."* 618 §13 records the missing escape hatch — an `--init/--bump` path for declared
growth — still marked **OPEN**.

The abstraction: **two ratchets over one substrate with no shared arithmetic.** There is a
conserved quantity here ("rule mass") and each gate measures only one side of it. This
shape is worth naming because it will recur anywhere the repo ratchets a *container* while
another gate mandates *contents*.

Directions (none preferred yet):

- **(a) Couple the budgets.** Ceiling becomes a function of registered-rule count, so a
  registered rule buys its own bytes and back-pressure lands on *unregistered* prose
  instead. Preserves both gates' intent. Cost: the ceiling is no longer a simple
  one-way ratchet, which is the property that made it trustworthy.
- **(b) Declared-growth path (618 §13).** Cheapest. The kernel already has the honest
  form of this — a classified changeset with a `tempdoc:`/`adr:` reference — so growth
  becomes *possible but expensive and attributed*, not silent. Risk: a negotiable ratchet
  is the thing ratchets exist to prevent; needs the changeset requirement to be real.
- **(c) Dissolve it — the register should not be always-loaded at all.** 620 Move 1
  already argued tier-register.md should be a **generated projection**. If it is
  generated, an agent needs the *rules*, not the enforcement-tier table; the table is a
  maintainer artifact. This removes 19,286 B (24% of the always-loaded set) and the
  deadlock together, rather than mediating between them. **Currently the strongest
  candidate**, and it is a move the repo already designed and did not finish.
  **→ ACCEPTED IN PRINCIPLE (owner, 2026-07-30): `tier-register.md` should not be in
  always-loaded context.** Direction settled; the *mechanism* is not — see §K.2 for what
  a design pass still has to answer (projection vs eviction, and where the
  `prose-tier-register` gate reads its register from afterwards).
- **(d) Accept and re-baseline** at 80,837. Honest and free, and gives up the only
  mechanism opposing the bloat CLAUDE.md says makes rules unfollowed. Recorded so the
  option is explicit rather than arrived at by default — which is what is happening now.

### C.3 The class is not a one-off: 15 unreferenced checks

`scripts/ci/` holds 85 `check-*.mjs`. Cross-referencing every basename against
`.github/workflows/`, `build-logic/`, `governance/`, `scripts/governance/`, `CLAUDE.md`,
`.claude/rules/` and `docs/reference/contributing/`:

- **62** are referenced somewhere — most via CLAUDE.md's Pre-merge table or the
  consult-register, i.e. a legitimate *agent-invoked* tier.
- **8** are `.test` self-tests of other checks (fine).
- **15** are referenced **nowhere at all**:
  `check-agent-hooks-wiring-regen`, `check-agent-quality-trend`,
  `check-always-loaded-budget`, `check-api-client-regen`, `check-chip-fact-authority`,
  `check-field-constants-regen`, `check-installer-execution-level`,
  `check-liveness-constants-single-authority`, `check-mcp-conformance`,
  `check-release-baseline-sync`, `check-shape-handler-regen`,
  `check-shape-view-coverage`, `check-tempdoc-status-staleness`, `check-ui-cycles`,
  `check-wire-schema-types-regen`.

Five of those are `*-regen` checks — the generated-fence family, whose entire job is to
catch generated-artifact drift. One is `check-mcp-conformance`, guarding the production
MCP server, which is the repo's public contract. (Two —
`check-installer-execution-level`, `check-release-baseline-sync` — belong to the
release/distribution lane and are out of scope here.)

Sampling four of them produced **three distinct failure modes**, which is the useful part:

| Check | Result | Failure mode |
|---|---|---|
| `check-field-constants-regen` | exit 0, clean | none — genuinely fine |
| `check-ui-cycles` | **6 circular dependency paths**, exit **0** | *advisory by default* — gating needs `--mode gate`, so wiring it naively would still bite nothing |
| `check-agent-hooks-wiring-regen` | exit **1** | *structurally unrunnable in CI* — it validates `.claude/settings.local.json`, which is gitignored (`.gitignore:148`), so the drift is plausibly expected machine-local state and the check can never run meaningfully in CI as written |
| `check-tempdoc-status-staleness` | exit 0, lists stale tempdocs | reporting-only by design |

So "unwired" is really three problems: **not invoked**, **invoked but advisory**, and
**cannot be invoked meaningfully**. A fix that only addresses the first would produce a
false sense of closure — worth stating up front, because "wire the orphans" is the
obvious move and it is insufficient.

Uncertainty to flag: the `check-agent-hooks-wiring-regen` failure is **not** asserted as a
real defect. It may be entirely expected on a machine with local hook customization. What
*is* asserted is that two authorities on hook wiring exist (`hook-integrity`, registered
and passing; `check-agent-hooks-wiring-regen`, unregistered and failing) and nothing
reconciles them.

### C.4 A second source: inherited authority across the public/private boundary

While tracing the missing class-size ratchet, a different mechanism surfaced.

530's §What's-already-shipped ledger states four Layer-2 gates shipped: `class-size`,
`npm-audit`, `ui-bundle`, `prose-tier-register` — and records that the Kotlin
`CheckClassSizeTask` was **retired** because "Node gate is the sole class-size enforcer."
In this repo, `scripts/governance/gates/` contains neither `class-size` nor `ui-bundle`;
neither is in `governance/registry.v1.json`; `git log --all` shows
`scripts/governance/gates/class-size/**` was never added here; and `CheckClassSizeTask` is
absent. `scripts/agent-analytics/expected-state.v1.json:40-45` nonetheless warns agents
that both gates "carry standing RED on main," citing observation conditions
(`obs:class-size-pin-drift`, `obs:ui-bundle-gate-red`, seen 12× each) that do not exist in
this repo's `docs/observations.md`.

This repo's history begins at `29579e51 JustSearch v0.1.0 — initial public release`. It is
a public cut of a larger lineage, and it **inherited authority artifacts describing a
superset system**: a tempdoc ledger asserting gates that did not cross the cut, and an
expected-state claim about their status.

CLAUDE.md already documents exactly one instance of this pattern — the "do not gitignore
model files" pitfall, noted as inherited at v0.1.0 "despite never being true of this
repo." **The instance is recorded; the class is not.** Any artifact that asserts what the
system *contains* (tempdoc shipped-ledgers, expected-state claims, tier-register
`Resolves to` markers, ADR references) is exposed to it.

Credit where due, and it matters for how urgent this is: the `governance-gates-standing-red`
entry is the *only* expected-state entry carrying a `reviewBy` (2026-08-03, four days from
this writing) and an owner. The mechanism for catching this already exists and is armed.
The question is whether one dated review is the general answer or a lucky instance.

### C.5 What the class suggests, abstractly

The repo is **excellent at authoring controls and weak at proving they run**. It already
solved this once, for exactly one tier: the `hook-integrity` gate proves hooks are wired,
load, and bite. There is no analogue for the CI-lint tier — `scripts/ci/check-*.mjs` are
wired by convention, and nothing checks that a given check is reachable from anything.

The obvious generalization is a **reachability gate over the control surface**: every
`check-*.mjs` must be invoked by a workflow, a Gradle task, or a registry gate — or carry
an explicit `advisory`/`agent-invoked` declaration naming its tier. That single control
would have caught the always-loaded drift on day one and would have surfaced the other 14.

Note what makes it attractive: it is not a new *kind* of thing. It is `hook-integrity`'s
shape applied one tier over, which conforms to 582 R4 ("finish wiring rather than author
anew") rather than growing the meta-tier. And note the hazard it inherits: a gate that
checks reachability is itself an assertion channel, and needs to be registered — the
recursion terminates only because `prose-tier-register` and the registry are themselves
run by `verifyGovernanceGates`. Worth stating explicitly in any eventual design so the
termination argument is on the record rather than assumed.

---

## §D Class 2 — ratchet gaps where churn is highest

### D.1 Configuration

Churn, last 90 days, `modules/**`. Four of the top five files are configuration:

| Changes | File | LOC |
|---:|---|---:|
| 18 | `configuration/resolved/ResolvedConfigBuilder.java` | 1,711 |
| 16 | `configuration/resolved/ResolvedConfig.java` | — |
| 16 | `configuration/EnvRegistry.java` | 1,420 |
| 11 | `ui/api/mcp/McpToolSurface.java` | 1,820 |
| 9 | `configuration/ConfigKey.java` | — |

Surface: **240 distinct `justsearch.*` sysprop keys + 298 distinct `JUSTSEARCH_*` env
keys**, 140 raw `System.getProperty`/`getLong`/`getBoolean` sites in production Java.
754 classified 70/70 and deleted 31 — so classification is demonstrably tractable — and
its own status line records what was left: *"the 28 shadowed/duplicate knobs (logged as
bugs, product call), **no regrowth gate**."*

**Reframe worth holding:** the count is not the problem; the **absence of a lifecycle** is.
Knobs are born during incidents and never die. 754 was a one-shot campaign against a
surface with no regrowth pressure, so the surface returns to the same place and the next
campaign pays the same cost. A ratchet is not primarily about the current number — it is
what makes a cleanup *durable*.

Directions:

- **Count ratchet on distinct keys.** Cheapest; mirrors `module-deps` exactly.
- **Birth certificates.** A new key requires a registry entry with an owner and a
  review date. Turns knobs into leased resources rather than permanent grants. The
  precedent is already in-repo and already working: `expected-state.v1.json` entries
  carry `reviewBy` + `owner`, and §C.4 shows that is the one mechanism that caught its
  own staleness.
- **Shadow detection as a gate.** The 28 known-shadowed knobs are a ready-made baseline
  *and* fixture pair for the kernel's self-test convention.

**Sharper reframe, offered as theory:** every knob is a decision deferred to runtime. For
a local-first single-user desktop application, most of ~538 should be resolvable to
constants. Read that way the knob count is not a config metric but a **decision-debt**
metric, and the interesting question is not "how do we cap it" but "which decisions are we
declining to make, and why." That framing suggests triage by *why the knob exists*
(incident escape hatch / A-B lever / genuine user preference / never-flipped) rather than
by module — which is a different cut from 754's and might route more knobs to deletion.

Hidden assumption to check before any of this: configuration reaches the Worker by **three
parallel paths** (full snapshot, blanket `JUSTSEARCH_*` env forwarding, and an explicit
`WorkerSpawner.WORKER_FORWARDED_PROPS` list), and post-handshake divergence checks only
**WARN**. The *effective* surface is therefore larger than any single count, and a count
ratchet over one path could read green while another path grows.

### D.2 Class size

26 Java production files exceed 1,000 LOC (90 exceed 500); 11 TypeScript production files
exceed 1,000. Per §C.4 the gate that enforced this did not cross the public cut, and the
Gradle task it replaced is gone, so class size is currently enforced by nothing here.

**The interesting observation is not the sizes but their location.** The largest
production files cluster on process seams: `KnowledgeServer` 2,149, `GrpcIngestService`
2,040, `McpToolSurface` 1,820, `RagContextOps` 1,802, `RemoteKnowledgeClient` 1,671,
`StatusLifecycleHandler` 1,591. Seam files grow because every cross-process concern must
land somewhere and the seam is the only place both sides are visible.

That has a design consequence worth testing before ratcheting: **a pure LOC ratchet would
redistribute mass without changing the topology** — the classic response is to split a
2,000-line seam file into three 700-line seam files and call it progress. The prior
question is whether a *layer* is missing at the IPC seam (a translation/projection tier)
that these files are currently absorbing. If yes, the ratchet is the wrong first move; if
no, the ratchet is fine and cheap. This is answerable by reading two or three of them with
that specific question in hand.

`UnifiedChatView.ts` at 5,562 lines (plus a 4,116-line test) is a different animal — a
view that accreted, not a seam absorbing a missing layer — and probably wants ordinary
decomposition rather than an architectural answer.

---

## §E Class 3 — substrate whose consumer count never grew

### E.1 The frontend plugin framework

`modules/ui-web/src/shell-v0/plugin-api/`: 40 files, **6,328 production LOC + 3,995 test
LOC**. Consumers: `shell-v0/plugins/token-editor` (**504 LOC**) and `CorePlugin` (the
shell hosting itself). knip reports 84 unused exports in that package alone; repo-wide it
reports 600 unused exports/types across 171 files, of which roughly 140 are barrel
re-exports and generated schema types and should be discounted.

**Frame it as an option, not a mistake.** The framework was purchased as an option on
future extensibility. Options carry a premium (~10.3k lines) and should carry an expiry.
The question is not "was this wrong" — it is *"is the option still worth its premium, and
when does it expire?"*

Three honest exits, deliberately including the one the repo's own rules make awkward:

- **Exercise it.** Name the second plugin and build it. The public-center thesis
  (`docs/explanation/28`) makes "external agents contribute surfaces" a plausible second
  consumer — if that is the intent, the framework is early rather than speculative.
- **Let it expire.** Collapse `plugin-api` into the shell, keep `token-editor` inline.
  This is a `retire-with-a-sweep` operation, and the sweep matters more than the deletion.
- **Hold with a dated trigger.** Note the tension honestly: `structural-defects-no-repeat`
  forbids "wait-for-more-evidence" deferral framings. That rule governs **known defects**.
  An unexercised option is not a defect — it is a bet whose payoff hasn't resolved — so a
  dated review is legitimate here and is *not* the evasion that rule names. Recording the
  distinction explicitly, because blurring it in either direction is the likely error:
  treating this as a defect forces premature deletion; treating defects as options licenses
  exactly the deferral the rule bans.

The `consumer-presence` / `consumer-drift` gates exist to police substrate-without-readers
and currently **pass**. So the repo's formal position is that these types have declared
consumers. The interesting question is therefore not "add a gate" but **whether "declared
consumer" and "real consumer" have diverged** — a gate-*precision* question. That is
cheap to probe and would generalize to every register in the repo.

**RESOLVED (owner, 2026-07-30): exit 1 — the framework is an early bet, deliberately
held.** It is not residue and is not up for retirement; §J.2 is closed.

One consequence worth recording, and it is the only open thread left here: a *deliberately
held bet* and *expiry-by-neglect* are currently indistinguishable from the repo's side —
nothing marks this substrate as intentional, so the next health pass will re-flag it
exactly as this one did. What a bet wants that a defect does not is a **recorded trigger**:
the condition under which it gets exercised or folded. Naming that condition is what
converts "we are holding this" from a memory into an artifact. Not designed here; noted so
the distinction §E.1 drew between defects and options has somewhere to land.

### E.2 The `app-agent-api` vocabulary layer

168 files / 10,978 LOC, of which **124 files sit in `agent/api/registry`** averaging
**65 LOC each**: `Altitude`, `Audience`, `AuditPolicy`, `AutonomyLevel`,
`AvailabilityExpression`, `ConfirmStrategy`, `ConsentCapsuleAuthority`, `DataClass`,
`DeliveryMode`, `EmissionPolicy`, `GateBehavior`, `HistoryPolicy`, `I18nKey`…

That size profile says these are mostly enums and small records — **vocabulary, not
behavior**. Vocabulary is cheap to hold and expensive to *change*: the risk is not the
line count, it is that a 124-term ontology makes every new feature a naming negotiation
against prior art, and that cost is paid on every feature rather than once.

No direction proposed. Flagged because the line-count framing would mis-rank it against
§E.1 — the plugin framework is 10k lines of *mechanism* with one consumer, which is a
different and more tractable problem than 11k lines of *vocabulary* with many.

---

## §F Class 4 — corpus topology

### F.1 Design history is 12× canonical

390,567 lines of tempdocs vs 33,337 canonical. The rules that manage this
(`tempdocs-are-dated-history`, the `tempdoc-age-hint` hook) manage **reading** risk —
they tell an agent a document may be stale. Nothing manages **volume**, and volume's real
cost is not reading, it is **search**: every "has this been tried?" question traverses
390k lines of stale-by-design prose, and getting it wrong is expensive. The observations
store records four brief-premise errors in a single chartering sitting, each refutable by
one command — a symptom of exactly this.

Also observed, and worth noting as the drift this topology produces: tempdoc 775's
frontmatter reads "STEP 1 IMPLEMENTED" while §I/§J of the same file document a settled
default flip and a live-verified delivery governor. Status lines age faster than bodies.

**The idea:** the repo contains a well-measured retrieval engine and an MCP server built
to let agents search a corpus, and it points neither at `docs/tempdocs/`. Verified not
already done — nothing under `scripts/jseval/` builds a corpus over repo docs.

### F.1.a Self-correction (2026-07-30, same day)

This idea was first written up as a single high-confidence item — "a fix, a free
measurement corpus, and the best possible demo." **Three of those claims do not survive
scrutiny and are withdrawn here rather than quietly softened.**

- **"Free corpus" is wrong.** The *documents* are free; the *gold* is not. 767 needed a
  full lane plus a certification runbook plus 32 scientific gates to certify two members.
  Anything claiming eval-corpus status here inherits that cost. The original phrasing
  compressed "no licence, no acquisition cost" into "free" and skipped the expensive half.
- **Leak exposure is severe, and it is the exact class the corpus program just paid twice
  to close.** Tempdoc filenames are `NNN-descriptive-slug.md`. For the natural question
  shape ("was X tried?"), the slug **is** the answer key. That is field_selectivity title
  separability — what 781's v2 rebuild existed to close (§E/§F.5), after 767 §Q.3 before
  it. A tempdoc corpus is maximally exposed to it.
- **The "honest test" argument does not survive scale.** It rested on tempdocs resembling
  the camouflaged-paraphrase shape the engine struggles with. But the collapse findings
  (F-030; the legal-10k floor in 774 §J.5; Q-018's German collapse) are **scale**
  phenomena at ~10⁴ documents. This corpus is **545 documents**. At that size retrieval
  works regardless of shape, so the property claimed to make it scientifically interesting
  is not present.

A better reason, missed in the first pass and not dependent on scale: several tempdocs
exceed 4,000 lines, so this is a genuine **long-document** corpus — which is exactly
783's chartered territory (long-document representation) and 785's (enrichment
throughput). That fit holds at n=545 where the paraphrase argument does not.

### F.1.b The two uses have different costs and should not be bundled

| Use | Needs gold? | Confidence | Note |
|---|---|---|---|
| **Dogfood / product** — index it, search it, answer "was this tried?" | **No** | **high** | Directly attacks the §F.1 volume problem; corpus is already on disk; ingest is a solved path |
| **Certified eval corpus** | Yes, expensive | **low** | Inherits 767-scale certification cost, severe title-leak exposure, wrong scale for the open retrieval questions |

Bundling them was the original error: the cheap high-value use was priced as if it carried
the expensive one's obligations.

### F.1.c A zero-cost validity test for the dogfood use

The observations store records **four orchestrator brief-premise errors in a single
chartering sitting** (787-4b, 783 §B.1, 784, 785), each refutable by one command, each
caught only after a full worker round-trip. Those are known-wrong premises whose correct
answers are already written down elsewhere in the corpus.

That makes a retrospective replay possible with **no gold authoring at all**: index the
corpus, issue the four premises as queries, check whether the refuting document surfaces
in top-k. The questions were not written from the documents, so there is no leak path;
the answers are already known, so there is nothing to label.

- Catches 3-4 → the dogfood use is demonstrated on real, pre-existing failures.
- Catches 0-1 → the idea dies for the price of an afternoon.

This is a better first move than the lane originally proposed, and it is explicitly *not*
an eval lane — it is a one-shot probe whose result decides whether a lane is warranted.

### F.2 Tooling mass, and the asymmetry underneath it

189k of tooling against 354k of product invites "too much." That instinct should be
pushed back on: the eval harness is the only reason search quality is knowable, and the
agent-analytics layer is the only reason the development process is knowable. Both are
load-bearing, and `scripts/jseval` carries 130 test files, so it is not unowned.

The real question is not size but **whether the instrument is held to the standard of the
thing it measures**. Four of the ten newest defect observations are *in* the harness —
`retriever.py:143`, `chunk_completeness.py`, the ASCII-only corpus-leak instruments, and
`campaign_preflight`. The first is the sharpest: eval scores with `ir_measures` over
`hit['score']` (the fused score) while the API returns hits in cross-encoder order, so
**a reorder-only stage is invisible to the metric** — which is exactly what a
cross-encoder is.

That is a categorically worse kind of bug than a product bug, and the difference is worth
naming:

> A product defect produces wrong **behavior**, visible now.
> An instrument defect produces a wrong **belief**, dated back to every measurement the
> instrument touched — silently, and retroactively.

Which yields a candidate principle (§G.2).

---

## §G Candidate principles (named, not built)

**G.1 — A control that is not reachable is a claim, not a control.**
Applies to CI lints, gates, hooks, expected-state entries, and tempdoc shipped-ledgers
alike. The repo already implements this for hooks (`hook-integrity`) and nowhere else.
The generalization is reachability-as-a-first-class-property of the control surface, with
"advisory" and "agent-invoked" as *declared* tiers rather than accidents. Corollary from
§C.3: reachability alone is insufficient — a reachable check that exits 0 on violations is
still a claim.

**G.2 — An instrument needs stronger verification than the system it measures.**
Because instrument failures are silent and retroactive, while product failures are loud
and current. The repo has a name for the neighbouring idea already —
`static-green ≠ live-working` — and this is its measurement-layer sibling. If adopted, the
concrete form is not more tests but an *oracle*: a known-answer replay the harness must
reproduce, which is what `768`'s smoke oracle (18/18 gold_rank vs replay) already is for
one path. The direction is to generalize that shape, not to invent one.

**G.3 — Two ratchets over one substrate need shared arithmetic.**
From §C.2. Where one gate mandates contents and another caps the container, the
conforming action may lie outside both. Either couple them, give one a declared-growth
path, or remove the substrate from the contested tier. Predictable failure without this:
the author does the locally-correct thing, both gates are individually satisfiable, and
the system still degrades.

**G.4 (weaker, offered tentatively) — Inherited authority needs an expiry.**
From §C.4. An artifact asserting what the system *contains*, carried across a repository
boundary, is a claim about a system that no longer exists. The one entry in this repo that
caught its own staleness is the one carrying `reviewBy` + `owner`. Whether that
generalizes to a mechanism or stays a habit is genuinely open — the sample size is one,
and it is recorded here as an observation rather than a recommendation.

---

## §H What this does NOT claim

- **No claim that the codebase is unhealthy.** By conventional measures it is unusually
  clean (§A). The findings are about controls and proportion, not craft.
- **No claim that the tooling should shrink.** §F.2 argues the opposite.
- **No claim that `check-agent-hooks-wiring-regen`'s failure is a real defect** — it
  validates a gitignored file and the drift is plausibly expected (§C.3).
- **No claim that the plugin framework was a mistake** (§E.1). Only that an unexercised
  option should have an explicit expiry, and currently has none.
- **No claim about the Hard-Invariant / ArchUnit item.** Out of scope by owner direction;
  deliberately not restated here so this document does not become its back door.
- **No claim that the 15 unreferenced checks should all be wired.** §C.3 shows three
  distinct failure modes; wiring addresses one.

## §I Falsifiers

Stated so this document can be wrong in a checkable way:

- If the always-loaded set returns to its ceiling and **stays** there for two months
  without a reachability mechanism, §C.5's premise is weaker than claimed and the problem
  was editorial discipline, not missing enforcement.
- If a reachability control ships and surfaces ≤2 real issues across the 15 checks, the
  generalization was over-fitted to the always-loaded instance and should be reduced to
  wiring that one check.
- If the config surface is re-measured in three months and the distinct-key count is flat
  or down **without** a regrowth gate, §D.1's "no regrowth pressure" premise is wrong and
  the ratchet is unnecessary.
- If a second real plugin lands, §E.1's early-bet call is vindicated and the option
  premium was correctly paid. (§E.1 is now owner-resolved as a held bet, so this is a
  confirmation test, not a decision input.)
- **§F.1's replay probe is its own falsifier and is the sharpest one in this document:**
  index the corpus, issue the four known-wrong brief premises, count how many surface
  their refuting document in top-k. 0-1 kills the dogfood use outright. This is the only
  item here that can be settled in an afternoon with no gold authoring and no spend.

## §J Open questions for the owner

1. ~~Is §C.2(c) acceptable in principle?~~ **ANSWERED 2026-07-30 — yes** (§K.2).
2. ~~Is the plugin framework an early bet, or residue?~~ **ANSWERED 2026-07-30 — early
   bet, held** (§K.1, §E.1).
3. ~~Should §F.1's tempdoc-corpus idea be chartered as an eval lane?~~ **REFRAMED, NOT
   ANSWERED.** The question was malformed: it bundled a cheap no-gold product use with an
   expensive certified-corpus use. Split in §F.1.b; the answerable version is "run the
   §F.1.c replay probe?" — an afternoon, not a lane (§K.3).
4. ~~Re-triage the config surface by why each knob exists?~~ **ANSWERED 2026-07-30 — yes**
   (§K.4).

**Still open, and not yet put to the owner:**

5. §C.5's reachability control — is generalizing `hook-integrity`'s shape to the CI-lint
   tier worth building, given §C.3 shows reachability alone would not have caught 2 of the
   4 sampled failure modes?
6. §D.2 — does the IPC seam have a missing layer, or is a class-size ratchet the right
   first move? Answerable by reading 2-3 seam files with that question in hand; not yet
   done.
7. §C.4 / §G.4 — does inherited-authority drift warrant a mechanism, or is the single
   `reviewBy` entry that caught it (due 2026-08-03, four days out) the general answer?

## §K Owner decisions (2026-07-30)

Recorded at the point of decision so later readers do not have to reconstruct them from
conversation. Each names what is settled and, more importantly, **what is still open
underneath it** — a settled direction is not a settled design.

### K.1 — The plugin framework is an early bet, held

Owner: it is an early bet on the extensibility thesis, not residue. §E.1 exit 1.
`plugin-api` stays; no retirement, no sweep, no expiry-by-default.

**Open underneath:** nothing in the repo distinguishes a deliberately-held bet from
neglect, so the next structural pass will re-flag it identically. The distinguishing
artifact would be a recorded **trigger** — the condition under which the bet is exercised
or folded. Not designed here.

### K.2 — `tier-register.md` does not belong in always-loaded context

Owner: accepted. This is the largest single lever available (19,286 B, 24% of the
always-loaded set) and it dissolves the §C.2 gate deadlock rather than mediating it.

**Open underneath — the direction is settled, the mechanism is not.** A design pass owes
answers to at least:

1. **Projection or eviction?** 620 Move 1 said *generated projection*. But if the agent
   needs the rules and not the enforcement-tier table, the table may not need to be
   projected into always-loaded context at all — it may simply move. These are different
   designs with different failure modes.
2. **Where does `prose-tier-register` read its register from afterwards?** The gate
   cross-validates `<!-- rule:slug -->` anchors against register rows. It must keep
   working, and it must not become the thing that drags the file back.
3. **Does this actually clear the deadlock, or relocate it?** §C.2's arithmetic problem is
   two ratchets over one substrate. Removing this substrate from the contested tier
   resolves *this* instance; it does not establish that the next mandate/cap pair will
   not recreate it. §G.3 is the general form and stays open.
4. **What is the residual overage?** Removing 19,286 B against a 12,639 B overage leaves
   headroom — but four other files are individually over their own ceilings, so the
   per-file ratchets stay red even when the total goes green. Whether the per-file
   ceilings are then re-derived is a separate call.

### K.3 — The tempdoc-corpus idea is reframed, not chartered

Owner asked how confident this document was in its own headline idea. On examination:
**not very** — see §F.1.a, where three of its four supporting claims are withdrawn.

What survives is the split in §F.1.b: the **dogfood use needs no gold and is
high-confidence**; the **certified-eval use is low-confidence and expensive**. The
next move is neither a lane nor a charter but the §F.1.c **replay probe** — four
already-known-wrong brief premises, replayed against the indexed corpus, zero gold
authoring, result decides whether anything further is warranted.

Recorded because the correction is the useful artifact here: this document's own
best-sounding idea was the one most in need of interrogation, and the property that made
it sound attractive (an "honest" corpus shape) turned out not to be present at this
corpus's scale.

### K.4 — Config surface: re-triage by knob origin (see §L.5 — input verified, bounded)

Owner: agreed with §D.1's decision-debt reframe. Re-triage by *why each knob exists*
(incident escape hatch / A-B lever / genuine user preference / never-flipped) rather than
by module.

**Open underneath:** 754's per-module classification is not invalidated by this — it is a
different cut of the same surface, and 754 already did the expensive part (70/70
classified, 31 deleted). The question a design pass must answer is whether the origin cut
is applied to the **28 shadowed knobs 754 explicitly left** as a bounded second pass, or
to the whole ~538-key surface as a fresh campaign. Also unresolved: §D.1's warning that
three parallel config-delivery paths make the *effective* surface larger than any single
count, so a triage over one path can read complete while another grows.

---

## §L Derisking pass (2026-07-30, same day)

A `/derisk` pass ran read-only investigation against §K before any implementation. **It
invalidated three claims made earlier in this document.** They are corrected in place
below rather than softened, because the corrections are the pass's main product.

### L.1 — ⚠️ CORRECTION: the §C.2 "gate deadlock" is substantially weaker than claimed

§C.2 called this "the genuinely new finding" and rested it on
`always-loaded-budget` having no upward path.

**A declared-growth path exists and has been used.** `check-always-loaded-budget.mjs:41,
125-161` implements `--bump <file> --reason "<why>"`, raising one ceiling to current size
and appending to `baseline.bumps`. Real bumps are recorded in
`always-loaded-budget.v1.json`: `CLAUDE.md` 24846→25051, `agent-lessons.md` 7285→8677,
`branch-safety.md` 9499→9763.

§C.2 cited tempdoc 618 §13 ("no intentional-raise path — **OPEN**") as evidence. **That
row is stale.** This document reproduced the exact hazard it describes in §F.1 — trusting
a dated tempdoc row as current truth — while complaining about it. Recorded as the
sharpest self-instance available.

**Revised, narrower finding:** the mechanism is complete and well-designed; the files grew
past *even the bumped ceilings*; nothing runs the check, so nobody is routed to the
declared path. This is a **wiring** problem, not a design contradiction.

Consequences: §G.3 ("two ratchets over one substrate need shared arithmetic") loses its
motivating instance and is **demoted to speculative**. §C.2 options (a) and (b) are moot —
(b) already shipped. §C.2 (c) survives, but on its own merits, not as deadlock relief.

### L.2 — ⚠️ CORRECTION: evicting `tier-register.md` does NOT make the budget green

§K.2 and its surrounding prose treated eviction as "the largest single lever" on the
overage. Measured:

| | total | ceiling | verdict |
|---|---:|---:|---|
| today | 80,837 | 68,198 | RED (+12,639) |
| after evicting `tier-register.md` | 61,551 | 52,473 | **STILL RED (+9,078)** |

Removing the file removes its **ceiling too**, so the overage barely moves. All four other
files remain individually over: `CLAUDE.md` +2,955, `agent-lessons.md` +3,330,
`branch-safety.md` +2,612, `hooks-reference.md` +202.

**K.2 is justified on its own merits** — an agent needs the rules, not the
enforcement-tier table — **but it is not a fix for the budget.** The overage lives in the
other four files and must be paid down separately, or declared via `--bump` per L.1.

### L.3 — ✅ RESOLVED: K.2 is eviction, not projection — but the sweep is ~3× larger than assumed

620 Move 1's "projection, not fork" **cannot apply here**: the register's load-bearing
columns (`Tier`, `Resolves to`, `Catches violations via`) are enforcement judgments, not
derivable from a `<!-- rule:slug -->` anchor. Only `Slug` is derivable. §K.2 open-question
1 is answered: **move it, do not generate it.**

The cost estimate was optimistic. A path sweep found **~16 references across 12 files**,
including two that a naive move would break:

- **`hook-integrity/enforcer.mjs:110` hardcodes** `resolve(sourceRoot,
  '.claude/rules/tier-register.md')` with **no config fallback** — unlike
  `prose-tier-register`, whose path *is* config (`registry.v1.json` →
  `baseline.path`, read at `enforcer.mjs:48`). So a second gate reads the register and
  must be edited in code.
- **A generated-file chain:** the `Edit(.claude/rules/tier-register.md)` hook condition
  appears in `governance/agent-hooks.v1.json:514` → `.claude/settings.json:221` →
  `.claude/settings.local.json:356`, policed by `check-agent-hooks-wiring-regen` (itself
  currently failing, §C.3). The move requires a regen step, not a hand-edit.

Remaining touch points: `governance-hint.mjs:52`, `always-loaded-budget.v1.json` (×2),
three canonical docs (`discipline-gate-kernel.md` ×3, `conflict-ledger.md` ×2), the
changesets README (×2), two skill copies, and three cosmetic hook comments.

**Incidental finding while sweeping:** `hook-integrity/enforcer.mjs:109` also hardcodes
`.claude/settings.local.json` — a **gitignored** file (`.gitignore:148`). A gate
validating a file absent from the repo may be partly vacuous in CI, which would explain
how `hook-integrity` passes while `check-agent-hooks-wiring-regen` fails on the same
subject (§C.3). Not investigated further; flagged.

### L.4 — ⚠️ CORRECTION: the §F.1.c replay probe is dead, and its rescue failed

§F.1.c called this "a better first move than anything I proposed originally" and §I called
it "the sharpest falsifier in this document." Checking whether each premise's refutation
is actually in the corpus:

| Premise | Refuting document | Verdict |
|---|---|---|
| 784 — chunk-SPLADE assumed unbuilt | tempdocs **707, 712**, both predating 784 | ✅ valid |
| 785 — enrichment anomaly | only 785 itself, and 790 (later) | ❌ post-hoc |
| 787-4b — deprecated wire shape | only 787 itself | ❌ post-hoc, circular |
| 783 §B.1 — corpora carry answer strings | **no tempdoc has it**; the fact lives in corpus data | ❌ absent |

**1 of 4.** Retrieving a refutation from the document that *records* the mistake proves
nothing about prevention.

The planned rescue — using the search-quality register's `Q-###` → `ANSWERED → F-###`
pairs as gold — **also fails, on both criteria set before looking**:

1. Only **6 of 18** `Q-` entries carry an `ANSWERED → F-` pointer (the pre-registered
   kill threshold was ~8).
2. `Q-###` and `F-###` are sections of **the same file**
   (`docs/reference/search-quality-register.md` — e.g. Q-015 at line 2211, F-030 at
   1537), so the retrieval task is degenerate unless the register is excluded from the
   corpus, at which point the answer key is a pointer chain into tempdocs and n=6.

**Verdict: §F.1's cheap validity test does not exist.** Per the pre-registered kill
criterion, the dogfood use is recorded as **plausible but unproven, with no known
cheap way to prove it** — not as a promoted next move. §I's falsifier list is wrong on
this point and is superseded here.

### L.5 — ✅ RESOLVED: K.4's input exists and is bounded

754 §150 enumerates all 28 shadowed/duplicate knobs **with their shadowing constants at
file:line** (e.g. `SECTION_TARGET_TOKENS = 1800` at `HierarchicalShapeRunner.java:63`;
`Worker.maxBatchSize`/`maxQueueDepth` shadowed by `GrpcIngestService.java:99,102`). Prose,
not machine-readable, but concrete — so K.4's bounded second pass has a real input and a
knowable cost. No machine-readable config registry exists under `SSOT/catalogs/` (only
`aliases`, `analyzers`, `fields`), so a key inventory must be derived.

**Independent support for Class 1 from a prior pass:** 754 §289 separately found
`app-config.schema.json` is "itself unenforced scaffolding" — another assertion channel
with no evaluator, found by someone else, before this document existed. Class 1 survives
L.1 intact; it was only §C.2's *deadlock* framing that was wrong.

### L.6 — Sequencing constraint discovered

Wiring `always-loaded-budget` into CI while it is **red turns `main` red for every other
agent**. Order must be: pay down (or `--bump` with declared reasons) **first**, wire
**second** — or wire in report mode and flip to gate mode after. This constraint did not
appear anywhere in §C or §K and would have been discovered the hard way.

### L.7 — §F.1 probe would need the shared dev stack

`raw_files` corpus mode exists (`ingest.py:237,256`, introduced for `mixed/realdocs-v1`,
tempdoc 686), so a directory of `.md` files can be ingested without authoring a
`corpus.jsonl`. But ingest drives `/api/indexing/roots` (`ingest.py:189`), i.e. it needs a
running backend — the **shared, contended** dev stack, requiring a lease and owner
coordination. "An afternoon" in §F.1.c understated this even before L.4 killed the probe.

---

## §M Implementation log — workstreams A + B + C (2026-07-30)

Branch `worktree-799-structural-health`. K.4 (config surface) is deliberately **not** in
this changeset — different subject, separate PR.

### M.1 Outcome

| | before | after |
|---|---:|---:|
| always-loaded total | 80,837 B (~20,209 tok) | **54,628 B (~13,657 tok)** |
| ceiling | 68,198 B | 55,287 B |
| verdict | **RED, +12,639 B** | **PASS** |
| files over ceiling | 5 of 7 | **0 of 6** |

**≈6,550 fewer tokens loaded into every session**, and the check now runs in CI, so it
cannot silently drift back.

### M.2 What was done

**A1 — `CLAUDE.md` −3,324 B.** `## Common Pitfalls` cut from 11 rows to 4. Three rows
(`lockfiles`, `ssot-catalog`, `api-record`) were **deleted** as verified-redundant — each
is already double-delivered by a shipped hook *and* a skill (checked, not assumed: the
destination skills were grepped for the specific commands first). Three rows migrated to
new `governance/consult-register.v1.json` regions (`model-blobs`,
`index-rebuild-after-field-change`, `dev-stack-stale-jar`). Two environmental rows and the
installer row (release-lane scope) stay.

`## Key Modules` was **deleted**: a hand-maintained list of 10 modules that omitted
`app-services` (62k LOC) and `worker-services` (30k) — the two largest in the repo — i.e. a
stale fork. Its unique content (role descriptions) moved to a corrected **Module roles**
table in `docs/explanation/01-system-overview.md`, the doc CLAUDE.md already cited as
authority; the inventory + dependency graph remain generated in
`docs/reference/architecture/module-deps.md`.

**A2 — `agent-lessons.md` −3,599 B.** The `## Named substrate-discipline principles`
section self-described as an index into `agent-postmortems.md` and then restated all 13
handles with glosses — a fork. Collapsed to a pointer + bare handle list. The
`subset-isnt-the-suite` bullet was **kept** in shortened form because it carries a
registered anchor (tier-register row 42); deleting it would have failed
`prose-tier-register/orphan-register-row`. Three unanchored platform-constraint bullets
were promoted to real postmortem cases: #25 `parked-subagent`, #26
`probe-reports-own-leak`, #27 `chrome-tab-exhaustion`.

**A3 — declared growth, not migration.** `branch-safety.md` (+2,612 B) and
`hooks-reference.md` (+202 B) were `--bump`ed with recorded reasons. Rationale for the
split: worktree/branch safety must be known *before* the first tool call, which is 620's
own residence test — the content is in the right tier and the ceiling was the wrong
constraint. 202 B does not repay migration overhead.

**B — `tier-register.md` evicted** to `docs/reference/contributing/tier-register.md`
(19,286 B out of always-loaded), with the full §L.3 sweep. Both traps materialised as
predicted: `hook-integrity/enforcer.mjs` hardcoded the path (now config-driven via
`gate.config.tierRegister`, so the next move is a registry edit), and the settings chain
needed codegen rather than hand-editing.

**C — the actual fix.** `check-always-loaded-budget.mjs` added to `ci.yml`'s
`public-claims` job. The stale `$comment` claiming "run pre-merge (manual now)" was
corrected in the same commit.

### M.3 Three things the plan did not predict

1. **`skills-sync` silently reverted an edit.** The jseval content was first written into
   `.claude/skills/jseval/SKILL.md` — inside a `<!-- generated:start -->` fence — and the
   next `skills-sync.mjs` run erased it. The source of truth is
   `docs/reference/jseval-pipeline-reference.md`. Caught by re-grepping for the moved
   strings rather than trusting the edit.
2. **Moving the register into `docs/reference/` subjected it to the canonical-doc link
   rule**, which forbids `docs/tempdocs/*.md` references. One "See also" line failed. Fixed
   by pointing at `discipline-gate-kernel.md` (the checker was *not* weakened — prose
   "tempdoc 530" mentions are unaffected, only explicit paths).
3. **Self-test fixtures embed the register path.** Four fixture copies under
   `scripts/governance/_fixtures/prose-tier-register/` had to move too, or
   `--self-test` fails `register-missing`. `--gate ... --mode gate` passed while the
   self-test was broken, so gate-green alone would have shipped it.

### M.4 ⚠️ CORRECTION to §C.4 — the missing gates were removed deliberately

§C.4 attributed the absent `class-size` / `ui-bundle` gates to inherited-authority drift
across the public/private cut. `docs/reference/contributing/discipline-gate-kernel.md`'s
own frontmatter states it plainly: *"The size/count ratchets (class-size, clone,
ui-bundle, exception-count) **were removed for go-public — tempdoc 634**."*

So the gates were **deliberately retired**, not lost in the cut. §C.4's mechanism is wrong
for the gates. What survives is narrower and still real: `expected-state.v1.json:40-45`
still warns agents that both gates "carry standing RED on main," citing observation
conditions absent from this repo — residue of a retirement that did not sweep its
fingerprints. That is a `retire-with-a-sweep` miss, not inherited authority. §G.4 (already
the weakest candidate principle, n=1) loses this as supporting evidence.

**Fourth self-correction in this document.** Recorded rather than quietly amended, because
the error rate under scrutiny is the most useful thing 799 has measured about itself.

### M.5 Verification

`check-always-loaded-budget` PASS · `prose-tier-register` PASS · `hook-integrity` PASS ·
`run.mjs --self-test` PASS · `llmstxt-generate --check` OK · `skills-sync --check` OK ·
`verify-canonical-doc-links` OK (159 files) · `gen-agent-hooks-wiring --check` PASS ·
`check-workflow-triggers` OK · `check-premerge-table` PASS · `module-deps --check-canonical`
OK · diff carries no unintended non-ASCII.

Full kernel: 31/34 pass. The 3 fails are all `kernel/input-missing` in a fresh worktree
with no `node_modules` (`npm-audit`, `dead-code`, `dead-code-jvm`); `npm-audit` was
confirmed **passing on `main`**, so they are environmental, not introduced. No gate that
reads the register fails.

---

## §N Workstream D — config surface (K.4)

Committed separately from A+B+C so the two can publish as separate PRs (different
subjects); both currently sit on `worktree-799-structural-health`.

### N.1 D2 — the `config-surface` regrowth gate — DONE

754's status line names its own gap verbatim: *"no regrowth gate."* That is now closed.

**Built on existing infrastructure rather than a new counter** (`explore-before-implementing`):
`scripts/docs/generate-runtime-config-matrix.mjs` already reads the three configuration
authorities (`EnvRegistry`, `ConfigKey`, `ResolvedConfigBuilder`) and emits
`yamlKeyCount` / `envSyspropPairCount` / `configKeyCount`. The gate consumes that report
via `config.inputs` with the generator declared as its producer — the `module-deps`
pattern exactly.

Pinned baseline (measured 2026-07-30, post-754): `yaml_keys 115`,
`env_sysprop_pairs 269`, `config_keys 56`.

**A kernel gate, deliberately, not a `scripts/ci/check-*.mjs`.** That choice *is* this
document's Class-1 finding applied to its own output: an unwired lint rots (§C.1/§C.3),
a registered gate is enumerable, self-tested, changeset-governed and SARIF-reporting.

⚠️ **Qualification — the first draft of this section overstated it.** "A registered gate
runs" is not true *automatically* in this repo: **no workflow and no Gradle task invokes
`scripts/governance/run.mjs`.** The kernel is agent-invoked pre-merge (CLAUDE.md's
Pre-merge table). `scripts/agent-analytics/expected-state.v1.json:40` matches on a
`verifyGovernanceGates` Gradle task that **does not exist here** — the same go-public
retirement residue as §M.4's class-size/ui-bundle entry, in the same expected-state row.

So the honest claim is narrower: a registered gate is *discoverable, self-tested and
delivered at the moment of relevance* (here, via the `workflow-config-key` recipe),
where the always-loaded lint was referenced by nothing at all and had no bite proof.
That is a real tier difference and it justifies the choice — but it is not CI
enforcement, and this document should not imply it is while criticising exactly that
kind of overclaim. Two consequences worth separating for a later decision:

- The gate cannot break `main` (nothing runs it there), so shipping it carries no CI risk.
- Whether the kernel itself should be CI-invoked is a *bigger* question than K.4 and is
  deliberately not answered here. It is the natural successor to §C.5.

Growth stays possible but must be **declared** — a changeset under
`gates/config-surface/.changesets/` classified `declared-growth` with a tempdoc/adr
justification. Same shape as `--bump` in §L.1: the goal is attribution, not prohibition.

Delivery: the rule reaches an author at the moment of relevance through the existing
`workflow-config-key` consult-register recipe (which already fires on
`EnvRegistry.java` / `ConfigKey.java` / `ResolvedConfigBuilder.java`), so it costs zero
always-loaded bytes — the residence discipline §L.2 was paid for.

**Honest limit, stated in the gate's own header rather than discovered later:**
configuration reaches the Worker by three parallel paths (snapshot, blanket
`JUSTSEARCH_*` env forwarding, the explicit `WorkerSpawner` forwarded-props list) and the
post-handshake divergence check only WARNs. The gate ratchets what is *declared*; it does
not claim to see every effective knob.

Verification: `--gate config-surface --mode gate` PASS; `--self-test` positive PASS /
negative FAIL, both expected; full kernel now **35 gates** (was 34) with no new failure;
`check-premerge-table` resolves the new gate reference (11 gate refs, was 10).

### N.2 D1 — the shadowed knobs — owner-decided 2026-07-30, removal half DONE

**Owner disposition received:** delete the duplicate, wire the user-facing few, withdraw the
rest. Executed as **22 removals + 4 wirings** (not 28 — see N.2.a).

#### N.2.a Two of 754's 28 were already gone

`Health.refreshIntervalMs` and `Health.stalenessAlertSeconds` have no trace in
`EnvRegistry`, `ResolvedConfig` or the docs — 754's own deletion pass took them with the
dead `Translator` tree. 754 §150 is dated history and its count was stale by two.
`tempdocs-are-dated-history`, third instance in this document.

#### N.2.b The measurement that made removal safe

Before touching anything, every candidate was counted by **record-accessor calls**
(`.field()`), not by name — a plain word-grep overcounts badly because names like
`maxBatchSize` and `maxSlots` collide with unrelated locals (17-18 hits each).

**17 of the 22 had ZERO accessor calls anywhere.** The remaining 5 had 9 call sites
between them, and **all 9 were in `ResolvedConfigBuilderTest`** — tests asserting that
resolution works for values nothing reads. That is `unreachable-seed-green` in its purest
form: green tests around a pipe that goes nowhere.

The full build then confirmed it independently — after removal, **every compile error was
in a test file. No production code broke.**

#### N.2.c Disposition

| Group | # | Action |
|---|---:|---|
| `Llm.llmGpuLayers` | 1 | **Deleted** — dead duplicate of `justsearch.gpu.layers` |
| `Rag.retrieveTopK` | 1 | **Deleted** — 754 records it as a second attempt at `ragTopK`; wiring both would recreate a duplicate |
| `Llm` VRAM/session cluster | 11 | **Withdrawn** — governed by deliberate safety thresholds, not user preference |
| `Llm` summarization group | 5 | **Withdrawn** — internal tuning + a hardcoded SSOT prompts root |
| `Worker.maxBatchSize`/`maxQueueDepth` | 2 | **Withdrawn** — gRPC transport internals |
| `Index.commitDebounceMs`, `Watcher.overflowRescanOnOverflow` | 2 | **Withdrawn** — internal timers |
| `Rag.ragTopK`, `Rag.citationMatchThreshold`, `Worker.maxContentLength`, `Worker.maxFileSize` | 4 | **To wire** — genuine user-facing choices |

17 doc rows deleted from `environment-variables.md`. That is the half users actually see:
each was a documented promise that did nothing.

#### N.2.d A test improved rather than deleted

`HeadlessAppGpuAutoPopulateTest` asserted `justsearch.llm.gpu_layers == "99"`, with the
comment *"BOTH gpu.layers and llm.gpu_layers sysprops set, since `rc.ai().gpuLayers` and
`rc.llm().gpuLayers` read different keys"* — i.e. it asserted the duplicate. Rather than
delete the assertion, it was **inverted to `assertNull`**, so the test now guards against
the duplicate being re-introduced. Stronger coverage than before, not weaker.

#### N.2.e The new gate exercised itself

Removing the knobs shrank the surface, and `config-surface` detected it immediately:
`env_sysprop_pairs` 269 → 251, `yaml_keys` 115 → 114, reported as `rebalance-available`,
then ratcheted down via `--rebalance`. The gate was built and validated against a real
shrink within the same session.

**Verification:** `./gradlew.bat build -x test` clean · full `./gradlew.bat test`
**BUILD SUCCESSFUL** · `verify-runtime-config-matrix` OK · `verify-canonical-doc-links` OK
· `config-surface` PASS at the new baseline.

#### N.2.f The 4 wirings — IMPLEMENTED 2026-07-30 (analysis retained below)

All four are wired, tested and green. Two corrections to the analysis that follows, both
found by reading the code before editing it:

1. **The hazard's mechanism was mis-stated.** It is not a "digest". `ExtractionArtifact`
   stamps `policyId` — a *name* — onto every extraction, and `SqliteJobQueue` persists it.
   So the failure mode is that a **persisted identity silently stops identifying the
   policy**: two materially different policies would both call themselves
   `tika-default-v1`, and any staleness or re-extract decision keyed on the id would treat
   them as equivalent. Worse than a mismatch, because nothing would ever error.
   Resolved by `TikaExtractionPolicy.fromWorkerLimits(...)`, which returns `defaults()`
   byte-identical when limits match, and otherwise mints a deterministic
   `tika-config-v1-<chars>-<bytes>` id. `defaults()` is untouched, so the sandbox-child
   path and `validate()`'s fallback stay deterministic. Eight tests guard it; the central
   one fails under the naive "make defaults() read config" implementation, which is the
   point of writing it.
2. **The claimed teardown did not exist.** §N.2.f originally said
   `StreamingCitationMatcher` duplicated `DEFAULT_CITATION_SIMILARITY_THRESHOLD`. It does
   not — it already delegates to the `DocumentService` constant; tempdoc 565 §15.A unified
   them. No duplicate to remove. **Fifth self-correction in this document**, and the one
   that most vindicates reading before editing.

A constraint that shaped the citation wiring and was not in the plan: 565 §15.A made that
cutoff *one shared value* precisely because a divergent local `0.45` was a defect. Wiring
only the RAG path would have recreated it, so **both** composition roots
(`ConversationApiAssembly`, `AgentLoopWiring`) read the same key.

Config is read at composition roots, never inside the SPI classes, so `RAGContext`,
`StreamingCitationMatcher` and `AgentCitationResolver` stay constructor-injected and
unit-testable. `ragTopK` precedence is **body → configured → 5**: an explicit per-request
`topK` still wins.

**Verification:** full `./gradlew.bat test` BUILD SUCCESSFUL · `config-surface` PASS at an
**unchanged** key count (251 — the plan's own mis-scope check) · `verify-runtime-config-matrix`
OK · always-loaded budget PASS · kernel `--self-test` PASS.

#### N.2.f.1 The live tier ran — and caught what everything else missed

`use-every-verification-tier` says check whether a tool provides the tier before declaring it
unavailable. The stack was free (no contention), so it was run: dist built from this worktree,
`distFrom` start, `ready_worker` reached. Two results.

**1. Boot smoke passed.** The change adds a `ConfigStore` read at two composition roots, which
no unit test covers. The stack reaches `ready_worker` with it, so the read does not break
startup.

**2. It found residue I had left — my own `retire-with-a-sweep` failure.** `effective_config`
on the running backend still listed `worker.limits.max_queue_depth`. The consumer was gone, but
the **`EnvRegistry` declaration and the YAML contribution were not**, so the key still resolved
into the live resolution map: declared, documented as removed, read by nothing. Precisely the
defect class this whole tempdoc is about, reintroduced by an incomplete sweep of my own.

A full audit then found **four** such knobs (`worker.limits.max_batch_size`,
`worker.limits.max_queue_depth`, `index.commit.debounce_ms`,
`index.watcher.overflow.rescan_on_overflow`) — two with orphaned `EnvRegistry` entries, all four
with orphaned `putYaml*` contributions, plus a `contributeYamlWatcher` method left empty. All
removed; the surface shrank again (`yaml_keys` 114→110, `env_sysprop_pairs` 251→249) and the
gate ratcheted down.

**Nothing else would have caught this.** The full unit suite was green, `build -x test` was
green, and `config-surface` was green — because a key that resolves but is never read is exactly
what none of them can see (§O.3). Only looking at the running system's own config surface
exposed it. That is the concrete argument for the live tier, and a live instance of §O.6's
warning that the new gate counts keys without knowing whether anyone reads them.

**Not done:** observing a changed retrieved-chunk count under a non-default
`justsearch.rag.top_k`. The MCP `dev_start` surface has no sysprop-injection parameter, so that
specific observation was not reachable without driving the backend outside the dev-runner.
Precedence is covered by unit tests instead; the gap is stated rather than papered over.

##### Original analysis (retained — it is why the design is shaped this way)

The withdrawal half is done and green. The wiring half is deliberately left for a scoped
follow-up, because the investigation turned up a hazard that makes "just read it from
config" wrong.

**`Worker.maxContentLength` / `maxFileSize`.** The seam looked clean: `TikaExtractionPolicy`
is a record whose `DEFAULT_MAX_EXTRACTED_CHARS` (10 MB) and `DEFAULT_MAX_INPUT_BYTES`
(100 MB) match the config defaults *exactly*, `StructuredContentExtractor` already takes
`maxContentLength` by constructor, and `TikaExtractionPolicy.defaults()` is the single
convergence point for 8+ call sites. The obvious move is to make `defaults()` consult
`ConfigStore.globalOrNull()`, mirroring the established
`DefaultWorkerAppServices.resolvedOcrConfig()` idiom.

**That would be a bug.** `ExtractionArtifact` (`:67`, `:126`) uses `defaults()` to build and
verify a **policy digest**. If `defaults()` becomes configuration-dependent, the digest
varies with configuration, and artifact verification starts comparing against a moving
reference. `ExtractionSandboxChild` compounds it: extraction runs in a **child process**
where the global `ConfigStore` may not be initialised at all, so the same policy would
resolve differently across the process boundary.

The correct shape is therefore a *separate* resolved-policy factory
(`TikaExtractionPolicy.fromWorkerLimits(ResolvedConfig.Worker)`, mirroring
`OcrRoutingConfig.from(...)`) wired only at the production construction seam, leaving
`defaults()` deterministic for digests and sandbox children. That is a real design, with a
real determinism invariant to protect — not a one-line read.

**`Rag.ragTopK`.** Seam is clear: `RAGContext.extractTopK` (`:343-350`) reads only the
request body and falls back to `DEFAULT_TOP_K = 5`. Wiring means threading the resolved
config to that static, which needs a plumbing decision (constructor injection vs. the
`ConfigStore` idiom) — and request-body `topK` must keep winning over config.

**`Rag.citationMatchThreshold`** is the messiest: it is typed `String`, resolves to `""`,
and is never parsed, while the live value is a `double`
(`DocumentService.DEFAULT_CITATION_SIMILARITY_THRESHOLD = 0.5`) read by
`StreamingCitationMatcher` and `AgentCitationResolver`. Wiring it is a type change plus
two consumer changes, not a hookup.

**Why this is the right stopping point:** the withdrawal half removes 22 false promises and
is verifiable by compile + full suite. The wiring half adds 4 *new behaviours* to
extraction and RAG, one of which has a determinism invariant that a naive implementation
silently breaks. Bolting it on at the end of a long session is precisely the
`static-green ≠ live-working` failure this document has been cataloguing. Scoped as a
follow-up with the hazard named above so the next pass starts informed rather than
discovering it.

#### N.2.g Original blocking analysis (retained)

**This is an owner decision, not an implementation gap.** 754 §150 is explicit:

> "Per owner scope, they are logged, not fixed, and **not deleted** — deleting would erase
> the record of intent without delivering the feature."

Each of the 28 resolves one of two ways — *wire the knob to its consumer* (deliver the
feature) or *delete it* (withdraw the promise) — and both are product calls the owner
already parked once. Making 28 of them unilaterally would re-open a settled deferral and
would be exactly the "summarize-and-suggest-closure" move `tempdoc-is-your-contract`
warns against, in reverse.

What is ready for that decision: 754 §150 already enumerates every knob with its shadowing
constant at `file:line` (e.g. `Worker.maxBatchSize`/`maxQueueDepth` ←
`GrpcIngestService.java:99,102`; the 11-knob `Llm` VRAM/session cluster ←
`HardwareProfile.java:24` + `VramRequirements.java:32` + `OnlineModeOps.java:73` +
`LlamaServerOps.java:230-232`; `Rag.ragTopK` ← `RAGContext.java:55`). The two genuinely
distinct sub-cases worth separating when the call is made:

1. **`Llm.llmGpuLayers` is a *duplicate*, not merely shadowed** — `justsearch.llm.gpu_layers`
   is dead while `justsearch.gpu.layers` works, and **both are documented**
   (`environment-variables.md:103` and `:81`). One of the two doc rows is simply false.
   This one is closer to a documentation defect than a product call.
2. **The rest are undelivered features.** Wiring them is real work with real behavioural
   risk (e.g. making `Worker.maxBatchSize` live changes ingest characteristics).

Note the new gate does **not** pressure these: it ratchets the *count*, and these knobs
already exist. Deleting any of them would only make the gate greener.

---

## §O Root causes (git archaeology, 2026-07-30)

Everything above this section describes *what* is broken. This section is the *why*, traced
through history rather than reasoned from the symptoms. It is the most reusable output of
this tempdoc, because the three findings turn out to be one mechanism wearing three
costumes.

**Method note and its limit:** this repo's history begins at the v0.1.0 public squash
(`29579e51`, 2026-06-25) — 425 commits. Anything born before that date is unknowable here,
which is why Cause 3 is argued from code shape rather than from commits.

### O.1 Cause 1 — a deferral with no trigger

The always-loaded budget ratchet shipped with its own excuse written into its config file,
present in the **first public commit** and unchanged for **35 days** until this branch
edited it:

> *"Wiring: run pre-merge (manual now); fold into the prose-tier-register gate or
> verifyGovernanceGates **once the doc set stabilizes**."*

The deferral itself was defensible. The *condition* was not: "once the doc set stabilizes"
has no definition of done, no owner, and no date — and it can never fire, because the
always-loaded set is living documentation that changes most weeks. A permanent deferral
written in the grammar of a temporary one.

What the history then shows (reconstructed by replaying actual vs. ceiling at every commit
that touched either):

| date | state | note |
|---|---|---|
| 2026-06-25 | **RED** 78,606 / 76,480 | over its limit on day one, at the public cut |
| 2026-07-01 | ok | ceiling *raised* twice (76,480 → 81,661 → 83,187) |
| 2026-07-03 | RED 86,094 | drifted past the raised ceiling |
| 2026-07-07 | ok 66,493 | a genuine cleanup — "681: instruction-layer re-baseline" |
| 2026-07-11 | **RED** | four days later |
| …19 days… | RED | climbing to 79,201 with the ceiling frozen at 68,004 |

So this was never slow neglect. It is a **cycle**: clean, drift, clean. Nothing made the
drift cost anything at the moment it happened, so it always resumed. Note also that raising
the ceiling and cleaning up are indistinguishable from outside — both turn the check green.

### O.2 Cause 2 — a retirement that swept the wiring but not the tools

Commit `88d14e8c` (2026-06-25, one day after the cut) built the public CI lane and cut
`ci.yml` from **771 lines to 59** — a 712-line deletion. Among the deleted lines:

```
- run: node scripts/ci/check-ui-cycles.mjs … --mode gate
- run: node scripts/ci/check-chip-fact-authority.mjs
```

The check *scripts* stayed in the tree. Only their invocations left.

**11 of the 13 non-release orphaned checks date from that single day.** They were not
accumulated over a month of neglect; they were created in one restructuring, by one commit,
as a side effect of a decision that was itself correct — a public repo *should* have a
smaller CI surface than a private one.

This also explains a detail §C.3 observed but could not account for: `check-ui-cycles`
reports 6 real circular dependencies and exits 0. Its `--mode gate` flag was in the deleted
line. The same commit family retired the `class-size` / `ui-bundle` gates (§M.4), leaving
`expected-state.v1.json` still warning agents about them a month later.

The missing step was never "don't trim CI". It was the follow-up question: **what did we
just stop enforcing, and do we still claim to enforce it?**

### O.3 Cause 3 — nothing links a config key to a reader

Pre-cut history hides these knobs' birth, so the argument is from code shape — and the
shape is unambiguous.

The hardcoded constants match the config defaults **exactly**: `MAX_BATCH_SIZE = 10_000`
against `resolveInt(…, 10_000)`; `MAX_QUEUE_DEPTH = 100_000` against
`resolveLong(…, 100_000L)`; 10 MB and 100 MB likewise. That is not coincidence — both
authors knew the same number. Yet **neither constant references the knob**, and
`GrpcIngestService`'s javadoc describes its constant as *"Maximum files allowed in a single
batch request"*, i.e. as the authority. `HierarchicalShapeRunner`'s says its value
*"matches legacy `TokenEstimationUtils.SECTION_TARGET_TOKENS`"* — carried from another
constant, not from config.

Two people writing the same number in two places at different times, and **nothing in the
system able to notice**. Verified: `consumer-presence` covers registry declarations, not
config keys, and no unused-config-key detector exists anywhere in `scripts/`.

### O.4 The mechanism underneath all three

> **This project can create an authority faster than it can create the thing that enforces
> it — and nothing notices when a claim loses its enforcer.**

Each individual decision was reasonable. Deferring wiring until things settle sounds
prudent. Trimming CI for a public repo is correct. Adding a config knob for a value is
routine. None of them is a mistake in isolation; the failure is that a document row, a doc
sentence, or a config key costs nothing to write and nothing to *keep* after the mechanism
behind it disappears. There is no decay pressure on claims.

That is why §B's Class 1 is a **class** and not three incidents, and it is the honest
justification for the `config-surface` gate being a registered kernel gate rather than
another `scripts/ci/check-*.mjs`.

**The public cut concentrated the damage.** Two of the three causes trace to the same week.
A large one-time restructuring is precisely when authority-to-enforcement links snap, and
no post-cut audit asked what had quietly become untrue.

### O.5 Coda — the same failure, applied to me

This document corrected itself four times (§L.1, §L.2, §L.4, §M.4). Every one had the same
shape: **I trusted a document that had stopped being true.** A tempdoc row marked `OPEN`
for a mechanism that had shipped; a list of 28 items of which 2 were already gone; a theory
about missing gates that had been deliberately retired.

That is Cause 1 and Cause 3 applied to documentation instead of code, and it is why §F.1's
"design history is 12× canonical" is not a tidiness complaint. The corpus is the same
hazard surface as the config surface — claims with no decay pressure — and it caught the
agent that was writing *about* the hazard.

### O.6 What this implies for the fixes already shipped

Stated plainly so no one over-reads what this branch accomplished:

- Wiring the budget check fixes **one instance** of Cause 1. The pattern is untouched.
- The `config-surface` gate caps **how many** config keys exist. It cannot tell whether one
  is read, so **Cause 3 remains open** — the same 22 could regrow, merely fewer of them.
- **Nothing here addresses Cause 2.** The other 13 orphaned checks are still orphaned.

The generalised fix for Causes 1 and 2 is the same object: a reachability control over the
whole control surface (§C.5), which is deliberately not built here.

---

## §P Cause 3 closed — the dead-config detector (2026-07-31)

§O.6 said the `config-surface` gate "cannot tell whether a key is read, so Cause 3 remains
open". §N.2.f.1 then proved it at my own expense: four dead keys survived this branch's own
cleanup with the full unit suite, `build -x test`, and the count ratchet all green. That is
the argument for building the reader check, and it is now built.

### P.1 What it checks

`scripts/governance/gates/config-surface/dead-config.mjs`, folded into the existing
`config-surface` gate rather than authored as a new one (582 R4 — finish wiring rather than
grow the gate count). Two rules:

- **`config-surface/dead-key`** — a setting is declared but no reader exists.
- **`config-surface/unread-component`** — a `ResolvedConfig` record component whose accessor
  is never called.

### P.2 Three read paths, and why that matters more than the rule itself

A setting counts as read if **any** of three paths reaches it: resolved into a record
component someone calls; read directly via `EnvRegistry.CONST.getX()`; or its raw key string
appearing anywhere outside the configuration module.

This was not obvious and it was not free. The first draft knew only path 1 and reported **33
dead keys**. Adding path 2 cut it to 10; adding path 3 removed a further false positive
(`justsearch.lite.mode`, read as a raw string elsewhere). A gate shipping with ~30 false
positives is not a strict gate — it is a gate that gets switched off, which is §O.1's failure
mode arriving by a different road. The iteration was the work; the rule was the easy part.

### P.3 Measured state, and what is baselined

**1 unread component + 10 dead keys**, pinned in
`gates/config-surface/dead-config-baseline.txt`. The gate fails on any NEW one.

The component is `simulatedLatencyMs` — an `Llm` field this very branch *kept* while removing
22 others. The detector found it within minutes of existing, which is the most direct evidence
available that manual sweeps do not substitute for it.

They are baselined rather than deleted because each is another wire-it-or-withdraw-it product
call — the same judgement 754 deferred and §N.2 put to the owner. The gate's job is to stop
the list growing while those calls are made, not to make them silently.

### P.4 Bite verified, not assumed

§C.3 found that "reachable but advisory" is its own failure mode, so reporting is not enough.
Verified by injecting a plausible unread knob into `EnvRegistry`:

- with the knob: gate **exits 1**, naming the key and both remedies (wire it or delete it);
- after revert: **exits 0**, and the injection is byte-clean gone.

`scripts/ci/test-config-surface-dead-config.mjs` makes the deciding logic permanently covered
(6 assertions, including that a *baselined* entry stays `info` while a new one *fails*), and
is wired into `ci.yml` so it is not itself an unrun check — which would be Cause 2, committed
by the person who wrote Cause 2 up.

### P.5 What is still open

Cause 1 (one instance fixed, pattern untouched) and Cause 2 (13 orphaned checks) remain. This
closes Cause 3 for the *declared* config surface only — §D.1's warning stands that
configuration reaches the Worker by three parallel paths, and a key travelling an undeclared
one is still invisible.
