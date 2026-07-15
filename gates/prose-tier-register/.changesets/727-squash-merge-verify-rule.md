---
classification: new-rule-registered
tempdoc: 727
---
New rule 40 (`squash-merge-verify-content-not-ancestry`) registered at `prose-only` tier in the
`.claude/rules/branch-safety.md` section.

Tempdoc 727 (session-transcript friction mining) found this as finding F-8a: at least 4
independent developer sessions concluded a piece of work was "unmerged" by inspecting branch
labels, commit dates, or `git log`/branch-ancestry, then discovered it was already merged
under a differently-titled squashed commit (ADR-0045 squash-merges every PR, so a branch's
original commits never appear in `main`'s history). One instance fed a wrong conclusion into a
user-facing decision before being caught. Neither `branch-safety.md`, ADR-0045, nor
`agent-postmortems.md` documented the correct verification method before this — every existing
doc explained *why* squash-merge exists, none said "don't trust commit ancestry for this."

Tier is `prose-only`: the correct check is a single command (`git diff <branch> main --
<paths>`, empty = already landed), so no gate/hook could add value beyond stating the fact —
automating "is this content actually landed" isn't distinguishable from just running the diff
yourself, and a gate can't fire at the moment an agent is about to draw a wrong conclusion from
ancestry (that moment is a mental step, not a tool call to intercept).
