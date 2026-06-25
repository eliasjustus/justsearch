<!-- budget: always-loaded; ceiling in scripts/ci/always-loaded-budget.v1.json (ratchets down) — tempdoc 620. -->

# Project-Specific Agent Guidance

## Search Strategy
- **Use `model: haiku`** for simple subagent tasks (file lookups, pattern searches).
- **Bound Explore agents**: default to 1; cap synthesis output.
- **Known large files** (use offset/limit): UnifiedChatView.ts, Shell.ts, SearchSurface.ts, SummaryController.java, LuceneIndexRuntime.java, analyze-session.mjs.
- **Docs index**: docs/llms.txt. Module structure: docs/explanation/01-system-overview.md.

## Editing
- **For Java files: run `./gradlew.bat spotlessApply` after edits**, then check compilation with `./gradlew.bat build -x test` before further changes.

## Worktree Awareness
- **After compaction or session start**, verify you're in the expected worktree — `compact-restore` now surfaces the dir + branch in the restored state block (tempdoc 620); see `branch-safety.md` `after-compaction-verify` for the fallback check.
- **Build artifacts are per-worktree.** Each worktree needs its own `./gradlew.bat assemble` and `npm install` (in `modules/ui-web`).
- **Don't `cd` to another worktree's directory.** Stay in your assigned worktree.
- **Cite full worktree-qualified paths when reasoning about a file that exists in several trees** (tempdoc 618 §1b). The same `ConversationEngine.java` / `UnifiedChatView.ts` lives in `main` and in every worktree; referring to it by bare name invites mentally merging two different copies (and can mask a base-ref mismatch). Use the full `.claude/worktrees/<name>/…` (or main-checkout) path.

## Compaction
When compacting, preserve: file paths modified, code changes made, function/component names involved, and any unresolved task context. Discard verbose tool outputs and exploratory dead ends.

Recommended: Set `CLAUDE_AUTOCOMPACT_PCT_OVERRIDE=65` in your environment for earlier compaction with more working memory.
