---
title: "Go-public (Option C) — the cutover & develop-in-public transition: the final irreversible safety pass (gitleaks on the snapshot), the fresh-history snapshot + repo creation, the private sidecar + frozen archive, the multi-agent cut-line (drain WIP, all sessions move to public at one line), the local-env rewire (sidecar mount + worktrees + commit guards), the repo settings (branch protection + fork-PR approval + self-hosted runner), and public-but-unannounced staging. Workstream 4 of 4 — execution; gates on 631–633."
type: tempdoc
status: "planned — go-public Option C, workstream 4/4 (execution). [founder] + [cutover]-heavy and operational. The most under-scoped piece is the multi-agent cut-line. Runbook mechanics live in the private cutover-package (go-to-market/cutover-package/cutover-runbook.md). 2026-06-23: added the §Inbound cutover-dependent hand-offs registry (the single projection of 631/632/633's flip-gated items) + recorded the private staging repo eliasjustus/justsearch-launch. 2026-06-24: clarified the flip-vs-announce boundary (this gates neither signing nor first-run readiness — those gate the *announce*); tightened the entry precondition (633's code has landed, `ded1779ca`); surfaced the pre-existing 632 gate-reds as a green-gate risk; gave the multi-agent cut-line a checkable definition-of-ready + a staging-repo rehearsal; re-scoped the 624 redaction; stamped the registry current through tempdoc 645. 2026-06-24 (takeover verification pass): added §Investigation notes — verified the registry against `main` (HEAD `8b5d4a10d`) + the live GitHub repos, re-synced 3 drifted projection items (the 632 gate-red specifics, the green-tree precondition scope, the bartowski public-host-test claim), and flagged the self-hosted-runner↔sidecar security coupling as the second under-specified risk beside the cut-line. Then added §Theorization & open directions (exploratory) — the canary's 'unannounced≠unindexed' limit, the cut-line's true invariant being *freeze* not *simultaneous-move*, four hidden assumptions (history/content/contributor-agent/guard-prefix), the publish-the-mechanism-keep-the-data principle, and a candidate `cutover-preflight` oracle as the doc's deeper canonical-source→projection→drift-gate shape."
created: 2026-06-22
updated: 2026-06-27
related:
  - 631-go-public-publish-machinery
  - 632-go-public-licensing-legal
  - 633-go-public-launch-content
---

# Go-public (Option C): the cutover & develop-in-public transition

