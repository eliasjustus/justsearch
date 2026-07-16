---
title: "742 — History-survivorship audit: abandoned-intention residue, dead tiers, and two inert gates"
type: tempdocs
status: "implementing 2026-07-16 — owner approved dispositions (delete e2e tier; JUNK+RESIDUE+VESTIGIAL batches; delete 2026-01-17 README batch; keep scripts/ui+ops cluster; two PRs). Branch A (worktree 742-residue-removal) DONE: all approved deletions + IndexDocument hoist + AnalyzerRegistry collapse + ADR-0011 banner + OTLP labels + 09-testing-strategy rewrite. D4 SUPERSEDED by a bigger finding (see §Branch-A implementation notes): the ENTIRE lingui catalog (815/815 msgids) is dead — shell-v0 never adopted lingui macros; catalogs left untouched, 'remove Lingui entirely?' added to dispositions. Branch B (kernel gate-input contract + knip revival) next."
created: 2026-07-16
author: agent session 70bf04ea (Fable 5, orchestrating 3 Sonnet subagents)
category: substrate hygiene / dead code / gate integrity
related:
  - 367 (legacy-code audit — its "future audit pass" deferral is closed by this tempdoc)
  - 727 (friction mining — the telemetry joined here)
  - docs/observations.d/1b3050fb (2026-07-15 phase-1 archaeology: GJF inert, synonyms gate blind spot — this tempdoc found the second inert gate and confirms the class)
  - ADR-0032 (React retirement), ADR-0043 (locale-invariant analysis), ADR-0011 (Remote Shard SPI — flagged unimplemented here)
---

# 742 — History-survivorship audit

## Why this exists

Owner hypothesis (2026-07-16 session): code from the project's earlier eras is
"indirectly slowing and interfering with development", and the existing dead-code
infrastructure misses it because static reachability can't see *abandoned intent*.
Approach agreed with owner: (a) survivorship analysis over `F:\JustSearch`'s full
history (not the phase-1 archive — zero merge-base, mined out 2026-07-06/15),
(b) intent recovery from the earliest surviving design documents (Nov 2025,
recovered off-repo) + ADRs, (c) join with agent-friction telemetry, (d) deliver ratchets,
not observations.

## Method (repeatable)

1. **Survivorship**: one-pass `git log --name-only` over `F:\JustSearch` main
   (6,563 commits, 2025-11-01→2026-06-25) → per-file first/last-touch/count;
   folded with `justsearch-public` post-release history (files touched since
   `29579e51` excluded); filtered to code substrates, last-touch ≤ 2026-02-28.
   **Mechanical mass commits must be excluded from last-touch** — the 2026-06-23
   SPDX-header commit (`11c306af`, 1,936 files) alone masked 90 frozen files
   (77 of them `src/main`). v1: 345 files; v2 (mech-excluded): 435.
   Caveat: rename-tracking off → renamed-after-Feb files don't appear (conservative).
2. **Intent recovery**: all 10 rescued Nov-2025 design docs + earliest surviving
   tempdocs + full ADR set + Nov–Feb commit narrative → abandoned-intentions
   register with grep fingerprints.
3. **Friction join**: per-file `file_path` extraction over all surviving session
   transcripts (125 post-cutover + 84 pre-cutover JSONL).
4. Classification by 3 parallel Sonnet subagents; load-bearing claims
   spot-reverified in the main loop (marked ✔ below).

## Headline results

### R1 — The friction hypothesis is NOT supported; the hazard hypothesis is

Across every recorded session transcript, the 345 v1 survivors were directly
Read/Edited **5 times total**, and ambient mentions of the residue clusters are
≈nil once false matches are removed (post-cutover "Playwright" hits are the live
`ui-shot` tooling; "synonyms" hits are CLAUDE.md rule text). **Old residue is not
measurably costing agent attention.** The justification for cleanup is
*correctness hazard and false authority* (inert gates, docs asserting dead
enforcement, patterns available for copying), not velocity. Future cleanup work
should not be sold as "this will speed up agents".

