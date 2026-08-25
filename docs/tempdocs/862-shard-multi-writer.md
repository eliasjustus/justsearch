---
title: "862 — Observation shards: one file, many writers"
type: tempdocs
status: "DESIGNED + PLANNED (2026-08-25) — not implemented. Supersedes tempdoc 828 §A (same defect, chartered 2026-08-14, never started)."
created: 2026-08-25
updated: 2026-08-25
author: agent session bccfc163-7b8f-4b1a-b9e4-0c011632d8a1 (charter agent, 862)
category: infra / agent-tooling
related:
  - 618 §Seam C (per-session shards — the mechanism this repairs)
  - 665 (observation-shard-hint — durability nudge that reads the shard path)
  - 680 (conditions store — the fold's identity resolution)
  - 828 §A (identical defect, chartered and unstarted; §B stays with 828)
  - scripts/agent-analytics/note-observation.mjs
  - scripts/agent-analytics/fold-observations.mjs
---

# 862 — Observation shards: one file, many writers

## A. The defect

Tempdoc 618 Seam C moved inbox notes out of the shared `docs/observations.md`
into per-**session** shards (`docs/observations.d/<sessionId>.md`) on the
invariant *"two writers never touch the same bytes — clobber is impossible by
construction"*. That held for two humans-in-parallel sessions. It does not hold
under the delegate-everything model (`CLAUDE.md` → *Model routing*), because a
subagent inherits its parent's session id:

- Probed live in a subagent shell this session:
  `CLAUDE_CODE_SESSION_ID=bccfc163-7b8f-4b1a-b9e4-0c011632d8a1` — identical to the
  orchestrator's. `resolveSessionId()` (`note-observation.mjs:53-66`) is env-first
  by design (684), and its own doc comment states the inheritance explicitly:
  *"the child inherits the PARENT session's env, and attributing the note/link to
  the parent is the desired behavior there too."* Attribution: yes. Filename: no.
- Therefore the orchestrator **and every worker it spawns** append to one path,
  `docs/observations.d/<orchestrator-session>.md` — the orchestrator uncommitted in
  the main checkout, each worker committed on its own branch in its own worktree.
- Every catch-up `git pull` in the orchestrator's checkout then hits an add/add or
  modify/modify conflict on that file, resolved by hand (union + dedupe + a merge
  commit) — 5 of 5 pulls in the 859 wave.

**Primary evidence** (`git log --oneline -- docs/observations.d/bccfc163-….md`, main,
2026-08-25): seven worker PR squashes (#528 `163f5071`, #529 `356dbc1c`,
#530 `6995d620`, #532 `0c778cd1`, #533 `9591589f`, #534 `4172ca82`, #535 `7ff04ab1`)
and two orchestrator shard commits (`4fddc103`, `90c3cbf4`) all write the *same*
shard file, plus two catch-up merge commits on it (`11a62f46`, `2a99dcd6`).
Nine independent writers, one file, one 5-day wave.

**Not new, and not decaying:** tempdoc 828 §A chartered exactly this on 2026-08-14
after the 821 publication campaign (*"~10 manual union-resolutions in one day … plus
one modify/delete against the fold"*) and recorded the second-order harm: *"workers
began declining to log observations to avoid dirtying their PRs — the mechanism
designed to prevent knowledge loss started causing it."* 828 was never started. 862
supersedes 828 §A (828 §B, wire-gate additive-field blindness, is unrelated and
stays there).

Structural, 5/5 occurrence, mechanical but permanent → per
`structural-defects-no-repeat`, this is chartered on the defect, not on a
cost-benefit case.

## B. What the current mechanism actually guarantees (source-verified)

| Fact | Source |
|---|---|
| Shard path is `<sanitized session id>.md`, nothing else in the name | `note-observation.mjs:85-87` |
| Session id resolution order: `CLAUDE_CODE_SESSION_ID` → `JUSTSEARCH_AGENT_SESSION_ID` → `tmp/agent-telemetry/current-session-id` → `wt-<sha1(toplevel)[0:12]>` | `note-observation.mjs:53-66` |
| The fold **globs the directory** — any `*.md` except `README.md` is a shard; the filename is never parsed | `fold-observations.mjs:45-57` |
| The fold writes the store first, then `rmSync`s every consumed shard | `fold-observations.mjs:154-156` |
| Fold merge is exact-occurrence dedupe inside a matched condition (`seen`++ only when new) | `lib/observations-store.mjs:307-316` |
| An entry whose condition was retired/renamed since the fold does **not** dedupe — it opens a *new* condition | `lib/observations-store.mjs:263-301`, `319-341` |
| The fold refuses to run from a checkout behind `origin/main` (tri-state; indeterminate ⇒ proceed) | `fold-observations.mjs:82-124` |
| Only three call sites depend on the shard *name*: `note-observation.mjs` itself, `observation-shard-hint.mjs:39,87-90` (imports `shardPathFor`), and `note-observation.test.mjs` | grep `shardPathFor|SHARD_DIR` |
| `record-merge.mjs:41,190` and `preview-squash-message.mjs:15,341` import `resolveSessionId` **only** — they never touch shard paths | grep |
| `.gitattributes` today has no rule for `docs/observations.d/**` | `.gitattributes` (read 2026-08-25) |

**Harness identity, probed (not assumed):** a subagent's shell environment exposes
`CLAUDE_CODE_CHILD_SESSION=1`, `AI_AGENT=claude-code_2-1-236_agent`, `CLAUDE_PID`,
and the parent's `CLAUDE_CODE_SESSION_ID` / `JUSTSEARCH_AGENT_SESSION_ID`. There is
**no per-subagent identifier** in the environment. 828 §A option 1's literal
"session id + agent id" is therefore not implementable from env; `CLAUDE_CODE_CHILD_SESSION`
is a boolean shared by *all* workers of a session, so keying on it would still leave
every worker sharing one file. The one discriminator that is both available and
*causally aligned with the conflict* is the **writing tree**: `git rev-parse
--show-toplevel`. Two writers in the same worktree can never conflict (they append
to one file on one branch — that is not a merge event at all); conflicts arise only
across checkouts/branches, which is exactly what the toplevel distinguishes.

## C. Candidates

### C1 — `.gitattributes` union merge driver
`docs/observations.d/*.md merge=union`. Zero code. Hazards to resolve, not wave at:
(a) the double-fold trap (after `--apply` drains a shard, a union merge against a
pre-fold branch re-adds drained entries — the live near-miss in this arc would have
re-folded 57 entries); (b) interleaving/duplication when both sides append at the
same anchor; (c) merge drivers do not run in GitHub's server-side merge; (d) it
would also capture `README.md` unless excluded.

### C2 — per-writer (per-worktree) shard discriminator
`<sessionId>.<writer>.md`, orchestrator/main checkout keeps the bare name. Fold
needs no change (it globs). Hazards: shard-count growth, the `observation-shard-hint`
hook path, README/contract text, and any tooling assuming one-shard-per-session.

### C3 — hybrid (C2 primary + C1 as belt-and-braces).

Decision and the loser's failure mode: §D. Reach: §E. Plan: §F.

## D. Design

### D.0 The identity that was conflated

618 Seam C picked **session** as the shard key because a session is the unit of
*authorship*. But the property the shard file has to provide is *non-contention*,
and contention is not a property of authorship — it is a property of the tree the
bytes are written in. Two writers in one worktree cannot conflict (one file, one
branch, append-only); two writers in different worktrees always can. Session was
the right **attribution** key and the wrong **isolation** key, and the delegate
model made the difference observable by giving one session many trees.

The fix is therefore not a new mechanism. It is 618's own invariant, applied to the
unit that actually merges:

> **A per-X file that exists to prevent write contention must be keyed by the
> concurrency domain (the tree/branch that merges), not by the actor. Attribution
> belongs *inside* the file, not in its name.**

### D.1 Decision — per-writer shards, keyed by the writing tree (C2)

`shardPathFor(sessionId, root)` resolves a **writer discriminator** from the
checkout it is called in:

- **Home checkout** (`git rev-parse --git-dir` == `--git-common-dir`, i.e. `.git` is
  a real directory): no suffix — `<sessionId>.md`, byte-identical to today. Existing
  shards, existing history, and the orchestrator's file identity are untouched.
- **Linked worktree** (`--git-dir` ≠ `--git-common-dir`): `<sessionId>.<writer>.md`,
  where `<writer>` is the sanitized basename of the worktree toplevel — the names
  git already gives these trees: `agent-a5ec1173e17b193ab`, `859-sv3-live-findings`.
  A worker's finding becomes self-labelling as a side effect.
- **Indeterminate** (no git, tmp roots, the unit tests): no suffix. Fail-open to
  today's behaviour — a note is never dropped for lack of an identity, matching
  `resolveSessionId`'s existing fallback discipline (`note-observation.mjs:53-66`).

`git rev-parse --git-common-dir` is not a new probe: `resolveDefaultMergesPath`
(imported by `recover-merge-links.mjs:51`) already uses exactly this to distinguish
"the main checkout" from "whatever worktree you are in". This design conforms to
that seam rather than inventing a second worktree test.

The fold needs **no change**: `listShards` globs `*.md` minus `README.md` and never
parses the name (`fold-observations.mjs:45-57`).

### D.2 Why the union driver loses (C1), and its failure mode stated plainly

`docs/observations.d/*.md merge=union` is rejected — as the primary fix *and* as
belt-and-braces. Three reasons, in order of weight:

1. **It does not fire where the fix has to hold.** Merge drivers are a local
   `.gitattributes` facility; GitHub's server-side merge/squash does not run them.
   Local `git pull` conflicts would vanish; a PR whose branch and `main` both touched
   the shard would still be reported as conflicting to the merge queue.
2. **It is inert on the only conflict shape that needs judgment.** The fold *deletes*
   consumed shards (`fold-observations.mjs:155`). A branch cut before the fold that
   still carries entries therefore produces a **modify/delete** tree conflict — union
   is a content driver and never runs for delete/modify. C1 automates the easy
   conflicts and leaves the hard one exactly as it was.
3. **Where it *does* fire across a fold boundary, it is actively wrong.** Union
   cannot know an entry was already drained; it re-adds it. Byte-identical text under
   a still-matching condition is absorbed harmlessly (`mergeOccurrence` dedupes on
   the exact occurrence line, `lib/observations-store.mjs:307-316`) — but an entry
   whose condition was since retired, re-titled, or re-anchored by triage no longer
   matches and **re-opens as a new condition** (`matchGroup` / `newGroupFrom`,
   `:263-341`). The rule that held in this arc — *after a fold, keep ONLY unfolded
   entries* — is precisely the knowledge a blind driver cannot carry.

**Loser's failure mode, explicitly:** adopting C1 removes the *symptom* (the pull
conflict) while keeping the *cause* (one file, many writers). The visible pain signal
disappears, PR-level conflicts on that file survive, the post-fold modify/delete
still needs a hand, and on the one path where the driver does act across a fold it
silently resurrects drained entries and re-opens retired conditions — the 57-entry
near-miss of this arc, made automatic and unobserved. That is worse than zero:
a legible conflict traded for a silent wrong merge in the store the whole mechanism
exists to protect.

**No hybrid.** Adding a silent auto-resolver to a file class whose residual conflicts
require judgment is not defence in depth; it is a second authority over the same
bytes. After D.1, no two writers share a shard path, so there is nothing left for it
to resolve.

### D.3 What this displaces (named, and owned by this tempdoc)

- **Tempdoc 828 §A** — the same defect, chartered 2026-08-14, never started. It is
  superseded, not merely duplicated: its option-1 wording ("session id + agent id")
  is *not implementable* (see §B — the harness exposes no per-subagent id), and its
  option-2 (the union driver) is refuted in D.2. Tombstoning 828 §A with a pointer at
  862 is part of this work, not a later sweep (`retire-with-a-sweep`). 828 §B is
  unrelated and stays live.
- **The shard header text** written into every new shard — *"Per-session inbox shard
  … do not share with other sessions"* (`note-observation.mjs:100-104`) — states the
  invariant that just failed. It becomes per-session-per-tree.
- **`docs/observations.d/README.md`** — the contract doc, same correction, plus the
  post-fold resurrection rule (keep only unfolded entries) which currently lives
  nowhere.
- **`observation-shard-hint.mjs:26`'s doc comment** — "the CURRENT session's own
  shard" is now "this session's shard *in this tree*". No code change: the hook
  imports `shardPathFor`, so putting the discriminator inside that function keeps
  every consumer coherent by construction. Keeping the resolution inside
  `shardPathFor` (rather than at the call site) is load-bearing for exactly this
  reason.

### D.4 The consumer this breaks if unhandled (load-bearing)

`recover-merge-links.mjs` (tempdoc 856) recovers session→merge links **from shard
filenames**: `sessionId = file.slice(SHARD_PREFIX.length, -'.md'.length)` (`:99-101`),
gated by `isPlausibleSessionId` = `/^[A-Za-z0-9._-]{4,80}$/` (`merge-links.mjs:107`).
That alphabet **admits dots**, so `<uuid>.<writer>` does not get rejected — it gets
*accepted* and written into the ledger as a session id that never existed. The
failure would be a silently wrong attribution row in a measurement file a falsifier
reads, which is the exact class 856 was built to remove. So:

- The filename parse must strip the discriminator: the writer suffix is the **last**
  dot-separated segment; a name with no dot is a whole session id. (Session ids are
  UUIDs or `wt-<hex>` — no dots — so this rule is unambiguous for every id the
  resolver can produce, and it degrades to today's behaviour on legacy shards.)
- Side effect, in the right direction: per-writer shards mean a worker PR **adds** a
  new shard file instead of modifying the orchestrator's, so more commits qualify for
  856's single-shard recovery. Attribution is unchanged — the stripped key is still
  the parent session, which 856 §216 already defines as the accountable one.

### D.5 Scope boundary

Not in scope, deliberately: changing what the fold deletes (truncate-instead-of-delete
would trade a modify/delete conflict for a content one and re-introduce the drained-entry
question D.2.3 describes); adding a gate; touching `CLAUDE.md` or `.claude/rules/*.md`
(the always-loaded budget ratchet makes prose edits there a separate cost, and the
existing guidance — "writes to *your* per-session shard" — stays true under D.1; the
correction belongs in the contract doc that owns the mechanism).

### D.6 Acceptance

1. Two concurrent worktree branches each logging observations merge to `main` with
   zero manual conflict resolution (828 §A's own acceptance criterion, inherited).
2. `note-observation` in the home checkout still writes `<sessionId>.md` — no rename
   of any shard in flight.
3. The fold consumes dotted shards (glob unchanged, proven by test, not by reading).
4. `recover-merge-links` derives the *parent session id* from a dotted shard name,
   and a synthetic dotted name does not enter the ledger unstripped.
5. `observation-shard-hint` nudges on the worktree-suffixed shard it actually wrote.

## E. Reach — the principle, its scope, and its retirement condition

**Principle: key a contention-avoiding file by the concurrency domain, not by the
actor.** When a directory-of-files exists so that parallel writers never touch the
same bytes, the filename must vary with the thing that *merges* (tree/branch/unit of
work). Any identity that can span several merging units — a session, a user, a role —
is an attribution fact and belongs inside the file. A per-actor name only looks like
isolation for as long as one actor means one tree.

**Where the repo already agrees (positive instances, no work implied):**

- `gates/*/.changesets/` are named per tempdoc/topic (`842-chat-profile-key.md`) —
  i.e. per branch of work. Two branches never write one changeset file, and nothing
  had to be added to make that true.
- `tmp/agent-telemetry/` per-checkout files (`current-session-id`) live in the
  worktree that wrote them and are untracked, so they never merge at all.
- `session-merges.ndjson` is deliberately resolved through `--git-common-dir` to the
  *one* main-checkout copy (`recover-merge-links.mjs:45-50`): a single shared
  authority, explicitly not sharded — the opposite choice, made consciously, and
  correct because it is a derived cache outside git.

**Where the repo pays for violating it (named, not fixed here):** tempdoc numbers are
a globally scarce name assigned per branch, and cross-worktree collisions are caught
by a *gate* (`check-tempdoc-numbers`) rather than prevented by construction — and that
gate is known to miss the same-tree case (existing inbox observation, 2026-08-19: two
854-numbered tempdocs in one tree pass). That is the same shape one rung weaker: a
name that must be unique across merging units, guarded by inspection instead of by
key choice. Recording it; not building anything for it here.

**Evidence it earns its keep:** the next multi-worker wave merges with zero manual
shard conflict resolutions (against 5/5 in the 859 wave and ~10-in-a-day in the 821
campaign, both recorded in-repo), and workers resume logging observations rather than
declining to dirty their PRs (828 §A's recorded second-order harm).

**Retirement condition:** if the harness ever exposes a genuine per-subagent identity
*and* workers stop writing in separate trees, or if shards stop being tracked in git
(e.g. the inbox moves to an untracked spool), the discriminator is redundant
apparatus — delete it and return to the bare name. Equally, if a wave still produces
manual shard conflicts after this lands, the key is still wrong and the principle is
what failed, not the implementation.

## F. Plan

One PR, one worktree, no dev stack, no UI surface. Node-only; the whole change is
four source files, three test files and two docs. Everything below extends existing
structure — no new module, script, gate, or config file is created.

### F.1 Steps

**S1 — writer discriminator inside `shardPathFor`** (`scripts/agent-analytics/note-observation.mjs`)
- Add `resolveWriterSuffix({ root })`: returns `''` when `git rev-parse --git-dir` and
  `--git-common-dir` resolve to the same directory (home checkout) or when either
  probe fails (no git / tmp root); otherwise the sanitized basename of
  `git rev-parse --show-toplevel`. Reuse the module's existing `sanitizeId` and the
  `execFileSync` import already present (`:28`, `:60-62`) — no new dependency.
- `shardPathFor(sessionId, root)` composes `<sid>[.<writer>].md`. **Signature and
  export unchanged** — this is what keeps `observation-shard-hint.mjs:39,87-90`
  correct with no edit (D.3).
- Add an optional `writer` parameter (defaulted from the resolver) so tests can drive
  both branches without a git fixture, mirroring how `resolveSessionId` already takes
  `{ root, env }` for the same reason (`:53`).
- Update the shard header text (`:100-104`) and the module doc comment (`:5-16`,
  `:39-52`) from "per-session / do not share with other sessions" to
  per-session-per-tree, naming 862.

**S2 — filename parse in the 856 recovery** (`scripts/agent-analytics/recover-merge-links.mjs:96-102`)
- Strip the last dot-separated segment when the basename has one, before
  `isPlausibleSessionId`. Keep the existing reject path intact — a name that is still
  implausible after stripping stays a *reported rejection*, per 856 §7 ("a rejected row
  is a finding about the source, not noise"). This is the load-bearing edit of the PR
  (§D.4): without it the ledger silently gains rows keyed to a session that never
  existed, because `SESSION_ID_RE` (`merge-links.mjs:107`) admits dots.

**S3 — tests** (all three files already exist; extend, don't add new harnesses)
- `note-observation.test.mjs`: (a) home-checkout/indeterminate root → bare
  `<sid>.md` (asserts the no-rename guarantee, D.6.2); (b) explicit writer →
  `<sid>.<writer>.md`; (c) two writers, one session id → two distinct files, both
  appended independently — the invariant 618 claimed and this restores.
- `fold-observations.test.mjs`: a dotted shard name is discovered by `listShards` and
  consumed by `foldShards({apply:true})` (D.6.3 — proven, not assumed).
- `merge-links.test.mjs` / the recovery's own test: `parseCommitLog` on a dotted shard
  path yields the bare parent session id; an undotted legacy name is unchanged.

**S4 — contract docs**
- `docs/observations.d/README.md`: key-by-writing-tree contract, the naming shape,
  shard-count growth between folds is expected, and the **post-fold rule** that
  currently lives nowhere — *if a fold has drained the store and a merge resurrects a
  shard, keep only the entries not already folded* (D.2.3).
- `docs/tempdocs/828-shard-collision-and-wire-additive-blindness.md`: tombstone §A
  in place (status line + a "superseded by 862" note at the §A heading), leaving §B
  live. Same-PR teardown, per `retire-with-a-sweep` — 828's own frontmatter `status`
  must say §A is closed elsewhere so the file cannot read as an open charter.
- Not touched, deliberately (D.5): `CLAUDE.md`, `.claude/rules/*.md` (always-loaded
  budget), `fold-observations.mjs`, `observation-shard-hint.mjs` code.

### F.2 Validation

| Claim | Check |
|---|---|
| Node unit suites green (discovery is automatic — a new test file is in CI the moment it lands, `run-all-tests.mjs:12-17`) | `npm run test:agent-analytics` |
| Nothing else reads the shard name | re-run `grep -rn "shardPathFor\|SHARD_DIR\|observations\.d" scripts/ .claude/ governance/` and account for every hit |
| The real defect is gone (D.6.1) | live two-branch rehearsal: from the worktree, `node scripts/agent-analytics/note-observation.mjs "862 rehearsal"` → asserts a suffixed filename; commit it; `git merge` main (which carries a bare-named shard commit) → **no conflict**. This is the acceptance test the 828 charter wrote and never ran; a green unit suite alone does not establish it (`audit-driven-fixes-need-test`). |
| Fold still drains everything | `node scripts/agent-analytics/fold-observations.mjs` (dry run) in the worktree lists both the dotted and bare shards |
| Recovery unharmed | `node scripts/agent-analytics/recover-merge-links.mjs` (dry run is the default) before/after: candidate/reject counts move only in the expected direction, and no candidate carries a dotted `sessionId` |

### F.3 Sequencing, delegation, risk

- S1+S3a-c and S2+S3d are the two independent halves; S4 depends on neither. The whole
  bundle is one small delegable chunk — a single pinned worker with this section as the
  brief, not a fan-out (the spawn cost of splitting exceeds the work). The live
  rehearsal in F.2 and the merge decision stay with the orchestrator.
- **Risk — silent no-op:** if `resolveWriterSuffix` returns `''` everywhere (e.g. the
  git probe fails in the worker's environment), every test still passes and the defect
  survives. The rehearsal row in F.2 is the guard: it asserts on the actual filename
  produced in a real linked worktree.
- **Risk — legacy shards in flight:** shards already committed under bare names stay
  valid input to the fold and to the recovery (both paths are name-shape-tolerant after
  S2). No migration, no rename, nothing to backfill.
