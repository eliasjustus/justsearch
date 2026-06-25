---
title: "Go-public (Option C) — launch content, presentation & first-run robustness: the M2 threat-model doc, the published benchmark methodology writeup (from release.v1.json), the 'how we build' machinery showcase, the remaining OSS files (CONTRIBUTING React→Lit, FUNDING.yml, SUPPORT.md, CHANGELOG), README/NON-GOALS finalization, and external model-hosting robustness. Workstream 3 of 4 — lowest live-repo risk, agent-heavy, safe to start now."
type: tempdoc
status: "implemented + committed to `main` (2026-06-24, agent-side complete) — go-public Option C, workstream 3/4. Shipped + live-validated: the threat-model doc + the DNS-rebinding Host-hardening, the benchmark-methodology projection (`gen-public-benchmark`), the privacy + download-source gates, the OSS files (SUPPORT/CHANGELOG/FUNDING + the CONTRIBUTING front door), README/NON-GOALS finalized-and-staged. Design spine (kept): the launch content is the *publication* instance of canonical-authority-and-projection — public claims (benchmark table, download-source, CSP/privacy) are governed projections of declared facts, not hand-copied forks; authored prose (NON-GOALS values, showcase narrative) stays prose; reuse `register-headline-sync` + the doc-claim lint, one new closure gate (download-source). The flip-gated remainder (README root-promotion, the `bartowski` GGUF mirror, the two CI guards' activation) is registered in tempdoc 634."
principle: "canonical-authority-and-projection, publication instance — a public claim is a projection of a declared fact, never a hand-copied fork; the more public/adversarial the surface, the stronger the case for a fork-prevention gate (the 'irreversibility premium'). Conforms to 623 (benchmark)/632 (license)/635 (eval input)/622 (telemetry); generalization stays unbuilt per 625. New facet recorded, not built: blast radius scales with audience."
created: 2026-06-22
updated: 2026-06-24
related:
  - 631-go-public-publish-machinery
  - 632-go-public-licensing-legal
  - 634-go-public-cutover-transition
---

# Go-public (Option C): launch content, presentation & first-run robustness

