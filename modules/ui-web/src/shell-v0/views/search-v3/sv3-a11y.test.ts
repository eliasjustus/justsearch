// SPDX-License-Identifier: Apache-2.0

/**
 * Document-structure closure for the Search v3 window (tempdoc 854 PR-A).
 *
 * `scripts/ci/check-a11y-closure.mjs` enforces rule (5) — one page `<h1>` and one `main` landmark,
 * both owned by the Shell — but it CANNOT see this directory: its walk reads `VIEWS_DIR` with a
 * non-recursive `readdirSync` (`:134`) and loops only those files (`:140`), so every file under
 * `views/search-v3/**` (and `views/security/**`) is unscanned. The window about to become THE window
 * therefore has no heading-structure guard at all.
 *
 * This is the scoped half of that fix. Making the gate's walk recursive is the other half, and it is
 * deliberately NOT bundled here: it turns the build red today on a real, pre-existing defect —
 * `Sv3Composer.ts:1403` emits a second page `<h1>` for the hero — and deciding between demoting that
 * heading and suppressing the topbar's in that state is a hero design question this PR has no mandate
 * to answer. The finding is logged as an observation and pinned below, so it can be neither lost nor
 * quietly joined by a second one.
 */
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));

const sources = (): { name: string; text: string }[] =>
  readdirSync(HERE)
    .filter((name) => name.endsWith('.ts') && !name.endsWith('.test.ts'))
    .map((name) => ({ name, text: readFileSync(join(HERE, name), 'utf8') }));

// Deliberately the GATE's own patterns (`scripts/ci/check-a11y-closure.mjs:142, 148`), not
// lookalikes: when the recursive-walk fix lands, this stand-in and the gate must be able to disagree
// only about SCOPE, never about what counts as a violation.
const H1 = /<\/h1>/g;
const MAIN_LANDMARK = /role=(['"`])main\1|<main[\s>]/;

describe('Search v3 document structure (854 PR-A — the scoped stand-in for check-a11y-closure)', () => {
  it('the transcript region declares no page heading and no main landmark of its own', () => {
    // Sv3Main is the file this PR restructured: it now stamps navigation landmarks on the question,
    // the answer, every run step and every held decision. Landmarks are `data-item-id` anchors, NOT
    // ARIA landmarks — a region that also claimed `main`, or a second `<h1>`, would give a screen
    // reader two page structures for one page.
    const main = readFileSync(join(HERE, 'Sv3Main.ts'), 'utf8');
    expect(main.match(H1) ?? []).toEqual([]);
    expect(MAIN_LANDMARK.test(main)).toBe(false);
  });

  it('no file in the window claims a main landmark', () => {
    const offenders = sources()
      .filter((f) => MAIN_LANDMARK.test(f.text))
      .map((f) => f.name);
    expect(offenders).toEqual([]);
  });

  it('exactly one KNOWN second page heading exists, and it is the hero’s', () => {
    // A pin, not an endorsement. Keyed by COUNT, not by file: a file-keyed set would let a SECOND
    // `<h1>` appear inside `Sv3Composer.ts` while still reading as "exactly one known heading". So
    // the expectation is the full census, and any new heading — in a new file or in the exempt one —
    // fails immediately, while the one already there stays visible as an open item rather than
    // dissolving into a blanket exemption. When the hero-heading decision lands, this becomes `{}`
    // in the same change.
    const census: Record<string, number> = {};
    for (const f of sources()) {
      const hits = (f.text.match(H1) ?? []).length;
      if (hits > 0) census[f.name] = hits;
    }
    expect(census).toEqual({ 'Sv3Composer.ts': 1 });
  });
});
