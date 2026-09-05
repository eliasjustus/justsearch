<!-- Sidecar of docs/tempdocs/930-replace-bounded-areas-with-maintained-oss.md — moved here per the tempdoc size-cap split (930 §19.3 F4, 2026-09-05). Headings below are unchanged from the main file; this directory is exempt from check-tempdoc-size.mjs. -->

## 16. Orchestrator verification of the relayed §13 claims (2026-09-05)

Founder asked whether the §13 findings were validated or merely relayed. Pass 1 and 2 workers
were read-only researchers; the orchestrator had verified ~15 load-bearing claims inline (noted
per section). This section records a second sweep over the rest, one command per claim, breadth
over depth. Legend: ✓ confirmed; ≈ confirmed with a different number, same conclusion;
✗ worker wrong, corrected.

| Claim (section) | Result | Note |
|---|---|---|
| 47 substantive changesets; classification split 13/13/9/3/3/2/2/1/1 (13.1) | ✓ | exact |
| 5 changesets titled `advance-baseline-to-NNN` (13.1) | ✓ | |
| 16 of 35 gates have zero changesets (13.1) | **✗ → 26 of 35** | worker undercounted; the stop case is stronger |
| 31 of 53 registers unreferenced from `modules/` (13.1) | ≈ 19–31 by method | basename grep finds 34 referenced, but 17 of the hits are comments |
| "No production code path reads any register" (13.1) | **✗ by one** | `modules/ui/.../UpgradeReconciliationProbe.java:200` loads `/governance/store-recoverability.v1.json` from the classpath. Every other main-source reference is javadoc or a built asset comment. That register must be kept or its consumer re-pointed in the governance stop. |
| One documented gate catch in postmortems (13.1) | ✓ | `agent-postmortems.md:96` |
| `bash-guard` git rules: 27 blocks, 0 true positives, 11 false (13.2) | ✓ | orchestrator joined every `is_error` block to its `tool_use` command: **11 results, all false**: 5 `cd`-into-worktree compounds, 2 single-file `--ours`/`--theirs`, 2 scratchpad/manifest echoes, 2 `gh workflow run … -f` chained after a plain push. 21 "main worktree" + 4 "force push" `is_error` results in 30 days. |
| sleep rule ≈117 blocks (13.2) | ✓ | 116 `is_error` results |
| `known-state-hint` 988 fires (13.2) | ≈ | 1,048 raw string hits, includes echoes |
| 19 pins, uniform `reviewBy 2026-09-30`, 3 with owner (13.2) | ✓ | exact |
| 1 `Skill` invocation in the 2-day ledger (13.4) | ✓ | 2 lines (pre + post) |
| Codex first commit 2026-09-03; branch split (13.4) | ✓ | 46 `codex/*` incl. remote vs 79 `worktree-*` local |
| 667 tempdocs / 468k lines / 153 > 1,000 / 40 > 2,000 / 6 > 4,000 (13.4) | ✓ | exact |
| 21% of tempdocs never cited (13.4) | ≈ 9–21% by regex | orchestrator's stricter pattern (also matching `NNN-slug` filenames) gives 62 of 666 |
| bisection / cohort / drift / compare-runs: zero artefacts in any run dir (13.3) | ✓ | 0 files for each name outside source and tests; `bisection.py` has 1 commit |
| `ci.yml:92` says the 132-file test suite runs nowhere; 46,941 test lines (13.3) | ✓ | |
| Catches at 916:775-780 (`degraded-ce`, 13 silent deadline drops) and 635:1006 (`COMPARABLE=False`, arm-C error rate 0.20) (13.3) | ✓ | both quoted verbatim |
| 201 dev-runner runs; 20 interference events; ~13 self-reclaims (13.5) | ≈ | 201 ✓, 20 ✓, **9 of 20** self-reclaim by actor = victim session id; still roughly half |
| Hot-reload 0 uses (13.5) | ≈ 0–1 | the OTLP log embeds the tool schema in request bodies, so every tool name appears ~55 times per window; only 2 `tool_result`/`tool_use` lines mention `reload`. Unused in the sample; the sample is two days. |
| CPU fallback shipped: `getCpuSession()` (13.6) | ✓ | called at `NativeSessionHandle.java:213,218,248`; `cpu_fallback.triggered` in `08-observability.md:581`; ADR-0004 update dated 2026-06-17 at `:96` |
| OCR 115,055 → 16,774 ms, 6.9× (13.6) | ✓ | 706 status line and `:345`; the per-file table says 6.8× |
| VDU gated on `hasVisionCapability()` (13.6) | ✓ | `VduBatchProcessor.java:162`, `VduProcessor.java:166` |
| `updater.rs` added 2026-07-31, 2 commits (13.6) | ✓ | `6a6e4835` |
| Two releases; 0 user issues; v0.2.0 setup 16 downloads, `latest.json` 1 (13.6) | ✓ | via `gh` |

**Net effect on §14/§15:** no verdict changes. Two corrections strengthen the governance stop
(26 not 16 gates without a changeset) and one narrows it (keep or re-point
`store-recoverability.v1.json`, which has a real runtime consumer). Every number derived from
the 2-day ledgers remains a spot check until §15 row 0 lands.

