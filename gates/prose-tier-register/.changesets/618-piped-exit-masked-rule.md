---
classification: new-rule-registered
tempdoc: 618
---
New rule 37 (`piped-exit-masked`) registered at `hook-hint` tier in the
`.claude/rules/agent-lessons.md` section.

Tempdoc 618's settlement converts its one high-blast-radius / deterministic-trigger
platform lesson (§10a) from an always-loaded prose bullet into a moment-of-relevance
delivery hook. §10a: a build/test command piped into `tail`/`grep`/`head` reports the
FILTER's exit code, not the build's — so `./gradlew build | tail -25` can read exit 0
while the build FAILED, one step from fast-forwarding `main` on a red build. The
motivating instance: §10a recurred in the 2026-07-01 session *despite* already living
in `agent-lessons.md` — maximal residence, minimal salience ("residence is not
delivery"; the just-in-time context-engineering principle applied to rule delivery).

Tier is `hook-hint`, not `prose-only` or `hook` (blocking): the trigger is
deterministic (a pipeline whose masking sink is the last stage and whose earlier stage
is a build/test command) but the hint must NOT block — a legitimate `log | tail` /
`… | grep` read must still pass, and even a masked build is sometimes intentional. So
salience at the command boundary is mechanized while judgment stays with the agent.
`pipe-mask-hint.mjs` (PreToolUse Bash) fires only on the stage-aware positive and stays
silent when the author preserved the exit (`set -o pipefail` / `${PIPESTATUS}` / `$?`)
— non-blocking, fail-open, honors `JUSTSEARCH_DISABLE_HOOKS=1`. Rule anchored in
`agent-lessons.md` (`<!-- rule:piped-exit-masked -->`); `Resolves to` is
`hook:pipe-mask-hint.mjs`. Detection precision is guarded by the corpus in
`pipe-mask-hint.test.mjs`.

It could NOT live in `bash-guard`: that hook is block-or-silent (no advisory channel)
and structurally short-circuits on any pipe (`bash-guard.mjs:217`), so it can neither
emit an advisory nor even see a piped command — hence a separate advisory hook mirroring
`docs-granularity-hint.mjs`. The always-loaded §10a prose is intentionally kept as the
public-checkout fallback (a checkout without wired hooks still carries the lesson); the
hook adds delivery, it does not replace the rule.
