---
classification: new-rule-registered
tempdoc: 653
---
New rule 36 (`docs-ride-along`) registered at `hook-hint` tier in the
`.claude/rules/branch-safety.md` section.

Tempdoc 653 shipped the public-main history policy in two halves. ADR-0045 +
the repo merge settings solved **axis 1**: a noisy branch transcript is squashed
into one curated `main` commit at merge. Axis 1 is silent on **axis 2**: whether
a trivial / docs-only change should be its *own* standalone public PR / commit at
all. The triggering instance was a docs-only tempdoc append landing as its own
public commit — exactly the "every intermediate tempdoc edit as a separate
public `main` commit" shape the tempdoc's own Boundary section warned against.

Internet research (653 §"Axis-2 internet research") found no novel solution
needed: the convention is industry-standard — docs ride along with the code they
document (Microsoft eng playbook), trivial doc/typo edits batch rather than each
opening a PR (Kubernetes PR guide), and working-design notes are projected, not
dumped. The rule records the JustSearch form: tempdoc/observations edits
ride-along or batch; canonical-doc updates (durable current truth) may stand
alone; a docs+code branch is already a ride-along.

Tier is `hook-hint`, not `prose-only` or `gate`: granularity is judgment (a gate
would false-positive on legitimate canonical-doc and ride-along branches and
cannot tell "worth a public commit" from "not"), but salience at the publish
boundary is mechanizable. `docs-granularity-hint.mjs` (PreToolUse Bash
`git push`) fires only when a branch's whole diff vs `origin/main` is
`docs/tempdocs/**` / `docs/observations*` only — non-blocking, fail-open, honors
`JUSTSEARCH_DISABLE_HOOKS=1`. Rule anchored in `branch-safety.md`
(`<!-- rule:docs-ride-along -->`); `Resolves to` is `hook:docs-granularity-hint.mjs`.

CLAUDE.md root is intentionally left untouched: the rule is needed at
commit/push time (the hook delivers it there) and the always-loaded budget is
guarded, so a new root paragraph would be the bloat the
`before-appending-to-rules` gate exists to prevent. Rationale lives in
`docs/reference/contributing/agent-guide.md` (History publication).
