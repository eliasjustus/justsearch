---
title: "606 — Dev-stack ownership coordination: a presence-aware verdict and its autonomous acting layer (queue · reaper · handoff)"
type: tempdoc
status: implemented
created: 2026-06-17
updated: 2026-06-18
category: multi-agent coordination / dev-runner / lease semantics / develocity
related:
  - docs/tempdocs/271-backend-lifecycle-isolation.md (foundation ownership contract — done)
  - docs/tempdocs/542-operation-scoped-lease-taxonomy.md (op-criticality layer — done)
  - .claude/rules/branch-safety.md §Shared Dev Stack
  - scripts/dev/dev-runner.cjs (lease + admission machinery)
  - scripts/dev/justsearch-dev-mcp/server.mjs (MCP ownership projection)
  - docs/observations.md (unguarded-mutation finding, 2026-06-17)
---

# 606 — Dev-stack ownership coordination: a presence-aware verdict + autonomous acting layer

> Design / exploration tempdoc. No code lands until the user approves a
> direction. This round is the long-term design theory, scoped to the
> problem the system actually has.

## Scope

The user's ask: let an agent **correctly decide whether it may take over the
single shared dev stack** — tell a stack someone is *actively using* apart
from one someone *walked away from*. The long-term ambition of N parallel
stacks is explicitly **out of scope**; so is building new *enforcement*
machinery (see §Deliberately out of scope).

The design has two layers. A **sensing layer** (the verdict authority,
§The long-term design) fixes the verdict an arriving agent receives — today
it is wrong/ambiguous, so the agent either bothers the user needlessly or
risks a bad takeover. An **acting layer** (queue · reaper · handoff,
§The acting layer) resolves the three verdicts that the sensing layer can
only dead-end into a manual or leaky outcome. Sensing says *what is true and
what to do*; acting *does it autonomously*.

## What already exists (investigated, not assumed)

Two leases, post-271/542:

| Lease | File | Writer | TTL | Answers |
|---|---|---|---|---|
| Routine session lease | `tmp/dev-runner/active.json` | `dev-runner.cjs` supervisor | 30s, renewed every 10s | "is the stack owned right now?" |
| Operation leases | `active.json.opLeases[]` | Java Head | `min(max(expected·2,60s),3600s)` | "is irreversible work in flight?" |

**The enforcement boundary is narrower than the model implies.** A full audit
of the MCP tool surface (`server.mjs`) + runner (`dev-runner.cjs`) confirms
that *ownership gates exactly one operation*: `start` (spawning a stack), via
the admission lock + lease/PID staleness + takeover policy. Everything else —
all reads (`quick_health`, `status`, `search_query`, `fetch_api_json`,
`tail_log`), **index mutation** (`ingest`), arbitrary allowlisted writes
(`api_call` POST/DELETE → reindex / GC / migration / worker-restart),
`ai_activate`, and `reload` (hot-reload bytecode) — operates against whatever
stack is listening on the resolved port, regardless of holder. `callerIsOwner`
/ `leaseFresh` are computed and *surfaced* but never *enforced*. (`stop` has a
session gate in the runner, but the MCP layer substitutes the holder's id
before calling it, so the gate never fires on the MCP path.)

The live `active.json` (inspected) confirms the holder block today is just
`{ source, agentSessionId }` — **no owner PID, no build provenance** — and the
lease carries only the supervisor's renewal timestamps.

Implication for this design: **the right enforcement surface (`start`) is
already the only one gated, and that is sufficient for a single-user local
dev tool.** The defect is not "too little enforcement." The defect is that the
*sensing* feeding the one gate (and the advisory surface) is wrong on three
counts.

## Diagnosis: three sensing defects

### D1 — "Fresh" means process-alive, not owner-present

`acquireAdmission` (`dev-runner.cjs:700-710`) computes staleness from the
**supervisor PID** (`isPidAlive(runnerPid)`) and lease expiry. The supervisor
is a detached process that renews the 30s lease every 10s for as long as the
backend/frontend live (`dev-runner.cjs:1361-1379, 1394`). It has **no link to
the Claude session that started it.** So `leaseFresh: true` stays true forever
while the backend survives — even after the owning session ended, crashed,
compacted, or went idle for hours. An arriving agent literally cannot
distinguish "owner is actively working" from "owner left a zombie stack
running." This is the core ambiguity the user named.

