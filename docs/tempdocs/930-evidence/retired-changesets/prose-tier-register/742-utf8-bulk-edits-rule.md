---
classification: new-rule-registered
tempdoc: 742
---
New rule 44 (`utf8-bulk-edits`) registered at `prose-only` tier in the
`.claude/rules/agent-lessons.md` platform-constraints section.

During tempdoc 742's IndexDocument rename, a sonnet worker's bulk edit
round-tripped 47 Java files through cp1252, mangling every non-ASCII character;
exactly 3 assertions in the entire test suite (language-detection parity) were
positioned to notice, and the repair required regenerating all 47 files from
HEAD with a UTF-8-safe transform. Prose-only because parent hooks do not fire
inside subagents (register row 28) and bulk edits are precisely subagent work —
the mandatory brief clause plus the orchestrator's zero-added-non-ASCII diff
check are the only available control surface.
