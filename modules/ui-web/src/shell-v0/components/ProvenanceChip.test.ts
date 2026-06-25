// @vitest-environment happy-dom

/**
 * ProvenanceChip unit tests — Tempdoc 543 §3.A + §13.2.3.1.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import './ProvenanceChip.js';
import type { ProvenanceChip } from './ProvenanceChip.js';
import {
  CORE_PROVENANCE,
  makePluginProvenance,
} from '../primitives/provenance.js';

function mountChip(): ProvenanceChip {
  const el = document.createElement('jf-provenance-chip') as ProvenanceChip;
  document.body.appendChild(el);
  return el;
}

async function awaitRender(el: ProvenanceChip): Promise<void> {
  await el.updateComplete;
}

describe('<jf-provenance-chip>', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders nothing for undefined provenance', async () => {
    const el = mountChip();
    await awaitRender(el);
    expect(el.shadowRoot?.querySelector('.chip')).toBeNull();
  });

  it('renders nothing for CORE provenance', async () => {
    const el = mountChip();
    el.provenance = CORE_PROVENANCE;
    await awaitRender(el);
    expect(el.shadowRoot?.querySelector('.chip')).toBeNull();
  });

  it('renders chip for TRUSTED_PLUGIN provenance', async () => {
    const el = mountChip();
    el.provenance = makePluginProvenance('acme', '1.2.3');
    await awaitRender(el);
    const chip = el.shadowRoot?.querySelector('.chip');
    expect(chip).not.toBeNull();
    expect(chip?.textContent).toContain('plugin');
    expect(chip?.textContent).toContain('acme');
    expect(chip?.textContent).toContain('v1.2.3');
  });

  it('renders untrusted class for UNTRUSTED_PLUGIN', async () => {
    const el = mountChip();
    el.provenance = makePluginProvenance('shady', '0.1.0', 'UNTRUSTED_PLUGIN');
    await awaitRender(el);
    const chip = el.shadowRoot?.querySelector('.chip');
    expect(chip?.classList.contains('untrusted')).toBe(true);
    expect(chip?.getAttribute('data-display-tier')).toBe('UNTRUSTED');
    expect(chip?.textContent).toContain('untrusted');
  });

  it('renders verified mark for identity.verified', async () => {
    const el = mountChip();
    el.provenance = makePluginProvenance('verified-plug', '2.0', 'TRUSTED_PLUGIN', {
      identity: { verified: true, signature: 'sig' },
    });
    await awaitRender(el);
    const chip = el.shadowRoot?.querySelector('.chip');
    expect(chip?.classList.contains('verified')).toBe(true);
    expect(chip?.getAttribute('data-display-tier')).toBe('VERIFIED');
    expect(chip?.querySelector('.chip-verify-mark')?.textContent).toBe('✓');
  });

  it('omits version span for version=0 (CORE sentinel)', async () => {
    const el = mountChip();
    el.provenance = {
      tier: 'TRUSTED_PLUGIN',
      contributorId: 'p',
      version: '0',
    };
    await awaitRender(el);
    const chip = el.shadowRoot?.querySelector('.chip');
    expect(chip?.querySelector('.chip-version')).toBeNull();
  });

  it('tooltip includes review timestamp and capability count', async () => {
    const el = mountChip();
    el.provenance = makePluginProvenance('p', '1', 'TRUSTED_PLUGIN', {
      review: { lastReviewedAt: '2026-05-01T00:00:00Z' },
      capability: ['filesystem.read', 'network.fetch'],
    });
    await awaitRender(el);
    const chip = el.shadowRoot?.querySelector('.chip');
    const title = chip?.getAttribute('title') ?? '';
    expect(title).toContain('reviewed 2026-05-01');
    expect(title).toContain('2 capabilities');
  });
});
