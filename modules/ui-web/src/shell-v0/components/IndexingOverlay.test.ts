// @vitest-environment happy-dom

import { describe, expect, it, beforeEach } from 'vitest';
import './IndexingOverlay.js';
import type { IndexingOverlay } from './IndexingOverlay.js';

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