### R2 — Two inert gates (the class from the 2026-07-15 GJF finding, confirmed)

1. **TS dead-code gate (Knip ratchet) has never run.** ✔ Nothing in
   `.github/workflows/` or `scripts/` generates `tmp/knip-report.json`
   (repo-wide grep: the only `knip` hits outside `modules/ui-web` are the
   enforcer itself); `scripts/governance/gates/dead-code/enforcer.mjs:52-56`
   downgrades a missing report to a `warning` and returns verdict `pass`;
   `gates/dead-code/baseline.txt` is empty; the enforcer's own header comment
   ("already wired in CI: `npm run knip`", `enforcer.mjs:4-5`) is false.
   Consequence: 9 fully-orphaned `ui-web/src/utils/*.ts` files (below) are
   invisible. Same shape as the GJF finding: *green misread as enforcement;
   required input permanently absent.*
2. **Knip's config is itself residue-contaminated.** ✔ `modules/ui-web/knip.config.ts:22-30`
   ignores six `src/components/views/*.tsx` React-era files that no longer exist
   anywhere in the tree, and lists the abandoned `e2e/**/*.ts` tier as *entry
   points* (so even a running Knip would treat dead specs as live roots).

### R3 — Whole abandoned tiers still in-tree (classifier, spot-verified)

| Cluster | Size | Evidence |
|---|---|---|
| Playwright e2e tier | 52 files, 10,223 lines (`modules/ui-web/e2e/`) | ✔ zero `playwright`/`e2e` matches in `.github/workflows/`; `package.json` e2e scripts invoked by nothing; runnable by hand only |
| React scaffolding in `modules/shell` | `App.jsx`, `main.jsx`, `App.css`, `react.svg`, `vite.config.js`, `index.html`, template README | `tauri.conf.json:8-11` builds exclusively against `../../ui-web/dist`; shell's own Vite app (port 1420) never launches (devUrl is 5173) |
| JNI script family | `scripts/ci/{build-jni-shim,run-reindex-jni,run-smoke-jni,run-ui-jni}.ps1` | pre-gRPC "Head→Lucene via JNI" era; zero `jni` matches in build-logic/gradle/workflows |
| VMware offline-installer harness | `scripts/vmware/*` (5 RESIDUE + 2 self-declared DEPRECATED) | superseded by `scripts/sandbox/sandbox-launch.py` (`docs/explanation/13-ai-setup-and-verification.md:164`); zero external referencers |
| Orphaned trace-interceptor prototype | `modules/telemetry/src/test/.../grpc/Trace{Client,Server}Interceptor.java` | rebuilt properly twice: `ipc-common` (client) + `worker-core` (server); prototype pair referenced only by its own IT |

Also JUNK: committed Lucene runtime artifacts
`modules/app-services/${user.home…/index/default/{segments_1,write.lock}` (two
brace-variant dirs; `.gitignore:22` covers the equivalent only for
`app-launcher`), four `modules/ui/src/_tmp*.py` scratch scripts (line-dump
debugging of the JavaFX-era `MainView.java`), and
`gradle/verification-metadata.dryrun.xml` (Gradle dry-run byproduct, zero refs).

### R4 — Frozen-production-source audit (90-file cohort unmasked by mech-exclusion)

~76 of 90 are LIVE-EARNED (stable DTOs/ports that earn their place — e.g.
`SearchPort` has a real Noop→Remote swap; GPL/LambdaMART is a fully wired
training pipeline, *not* abandoned; VDU and intent-classification are alive).
The keepers matter: two suspects an eager cleanup would have wrongly deleted.

