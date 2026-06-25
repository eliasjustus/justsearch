// @vitest-environment happy-dom

/**
 * 569 §16 #3 — the CORE_DECLARED runtime contract test.
 *
 * The runtime-truth complement to the static `check-declared-surfaces` gate (`audit-without-test`:
 * the gate is the hypothesis — it scans source for the engine mount + a single registration — and
 * this test is the truth — it loads the REAL `CORE_DECLARED` object and exercises it. So the
 * four-surface inversion (Settings/Library/Help/Health) cannot silently break at the declaration
 * level even if the static source-scan is somehow evaded:
 *
 *   (i)  every declared body region CERTIFIES through the conformance gate (Move 6), and
 *   (ii) every `x-ui-renderer` hint the bodies reference RESOLVES to a registered renderer.
 */
import { describe, it, expect } from 'vitest';
import { CORE_DECLARED, BUILTIN_PRESENTATIONS } from './builtinPresentations.js';
import { certifyPresentation, describeConformanceError } from './conformanceGate.js';
import { getXUiRendererTag } from '../renderers/controls/XUiRendererControl.js';
import { ensureXUiRenderer } from '../renderers/controls/lazyHintLoaders.js';
// Side-effect import: the renderer barrel registers the eager default set. The four bespoke surface
// renderers (folder-card/shortcuts-table/list-items/metric-card) are LAZY (569 Phase 0) — they
// register on demand via `ensureXUiRenderer`, which the hint-resolution test below awaits.
import '../renderers/registry.js';

/** Deep-collect every `x-ui-renderer` hint referenced anywhere within a declaration's bodies. */
function collectHints(node: unknown, out: Set<string>): void {
  if (Array.isArray(node)) {
    for (const v of node) collectHints(v, out);
  } else if (node && typeof node === 'object') {
    for (const [k, v] of Object.entries(node)) {
      if (k === 'x-ui-renderer' && typeof v === 'string') out.add(v);
      else collectHints(v, out);
    }
  }
}

describe('CORE_DECLARED contract (569 §16 #3 — runtime complement to check-declared-surfaces)', () => {
  it('every built-in presentation (incl. the Phase 4 style variations) certifies cleanly', () => {
    expect(BUILTIN_PRESENTATIONS.length).toBeGreaterThanOrEqual(8); // CORE + 6 variations + others
    for (const decl of BUILTIN_PRESENTATIONS) {
      const { verdict } = certifyPresentation(decl);
      expect(
        verdict.ok,
        `built-in '${decl.id}' must certify; errors: ${verdict.errors.map(describeConformanceError).join('; ')}`,
      ).toBe(true);
      // 569 §19 Seam 1 — every built-in carries team provenance.
      expect(decl.origin?.kind, `built-in '${decl.id}' must be stamped team`).toBe('team');
    }
  });

  it('every declared body region certifies through the conformance gate (no quarantine)', () => {
    const { verdict } = certifyPresentation(CORE_DECLARED);
    // The four declaration-default surfaces are the live default; the whole declaration must certify
    // with no surface or layout quarantined to the fallback.
    expect(verdict.errors).toEqual([]);
    expect(verdict.ok).toBe(true);
    expect(verdict.quarantinedSurfaces).toEqual([]);
    expect(verdict.quarantinedLayout).toBe(false);
  });

  it('every x-ui-renderer hint referenced in the bodies resolves to a registered renderer', async () => {
    const hints = new Set<string>();
    collectHints(CORE_DECLARED.body, hints);
    // Sanity: the bodies do reference hints (a future empty-body regression would be caught here too).
    expect(hints.size).toBeGreaterThan(0);
    for (const hint of hints) {
      // 569 Phase 0 — the four bespoke surface renderers are lazy; loading them on demand is part
      // of the contract (every declared hint must be resolvable, eagerly or lazily).
      await ensureXUiRenderer(hint);
      expect(
        getXUiRendererTag(hint),
        `x-ui-renderer hint '${hint}' (referenced by a CORE_DECLARED body) must resolve to a ` +
          `registered renderer — register it or the region cannot render declaration-default`,
      ).toBeTruthy();
    }
  });
});
