// @vitest-environment happy-dom

import { describe, expect, it, beforeEach, vi } from 'vitest';
import './StatusDeck.js';
import type { StatusDeck } from './StatusDeck.js';
import { __resetStatusPollForTest } from '../utils/statusPoll.js';
import { __resetInferencePollForTest } from '../utils/inferencePoll.js';
import { __resetAiStateForTest, type AiState } from '../state/aiStateStore.js';
import { known, UNKNOWN } from '../state/known.js';
import { EPHEMERAL_TOAST_EVENT } from './advisory/ephemeralToast.js';
import { formatCount } from '../display/format.js';

function makeAiState(overrides: Partial<AiState> = {}): AiState {
  return {
    phase: 'connected',
    readiness: UNKNOWN,
    capabilities: { chat: false, rag: false, extract: false, embedding: false },
    connection: { reachable: true, lastSuccessMs: Date.now(), consecutiveFailures: 0 },
    runtime: { mode: 'offline', modelId: null, modelLabel: null, contextWindow: null, gpu: null, installed: known(false), installing: known(false), loadStartedAtMs: null },
    activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
    index: { documentCount: known(0), pendingJobs: known(0), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
    realized: {
      reranker: { loaded: false, accelerator: null, failureReason: null },
      embed: { loaded: false, accelerator: null, failureReason: null },
      splade: { loaded: false, accelerator: null, failureReason: null },
    },
    statusLabel: 'offline',
    statusTier: 'offline',
    stability: { kind: 'settled' },
    verdict: { kind: 'operational', severity: 'ok', reasons: [] },
    status: null,
    inference: null,
    lastSettledIndex: null,
    ...overrides,
  };
}

function make(): StatusDeck {
  const el = document.createElement('jf-status-deck') as StatusDeck;
  document.body.appendChild(el);
  return el;
}

describe('StatusDeck (slice 461)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    __resetStatusPollForTest();
    __resetInferencePollForTest();
    __resetAiStateForTest();
    vi.restoreAllMocks();
    // Mock fetch to avoid network during tests
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false }) as unknown as typeof fetch,
    );
  });

  it('renders default groups when status null (everything muted)', async () => {
    const el = make();
    await el.updateComplete;
    // No status data — connection dot should be muted
    const dot = el.shadowRoot?.querySelector('.dot');
    expect(dot?.classList.contains('muted')).toBe(true);
  });

  it('connection dot turns healthy when head + worker READY', async () => {
    const el = make();
    // B2: status now comes from the ONE observed-state authority (aiState.status).
    el.aiState = makeAiState({
      status: {
        components: { head: { state: 'LIFECYCLE_STATE_READY' }, worker: { state: 'LIFECYCLE_STATE_READY' } },
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const dot = el.shadowRoot?.querySelector('.dot');
    expect(dot?.classList.contains('healthy')).toBe(true);
  });

  it('memory dot turns warn at >80% utilization', async () => {
    const el = make();
    el.aiState = makeAiState({
      status: {
        memoryUsedBytes: 8500,
        memoryMaxBytes: 10000,
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const memDot = el.shadowRoot?.querySelectorAll('.dot')[1];
    expect(memDot?.classList.contains('warn')).toBe(true);
  });

  it('inference status badge tone follows AI state tier', async () => {
    const badgeTone = (el: StatusDeck) =>
      el.shadowRoot?.querySelector('jf-status-badge')?.getAttribute('tone');
    const el = make();
    el.aiState = makeAiState({ statusTier: 'online', statusLabel: 'Online' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('success');
    el.aiState = makeAiState({ statusTier: 'degraded', statusLabel: 'Indexing' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('warning');
    el.aiState = makeAiState({ statusTier: 'offline', statusLabel: 'offline' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('neutral');
    el.aiState = makeAiState({ statusTier: 'disconnected', statusLabel: 'Disconnected' });
    await el.updateComplete;
    expect(badgeTone(el)).toBe('error');
  });

  it('queue group renders when pendingJobs > 0', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
    });
    await el.updateComplete;
    const groups = el.shadowRoot?.querySelectorAll('.group');
    const queueText = Array.from(groups ?? [])
      .map((g) => g.textContent ?? '')
      .find((t) => t.includes('queue:'));
    expect(queueText).toBeTruthy();
  });

  const queueText = (el: StatusDeck) =>
    Array.from(el.shadowRoot?.querySelectorAll('.group') ?? [])
      .map((g) => g.textContent ?? '')
      .find((t) => t.includes('queue:'));

  it('630: queue reads "paused" on the main-surface bar when energy saver is active', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: { power: { energyReduced: true } } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toContain('paused');
  });

  it('630: queue stays the plain active count when not energy-reduced', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(0), pendingJobs: known(5), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: { power: { energyReduced: false } } as unknown as AiState['status'],
    });
    await el.updateComplete;
    expect(queueText(el)).toBeTruthy();
    expect(queueText(el)).not.toContain('paused');
  });

  it('§17.2 — files/size/memory values come from the projectFact authority (one formatter)', async () => {
    const el = make();
    el.aiState = makeAiState({
      index: { documentCount: known(605), pendingJobs: known(0), embeddingPending: known(0), embeddingBlocked: known(false), embeddingQueueSize: known(0), vduQueueSize: known(0) },
      status: {
        worker: { core: { indexSizeBytes: 53_477_376 } },
        memoryUsedBytes: 238_026_752,
      } as unknown as AiState['status'],
    });
    await el.updateComplete;
    const vals = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(vals).toContain('605'); // files
    expect(vals).toContain('51.0 MB'); // size — the shared formatter
    expect(vals).toContain('227.0 MB'); // memory
  });

  it('endpoint label shows api-base attribute', async () => {
    const el = make();
    el.apiBase = 'http://127.0.0.1:33221';
    await el.updateComplete;
    expect(el.shadowRoot?.querySelector('.endpoint')?.textContent).toBe('http://127.0.0.1:33221');
  });

  it('595 §15.1 (E1): a visually-hidden aria-live announcer mirrors the verdict, politely', async () => {
    const announcer = (el: StatusDeck) =>
      el.shadowRoot?.querySelector('[data-testid="verdict-announcer"]');
    const el = make();
    el.aiState = makeAiState({
      verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] },
    });
    await el.updateComplete;
    const a = announcer(el);
    expect(a).not.toBeNull();
    expect(a?.classList.contains('visually-hidden')).toBe(true);
    expect(a?.textContent?.trim()).toBe('Rebuilding…');
    // jf-system-notice reflects live → role=status + aria-live=polite for non-error.
    expect(a?.getAttribute('live')).toBe('status');
  });

  it('595 §15.1 (E1): an error verdict announces assertively', async () => {
    const el = make();
    el.aiState = makeAiState({
      verdict: { kind: 'unreachable', severity: 'error', reasons: [] },
    });
    await el.updateComplete;
    const a = el.shadowRoot?.querySelector('[data-testid="verdict-announcer"]');
    expect(a?.textContent?.trim()).toBe('Backend disconnected');
    expect(a?.getAttribute('live')).toBe('alert');
  });

  // 595 §15.3 (N1) — completion toast on the transitioning → operational edge.
  it('N1: a transitioning → operational edge fires one completion toast', async () => {
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] } });
      await el.updateComplete;
      expect(toasts).toEqual([]); // entering a transition does not toast
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  it('N1: first-load (connecting → operational) and reconnect (unreachable → operational) do NOT toast', async () => {
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'connecting', severity: 'info', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'unreachable', severity: 'error', reasons: [] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual([]);
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  it('N1: a transitioning → checking → operational recovery still fires exactly one toast', async () => {
    // A settled poll whose readiness is momentarily `unknown` resolves to `checking` before
    // `operational`, so the toast must survive the intermediate kind (not require a direct edge).
    const toasts: string[] = [];
    const onToast = (e: Event) => toasts.push((e as CustomEvent).detail.message);
    document.addEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    try {
      const el = make();
      el.aiState = makeAiState({ verdict: { kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'checking', severity: 'info', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual([]); // not yet operational
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']);
      // A later degraded → operational with no preceding transition must stay silent.
      el.aiState = makeAiState({ verdict: { kind: 'degraded', severity: 'warn', reasons: ['x'] } });
      await el.updateComplete;
      el.aiState = makeAiState({ verdict: { kind: 'operational', severity: 'ok', reasons: [] } });
      await el.updateComplete;
      expect(toasts).toEqual(['Index ready — all systems operational']); // still just one
    } finally {
      document.removeEventListener(EPHEMERAL_TOAST_EVENT, onToast);
    }
  });

  // 595 §15.3 (N3) — the system pill is an operable control that opens Health.
  it('N3: the inference-mode pill is a jf-control that navigates to Health on activate', async () => {
    const el = make();
    el.aiState = makeAiState({ statusTier: 'online', statusLabel: 'Online' });
    await el.updateComplete;
    const control = el.shadowRoot?.querySelector('jf-control.status-pill') as
      | (HTMLElement & { onActivate: (() => void) | null })
      | null;
    expect(control).not.toBeNull();
    expect(control?.getAttribute('label')).toContain('Open Health');
    let navTarget: string | null = null;
    el.addEventListener('navigate-with-context', (e) => {
      navTarget = (e as CustomEvent).detail.target;
    });
    control?.onActivate?.();
    expect(navTarget).toBe('core.health-surface');
  });

  // 595 §15.3 (E2) — Files/Size show the last-settled value (dimmed) while provisional.
  it('E2: provisional with a retained last-settled value shows it dimmed, not "…"', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: { documentCount: 1234, indexSizeBytes: 4096 },
    });
    await el.updateComplete;
    const vals = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []);
    const texts = vals.map((v) => v.textContent?.trim());
    // Files (.val[0]) + Size (.val[1]) show the last-known value, not "…". (Memory is a
    // separate Head-process metric and keeps its own provisional treatment.)
    expect(texts[0]).toBe(formatCount(1234));
    expect(texts[0]).not.toBe('…');
    expect(texts[1]).not.toBe('…');
    expect(vals.some((v) => v.classList.contains('stale'))).toBe(true);
  });

  it('E2: provisional with NO retained value falls back to "…"', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: null,
    });
    await el.updateComplete;
    const texts = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(texts).toContain('…');
  });

  it('E2: a retained value with a null size shows last-known Files but "…" for Size (honesty)', async () => {
    const el = make();
    el.aiState = makeAiState({
      stability: { kind: 'provisional', cause: 'worker-restart' },
      lastSettledIndex: { documentCount: 1234, indexSizeBytes: null },
    });
    await el.updateComplete;
    const texts = Array.from(el.shadowRoot?.querySelectorAll('.val') ?? []).map((v) => v.textContent?.trim());
    expect(texts[0]).toBe(formatCount(1234)); // Files last-known
    expect(texts[1]).toBe('…'); // Size: no observed size → not a fake "0 B"
  });
});
