# Round 19 charter — 0.3.0 candidate 2, fresh-install (the runbook's required first fresh-install round)

Round numbering continues the 0.2.0 series (round 18 was the upgrade-from-release round on
candidate 1; tempdoc 941 records it).

Purpose: the first `fresh-install` round of the 0.3.0 line — the only mode that covers the real
model/GPU-runtime download path — on candidate 2, which carries the round-18 fix campaign. Two
jobs: (1) the full first-run journey on a clean machine (installer → first paint → Install AI →
first index → first search/chat → restart → uninstall), and (2) re-confirm every round-18 finding
fixed, with the healthy signature stated per item below. Verifier: Codex first (round 18 proved
the harness; its Step-0 probe and screenshot export were amended after that round), Claude Code
fallback.

Candidate: `JustSearch_0.3.0_x64-setup.exe` — UNSIGNED dispatch of `build-installer.yml` on
`main` after the fix-campaign PRs merged (`sign=false`, `sandboxTestMode=false`). Exact SHA-256,
size and CI run: `candidate-provenance.md`. Unsigned ⇒ Authenticode NotSigned everywhere and the
SmartScreen unsigned-publisher warning: capture it, mark `install-trust-prompts`
**unobservable-signed** with that reason, do NOT file it.

Mode: `fresh-install`, `--no-models`. No host models mapped; never set `JUSTSEARCH_MODELS_DIR`.
Search v3 (tempdoc 852) is NOT promoted: a visible "v3" chat toggle or second chat surface is a
finding.

## Sequence

1. Install on the clean image. Capture every installer page and every Windows trust prompt.
2. First paint, backend health, jar-uniqueness check, pre-Install-AI UI sanity.
3. Install AI through the UI (full download). On ANY package failure run `probe-download.ps1`
   before forming a hypothesis. Record per-package truthfulness against `/api/ai/install/status`.
4. Library: add the SciFact folder (one confirm click — 914 §O-2) and one small folder of your
   own; let enrichment drain; capture golden queries only once the embed EP is warm (the
   collector gates this itself).
5. Coverage brief `sandbox`-tier items, the escalation ladder (Search → Documents → Structured →
   Agent), trust surfaces, `/mcp` raw POST + the MCPB stdio TYPED_CONFIRM driver, one Worker kill
   during an agent run, one genuine four-process cold restart, uninstall.

## What is NEW in candidate 2 vs candidate 1 (verify, don't re-file)

1. **Settings stays localized in a long-running shell (round-18 F3 fix).** Trigger: open
   Settings once BEFORE the Worker kill / schema rebuild / cold restart, once during the
   long-running session AFTER those events, and once after the cold restart. HEALTHY: every
   label is text; no visible label begins with `settings.`. BROKEN: raw keys such as
   `settings.group.general` anywhere, at any of the three points.
2. **Health event copy for a failed indexing job (F4 fix).** Trigger: put a corrupt PDF
   (`%PDF-1.4` header followed by garbage, NOT a zero-byte file — zero-byte and locked files are
   handled as no-content and leave no failed row) in a watched folder, rescan. HEALTHY: the
   System Health / activity row names the file path and the error in a sentence; no `{path}` /
   `{errorClass}` placeholders, no `atMs=` attribute dump. Discriminator endpoint for the row
   itself: `GET /api/indexing-jobs/failed` (substrate, carries `scanId`), not the legacy
   `/api/indexing/failed-jobs`.
3. **Add Folder during a schema rebuild (F5 fix).** Trigger: Library → Add Folder while
   `/api/knowledge/status` reports the rebuild running. HEALTHY: the notice says the index is
   being rebuilt and the folder can be added once that finishes, in user words. BROKEN: the raw
   condition id `rebuild-index` or a raw GMT timestamp as the headline.
4. **Failed-files drawer shows the scan id (F2 fix).** Same corrupt-PDF row as item 2, opened
   from the Library drawer. HEALTHY: a secondary "Scan <id>" line whose id equals `scanId` on the
   `GET /api/indexing-jobs/failed` row (the latest scan that failed the file — a rescan changes
   it, by design). BROKEN: no scan line while the API row carries a non-empty `scanId`.
5. **Structured rung (round-18 watch item, not a finding).** On the LONG-RUNNING shell (not a
   fresh one), record the `POST /api/chat/dispatch` span count, send one Structured prompt with a
   one-property schema, record the count again. HEALTHY: count +1 (plus the OPTIONS preflight)
   and a rendered JSON result tied to that prompt under the "Model-generated structure" frame.
   BROKEN: prompt cleared, no new JSON, dispatch answered in ~1 s (round 18 saw this twice and
   could not reproduce it on a fresh shell). The shipped UI never calls `/api/chat/extract`;
   the direct control is `POST /api/chat/dispatch` with `shapeId: "core.extract"` and a `prompt`
   field (NOT `question`).
6. **Round-18 must-watch ids** stay in the brief and get verdicts; the five `upgrade-*` ids are
   `unobservable` in a fresh-install round with that reason.

## Round-plan obligations

- Budget `expired-pending-approval-ceremony` right after the first TYPED_CONFIRM approval.
- `webview-performs-one-search` (UI search with results; trace shows the mutating call 2xx with
  the session token).
- Golden parity: the staged `golden-parity.json` is the candidate-1 baseline (same corpus,
  same embedding weights, no search-affecting change in the fix campaign); capture after
  enrichment is drained and the EP is warm.
- Codex: Step-0 probe per the amended skill (probe `@oai/sky` through `node_repl` before reading
  an empty unified inventory; verify PNG magic bytes on the first capture). Record in
  `session-analysis.md` whether either amendment was needed.
- Every Documents-rung capture filename carries BOTH `unified-chat` and `rag-ask`.
- All six process artifacts; `findings.md` standalone; in-round tools under the mapped share's
  `round-tools/`.

## Blocker classification (runbook step 3)

Every item above is **needs-round**. There are no open needs-dig blockers on this candidate.

## Carried over / do NOT re-file

- Known Issues in the 0.3.0 CHANGELOG (in-app update from 0.2.0 not exercised — 617 §9; one
  overall download bar — 840; empty quick answer with reasoning on — 845/848; out-of-root
  documents not removable — 875; the 10-iteration agent ceiling — 868): observed-known unless
  worse than described.
- Locked-chat `POST /api/chat/dispatch` returning 200 and discarding the question (817 §1).
- Health card 2–4 min staleness in the safe direction (0.2.0 round-16 F5).
- Restart-era bare "AI installed." beside `installedFully:false`.
- SmartScreen reputation verdicts and elevation prompts: structurally unobservable
  (`EnableLUA=0`, no MOTW on the folder mount) — record unobservable-with-reason.
- Golden parity q04/q06/q08 sub-floor overlap: descriptive under the blocking
  golden-#1-in-top-3 assertion (the dev→Sandbox systematic divergence, 734 finding 5).
- Round-18 harness items H1/H2 are fixed in the staged harness; if either recurs it is a
  harness finding again, not a product one.
