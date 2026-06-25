// @vitest-environment happy-dom

/**
 * Render test for LibrarySurface empty-state — tempdoc 595 §4.3 (closes 1.2).
 *
 * A fresh load while the backend is mid-transition (worker restart / rebuild)
 * leaves `roots = []` (the 503 keeps the never-loaded initial value), which used
 * to render the catastrophe-reading "No watched folders". The surface now
 * consults the one Stability axis: while provisional, an empty list renders the
 * transition, not "no folders configured".
 */

import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import './LibrarySurface.js';
import type { LibrarySurface } from './LibrarySurface.js';
import type { PluginHostApi } from '../plugin-api/plugin-types.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';

// A host whose fetch always 503s — reproduces the worker-down window (roots stay []).
const stubHost = {
  platform: { capabilities: new Set<string>() },
  data: { fetch: async () => ({ ok: false, status: 503 }) as unknown as Response },
} as unknown as PluginHostApi;

function feedRebuilding(): void {
  __feedForTest({
    status: {
      worker: {
        core: { indexedDocuments: 0, indexState: 'IDLE', indexHealthy: true },
        migration: {
          migrationState: 'MIGRATING',
          activeGenerationId: 'g1',
          buildingGenerationId: 'g2',
          servingSearchGenerationId: 'g1',
          servingIngestGenerationId: 'g1',
        },
      },
      readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
    } as unknown as StatusSnapshot,
  });
  __tickClockForTest();
}

async function mount(): Promise<LibrarySurface> {
  const el = document.createElement('jf-library-surface') as LibrarySurface;
  el.host_ = stubHost;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('LibrarySurface — transition-aware empty state (595 §4.3)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  it('while the backend is rebuilding, an empty roots list renders the transition, NOT "No watched folders"', async () => {
    feedRebuilding();
    const el = await mount();
    try {
      await el.updateComplete;
      const text = el.shadowRoot?.textContent ?? '';
      expect(text).toContain('Rebuilding index');
      expect(text).not.toContain('No watched folders');
    } finally {
      el.remove();
    }
  });

  it('when settled with no folders, it still shows the genuine "No watched folders" empty state', async () => {
    // settled store (no migration / IDLE)
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true },
          migration: {
            migrationState: 'IDLE',
            activeGenerationId: 'g1',
            buildingGenerationId: '',
            servingSearchGenerationId: 'g1',
            servingIngestGenerationId: 'g1',
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      await el.updateComplete;
      const text = el.shadowRoot?.textContent ?? '';
      expect(text).toContain('No watched folders');
      expect(text).not.toContain('Rebuilding index');
    } finally {
      el.remove();
    }
  });
});
