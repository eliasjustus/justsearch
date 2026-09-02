---
title: "File lifecycle and data portability: per-file forget, citation liveness in saved conversations, out-of-root prune reachability, plaintext conversation export, uninstall data prompt"
type: tempdocs
status: CHARTERED (2026-09-02) — not started
created: 2026-09-02
updated: 2026-09-02
lane: 887 L5
model: opus (takeover)
parent: 887-improvement-landscape-register
related:
  - 626-incremental-indexing-correctness   # sweep-based delete detection; convergence authority
  - 811-corpus-scoping-policy-brief        # MCP-ingested docs with collection=null are unreachable by prune
  - 812-action-ledger-audit-retention      # retention by time/count, not by referenced-file lifetime
  - 629-data-at-rest-encryption            # AUTHORED/DERIVED classes; export container
  - 875-agent-tool-consent-boundary        # undo of file operations; second ingest surface open
  - 879-operation-policy-enforcement       # store-recoverability register truth
---

# 891 — File lifecycle and data portability

## Briefing for the agent picking this up

Fresh start. Read this file and 887 Appendix A3 §3.2/§3.3. Work in a worktree. Index deletion
lives in `modules/adapters-lucene/.../runtime/PruneOps.java` (Worker side, reached by gRPC) and
`modules/app-services/.../worker/RootLifecycleOps.java` (Head side); operations are declared in
`CoreOperationCatalog.java` and gated by the `operation-surface` kernel gate; the encryption/
export controller is `modules/ui/.../api/ConversationBackupController.java`; the NSIS hooks are
`modules/shell/src-tauri/nsis/installer-hooks.nsh`. Head never touches Lucene — everything
index-side is a Worker RPC. Four PRs, in the order below.

## Thesis

Per-root removal exists (`RootLifecycleOps.removeWatchedPath`, `core.remove-watched-root`), but
there is no per-file forget; deletion is detected only by periodic sweeps; citations inside saved
conversations and action-ledger references keep docId + path forever and only fail at read time
(`ReadDocumentTool.java:206`); MCP-ingested out-of-root documents are unreachable by any prune
path (811); conversation export requires encryption to be set up and unlocked; and uninstall
leaves the data dir and models on disk with no prompt (`installer-hooks.nsh:87-90`).

## Decisions made for you

- **Citations are marked, not deleted.** A saved conversation is authored user data; when its
  source file is gone the citation renders as "source no longer available" and stays clickable
  to the last-known path. Liveness is a read-time **projection** (`docId` → present/absent),
  not a write-time mutation of conversation records. This keeps the one canonical citation
  record (853/867/869 citation-mark authority) and avoids a fork.
- **Per-file forget is a proper operation**, `core.forget-path`, medium risk, inline-confirm,
  declared in `CoreOperationCatalog` next to `core.remove-watched-root`. It deletes by exact
  path (and descendants if a directory), including chunks, and emits a ledger event. It also
  works for documents outside any watched root — prune by docId set, not path prefix — which is
  the 811 gap.
- **Data-dir relocation / portable mode is a non-goal here** (product-shape; owner decision).
- **Uninstall keeps data by default.** The uninstaller shows one checkbox, unchecked: "Also
  remove indexed data and downloaded models (`<dataDir>`, `<modelDir>`)". Checked → recursive
  delete after process kill. Per-user install, so no elevation surprises.
- **Plaintext export is per-conversation Markdown + JSON**, available regardless of encryption
  state (it is the user's own text). The encrypted whole-store container stays as is.

## Scope

1. `core.forget-path` operation + Worker RPC (`DeleteByDocIds`/`DeleteByExactPath` in the wire
   contract — run `--gate wire`) + ledger event + FE entry point on a result's context menu
   (`OpButton` from the catalog, 509). Tests: forget a file inside a root, a directory, and an
   MCP-ingested out-of-root doc; assert chunks gone and ledger row present.
2. Citation liveness projection: a `sourceState` field on the citation projection computed at
   render/fetch time from the index (`exists` by docId, batched), rendered per the citation-mark
   authority; agent `read_document` returns the same state. Test with a conversation citing a
   file that is then deleted and pruned.
3. Per-conversation export endpoint (`GET /api/conversations/{id}/export?format=md|json`) +
   "Export" action in the conversation controls (610). Redaction: paths stay (user's own data).
4. Uninstall prompt in `installer-hooks.nsh` (`NSIS_HOOK_PREUNINSTALL`) + `docs/explanation/
   12-desktop-installer-and-sandbox-setup.md` update. Verify with the sandbox silent-test script
   (`scripts/release/sandbox-guest-silent-test.ps1`) in both checkbox states; load `/installer`.

## Acceptance criteria

- `node scripts/governance/run.mjs --gate operation-surface --mode gate`, `--gate wire`,
  `check-store-recoverability` green; `./gradlew.bat build -x test`; module tests for
  `adapters-lucene`, `app-services`, `ui`, `app-agent`.
- Live: forget a file → it disappears from search within one query; the conversation that cited
  it shows the unavailable state; export produces a Markdown file with the citation marks intact.
- Uninstall: both checkbox states verified in Windows Sandbox (record evidence paths in §Status).

## Constraints

- Do not touch encryption key handling (629) beyond calling the existing unlocked-state check.
- Do not change delete-detection cadence (626) — forget is explicit; sweeps stay as they are.
- Non-goals: relocation/portable mode, "forget" for agent memory (retired, 872), retention
  policy changes (812).

## Status

(unstarted)
