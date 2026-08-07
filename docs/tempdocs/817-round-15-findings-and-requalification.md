---
status: active
created: 2026-08-07
updated: 2026-08-07
---

# 817 — Round 15 findings, post-round findings, and the 0.2.0 requalification wave

Round 15 (2026-08-07, TRUE fresh-install, candidate `JustSearch_0.2.0_x64-setup.exe`
sha256 `3b10c1ce…06407`, built from `0b14840b`) was the intended final qualifying round
for 0.2.0. It was NOT clean: one real defect (F1/F1b) plus a post-round live-validation
session that surfaced a heavier scheduler defect. A rebuilt candidate and a fresh-install
round 16 are required. Evidence archive: `tmp/sandbox/share/evidence/` (findings.md,
retrospective.md, session-analysis.md, the four post-round notes, 190+ captures).

## 1. Round-15 verdict

- **F1/F1b (HIGH, twice-reproduced)** — the progress card renders "Ready — fully
  searchable / Everything is indexed and enriched" and the Library row an unqualified
  green "Verified" while the API reports `embeddingCoveragePercent=0.0`, SPLADE 0%,
  NER 0/5189, `NO_EMBEDDING_MODEL`. The two-tier completion strings shipped in #395
  ARE present and correct mid-enrichment (verified in-round on the same build); the
  hole is that "done" derives from *no enrichment work outstanding* rather than
  *coverage complete* — with no model installed nothing can be queued, so
  zero-outstanding reads as complete (`unreachable-seed-green`). Trigger conjunction
  (established by the two reproductions): coverage 0 + model absent + queue empty.
  Coverage-100-with-model-absent correctly reads complete (vectors persist) and must
  keep doing so.
- **F2 (observation, not filed)** — with chat history locked, the UI path is honest
  (Send disabled + reason + draft survives; the old silent-discard did not reproduce),
  but a direct `POST /api/chat/ask` returns 200 with a full RAG answer instead of the
  charter's 423. **Owner decision deferred (2026-08-07): open question, not
  release-blocking.** If later ruled a gap: host live-stack test asserting 423 on the
  API path while locked. Unexamined edge worth checking at decision time: whether the
  200-path answer can be persisted into the locked history or is silently dropped.
- Everything else verified positive: visual identity everywhere / zero stock-Tauri
  residue, Other-sources, audit-trail restart durability, TYPED_CONFIRM via the real
  MCPB bridge, default-scope counting, ladder offline honesty, openai-compat,
  ADR-0024 uninstall, GPU ~67–69 tok/s. Golden parity 10/10 blocking pass (q06/q08
  sub-floor = the pre-registered dev↔sandbox systematic, descriptive only).

## 2. Post-round findings (owner-driven session, reinstalled instance, real-doc corpus)

Source notes in the evidence dir: `tempdocs-enrichment-stall.md`,
`progress-indicator-scope-mismatch.md`, `search-history-and-results-defects.md`,
`agent-scrollbar-analysis.md`.

- **Long-document head-of-line blocking (the campaign's most important product
  finding)** — on a 570-doc corpus with mean file ~47 KB, the combined backfill's
  work-set was byte-identical for 54 consecutive cycles (12+ min): window-embeds
  succeeded every cycle (`fail=0`, `longDocWindowed=48`) but documents never completed
  (window progress not persisted across cycles; the head of the queue was re-fetched),
  and the embed stage consumed the full 5000 ms budget every cycle, starving SPLADE and
  NER to literally 0 ms (1.05% / 6-of-570 for the whole stall). Positively confirmed
  mechanism: both stages began advancing the instant embed hit 100%. Not permanent
  livelock — severe stall that eventually drained. Scifact (mean ~1.4 KB) never
  exercises the windowing path, which is why every prior round was blind to this.
- **Brain-bar scope mismatch** — Brain's "Building semantic search" bar tracks only
  document-level embedding coverage (1 of 4 signals), carried a subtitle describing a
  different signal ~67 points away, and disappears at its own 100% while overall
  enrichment is at 46% (Brain then reads as done mid-run). The bottom-left card is the
  honest authority (unit-weighted over 4 signals, arithmetic verified, matches the
  status bar). Fix direction: one shared progress source.
