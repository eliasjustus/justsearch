---
classification: new-rule-registered
tempdoc: 861
---
New rule 45 (`agent-spawn-session-end-reap`) registered at `hook-hint` tier in the
`.claude/rules/branch-safety.md` Shared Dev Stack section.

Tempdoc 861 Phase 5 wires the `session-start`, `session-end`, and `session-closeout`
occasions of the agent-spawn reaper (`scripts/dev/lib/agent-spawn-reaper.cjs`, landed
Phase 4 / PR #552) onto real triggers. Phase 3 established that the one existing reap
point for a leaked ui-shot Vite server (`ui-shot-cleanup.mjs`) was never wired into the
hook manifest, so every agent-spawned helper process leaked by construction unless
something else happened to kill it. This rule states the resulting discipline for a
reader: don't hand-`taskkill` a registered helper process — the registry's whole point
is the identity re-verification (pid AND creation time AND command-line fingerprint,
861 §6.2) a manual kill bypasses, and the reaper already runs automatically at the
session boundaries that matter.

Tier is `hook-hint`, not `prose-only`: `agent-spawn-session-end-reap.mjs` (SessionEnd)
mechanizes the "this session's own spawns" half at the moment of relevance, and
`agent-spawn-sweep-hint.mjs` (SessionStart, async) plus `remove-worktree.cjs`'s
teardown consult (861 §6.4) cover the other two occasions this rule's row names —
so the discipline is delivered automatically rather than resting on the agent
remembering to run a sweep by hand. It stays `hook-hint` (~85%), not `hook` (blocking),
because a missed reap leaks a process rather than causing an unsafe state a block
would need to prevent — the next occasion (session-start, or a human-run
session-closeout sweep) gets another chance at the same record.
