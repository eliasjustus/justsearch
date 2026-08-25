---
classification: new-rule-registered
tempdoc: 861
---
New rule 46 (`agent-spawn-build-hint`) registered at `hook-hint` tier in the
`.claude/rules/branch-safety.md` Shared Dev Stack section.

Tempdoc 861 §2-bis (b) names the dominant observed harm this whole design responds
to: a capture-then-build `EPERM`/`-4048` while a registered process (an owner-alive
Vite server) still holds a path the build needs to write — a mystifying error with no
obvious cause, costing an agent real diagnosis time. §6.3/[A4] deliberately puts this
occasion at the ADVISORY tier rather than letting it kill: "a hook that kills processes
as a side effect of an agent typing `gradlew` is a much larger hazard than the one it
removes." This rule states the resulting discipline for a reader: the hint names the
holder and a ready-to-run remedy, but it is on you to decide whether to act on it —
the hook cannot decide for you even for your own session's spawn.

Tier is `hook-hint` (~85%), not `hook` (blocking) and not `prose-only`: the trigger is
deterministic (a gradlew/npm-shaped Bash command whose target tree a registered record's
`resourceRoots` resolves under, via `recordHoldsPath`) so a PreToolUse hint
(`agent-spawn-build-hint.mjs`) delivers it at the moment of relevance, but the fix
(clear the holder, or don't) stays the agent's judgment call — the hook is structurally
unable to kill even if it tried: `before-a-build` binds to `capability: 'advisory'` in
the reaper's frozen `OCCASIONS` map (861 review finding F2), so `reapEligible` mints no
`reap` entry for this occasion regardless of what the hook hands it.
