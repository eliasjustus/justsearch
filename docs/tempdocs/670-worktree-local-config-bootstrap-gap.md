---
title: "Worktree local-config bootstrap gap: prepare-worktree.cjs documented the .mcp.json/settings.local.json seed step but never ran it"
type: tempdocs
status: "PR open, not yet merged (2026-07-02). Fix implemented and live-verified via `claude mcp list` (both the main checkout and a fresh worktree now report `justsearch-dev: ... Connected`); the one remaining verification step — confirming `mcp__justsearch-dev__*` tools actually appear inside a live agent session — needs a session restart and was not exercised. See §Remaining work."
created: 2026-07-02
updated: 2026-07-02
author: agent investigation + fix pass (triggered by agents reporting no access to the justsearch-dev MCP tooling; live repo inspection of .gitignore, ~/.claude.json, scripts/dev/prepare-worktree.cjs, .claude/rules/branch-safety.md, MAINTAINING.md, and git history of tempdocs 631/634 + PR #14)
related:
  - 631-go-public-publish-machinery   # the decision to gitignore .mcp.json/.claude/settings.local.json for the public repo
  - 634-go-public-cutover-transition  # where that exclusion actually executed against the live tree
  - 618-agent-developer-velocity-friction  # origin of prepare-worktree.cjs and the worktree-lifecycle conventions this bug lives in
---

# 670 — Worktree local-config bootstrap gap

## Context

Multiple agent sessions reported no access to the `justsearch-dev` MCP dev-tooling
server (`justsearch.dev.start`, `quick_health`, etc. — see
`docs/reference/contributing/mcp-dev-tools.md`). This tempdoc records the
investigation, the fix, what was verified, and what's still open.

## Root cause chain

1. **631/634 (2026-06-23 to 2026-06-25):** the go-public cutover deliberately
   `git rm --cached`'d `.claude/settings.local.json` and `.mcp.json` and
   gitignored both. Reason: `settings.local.json` advertised a permissive
   local permission posture, and `.mcp.json` carried a `GITHUB_PERSONAL_ACCESS_TOKEN`
   placeholder for the `github` MCP server — neither should ship in a public
   repo. 631 flagged at the time (finding "C4") that this would leave a
   contributor without the `justsearch-dev` tools the published docs/skills
   reference, and proposed a sanitized template or docs note as the follow-up.
2. **PR #14 ("maintainer re-wire seeds", 2026-06-30)** shipped that follow-up:
   `.mcp.json.example` and `.claude/settings.local.json.example` (both
   committed/tracked), plus a documented manual bootstrap step in
   `MAINTAINING.md`:
   ```bash
   cp .claude/settings.local.json.example .claude/settings.local.json
   cp .mcp.json.example .mcp.json
   ```
3. **The gap:** two places were never updated to match PR #14's fix:
   - `scripts/dev/prepare-worktree.cjs` — the script that's supposed to make a
     new worktree dev-ready — had a comment (its own header, lines 18-22)
     *describing* the copy step, but the script never executed it. Running
     the documented, expected workflow (`node scripts/dev/prepare-worktree.cjs`)
     still left a worktree without `.mcp.json`.
   - `.claude/rules/branch-safety.md` (an always-loaded rule file) still
     asserted the pre-cutover behavior verbatim: *"Config files
     (`.claude/settings.local.json`, `.mcp.json`) are tracked, so every
     worktree already has them."* This is false post-cutover, and it actively
     misled agents into believing no setup step was needed — which is
     probably why nobody noticed the `prepare-worktree.cjs` gap sooner.

An independent per-session observation (`docs/observations.d/357376d3-...md`,
2026-07-01, from an unrelated tempdoc-643 session) had already flagged the
same symptom in passing: *"This worktree has no .mcp.json (only
.mcp.json.example) ... contradicting branch-safety.md's claim that .mcp.json
is tracked."* That shard is not yet folded into `docs/observations.md`
(folding happens at merge time via `fold-observations.mjs`, a separate step)
— cited here as corroborating evidence, not resolved by this tempdoc.

## Investigation evidence

Confirmed directly (not inferred) before writing the fix:
- No `.mcp.json` existed in the checkout used for the investigation.
- `~/.claude.json`'s per-project `mcpServers` was `{}` for every local
  project, including this one — the server was not registered anywhere on
  the machine.
- The investigating session's own tool list contained no `mcp__justsearch-dev__*`
  entries.

## Platform-behavior finding (new, not previously documented anywhere found)

