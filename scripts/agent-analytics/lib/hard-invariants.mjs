/**
 * Single-authority reader for CLAUDE.md's `## Hard Invariants`.
 *
 * The invariants have ONE home (CLAUDE.md). Any consumer that needs them —
 * notably `subagent-guide.mjs`, which injects them into subagents that don't
 * see CLAUDE.md — must PROJECT from this reader, never hand-copy them. A
 * hand-copy drifts: `subagent-guide` previously inlined invariants 1-4 and
 * silently fell out of date when #5 (frontend-is-Lit) and #6 (language-agnostic)
 * were added, so subagents doing frontend work were never told "Lit, not React"
 * (tempdoc 620 Part V — the doc-as-projection principle applied to a hook).
 *
 * Live read at call time, mirroring `governed-regions.mjs` reading its register.
 * Fail-open: returns [] on any parse/IO failure (a hook must never crash).
 */

import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const CLAUDE_MD = resolve(REPO_ROOT, 'CLAUDE.md');

/**
 * Parse the `## Hard Invariants` numbered list out of CLAUDE.md, stripping each
 * item's trailing `<!-- rule:* -->` anchor.
 *
 * @returns {string[]} invariant texts in order (e.g. "**Head never touches
 *   Lucene** - Delegate all index IO to Worker via gRPC"), or [] on failure.
 */
export function hardInvariants() {
  try {
    const lines = readFileSync(CLAUDE_MD, 'utf8').split(/\r?\n/);
    const out = [];
    let inSection = false;
    for (const raw of lines) {
      const line = raw.trimEnd();
      if (/^##\s+Hard Invariants/i.test(line)) {
        inSection = true;
        continue;
      }
      if (inSection && /^##\s+/.test(line)) break; // next section header ends it
      if (!inSection) continue;
      const m = line.match(/^\d+\.\s+(.*)$/);
      if (m) {
        const text = m[1].replace(/<!--\s*rule:[a-z0-9-]+\s*-->\s*$/i, '').trim();
        if (text) out.push(text);
      }
    }
    return out;
  } catch {
    return [];
  }
}