## Context
The actual **flip** plus the **operational transition** to developing in the public repo. Going public is
**irreversible** (you can't un-publish a leaked secret, a broken build, or a bad first impression), so the safety
pass here is the load-bearing gate. The under-appreciated part is the **multi-agent cut-line**: the team runs
3–4 concurrent agent sessions on private `main`, and they all have to move to the public repo at *one* clean line.

## What this cutover does NOT gate on (the flip-vs-announce boundary)

634 scopes the **structural flip** plus **public-but-unannounced** staging — *not* the launch. Two
things that the strategy names as hard launch blockers are **deliberately out of scope here** and gate
the **announce** sequence (separate work, step 7's hand-off), not this cutover:

- **Code signing / SmartScreen reputation** — owned and *designed* in **374 G4 / 617 D4** ("drop-in
  ready, not bought"); explicitly deferred pending an Authenticode cert purchase (a `[founder]` budget
  call). Unannounced staging with 1–2 *trusted* devs (scope #7) tolerates an unsigned installer (they
  click through, as in today's manual flow); a public **announce** to strangers / the professional ICP
  does **not** (`docs/business/strategy.md` G0).
- **First-run / product-readiness robustness** — the durability/readiness cluster (**626–630**) and the
  "5-minute success" path. Necessary before announcing; not required to make the repo public-but-quiet.

**The trap this prevents:** reading "cutover done" as "ready to launch." It is not — going public here
is the irreversible *structural* step; announcing on an unsigned alpha is the separate, readiness-gated
step. Keep them sequenced (strategy E.2 #3: protect the one-shots until prerequisites are met).

## Scope (gates on 631 + 632 + 633 being done)
1. **Final safety pass** `[agent][cutover]` — install `gitleaks` (absent locally) + run it on the **final
   snapshot** + a sensitive re-scan, **immediately before the flip** (a concurrent commit could introduce a
   secret between prep and push). Confirm **fresh history** (no import) so the dirty 6,200-commit history never publishes.
2. **Snapshot production** `[agent-prep / founder]` — apply the Option-C include/exclude (cutover-runbook): publish
   almost everything; private = strategy sidecar + local-runtime bits + the 9 LFS model blobs. Fresh clean history.
3. **Repo creation + sidecar + archive** `[founder]` — create the public repo (**the trigger**); create the
   **private sidecar repo** for `docs/business/` + `docs/market-analysis/`; **freeze the current repo as the archive**.
4. **★ The multi-agent cut-line** `[founder + agents]` — drain in-flight WIP on private `main`; **all sessions move
   to the public repo at one clean line** (you cannot half-transition with some agents on private-`main` and some
   on public). The deepest operational risk; can't be fully dry-run. *(→ Phase-0 decision #2, 2026-06-25:
   resolved to **freeze-first** — the founder stops all agent sessions, then the snapshot is taken from the clean,
   frozen `main`; the synchronized "one clean line" is no longer required. See §Phase-0 decisions.)*
5. **Local env rewire** `[founder/agent]` — the dev checkout becomes the public repo with the **sidecar mounted as
   a gitignored folder** (so the workflow + the sidecar-reading gates still work locally); re-prepare worktrees
   against the public repo; point the dev-stack/MCP tooling there; install the **commit guards** (gitleaks
   pre-commit + `core.hooksPath`); set the commit-scope convention.
6. **Repo settings** `[founder]` — branch protection on `main`, **fork-PR approval**, register the **self-hosted
   runner** for the GPU lane (per 631's CI split). *(→ Phase-0 decision #1: **1a** — the GPU lane runs only on
   push-to-`main` / maintainer-dispatch, **never on fork PRs**, so the runner never executes untrusted code;
   "fork-PR approval" becomes a backstop, not the primary control. Resolves the §C runner↔sidecar coupling.)*
7. **Public-but-unannounced staging** `[agent+founder]` — push; full `gitleaks` over the live repo; CI **green on
   both lanes**; have 1–2 trusted devs run the README quickstart + the MCP setup; fix the first impression. **Then**
   hand to the launch/announce sequence (separate from this structural work).

## Done
Public-but-unannounced; both CI lanes green; the trusted-dev quickstart + MCP setup pass; development happening in
the public repo with the strategy sidecar mounted privately; the old repo frozen as the archive.

## Post-flip stabilization note (2026-06-27)

The public repository (`eliasjustus/justsearch`, local checkout `F:\justsearch-public`) is now the canonical
development repo. The former private repository (`F:\JustSearch`) is archive/history only and must not be treated as
an active source to re-snapshot from without deliberately replaying public-only changes such as CLA policy, CI fixes,
README truthfulness updates, and snapshot-exclusion updates. The sidecar remains private data, not an upstream source
of public development truth.

The post-flip stabilization pass also records `docs/tempdocs/390-results/` as a machine-local artifact directory that
belongs in the snapshot exclusion list. This is independent of the intentionally deferred `docs/future-features/`
link cleanup.

Hosted public CI uses `assemble` plus the separate `test`, license, notice, benchmark, secret-scan, CLA, and DCO gates.
It intentionally does not use root `build` as the hosted build step, because root `build` currently pulls in broader
PMD/SpotBugs/integration-test debt that was not part of the public cutover stabilization surface.

## Dependencies
**Gates on 631 + 632 + 633 (all three).** This is the last workstream.

**Entry precondition (to *start* the snapshot):** 633's *implementation* has now **landed on `main`**
(`ded1779ca feat(633): go-public launch content + first-run robustness + loopback hardening`, 2026-06-24
— note 633's own frontmatter status still reads "planned" and lags the code). So the precondition
narrows to: (a) the remaining 633 **staged-not-landed** assets (README/NON-GOALS root promotion, the
model-mirror — see "From 633" below), and (b) **`main` verified green on the gates that will run under
public CI** (incl. resolving the pre-existing 632 gate-reds — see below) — the fresh-history snapshot
(scope #2) can only capture a clean, committed, green tree. This gates *beginning* 634; the founder name
decision + the repo-creation trigger gate *completing* it.

## Inbound cutover-dependent hand-offs (the single registry — added 2026-06-23)

The one place that answers *"what unblocks at the flip?"* Every item below is work the other workstreams
finished *up to* the point a public repo is required; it lands here, at the cutover, because it cannot exist
or be validated pre-flip. This is a **projection of the source tempdocs' deferred items**, not a second
authority — if a source tempdoc adds a cutover-gated item, mirror it here.

> **Registry currency:** verified against all tempdocs through **645** (2026-06-24). 643/644/645
> (judge-stage ranking, eval-from-worktree, jseval CLI split) are engine/eval-tooling work and add **no**
> cutover-gated items. Re-check this stamp when a new go-public-family tempdoc (631–634 lineage) lands.

### Repo identity (resolve first — blocks everything public-facing)
- **The `justsearch` name is taken by the existing *private* dev repo** (`eliasjustus/JustSearch` — this
  checkout's `origin`, ~6,200-commit dirty history that must NOT publish per scope #1–2). GitHub names are
  case-insensitive, so a fresh `justsearch` can't be created until the old one is renamed/archived.
- **A clean private staging repo exists: `eliasjustus/justsearch-launch`** (created 2026-06-23, empty, PRIVATE —
  fresh history). The cutover sequence for the name: **(a)** drain WIP + freeze/rename the old `JustSearch` →
  archive (scope #3); **(b)** rename `justsearch-launch` → `justsearch`; **(c)** populate from the snapshot
  (scope #2) and flip public (scope #7). Confirm the **final slug** (provisional pending the 632 trademark check)
  before the rename. *(→ Phase-0 decision #4, 2026-06-25: slug **confirmed `justsearch`**; the rename stays
  deferred to the flip since the private `eliasjustus/JustSearch` still holds the (case-insensitive) name.)*

### From 631 (publish machinery) — agent-side COMPLETE + committed (2026-06-23); these flip-gated items remain

**Prepared artifacts (ready to apply at the flip):**
- **`cutover-package/public-settings.json`** — the composed public `.claude/settings.json` (worktree/MCP/plugins +
  the **guards-only** hooks block; **no `permissions`, no `env`**). Regenerable: `node
  scripts/codegen/gen-agent-hooks-wiring.mjs --emit-public-template`. This is the swap source for the step below.
- **Wire-policy DECIDED (guards-only).** The 4 founder-analytics hooks (`dispatch`, `export-session-env`,
  `otlp-sink-ensure`, `mcp-session-inject`) are excluded from the public wiring (present-but-opt-in) — encoded in
  the generator's `PUBLIC_EXCLUDED_HOOKS`. So the published guards are live but not imposing.
- **`MAINTAINING.md` + signpost READMEs** (`.claude/`, `scripts/agent-analytics/`, `governance/`) — committed,
  publish as-is (the maintainer front-door + "present for transparency, contributors can ignore it").

**Cutover executions (cannot be done pre-flip):**
- **`settings.json` swap + exclude** — replace live `.claude/settings.json` with `public-settings.json`; `git rm
  --cached .claude/settings.local.json .mcp.json` + gitignore them; ship a `settings.local.json.example` so a
  maintainer re-wires the analytics hooks locally. *(Deferred because untracking shared per-machine config + a
  double-loaded hooks block would break in-flight private multi-agent dev.)*
- **Regen-gate repoint (631 F3)** — `check-agent-hooks-wiring-regen` / `gen-agent-hooks-wiring.mjs` currently read
  `settings.local.json` (absent publicly → ENOENT crash). Point public-CI's check at the committed template. The
  published guards are inert until the swap + this land.
- **Public CI re-architecture (item 4)** — hosted-lane / GPU-self-hosted split + fork-PR-approval. Skeleton:
  `cutover-package/public-ci.yml`. *(Deferred: free hosted runners + the fork-PR-approval setting can't exist
  until the public repo does.)*
- **`CLAUDE.md` slim (G8)** — move the maintainer-personal ops (worktree ownership, dev-stack lease, telemetry,
  parallel-agent rules) out of the auto-loaded `CLAUDE.md`; `MAINTAINING.md` is the destination (already in place).
  *(Deferred: stripping the always-loaded file degrades in-flight private dev.)*
- **Snapshot include-list correction** — publish the full machinery dependency closure
  `scripts/{agent-analytics (incl. `lib/`), governance, ci, codegen}/`, **not** just `hooks/` (631 C1 — the bullet
  in the runbook was stale; hooks import `../lib/*` and the 36 gate enforcers live under `scripts/governance/gates/`).
- **"Green on both CI lanes" + integrated green-under-public-conditions run** in a disposable clone (excludes
  applied + template swapped). 631's Done was honesty-corrected to defer this integrated proof here — pre-flip it
  is only reasoned component-by-component (no gate reads the sidecar; model tests self-skip; template builds).

**Pre-snapshot decisions/scrubs (founder — resolve before the flip, not 634-executed):**
- **624 NLnet-grant-passage redaction** — `624-agentic-retrieval-eval-rebuild.md` stays PUBLIC (shipped
  `scripts/jseval/**` + its schema cite it as the design authority — un-moved 2026-06-23), but it candidly says the
  **92% number in the live NLnet grant application** was an unreliable "fork" and links the private grant draft
  (`:71/100/104/204/610/614/1191`). Redact those grant-integrity lines before the snapshot. **Re-scope at
  cutover against 624's *current* state** — since this item was written, 624 shipped the eval machinery and
  *measured the honest realistic agent-utility number as weak* (+0.00 acc / ~8% tokens; business re-rooting
  still owner-gated). Confirm the redaction covers any newly-added grant-sensitive content (the weak-number
  finding, the re-root decision, links to the private draft), not just the original "92%-was-a-fork" lines.
  **[founder]**
- **Attacker's-roadmap lens** — publishing 388 candid design docs is an attack-surface map for a privacy product
  (item-5's scan covered secrets/PII, not exploitability). Decide: accept as the transparency tradeoff, or a bounded
  targeted "would this help an attacker?" pass over the security-relevant tempdocs (loopback/sandbox/extraction/
  secrets-handling) before the flip. Rec: the targeted pass. **[founder]**
- **Item-1 third-party commentary flags** (~7 passages, assessed publishable) + **analytics-scripts publish scope
  (C1)** (publish all of `scripts/agent-analytics/` incl. telemetry tooling, vs discipline-core only). **[founder]**

### From 632 (licensing/legal) — agent-actionable work COMPLETE + merged (2026-06-23); only these flip-gated items remain
- **★ Resolve the pre-existing 632 gate-reds first** — session observations note red 632 discipline-gate
  checks on `main` (`09d248a93 chore(637): ... pre-existing 632 gate reds`). Scope #7's "CI green on both
  lanes" cannot pass through them, and scope #2's snapshot must capture a green tree. Before the snapshot:
  either fix them or confirm each is a **sidecar-absent false-positive** that self-skips under public CI
  (no gate reads the private sidecar). **[agent triage → founder sign-off]**
- **`public-ci.yml` validation that can't exist pre-flip** — `checkLicense` green on the *hosted* lane, the
  repo-local DCO sign-off check, the npm/cargo license dumps in CI (632 Stage F authored these; validation defers here).
- **DCO enforcement** — post-flip stabilization replaced the third-party DCO action / app-dependency path with a
  repo-local CI check. CLA assistant remains the one-time contributor agreement gate; DCO remains per-commit.
- **Packaging validation of the libjbig KEEP compliance** — 632 decided **keep** `libjbig-0.dll` (GPL-2.0) and wired
  the Gradle staging so `NOTICE-JBIGKIT.txt` + `LICENSE-GPL-2.0.txt` ship into `native-bin/tesseract/` next to the
  DLL (GPL-2.0 §1/§3). Confirm they actually land in a real packaging/installer build at cutover. **[cutover]**
- **NVIDIA notice-retention check** — one-time: confirm the shipped `variants/cuda12/` runtime retains NVIDIA's
  license files (the accept-and-document posture hinges on not stripping them). **[founder/legal]**
- **Trademark / final slug** — 632 decided **keep `justsearch` provisionally** (revisit only on a collision /
  protectability problem); the Repo-identity "final slug" confirmation above can proceed on that basis. **[founder]**

**Explicitly NOT cutover gates** (recorded so they aren't mistaken for blockers):
- **Tesseract `--disable-jbig` rebuild** — OPTIONAL future purity only; libjbig is *kept + compliant*, so it does
  not gate the flip. (632 also removed the vendored `third_party/llama.cpp` source tree — a repo-size/provenance
  cleanup, already merged; production ships the pinned upstream prebuilt.)
- **CRA obligations** — bind only the future *paid* tier (manufacturer); the non-monetised free launch does not trip
  the 11 Sep 2026 reporting clock, so it does **not** gate this cutover.

### From 633 (launch content)
- **README + NON-GOALS root promotion** — promote the finalized drafts (`positioning/readme-draft.md`,
  `go-to-market/non-goals-draft.md`) to the repo **root**; resolve the remaining founder placeholders (demo GIF,
  badge URLs, final slug). *Staged, not landed — by founder decision.*
- **Mirror the third-party `bartowski` chat-model GGUF** onto the releases repo (the first-run SPOF); then update
  the `model-registry.v2.json` URL — the existing `everyDownloadUrlResolvesFromPublicHost()` test validates it.
  *(→ Phase-0 decision #3, 2026-06-25: host on the founder's **own HuggingFace** repo, NOT `justsearch-releases` —
  GitHub release assets cap ~2 GB and the Q4 GGUF is ~5–6 GB (which is why it sits on a third-party HF today).
  **Reclassified announce-prep, NOT flip-gated** — it gates new-user first-run, which gates the *announce*, not
  the public-but-quiet flip. And the public-host test only checks the host ∈ {github.com, huggingface.co}, so it
  does **not** enforce that the mirror happened — honor-system, per finding B.3.)*
- **Public-CI activation of two 633 guards** — `scripts/ci/check-readme-benchmark-numbers.mjs` (already wired into
  `public-ci.yml`; auto-targets the root README once promoted) and the methodology-table projection
  (`gen-public-benchmark`); both begin guarding the root README at the flip.
- **Repo presentation (#7, founder)** — GitHub description, topics, social-preview image.

### Cross-cutting (634's own scope, restated as gates on the above)
- Final **gitleaks** safety pass on the snapshot **immediately before** push (scope #1) — load-bearing; nothing
  public until green.
- The **multi-agent cut-line** (scope #4) — drain WIP, all sessions move at one clean line.
  **Definition-of-ready (checkable, so the doc's biggest risk isn't left as prose):** (a) every worktree
  branch is merged-or-abandoned and `git worktree list` shows none mid-flight; (b) no agent session holds
  private `main` after a recorded cut time T; (c) the dev-stack lease is released. **Partial dry-run:** the
  cut-line itself can't be rehearsed, but **scope #5's local-env rewire CAN be — against the existing empty
  `eliasjustus/justsearch-launch` staging repo** (mount the sidecar gitignored, re-prepare one worktree,
  re-point dev-stack/MCP, install the commit guards) before the real flip, so only the irreducible
  all-sessions-move step is untested on the day. Full mechanics in the private cutover-runbook.
- **Local env rewire** (scope #5) — sidecar mounted gitignored; worktrees/dev-stack/MCP re-pointed; commit guards.
- **Final dangling-reference sweep** `[agent]` — 631 G4's dead-ref sweep ran *before* the late sidecar moves;
  re-check that no published artifact strands a now-private doc (e.g. `release.v1.json`'s `tempdoc`-provenance
  field). Cheap; run on the snapshot. (`624` was deliberately *un-moved* because `scripts/jseval/**` cites it —
  confirm the same for any other moved doc.)

## Investigation notes — 2026-06-24 (takeover verification pass)

Read 634 + the three gating tempdocs (631/632/633) + the cutover-package + `go-public-readiness.md`, then
**verified the registry's claims against `main` (HEAD `8b5d4a10d`, newer than every 631/632/633 merge) and the
live GitHub repos**. The registry is a *projection* of the source tempdocs' deferred items (its own stated
discipline); a projection drifts, so the point of this pass was to re-sync it against shipped reality. No scope
items rewritten — findings + recommendations only.

### A. Verified accurate (registry holds)
- **Repo identity (`gh`-confirmed):** `eliasjustus/justsearch-launch` is **PRIVATE, empty, created 2026-06-23**;
  `eliasjustus/JustSearch` (this checkout's `origin`) is **PRIVATE, non-empty**; and — not previously stamped —
  **`eliasjustus/justsearch-releases` is already PUBLIC**, so the external model-blob hosting (scope #2c) works
  today. The case-insensitive-name cutover sequence (archive old `JustSearch` → rename `justsearch-launch` →
  populate → flip) is sound.
- **Entry-precondition commits exist:** `ded1779ca` (633 landing) and `09d248a93` (the 637 session-obs note that
  flagged the 632 gate-reds) both resolve on `main`.
- **`gitleaks` still absent locally** (`command -v gitleaks` → not found) — scope #1's "install gitleaks" is
  still real, not stale.
- **Publish-machinery template is as described:** `scripts/codegen/gen-agent-hooks-wiring.mjs` has both
  `--emit-public-template` and `PUBLIC_EXCLUDED_HOOKS` (script lines 50/146); the emitted
  `cutover-package/public-settings.json` is guards-only (no `permissions`, no `env`) with the 4 analytics hooks
  dropped. Matches 631's hand-off.
- **bartowski SPOF is concrete:** `modules/ui/src/main/resources/ai/model-registry.v2.json:235,243` host the chat
  GGUF + mmproj on `huggingface.co/bartowski/Qwen_Qwen3.5-9B-GGUF/...`; *every other* blob is on
  `github.com/eliasjustus/justsearch-releases`. So the chat model is the lone third-party-host SPOF, as claimed.
- **Fresh-history is self-completing for secret-scanning:** a snapshot repo is a single commit = its entire
  history, so `gitleaks` on the snapshot **is** the complete-history scan (no pre-squash commits to miss). The
  Option-C "no import" design makes scope #1 both sufficient and complete — a genuine safety property, worth
  stating as such. (Web-confirmed: gitleaks only scans surviving history; with no imported history there is
  nothing it can miss.)

### B. Projection drift — re-sync these three registry lines
1. **The "★ resolve the pre-existing 632 gate-reds" item is stale in its specifics.** On current `main` the four
   files 632 named (`HeadlessApp`/`EnvRegistry`/`HeadAssembly`/`KnowledgeServer`) are **re-pinned/resolved** (the
   `637-followup-pin-realign-headassembly` + `638-merge-realign-main` class-size changesets). The class-size gate
   is **still red**, but for a *different* cause: one genuine `class-size/silent-growth`
   (`ResolvedConfigBuilder.java` +6 LOC over its pin, no changeset) plus five non-fatal-in-spirit
   `rebalance-available`/`row-removed` loose-pin housekeeping findings. So the item is valid in spirit but should
   be reframed: the precondition is **not** "fix the 632 four" — it is "the full gate kernel is green on the clean
   snapshot commit at cut time," because class-size on a manual-CI repo drifts continuously and the red set is a
   moving target.
2. **The green-tree precondition is materially under-scoped.** Running the full 37-gate kernel
   (`node scripts/governance/run.mjs --mode gate`) right now shows **9 gates red / 143 findings**: `test-efficacy`,
   `exception-count`, `class-size`, `ui-bundle`, `ts-any`, `clone`, `consumer-drift`, `execution-surface`,
   `observed-happening`. **CAVEAT:** this is the *dirty* main checkout (~25 in-flight modified files from other
   agents' WIP), which inflates the count — many should clear once WIP is committed/drained at the cut-line. But
   it shows the precondition is **multi-gate**, not the single class-size red the registry implies. Recommend
   scope #2/#7 require a full `run.mjs --mode gate` (+ `./gradlew.bat build` + `test`) **green on the actual clean
   snapshot commit**, captured at cut time T — the operational restatement of the doc's own line "the
   fresh-history snapshot can only capture a clean, committed, green tree."
3. **The bartowski public-host-test claim overstates the guard.** `everyDownloadUrlResolvesFromPublicHost()`
   allowlists `{github.com, huggingface.co}` (`ModelRegistryLoaderTest.java:143`), so the bartowski URL **passes
   today and would pass before *and* after the mirror**. The test guards against *private/localhost* hosts (already
   satisfied); it does **not** enforce that the mirror happened. So mirroring the GGUF is an **honor-system
   robustness item, not test-gated** — reword the "the existing test validates it" line accordingly.

### C. Critical-analysis risks to weigh (assumptions questioned)
- **★ The self-hosted GPU-lane runner is a high-severity coupling with scope #5's sidecar mount — the second
  under-specified risk beside the cut-line.** Current GitHub + security-industry guidance (GitHub Docs *secure
  use*; Wiz / Sysdig; the Nov-2025 Shai-Hulud worm that weaponised self-hosted runners) is blunt: self-hosted
  runners should *almost never* serve a **public** repo — fork-PR approval **reduces but does not remove** the
  RCE risk (an approved-but-malicious PR, or a compromised contributor, runs code on the runner). Scope #5 mounts
  the **private sidecar** (strategy, founder PII, grant drafts) as a gitignored folder **on the same dev machine**
  the GPU lane (scope #6) would run on — so a single fork-PR RCE could **exfiltrate exactly what the whole
  cutover exists to keep private.** Recommend treating the runner posture as a *security decision*, not a settings
  toggle: (i) run the GPU lane only on **push-to-`main` / maintainer-dispatch, not on fork PRs** (sidesteps
  untrusted-code-on-runner entirely), and/or (ii) an **ephemeral, isolated runner on a dedicated machine/VM with
  no sidecar mounted** — never the founder's dev box. 634/631 currently scope this as "register the self-hosted
  runner + fork-PR approval," which under-states the exposure.
- **The bartowski mirror straddles the flip-vs-announce boundary 634 itself draws.** 634 says first-run /
  product-readiness robustness gates the *announce*, not the *flip*. The bartowski SPOF *is* a first-run
  robustness item (third-party host could vanish). It needs the public releases repo (post-flip) but does **not**
  block the public-but-unannounced flip. Consider reclassifying it as **post-flip / pre-announce**, consistent
  with the boundary — it is listed flip-gated only because it "can't exist pre-flip," which conflates "needs the
  public repo to exist" with "blocks the flip."
- **License-runbook drift (cutover-execution risk; logged, not fixed).** The founder-facing
  `cutover-package/license-runbook.md` §2/§5 still frames the Tesseract copyleft risk as cairo/pango (LGPL-2.1) +
  libgcc/libstdc++ (GPLv3) and **never mentions libjbig** (lines 25, 40-41). 632's *final* decision is **KEEP
  `libjbig-0.dll` (GPL-2.0)** with `NOTICE-JBIGKIT.txt` + `LICENSE-GPL-2.0.txt` staged into `native-bin/tesseract/`
  (§1/§3). 634's registry is correct, but it sends the founder to a **stale runbook** for the scope-#6
  packaging-validation step — the runbook tells them to look for the wrong artifacts. `go-public-readiness.md`
  §License-paperwork item 4 carries the same stale framing. Fix both before the founder executes scope #6.
- **Cut-line definition-of-ready is missing a "main checkout is clean" criterion.** Scope #4's DoR checks
  worktrees-drained, no-session-holds-`main`, lease-released — but not "no uncommitted modifications in the main
  checkout." The snapshot (scope #2) is cut **from `main`**, which routinely carries in-flight WIP (right now
  ~25 modified files). Add a 4th checkable criterion: `git status` clean on `main` at cut time T, so the snapshot
  captures a committed (and gate-green per B.2) tree.

### D. No new tempdoc
Every finding is an update to 634's own registry/scope plus one logged drift in two sibling business docs
(`license-runbook.md`, `go-public-readiness.md`). Recorded here per the projection-not-fork discipline; nothing
warranted a separate tempdoc or an observations.md note beyond what is captured above.

## Theorization & open directions — 2026-06-24 (exploratory; not design yet)

Thinking broadly about framings, alternative directions, hidden assumptions, and the deeper shape of this
cutover **before** the design is settled. Everything below is "worth considering," not a decision.

### T1. Reframe: the cutover is a staged rollout with a rollback budget — and "unannounced ≠ unindexed"
634 already half-uses this: the **public-but-unannounced window is a canary** (tiny blast radius, fix the first
impression before strangers arrive). Pushing that frame harder yields a sharper safety model — *reversibility is
per-failure-class, not uniform*:
- **Reversible during the canary:** a broken build, a bad README, a missing badge. The unannounced window is
  exactly the grace period for these. Cheap to fix; the canary is the right tool.
- **IRREVERSIBLE the instant of `git push`, canary or not:** a leaked secret or a stray PII/strategy line.
  **Hidden assumption to kill: "unannounced" gives a grace period for secrets. It does not.** GitHub's public
  event firehose (githubarchive.org / the events API, plus secret-harvesting and training scrapers that watch new
  public repos) ingests a public push within *minutes*, independent of whether you announced. So **the gitleaks
  pass (scope #1) is the *sole* line of defense for secrets/PII — the canary backstops the reversible classes
  only.** This argues for treating scope #1 as a hard pre-push gate with no "we'll catch it while it's quiet"
  fallback, and for the content-sweep in T3b.
- **Useful idea this unlocks — a reversible integrated dry-run.** 631 *deferred* the "green-under-public-CI-
  conditions" integrated proof to here because it "can't exist until a public repo does." But it can — in a
  **disposable, non-discoverable throwaway public repo** (NOT `justsearch-launch`, whose public event-history
  must stay clean since it becomes the real `justsearch`): push the snapshot, let both CI lanes + gitleaks +
  `gitleaks-action` run, clone-as-a-stranger, then delete. This converts the irreversible integrated-proof into
  a reversible one and retires 631's largest deferred unknown *before* the real name is burned. (Subject to the
  T1 caveat: even a throwaway public push is firehose-harvested — so it must itself be gitleaks-clean first; it
  de-risks *CI/build/UX*, not secret-safety.)

### T2. Reframe the cut-line's true invariant: *freeze*, not *simultaneous move*
634 calls scope #4 the deepest operational risk and models it as stop-the-world ("all sessions move at one clean
line… you cannot half-transition"). But the property that actually prevents divergence is weaker and far more
achievable: **private-`main` accepts no writes after cut-time T, and the snapshot == private-`main`@T.** Once
private is *frozen* (branch-locked / archived — scope #3 already freezes it), it is irrelevant whether agent A
re-points at T and agent B at T+2h: neither can commit to private, so there is no second head to diverge. The
agent migration becomes **eventually-consistent**, not atomic. Consequences worth weighing:
- The only WIP that **must** drain *before* T is WIP that needs *private* context (touches the sidecar). All
  other in-flight WIP is just an *uncommitted diff* — it re-applies onto the fresh public root and the sidecar
  pre-commit guard stops any sidecar-path leak. So "drain ALL WIP" may be over-strict; "drain sidecar-touching
  WIP; carry the rest over" is enough.
- This re-sequences cleanly: **freeze private (T) → snapshot from private@T → create public → re-point lazily.**
  The freeze is the single atomic primitive; everything after it is recoverable. It unifies scope #3's "freeze
  the old repo" with scope #4's cut-line around one act instead of a synchronized fleet maneuver.
- Net: the "can't be dry-run" risk shrinks to "did we freeze before we snapshotted, and does the snapshot equal
  the frozen tip" — both *checkable*, which is the T5 preflight shape.

### T3. Hidden assumptions worth surfacing
- **(a) Fresh git history ≠ no historical record.** Option C publishes `docs/tempdocs/` — a 600-doc, append-only
  *narrative* of the private 6,200-commit past (decisions, rejected paths, postmortems, the NLnet grant, agent
  failures). So "fresh history" launders the *git log* but ships a **richer** history than the log it replaced.
  This is 631's attacker's-roadmap concern generalized: the showcase value and the exposure are the same artifact.
- **(b) Path-based sidecar exclusion ≠ content-based** — *corrected 2026-06-25 (the content sweep is already
  done).* The guard + dangling-ref sweep catch *paths/links* into `docs/business/` + `docs/market-analysis/`; the
  *content*-level check (published prose that paraphrases sidecar-grade strategy/PII) is a different concern — but
  **631 item 1's candor/sensitivity pass already executed it corpus-wide** (PII/secrets/machine-paths auto-fixed;
  whole-doc strategy/funding moved to the sidecar; third-party commentary flagged → founder editorial;
  self-critique/stale-numbers neutralized by the README "dated working-notes, not product claims" framing —
  verdict *"nothing unsafe remains"*, `631:334-340`). So this is **not** an open gap. Two residuals only: **(i)** a
  **delta re-scan** of tempdocs added/changed *since* that 2026-06-23 pass (635-646 + any new content in 631-634
  themselves — the corpus grew ~12-15 docs); **(ii)** the **attacker's-roadmap / exploitability lens** (631 #3 /
  line 145) — a genuinely *different* check ("does publishing the security-design docs help an attacker?"), still
  an open founder decision. The 624 grant redaction is already a tracked founder item. *Supersedes my Phase-1
  "Call 1" framing of a fresh broad content sweep — that would duplicate 631 item 1.*
- **(c) "Public CI green" ≠ "contributor-safe agent experience."** Develop-in-public means a *stranger's* agent
  runs the published `.claude/` machinery. A distinct verification tier from CI: **clone as a stranger, open in
  Claude Code, see what fires** (do the hooks ENOENT on absent `settings.local.json`? does a skill assume the
  founder's worktree fleet? does CLAUDE.md read as contributor-grade post-G8-slim?). Scope #7's trusted-dev
  quickstart should explicitly include this "agent-experience" smoke, not just "build + MCP setup."
- **(d) The sidecar guard assumes all future private content lives under two hardcoded prefixes.** Day-2 risk:
  new private content created *outside* `docs/business/` / `docs/market-analysis/` (a root strategy note, a
  `TODO-private.md`) is not caught. A deny-by-default *private prefix* convention + a staged-content scan is a
  more durable steady-state than two literal prefixes.

### T4. Question the Option-C bet (lightly — the choice is made, but name the cost)
Radical transparency's under-weighed cost: publishing the machinery makes it a **public API + support surface +
scrutiny target**, and the "here's our rigorous AI-agent dev system" showcase only lands if the machinery is
*visibly coherent and green* — yet the kernel shows 9 red gates today (finding B.2). A red showcase undercuts the
claim, so Option C *raises* the cutover's quality bar rather than just adding publish steps. Two durable framings
fall out, neither requiring a redesign:
- **Option C is really "publish + de-emphasize."** The value is *optionality* (the guardrails are there if you
  want them); the risk is the meta-layer becoming the *story* instead of the product. 631's signpost-READMEs +
  present-but-opt-in wiring already enact this — the cutover should keep the *default* contributor path about the
  product, with the machinery one click down. Make the bet explicit so it isn't accidentally inverted.
- **Principle: publish the mechanism, keep the data.** The hooks/gates/telemetry *code* goes public; the
  accumulated agent-telemetry + decision history (the actual moat) stays on the founder's machine (`tmp/`,
  gitignored). For a transparency play the defensible asset is the accrued data/judgment, not the code — and this
  cutover's split already honors that. Worth stating as the invariant behind "what stays private."

### T5. The deeper shape: a cutover *preflight oracle* (verify-don't-guess, applied to a one-shot)
634 is itself an instance of the codebase's signature pattern — **canonical-source → projection → drift-gate**
(SearchTrace register, 632 attribution-as-projection, 633 benchmark projection). Its inbound registry is an
*explicitly declared projection* of 631/632/633's deferred items — but a **hand-maintained, un-gated** one, and it
*drifted* (findings B.1–B.3). The system's own medicine points at the natural shape: a single runnable
**`cutover-preflight`** that composes the oracles that already exist into one go/no-go, mirroring the dev-stack's
existing `preflight` and the governance `--preflight` mode:
> gitleaks clean on the snapshot · all 37 gates green on the *clean snapshot commit* (not the dirty checkout) ·
> include/exclude linter (no sidecar path staged; the full machinery dependency closure present) ·
> `everyDownloadUrlResolvesFromPublicHost` green · dangling-ref sweep clean · `git worktree list` empty ·
> `git status` clean on `main` · private frozen ∧ snapshot==frozen-tip (T2).
This turns 634's prose checklist + moving-target gate-reds into one legible green/red, which matters *because the
event is irreversible*. Tradeoff / YAGNI tension: it is a one-shot, so a full *gate* (CI-wired, drift-checked
forever) is over-engineering — but **composing the existing checks into one preflight script is cheap and
high-value**, precisely the "definition-of-ready as an oracle, not a paragraph" move the doc already started for
the cut-line. Candidate broader invariant (for later, not now): *an irreversible migration earns a preflight
oracle the way a reversible operation earns a test.*

### T6. Smaller sequencing traps to keep in view
- **Branch protection must be applied *after* the initial snapshot push** (or via a one-time admin bypass) — a
  protected `main` that requires PRs will reject the very first direct push that seeds the repo.
- **Register the self-hosted runner with its restrictive fork-PR settings *before* the repo is discoverable**, not
  after — there must be no window where a fork PR can reach an unguarded runner (compounds the §C self-hosted
  risk; arguably moot if the GPU lane never runs on fork PRs per that recommendation).
- **Name swap leaves GitHub redirects:** archiving/renaming the old private `JustSearch` creates a redirect from
  its old URL; harmless (private, auth-gated) but worth knowing it exists when the new public `justsearch` takes
  the slug.

## How to proceed — 2026-06-24 (proposed sequencing; `main`-green **deferred** per instruction)

**Organizing principle (falls out of T1/T2/T5): shrink the irreducible irreversible core.** 634 is mostly
`[founder]` + irreversible; the agent cannot do the flip and should not try to. The agent's leverage is to move
*every reversible or rehearsable de-risk* **out of** the flip window and **into** prep, then gate the flip behind
one preflight — so the founder's irreversible act collapses to "freeze → snapshot → push," with everything else
already proven. **Making `main` green is deferred:** it is *not* a prerequisite for any prep below; it is
**absorbed into the preflight (Phase 2)** as the last item the founder clears immediately before the flip. The
deferral therefore subtracts nothing from Phases 0–3.

- **Phase 0 — resolve the framing-forks first (don't design around an unmade decision).** Three open directions
  are *forks that change downstream scope*, so settling them gates everything else: the **self-hosted-runner
  posture** (§C — the runner↔sidecar security coupling; the biggest), the **cut-line model** (stop-the-world vs
  *freeze-first*, T2), and **bartowski mirror = flip-gated vs announce-gated** (T-B). Agent-actionable now = turn
  these into a crisp founder-decision surface (either/or + a recommendation each), **not** a design. This is the
  honest next step — building the preflight or rewriting scope before these are settled would be premature.
- **Phase 1 — reversible agent prep (no founder, no flip, no `main`-green needed).** The content-level
  sidecar-leak sweep (T3b) + the dangling-ref sweep, run against a *simulated* snapshot (apply the include/exclude
  to a scratch clone) — this de-risks the one class the canary can't undo (irreversible content/PII exposure, T1).
  Plus the include/exclude **linter** (no sidecar path staged; full machinery dependency closure present) and the
  two doc-drift fixes (the libjbig runbook + readiness, finding C).
- **Phase 2 — the preflight oracle (T5) absorbs the deferral.** Compose the existing oracles + the Phase-1
  linters into one `cutover-preflight` go/no-go. The deferral lands cleanly here: the oracle simply *reports* the
  red gates; the founder clears them at flip-time. So we don't fix gates now — we ensure the oracle will catch
  them then.
- **Phase 3 — rehearsal (founder + agent, reversible).** Scope #5's local-env rewire against `justsearch-launch`;
  T1's integrated dry-run in a *throwaway* public repo (CI/build/UX + the contributor-agent smoke, T3c). Retires
  631's deferred "green-under-public-conditions" integrated proof *reversibly*.
- **Phase 4 — the irreducible irreversible core (founder, last, smallest possible window).** Real freeze, real
  snapshot push under the real name, name swap. Gated on: preflight green (incl. the now-un-deferred `main`-green
  + gitleaks clean) and the Phase-0 decisions resolved.

**Immediate next action is Phase 0 (surface the three forks for the founder), not building anything** — and given
the standing "don't start implementation/design yet," even Phase 0 stops at *framing the decisions*, not designing
their outcomes.

### Phase-0 decisions — RESOLVED 2026-06-25 (founder)
All four scope-shaping forks are settled. (Decisions only — the scope sections above are **not** rewritten yet;
that is design work, deferred.)
1. **Runner posture → 1a.** The GPU lane runs **only on push-to-`main` / maintainer-dispatch, never on fork
   PRs.** Resolves the §C runner↔sidecar coupling: the self-hosted runner only ever executes *trusted* (founder)
   code, so a stranger's PR can't reach the box that has the sidecar mounted. Accepted cost: fork PRs get no
   automatic GPU-test coverage (founder verifies those manually). Residual discipline: review the diff before
   ever running a contributor branch locally on the sidecar-mounted box. *Refines scope #6 + the §C risk.*
2. **Cut-line → simple / freeze-first (T2).** The founder stops all agent sessions in-band; once nothing writes
   to private `main`, snapshot the clean, frozen tree — no synchronized "all move at one line" maneuver. Ordering
   invariant: snapshot **after** the last commit lands, with `git status` clean. *Collapses scope #4's "deepest
   operational risk" into a checkable freeze + clean-tree condition.*
3. **Bartowski chat-model → own HuggingFace + announce-prep.** Host the Qwen GGUF on the founder's **own HF**
   repo — GitHub release assets cap individual files at ~2 GB and the Q4 GGUF is ~5–6 GB, which is *why* it sits
   on a third-party HF today and is **not** already on `justsearch-releases`. Reclassified **off the flip critical
   path**: it gates the *announce* (new-user first-run), not the public-but-quiet flip (trusted testers use the
   existing bartowski link). *Moves the "From 633" bartowski item out of cutover-gated → announce-prep.*
4. **Slug → `justsearch` (confirmed); rename deferred to the flip.** The name is locked now. The **rename action
   stays in Phase 4** — `justsearch-launch` → `justsearch` cannot run until the private `eliasjustus/JustSearch`
   releases the (case-insensitive) name at archive/rename time. Name = decided; rename = executed at cutover, as
   already planned.

**Phase 0 closed.** Per the standing "don't design/implement yet," work pauses here until the next phase is
directed.

### Public version-line reset — DECIDED + APPLIED 2026-06-25 (founder)
**Reset the SemVer line to `0.1.0` for the public line; identity unchanged.** The private dev line ran
`2.0.0-alpha.N` (last `alpha.28`); carrying that into the fresh-history public snapshot reads oddly (a "2.0"
with 28 unseen alphas + a phantom 1.0) and leads with the alarming "alpha" word to a stranger who finds an
unannounced build. `0.1.0` is honest pre-1.0 SemVer (early public, evolving, no stability commitment) and
sheds the "alpha" terminology; a public `1.0.0` becomes the announce milestone. **Scope = version string
only** — the `io.justsearch.shell` bundle id, `%LOCALAPPDATA%\JustSearch` install dir, the `io.justsearch`
namespace, and the `justsearch` slug (Phase-0 #4) are all **unchanged** (the identity-bearing layer, whose
only cheap-to-change window is pre-install, is deliberately left as-is).
Applied on `main` now (`gradle.properties` → `sync-version.ps1 -RequireReleaseSemver` propagates to
`tauri.conf.json` / `package.json` / `Cargo.toml`, + hand-synced `Cargo.lock`) so the soft-launch installer
already wears it and the snapshot inherits it. Note: a bare `v0.1.0` tag is auto-flagged a *normal*
(non-prerelease) GitHub Release by `build-installer.yml` (which keys "prerelease" off `alpha/beta/rc`) —
coherent for an early `0.x`; tweak only if an explicit prerelease flag is wanted.

## Adjacent-tempdoc survey — 2026-06-25 (is any relevant tempdoc unread?)

Surveyed the go-public-adjacent tempdocs *outside* 634's dependency set (631/632/633) for flip-gating items they
miss. **Result: none add an open flip-gate.** The closest two are adjacencies worth naming.
- **249** (open-source-investigation) — **NONE**: it studies *peer* OSS search/RAG projects to learn patterns,
  unrelated to publishing *this* repo (`249:20-21`). The "sidecar" hits are Docling doc-processing sidecars, not
  our business-docs sidecar.
- **374** (packaging/distribution) — **captured.** Confirms signing gates the *announce*, not the flip: G4 = the
  OV Authenticode cert (~$300–500/yr; days-to-weeks CA identity verification) "revisit when preparing for public
  release" (`374:47`). Release machinery (G3 `v*`-tag → GitHub Release; G15 chat/embed GGUF is BYO, not bundled)
  is already mirrored by 631/633.
- **428** (repo-entrypoint-security-automation) — confirms **scope #6 is net-new.** 428 shipped CodeQL +
  Dependabot + `workflow_dispatch` normalization, but **no** branch-protection / secret-scanning / fork-PR-approval
  / self-hosted-GPU-runner — those are 634-owned, not inherited (`428:24,117-126`).
- **★ 617** (in-place auto-update) — the one **net-new adjacency.** The app's update **feed points at a public
  URL whose location is undefined** (`/releases/latest` vs the `justsearch-releases` repo, `617:196-198`); its D4
  = silent-apply **gated on the same deferred signing** (`617:167-169`), aligning with 634. So a *second*
  public-facing dependency exists beyond the code repo.
- **409** (releases-repo audit) — **HIGH topically** (the already-public distribution surface) but its specific
  defects are **verified resolved on the live repo today** (`gh`, 2026-06-25): the NER license now reads
  **AFL-3.0** (was the MIT mislabel, D1), the release body carries a complete license table + the SPLADE §4(b)
  modification notice + a linked `THIRD_PARTY_NOTICES.txt` (D2), and the release titles are clean (the "tempdoc
  343" leak, 409-D4, is gone). So **not a live open item** — 632's attribution work has propagated here.

**Durable framing this surfaces — going public touches THREE public surfaces, not one:**
1. **the code repo** — 634's entire focus;
2. **the `justsearch-releases` repo** — *already public*; hosts the installer + ONNX/SPLADE models + CUDA/llama.cpp
   binaries; currently license/ref-clean (409 verified), but a standing public surface whose NOTICE/titles must
   stay clean *alongside* the code repo, and whose tags are **immutable** (`409:81-88`) — so the bartowski model
   re-host (Phase-0 decision #3) lands here and needs a fresh tag/release, not an in-place swap;
3. **the app's update feed** (617) — a public URL the shipped binary may poll; feed location still undefined.
634 is correctly scoped to surface #1; #2 and #3 are adjacent, currently fine, and recorded so they aren't
forgotten at the flip. *No new dependency to add to the gate set — 631/632/633 remain the complete flip-gates.*

## Remaining-work ledger — 2026-06-25 (what's left, by owner)

Post-investigation synthesis. The heavy lifting is **done** (631/632/633 merged; the corpus-wide content/candor
sweep baseline; the releases-repo verified clean; the four Phase-0 decisions closed; no missing dependency). What
remains splits sharply by owner — and the **agent-actionable prep is now thin**; the bulk is founder execution +
flip-time checks that can only run on the real snapshot. **634 is prep-saturated, not complete.**

- **Agent-actionable now (small):** (a) the include/exclude snapshot **linter — *spec only*** (build deferred to
  the Phase-2 preflight); (b) two **doc-drift fixes** — the libjbig framing in `license-runbook.md` +
  `go-public-readiness.md`; (c) author the **`cutover-preflight` spec** (T5) — compose the existing oracles into
  one go/no-go. *That is the whole of what can be done without the founder or the snapshot.*
- **Flip-time checks (agent-run, but only on the *actual snapshot* — early runs go stale):** `gitleaks` on the
  snapshot (scope #1); the **content/candor delta-scan** since 631's 2026-06-23 baseline (the relocated "Call 1");
  the **dangling-ref sweep** on the snapshot (scope #7 cross-cutting); the include/exclude linter *run*; the
  **integrated green-under-public-CI run** in a throwaway public repo (the reversible dry-run, Phase 3); and
  **`main` green** (deferred per instruction — absorbed here as the last preflight item before the flip).
- **Founder decisions / legal still open (registered; not agent work):** the 624 grant redaction (re-scope at
  cutover); the attacker's-roadmap / exploitability lens; the 8 third-party-commentary passages (editorial); the
  DCO GitHub-App install; the NVIDIA notice-retention check + the libjbig packaging-validation at a real build;
  repo presentation (description / topics / social-preview). *(Slug, runner-posture, cut-line model, and bartowski
  are now DECIDED — Phase 0.)*
- **The irreducible irreversible core (founder, Phase 4):** freeze private (stop all agents) → produce + push the
  snapshot → create public repo + sidecar + archive → name swap → repo settings (branch protection *after* the
  first push; runner registered restricted-to-push/maintainer per 1a) → local-env rewire (sidecar mount,
  worktrees, dev-stack/MCP, commit guards) → public-but-unannounced staging + the trusted-dev quickstart
  (incl. the contributor-agent smoke, T3c).

**Net:** 634 is **not** complete — the Done criteria (public-but-unannounced, both CI lanes green, trusted-dev
quickstart + MCP pass, dev-in-public with the sidecar mounted, old repo archived) are all unmet, and substantial
founder execution remains. But it is **prep-saturated**: the agent's remaining slice is ~3 small items plus the
flip-time guards; everything else is the founder's irreversible act, gated behind the preflight. That is the
intended end-state of "shrink the irreversible core" — what's left *is* the core.

## Phase-1 execution — 2026-06-25 (agent, autonomous)

The remaining founder decisions are now closed and the agent-actionable prep is done.

### Remaining founder decisions — RESOLVED 2026-06-25
- **Attacker's-roadmap / exploitability lens → ACCEPT** (publish the candid security-design tempdocs as a
  transparency tradeoff; no targeted pre-flip pass). *Closes the 631 #3 open decision + the §C.a "treat as its
  own class" residual.*
- **Third-party-commentary (the ~7–8 flagged passages, incl. 424's vendor-CVE + 83–92% pricing) → publish as-is.**
  *Closes the 631 item-1 flag-only editorial call.*
- **Analytics-scripts publish scope (C1) → publish ALL of `scripts/agent-analytics/`** (incl. the telemetry
  tooling), consistent with Option C. *Founder veto reserved if any telemetry script embeds something private —
  none found by 631's scan.*
- **624 grant redaction → DONE (agent first-pass, founder review).** Generalized the two residual grant-integrity
  lines that still named the live grant application (`624:202`, `624:204-205` — "live NLnet application draft" /
  "the funding draft" → "internal positioning/funding drafts … private business sidecar"). The technical
  "92% is an identity-less fork" thesis is **kept** (it is the doc's point and shipped `scripts/jseval/**` cites
  it). *Re-confirm at cutover against 624's then-current state (634 registry already requires this).*
- **Repo presentation (#5) → founder owns it.** **Sidecar form → a private *local* git repo** (history/backup),
  mounted as a gitignored folder in the dev checkout (scope #5). *Nuance: "local-only" = no off-machine backup;
  consider a private remote.*

### Doc-drift fixes — DONE 2026-06-25
- `cutover-package/license-runbook.md` §2 + §5 and `legal/go-public-readiness.md` §License-paperwork #4: replaced
  the stale "cairo/pango is the copyleft risk" framing with **632's final libjbig disposition** — `libjbig-0.dll`
  (GPL-2.0) is the one strong-copyleft item, KEPT + compliant via `NOTICE-JBIGKIT.txt` + `LICENSE-GPL-2.0.txt`
  staged into `native-bin/tesseract/`; cairo/pango (LGPL-2.1) + libgcc/libstdc++ (GPLv3+exception) are
  weak/exception; `--disable-jbig` is optional. The founder-facing runbook now points at the right compliance
  artifacts for the scope-#6 packaging check. *(Closes Investigation finding C.)*

### Spec — include/exclude snapshot linter (build deferred to the preflight)
A checkable that, given a candidate snapshot tree, fails on a wrong include/exclude. Checks:
1. **No sidecar path staged** — nothing under `docs/business/` or `docs/market-analysis/`.
2. **No local-runtime bits** — `.claude/settings.local.json`, `.mcp.json`, `tmp/` (incl. agent-telemetry data),
   `.claude/worktrees/` absent.
3. **No model LFS blobs** — `models/onnx/*`, `models/splade/*` `*.onnx` absent; the small config/tokenizer JSON
   present.
4. **Machinery dependency closure PRESENT** — `scripts/agent-analytics/` *incl. `lib/`*, `scripts/{governance,ci,
   codegen}/`, the 36 enforcers under `scripts/governance/gates/`, `.claude/{rules,skills,CLAUDE.md}`,
   `governance/agent-hooks.v1.json` (631 C1 — guards against the stale "hooks/-only" list).
5. **Public `settings.json` swapped** — `.claude/settings.json` == the guards-only template (no `permissions`/`env`;
   the 4 analytics hooks excluded); `settings.local.json` untracked + a `settings.local.json.example` shipped.

### Spec — `cutover-preflight` (one go/no-go; composes existing oracles; T5)
Runs against the **clean snapshot commit**; green = safe to flip. Composition:
- `gitleaks detect` clean on the snapshot (scope #1).
- Content/candor **delta-scan** since 631's 2026-06-23 baseline — clean or founder-signed (the relocated Call 1).
- The include/exclude linter (above) — green.
- `everyDownloadUrlResolvesFromPublicHost` — green.
- Dangling-ref sweep — clean (scope #7 cross-cutting).
- **Full discipline-gate kernel** green — `node scripts/governance/run.mjs --mode gate` (this is where the deferred
  "`main` green" is cleared, immediately before the flip).
- `./gradlew.bat build` + `test` — green.
- `git worktree list` empty + `git status` clean on `main` (the freeze + clean-tree invariant, T2 / finding D).
Returns one green/red, mirroring the dev-stack `preflight`. **Build deferred to Phase 2** (spec-only here, per the
hold); it is the natural consolidation point for everything in the flip-time-checks bucket above.

## Phase-2 build — 2026-06-25 (agent, autonomous; both tools built + tested)

Built the two specs above as standalone tools under a new **`scripts/cutover/`** dir (a dedicated one-shot home,
*not* `scripts/ci/` — these are not continuous gates; per §T5 a CI-wired gate for a one-shot flip is
over-engineering, so they are run by hand at the cutover and published under Option C as transparency). All
Node-stdlib, matching the `scripts/ci/check-*.mjs` convention (exported testable core + a `.test.mjs`).

- **`scripts/cutover/check-snapshot-includes.mjs`** (+ `.test.mjs`, **26 checks green**) — the include/exclude
  linter. EXCLUDE (sidecar / local-runtime / model `*.onnx`) + CLOSURE (the 631-C1 machinery dependency set) +
  SETTINGS (guards-only template). `--source` mode prints the strip-list and enforces CLOSURE only; strict mode
  enforces all three. **Live-verified:** `--source` against the repo → green, closure 11/11 present, and it
  enumerated exactly the **9 model `.onnx` blobs** + 6 sidecar/runtime paths to strip (matches the runbook's "9
  LFS blobs"); strict mode against the live private tree → exits 1 (the sidecar is present, as it should be
  pre-snapshot).
- **`scripts/cutover/preflight.mjs`** — the go/no-go board composing the linter + `gitleaks` + the discipline-gate
  kernel (`run.mjs --mode gate`, where the deferred `main`-green clears) + the public-host test + the dangling-ref
  sweep + build/test + the freeze invariant (`git status` clean + no mid-flight worktree). PREP mode = dashboard
  (light checks now; the rest PENDING with the exact command); `--flip --full <snapshotTree>` = the real
  enforcing gate before push. **Live-verified** in PREP mode: linter ✓, gitleaks ⊘ (not installed — flagged as
  required at flip), the heavy checks ⏳ PENDING with commands, and `freeze-clean` correctly reported the dirty
  tree (17 uncommitted changes "must be 0 at freeze"). `scripts/cutover/README.md` documents both.
- **Not CI-wired** (by design): `ci.yml` wires checks individually, not by `*.test.mjs` glob; these stay one-shot.
  The preflight is the Phase-2 consolidation the ledger's flip-time-checks bucket pointed to — the flip now has a
  single legible go/no-go that absorbs the deferred `main`-green as its last gate.

## Phase-3 dry-run — 2026-06-25 (agent, autonomous; LOCAL snapshot, no push)

Produced a **local, reversible** snapshot dry-run (the irreversible-edge of scope #5, without any push/public
repo): `git archive HEAD` → strip the 15 Option-C exclude paths → swap in the guards-only `settings.json` + the
`.gitleaks.toml`, in a scratch dir. Ran the full safety pass. Findings:
- **Snapshot mechanics validated.** The strip removed exactly the 9 model blobs + 6 sidecar/runtime paths; the
  **strict linter PASSES** (closure 11/11, no leaked sidecar/runtime/model path, settings == template). The
  include/exclude is correct.
- **★ `gitleaks` on the snapshot: 57 hits — ALL false positives, but the `.gitleaks.toml` allowlist is
  incomplete.** Confirmed benign by eyeballing the plausibly-real ones: `StoreCipherTest.java` literal test key
  `0123456789abcdef…`; 38 base64 **pagination cursors** (`pit|…` search tokens) in `reports/phase13/paging/*.json`;
  13 jseval smoke fixtures; doc examples; a `release.v1.json` hash. No real secret (matches the readiness audit).
  **But** the `.gitleaks.toml` only allowlists `third_party/ · datasets/ · *.onnx · *tokenizer.json` — *not*
  `reports/` or the jseval smoke fixtures — so the flip-time scan (and `public-ci.yml`'s `gitleaks-action` job)
  would fire 57 false leaks, defeating the config's stated zero-false-positive goal (a real hit must mean a real
  secret). **Actionable fix before the flip:** expand the allowlist (add `reports/.*`, the jseval smoke fixtures,
  and a rule-allowlist for obvious test-key patterns) **OR** add `reports/phase13/` to the snapshot EXCLUDE.
  *Founder judgment:* does `reports/phase13/` eval-iteration data publish at all (allowlist) or get excluded?
- **Snapshot was cut from HEAD**, so it carries the **un-redacted 624** (my redaction is uncommitted) — confirms
  the prep edits must be **committed before** the real snapshot (the freeze-clean invariant, finding D).
- Scratch artifacts under the session scratchpad (`snapshot-dryrun/` + `gl-report.json`); not committed.

**Update (2026-06-25 cont.) — gitleaks allowlist FIXED (57 → 0).** Expanded `cutover-package/gitleaks.toml`:
path-allowlisted `reports/.*` (eval pagination cursors), `scripts/jseval/.*` (smoke fixtures + release hashes),
`.*Test\.(java|ts|tsx)` (test fixtures), plus 3 individually-vetted file FPs (a doc example resumeToken, a tempdoc
title line, an i18n identifier). Re-scan on the snapshot → **"no leaks found", exit 0.** The scan now meets its
zero-false-positive goal (a real hit means a real secret). *(Breadth tradeoff: the `Test`/`jseval` path
allowlists are broad-but-justified given the readiness audit's secret-clean verdict; the founder can tighten.)*

**The 9 gate reds — triage (NOT all cleanly "changeset-able").** They are accumulated drift from **other feature
WIP** sitting uncommitted in the tree, *not* go-public drift. Two kinds:
- **Clean accept (sanctioned ratchet/cap mechanism, honest declaration):** `class-size` (`ResolvedConfigBuilder`
  +6 LOC + loose-pin rebalances), `exception-count` (65 > 56 → budget-raise changeset), `ts-any` (1 new `any` in
  `SurfaceTabs.test.ts`), `clone` (duplication grew across `adapters-lucene`/`app-agent`), `ui-bundle` (3.35 MB >
  3.15 MB hard cap → a deliberate cap-raise decision).
- **★ Real drift needing CORRECT declarations, not rubber-stamps:** `execution-surface` (new code references the
  canonical `SearchTrace` unregistered — this is *literally the projection-vs-fork discovery gate*; blind-registering
  could publish a fork), `consumer-drift` (a new substrate with no consumer floor), `observed-happening`
  (declared contributors resolve to no catalog id), `test-efficacy` (6 logic seams unmeasured in the strength
  report). Resolving these correctly needs the *feature context* that introduced them — `fix-root-causes-not-
  symptoms` says don't blanket-accept them blind. **Open decision (founder):** reconcile the 4 properly, or make a
  conscious "accept the WIP drift as the launch baseline" call. Until then `main` is not cleanly greenable, so the
  preflight stays red and the flip stays blocked.

**Content/candor delta-scan (2026-06-25) — the prose analogue of gitleaks; 4 results.** Scanned the publish
surface (docs minus the sidecar; scripts/.claude/CLAUDE.md) for sidecar-grade content vs 631's 2026-06-23
baseline. (Highest tempdoc is now **650** — "Go-public capability-descriptor truthfulness", the in-flight
descriptor work.)
1. **Founder PII: CLEAN.** No founder email/phone/personal detail in the publish surface — it lives only in the
   excluded sidecar. (The `+49` / `Abitur` hits are *public eval datasets* — CoNLL/Enron/MIRACL — not PII.)
2. **★ Machine-path leak: FIXED.** 631's `C:\Users\Elias` scrub was incomplete — **33 occurrences across 17
   published tempdocs** (+ a canonical doc) remained. Re-ran the codemod → `C:\Users\<user>`; verified 0 remain.
3. **Grant-integrity candor in the go-public tempdocs (founder-editorial — OPEN).** 631/633/634/624 candidly
   discuss the NLnet grant application's retracted "92%-fork" number (e.g. `631:511-512`, `634:136-138`,
   `624:1187`). The 624 redaction removed 624's worst instances, but 631/633/634 *reproduce* it and also publish.
   Decide: accept-as-transparency (consistent with publishing candid tempdocs) or scrub for consistency with the
   624 call. **→ DECIDED 2026-06-25: ACCEPT — publish the grant-integrity candor as a transparency tradeoff
   (consistent with the attacker's-roadmap accept; no scrub of 631/633/634/624).**
4. **★ Sidecar-grade business content OUTSIDE the sidecar paths (founder decision — OPEN; the T3b class made
   concrete).** `docs/future-features/` (31 tracked files) + `docs/research-results/` would publish. Most of
   future-features is legit OSS roadmap, but several carry strategy: **`open-source-readiness.md`** (an
   Open-Source Readiness Analysis overlapping the *private* readiness doc), monetization mentions
   (`future-features.md`, `monorepo-to-multi-repo.md`, `512`), `enterprise-policy-v3.md`, and
   `research-results/compass_*` (market sizing / addressable-market / GTM). Path-based exclusion misses these.
   Decide: exclude these paths from the snapshot, scrub the business bits, or accept. *(This corrects the earlier
   B-finding note that 631 had done the content sweep "corpus-wide": its candor pass did not catch the business
   content in these non-sidecar paths — exactly the T3b path≠content gap.)* **→ DECIDED 2026-06-25:** recency
   analysis confirmed these are mostly stale (research-results 5mo, enterprise/monorepo 4–5mo). **Deleted**
   `docs/research-results/` + `enterprise-policy-v3.md` + `subscription-licensing-for-premium-features.md`;
   `docs/future-features/` is a **provisional unpublish** (stays private; wired into the snapshot linter as
   `unpublished/docs-future-features` + the cutover-runbook §1d). Revisit to publish the ~20-doc technical-roadmap
   subset later. **The candor delta-scan is now resolved** (PII clean, machine-paths scrubbed, secrets 57→0,
   grant-candor accepted, business-content excluded/deleted).