Vestigial/dead flags:
- **`AnalyzerRegistry`/`AnalyzerDescriptor`** (`modules/core/.../analyzers/`) —
  single implementation (`SsotAnalyzerRegistry` ✔ only `implements` hit);
  production consumers (`ComponentsFactory.java:70`, `IndexSchema.java`) type
  the concrete class, bypassing the interface. Collapse or document the seam.
- **`IndexApi.java`** (`modules/indexing/api/`) — `index()` has zero
  implementations ever; only the nested `IndexDocument` DTO is used
  (`IndexingDocumentOps.java:278` etc.). Hoist the DTO, delete the interface.
  (`modules/indexing` as a module is live — 7 production dependents — despite
  not appearing in CLAUDE.md's curated module table.)
- **`SummaryRejection` + `SummaryRejectedException`** (`app-api`) — ✔ referenced
  only by each other + their test; no throw site, no FE consumer. Wire or delete.
- **9 orphaned `modules/ui-web/src/utils/*.ts`** — `lang, text, textHelpers,
  fileNames, gpuDetection, healthDiagnostic, platform, policyPaths, intlFormat`
  — zero importers repo-wide (✔ spot-checked lang/policyPaths/intlFormat).
  Docstring forensics: `fileNames.ts` names consumers that no longer exist;
  `gpuDetection.ts` names pre-ADR-0032 React hooks; `intlFormat.ts` lost the
  tempdoc-594 §17.2 DRY consolidation to `shell-v0/display/format.ts`.
  `ChunkFormat.validateSequence()`/`ChunkEnvelope` is a dead sub-surface of an
  otherwise-live class.

### R5 — Stale/false authority (docs)

- **`docs/explanation/09-testing-strategy.md:113`** ✔ claims the Playwright
  selector guard is "Enforced by `scripts/ci/check-playwright-hardcoded-testids.mjs
  --mode gate`" — that script is invoked by nothing, and documents 15 dead specs
  as current. Canonical-doc drift on a dead tier.
- **Lingui catalog accumulates deleted-tree references** ✔ —
  `modules/ui-web/src/locales/de/messages.po` carries 769 `#: src/components/…`
  comments pointing at deleted React files while being regenerated as recently
  as 2026-06-25: `extract` runs without `--clean`, so this *regrows*.
- **ADR-0011 (Remote Shard SPI) is accepted-but-unimplemented** ✔ — zero
  `RemoteShard` hits in code; nothing marks it aspirational. A future agent can
  read it as describing an existing SPI. Same dormant family: OTLP collector
  config (`config/application.yaml:133`, `profiles/smoke.yaml:121` ✔ →
  `http://collector:4317`, disabled since inception, no collector exists).
- **2026-01-17 module-README batch**: 2 of 3 spot-checked have phantom facts
  (`telemetry/README.md:39-44` lists 4 nonexistent modules as dependents;
  `app-inference/README.md:26-29` lists nonexistent `ai-bridge`). Remaining ~15
  unchecked — treat batch as untrusted.
- **`modules/app-services/src/main/resources/ssot_snapshot.json`** ✔ orphan —
  no reader in app-services; the live copy is read by
  `app-observability/CapabilitiesService.java:276`. Outside the
  `ssot-catalog-sync` gate's scope (it checks only the 3 `SSOT/catalogs/*.json`
  mirrors), so it can drift silently.
- **Meta**: tempdoc 367 (2026-03-28) already flagged the `scripts/ui` +
  `scripts/ops` cluster UNWIRED and deferred deletion to "a future audit pass"
  that never ran. This tempdoc is that pass; do not re-defer without a decision.

### R6 — Abandoned-intentions register (for future sweeps)

Cleanly deleted, residue-free (verified fingerprint sweeps): FFM/Panama J-LLM
engine (ADR-0005), `ai-worker` 4th process (ADR-0017), pipeline DAG engine +
definitions (ADR-0014), bare-metal indexing engine (never built), knowledge-graph
UI (ADR-0007, deliberately rejected). The deletion discipline for *named,
ADR-reversed* intentions has been good; what leaks is (a) scaffolding nobody
named in an ADR (shell React files, e2e tier, JNI scripts) and (b) enforcement
plumbing that decays silently (gates, knip config, lingui refs, READMEs).

