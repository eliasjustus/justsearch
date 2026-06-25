// @vitest-environment happy-dom

/**
 * Slice 490 §4.D — AdvisoryToastHost component tests.
 *
 * Covers the Group A2 hasSeenFirstSnapshot + lastFrameKind heuristic:
 *   - initial state → no toast.
 *   - LIFECYCLE snapshot (any size) → seed seenKeys silently.
 *   - UPDATE → toast each new advisory.
 *   - UPDATE before snapshot → defensive baseline seeding.
 *   - Click on toast → store.acknowledge + dismiss.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import './AdvisoryToastHost.js';
import type { AdvisoryToastHost } from './AdvisoryToastHost.js';
import type {
  AdvisoryListener,
  AdvisoryRecord,
  AdvisorySnapshot,
  AdvisoryStore,
} from './AdvisoryStore.js';

class StubAdvisoryStore {
  private listeners = new Set<AdvisoryListener>();
  private snap: AdvisorySnapshot = {
    advisories: [],
    unreadCount: 0,
    isConnected: true,
    lastFrameKind: 'initial',
  };
  acknowledge = vi.fn();
  dropEphemeral = vi.fn();

  subscribe(listener: AdvisoryListener): () => void {
    this.listeners.add(listener);
    listener(this.snap);
    return () => this.listeners.delete(listener);
  }
  push(s: Partial<AdvisorySnapshot>): void {
    this.snap = { ...this.snap, ...s };
    for (const l of this.listeners) l(this.snap);
  }
}

function rec(
  operationId: string,
  occurredAt: string,
  acknowledged = false,
  sourceRenderHint: 'EPHEMERAL' | 'PERSISTED' | 'REQUIRES_ACK' = 'PERSISTED',
): AdvisoryRecord {
  return {
    key: `operation.completed:${operationId}:SUCCESS`,
    event: {
      classId: 'operation.completed',
      id: `operation.completed:${operationId}:SUCCESS`,
      occurredAt,
      renderHint: sourceRenderHint,
      diagnosticsLink: null,
      provenance: {
        transport: 'BUTTON',
        executor: 'UI',
        initiator: null,
        occurredAt,
      },
      primaryAction: null,
      bodyI18nKey: 'advisory.operation-completed.success',
      classExtras: { operationId, outcome: 'SUCCESS' },
    },
    acknowledged,
    sourceRenderHint,
    origin: "stream",
  };
}

function make(store: AdvisoryStore | null): AdvisoryToastHost {
  const el = document.createElement('jf-advisory-toast-host') as AdvisoryToastHost;
  el.store = store;
  document.body.appendChild(el);
  return el;
}

describe('AdvisoryToastHost (Group A2 + B4)', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initial state renders nothing', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('LIFECYCLE snapshot with advisories seeds seenKeys silently — no toast', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({
      advisories: [rec('core.historical', '2026-05-12T08:00:00Z')],
      lastFrameKind: 'snapshot',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('LIFECYCLE empty snapshot then UPDATE — toasts the new event (Group A2 fix)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // Empty snapshot — the OLD heuristic mis-classified this as "no snapshot
    // seen" and the NEXT UPDATE was treated as snapshot seed.
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);

    // First UPDATE arrives — Group A2 fix asserts it toasts (where the old
    // heuristic would have dropped it).
    store.push({
      advisories: [rec('core.first-real', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    expect(el.visible[0]?.record.event.classExtras.operationId).toBe('core.first-real');
  });

  it('UPDATE arriving before any snapshot — defensive baseline seeds silently', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // Pathological case: an UPDATE arrives before any LIFECYCLE snapshot.
    // Group A2 fix treats this as the implicit baseline (no toast).
    store.push({
      advisories: [rec('core.early-update', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);
  });

  it('toasts each new advisory after the first snapshot', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // First snapshot seeds two historical advisories silently.
    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
      ],
      lastFrameKind: 'snapshot',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(0);

    // Two new updates — each toasts.
    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
        rec('core.new-a', '2026-05-12T09:00:00Z'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    expect(el.visible[0]?.record.event.classExtras.operationId).toBe('core.new-a');

    store.push({
      advisories: [
        rec('core.old-1', '2026-05-12T08:00:00Z'),
        rec('core.old-2', '2026-05-12T08:01:00Z'),
        rec('core.new-a', '2026-05-12T09:00:00Z'),
        rec('core.new-b', '2026-05-12T09:05:00Z'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(2);
  });

  it('Substrate-completion — REQUIRES_ACK toasts have no auto-dismiss timer', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    store.push({
      advisories: [
        rec('core.req-ack', '2026-05-12T09:00:00Z', false, 'REQUIRES_ACK'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    // The REQUIRES_ACK toast must persist (no auto-dismiss). The dispatch is
    // now per-event via record.sourceRenderHint (substrate-completion P2.3).
    expect(el.visible[0]?.timeoutId).toBeNull();
  });

  it('Substrate-completion — EPHEMERAL + PERSISTED records in same snapshot dispatch independently', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    store.push({
      advisories: [
        rec('core.transient', '2026-05-12T09:00:00Z', false, 'EPHEMERAL'),
        rec('core.persisted', '2026-05-12T09:00:01Z', false, 'PERSISTED'),
        rec('core.urgent', '2026-05-12T09:00:02Z', false, 'REQUIRES_ACK'),
      ],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(3);
    // Both EPHEMERAL and PERSISTED have auto-dismiss timers; REQUIRES_ACK doesn't.
    const ephemeralToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.transient',
    );
    const persistedToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.persisted',
    );
    const requiresAckToast = el.visible.find(
      (t) => t.record.event.classExtras.operationId === 'core.urgent',
    );
    expect(ephemeralToast?.timeoutId).not.toBeNull();
    expect(persistedToast?.timeoutId).not.toBeNull();
    expect(requiresAckToast?.timeoutId).toBeNull();
  });

  it('clicking a toast acknowledges + dismisses it', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [], lastFrameKind: 'snapshot' });
    store.push({
      advisories: [rec('core.click', '2026-05-12T09:00:00Z')],
      lastFrameKind: 'update',
    });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    toast.click();
    expect(store.acknowledge).toHaveBeenCalledWith('operation.completed:core.click:SUCCESS');
    expect(el.visible.length).toBe(0);
  });

  // Tempdoc 559 Authority III — local-origin ephemeral records render through the
  // ONE toast host (no second SimpleToast renderer), ungated by frame-kind.
  function localRec(message: string, severity: 'info' | 'success' | 'warning' | 'error'): AdvisoryRecord {
    return {
      key: 'local:1',
      event: {
        classId: 'core.ephemeral',
        id: 'local:1',
        occurredAt: '2026-05-12T09:00:00Z',
        renderHint: 'EPHEMERAL',
        severity,
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        primaryActionKind: null,
        bodyI18nKey: null,
        classExtras: { message },
      },
      acknowledged: false,
      sourceRenderHint: 'EPHEMERAL',
      origin: 'local',
      toast: { message, severity },
    };
  }

  it('559 — renders a local ephemeral record with its message + severity tone, ungated', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    // No snapshot frame at all (lastFrameKind stays 'initial') — local records
    // must toast anyway (they are always live).
    store.push({ advisories: [localRec('Bookmarked', 'success')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    expect(toast.textContent).toContain('Bookmarked');
    // 559 notice-presentation: the tone is on the shared <jf-system-notice>, not
    // a per-toast CSS class.
    const notice = toast.querySelector('jf-system-notice') as HTMLElement;
    expect(notice).not.toBeNull();
    expect(notice.getAttribute('tone')).toBe('success');
    expect(notice.getAttribute('role')).toBe('status');
  });

  // Tempdoc 613 §14 — a local toast's announcement politeness + dwell are a projection of its declared
  // severity. An ERROR announces assertively (role=alert / aria-live=assertive) AND sticks (no auto-dismiss
  // timer) — an error must not silently auto-vanish (the NN/g "toast is a bad way to show an error" fix).
  it('613 §14 — a local ERROR toast announces assertively and is sticky (no auto-dismiss)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Operation failed', 'error')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('tone')).toBe('error');
    expect(notice.getAttribute('role')).toBe('alert');
    expect(notice.getAttribute('aria-live')).toBe('assertive');
    // Sticky: no auto-dismiss timer (it persists until the user clicks to dismiss).
    expect(el.visible[0]?.timeoutId).toBeNull();
  });

  it('613 §14 — a local WARNING announces assertively but is NOT sticky', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Heads up', 'warning')] });
    await el.updateComplete;
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('role')).toBe('alert');
    expect(el.visible[0]?.timeoutId).not.toBeNull();
  });

  it('613 §14 — a local SUCCESS/INFO toast stays polite + auto-dismisses', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Saved', 'success')] });
    await el.updateComplete;
    const notice = el.shadowRoot?.querySelector('jf-system-notice') as HTMLElement;
    expect(notice.getAttribute('role')).toBe('status');
    expect(notice.getAttribute('aria-live')).toBe('polite');
    // Auto-dismiss timer present (not sticky).
    expect(el.visible[0]?.timeoutId).not.toBeNull();
  });

  it('559 — clicking a local toast drops it (not acknowledge — never persisted)', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [localRec('Copied', 'info')] });
    await el.updateComplete;
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    toast.click();
    expect(store.dropEphemeral).toHaveBeenCalledWith('local:1');
    expect(store.acknowledge).not.toHaveBeenCalled();
  });

  // Tempdoc 602 R4 — when a superseding emit drops the prior same-classId record
  // from the store, the host must prune its now-orphaned visible toast so the
  // replacement does not briefly stack beside it.
  function navRec(key: string, message: string): AdvisoryRecord {
    return {
      key,
      event: {
        classId: 'core.navigation',
        id: key,
        occurredAt: '2026-05-12T09:00:00Z',
        renderHint: 'EPHEMERAL',
        severity: 'info',
        diagnosticsLink: null,
        provenance: null,
        primaryAction: null,
        primaryActionKind: null,
        bodyI18nKey: null,
        classExtras: { message },
      },
      acknowledged: false,
      sourceRenderHint: 'EPHEMERAL',
      origin: 'local',
      toast: { message, classId: 'core.navigation', supersede: true },
    };
  }

  it('602 R4 — prunes a superseded local toast that left the snapshot', async () => {
    const store = new StubAdvisoryStore();
    const el = make(store as unknown as AdvisoryStore);
    store.push({ advisories: [navRec('local:1', 'Navigated to Search')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    // The store superseded local:1: it is gone, local:2 is the replacement.
    store.push({ advisories: [navRec('local:2', 'Navigated to Library')] });
    await el.updateComplete;
    expect(el.visible.length).toBe(1);
    const toast = el.shadowRoot?.querySelector('.toast') as HTMLElement;
    expect(toast.textContent).toContain('Navigated to Library');
  });
});
