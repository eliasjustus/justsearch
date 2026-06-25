// @vitest-environment happy-dom

/**
 * Tempdoc 613 — coherence: the AI Brain compatibility callout must WORD its cause from the ONE
 * canonical reindex vocabulary (`reasonFor`/CAUSE_ROWS — the same the Chat degradation banner +
 * the 595 verdict use), not a hardcoded fork. These tests pin that the callout's lead equals the
 * canonical wording (so the two surfaces cannot drift) while the config-altitude technical detail
 * (the legacy/mismatch tag + the fingerprint hashes) is retained beneath it.
 */

import { describe, expect, it } from 'vitest';
import { render, type TemplateResult } from 'lit';
import './BrainSurface.js';
import { reasonFor } from '../state/readinessNotice.js';

interface CalloutHarness {
  systemStatus: unknown;
  apiBase: string;
  _unifiedAiState: unknown;
  renderCompatibilityCallouts(): TemplateResult | symbol;
}

/** Build a detached BrainSurface (no connectedCallback ⇒ no poll/subscribe), drive the private
 *  callout renderer, and return its rendered text. */
function calloutText(systemStatus: unknown, verdictReasons: string[]): string {
  const el = document.createElement('jf-brain-surface') as unknown as CalloutHarness;
  el.systemStatus = systemStatus;
  el.apiBase = '';
  el._unifiedAiState = { verdict: { kind: 'degraded', reasons: verdictReasons } };
  const tpl = el.renderCompatibilityCallouts();
  const container = document.createElement('div');
  render(tpl as TemplateResult, container);
  return container.textContent ?? '';
}

const EMB_BLOCKED_LEGACY = {
  embedding: {
    compatState: 'BLOCKED_LEGACY',
    fingerprintStored: 'aaaaaaaaaaaa1111',
    fingerprintCurrent: 'bbbbbbbbbbbb2222',
  },
  schema: { compatState: 'COMPATIBLE' },
};

describe('BrainSurface compat callout — tempdoc 613 reindex coherence', () => {
  it('leads with the canonical reindex wording (identical to the Chat banner / 595 verdict)', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    // The lead is the SAME wording the Chat banner renders (asserted against the authority, not a
    // string literal — so this fails if the two ever diverge).
    expect(text).toContain(reasonFor('index.embedding_legacy').wording);
  });

  it('drops the old hardcoded fork wording', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    expect(text).not.toContain('before embedding fingerprinting was enabled');
  });

  it('retains the config-altitude technical detail (legacy tag + fingerprint hashes)', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, ['index.embedding_legacy']);
    expect(text).toContain('Embedding model fingerprint missing');
    expect(text).toContain('Stored:'); // fingerprint stored→current line retained
  });

  it('falls back to a generic remedy line when the verdict carries no reindex code (no per-cause fork)', () => {
    const text = calloutText(EMB_BLOCKED_LEGACY, []);
    expect(text).toContain('Rebuild the index to restore full search.');
  });
});
