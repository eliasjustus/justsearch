#!/usr/bin/env node

/**
 * Synchronous SubagentStart hook — injects codebase-specific guidance.
 *
 * Emits additionalContext with project-specific knowledge that subagents
 * wouldn't otherwise have. Generic rules (use Read not cat) are already
 * in the system prompt — this adds value beyond that.
 *
 * - Synchronous (async: false) — blocks until it returns
 * - Timeout: 5s
 * - Always exits 0 — never blocks subagent creation
 */

import { hardInvariants } from '../lib/hard-invariants.mjs';

function buildGuidance(input = {}) {
  const sessionId = typeof input.session_id === 'string' && input.session_id.trim()
    ? input.session_id.trim()
    : null;
  const platformLine = process.platform === 'win32'
    ? 'Windows Git Bash. Use forward slashes and /dev/null, not NUL.'
    : `${process.platform}.`;

  // This hook is the ONLY project-aware context a subagent receives. Subagents
  // do not see CLAUDE.md, .claude/rules/*.md, or any parent-session hooks
  // (verified via Piebald-AI prompt-leak repo + live introspection probe,
  // tempdoc 423 §14.16). Keep this guidance under ~10K chars (hook output cap).

  const sections = [];

  sections.push('## JustSearch — subagent context (parent CLAUDE.md is NOT loaded for you)');

  // Projected LIVE from CLAUDE.md's Hard Invariants (single authority — never
  // hand-copy; a hand-copy silently drifted to 4-of-6 before tempdoc 620 Part V).
  const invariants = hardInvariants();
  if (invariants.length) {
    sections.push(
      '### Hard invariants (do not violate) — projected from CLAUDE.md',
      ...invariants.map((t, i) => `${i + 1}. ${t}`),
    );
  } else {
    sections.push(
      '### Hard invariants (do not violate)',
      '1. Head process never performs Lucene index IO directly — delegate through service/Worker abstractions.',
      '2. Local API binds to 127.0.0.1 only (loopback).',
      '3. Do not resurrect removed endpoints (`/api/search`, `/api/settings`).',
      '4. Verify, do not guess — use `/api/debug/state` and `/api/health`, not log grepping.',
      '5. Frontend is Lit web components, not React (the React stack is retired, ADR-0032).',
      '6. No per-language search levers — search analysis is locale-invariant (ADR-0043).',
    );
  }

  sections.push(
    '### Agent discipline',
    '- Fix root causes, not symptoms. Never comment out failing code, weaken assertions, @Disabled tests, or broaden catches to silence failures.',
    '- If a test fails after your changes, the test is probably right and your code is wrong.',
    '- Explore existing helpers before creating new ones. The most common mistake is reinventing utilities that exist two packages over.',
    '- Do not introduce backwards-compatibility shims, dead-code comments, or speculative abstractions.',
    '- Default to writing no comments. Only add WHY-comments for non-obvious invariants.',
  );

  sections.push(
    '### Subagent-specific risk profile',
    '- **No hooks fire in your context.** The parent\'s bash-guard, repeat-guard, intervene, build-counter, and ssot/docs/lockfile/ui-shot hints DO NOT protect you.',
    '- You can run destructive git commands (e.g., `git reset --hard`) in the main worktree without the bash-guard intercepting. Don\'t.',
    '- You don\'t get auto-Read-limit injection. Be explicit with offset/limit on files >8KB; large files include modules/ui-web/src/shell-v0/views/UnifiedChatView.ts (~5,400 lines), SummaryController.java, LuceneIndexRuntime.java, analyze-session.mjs.',
    '- You don\'t get repeat-guard. If you find yourself reading the same file 3 times, stop and reconsider.',
  );

  sections.push(
    '### Observations protocol',
    'If you notice a pre-existing issue outside your task scope (bug, dead code, stale comment, config drift): log one line to the inbox and keep working. Do not investigate. Do not fix.',
    'Log via: `node scripts/agent-analytics/note-observation.mjs "<description> — \\`<file:line>\\`"` — it writes to your own per-session shard under `docs/observations.d/` (618 Seam C), so a parallel agent\'s commit can\'t wipe your note; it resolves your session id and stamps the date.',
  );

  sections.push(
    '### Tooling pointers',
    `- Platform: ${platformLine}`,
    '- Use Grep files_with_matches first to find which files to read; then targeted Read.',
    '- Docs map: docs/llms.txt. Architecture overview: docs/explanation/01-system-overview.md.',
    '- Build: `./gradlew.bat build -x test` (compile only) before declaring done.',
    '- Format: `./gradlew.bat spotlessApply` after Java edits.',
    '- Pipeline profiling: `python -m jseval` (NEVER raw `gradlew runHeadless &` + `sleep` loops).',
    '- Sleep ≥1s in Bash is blocked by the parent\'s bash-guard but not yours — still don\'t use it. Use jseval for backend lifecycle.',
  );

  sections.push(
    '### Reporting',
    'Stop after answering what was asked. Don\'t gold-plate. Return a concise summary; the parent relays it to the user.',
  );

  if (sessionId) {
    sections.push(
      '### Session attribution',
      `If you invoke workflow wrappers/DAGs that take --session-id, pass: ${sessionId}`,
    );
  }

  return sections.join('\n');
}

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);

  const raw = Buffer.concat(chunks).toString('utf8').trim();
  if (!raw) return;
  let input = {};
  try {
    input = JSON.parse(raw);
  } catch {
    input = {};
  }

  process.stdout.write(JSON.stringify({
    hookSpecificOutput: {
      hookEventName: 'SubagentStart',
      additionalContext: buildGuidance(input),
    },
  }));
}

main().catch(() => process.exit(0));
