---
classification: new-rule-registered
tempdoc: 727
---
New rule 41 (`edit-reread-cross-root`) registered at `hook-hint` tier in the
`.claude/rules/agent-lessons.md` section.

Tempdoc 727 (session-transcript friction mining) found this as finding F-7a: the single
largest sub-cluster in its entire dataset (11+ sessions) was `Edit` failing with "File has not
been read yet" / "modified since read" because a worktree-copy and a main-checkout copy of the
"same" logical file don't share read state — they're different files on disk. This was
*already* diagnosed once, in tempdoc 618 §11e, and flagged for promotion into this rules file,
but the promotion was never completed; it's absent from `agent-lessons.md` before this change.
727 independently rediscovered the same category without originally connecting it back.

Tier is `hook-hint`, not `prose-only`: the platform's own error text is already
self-explanatory in the generic case, so a hook only earns its keep by adding the specific,
repo-flavored explanation a generic error can't know (which other path, under which root, was
already read this session) — exactly the "residence → delivery" pattern already named for
`pipe-mask-hint`/`tempdoc-age-hint` (agent-lessons.md rule 37). `edit-reread-hint.mjs`
(`PostToolUseFailure`/Edit) delivers that specific note when a cross-root re-read match
exists, and stays silent otherwise rather than restating the platform's own message. The
`agent-lessons.md` prose stays as the always-loaded fallback for sessions where the hook
doesn't fire (e.g. no matching earlier read exists to point to).
