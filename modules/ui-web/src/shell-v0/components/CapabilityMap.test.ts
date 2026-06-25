// @vitest-environment happy-dom

/**
 * Tempdoc 596 §16.5 — CapabilityMap projects every affordance's availability into one read view. Pins:
 * each row reflects projectAvailability, keyword search is always available, and a blocked row with a
 * remedy renders an inline remedy control.
 */
import { beforeEach, describe, expect, it } from 'vitest';
import './CapabilityMap.js';
import type { CapabilityMap } from './CapabilityMap.js';
import type { AiState } from '../state/aiStateStore.js';
import { known, UNKNOWN } from '../state/known.js';

function aiState(opts: { chat?: boolean; docs?: number }): AiState {
  return {
    phase: 'connected',
    capabilities: { chat: opts.chat ?? true, rag: false, extract: false, embedding: false },
    runtime: { mode: 'online' },
    readiness: UNKNOWN,
    index: {
      documentCount: opts.docs === undefined ? UNKNOWN : known(opts.docs),
      pendingJobs: UNKNOWN,
    },
  } as unknown as AiState;
}

async function mount(state: AiState | null): Promise<CapabilityMap> {
  const el = document.createElement('jf-capability-map') as CapabilityMap;
  el.aiState = state;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

const rowTexts = (el: CapabilityMap): string[] =>
  Array.from(el.shadowRoot?.querySelectorAll('li') ?? []).map((li) => li.textContent?.trim() ?? '');

describe('CapabilityMap (jf-capability-map)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('always lists keyword search as available, regardless of AI state', async () => {
    const el = await mount(null);
    const rows = rowTexts(el);
    expect(rows.some((t) => /Search \(keyword\)/.test(t) && /Available/.test(t))).toBe(true);
  });

  it('AI offline → the AI affordances read their reason', async () => {
    const el = await mount(aiState({ chat: false }));
    const rows = rowTexts(el).join(' | ');
    expect(/offline/i.test(rows)).toBe(true);
  });

  it('chat up + docs present → the documents affordance is available', async () => {
    const el = await mount(aiState({ chat: true, docs: 5 }));
    const docRow = rowTexts(el).find((t) => /Ask AI about your documents/.test(t));
    expect(docRow && /Available/.test(docRow)).toBe(true);
  });

  it('a blocked affordance with a remedy renders an inline remedy control', async () => {
    // chat up but zero docs (idle) → documents unavailable with the "Add documents" navigate remedy.
    const el = await mount(aiState({ chat: true, docs: 0 }));
    const remedyControls = el.shadowRoot?.querySelectorAll('jf-control.cap-remedy');
    expect((remedyControls?.length ?? 0) > 0).toBe(true);
  });
});
