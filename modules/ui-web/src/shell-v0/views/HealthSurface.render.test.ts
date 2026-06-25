// @vitest-environment happy-dom

/**
 * Render test for HealthSurface — `recommendedActions` panel
 * (slice 447-followup-live-wiring §X.12.8 Item 2.3).
 *
 * Phase 4 of §X.11.5 added the "Recommended for current conditions"
 * sub-section to HealthSurface. The HealthLitView/HealthSurface
 * mix-up surfaced via §X.12 live testing (commit f68deb830) ported
 * the panel to the rail-mounted production component, but no
 * regression test guarded the panel from silent removal during
 * future refactors. This test fills that gap.
 */

import { describe, expect, it, beforeEach, afterEach } from 'vitest';
import './HealthSurface.js';
import type { HealthSurface } from './HealthSurface.js';
import {
  __resetAiStateForTest,
  __feedForTest,
  __tickClockForTest,
  type StatusSnapshot,
} from '../state/aiStateStore.js';
import { formatCount } from '../display/format.js';

async function mount(): Promise<HealthSurface> {
  const el = document.createElement('jf-health-surface') as HealthSurface;
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

function teardown(el: HealthSurface): void {
  el.remove();
}

describe('HealthSurface — recommendedActions panel', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  // 595: a ready readiness composite fed to the ONE store → the verdict is
  // `operational`, which both header and footer consume.
  function feedReady(over: Record<string, unknown> = {}): void {
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
        ...over,
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  it('renders "All healthy" when recommendedActions is empty and the verdict is operational', async () => {
    feedReady();
    const el = await mount();
    try {
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section).not.toBeNull();
      expect(section?.textContent).toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('595 §1.1: header and footer agree on the unknown boundary — both "Checking…", NEVER a split', async () => {
    // retrieval 'unknown' (settled) was the boundary where the header alarmed
    // ("Service degraded") while the footer greened ("✓ All systems operational").
    // With one verdict, both must read the SAME non-green, non-alarming "Checking…".
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: {
          composites: {
            retrieval: { state: 'UNKNOWN', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      const footer = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(badge?.textContent).toContain('Checking');
      expect(footer?.textContent).toContain('Checking');
      // The contradiction must be GONE on both surfaces.
      expect(badge?.textContent).not.toContain('Service degraded');
      expect(footer?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('595 §10.3: a cosmetic degradation (LambdaMART) reads calm on both surfaces (not an alarm)', async () => {
    feedReady({
      readiness: {
        composites: {
          retrieval: { state: 'DEGRADED', reasonCodes: ['lambdamart.not_configured'] },
          aiFeatures: { state: 'READY', reasonCodes: [] },
        },
      },
    });
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      expect(badge?.textContent).toContain('Reduced capability');
      expect(badge?.getAttribute('tone')).toBe('info'); // calm, not 'warning'
      const footer = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(footer?.textContent).toContain('Reduced capability');
    } finally {
      teardown(el);
    }
  });

  it('§2.C: shows "Connecting…" (not all-operational) before health data arrives', async () => {
    const el = await mount();
    try {
      // Fresh mount, no poll completed → must NOT fail-open to "operational".
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section?.textContent).toContain('Connecting');
      expect(section?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('renders one button per entry when recommendedActions is populated', async () => {
    const el = await mount();
    try {
      el.recommendedActions = new Map([
        ['schema.reindex-required|worker.schema', 'core.reindex'],
        ['index.unavailable|worker', 'core.rebuild-index'],
      ]);
      await el.updateComplete;

      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section).not.toBeNull();

      const buttons = el.shadowRoot?.querySelectorAll(
        '.card.section.recommended jf-button',
      );
      expect(buttons?.length).toBe(2);

      const buttonText = Array.from(buttons ?? [])
        .map((b) => b.textContent?.trim())
        .join(' | ');
      // §2.A: the visible label is humanized via present({kind:'condition'}) —
      // the raw dotted condition id must NOT reach the user (Q7 leak class).
      expect(buttonText).toContain('Schema Reindex Required');
      expect(buttonText).toContain('Index Unavailable');
      expect(buttonText).not.toContain('schema.reindex-required');
      expect(buttonText).not.toContain('index.unavailable');
    } finally {
      teardown(el);
    }
  });

  it('renders entries in sorted (conditionId, subject) order for stable display', async () => {
    const el = await mount();
    try {
      // Insertion order is reverse-alphabetical; the render should sort by key.
      el.recommendedActions = new Map([
        ['z.condition|sub', 'core.z-op'],
        ['a.condition|sub', 'core.a-op'],
      ]);
      await el.updateComplete;

      const buttons = el.shadowRoot?.querySelectorAll(
        '.card.section.recommended jf-button',
      );
      const texts = Array.from(buttons ?? []).map((b) =>
        b.textContent?.trim() ?? '',
      );
      expect(texts.length).toBe(2);
      // a.* sorts before z.*; assert button ordering reflects key sort.
      // Labels are humanized (present({kind:'condition'})): "A Condition" / "Z Condition".
      expect(texts[0]).toContain('A Condition');
      expect(texts[1]).toContain('Z Condition');
    } finally {
      teardown(el);
    }
  });

  it('B7/B2: reads observed-state from the store; an impairing degraded readiness → "Service degraded"', async () => {
    // Feed the ONE store (no local /api/status poll) with an impairing degraded
    // retrieval composite. HealthSurface must pick it up via its store
    // subscription and refuse to claim "operational" (no reasonCodes ⇒ warn).
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 5, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'DEGRADED', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const section = el.shadowRoot?.querySelector('.card.section.recommended');
      expect(section?.textContent).toContain('Service degraded');
      expect(section?.textContent).not.toContain('All systems operational');
    } finally {
      teardown(el);
    }
  });

  it('button title includes the conditionId and subject for accessibility', async () => {
    const el = await mount();
    try {
      el.recommendedActions = new Map([
        ['schema.reindex-required|worker.schema', 'core.reindex'],
      ]);
      await el.updateComplete;

      const button = el.shadowRoot?.querySelector(
        '.card.section.recommended jf-button',
      ) as HTMLButtonElement | null;
      expect(button).not.toBeNull();
      expect(button?.title).toContain('schema.reindex-required');
      expect(button?.title).toContain('worker.schema');
    } finally {
      teardown(el);
    }
  });

  it('595 §4.3: during a worker-restart provisional, the Files/Size stat cards show "…" not a settled "0"', async () => {
    // indexState UNAVAILABLE ⇒ the worker-down fallback (docs/size = 0). Those
    // worker-sourced cards must render "…" (provisional), never a settled "0".
    // Memory is Head-process state and stays.
    __feedForTest({
      status: {
        worker: {
          core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false },
        },
        memoryUsedBytes: 1234567,
        memoryMaxBytes: 8000000,
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      // Files (first) + Size (second) provisional → "…", not "0" / "0 B".
      expect(values[0]).toBe('…');
      expect(values[0]).not.toBe('0');
      expect(values[1]).toBe('…');
      // Memory (third) is Head-process state — still a real value.
      expect(values[2]).not.toBe('…');
      expect(values[2]).toMatch(/B$/); // formatBytes output (e.g. "1.2 MB")
    } finally {
      teardown(el);
    }
  });

  it('595 §15.2 (E4): an OVERDUE rebuild escalates the header to "taking longer than expected" (warning)', async () => {
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
            migrationSwitchingAgeMs: 120000,
            migrationSwitchingMaxDurationMs: 60000,
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const badge = el.shadowRoot?.querySelector('.header jf-status-badge');
      expect(badge?.textContent).toContain('taking longer than expected');
      expect(badge?.getAttribute('tone')).toBe('warning'); // escalated, no longer calm
    } finally {
      teardown(el);
    }
  });

  // 595 §15.3 (E2) — last-settled retention: Files/Size keep the last good value (dimmed)
  // across a provisional window instead of collapsing to "…".
  it('E2: after a settled poll, a worker-restart provisional shows the last-known Files (not "…")', async () => {
    // 1) a settled poll stamps the retained value.
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 1234, indexSizeBytes: 4096, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    // 2) the worker restarts: a successful poll returns the fallback (0 / UNAVAILABLE).
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false } },
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      expect(values[0]).toBe(formatCount(1234)); // last-known Files, NOT "…" and NOT "0"
      expect(values[0]).not.toBe('…');
      const subs = Array.from(el.shadowRoot?.querySelectorAll('.card-sub') ?? []).map(
        (s) => s.textContent?.trim() ?? '',
      );
      expect(subs).toContain('Last known');
    } finally {
      teardown(el);
    }
  });

  it('E2: when the settled poll had no size, Size shows "…" while Files shows the last-known count', async () => {
    // A settled snapshot with a doc count but no indexSizeBytes → size retained as null. During a
    // later provisional window Files shows the last-known count; Size must NOT assert a fake "0 B".
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 1234, indexState: 'IDLE', indexHealthy: true } },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    __feedForTest({
      status: {
        worker: { core: { indexedDocuments: 0, indexState: 'UNAVAILABLE', indexHealthy: false } },
        readiness: { composites: { retrieval: { state: 'UNKNOWN', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const values = Array.from(el.shadowRoot?.querySelectorAll('.card-value') ?? []).map(
        (v) => v.textContent?.trim() ?? '',
      );
      expect(values[0]).toBe(formatCount(1234)); // Files last-known
      expect(values[1]).toBe('…'); // Size: no observed size → not "0 B"
    } finally {
      teardown(el);
    }
  });

  // 595 §15.3 (E3) — an indicative rebuild progress bar during a MIGRATING window.
  it('E3: a rebuild renders a progressbar with the clamped building/active fraction', async () => {
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
            activeIndexedDocuments: 1000,
            buildingIndexedDocuments: 600,
          },
        },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const bar = el.shadowRoot?.querySelector('[role="progressbar"]');
      expect(bar).not.toBeNull();
      expect(bar?.getAttribute('aria-valuenow')).toBe('60'); // 600/1000, clamped
      expect(bar?.textContent).toContain('Rebuilding…');
      const inner = bar?.querySelector('.progress-bar') as HTMLElement | null;
      expect(inner?.style.width).toBe('60%');
    } finally {
      teardown(el);
    }
  });

  it('E3: no progressbar when settled', async () => {
    feedReady();
    const el = await mount();
    try {
      expect(el.shadowRoot?.querySelector('[role="progressbar"]')).toBeNull();
    } finally {
      teardown(el);
    }
  });
});

// 630 D1 — the Queue card's calm status vocabulary + the explicit "Up to date" terminal close.
describe('HealthSurface — Queue card status vocabulary (630 D1)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetAiStateForTest();
  });
  afterEach(() => __resetAiStateForTest());

  function feed(core: Record<string, unknown>): void {
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', ...core } },
        readiness: {
          composites: {
            retrieval: { state: 'READY', reasonCodes: [] },
            aiFeatures: { state: 'READY', reasonCodes: [] },
          },
        },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
  }

  const queueSubs = (el: HealthSurface) =>
    Array.from(el.shadowRoot?.querySelectorAll('.card-sub') ?? []).map((s) => s.textContent?.trim() ?? '');

  it('idle + verified-healthy index ⇒ "Up to date" (the terminal trust state), never "Idle"', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 0, indexHealthy: true });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Up to date');
      expect(subs).not.toContain('Idle');
    } finally {
      teardown(el);
    }
  });

  it('idle but NOT confirmed-healthy ⇒ stays honest "Idle" (no false "Up to date")', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 0, indexHealthy: false });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Idle');
      expect(subs).not.toContain('Up to date');
    } finally {
      teardown(el);
    }
  });

  it('queued work ⇒ "Indexing"', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 5, indexHealthy: true });
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Indexing');
      expect(subs).not.toContain('Up to date');
    } finally {
      teardown(el);
    }
  });

  it('energy-pause still wins precedence over the working label', async () => {
    feed({ indexedDocuments: 5, pendingJobs: 5, indexHealthy: true, power: undefined });
    // power lives at the top level of the status, not under core — feed it directly.
    __feedForTest({
      status: {
        worker: { core: { indexState: 'IDLE', indexedDocuments: 5, pendingJobs: 5, indexHealthy: true } },
        power: { energyReduced: true },
        readiness: { composites: { retrieval: { state: 'READY', reasonCodes: [] } } },
      } as unknown as StatusSnapshot,
    });
    __tickClockForTest();
    const el = await mount();
    try {
      const subs = queueSubs(el);
      expect(subs).toContain('Paused — saving energy');
      expect(subs).not.toContain('Indexing');
    } finally {
      teardown(el);
    }
  });
});