## Context
The public-facing **conversion surface** for the developer/MCP + research spearhead, plus the
"**a stranger can clone → build → run**" robustness. The README and NON-GOALS drafts exist (in the private
positioning sidecar) and were **live-validated** (the hero CTA's retrieval + cited-answer path works end-to-end).
This workstream is mostly **pure docs — no live-repo risk — so it can start immediately.**

## Scope
1. **Threat-model doc (M2)** `[agent]` — STRIDE-style over the local-first / loopback architecture; formalize the
   *provable-privacy* story (CSP loopback-lock; no telemetry exporter; the one-time model download). Unblocks the
   README's `<<threat-model>>` link and is an NLnet-M2 deliverable.
2. **Published benchmark methodology writeup** `[agent]` — from `scripts/jseval/release.v1.json` (the canonical
   2026-06-21 release): honest config disclosure (default `hybrid` vs `full`), the external-baseline comparisons
   (the reproducible SciFact win over ColBERTv2/SPLADE++), reproduction commands. The research-channel asset +
   the README's benchmark link. *(Numbers already reconciled in the README; this is the standalone methodology.)*
3. **The "How we build" showcase** `[agent]` — make the published machinery **legible**: the agentic-dev workflow
   + the discipline-gate kernel as a deliberate system, so it reads as a *showcase* rather than dumped internal
   files. This is the Option-C upside; a small investment makes the transparency a credibility asset.
4. **OSS files** `[agent][live, small]` — `CONTRIBUTING.md` "React"→"Lit" (ADR-0032); **new** `FUNDING.yml`,
   `SUPPORT.md` (response norms — the solo-dev support flood), `CHANGELOG` (maturity vs. the fresh-history seam).
   `SECURITY.md` / `CODE_OF_CONDUCT` / issue+PR templates / `CODEOWNERS` already present.
5. **Finalize README + NON-GOALS** `[agent+founder]` — promote from the positioning drafts; the remaining
   placeholders are founder assets (demo GIF, badge URLs, the threat-model + benchmark links from #1–2).
6. **First-run robustness** `[agent+founder]` — verify **every** `model-registry.v2.json` `downloadUrl` resolves
   from a *public* source; **mirror the third-party `bartowski` chat-model GGUF** (a single point of failure for
   first-run) onto the releases repo.
7. **Repo presentation** `[founder]` — GitHub description, topics, the social-preview image.

## Done
The README's links all resolve; a stranger can **clone → build → first-run from public sources**; the machinery
reads as a deliberate showcase (#3).

## Dependencies
Parallel with 631 + 632. **Feeds 634.** Items #1–3 are zero-live-risk and are the recommended starting point.

## NEW FINDING (2026-06-23): the clean contributor front door (the contributor-path half of 631's clone-experience finding)

**New, founder-raised finding** (its machinery-side twin is 631's "present-but-opt-in" section). Under Option C a
fresh cloner meets the founder's whole apparatus; the fix is *not* to hide the machinery but to make sure a
contributor isn't **force-fed** it — they should hit a **clean, conventional repo**. 631 owns the *machinery side*
(opt-in wiring + signpost READMEs); **633 owns the contributor-path side**, captured here:

- **The CONTRIBUTING front door must be conventional** (sharpens #4 + aspect #26). It says: *clone → `./gradlew build`
  → run tests → here's a good-first-issue.* **Nothing about Claude Code, the hooks, or the dev-stack is required to
  contribute.** The agentic apparatus is *how we develop*, offered as transparency — **not** the contributor's
  required path. This is the single most important lever for "the repo isn't annoying to a new dev."
- **The "How we build" showcase (#3) must frame the machinery as *optional/transparency*** — "here's our (opinionated)
  agentic + governance process; adopt it if you like, ignore it to just send a PR." Read this way, the machinery is a
  *credibility asset*, not an imposition. (Pairs with 631 G9's signpost READMEs.)
- **Contributor DX (#26) for the heavy build** — a bootstrap one-liner / dev-container so a contributor reaches a
  green build without reverse-engineering the founder's setup. The build is genuinely heavy (Java 25, 30+ modules,
  GPU-optional); the front door has to make first-build painless.

**Boundary:** the `CLAUDE.md`-stays-contributor-grade + the opt-in hook wiring live in **631 (G8 / #2)**; this side is
purely the *documented contributor path* + the showcase *framing*. Net of both halves: machinery **present + visible
+ opt-in**, behind a **clean front door** — transparency without imposition.

---

## Investigation / pre-implementation findings (2026-06-23, takeover pass — verified against `main`)

Read-only audit + a few experiments against the live tree. Each finding cites primary-source `file:line`. The
headline: **every source the workstream draws from exists and the load-bearing claims are real** — `release.v1.json`
is the calibration run the README cites; the CSP loopback-lock and "no product telemetry" claims are grounded in
source; the bartowski SPOF is exactly as described. The corrections below are about **accuracy/sequencing risk in the
output docs**, not about the plan being wrong. Two findings change scope (★).

### State of the deliverables (what exists vs. what #1–#7 must produce)
- **README** — the root `README.md` is the **OLD dev README** (`README.md:23` "React/Vite web UI" — violates the
  Lit invariant in spirit; `README.md:48` references the dead `scripts/gate.ps1`). The **launch README** is the draft
  at `docs/business/positioning/readme-draft.md` (correctly says "Lit/web-components", line 126). So #5 is *promote
  the draft*, not edit the root file — and that draft's `<<threat-model>>` (line 138) + benchmark link (line 105)
  placeholders are exactly #1 + #2's outputs, confirming the intra-633 ordering: **do #1, #2 (and #3) before #5.**
- **NON-GOALS.md, SUPPORT.md, CHANGELOG, FUNDING.yml — absent at root** (verified). NON-GOALS draft exists
  (`docs/business/go-to-market/non-goals-draft.md`, paste-able). SUPPORT/CHANGELOG/FUNDING are greenfield (#4).
- **CONTRIBUTING.md** — on `main` still says "React frontend" (`CONTRIBUTING.md:117`), references the **dead
  `scripts/gate.ps1`** (`:31`,`:67`) and a **non-existent `modules/ai-bridge`** (`:118`; the real module is
  `gpu-bridge`/`ai-backend` per CLAUDE.md). 632's worktree did a React→Lit pass but **it is not merged to `main`**
  (its commits live on `worktree-632-licensing`; `git log` on `main` has no CONTRIBUTING edit). So #4's CONTRIBUTING
  work is still open on `main` — and must be reconciled with 632's branch to avoid a double-edit.
- **Threat-model doc — absent** (`docs/reference/security/` does not exist; no `*threat*` doc anywhere). Greenfield (#1).
- **Showcase (#3)** — *raw material already exists*: `governance/README.md`, `scripts/agent-analytics/README.md`,
  `docs/reference/contributing/discipline-gate-kernel.md`. So #3 is **assembly + framing**, not authoring from
  scratch — and it coordinates with **631 G9's signpost READMEs** (don't double-author the same signposts).
- **Present (no work):** `SECURITY.md`, `CODE_OF_CONDUCT.md`, `NOTICE`, `.github/{CODEOWNERS,PULL_REQUEST_TEMPLATE.md,
  ISSUE_TEMPLATE/{bug_report,feature_request}.md,dependabot.yml}`. Matches the tempdoc's "already present" note.

### F1 — ★ Item #2's headline benchmark framing has a methodology hole the *same workstream's* research-channel plan warns against
The README/benchmark numbers are **real and reconcile to the calibration run** — `scripts/jseval/release.v1.json`
(`composed_at` 2026-06-21, `git_sha` 691d5c5a0, `default_mode:"hybrid"`, RTX 4070 hardware block) — this **is** the
fresh single-config/single-commit run the research plan's Decision-2 gate demanded as the precondition for *any*
published number (`docs/business/research-channel/plan.md:87-123`). SciFact `measured.nDCG@10 = 0.7514`
(`release.v1.json:173`) = README's 0.751; CourtListener hybrid `0.6204` (`:195`) / `full` ablation `0.9706` (`:21`)
= README's "≈0.62 / 0.97". Good. **But two comparison-class problems must be foregrounded in the methodology doc, or
the adversarial research audience (the plan's decision 5: "punishes polish", a launch was destroyed over a hidden
`marketing.md`) will dismantle it:**
  1. **Not apples-to-apples retrievers.** The SciFact "win" (0.751 > ColBERTv2 0.693 > SPLADE++ 0.71) compares
     JustSearch's **full hybrid + cross-encoder rerank pipeline** against **single-model** baselines
     (`release.v1.json:55-83`). A hybrid+rerank system out-scoring single retrievers is expected, not a finding —
     a reviewer notes this in one line. The methodology doc must **state the comparison class** (and the plan's own
     form rule — `plan.md:146` "never 'my product scores X'" — argues against leading with the "win" at all).
  2. **MIRACL baselines are DEV-split, not our axis.** `release.v1.json:87,97,107` carry explicit caveats
     ("DEV split (≠ our test axis)"; the de baseline is "NON-CANONICAL … a single non-canonical source"). The README
     table presents MIRACL-de/fr cleanly; the methodology doc must carry the split/axis caveat or it overclaims.
  - **Implication for #2:** the writeup is *more* than "honest config disclosure + reproduction commands" — it must
    lead with **method/artifact + comparison-class caveats + confidence tiers** (release.v1.json already carries
    `confidence_tier` per corpus), per the research plan's form rule. The tempdoc's #2 phrasing ("the reproducible
    SciFact win over ColBERTv2/SPLADE++") risks importing the very win-framing the plan quarantines. **Reconcile #2's
    wording with `research-channel/plan.md` decisions 2/3/5 before drafting.**

### F2 — ★ The threat-model doc (#1) is under-scoped if it stops at "CSP loopback-lock"
The privacy claims are **grounded**: the webview CSP is `connect-src 'self' ipc: http://ipc.localhost
http://127.0.0.1:*` with `script-src 'self'` and no external `img/script` sources
(`modules/shell/src-tauri/tauri.conf.json:70`) — so the webview genuinely cannot egress to the public internet. And
there is **no product telemetry exporter** (no posthog/segment/sentry/analytics SDK in `modules/*/src`; the only
"telemetry" hits are internal Lucene commit counters, never exported). **But a STRIDE doc that only covers
*outbound* egress misses the more interesting half — the *inbound* loopback attack surface:**
  - The local API binds `127.0.0.1:*` — reachable by **any other local process / any app on the machine**, and
    historically by web pages via **DNS-rebinding** or localhost-scanning JS. "Loopback-only" = private-from-the-
    *internet*, **not** isolated-from-other-*local-software*. The doc must address: does the local API
    authenticate? check `Origin`/`Host` (DNS-rebinding defense)? And the **`POST /mcp` retrieval endpoint** the
    README markets as a "private retrieval backend" — is it unauthenticated to any local agent? That is the most
    security-substantive question for a tool whose whole pitch is privacy.
  - **Precision trap for the author:** the repo *does* run a dev-time **agent** OTLP sink
    (`scripts/agent-analytics/otlp-sink.py`, binds `127.0.0.1:4318`, data under `tmp/`) — this is **agent-development
    telemetry, not product phone-home.** The threat-model doc (and the README's "no telemetry exporter exists" line)
    must explicitly distinguish dev/agent telemetry from product telemetry, or a sharp reader who greps the repo
    finds "telemetry" + "otlp-sink" and reads it as a contradiction.

### F3 — Item #6 (first-run robustness) confirmed exactly; one addition + an integrity coupling
Enumerated every `downloadUrl` in `modules/ui/src/main/resources/ai/model-registry.v2.json`:
  - **Founder-controlled (public, safe):** all 5 ONNX model families (embed/splade/reranker/ner/citation) → the
    founder's `github.com/eliasjustus/justsearch-releases/.../models-v1`; the ORT CUDA + cuDNN runtime → the same
    repo's `cuda-runtime-12.4` release.
  - **Third-party SPOF (the tempdoc's finding — confirmed):** the chat model →
    `huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF/resolve/ff13963…/Qwen_Qwen3.5-9B-Q4_K_M.gguf` (+ its `mmproj`).
    Pinned to a revision (good for integrity), but if bartowski renames/deletes the repo, **first-run breaks**. Mirror
    onto the releases repo as the tempdoc says.
  - **Addition the tempdoc misses:** the **llama-server binaries** are *also* third-party —
    `github.com/ggml-org/llama.cpp/releases/download/b8571/…` (the win-cuda zip + cudart zip). More durable than a
    personal HF repo (official project, tagged release), but still an external SPOF for first-run; decide whether to
    mirror these too or accept them (ggml-org tags are immutable).
  - **Integrity coupling:** mirroring the GGUF means **updating the registry `downloadUrl` AND keeping the existing
    `sha256`** so the integrity check still passes against the mirrored copy. The mirror is also **LFS/releases-repo
    work that overlaps 634** (snapshot/releases) — coordinate the *where it's hosted* with 634.
  - **Acceptance literally requires resolution:** #6 says "verify *every* downloadUrl resolves from a public source."
    The model id `Qwen3.5-9B` is unusual enough to warrant a one-time `curl -I`/WebFetch HEAD check that the bartowski
    URL actually 200s **today** — if the upstream is already gone, #6 is more urgent than "mirror it." (Not verified
    in this pass — no external fetch run; flag for the implementer.)

### F4 — The contributor-front-door aspiration (#26 / NEW FINDING) collides with a genuinely heavy, Windows-only stack
The "bootstrap one-liner / dev-container so a contributor reaches a green build" is the right *goal*, but the stack
is **Java 25 + 30+ Gradle modules + Tauri/Rust + GPU-optional + Windows-only + ~9 GB one-time model download**
(README draft lines 109,119,142). A true one-command dev-container for a **Windows-native + CUDA + Rust + JDK-25**
target is not realistic. The honest deliverable is a **documented prereq checklist + a `verify-prereqs` script + a
"build without models / CPU-only" fast path**, not a literal dev-container. Recommend re-scoping #26's wording from
"dev-container" to "painless, *documented* first build (prereq check + CPU/no-AI fast path)". Pairs with fixing
CONTRIBUTING's dead `gate.ps1`/`ai-bridge` refs (F-state above) so the front door's commands actually work.

### F5 — Sequencing / boundary notes (so this doesn't trip on 631/632/634)
- **README finalization (#5) is gated**, in-workstream, on **#1 (threat-model link)** + **#2 (benchmark link)**, and
  cross-workstream on **632** (the `THIRD_PARTY_NOTICES` the README references at draft line 155 — 632 generated it on
  its branch) and on **634** (the final public org/repo slug — the draft hardcodes `eliasjustus/justsearch` as a
  `<<confirm at cutover>>`). So #5 produces a *finalized draft with resolved internal links*; the **root placement +
  slug finalization happen at the 634 cutover**, not now (landing the marketing README at root in the *private* repo
  pre-flip would prematurely replace the dev README).
- **Founder-slug scrub is 631's (G1), not 633's** — confirmed the slug spans ~15 tracked files
  (`.github/CODEOWNERS`, `SECURITY.md`, `CONTRIBUTING.md`, `governance/*.schema.json` `$id`s, `packages/plugin-api-ts/*`,
  the registry, etc.). 633 should *not* re-scrub; only ensure the **new** files it authors (FUNDING/SUPPORT/CHANGELOG)
  use the eventual public slug placeholder, deferring the literal to 634's cutover slug decision.
- **These go-public tempdocs (631–634) are untracked on `main`** (verified) and live only in the main checkout's
  working tree — a fresh `EnterWorktree` won't see 633. Per 631 F6: either work the doc-side items in main, or
  `git add` 633 to a feature branch first. (Relevant because most of 633 is doc authoring.)

### Net assessment
The plan is **sound and well-grounded** — the rare go-public workstream where the cited sources actually exist and
check out. The real work is in **getting the output docs' *framing* right**: #2 must not import the win-framing its
own research-channel plan quarantines (F1); #1 must cover the inbound loopback surface, not just CSP egress (F2); #6
is exactly right plus the llama.cpp SPOF + an upstream-resolves check (F3); #26 should drop "dev-container" for a
realistic painless-build story (F4). No item is infeasible; nothing here is a reason to descope. Recommended start
order (matches the tempdoc's "#1–3 are zero-live-risk"): **#1 → #2 → #3 → #4 → #5**, with #6 coordinated against 634.

---

## Long-term design — launch content as governed projection, not fork (2026-06-23)

### The durable problem under the doc-authoring
633 reads as "write the launch docs," but the durable problem is structural and outlives the writing: **the launch
surface makes *claims about the system*, and a public claim that is a hand-copied fact drifts the moment the fact
changes — and on the *public* surface that drift is an irreversible credibility hit in front of an adversarial
audience.** This is not hypothetical; the forks are already on disk: the root README says "React/Vite web UI"
(`README.md:23`) — a fork of the now-Lit reality; CONTRIBUTING references the dead `scripts/gate.ps1` and a
non-existent `modules/ai-bridge` (`CONTRIBUTING.md:31,67,118`) — forks of the real build commands + module list;
the old `NOTICE` referenced a non-existent "licenses directory" (632). The benchmark table, the "downloads from
public sources" claim, and the privacy/CSP claims are simply the *next* forks waiting to drift the first time a
release is re-run, a model URL moves, or the CSP is edited. The purpose of this workstream is therefore not "author
a README once" — it is **be, and stay, true at the public surface.** That is a drift problem, and this codebase has a
settled, named answer to drift problems.

### Conform to the existing seam (do NOT invent a parallel one)
The repo already named this principle and has already applied it to the two adjacent surfaces: **`canonical-authority-
and-projection`** (tempdoc 623 frontmatter `principle:`) — *"a published measurement is a projection of a
cohort-identified reproducible run, never a hand-copied value."* It is an instance of the recurring system shape
**"single canonical authority + governed consumers + fork-prevention gate"** (conforms to 553 SearchTrace / 559
sibling-record / 622 telemetry; further instances 626/627/628; **license attribution = 632's "attribution as
projection, not fork"; eval-INPUT = 635**). Crucially the *generalization* ("every measurement is a projection") is
**recorded-but-deliberately-unbuilt as tempdoc 625** — the exact recognize-the-principle-without-building-the-
framework discipline this design also follows. And the benchmark machinery already runs: `scripts/docs/register-
headline-sync.mjs` projects `release.v1.json` into the internal search-quality register's marker-region, with a
`--check` drift gate (`scripts/ci/check-release-baseline-sync.mjs`); it already formats the external-baseline column
and already encodes 623 C-3/C-4's scope rule (only the production-default headline is projected; heterogeneous
ablation tables stay hand-authored). **633's launch claims are just the *publication consumer* of the same
authorities.** So the correct design is conformance + extension, not new structure.

### The design (general level): three authorities already exist; 633 adds *public* projections
A launch claim is exactly one of two things — **(a) a projection of a declared fact** (generate it; gate it against
drift) or **(b) authored prose** (leave it alone). The whole design is classifying each claim and routing it:

1. **Benchmark claims (README table + methodology doc, #2)** — a projection of `release.v1.json` (the canonical
   cohort-identified run; commit 691d5c5a0). **Extend `register-headline-sync` rather than rebuild:** add the public
   README/methodology marker-region as a *second projection target* of the same source, `--check`-gated, regenerated
   on the existing release / `search-engine-hint` trigger (623 C-6: a projection re-rots without a regeneration
   nudge — this swaps "hand-typed fork" for "stale projection," strictly better only if a trigger exists). Carry
   623 C-3's **two-kinds-of-numbers** rule into the public doc: *our* column is a projection; the *external-baseline*
   column is **cited evidence**, never generated (we cannot re-run Pyserini/ColBERT) — but its provenance is itself
   *declared* in `release.v1.json.external_baselines` (`source_url`, `split`, `self_reproduced:false`, `caveat`) and
   projected verbatim. **Structural fix for F1's methodology hole:** encode the "hybrid+rerank vs single-model" /
   "DEV-split ≠ our axis" comparison-class caveats as fields on each `external_baselines` entry at the canonical
   source, so every projection (README, methodology doc, register, a future blog) carries the caveat *by
   construction* — the win-framing the research-channel plan quarantines becomes structurally impossible to drop.

2. **Download-source claim (#6)** — a *closure property* of `model-registry.v2.json`: every `downloadUrl` resolves
   from a public, project-controlled-or-durable host. Same shape as `check-runtime-manifest-closure` (tempdoc 501):
   a declared manifest + a gate that fails on a non-conforming entry. This is the **one genuinely-new piece of
   structure** the problem requires — and it is minimal (a public-host check over the registry; optional liveness
   HEAD). The README's "downloads from public sources" line then *projects from the verified registry* instead of
   being a hand-promise; the bartowski mirror + the llama.cpp SPOF (F3) become *registry edits the gate validates*,
   not one-time manual verifications that rot.

3. **Privacy / CSP claims (#1 threat-model + README Privacy)** — anchor the *verifiable assertions* to source via
   the existing doc-claim-lint seam (`scripts/docs/check-frontend-stack-claims.mjs` is the precedent: a lint that
   fails when a doc asserts something false about the code; rule #33 / tempdoc 579). A minimal CSP-claim assertion
   (the `connect-src` in `tauri.conf.json:70` still pins `127.0.0.1` with no external origin; no analytics SDK in
   `modules/*/src`) keeps "provable privacy" *provable*. The STRIDE *reasoning* — attack trees, and the inbound-
   loopback / DNS-rebinding / unauthenticated-`POST /mcp` analysis from F2 — stays **authored prose**: it is
   security analysis, not a derived fact.

### What stays prose (scope discipline — do not over-project)
NON-GOALS values, the "how we build" narrative framing (#3), SUPPORT response norms, CHANGELOG, the README pitch,
CONTRIBUTING's tone/process. These are *authored*, not *derived*; projecting them would be premature abstraction for
cases the problem does not include. **The one nuance:** inside #3/#4, factual *enumerations* — the discipline-gate
list, the module table, the build commands — are projections of `governance/registry.v1.json` / the module graph /
reality, and are *exactly where CONTRIBUTING already drifted*. Treat those specific enumerations as derived (cite or
generate); the surrounding prose stays prose.

### Scope verdict
Required new structure: **exactly one small closure gate (#6).** Everything else is **reuse/extension** of seams
already running — `register-headline-sync` + its `--check`; the doc-claim lint; the marker-region projection 632
also reused for `NOTICE`/module-deps. The change is small-to-moderate and dominated by conformance, which is the
correct outcome for a problem the system has already solved twice (623 internal benchmark, 632 license) and is
solving a third time alongside (635 eval input). This neither omits structure the problem requires (the public
benchmark table *will* drift; #6 *is* an objective closure property) nor adds structure it does not (the values
statement and the narrative are not measurements).

## Design reach (recognize, scope, do NOT build now)

- **Not a new principle — a new *consumer class* of an existing one.** This is `canonical-authority-and-projection`
  applied to the **external/published** surface. Conform to it; cite 622/623/632/635 and the deliberately-unbuilt
  generalization **625**. Do **not** spin up a parallel "launch-doc projection framework."
- **The facet 633 adds — name it, don't build it: a projection's blast radius scales with its audience; public
  projections carry an *irreversibility premium*.** An internal projection that drifts (a register headline) is
  fixed quietly by a teammate; a *public* one drifts into an unrecoverable credibility hit in front of the
  adversarial research audience (634: "you cannot un-publish a bad first impression"). The *mechanism* is identical
  to the internal case; the *priority* is strictly higher. Implication: of all projection surfaces, the public ones
  have the **strongest** case for a fork-prevention gate — so the modest gates above are well-justified, not
  gold-plating. (This is a prioritization corollary of the existing principle, not a rival principle.)
- **Candidate scope beyond 633 (record, do not build):** every future *external* surface is a projection of a
  declared fact under the same irreversibility premium — the research/Simon-Willison writeup, a BEIR/MTEB
  leaderboard submission, the **NLnet M2 grant-deliverable text**, the GitHub social-preview/description, eventual
  app-store copy. Each restates benchmark numbers, privacy properties, or scope already declared canonically; each
  is a fork waiting to drift the moment the canonical source moves.
- **Existing violations already on disk (the bug-class is proven on the public surface, not hypothetical):** root
  `README.md:23` "React/Vite"; `CONTRIBUTING.md:31,67,118` (`gate.ps1` + `modules/ai-bridge`); the stale `NOTICE`
  "licenses directory" (632). These are the public-surface instances of the same drift 623 found internally.
- **Adjacent seam it also instantiates (579 consult/maintain-doc — the behavioral twin):** the launch-claim
  *sources* (`tauri.conf.json`, `release.v1.json`, `model-registry.v2.json`) are candidates for the
  `GOVERNED_REGIONS` map (`scripts/agent-analytics/lib/governed-regions.mjs`) so editing a source nudges "update the
  public claim." Recognize this as the exit-side complement to the projection gate; the benchmark regen-trigger
  already exists (`search-engine-hint`), so **no new hook is required now** — record the candidacy, don't wire it.

## Design refinement — second critical pass (2026-06-23)

Re-examined the pass-1 design with one question: *did I force-fit the codebase's favorite "projection" hammer onto
parts that aren't nails?* The core design holds — the three projection surfaces (benchmark / download-source /
CSP-privacy) and the "rest stays prose" split are correct. Three sharpenings, each preventing a wrong implementation:

- **R1 — The methodology doc (#2) is *essay-prose with an embedded generated table region*, not a generated
  document.** The projected thing is the **numeric table** (our column + the external-baseline rows with their
  declared caveats); the surrounding methodology *narrative* (what we measured, why, reproduction story) is authored
  prose. This is exactly the `module-deps.md` shape (prose doc + a `<!-- GENERATED:…:BEGIN/END -->` matrix region),
  so it conforms cleanly — there is no "generate the whole essay" awkwardness. Same for the README: a prose page
  with one generated benchmark-table region. Guards the scope rule (don't over-project narrative) at the file level.

- **R2 — The download-source gate (#6) checks a *static* property, not liveness.** The deterministic, CI-safe
  invariant is *"every `downloadUrl` host is in a public-host allowlist"* (project releases repo, HuggingFace,
  ggml-org) — pure string/host check, no network, offline-CI-safe (ADR-0026 manual CI + fork lanes must not depend
  on reachability). **URL liveness (does it 200 today) is explicitly NOT the gate** — that would make CI flaky and
  network-dependent; liveness stays a *manual* cutover check (634's domain) + the one-time HEAD probe flagged in F3.
  This split mirrors how the runtime-manifest-closure gate checks *declared structure*, not runtime reachability.

- **R3 — The threat-model doc (#1) is *canonical maintained prose*, not ephemeral prose — it belongs under the
  579 governed-doc protocol.** Pass-1 lumped the STRIDE reasoning into "stays prose." Sharpen: the *reasoning* is
  authored, but the doc makes **architectural claims that must not silently drift** (loopback binding, CSP, process
  isolation, no-egress) — the precise failure 579's consult/maintain-doc-hint prevents. So the threat-model doc
  (and the README Privacy section) should (a) live as canonical docs (`docs/explanation/` or a new
  `docs/reference/security/`), and (b) have their *source files* (`tauri.conf.json`, the API binding site) added to
  the `GOVERNED_REGIONS` map so a change to the egress surface nudges "re-verify the threat model." This refines the
  prose/projection split into a **three-way** one: *projected facts* (gated) · *canonical maintained prose* (579
  consult/maintain) · *ephemeral prose* (NON-GOALS values, showcase narrative — ungoverned). The CSP-claim lint (R-shape
  from pass-1 §3) is the *projection* half; the governed-doc placement is the *maintenance* half — both halves of
  the same 579 consult-then-maintain protocol, applied to the one doc whose whole value is that its claims are true.

**Net:** no change to the pass-1 spine or scope verdict (one new gate; the rest reuse). R1–R3 are precision on
*where the marker-region sits*, *what the gate actually asserts*, and *which "prose" is canonically governed vs
free* — they make the design implementable without the three most likely wrong turns.

## Internet-research pass — scope decision + findings (2026-06-23)

**Decision: research ONE part, skip the rest.** Most of this design sits on *stable internal ground* — the
projection/closure spine conforms to `main`'s own seams (623/632), not to any literature; OSS community files
(FUNDING/SUPPORT/CHANGELOG) are settled conventions; and the benchmark-reproducibility landscape was *already*
researched by the adjacent tempdocs (635 ran a 2025–2026 contamination-eval landscape pass; the research-channel
plan grounded the venue/reproducibility norms). Researching those would re-derive what `main` already encodes —
no marginal value. **The one part on fast-moving, post-training-cutoff ground is the threat-model's inbound attack
surface (#1 / F2)** — because (a) JustSearch markets a loopback `POST /mcp` retrieval backend, (b) local-MCP-server
security is the *most* active area of MCP security work right now, and (c) the claim it backs ("your files never
leave / provable privacy") is exactly the irreversible-if-wrong public claim this workstream exists to get right.
So a narrow, surgical pass was warranted; a full deep-research harness was not.

**Findings (current, authoritative — these change the threat-model design):**
- **DNS-rebinding against localhost MCP servers is the dominant 2025–2026 attack class**, with a live CVE wave:
  CVE-2026-42559 (RMCP / official Rust SDK, CVSS 8.8 — a malicious public website drives the victim's browser to
  send requests to a loopback/private MCP server), CVE-2026-11624 (MCP servers failing to validate Origin pre-v0.25),
  the MCP **Python SDK fix in 1.23.0**, and the MCP **TypeScript SDK shipping DNS-rebinding protection OFF by
  default**. The exact-class precedent is **Ollama CVE-2024-28224** — DNS-rebinding a local LLM server to *read and
  exfiltrate arbitrary file data* the server process can reach. This is precisely the outcome JustSearch's privacy
  claim promises cannot happen.
- **Binding to `127.0.0.1` (the project's Hard Invariant #2) is necessary but NOT sufficient.** The robust defense
  is **Host-header allowlist validation** (reject unless `Host ∈ {127.0.0.1:<port>, localhost:<port>}`) — even when
  rebinding tricks the browser into hitting 127.0.0.1, the server sees the attacker's domain in `Host` and returns
  403. The MCP **Security Best Practices** "Local MCP Server Compromise" section names JustSearch's exact case (a
  local server over **HTTP transport**, vs `stdio`) and requires: *"Restrict access if using an HTTP transport —
  require an authorization token; or use unix domain sockets / IPC with restricted access."* Plus: MCP servers
  **MUST NOT use sessions for authentication**, **MUST** use non-deterministic session IDs, and **SHOULD** minimize
  tool scope (relevant: the 5 tools include the privileged `justsearch_ingest`, not just read-only search).
- **Code-fact check against `main` (the load-bearing question):** the local API *does* have
  `modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java` doing **Origin** (CORS-style) validation
  (`resolveAllowedOrigin`, line 304). **But CORS/Origin checking protects *response-reading*, not *request-
  execution*** — a DNS-rebound simple request can still *execute server-side* (the tool call runs, files are read)
  even if the browser blocks the attacker from reading the response. **I did not find explicit *Host*-header
  allowlist validation** (the actual DNS-rebinding defense) on the local API or the `/mcp` endpoint. *(Not a full
  audit — `ApiSecurityFilters` may guard Host elsewhere; this is the single fact to verify, not a confirmed gap.)*

**Design implication (sharpens #1 from "write a doc" to "verify a property, then write the doc"):** the threat-model
doc's load-bearing content is **not** "we bind to loopback, therefore private" — it is the **inbound DNS-rebinding /
local-MCP-compromise analysis**, and the doc can only *honestly* assert "provable privacy" if the local API + `/mcp`
endpoint actually **validate the Host header against a localhost allowlist** (and ideally gate `/mcp` behind a local
auth token, per the spec's HTTP-transport guidance). So #1 carries a **code-verification dependency**, and possibly a
**small, pre-launch hardening task** (add Host-allowlist validation to the shared request filter) — which is exactly
the kind of finding that must be settled *before* the irreversible publish, not after. This does **not** change the
overall design spine (the threat-model *reasoning* is still authored prose under R3's governed-doc placement); it
raises the priority and adds a concrete acceptance bar: *the privacy claim is publishable only once Host-validation
is verified present (or added).* Recommend a one-line note into the inbox / a 631-or-new hardening item; flagged here
so #1 is scoped as verify-then-document, not document-only.

Sources: [MCP Security Best Practices](https://modelcontextprotocol.io/docs/tutorials/security/security_best_practices) ·
[MCP TS SDK DNS-rebinding advisory (GHSA-w48q-cv73-mx4w)](https://github.com/modelcontextprotocol/typescript-sdk/security/advisories/GHSA-w48q-cv73-mx4w) ·
[CVE-2026-11624 (MCP DNS rebinding)](https://threat-modeling.com/cve-2026-11624-mcp-dns-rebinding/) ·
[Ollama DNS-rebinding CVE-2024-28224 (NCC Group)](https://www.nccgroup.com/research/technical-advisory-ollama-dns-rebinding-attack-cve-2024-28224/) ·
[IBM bulletin — MCP Python SDK fixed 1.23.0](https://www.ibm.com/support/pages/node/7268889)

## Pre-implementation confidence pass (2026-06-23 — read-only probes, plan-approved)

Ran the 8 de-risking probes from the approved plan against `main`. All resolved; the highest-stakes unknown (the
DNS-rebinding defense) is now characterized precisely with the exact fix site. Findings with `file:line` evidence:

- **P1 — DNS-rebinding defense: PARTIAL, gap is narrow and the fix site is identified. ★** The loopback API
  (`modules/ui/src/main/java/io/justsearch/ui/api/ApiSecurityFilters.java`) defends via **Origin/CORS**
  (`resolveAllowedOrigin`, :304 — prod allows only `tauri://localhost` / `https://tauri.localhost`) **and a
  startup session token required for POST/PUT/DELETE in prod** (:117-156; token is Tauri-bridge-injected, so a web
  origin can't obtain it). The `/mcp` endpoint is **`POST` + `DELETE` only** (`LocalApiServer.java:561-562`, no
  `GET`) → **the MCP retrieval backend is token-protected against DNS-rebinding in prod.** **The residual gap:**
  **`GET` reads are token-exempt** (:136) — `/api/knowledge/search`, `/suggest`, `/status`
  (`KnowledgeRoutes.java:28-33`) — and after DNS-rebinding the request is *same-origin* with the attacker domain, so
  CORS/Origin is moot; **only a `Host`-header allowlist would stop it, and there is none** (`LOOPBACK_HOSTS` at :33
  is used *only* for Origin parsing, :327/:338). So a malicious page a victim visits could exfiltrate *search
  results* (not trigger tool calls / mutations). **Fix:** add a `Host ∈ LOOPBACK_HOSTS:<port>` check to
  `ApiSecurityFilters.install` (one before-filter, reusing the existing constant). → **#1 is verify-then-document
  *and* recommend this small hardening; the privacy claim is honest once it lands.** Ownership = founder decision D2.

- **P2 — Benchmark projection source is UNTRACKED.** `git ls-files scripts/jseval/release.v1.json` → empty: the
  projection's *source of truth is not committed*. `register-headline-sync.mjs` emits a "pending" placeholder when
  no release is present (:48-54), so the internal register headline is currently *un-projected* in CI. **#2 must
  first commit `release.v1.json`** (or confirm `jseval release` regenerates it deterministically and commit it) —
  otherwise the public table has no stable source. `release.v1.schema.json` exists (schema half present). *(Partial:
  did not fully read `buildGenerated` past :55 — the "second projection target is cheap" claim is likely but not
  fully verified; small residual.)*

- **P3 — F1 caveat fix is in-lane (RESOLVED).** `external_baselines[].caveat` already carries rich hand-authored
  prose with citations ("NON-CANONICAL…", "DEV split (≠ our test axis)") — it is an *author-set* field, not derived.
  So encoding the "hybrid+rerank vs single-model" comparison-class caveat = **populate the existing field** in the
  release input + recompose; no schema/composer surgery. (Coordinates with jseval since the input feeds `jseval
  release`, but no new structure.)

- **P4 — Download-source gate is genuinely NEW (small).** Only `check-notices-regen.mjs` reads
  `model-registry.v2.json` (632's license projection); **no model-registry validation or dual-copy sync gate
  exists.** So #6's gate is new, not an extension — confirmed minimal: a static public-host allowlist
  (`github.com/eliasjustus/justsearch-releases`, `huggingface.co`, `github.com/ggml-org`) over every `downloadUrl`.

- **P5 — Third-party first-run sources RESOLVE today (fragile, not broken).** The bartowski repo exists with
  `Qwen3.5-9B-Q4_K_M.gguf` (6.17 GB); llama.cpp `b8571` exists with both win-cuda assets. → mirror the GGUF as
  planned (not an emergency). **One residual flag:** the repo file listing did **not** surface the
  `mmproj-Qwen_Qwen3.5-9B-f16.gguf` the registry references — could be pagination / only-at-pinned-revision /
  genuinely absent. **Targeted HEAD on the exact revision-pinned mmproj URL** is the one external check still owed
  (if absent, vision-OCR first-run breaks → more urgent than a mirror).

- **P6 — CONTRIBUTING is 633's to own, no conflict (RESOLVED).** `worktree-632-licensing` no longer exists
  (`git branch -a` shows only `main`, `worktree-604-liveness`, `worktree-612-polish`, `codex/installer-ci-lockfix`).
  632's React→Lit CONTRIBUTING pass is **not on any reachable branch** and `main`'s CONTRIBUTING still says "React"
  → 633 does the fix outright; no double-edit risk. *(Side-note for the founder: 632's licensing branch being gone
  may mean its NOTICE/gen-notices work is also unmerged — a 632/634 concern, logged here only as a flag.)*

- **P7 — Threat-model placement + governance wiring is cheap (RESOLVED).** `llmstxt-generate.mjs:113` enumerates
  canonical dirs `["explanation","reference","how-to","decisions","governance"]` — so a doc under
  `docs/reference/security/` is **auto-included** (it's under `reference/`), no new-dir wiring. `governed-regions.mjs`
  compiles rows from `governance/consult-register.v1.json` (declarative `pathIncludes`/`docs`), so adding the
  threat-model doc + `tauri.conf.json` as a consult-register row is a **one-row config edit**.

- **P8 — Showcase raw material confirmed present.** `governance/README.md`, `scripts/agent-analytics/README.md`,
  `docs/reference/contributing/discipline-gate-kernel.md` exist → #3 is assemble-and-frame, overlapping 631 G9's
  signposts (founder framing decision D3).

### Confidence rating for the remaining work: **8 / 10**

The technical surprises are now characterized, not latent: the scariest unknown (P1) resolved to a *narrow*,
well-located gap with a known one-filter fix; the projection machinery exists; the download gate is small and
genuinely new; the OSS files are unblocked; the first-run sources resolve. **Why not higher:** (i) `release.v1.json`
must be committed before the benchmark projection is real (P2) and `buildGenerated`'s reusability is only
likely-verified; (ii) the `mmproj` URL flag (P5) is an open external check that could escalate #6; (iii) the P1
hardening's *ownership* (633 vs 631 vs 634) and the README *landing sequence* (D1) are founder decisions that could
reshape scope. **Why not lower:** none of these are unknowns anymore — they are scoped items with identified
mechanisms and evidence, which is exactly the pre-implementation state this pass aimed for.

## Implementation status (2026-06-23 — built in the main checkout, uncommitted WIP; live-validated)

All agent-scoped items implemented + verified. Founder decisions baked in: **README/NON-GOALS staged for the 634
cutover** (not landed at root); **the Host-validation hardening included in 633**. Showcase (#3) was already
present (MAINTAINING.md + signpost READMEs — 631 G9), so it was not re-authored.

**#1 — threat-model + Host hardening ✓**
- **1a (hardening):** `ApiSecurityFilters.install` now runs a `setupHostValidation` before-filter rejecting any
  request whose `Host` ∉ `LOOPBACK_HOSTS` (the DNS-rebinding defense; reuses the existing constant). Package-private
  `isAllowedHost` + rate-limited `maybeRecordHostDeny`. New `LocalApiHostValidationTest` (predicate + raw-socket
  live tests) — green; existing `LocalApiCorsPolicyTest`/`LocalApiUiTokenPolicyTest` still green.
- **1b:** `docs/reference/security/threat-model.md` — STRIDE + the inbound DNS-rebinding analysis, grounded in
  `tauri.conf.json` CSP + `ApiSecurityFilters` + `LocalApiServer.java:561-562` (`/mcp` is POST/DELETE only), with the
  dev-vs-product telemetry distinction.
- **1c:** `governance/consult-register.v1.json` — a `maintain:true` `threat-model` row keyed to `tauri.conf.json` +
  `ApiSecurityFilters.java`.
- **1d:** `scripts/docs/check-privacy-claims.mjs` (CSP loopback-lock + no-analytics-SDK lint) — green; wired into
  `.github/workflows/docs-lint.yml`.

**#2 — benchmark methodology projection ✓**
- **2a:** comparison-class caveat added to the SciFact entries in `scripts/jseval/external-baselines.v1.json` (the
  F1 source fix — travels to the next release composition). **2b: release.v1.json deliberately NOT recomposed** — it
  is pinned to cohort commit `691d5c5a0`; recomposing at HEAD would falsify cohort identity. The methodology *prose*
  carries the comparison-class caveat now (belt); the source edit propagates next release (suspenders).
- **2c:** `docs/reference/benchmarks/methodology.md` — prose essay + an embedded generated table region, leading
  with the system-vs-component + DEV-split honesty.
- **2d:** `scripts/docs/gen-public-benchmark.mjs` — projects `release.v1.json` into the methodology doc's marker
  region (reuses the `fmtExternal` shape); `--check` mode. **2e:** wired into `scripts/ci/check-release-baseline-sync.mjs`.
  Table reconciles with the README numbers (SciFact 0.751, CourtListener 0.620 hybrid / 0.971 full, etc.).

**#3 — showcase ✓ (already present)** — `MAINTAINING.md` + `governance/README.md` + `scripts/agent-analytics/README.md`
already carry the optional/transparency framing (631 G9). Only added: CONTRIBUTING's "How we build" pointer → `MAINTAINING.md`.

**#4 — OSS files ✓** — CONTRIBUTING: conventional front-door preamble (machinery optional) + `ai-bridge`→`gpu-bridge`/
`app-inference` (React→Lit + `gate.ps1` were already fixed in main by a parallel edit). New `SUPPORT.md`,
`CHANGELOG.md` (Keep-a-Changelog, fresh-history note), `.github/FUNDING.yml` (founder placeholders).

**#5 — README/NON-GOALS finalized + staged ✓** — the 633-owned placeholders resolved in the positioning drafts
(threat-model link → the shipped doc; benchmark link → the methodology doc; `THIRD_PARTY_NOTICES` referenced); the
cutover-package README updated to mark them finalized-for-634 and list what's already shipped vs still founder-gated.

**#6 — first-run robustness ✓** — `gates/ssot-catalog-sync/mirrors.json` gains a `model-registry` dual-copy mirror
(gate green); `ModelRegistryLoaderTest.everyDownloadUrlResolvesFromPublicHost()` asserts every `downloadUrl` is
HTTPS from `{github.com, huggingface.co}` (green). **mmproj flag resolved:** the pinned-revision
`mmproj-Qwen_Qwen3.5-9B-f16.gguf` (918 MB) **is present** — first-run is not broken; mirror as planned (634).

### Verification evidence
- `./gradlew.bat build -x test` — **BUILD SUCCESSFUL** (compile + spotless + PMD repo-wide).
- `:modules:ui:test` (Host + CORS + token tests) and `:modules:configuration:test` (ModelRegistryLoaderTest) green.
- Lints/gates green: `check-privacy-claims`, `gen-public-benchmark --check`, `check-release-baseline-sync`,
  `ssot-catalog-sync` gate, `verify-canonical-doc-links`, `markdownlint`, `llmstxt-generate --check` (regenerated).
- **★ Live UI validation (dev stack, apiPort 49263):** the real webview rendered the search surface ("CONN", 456
  docs, API `http://127.0.0.1:49263`) and a search for "the" returned "Top 50 of 458 matches · 300ms" with
  results — the legitimate webview→loopback path (incl. the token-exempt `GET /api/knowledge/search`) survives the
  new filter. **Negative (curl):** spoofed `Host: evil.com` on `/api/knowledge/search` and `/api/health` → **403**;
  real loopback / `localhost` Host → **200**. DNS-rebinding vector closed; UI unaffected.

### Correction to the confidence pass
**P2 was wrong:** `release.v1.json` **IS git-tracked** (`git ls-files` returns it) — I mislabeled my own probe
output. No "must commit it" step was needed; the projection source was already committed.

### Founder / 634 hand-offs (not agent work)
- **634:** promote the staged README/NON-GOALS to repo root + resolve the final repo slug, demo GIF, badge URLs;
  mirror the `bartowski` chat-model GGUF onto the releases repo (then update the registry URL — validated by #6b);
  the Host-hardening composes with 634's gitleaks/safety pass. **631:** the founder-slug scrub (left as-is here).
  **Founder (#7):** GitHub description, topics, social-preview image.
- **Note:** the README's *summary* benchmark table is still hand-authored (matches `release.v1.json`); at the 634
  root-promotion it should get the `gen-public-benchmark` marker region too (the generator supports adding a
  second target). Left for 634 since the README is not at root yet.

### Post-review fixes (2026-06-23, critical-analysis pass)
A self-review against the design found four substantive issues; all fixed + verified:
- **A — integration coverage gap closed.** Ran `:modules:ui:integrationTest` (builds a real `LocalApiServer`
  via `LocalApiIntegrationTestBase`) — **green**: the global Host before-filter doesn't regress HTTP-level
  integration tests (they connect over loopback). The prod frontend also uses loopback (`http.ts:15`
  `DEFAULT_HOST='127.0.0.1'`), so the filter is safe in prod too.
- **B — privacy-lint egress hole closed.** `check-privacy-claims.mjs` now uses a **permitted-token allowlist**
  for `connect-src` (was a per-host check that let scheme wildcards through). Proven: `*`, `https:`, `http:`,
  `data:`, `blob:`, external hosts, and the subdomain-spoof `127.0.0.1.evil.com` all now FAIL; the current CSP
  passes. This catches the drift class the lint exists to guard.
- **C — README win-framing removed.** The SciFact note no longer reads "above ColBERTv2/SPLADE++"; it now reads
  "in the range of … — *system vs. component*" and links the methodology's comparison-class section. The
  headline credibility risk (research-channel plan decision 5) is now neutralized on the *most-read* surface,
  not only in the methodology doc.
- **D — dead generator target removed.** `gen-public-benchmark.mjs` now targets only the methodology doc.

**Honesty correction to the F1 claim:** the generated benchmark *table* renders model/value/split, **not** the
`caveat` field — so the comparison-class caveat does not "travel by construction" *in the table*. It is surfaced
where readers actually see the numbers via (i) the methodology doc's prose section and (ii) the README note (fix C),
and it is carried in the data source (`external-baselines.v1.json`) for the next release. That is the accurate
scope of the F1 fix.

### Confidence pass on the two remaining gaps (2026-06-23 — read-only probes, plan-approved)

The two gaps from the conceptual review (Contributor-DX; README-table-as-fork). Probed both; the load-bearing
unknowns resolved, and **the remaining work is smaller than the NEW FINDING's wording implies.**

- **Gap 1 — the "heavy build" fear is largely unfounded *for the contributor front door*.** Verified: (i)
  `modules/shell` has **no `build.gradle.kts`** and is **not** a Gradle subproject → `./gradlew build` does
  **not** compile the Rust/Tauri shell, so **no Rust toolchain** is a build prereq; (ii) the JDK is
  **auto-resolved by the Gradle toolchain** (no manual JDK-25 install); (iii) unit tests are
  **model-independent** — they fabricate stubs/fakes in temp dirs (`InferenceConfigFromEnvironmentTest` writes a
  1-byte fake gguf under `tempDir`; `InferenceConfigServerExeTest` writes fake `llama-server.exe`;
  `LambdaMartRerankerTest` uses `assumeTrue(...)` to skip) — so **no 9 GB model download** is needed to `build` +
  `test`. The only real prereq beyond Gradle is **Node** (for `modules/ui-web`), and a helper already exists
  (`scripts/setup/bootstrap-node-win.ps1`). → **Deliverable shrinks to documentation**: state in CONTRIBUTING that
  build+test is light (no Rust/models/GPU; JDK auto-resolved), list the true minimal prereqs, and surface the
  bootstrap-node helper. **Not** a dev-container, **not** a new doctor script. (`scripts/verify-prerequisites.mjs`
  exists but is an *AI-runtime* verifier, not a build-prereq checker — out of scope here.)

- **Gap 2 — the README table should be a *number-consistency-check*, not a full projection.** The README table is
  editorial (curated 5-corpus subset + prose notes); a full mechanical projection would flatten the messaging and
  conflate two change-reasons (AHA). The proportionate fix conforms to **632 P3's "consistency-check, not
  generation" decision** for the same editorial-vs-derived tension: a check that asserts the README's nDCG numbers
  match `release.v1.json` (within rounding), preserving the prose while catching numeric drift. Its natural home is
  the **634 root-promotion** (README at repo root + in public CI); against the staged draft it would run locally
  and skip in CI under the established sidecar local-vs-CI duality.

**Recommended shapes (to confirm with the founder at implementation):** Gap 1 = document-the-light-build (vs the
NEW FINDING's literal "dev-container"); Gap 2 = consistency-check (vs full projection), wired at 634.

#### Confidence rating for the remaining work: **8 / 10**
Both gaps' load-bearing unknowns are resolved and the work is smaller + lower-risk than feared (Gap 1 ≈ docs;
Gap 2 ≈ a small check with a decided shape). **Why not higher:** (i) test model-independence is inferred from
stub-fabrication + this session's green runs, not proven by a full `test` suite with `models/` physically absent
(I won't delete the shared dir); (ii) two founder-preference confirmations remain (document-vs-container;
check-vs-projection); (iii) the Gap-2 check's natural timing is 634, a sequencing nuance. None are unknowns now —
they are scoped, evidence-backed decisions awaiting a founder nod.

### Two remaining gaps — implemented (2026-06-23, founder-confirmed)
Both founder decisions came back: **Gap 1 = document the light build**; **Gap 2 = number-consistency check**.

- **Gap 1 ✓ (docs).** `CONTRIBUTING.md` Prerequisites now splits **build+test** prereqs (a bootstrapping JDK —
  Gradle auto-resolves JDK 25 — + Node, with `scripts/setup/bootstrap-node-win.ps1` surfaced) from **runtime**
  prereqs (GPU optional, models on first run), and states `./gradlew build` needs **no Rust toolchain, no GPU, no
  ~9 GB model download**. The README draft's Quickstart prereqs line carries the same "building needs none of
  that — models are runtime-only" clarification. No dev-container / new script (the front door is already light).
- **Gap 2 ✓ (consistency check).** New `scripts/ci/check-readme-benchmark-numbers.mjs` asserts every nDCG number
  the README *shows* matches `release.v1.json` (values **derived** from the release, within the README's
  rounding), preserving the editorial prose — the 632 P3 "consistency-check, not generation" shape. It
  **skip-if-absent** and **auto-resolves** between the root `README.md` and the staged draft (whichever has a
  `## Benchmarks` section), so it guards the draft now and the promoted root README at 634 with no re-point. Wired
  into the cutover-package `public-ci.yml` (activates in public CI at the 634 root-promotion).
  - **Verified:** positive (6 shown numbers match), **negative-proof** (corrupting SciFact 0.751→0.799 → FAIL with
    the corpus + expected/found), restore → green. `check-frontend-stack-claims`, `check-privacy-claims`,
    `gen-public-benchmark --check` all still green. No UI surface in this work → no browser validation (considered
    judgment, not an omission).

**This closes the agent-scoped 633 work.** Remaining items are founder/634 hand-offs: README/NON-GOALS root
promotion (at which point the README-numbers check + the methodology-table projection begin guarding the root
README in public CI), final repo slug, the `bartowski` GGUF mirror, and repo presentation (#7).
