---
title: "832 — Core differentiators charter: eval-driven search quality + agent-facing runtime contract"
type: tempdocs
status: "CHARTER + EXECUTING (2026-08-14) — investigation complete (4 verified lanes), sequencing set; lane A step 1 (748 §G.1 probe) and lane D (MCP hygiene bundle) launched this session."
created: 2026-08-14
author: agent session 7eb0297f-46bd-47bf-9c0a-203922f2303a (Fable orchestration)
category: search-quality / mcp-runtime-contract / measurement
related:
  - 821-root-cause-debt-charter.md   # sibling direction; its §3 C7 cedes measurement-apparatus integrity to this direction
  - 803 (harness fix shipped; re-baseline debt is lane B here)
  - 771 §G / 789 / 791 (agent-utility levers; 791 is the campaign design home)
  - 796 §Deferred (tier-D) / 748 §G (DE rebuild probes)
---

## §0 Frame

Owner direction (2026-08-12/13, session 776e10cd transcript + this session): post-installer
risky phase splits into two directions — root-cause debt (tempdoc 821, other agent) and
**core differentiators** (this document): eval-driven search quality plus the MCP/local-
runtime-contract surface. Frontend excluded. No compat constraints yet (installer = demo
artifact). 821 §3 C7 explicitly assigns measurement-apparatus integrity "mostly owned by
the differentiators direction" — i.e. here.

This charter records the investigation-verified state (2026-08-14), the sequenced plan,
and the execution log. Detailed findings live in the tempdocs named per lane; the
search-quality register is updated as measurements land.

## §1 Verified state (all claims file:line-verified at main HEAD, 2026-08-14)

**Lane A — cheap unrun measurements (owned homes: 748, 796).**
- 748 §G.1 (German-vs-English gold-bridge at matched payload version): committed corpora
  (`scripts/jseval/748-corpora/de-miracl/`), committed script
  (`scripts/jseval/experiments/gold_bridge_pair_748.py`), pre-registered decision rule
  (748:449-452). ~2 min, zero backend/GPU. Never run.
- 796 tier-D (in-corpus paraphrase bridging; the Q-019 attribution key for the hero q0
  failure): `paraphrase_bridge_suite.py tier-d` (CLI verified at :1096), ~2.5-3h CPU-only,
  one-heavy-lane thermal rule, checkpoint cache gone (cold start). Never run.