- **Search surface S1–S6** — two unlabeled history affordances (S1/S2), recent-list
  duplicates (S3), popover occluding the primary action with no Esc/outside-click
  dismiss (S4), "Top 50 of 288" header over a "Show all 20" control with `totalHits`
  tracking the request limit while `matchCount` is the stable figure (S5), stale
  result blocks unmarked when the index changed underneath them (S6). Recurring
  pattern named by the round: *a number true of a narrow scope rendered as though it
  describes the whole* (same family as F1 and the Brain bar).
- **Agent run-minimap** — good design (semantic minimap, honest collapsed groups,
  tool-call tooltips), but a six-glyph private symbol language: no legend, color-only
  authorship distinction, ~4 px hit targets, competing native scrollbar. Medium,
  polish tier.

## 3. Finalize-gate forensics (host-side)

Token health, mutating probe, must-watch verdicts (8/8, two honest `unobservable`),
retrospective, session-analysis, golden parity: all pass. `check_coverage.py` exited 1
with four "uncovered" items + an incomplete evidence review — **all five are evidence
plumbing artifacts, not coverage gaps**:

- The evidence `traces.ndjson` starts 02:11Z: it is the **pristine-repro instance's**
  trace file — the F1 renamed-aside-data-dir reproduction replaced the round's
  telemetry, and the final collect (02:18Z) copied that ~7-minute file. `POST /mcp`,
  `/v1/*`, and the memory routes were exercised (their evidence artifacts exist:
  `mcp-tools-list-raw.json`, typed-confirm frames, `api-v1-*.json` — the last captured
  after the final collect anyway) but their spans are gone.
- `unified-chat` "uncovered" is a filename-token miss on a heavily-screenshotted
  surface.
- The 35 "unexamined" screenshots are all from the post-finalize investigation session
  (the 90–118 series), which landed after the review was correctly completed.

Harness consequences (routed to the wave's harness worker): the renamed-aside
technique must preserve traces before the swap; post-finalize investigation gets a
sanctioned `evidence/post-round/` dir excluded from credit-eligibility (parallel to
`raw-frames/`); plus the round's own retrospective items (GUI README signature rows,
`Assert-AppSurface` ledger predicate, prior-install false alarm, folder-picker note,
`unified-chat` token guidance).

## 4. Owner decisions (2026-08-07)

1. **Enrichment stall: FULL fix in the requalification candidate** — per-window
   progress persistence (the real fix) + per-stage budget reservation + stall WARN,
   not mitigations-only.
2. **F2: deferred** — open owner question (see §1).
3. **816 per-surface sweep: parked until after 0.2.0** (measure number stays 88ch for
   now; the sweep and the 72–76ch decision resume post-release).
4. **Search-window rewrite: planned, post-0.2.0, design-first** — most S-findings are
   consequences of the window's accreted model (search-results-as-transcript), not
   implementation bugs; a rewrite tempdoc settles the interaction model before any
   code. S1/S2/S4/S6 are deliberately NOT patched into the old structure; S5's
   count-field fix is API-adjacent and may land earlier. The rewrite becomes the first
   surface born under the 814 height-budget + 816 sizing substrates.

## 5. The requalification wave (in flight)

| Worker | Scope | Regression home |
|---|---|---|
| FE honesty (opus, worktree) | F1/F1b: completion tier additionally requires coverage support; honest qualifier when semantic enrichment cannot run; Brain consumes the shared aggregate progress source, scope-labeled, no early disappearance | Render/unit tests from the captured F1 API fixture (zero-coverage + zero-queue + `NO_EMBEDDING_MODEL` → not "fully searchable"); coverage-100-model-absent stays complete; Brain shows progress at embed=100/others<100 |
| Backfill (opus, worktree) | Per-window progress persistence; per-stage budget reservation; deduplicated stall WARN | Deterministic long-doc test: work-set strictly decreases across cycles; SPLADE/NER get non-zero scheduling under an embed backlog; WARN fires on frozen progress only |
| Harness (sonnet, worktree) | §3's harness consequences + retrospective items 1–7 | `test_check_coverage.py` suite + new post-round-exclusion and predicate self-tests |

Chain after merge: dispatch `build-installer.yml` from the merged main, hash-verify,
regenerate golden parity on the new candidate's dev stack, restage the sandbox share,
**round 16 fresh-install** (per policy the final qualifying round re-runs
fresh-install). Round-16 charter must pre-register F1's healthy signature (both tiers
of the fixed card), the long-doc corpus watch (stage a corpus with >100 KB files —
scifact cannot exercise the windowing path), and Brain-bar/card agreement.