While testing the fix, a freshly created worktree (via the `EnterWorktree`
mechanism) turned out to already contain `.claude/settings.local.json` —
identical byte size and mtime to the base checkout's copy — even though
`git ls-files` confirmed the file is **not** git-tracked in that worktree.
So something (most plausibly worktree-creation tooling itself, not git)
copies certain gitignored config files from the base checkout into a new
worktree **if the base already has them at creation time**. `.mcp.json`
was *not* copied in that same test only because the base checkout didn't
have one yet to copy.

This is a single confirmed data point, not exhaustively verified (not
reproduced across multiple worktree-creation paths, and no published
documentation for this behavior was found). It matters because it means the
*old* "are tracked, so every worktree already has them" claim was
half-accidentally half-true for `settings.local.json` in practice (if the
base checkout happened to have one), which likely delayed anyone noticing
the gap. The fix below deliberately avoids asserting a mechanism this
tempdoc can't fully confirm, and instead states only the verified facts.

## Fix

Two files changed (PR: see repository history for this branch,
`worktree-mcp-bootstrap-fix`):

1. **`scripts/dev/prepare-worktree.cjs`** — added a seeding step that runs
   before the existing `npm ci`/Gradle steps: for each of
   `(.mcp.json.example → .mcp.json)` and
   `(.claude/settings.local.json.example → .claude/settings.local.json)`,
   copy the example to the destination only if the destination doesn't
   already exist (never overwrites a maintainer's customized copy). Logs
   what it did either way. Also reminds the operator that the
   `justsearch-dev` server needs no secret to work — only the `github`
   server needs a token filled in afterward.
2. **`.claude/rules/branch-safety.md`** — replaced the false "are tracked"
   sentence with: these files are gitignored, not git-tracked; whether a new
   worktree starts with them depends on the base checkout's state at
   creation time, so don't rely on it; `prepare-worktree.cjs` now seeds
   whatever's missing, so it's always safe to run.

## Verification

- Ran `node scripts/dev/prepare-worktree.cjs --no-dist` in the worktree with
  both target files removed — confirmed both were seeded from their
  `.example` files.
- Re-ran with a sentinel string appended to `.mcp.json` — confirmed the
  second run left the file untouched (no clobber).
- `node -c scripts/dev/prepare-worktree.cjs` — syntax check passes.
- **End-to-end confirmation via `claude mcp list`** (the CLI's own MCP
  discovery/health-check, independent of any single session's in-memory tool
  list): after seeding, both the main checkout and the worktree report
  `justsearch-dev: node scripts/dev/justsearch-dev-mcp.mjs - ✔ Connected`.
  This is the strongest evidence available that the fix actually restores a
  working server registration, not just that a file got copied.
- One test artifact was caught and corrected during verification: the
  sentinel-corruption test above briefly left an invalid `.mcp.json` in the
  worktree (`claude mcp list` surfaced it as a JSON parse error); this was
  test residue from manually appending to the file, not a defect in the fix,
  and was cleaned up by re-seeding a clean copy.

## Remaining work / open questions

- **Not yet verified:** `mcp__justsearch-dev__*` tools actually appearing in
  a *live* agent session's tool list. MCP connections are established once
  at session start and don't refresh mid-session, so this couldn't be
  exercised from within the same session that made the fix. Whoever picks
  this up next should start a fresh session in a checkout/worktree that has
  gone through `prepare-worktree.cjs` (or a manual seed) and confirm the
  tools are offered.
- **PR not yet merged** at the time of writing — the usual pre-merge gate
  (`./gradlew.bat build -x test`) has not been run against this branch.
- **The undocumented worktree-creation copy behavior** (see above) is only
  confirmed for one file, one time. If another agent reproduces or falsifies
  it, that's worth writing up somewhere more permanent (e.g.
  `.claude/rules/agent-lessons.md`'s platform-constraints list) rather than
  leaving it only in this dated tempdoc.
- **The observation shard** (`docs/observations.d/357376d3-...md`) that first
  flagged this symptom belongs to a different session and was intentionally
  left untouched; it will be folded into `docs/observations.md` at the usual
  merge-time step, independent of this tempdoc.
- **Not investigated:** whether a completely fresh `git clone` (no prior
  worktree, no prior main-checkout bootstrap) behaves identically to the
  worktree case tested here. The fix should cover it in principle (it seeds
  from `.example` unconditionally, regardless of what the base has), but
  this wasn't separately exercised against a true fresh clone.
