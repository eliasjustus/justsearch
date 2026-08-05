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

function aiState(opts: { chat?: boolean; docs?: number; snapshotLive?: boolean }): AiState {
  return {
    phase: 'connected',
    // Tempdoc 807 A.3 — a fixture whose subject is not liveness asserts a LIVE snapshot: these tests
    // describe a backend that is answering. The liveness test below passes `false` explicitly.
    snapshotLive: opts.snapshotLive ?? true,
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

  // Tempdoc 807 A.3 (round-13 R13-F2) — the map reads every row off a retained snapshot, so with the
  // backend dead it listed AI capabilities as "Available" while the shell said "Backend disconnected."
  it('807: a not-live snapshot stops the map reporting AI capabilities as available', async () => {
    const el = await mount(aiState({ chat: true, docs: 5, snapshotLive: false }));
    const docRow = rowTexts(el).find((t) => /Ask AI about your documents/.test(t));
    expect(docRow && /Available/.test(docRow)).toBe(false);
    expect(docRow && /connection to the search backend was lost/.test(docRow)).toBe(true);
    // Keyword search never depended on the backend being up in the AI sense — it stays listed.
    expect(rowTexts(el).some((t) => /Search \(keyword\)/.test(t))).toBe(true);
  });

  it('807 ANTI-REGRESSION: the SAME state with a live snapshot is still available', async () => {
    const el = await mount(aiState({ chat: true, docs: 5 }));
    const docRow = rowTexts(el).find((t) => /Ask AI about your documents/.test(t));
    expect(docRow && /Available/.test(docRow)).toBe(true);
  });
});