## Design (settled 2026-07-16; supersedes the "alternative shapes" in Theorization)

Investigation of existing machinery reframed the problem. The kernel runner
(`scripts/governance/run.mjs`, tempdoc 530) already has `--self-test` (per-gate
positive/negative fixture pairs — the "bite probe" exists); `hook-integrity`
already does wiring/load/bite for hooks; and the fail-open input pattern turns
out to be a **family, propagated by precedent**: `dead-code`, `dead-code-jvm`,
`npm-audit`, `module-deps` (its comment literally says "matches the npm-audit
gate's report-missing UX"), and `test-efficacy` all consume `tmp/` report
artifacts and individually chose warn-on-missing. The public CI lane
intentionally does not run the kernel (ADR-0044 — local-first); enforcement is
the local pre-merge path. So "wire knip into CI" was the wrong frame — the fix
belongs in the kernel itself.

**D1 — Inputs are part of the gate contract, enforced by the runner, not the
enforcers.** The registry (`governance/registry.v1.json`) declares each gate's
required input artifact(s) alongside the existing `config.reportPath`, plus the
one-command producer for each (e.g. the knip invocation). In `--mode gate`, the
*runner* fails a gate whose declared input is absent, printing the producer
command as the remedy; `--mode warn` keeps advisory behavior. Root cause
addressed: today each enforcer chooses its own missing-input behavior, and the
copied choice was fail-open. One authority, one policy.
**Orphaned by D1 (deleted/retired in this same work, not a later sweep):** the
per-enforcer `*/report-missing` warning paths in all five report-consuming
gates (unreachable once the runner owns the check), the false "already wired in
CI" header comment in `gates/dead-code/enforcer.mjs:4-5`, and the empty
`gates/dead-code/baseline.txt` (replaced by an honest baseline, see D2).
Presence-only for now; input *staleness* (report predates the change under
review) is a recognized extension with a trigger — build it on the first
incident where a stale report masks a regression, not before.
At implementation time, inventory which of the five inputs are already produced
somewhere (the JVM side has a CI-run `:modules:dead-code-audit:test`; knip's is
produced nowhere) — D1's failure message is only as good as its producer
commands.

**D2 — Knip revival uses the existing ratchet as designed, no new machinery.**
Fix `knip.config.ts` (drop the six ghost `.tsx` ignores — knip's own
configuration hints flag these once it runs; decide `e2e/**`-as-entry jointly
with the e2e disposition), run it, baseline the honest count, ratchet down.
If the first honest run is a flood, the baseline absorbs it — weakening the
gate to get to green is the named anti-pattern.

**D3 — `--self-test` joins the routine pre-merge path** so fixture rot is
caught where it bites. It exists and is invocable today; the only design
content is that it must be *scheduled*, not merely available. (How — pre-merge
table row vs. bundled into full-kernel runs — is implementation detail.)

**D4 — Lingui extraction becomes always-clean.** Add `--clean` to the standard
extract invocation (verified current in the pinned major, see research notes);
obsolete translations remain recoverable from git history. One regeneration
then removes the accumulated stale references. No checker needed — regrowth
becomes impossible by construction, which beats detection.

**D5 — ADR-liveness stays manual at n=1.** ADR-0011 gets an explicit
"accepted, not yet implemented" banner; the dormant OTLP config gets labeled or
removed with it. No fingerprint lint now — build it if a second
accepted-but-unimplemented ADR ever surfaces. (Deliberate scope restraint:
one instance is a banner, not a subsystem.)

**D6 — Mechanical junk + `.gitignore` generalization** (the `${user.home` rule
beyond `app-launcher`; delete the four `_tmp*.py`, both committed index dirs,
`verification-metadata.dryrun.xml`). No design content; listed for completeness.

**Disposition decisions remain owner calls** (unchanged from the worklist):
e2e tier (delete / revive / archive out-of-tree — and learn *why* it stopped
running before choosing), shell React scaffolding, JNI + VMware scripts, 9 dead
TS utils, `SummaryRejection`, `IndexApi` interface collapse, `AnalyzerRegistry`
seam, trace-interceptor prototype pair, `Rrd4jSpikeTest`, app-services
`ssot_snapshot.json`, tempdoc-367's deferred scripts list, 2026-01-17 README
batch, `09-testing-strategy.md:113` correction.

### Reach: the principle this instantiates

**"Vacuous green is a failure state: an enforcement mechanism whose
precondition is absent must fail loudly, not degrade to pass."** This is not
new to the repo — it's the same principle behind `hook-integrity`'s bite
probes, tempdoc 716's jseval cross-checkout fail-closed conversion ("pre-716 it
silently ran the wrong copy"), and tempdoc 729's verdict on the formatter ("the
defect was the silent fallback, not the missing formatter"). D1 conforms to
that existing principle rather than inventing one; the design deliberately
extends the kernel runner instead of adding a parallel `gate-integrity` gate.

Where else it plausibly applies (recorded, not built): doc claims of the form
"Enforced by X --mode gate" are grep-able and resolvable against the registry —
one confirmed violation (`09-testing-strategy.md:113`) is below the automation
threshold; a second would justify a docs-lint. Knip's config-hint warnings are
the same shape upstream (hints don't fail CI; issue #1026).

**Evidence this earns its keep:** a pre-merge kernel run goes red on a missing
input that would previously have passed vacuously, at least once on a real
change — or the inventory of report-consuming gates with unproduced inputs
(≥1 known today: knip) reaches and stays at zero.
**Retirement condition:** if input production moves inside the runner itself
(inputs become present by construction), the presence check is dead scaffolding
— fold it out. Likewise if it accumulates per-gate exemptions ("this input is
legitimately optional") faster than it catches absences, the contract is wrong
— redesign rather than exempt.

## Theorization (pre-design; nothing below is settled)

### Reframing: this is trust decay, not dead code

The audit set out to find dead *code* and mostly found dead *authority*: gates
that don't run, a config ignoring ghost files, a canonical doc asserting
enforcement that doesn't exist, an accepted ADR with no implementation, READMEs
describing a module graph that's gone, docstrings naming deleted consumers.
Dead code is inert; dead authority is *load-bearing in the reader's model* while
being false. The recurring system shape across every R2–R5 finding is:

> **an assertion channel whose truth condition silently stopped being
> evaluated** — gate←report, config←file-list, doc←wiring, ADR←implementation,
> baseline←scan, docstring←consumer.

That suggests the durable invariant candidate is not "no dead code" (already
ratcheted, imperfectly) but something like **"every assertion channel is either
evaluated or labeled"**: a gate must fail closed on missing input; an ADR that
isn't implemented must say so; a doc claim of enforcement must resolve to
wiring. Full generality is unachievable (docstrings, prose) — the design
question is which channels are cheap to close mechanically (gates: yes; ADR
fingerprints: probably; doc "Enforced by X" claims: maybe, they're
grep-able; READMEs: no — consider demoting/deleting instead).

### The prospective inversion: make deletions carry their own sweep

Archaeology was expensive because past deletions didn't sweep their
self-descriptions. The FFM engine, ai-worker, and pipeline-DAG deletions (all
ADR-named) left ~zero residue; the un-named retirements (React shell scaffold,
e2e tier, JNI) leaked. The difference wasn't discipline, it was *naming*: an
ADR-documented reversal came with a fingerprint list de facto. A lightweight
"retirement sweep" convention — when abandoning a feature/tier, grep its
fingerprints across code+config+docs+gates and delete or label each hit —
would make this tempdoc's method a one-time cost rather than a recurring one.
(Same move as the intent-register, applied at deletion time instead of a year
later.)

### Hidden assumptions worth keeping visible

- **"Referenced" ≠ "earning its place."** dead-code-jvm proves reference, not
  reason-to-exist. The vestigial finds (single-impl interface bypassed by its
  own consumers; interface whose only live part is a nested DTO) suggest a
  *flag-not-fail* heuristic lint (e.g., interfaces with exactly one impl whose
  consumers name the concrete type) — but per AHA, some seams are deliberate
  (`IndexOpenGuard` is single-impl and correct), so this can never be a hard
  gate. Judgment stays in the loop.
- **The friction negative has limits.** Transcript `file_path` counts miss
  Grep-result tokens, build minutes, and the rare-but-expensive event of an
  agent copying a dead pattern as precedent. R1 justifies changing the *pitch*
  (hazard, not drag), not concluding residue is free.
- **Deletion is not the only disposition.** For the e2e tier specifically, three
  options exist: revive (wire `test:gate` into CI — the specs may encode test
  intent the unit suite lacks), delete, or **archive out-of-tree** (the
  established `justsearch-archive` pattern preserves retrievability without
  in-tree false authority). Revival has a real cost the other two don't:
  Playwright-vs-live-stack flakiness was presumably why the tier decayed —
  worth one look at *why* it stopped running before choosing.
- **Survivorship analytics have a general trap**: mechanical mass commits
  (reformat, license headers) reset last-touch and silently mask the signal.
  Any future re-run must exclude them; a cheap forward-looking aid is a commit
  trailer convention (e.g. `Mechanical: true`) so exclusion stops requiring
  archaeology of its own.

### Alternative shapes for the gate-liveness ratchet

1. **Minimal (fail-closed inputs)**: flip `report-missing` to error + wire the
   report generation. Fixes both known instances' shape; one afternoon.
2. **Bite-probe (hook-integrity analogue)**: every gate must fail its negative
   fixture in CI. The `_fixtures/<gate>/{positive,negative}` layout already
   exists for several gates — the missing piece is asserting the *input
   pipeline* produces fresh artifacts, not just that the enforcer logic works.
3. **Freshness assertion**: governance run records each gate's input-artifact
   provenance (produced-by step + age) and fails on stale/absent inputs.
   Strongest, most machinery; risks becoming the next unevaluated channel.
   Leaning 1 now + 2 where fixtures already exist; 3 only if a third instance
   of the class appears.

### External research notes (2026-07-16; docs current as of writing)

Checked because both tools moved past prior knowledge (`knip ^6.20.0`,
`@lingui/cli ^6.4.0`); facts only, no external code adapted:

- **Knip has no native baseline/ratchet** — its documented CI adoption path is
  "triage, tune config, then fail CI" ([handling-issues](https://knip.dev/guides/handling-issues)).
  The custom `dead-code` ratchet enforcer therefore remains justified; no
  redesign needed, only the input wiring fix.
- **Knip's JSON reporter shape** (`{issues:[{file, exports:[{name,line,…}]}]}`)
  matches what `enforcer.mjs` already parses tolerantly
  ([reporters](https://knip.dev/features/reporters)) — the enforcer will work
  as-is once a report exists.
- **Knip emits Configuration Hints for stale `ignore` entries** (e.g. the six
  ghost `.tsx` patterns) but only as warnings, not exit-code failures — an open
  upstream request ([#1026](https://github.com/webpro-nl/knip/issues/1026))
  asks for `--treat-config-hints-as-errors`. So the ghost-ignore cleanup is
  semi-mechanical (run knip, read hints) but its non-regrowth is not upstream-
  enforceable yet; the ratchet baseline is the local backstop. Knip's own
  guidance is to avoid broad `ignore` patterns entirely
  ([configuration-hints](https://knip.dev/reference/configuration-hints)).
- **Lingui v6 `extract --clean` is current and does exactly what R5 needs**:
  "remove obsolete messages that are no longer present in the source code"
  ([CLI ref](https://lingui.dev/ref/cli)) — the 769 stale `#:` refs hang off
  obsolete messages that the default merge-preserving extract keeps forever.
  If origin comments prove noisy even when fresh, PO `formatOptions`
  (`origins`/`lineNumbers`) can drop them entirely — separate knob, separate
  decision.

### Risks for the disposition phase

- Mass deletions conflict with 3–4 parallel worktrees; batch into small
  single-cluster PRs and sequence them in quiet windows.
- Some UNWIRED scripts may be human-invoked incident tooling (367's
  "human-facing test" was never run) — owner call per file, not bulk delete.
- Reviving Knip honestly may surface far more than the 9 known orphans;
  baseline first, then ratchet down, or the gate arrives red and gets weakened
  (the exact anti-pattern `fix-root-causes-not-symptoms` names).
- A liveness meta-check that itself goes stale would be ironic and invisible —
  prefer the dumbest mechanism that fails loudly (missing input = red build)
  over a new subsystem.

## Derisk results (2026-07-16; all evidence live-probed, no implementation)

- **U1 (runner insertion / self-test interaction) — resolved, favorable.**
  `run.mjs` self-test is a separate early-exit path (`runSelfTest`, exits before
  the evaluation loop), and fixtures ship their own inputs inside `fixtureRoot`
  (e.g. `_fixtures/dead-code/{positive,negative}/tmp/knip-report.json`), with
  enforcers resolving `reportPath` against `fixtureRoot` in fixtureMode. A
  runner-level input check inserted before `runGate` (evaluation loop,
  ~`run.mjs:322`) cannot break self-test. Synthetic-fail findings need only a
  rule description merged into `allRuleDescriptions`.
- **U2 (input production cost) — resolved, design AMENDED.** Producers:
  knip = `npm run knip` (seconds, never wired); npm-audit =
  `report-npm-audit.mjs` (runs the tool itself, seconds); module-deps =
  `module-deps.mjs` (seconds, already CI-run with `--check-canonical`);
  dead-code-jvm = `:modules:dead-code-audit:test` (moderate gradle test,
  CI-run); **test-efficacy = pitest mutation runs (expensive, minutes per
  seam)**. Blanket fail-closed is therefore wrong: **D1 gains per-gate input
  classes** — `required` (cheap producers; absent ⇒ fail) vs `on-demand`
  (expensive; absent ⇒ fail only when the gate is explicitly selected or its
  subject is affected, else explicit `skipped-no-input` verdict, never silent
  pass). The vacuous-green fix is the *labeled skip*: today's warn-and-pass
  becomes an honest non-verdict.
- **U3 (knip live run) — resolved; flood confirmed and sized.** `npx knip
  --reporter json` runs clean (exit 1 = findings): **181 files with issues,
  261 unused exports, 340 unused types, 4 unused deps.** 8 of the 9 dead
  utils are flagged; `intlFormat.ts` escapes because its own `.test.ts` counts
  as a consumer in default mode (production-mode analysis is a possible later
  tightening, not now). Honest baseline will be large; the ratchet's
  per-file-count format absorbs it.
- **U4 (self-test today) — green.** `run.mjs --self-test` exits 0; five gates
  skip with honest "no fixtures directory" labels. D3 scheduling is safe.
- **U5 (lingui wiring) — surprise.** There is **no extract script** in
  `modules/ui-web/package.json` (no `lingui extract` invocation anywhere in
  repo scripts/CI). The catalog was regenerated somehow on 2026-06-25 —
  presumably ad-hoc `npx lingui extract`. D4 therefore = *create* the canonical
  extract script (with `--clean`) rather than amend one; first implementation
  step is establishing how extraction actually happens today.
- **U6 (worktree collisions) — zero.** No in-flight worktree branch touches
  `scripts/governance/**`, `gates/**`, the registry, `knip.config.ts`,
  ui-web `package.json`, or `locales/` (three-dot diff vs main, all worktrees).
- **U7 (registry schema) — resolved, with a meta-finding.**
  `governance/registry.v1.schema.json` sets `additionalProperties: false` on
  gate entries, but **nothing validates the registry against it** (no
  referencing script anywhere) — the schema is itself an unevaluated assertion
  channel, one more instance of this tempdoc's principle. Implementation
  choice: nest input declarations under the free-form `config` (no schema
  edit) or edit the schema — and either way, note the unvalidated schema in
  the disposition list.

Residual unknowns (accepted, implementation-time): how the 2026-06-25 catalog
regeneration was actually performed; final per-gate class assignment for the
five inputs; whether gate-behavior changes require kernel changesets under the
existing classification machinery.

## Branch-A implementation notes (2026-07-16)

Executed in worktree `742-residue-removal` (base `origin/main` ce4d6de8) by three
sonnet workers + orchestrator. Deviations from the audit's premises, found and
handled during implementation:

- **Lingui is 100% dead, not 76% stale (supersedes D4).** A clean extraction
  wiped both catalogs 815→0: `src/` contains zero `t()`/`msg()`/`Trans` macro
  usage — the Lit `shell-v0` rewrite deliberately bypassed lingui
  (`src/i18n.ts:86` comment). The 769-stale-refs finding undercounted; every
  msgid is residue. Catalogs restored untouched; the planned `i18n:extract
  --clean` script was added then REMOVED (running it would wipe the catalogs).
  New disposition item: **remove the lingui layer entirely** (deps, vite
  plugin, `lingui.config.ts`, catalogs, `src/i18n.ts` bootstrap) — owner call.
- **`public/vite.svg` is live** (favicon, `modules/ui-web/index.html:5`) —
  audit misclassified it as residue; kept.
- **README batch enumerated authoritatively** from the full-history repo
  (public history is squashed): exactly 16 files created 2026-01-17; all 16
  deleted; the 7 non-batch module READMEs kept. `modules/ui-web/README.md`
  still claims React/Zustand (Hard Invariant #5 violation) — logged, out of
  batch scope.
- **ADR-0011 frontmatter constraint**: `status:` values other than
  `stable|in-progress|advisory` silently drop the ADR from `docs/llms.txt`
  (`llmstxt-generate.mjs:149`) — banner-only, frontmatter left `stable`.
- **`${user.home` junk**: the "two variants" are one unclosed literal dir
  (brace placement differs on the `.justsearch` segment); 4 tracked files
  deleted; `.gitignore` rule generalized to `modules/*/`.
- **Pre-existing reds found, logged, not fixed** (observations shard):
  `check-liveness-constants-regen` fails on main (SPDX mass-commit stamped a
  generated file the generator doesn't stamp — inert-assertion class again);
  `docs-validate.mjs` YAMLException on tempdoc 530 frontmatter.

Verification (branch A): `spotlessApply` + full `build -x test` green; six
affected module test suites green (A2); ui-web typecheck + 3,779 unit tests
green (A1); frontend-stack docs lint OK (files=103); language-agnostic gate OK;
ui-web regen/gate set green except the pre-existing liveness-constants red
above; full `./gradlew test` + governance warn-run recorded at commit time.

## What NOT to do (verified keepers)

`SSOT/prompts/en/**` (sole namespace, live readers — not an ADR-0043 violation),
VDU (49 production files), GPL/LambdaMART, intent-classification grammar chain,
`modules/indexing` as a module, telemetry module (runtime dep of both processes),
shell's Rust/Tauri half, revapi/errorprone/spotbugs baselines, system-tests
corpus fixtures.
