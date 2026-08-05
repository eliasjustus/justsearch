// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import { nothing } from 'lit';
import './IndexingOverlay.js';
import type { IndexingOverlay } from './IndexingOverlay.js';
import type { AiState } from '../state/aiStateStore.js';
import { known } from '../state/known.js';

function make(): IndexingOverlay {
  const el = document.createElement('jf-indexing-overlay') as IndexingOverlay;
  document.body.appendChild(el);
  return el;
}

describe('IndexingOverlay (slice 460)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('renders header + explain text', async () => {
    const el = make();
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('h3')?.textContent).toBe('Batch Processing Active');
    expect(el.shadowRoot?.querySelector('.explain')?.textContent).toContain(
      'embeddings',
    );
  });

  it('renders queue rows when work is pending', async () => {
    const el = make();
    el.embeddingQueueSize = 333;
    el.vduQueueSize = 0;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.queue-row.embed')).not.toBeNull();
    expect(el.shadowRoot?.querySelector('.queue-row.vdu')).toBeNull();
  });

  it('omits the queue card when total is 0', async () => {
    const el = make();
    el.embeddingQueueSize = 0;
    el.vduQueueSize = 0;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.queue')).toBeNull();
  });

  it('emits go-online on CTA click', async () => {
    const el = make();
    await el.updateComplete;
    let fired = false;
    el.addEventListener('go-online', () => (fired = true));
    el.shadowRoot?.querySelector<HTMLButtonElement>('button.cta')?.click();
    expect(fired).toBe(true);
  });

  it('CTA disabled while switching', async () => {
    const el = make();
    el.switching = true;
    await el.updateComplete;
    const btn = el.shadowRoot?.querySelector<HTMLButtonElement>('button.cta');
    expect(btn?.disabled).toBe(true);
  });

  it('emits dismiss on close button click', async () => {
    const el = make();
    el.dismissible = true;
    await el.updateComplete;
    let fired = false;
    el.addEventListener('dismiss', () => (fired = true));
    el.shadowRoot?.querySelector<HTMLButtonElement>('button.close')?.click();
    expect(fired).toBe(true);
  });

  it('hides close button when not dismissible', async () => {
    const el = make();
    el.dismissible = false;
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('button.close')).toBeNull();
  });
});

/**
 * Tempdoc 807 A.3 (round-13 R13-F2) — the HOST decides whether the overlay may assert "indexing is
 * happening right now". Its inputs are fields off the retained snapshot, so with the backend dead it
 * kept asserting live work (and offered a "Go online" button that would POST into the void).
 */
describe('IndexingOverlayHost — snapshot liveness (807)', () => {
  interface HostHarness {
    aiState: AiState | null;
    render(): unknown;
  }
  /** Detached (never appended) ⇒ no connectedCallback ⇒ the store subscription can't overwrite the fixture. */
  const host = (snapshotLive: boolean): HostHarness => {
    const el = document.createElement('jf-indexing-overlay-host') as unknown as HostHarness;
    el.aiState = {
      runtime: { mode: 'indexing' },
      index: { embeddingQueueSize: known(4789), vduQueueSize: known(0) },
      snapshotLive,
    } as unknown as AiState;
    return el;
  };

  it('withdraws when the snapshot is no longer a live observation', () => {
    expect(host(false).render()).toBe(nothing);
  });

  it('ANTI-REGRESSION: still renders while the snapshot IS live', () => {
    expect(host(true).render()).not.toBe(nothing);
  });
});