This is orthogonal to 542. 542 answers *"should I interrupt this work?"*
(don't truncate a migration) and assumes the holder is alive. D1 is *"is there
anyone there at all?"* A complete takeover decision needs both.

### D2 — The verdict is blind to build provenance

`active.json` records `repoRoot` but **not the git commit / worktree the
running dist was built from.** In a parallel-agent shop every session is a
different worktree at a different HEAD. An agent that inherits or uses a
running stack inherits a backend built from *someone else's code* — the
documented "dev stack runs stale jar" pitfall (CLAUDE.md Common Pitfalls):
wire payloads reflect the other agent's code and the user draws wrong
conclusions. The verdict can't warn about this because the fact isn't on the
lease.

### D3 — The verdict is derived three times, independently

The "is this a conflict, and what should I do?" judgment is re-derived in
three places that can disagree: the MCP `start` proactive pre-check
(`server.mjs:418-447`), the runner's `acquireAdmission`
(`dev-runner.cjs:660-865`), and the MCP `quick_health`/`status` advisory
projection (`server.mjs:1430-1521`). This is exactly the multi-derivation
drift the codebase elsewhere designs out with single-authority/single-
derivation gates (e.g. `verdict-derivation.v1.json`). The ownership verdict
has no such single authority.

## Evidence from the field (3 sessions, 2026-06-17)

Three consecutive agent sessions from 2026-06-17 were analysed; **all three**
hit the defects above, with the same root signature. This is proof-by-example
of the bug *class*, not one incident.

**The cross-cutting finding (D1 + reaper + queue in one observation).** The
*same* holder — session `b9e86b32`, `holder.source: "unknown"`,
`aiActive: null` the entire time, lease sequence ticking 169 → 531 — held the
stack across all three sessions over ~1 hour. Its supervisor was renewing
(so `leaseFresh: true` always), but it was never observed *using* the stack
(`aiActive: null` throughout). It is almost certainly an **idle/abandoned**
stack. Yet because presence is unobservable (D1), it serially blocked three
independent agents, each of which (a) abandoned its live-verification tier,
and (b) required a **human to type "takeover and proceed"** to proceed. One
likely-abandoned stack → three forced human round-trips. A presence signal
would have shown it abandoned; a reaper would have freed it; a queue would
have removed the human round-trips.

- **Session `2a60df9b`** (598/606 UX work): 3 separate blocking pivots away
  from live repro to static/read-only workarounds; could not reproduce its
  degraded-state scenarios at all; unblocked only by a user interrupt +
  "takeover and proceed". Then **D2**: had to stop the stack, rebuild from its
  worktree, manually copy 170 MB of `.dev-data`, and clear a stale lock —
  because nothing told it the running stack was built from `main`.
- **Session `21442124`** (603 RAG work — *the session that created 606*): the
  entire investigation was forced static; the live oracle (U7) was left
  unverified; a loud "⚠️ GOAL NOT FULLY ACHIEVED" stop was emitted; unblocked
  only by user "takeover and proceed". Then **D2**: the agent had to *deduce*
  "the dev-runner is main-bound, so it runs main's code, not my fix" and
  settle for a client-side-injected FE-only verification.
- **Session `80a60555`** (600 C-2 work): task briefly declared *unachievable*;
  unblocked by user "takeover and proceed"; then a **~15-turn stale-jar
  marathon** (D2, the most expensive single incident across all three) — the
  agent took over believing it would run its own code, the catalog fetch hung
  against the killed old backend, and classpath archaeology finally revealed
  the mechanism (see sharpened D2 below). It hand-copied worktree jars into
  main's dist, then restored them, then a *third* owner (`bcd7ae1f`) churned
  in — ownership transferred across three sessions, every transition manual.

Two corroborating meta-signals: the D1/queue gap is now so well-known that a
**task author hand-coded it into the prompt** ("if the stack is owned, do NOT
take over — STOP and post a ⚠️ warning") — the system has no primitive for it,
so it leaks into instructions. And in `80a60555`, after takeover,
`callerIsOwner: true` was reported while the live backend was still the
*killed* old one (D3: the lease verdict disagreed with on-the-wire reality;
`source: "unknown"` / `confidence: "medium"` in every reading).

### D2 sharpened by the field: the dist-path coupling

The `80a60555` root-cause is more specific than "no provenance on the lease,"
and it tightens Piece 2. The MCP dev-runner launches the backend from the
**main checkout's** dist (`F:\JustSearch\modules\ui\build\install\ui\bin`),
**not the starting worktree's** dist — so a worktree agent's freshly-built
jars never load even after a clean takeover + `installDist`. Provenance is
therefore not merely *unrecorded*; the running stack is structurally bound to
`main`'s dist regardless of who started it. Piece 2 must record the **dist
path + commit actually launched** (not the starting worktree's HEAD), so the
verdict reads "running `main`@<commit>, your code is in worktree-X — rebuild
or relaunch" up front, instead of after a 15-turn hunt. Whether the runner
*should* launch from the owning worktree's dist is a deeper question flagged
in §Open questions — but even without changing launch behaviour, recording
the true running dist closes the silent-wrong-results gap. (Logged 2026-06-17
to `docs/observations.md`.)

## The long-term design: one presence-aware ownership verdict

The correct structure is **not** more leases, a queue, or per-tool
enforcement. It is to make the *one judgment* an arriving agent needs —
"what should I do about this stack?" — a **single derived verdict** computed
from the full set of facts, enforced at the one gate and surfaced verbatim on
the advisory surface. Three additive pieces, all extending what exists:

### Piece 1 — Presence as a join against the session-activity authority (fixes D1)

The first draft proposed recording the owning Claude session's **PID** and
checking `isPidAlive`, mirroring the supervisor check. Investigation kills that
mechanism and replaces it with a better one.

**The Claude process PID is not obtainable.** It is in no hook stdin payload,
no env var, and a hook subprocess's `process.ppid` is the node-launcher's
parent, not a stable Claude PID (`scripts/agent-analytics/lib/hook-base.mjs`,
`hooks/dispatch.mjs`). The only PIDs in the system are the dev-runner's own and
the backend children. The `pid + startedAt` presence check cannot be built. But
the PID was never the right signal, and it is not needed.

**What is reliable, and already on disk:**
- **The session id in the lease is trustworthy.** The `mcp-session-inject` hook
  injects the true per-call `session_id` into every `justsearch-dev` MCP call —
  purpose-built to close the same-CWD multi-agent gap
  (`scripts/agent-analytics/hooks/mcp-session-inject.mjs`). So
  `holder.agentSessionId` is a sound join key. (The field's `source: "unknown"`
  is only the broken-on-Windows env-var path — the id itself is correct, so this
  is a red herring for identity.)
- **Two activity signals already exist, both keyed by session id, both written
  by hooks that already fire:**
  - *General activity* — `tmp/agent-telemetry/turn-count-<id>.txt`, whose mtime
    is rewritten on **every** tool call (PreToolUse → `dispatch.mjs`). Fresh
    whenever the session is doing *anything*.
  - *Dev-stack activity* — one new stamp `dev-stack-touch-<id>`, written by
    extending `mcp-session-inject` (which already fires on exactly
    `mcp__justsearch-dev__*` with the session id in hand). Fresh only when the
    owner actually touches the stack.

Presence — and the two grades the critical pass demanded — fall out of joining
these against the lease, **with no PID at all**:

| general-activity | dev-stack-activity | grade | verdict |
|---|---|---|---|
| fresh | fresh | owner actively using the stack | `CONTENTION` |
| fresh | stale | owner alive, **not using** the stack | `IDLE_HOLD` |
| stale | stale | owner gone / silent | `TAKEOVER_ABANDONED` |

This is strictly **better** than the PID model. PID-liveness could only say
alive/dead, and would have mis-called the dominant field case — a live owner
doing an hour of static work — as `CONTENTION`, still bouncing to the user. Two
activity timestamps separate "working but not on the stack" from "actively on
the stack" from "gone" — exactly the distinction the `b9e86b32` incident needed.
The keystone risk is thereby resolved by *existing* infrastructure, not by a
PID we cannot get.

**Placement is the load-bearing decision (single-authority).** Session liveness
is owned by **agent-analytics** — the one system with reliable session identity
that already writes per-session activity — *not* by the dev-runner. The
ownership verdict (Piece 3) and the supervisor's reaper tick (acting layer)
*consume* it by joining `holder.agentSessionId` against these files; the
dev-runner never re-derives session presence. This mirrors **tempdoc 605**'s
resolution of the single-live-run invariant: make the invariant structural by
deriving it from one identity authority plus exactly one genuinely-new datum
(there, the ceremony's owning-run id; here, the `dev-stack-touch` stamp).
`SessionEnd` may also write an explicit `endedAt` for instant clean-exit
detection, but it is documented as droppable (`compact-restore.mjs`) — so
activity-staleness is the authority and the end-marker is only an accelerator.

**Mechanism note (see §Prior art).** The *abandoned* case (general-activity
stale) is best handled not by a presence field the verdict reads, but by making
the supervisor's renewal itself activity-contingent — it stops renewing when the
owner goes silent, so the lease expires and today's `stale → reclaim` path
fires. The two-signal activity-join above is then strictly required only for the
`IDLE_HOLD` grade (owner alive but not *using* the stack), which expiry cannot
express. Same signals, simpler wiring for the common case.

### Piece 2 — Record build provenance on the lease (fixes D2)

The lease records which worktree + git commit the running dist was installed
from. This is a fact already determinable at spawn (the runner already walks
the worktree's `.git`); it simply isn't persisted. With it, the verdict can
compare the running dist against the arriving agent's HEAD and flag
`rebuild-first` when they differ — turning a silent wrong-result class into an
explicit signal.

**Lease-claimed provenance is not enough on its own — cross-check the wire.**
The `80a60555` incident showed `callerIsOwner: true` reported while the live
backend on the port was the *killed old one* (Vite proxying to a dead
backend). That is the verdict disagreeing with on-the-wire reality, which a
lease-only provenance field would not catch. The robust form has the backend
**self-report its build commit** (e.g. on `/api/status`) and the verdict
cross-check lease-claimed vs backend-reported provenance. This closes both the
stale-jar case (lease says worktree-Y, backend reports `main`) and the
dead-backend case (backend doesn't answer / reports a different run) with one
check.

### Piece 3 — One verdict authority consumed by gate + advisory (fixes D3)

A single pure function maps the full fact set —
`{ custody (self/other), supervisor-liveness, owner-activity (general +
dev-stack, Piece 1), critical-op (542), provenance (Piece 2) }` — to one
**prescriptive verdict** with a reason. Both consumers
read it: `start` *enforces* it; `quick_health`/`status` *surface* it verbatim.
Because the runner (.cjs) and MCP server (.mjs) both need it, it lives as one
shared module they both import — the single-derivation shape the codebase uses
everywhere else.

The verdict enum (the prescriptive recommendation the agent acts on):

| Verdict | When | Agent action | Prompt user? |
|---|---|---|---|
| `USE` | self & running — or any read-only need (reads are unenforced & safe) | use it directly | no |
| `RECLAIM_DEAD` | supervisor dead / lease expired | start fresh (existing stale path) | no |
| `TAKEOVER_ABANDONED` | running, **owner silent** (general-activity stale) | self-serve takeover | no |
| `IDLE_HOLD` | running, owner alive (general-activity fresh) but **dev-stack-activity stale > threshold**, no critical op | takeover-with-notice (notify displaced owner via epoch; they can re-queue) | no (or soft) |
| `CONTENTION` | running, owner alive **and recently using the stack** (both fresh), no critical op | real conflict — surface owner context, decide | **yes** |
| `WAIT_CRITICAL_OP` | running, owner present, 542 critical op | 542 handshake / wait | yes |

A `rebuild-first` flag (Piece 2) overlays `USE` / `TAKEOVER_ABANDONED` when the
running dist ≠ the agent's code.

Two consequences worth stating because they resolve real confusion today:

- **Reads never need takeover.** Since read tools already work against any
  running stack, the verdict for a read-only need is always `USE`. The current
  guidance ("OWNER_CONFLICT → ask the user") wrongly implies reads are blocked;
  the verdict makes explicit they are not. This is a *guidance/derivation* fix,
  **not new structure** — the capability already exists.
- **The user is interrupted only for genuine contention** (`CONTENTION` /
  `WAIT_CRITICAL_OP`), which — once D1 is fixed — is the rare case, not the
  default.

### Composition with 542

606 supplies the *presence/usage* axis (self / silent / idle-hold / actively-
using); 542 supplies the *criticality* axis (none / must-complete / unsafe).
The verdict is their product. No 542 behavior changes — 606 only splits the
formerly-undivided "not stale, not me" cell into `TAKEOVER_ABANDONED` /
`IDLE_HOLD` / `CONTENTION`, and the critical-op rows defer to 542's existing
handshake.

## The acting layer: making three dangling verdicts autonomous

The verdict authority is *sensing*. But three of its verdicts dead-end into
an outcome the sensing layer cannot itself resolve:

- `CONTENTION` / `WAIT_CRITICAL_OP` → "ask the user, then poll and retry by
  hand." The agent has no mechanism to *wait and be granted*.
- `TAKEOVER_ABANDONED` **with no arriving taker** → nothing reclaims the
  zombie; it holds VRAM / RAM / ports indefinitely.
- Any **involuntary custody change** (abandoned-reclaim, or a user-approved
  takeover of an idle owner) → if the displaced owner returns, it silently
  operates against a stack it no longer owns / that now runs different code.

These are not three subsystems. They are the **three transitions out of the
verdict states** that sensing leaves dangling — and they share one data
model and one actor.

### Shared model — ownership as an epoched episode with waiters

Three additive fields on the lease complete the model:

- **`ownershipEpoch`** — a monotonic id that bumps **only on custody
  transfer**, distinct from the per-10s `lease.sequence` (which bumps on
  every renewal). "Did ownership change?" is `epoch-then ≠ epoch-now`. This
  is the entire basis of notification.
- **`waiters[]`** — an ordered list of intents-to-acquire
  `{ agentSessionId, intent, enqueuedAt }`. Piece 1's activity-join keeps it
  honest: a waiter whose own session has gone silent is dropped (no PID needed).
- **`grant`** — the front waiter, reserved, with its **own short TTL** so a
  waiter that reserves but never starts cannot deadlock the queue (TTL lapse
  → next waiter).

### Shared actor — the supervisor's existing tick

Reaper and queue need an actor that **outlives any single agent session** and
**owns the backend child processes**. That actor already exists and already
runs a loop: the detached supervisor's 10s renewal interval. The correct
design hosts all acting there — no new daemon, no new IPC channel. Each tick
the supervisor already renews the lease; it additionally (a) evaluates owner
**presence** (Piece 1); (b) if a release / op-clear / abandonment makes the
stack available **and `waiters[]` is non-empty**, promotes the front waiter
to `grant` (bumping `epoch`); (c) if **abandoned beyond grace with no eligible
waiter**, self-terminates — killing its children, the thing it already does
on exit — with a `reaped_abandoned` disposition.

### A1 — Wait-queue (continuation of `CONTENTION` / `WAIT_CRITICAL_OP`)

"Acquire" does **not** mean spawning into another agent's worktree — the
supervisor cannot do that. It means **reserving admission**: the front waiter
is told "the gate will now admit you," and the waiter runs its own `start` in
its own worktree, conflict pre-cleared. An `acquireWhenFree(timeout)`
replaces "ask human + manual retry" with "enqueue → blocked until granted
(release / abandonment / 542 op-clear) → start." On timeout it falls back to
the *existing* escalation (ask user, `force` per 542) — so the queue is a
convenience over the escalation path, not a replacement, and needs no
priority/preemption of its own (`force` is the escape hatch, so no
starvation-prevention machinery is required).

### A2 — Reaper (continuation of `TAKEOVER_ABANDONED` with no taker)

Presence-absence *detects* abandonment; the reaper *acts* on it. A zombie
stack on a memory-pressured single-GPU host is a real cost (only one
inference runtime fits at a time). The supervisor self-terminates an
abandoned stack after a grace period — **unless** a queued waiter can inherit
it. Grant-to-a-same-code waiter is a *warm handoff* (seconds, vs ~1 min
cold); grant-to-a-different-code waiter is "reap now + signal the waiter to
start." Reap-on-timeout and reap-on-handoff are the **same action**, different
trigger — which is precisely why hosting both in the supervisor tick is the
natural structure, not a coincidence.

### A3 — Notification (continuation of involuntary custody change)

Not a push — 542 rightly rejected push-as-mechanism because a push can be
missed. The robust form is **pull-at-next-action**: an agent stamps the
`ownershipEpoch` it acquired at; its next MCP call (or a SessionStart /
PreToolUse hook) compares stored vs current. If it changed and the holder is
no longer self, surface "your dev stack was {reaped | taken over by session
X} at T (now running worktree-Y@commit)." A returning idle owner thus
*cannot* silently act on a stack it lost — the stamp is read exactly when it
would otherwise act on the stale assumption.

### How the acting layer composes

- With **provenance (Piece 2):** every grant/handoff consults it — warm
  inheritance only when the running dist matches the waiter's code, else
  reap-then-cold-start or a `rebuild-first` grant. Provenance is what keeps
  the queue/reaper from manufacturing stale-jar bugs.
- With **542:** the grant trigger for `WAIT_CRITICAL_OP` is the op-lease
  clearing; `force` remains the preemption escape hatch, so the queue adds no
  parallel preemption logic.
- With the **sensing layer:** acting changes only what happens *after* a
  verdict. The verdict enum and its derivation are unchanged.

## Prior art & positioning

A web survey (2026-06) confirms every *primitive* here is established
distributed-systems prior art, that 606 independently converged on the
battle-tested shapes, and that no project was found doing 606's specific
combination. Mapping:

| 606 concept | Established prior art |
|---|---|
| 30s lease + heartbeat renewal | classic lease pattern (Kleppmann "How to do distributed locking"; DynamoDB Lock Client) |
| `ownershipEpoch` (bumps only on transfer; read at next action) | **fencing token** / Kubernetes leader-election `Lease.leaderTransitions` — "an integer that increases by 1 every time a Lease changes hands" |
| reaper (free on absence) | lease-expiry auto-free ("the clock answers for you") + ephemeral-sandbox idle/timeout auto-destroy (Northflank, Bunnyshell, Ramp) |
| wait-queue / `acquireWhenFree` | lock-based task claiming (already used in Claude-agent setups) |
| holder identity + provenance on lease | k8s `Lease` `holderIdentity` / `acquireTime` |

Two findings worth carrying:

**The industry's dominant answer is isolation — exactly what 606 defers.** For
parallel AI coding agents specifically, the field overwhelmingly avoids the
shared-resource problem rather than coordinating it: three-layer isolation
(worktrees + DB branches + **port namespacing** so agents never fight over a
dev-server port) and per-agent **ephemeral sandboxes/microVMs** that
auto-destroy on idle. Coordination that exists is at the *file/task* layer
(shared task lists, hook-enforced file-ownership maps, lock-based task
claiming), not the running-stack layer. This is strong external evidence that
the long-term arc bends toward 606's deferred parallel-stacks ambition — and
that 606's shared-stack coordination is the *bridge*, valuable precisely while
one stack is still shared.

**606's one genuinely non-standard problem is the decoupled renewer.** In every
standard lease system the *holder renews its own lease*, so "stopped renewing
== holder gone" and the clock settles abandonment with no liveness debate.
606's D1 exists only because the renewer (the detached supervisor) is
**decoupled from the principal** (the agent session) — so the lease never
expires on owner death and freshness is a *false* liveness signal. The
literature's own prescription — "piggyback the lease on the heartbeat the node
was already sending for liveness" — points at a structurally simpler variant of
this design, below.

### Alternative worth weighing: a presence-aware renewer

Instead of (Piece 1) recording presence + (Piece 3) a verdict that joins it,
**make the supervisor's renewal itself contingent on owner liveness**: each tick,
the supervisor renews the lease *only if* the owner's general-activity signal is
fresh; when the owner goes silent, it simply stops renewing. The lease then
expires on owner-death exactly like a standard self-renewed lease, and the
*existing* `stale → reclaim` admission path resolves abandonment — **no new
presence field, no new verdict branch** for the abandoned case. This re-couples
renewal to the principal, which is what standard leases get for free and this
architecture broke.

What this collapses vs. what it keeps:
- **Collapses:** `TAKEOVER_ABANDONED` folds into today's stale-reclaim; the
  reaper becomes "supervisor stops renewing → lease expires → next arrival
  reclaims," needing far less new logic.
- **Still needs the two-signal split (Piece 1):** `IDLE_HOLD` (owner alive but
  not *using* the stack) is not an expiry case — a live owner doing static work
  must keep the lease alive, so distinguishing idle-use from gone still requires
  the general-vs-dev-stack activity grade.
- **Still needs Pieces 2–3 + epoch:** provenance, the prescriptive verdict, and
  the fencing-token/notification remain.

Recommendation: treat the presence-aware renewer as the **preferred mechanism
for the abandoned case** (it is simpler and aligns with proven lease practice),
and keep the activity-join only where expiry cannot speak — the `IDLE_HOLD`
grade. This is a mechanism refinement, not a scope change; the verdict surface
and acting layer are unaffected.

## Why this scope is right

- **It extends 271, 542, and the agent-analytics hooks; it replaces nothing.**
  Every existing lease field, disposition, and the 30s routine lease survive.
  The lease gains provenance, an ownership epoch, and a waiters/grant queue;
  **session presence is an external join against activity signals
  agent-analytics already writes** (one new `dev-stack-touch` stamp); one shared
  verdict function replaces three ad-hoc derivations. The dev-runner gains no
  responsibility for session identity — that stays with the system that already
  owns it.
- **It matches the problem.** The design invests in *sensing* (presence,
  provenance, a single honest verdict) and in *acting* on what it senses
  (queue, reaper, handoff) — but not in new *enforcement* (per-tool gating)
  the single-user dev tool doesn't need. Each acting feature closes a specific
  transition the verdict dead-ends into; none adds capability for a case the
  problem doesn't have.
- **The acting layer reuses the one process built for it.** Reaper and queue
  live in the supervisor's existing tick — the only process that outlives
  sessions and owns the child processes — and notification is a stamp read at
  next action. No new daemon, no new channel, no push that can be missed.
- **It applies the codebase's own principle.** Single-derivation of a verdict
  consumed by multiple surfaces is the exact pattern the governance gates
  enforce elsewhere; the ownership verdict is currently the odd one out.
- **It is the load-bearing prerequisite for the parallel-stacks ambition.**
  You cannot safely recycle/inherit stacks at scale until you can tell a live
  owner from an abandoned one and know what code a stack is running. Presence +
  provenance are that foundation.

## Critical self-assessment: would this resolve the field evidence?

Honest per-issue verdict against the three transcripts. It does **not** resolve
everything.

| Observed issue | Resolved? | By what |
|---|---|---|
| Dead/ended owner blocks an arrival | **Yes** | Piece 1 presence → `TAKEOVER_ABANDONED`, auto |
| Abandoned stack leaks resources ~1 hr | **Yes** | reaper (supervisor self-terminates on absence) |
| Displaced owner never told | **Yes** | epoch stamp, pull-at-next-action |
| Human "takeover and proceed" round-trip | **Partly** | queue removes it *if* the owner releases or is absent; a live owner who never releases still needs escalation |
| **Live-but-idle owner holds the stack** (likely the dominant case here) | **Yes** | the two-signal activity-join (Piece 1): general-fresh + dev-stack-stale → `IDLE_HOLD` |
| Stale-jar diagnosis cost (~15 turns) | **Yes, as diagnosis** | provenance + wire cross-check make it a single up-front check |
| Actually running a worktree's code on the shared stack | **Small fix** | already works from the worktree CLI today; via MCP needs only a per-call `repoRoot`/`distFrom` override (§Open questions 3) |
| `callerIsOwner: true` while backend is the killed old one | **Yes, with the wire cross-check** | backend self-reported commit vs lease |
| `source: "unknown"` holders | **Yes** | identity comes from the `mcp-session-inject`-injected session id, not the lease's `source` field |

After this investigation pass, the keystone is **resolved** and the launch gap
**shrunk**. The honest residuals are now two, both bounded:

1. **Hook-execution reliability (the new keystone dependency).** Presence now
   rests on the agent-analytics hooks reliably writing the activity signals
   (`turn-count` mtime + the new `dev-stack-touch` stamp). If a hook silently
   fails to fire, a live owner can look silent (false `TAKEOVER_ABANDONED`).
   This is a *bounded, monitored* dependency — tempdoc 592 (`hook-execution-
   integrity`) is the relevant guard — not the open-ended "can we even get the
   signal" risk the PID approach carried. The failure mode is also self-
   correcting: a falsely-reaped owner is notified via the epoch (Piece A3) and
   re-queues.

2. **The idle-hold tension.** `IDLE_HOLD` takeover risks *stealing a warm stack
   from a patient owner* — the analysed sessions did static work *first* and
   needed the stack *later*; granting their idle stack away right before they
   reach live-verify just moves the friction onto them. This is why `IDLE_HOLD`
   is takeover-*with-notice* + epoch + re-queue, not silent, and why the
   dev-stack-idle threshold must be generous. It is the one genuine policy
   trade-off the design admits; it is feasible (the signal exists), but the
   threshold is a judgement, not a default.

Net: the design now **fully** resolves the dead-owner, idle-holder, reaper, and
notification cases and the D2/D3 *diagnosis* cost; the worktree-code launch is a
small, already-CLI-supported plumbing fix; and the only standing risks are hook-
execution reliability (bounded, self-correcting) and the idle-threshold
judgement.

## Deliberately out of scope (and why)

- **Per-tool mutate/lifecycle enforcement.** The audit found a real gap: a
  non-owner can `ingest` / reindex / migrate / `reload` a peer's running stack
  with no check (logged to `docs/observations.md`, 2026-06-17). This is a
  *different* problem (uncoordinated mutation), not the takeover-sensing one. On
  a single-user local dev tool, contention is rare and the cost of a bad
  concurrent mutate is recoverable (rebuild / reindex), so sensing + an honest
  verdict is sufficient; building an enforcement layer here would be structure
  for a case this problem does not include. It belongs with the parallel-stacks
  work, if ever.
- **Parallel dev stacks.** The long-term ambition; a separate effort that this
  sensing layer is a prerequisite for.

## Open questions (load-bearing, resolve before any slice)

1. **Activity-freshness thresholds (gates Piece 1, replaces the old PID
   probe).** The PID probe is *resolved* — the Claude PID is unobtainable and
   unnecessary; presence is the activity-join above. The remaining call is the
   two freshness windows: how stale is "general-activity → silent → abandoned"
   (gates `TAKEOVER_ABANDONED`), and how stale is "dev-stack-activity → idle"
   (gates `IDLE_HOLD`). Both must absorb normal between-tool-call gaps (thinking,
   a long build) without false abandonment, while still freeing a walked-away
   owner promptly. Pair with hook-execution monitoring (tempdoc 592) since the
   signals depend on hooks firing.
2. **Idle threshold + the warm-stack-steal hazard (gates `IDLE_HOLD`).** The
   one genuine policy trade-off: large enough that an owner doing static-work-
   then-verify is not robbed of its stack the moment before it needs it, small
   enough that a walked-away owner doesn't block for long. Pair with the epoch
   notification + cheap re-acquire so a mis-judged idle takeover is recoverable,
   not silent.
3. **Worktree-dist launch (gates the D2 *remedy*; capture is settled).**
   Provenance capture is straightforward — stamp the **dist path + commit
   actually launched** (the field finding: this is the MCP-server-launch cwd's
   dist, usually `main`'s, not the starting worktree's HEAD — record what runs,
   not what was intended) and compare to the arriving agent's HEAD. The *remedy*
   is also cheaper than first thought: the dev-runner already launches from its
   invoking checkout's dist and uses the main repo only for shared state, so
   running a worktree's own code on the shared lease **already works from the
   worktree CLI today**. The only gap is the MCP front door resolving `repoRoot`
   from the server-process cwd once, with no per-call override — so the fix is a
   per-call `repoRoot`/`distFrom` parameter on the MCP `start` tool (the
   `JUSTSEARCH_REPO_ROOT` env knob already exists). Small plumbing, not a
   redesign; the main-dist binding is incidental, not intentional.
4. **Clean handoff on SessionEnd.** Should `SessionEnd` mark the lease absent
   and leave the stack running (warm inheritance, seconds vs ~1 min cold), or
   stop it? Leaning mark-not-stop, paired with Piece 2 so warm inheritance is
   safe — but this is a real choice, not a default. (SessionEnd is droppable, so
   it only *accelerates* the activity-staleness path; it is not the authority.)
5. **Warm handoff sequencing (gates A1+A2).** When a granted waiter needs
   *different* code, the supervisor must stop its children so the waiter can
   cleanly `start` — confirm the stop→signal→waiter-start handoff has no
   window where two stacks bind the same port. (Reuses the existing pre-spawn
   port-cleanup; needs a sequencing check, not new machinery.)
6. **Grace period + grant TTL values.** Pick the abandonment grace (reaper)
   and the `grant` TTL (no-show waiter) — both want to be a small multiple of
   the 10s tick, long enough to absorb a brief owner blip, short enough that a
   zombie/queue doesn't linger.

## As-built (2026-06-18)

Implemented and merged on `worktree-606-ownership-coordination`. Build
(`./gradlew.bat build -x test`) and all governance gates green; 34 verdict + 6
admission unit tests pass.

**Sensing.** One verdict authority — `scripts/dev/lib/ownership-verdict.cjs`
(`computeOwnershipVerdict`, pure) — consumed by the dev-runner admission gate
(`acquireAdmission`) and the MCP advisory (`quick_health`/`status`/`start` via a
shared `buildOwnershipProjection`), so the conflict verdict is single-derived
(D3). Owner presence is sensed from two activity stamps the agent-analytics
hooks write to the **shared** state root (`tmp/dev-runner/sessions/<id>.json`):
`lastActivityAt` (every tool call, via `dispatch.mjs`) and `lastDevStackTouchAt`
(every `justsearch-dev` call, via `mcp-session-inject.mjs`); `hook-base.mjs`
gained `resolveMainRepoRoot`/`stampSessionActivity`. Grades: general-stale →
`TAKEOVER_ABANDONED` (auto-proceeds even on `deny`), general-fresh + dev-stale →
`IDLE_HOLD` (self-authorize `warn`), both fresh → `CONTENTION` (ask user). The
supervisor renewer is presence-aware (pauses renewal for a silent owner so the
lease lapses). **Safety:** a missing stamp is treated as *active* — never a
false takeover.

**Provenance (D2).** The runner stamps `provenance{repoRoot,gitHead,headDistStamp}`
on the lease; the verdict sets `rebuildFirst` on a worktree/commit mismatch. The
Head self-reports its launched dist stamp via `RuntimeManifest.HeadInfo.buildStamp`
(new `EnvRegistry.HEAD_BUILD_STAMP`, injected as `-Djustsearch.head.stamp`); the
projection cross-checks it against the lease (`backendStale`).

**Acting.** `ownershipEpoch` on the lease + pull-at-next-action `displacedNotice`;
the reaper (supervisor self-terminates an abandoned stack past grace); the
`acquire_when_free` MCP tool.

**Scope reductions from the plan (deliberate, YAGNI):** the wait-queue is
verdict-**polling** (`acquire_when_free`), not a `waiters[]`+grant+promotion
protocol — the presence-aware renewer already makes the verdict turn acquirable,
and FIFO fairness across waiters is speculative for a single-user tool (`force`
is the escape hatch). The Head self-report rides the existing `RuntimeManifest`
(not a contract surface → no FE wire-regen) rather than a new `/api/status` field.

**Verified:** unit (the verdict = the gate's whole decision) + functional (hooks
write/clear the stamps) + a read-only run of the verdict against the **live**
lease (returns the conservative `CONTENTION` for a stamp-less holder; crafted
stamps produce the right transitions) — all side-effect-free.

### Live validation (2026-06-18, completed)

Two isolation seams were added so the gate/backend could be validated without
touching the other session's live stack: `JUSTSEARCH_DEV_RUNNER_STATE_ROOT`
(isolated state root) and `JUSTSEARCH_DEV_ABANDONED_MS`/`_IDLE_MS`/
`_REAPER_GRACE_MS` (fast thresholds). Validated:

- **Hooks (live, in production):** the merged hooks write `{lastActivityAt}` /
  `{lastDevStackTouchAt}` to `tmp/dev-runner/sessions/<id>.json` — observed for
  real sessions.
- **Verdict vs the real live lease (read-only):** a stamp-less pre-606 holder →
  conservative `CONTENTION` (no false reap); crafted stamps → the right grades.
- **Gate end-to-end (hermetic, `test-dev-runner-gate-integration.mjs`, 8 cases):**
  the real `acquireAdmission` against an isolated state root —
  NO_OWNER/dead/abandoned/idle/active/unknown → correct action + disposition +
  `stopRun` clears state. Plus 34 verdict + 6 op-lease unit tests.
- **Isolated REAL backend (own state root + data + ephemeral ports):** lease
  `provenance{repoRoot,gitHead,headDistStamp}` + `ownershipEpoch:1` stamped; the
  live Head self-reports `head.buildStamp` on `/api/runtime/manifest` ==
  the lease's `headDistStamp` (the killed-old-backend cross-check), all with
  zero impact on the shared stack.
- **Reaper end-to-end (real supervisor):** an abandoned isolated stack is reaped
  — resources freed, `active.json` removed, `reaped_abandoned` stop-report
  written, supervisor exits cleanly.

**Two defects found by the live reaper run and fixed** (live validation working
as intended): (1) the supervisor's `backend.on('exit')` raced `stopRun` cleanup
(fixed with a `reaping` guard); (2) `stopRun` taskkilled `runnerPid` — the
supervisor's own tree — before cleanup when the reaper called it in-process
(fixed by skipping the self-kill when `runnerPid === process.pid`).

**Still requires a fresh session (this session's MCP server is pre-606 code):**
the MCP *advisory* surface. Checklist for a new session: `quick_health` →
confirm `ownership.verdict` + `recommendedAction`; against an abandoned/idle
crafted holder confirm `start` auto-proceeds / recommends self-authorized
`warn`; `acquire_when_free` returns `recommendedTakeover`; a displaced prior
owner sees `ownership.displacedNotice`.

**Out-of-scope findings logged to `docs/observations.md` (2026-06-18):** ownership
gates only `start` (a non-owner can still mutate a peer's running stack); zod v4
single-arg `z.record(z.unknown())` in `OpLeaseSchema` is malformed.