**Lane B — measurement debt behind the 803 harness fix (this direction's core apparatus
work).** The 800/802 CE-ordering defect is FIXED (`retriever.py:160-162`, PR #354). Open:
(1) the 5-corpus re-baseline never ran — blocked on a reproducible 1-doc SPLADE
enrichment failure on miracl-fr-2k via the eval ingest path (803 §; 3/3 repro eval path,
0/1 dev-stack path; mis-filed in observations as a flaky gate, `obs:readiness`);
(2) relevance-ratchet floors are old-basis while all new runs are new-basis — on
legal-clerc the ordering term (−0.0418) exceeds the 0.02 tolerance;
(3) `staged_recall_accounting.py:27-29` trec-preference rationale is void post-fix;
(4) F-001/F-002/F-006/F-008 (+F-026 by inheritance) need fresh measurement — original
artifacts no longer exist. Re-baseline = 5 corpora × 4 modes, GPU-exclusive, ~1-2h/pass.

**Lane C — agent-utility levers (owned homes: 771 §G, 789; design home for next campaign:
791).** Entity carriage live at HEAD, default-off (`ResolvedConfigBuilder.java:562-571`),
live-backend smoke pending ($0, one query). 789 F1/F2 graduated the direction-finder;
naturalistic replication launch-ready, founder-gated (~$45-100 sonnet cells, 782 harness
verbatim). No arm has ever combined carriage+F1 (789 flags this). Any accuracy/behavior
measurement requires paid cells; only content-coverage was measurable at $0 (done).

**Lane D — MCP hygiene (verified still-live at HEAD post-#420/#444).**
1. MCPB manifest drift: `packaging/mcpb/manifest.json:6` = 0.1.0 vs canonical 0.2.0;
   `pack-mcpb.mjs --set-version` (:204-218) only stamps server.json.
2. `resources/read` raw `Map.of` (JVM-salted ordering): `McpToolSurface.java:1614, 1627,
   1644, 1663, 1793`; the `orderedMap()` helper (:1764-1770) already exists from the
   resources/list fix.
3. Agent-ingest wiring gap: `AgentToolFactory.java:60-61` builds a second
   `KnowledgeHttpApiAdapter`; `setScanProgressRegistry`/`setScanRollupLedger` are only
   ever called on the controller's adapter (`LocalApiServer.java:851,858-859`) → agent
   `scanRoot` emits no scan-progress SSE / rollup ledger row.
4. Docs caveat asymmetry: the "v0.1.0 predates /mcp" warning exists only for the MCPB
   bundle (`mcp-production-server.md:42-52`), not the manual-connector sections or
   README:67-88 — and the premise itself is unverified against the shipped binary (the
   in-tree v0.1.0 tag DOES register `/mcp`; public history is squashed, so tag ≠ binary).

## §2 Sequenced plan

1. **Now ($0):** 748 §G.1 probe; MCP hygiene bundle (items 1+2+4 wording + item 3 with
   the threaded-wiring design) as one delegated worker chunk in an isolated worktree.
2. **CPU slot (serialized, load-gated):** 796 tier-D.
3. **GPU/dev-stack slot:** root-cause + fix the miracl-fr eval-path SPLADE failure, then
   the 803 re-baseline campaign (new release object → floors + README table re-project;
   fresh F-001/F-002/F-006/F-008 measurements; retire the stale trec-preference in
   staged_recall_accounting). Entity-carriage live smoke rides the same stack window.
4. **Founder-gated spend:** 789 naturalistic launch and/or 791 campaign v2, designed to
   include an entity-carriage arm. Not launched without explicit owner go-ahead.

## §3 Coordination / collision notes (checked 2026-08-14)

- `McpToolSurface.java` is hot: #420 (821) + #444 (822) landed; locked worktrees
  `822-closeout` / `sv3-*` continue 822's arc. Keep lane-D diffs minimal; check merge
  order before PR.
- The register is concurrently amended by the 821 session (F-045/F-046). Pull before
  register edits; conflicts are append-shaped.
- Re-baseline should run after `819-fingerprint-boot-race` merges if possible (821 §N
  raised its priority; fingerprint resets force full re-embeds) — each jseval run builds
  a clean index, so this is an efficiency concern, not validity.
- Dev stack is shared; lease with `leaseDurationSec` for campaign passes.
- 828 (shard identity collision) unfixed: workers logging observations will conflict on
  the shared shard — workers should report observations back in their result instead;
  orchestrator logs them.

## §4 Execution log

- 2026-08-14: charter written (renumbered 830→832 after cross-worktree collisions with
  in-flight sv3 tempdocs); MCP hygiene bundle delegated (worktree-isolated opus worker).
- 2026-08-14: **748 §G.1 probe EXECUTED** (env note: ambient `python` lacks onnxruntime —
  the dep-complete interpreter is `F:\scoop\apps\python\current\python.exe`). Result: gap
  persists at matched payload.v2 — EN bridge P@1 0.84 / margin +0.046 vs DE 0.30 / −0.026;
  hypothesis (a) survives as a scoped secondary cause. Recorded in 748 §G.1 + register
  Q-018; regen (llms.txt + skills-sync) run. Artifact
  `scripts/jseval/tmp/748/gold-bridge-pair-v2.json`.
- 2026-08-14: **Lane D SHIPPED to PR #453** (branch `worktree-agent-ab144637100f43211`,
  5 commits, orchestrator-reviewed diff). All four items done; item 3's briefed design was
  infeasible (registry/ledger don't exist at ServicePhase; the eager adapter is null on
  normal async-Worker boots) — shipped as `bindScanObservability` on BOTH adapter paths
  (eager + late-bound), with `AgentToolFactoryScanWiringTest`. resources/read has ZERO
  test coverage (pre-existing gap, worker-verified); `AgentToolHandlers.registerLateBound`
  duplicates ~30 lines of `AgentToolFactory.build` (two authorities — the scan-wiring bug
  was a symptom). RELEASE NOTE: `server.json` fileSha256 now describes the 0.2.0-stamped
  bundle — the v0.2.0 `.mcpb` asset must be built from this commit or later.
  preview-squash-message OK. **MERGED 2026-08-14 as `89b85fe5`** under the restored
  standing publish grant (/publish flow: catch-up merge, full local suite — one
  LifecycleContractTest flake interrogated to CPU-load cause (tier-D saturation) with
  isolated + full re-runs green — secret/claims scans clean, required CI green).
  Incident: the required `cla-assistant` check hung `in_progress` 50+ min (norm 9-12s);
  plain cancel didn't take, `POST .../actions/runs/<id>/force-cancel` + rerun cleared it
  in 7s. Worktree removed, session→merge recorded.
- 2026-08-14: **Trap found (tooling, this direction's own instrument):** the 796 suite's
  `pairs` step regenerates `scripts/jseval/796-paraphrase-pairs/paraphrase-pairs.v1.json`
  in place and, when the DE pool sources are absent from tmp, silently writes an EN-only
  register — DELETING the committed German half (1,807 lines). Caught in `git status`,
  restored via single-file checkout; tier-D unaffected (EN members, reads the tmp copy).
  Follow-up (small, no own tempdoc): `pairs` should fail closed or merge-preserve
  languages it cannot regenerate (`green-masked-destructive` shape). Root cause found
  later the same session: `pairs` defaults to `--langs en` (the F-044 run used `en,de`).
- 2026-08-14: **796 tier-D COMPLETE → F-048 / Q-019 ANSWERED.** Host dilution dominant
  (isolated 0.995@10 → 0.78 on emails → 0.25 on CLERC-length hosts), query shape a
  secondary amplifier (−9 pts top-1 question→keyword on enron); hero q0 = dense rank 16
  at question form, 76 at keyword — dilution-marginal, shape-pushed out of window.
  Consequence: delivery lane (788 §3.A) and engine lane (§3.B.10) remain SEPARATE
  problems; no encoder swap re-licensed. Recorded in 796 §Tier D + register F-048 +
  Q-019 disposition. Driver incident: `report` hard-requires session-local tier-p/s
  artifacts — regenerated EN-only, then green; my driver also stamped OK despite
  report=1 on the first pass (marker not gated on the report step — noted, one-off).
- 2026-08-14: **796 tier-D launched** (CPU load 23% < 60 precondition; detached
  `Start-Process` driver `tmp/paraphrase-bridge/tier-d-driver.ps1` with per-stage markers,
  since tracked background tasks die at ~60 min). `pairs.v1.json` was absent (tmp cleared)
  so the driver re-runs the `pairs` step first; checkpoint cache gone → cold start,
  expected ~3h total (enron ~25min/member, clerc ~2h, then `report`).
