// @vitest-environment happy-dom
/**
 * Tempdoc 550 C1 / thesis II (FE): <jf-action-ledger> renders the unified activity stream as a
 * LIVE read-view — it subscribes to openActionLedgerStream (the /api/action-ledger/stream SSE),
 * whose initial snapshot frame seeds the view and whose UPDATE frames append rows, with the
 * FE-local Effect Journal folded in on every change. There is no separate snapshot-fetch path
 * (tempdoc 550 thesis I: snapshot and live are two reads of one projection, not two code paths),
 * so connection failures are handled by the stream's auto-reconnect, not a fetch-error banner.
 */
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import './ActionLedgerView.js';
import type { ActionLedgerView } from './ActionLedgerView.js';
import { __resetJournalForTest } from '../substrates/effects/index.js';
// tempdoc 612 §UX — the one shared seen-cursor the "new since you looked" marker reads.
import { __resetRecallCursor, getSeenCursor, markSeen } from '../substrates/recall/recallCursor.js';

// Minimal EventSource double (mirrors EnvelopeStream's 'frame' listener contract).
class FakeEventSource {
  listeners: Record<string, Array<(e: unknown) => void>> = {};
  closed = false;
  constructor(public url: string) {}
  addEventListener(type: string, fn: (e: unknown) => void): void {
    (this.listeners[type] ??= []).push(fn);
  }
  removeEventListener(type: string, fn: (e: unknown) => void): void {
    this.listeners[type] = (this.listeners[type] ?? []).filter((f) => f !== fn);
  }
  close(): void {
    this.closed = true;
  }
  emitFrame(envelope: unknown): void {
    for (const fn of this.listeners['frame'] ?? []) fn({ data: JSON.stringify(envelope) });
  }
}

function snapshotFrame(entries: unknown[]): unknown {
  return {
    streamId: 'action-ledger/v1',
    frameKind: 'LIFECYCLE',
    seq: 1,
    ts: '2026-05-26T00:00:00Z',
    resumeToken: 't1',
    payload: { kind: 'snapshot', entries },
  };
}

function updateFrame(row: unknown, seq: number): unknown {
  return {
    streamId: 'action-ledger/v1',
    frameKind: 'UPDATE',
    seq,
    ts: '2026-05-26T00:00:0' + seq + 'Z',
    resumeToken: 't' + seq,
    payload: row,
  };
}

let host: ActionLedgerView;
let es: FakeEventSource | undefined;

/** Create + mount a <jf-action-ledger> wired to a fresh FakeEventSource. */
function mount(): FakeEventSource {
  host = document.createElement('jf-action-ledger') as ActionLedgerView;
  host.eventSourceFactory = (url: string) => {
    es = new FakeEventSource(url);
    return es as unknown as EventSource;
  };
  document.body.appendChild(host);
  return es!;
}

beforeEach(() => {
  __resetJournalForTest();
  __resetRecallCursor(); // tempdoc 612 §UX — deterministic "seen" state per test ('' = never looked).
  es = undefined;
});

afterEach(() => {
  if (host && host.parentNode) host.remove();
});

describe('<jf-action-ledger> (tempdoc 550 C1 / thesis II — live read-view)', () => {
  it('renders the ONE log: a backend operation + an ingested FE-local effect (thesis I)', async () => {
    // Tempdoc 550 thesis I (process-spanning ONE log): the view reads one backend log, no read-time
    // journal fold. An FE-local effect appears because the shell INGESTED it into the log (effect
    // kind); the operation appears as a backend operation row. Both arrive in the one snapshot.
    const src = mount();
    expect(src.url).toContain('/api/action-ledger/stream');
    // Tempdoc 613 §6/§10 — the FE-local example here is a navigate effect, which the default feed now
    // de-floods (routine direct-user navigation). This test is about the ONE log containing BOTH
    // records, so reveal the full view; the de-flood default itself is covered by its own test below.
    host.showRoutine = true;

    src.emitFrame(
      snapshotFrame([
        {
          kind: 'operation',
          occurredAt: '2026-05-26T00:00:00.000Z',
          originator: 'agent',
          operationId: 'core.reindex',
          outcome: 'SUCCESS',
        },
        {
          kind: 'effect',
          occurredAt: '2026-05-26T00:00:01.000Z',
          originator: 'user',
          effectKind: 'navigate',
          subject: '#somewhere',
        },
      ]),
    );
    await host.updateComplete;

    const rows = host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]');
    expect(rows.length).toBe(2);
    const kinds = Array.from(rows).map((r) => r.getAttribute('data-kind'));
    expect(kinds).toContain('operation'); // backend op
    expect(kinds).toContain('effect'); // FE-local effect, ingested into the one log
    const text = host.shadowRoot!.textContent ?? '';
    // §2.A / Q7: the timeline shows the human label, not the raw operation id.
    expect(text).toContain('Reindex');
    expect(text).not.toContain('core.reindex');
    expect(text).toContain('agent');
    expect(text).toContain('user');
  });

  it('de-floods routine direct-user navigation from the default feed, revealed by the routine toggle (tempdoc 613 §6/§10)', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        // A real system event the user opened Activity to see.
        {
          kind: 'index',
          occurredAt: '2026-05-26T00:00:00.000Z',
          originator: 'system',
          collection: 'default',
          state: 'DONE',
        },
        // Two routine direct-user navigations (the §10 flood).
        {
          kind: 'navigation',
          occurredAt: '2026-05-26T00:00:01.000Z',
          originator: 'user',
          targetSurface: 'core.system-surface',
        },
        {
          kind: 'effect',
          occurredAt: '2026-05-26T00:00:02.000Z',
          originator: 'user',
          effectKind: 'navigate',
          subject: '#search',
        },
      ]),
    );
    await host.updateComplete;

    // DEFAULT: the two navigations are excluded; only the system event shows.
    const rowKinds = () =>
      Array.from(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]')).map((r) =>
        r.getAttribute('data-kind'),
      );
    expect(rowKinds()).toEqual(['index']);
    expect(host.shadowRoot!.textContent).not.toContain('Navigate to');

    // The routine toggle advertises how many were hidden.
    const toggle = host.shadowRoot!.querySelector(
      '[data-testid="ledger-routine-toggle"]',
    ) as HTMLElement | null;
    expect(toggle).not.toBeNull();
    expect(toggle!.textContent).toContain('(2)');

    // Reveal: clicking the toggle brings the navigations back (the full view stays reachable).
    toggle!.click();
    await host.updateComplete;
    expect(rowKinds()).toContain('navigation');
    expect(rowKinds()).toContain('effect');
    expect(host.shadowRoot!.textContent).toContain('Navigate to');
  });

  it('projects occurredAt + outcome — the "timing" and "outcomes" the subtitle promises (tempdoc 558 Deepening 3)', async () => {
    // 558 Deepening 3 — the row was a LOSSY projection: occurredAt was on the record + the unified
    // projection but the view dropped it, while ActivitySurface's subtitle promised "outcomes and
    // timing". The row is now a TOTAL projection: it renders a <time> carrying the record's ISO, and
    // the outcome rides the label (operation rows). Assert on the machine-readable datetime (locale-
    // and TZ-independent), not the rendered local-time text.
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        {
          kind: 'operation',
          occurredAt: '2026-05-26T00:00:00.000Z',
          originator: 'agent',
          operationId: 'core.reindex',
          outcome: 'SUCCESS',
        },
      ]),
    );
    await host.updateComplete;

    const time = host.shadowRoot!.querySelector('[data-testid="ledger-time"]');
    expect(time).not.toBeNull();
    expect(time!.tagName.toLowerCase()).toBe('time'); // semantic element (a11y), not a bare span
    expect(time!.getAttribute('datetime')).toBe('2026-05-26T00:00:00.000Z');
    // tempdoc 558 Phase 1 — outcome is a STRUCTURED cell (glyph + tone), not baked into the label.
    const outcome = host.shadowRoot!.querySelector('[data-testid="ledger-outcome"]');
    expect(outcome).not.toBeNull();
    expect(outcome!.getAttribute('data-tone')).toBe('success');
    expect(outcome!.textContent).toContain('SUCCESS');
    // the label no longer carries the " — SUCCESS" suffix (the outcome moved to its own cell).
    const label = host.shadowRoot!.querySelector('[data-testid="ledger-row"] .label');
    expect(label!.textContent).not.toContain('SUCCESS');
  });

  it('renders a FAILURE outcome as the error tone + glyph — failed must read as failed (tempdoc 558)', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        {
          kind: 'operation',
          occurredAt: '2026-05-26T00:00:00.000Z',
          originator: 'agent',
          operationId: 'core.reindex',
          outcome: 'FAILURE',
        },
      ]),
    );
    await host.updateComplete;
    const outcome = host.shadowRoot!.querySelector('[data-testid="ledger-outcome"]');
    expect(outcome).not.toBeNull();
    expect(outcome!.getAttribute('data-tone')).toBe('error');
    expect(outcome!.textContent).toContain('✕'); // the failure glyph (statusGlyph → glyphChar)
    expect(outcome!.textContent).toContain('FAILURE');
  });

  it('shows an empty state when the snapshot has no activity', async () => {
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    await host.updateComplete;

    expect(host.shadowRoot!.querySelector('[data-testid="ledger-empty"]')).not.toBeNull();
  });

  it('appends a live UPDATE row (a trust-gate firing) to the stream', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        { kind: 'operation', operationId: 'core.a', occurredAt: '2026-05-26T00:00:00.000Z', originator: 'agent' },
      ]),
    );
    src.emitFrame(
      updateFrame(
        {
          kind: 'gate',
          operationId: 'core.b',
          disposition: 'GATED',
          gateBehavior: 'TYPED_CONFIRM',
          occurredAt: '2026-05-26T00:00:01.000Z',
          originator: 'agent',
        },
        2,
      ),
    );
    await host.updateComplete;

    const kinds = Array.from(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]')).map((r) =>
      r.getAttribute('data-kind'),
    );
    expect(kinds).toContain('operation');
    expect(kinds).toContain('gate');
  });

  it('collapses an indexing burst into one summary row (thesis III(b) bounded projection)', async () => {
    // Tempdoc 550 thesis III(b): a large indexing run feeds many kind=index terminal outcomes into
    // the one log; the timeline must collapse the adjacent same-collection burst into a single
    // "Indexed N · <collection>" summary rather than render N individual rows.
    const src = mount();
    const indexRows = Array.from({ length: 5 }, (_, i) => ({
      id: `index:2026-05-28T00:00:0${i}.000Z:default:h${i}:DONE`,
      kind: 'index',
      originator: 'system',
      collection: 'default',
      state: 'DONE',
      pathHash: `hash${i}`,
      occurredAt: `2026-05-28T00:00:0${i}.000Z`,
    }));
    src.emitFrame(snapshotFrame(indexRows));
    await host.updateComplete;

    const bursts = host.shadowRoot!.querySelectorAll('[data-testid="ledger-burst"]');
    expect(bursts.length).toBe(1);
    expect(bursts[0]!.textContent).toContain('Indexed 5 · default');
    // tempdoc 558 Deepening 3 — the burst is also a projection of records that occurred at a time:
    // it carries the most-recent occurredAt of the collapsed group (rows are newest-first).
    const burstTime = bursts[0]!.querySelector('[data-testid="ledger-time"]');
    expect(burstTime).not.toBeNull();
    expect(burstTime!.getAttribute('datetime')).toBe('2026-05-28T00:00:04.000Z');
    // The 5 individual index rows must NOT each render as a row.
    const indiv = host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]');
    expect(indiv.length).toBe(0);
  });

  it('filters the stream by originator via FilterChip facets (tempdoc 558 §E1)', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        {
          kind: 'operation',
          occurredAt: '2026-05-26T00:00:00.000Z',
          originator: 'agent',
          operationId: 'core.reindex',
          outcome: 'SUCCESS',
        },
        {
          // Tempdoc 613 §6/§10 — a NON-routine user action (an operation, not navigation) so the
          // who-facet still has two originators after the navigation de-flood. (This test is about
          // originator faceting, not navigation.)
          kind: 'operation',
          occurredAt: '2026-05-26T00:00:01.000Z',
          originator: 'user',
          operationId: 'core.search-index',
          outcome: 'SUCCESS',
        },
      ]),
    );
    await host.updateComplete;
    // both rows present + a "who" facet bar (two distinct originators)
    expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]').length).toBe(2);
    const chips = host.shadowRoot!.querySelectorAll(
      '[data-testid="ledger-filters"] jf-filter-chip',
    );
    const agentChip = Array.from(chips).find((c) => (c.textContent ?? '').includes('agent'));
    expect(agentChip).toBeTruthy();
    // activating the agent facet narrows the stream to the agent row only
    (agentChip as HTMLElement).click();
    await host.updateComplete;
    const rows = host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]');
    expect(rows.length).toBe(1);
    expect(rows[0]!.querySelector('.who')?.textContent).toContain('agent');
  });

  // tempdoc 612 §3/§L — the routine de-flood widened beyond navigation to witnessed local-ack /
  // preference effects; the toggle is relabelled to name the whole class.
  it('de-floods witnessed preference effects (set-appearance) and labels the toggle "routine"', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        { kind: 'index', occurredAt: '2026-05-26T00:00:00.000Z', originator: 'system', collection: 'default', state: 'DONE' },
        { kind: 'effect', occurredAt: '2026-05-26T00:00:01.000Z', originator: 'user', effectKind: 'set-appearance', subject: '' },
        { kind: 'effect', occurredAt: '2026-05-26T00:00:02.000Z', originator: 'user', effectKind: 'save-settings', subject: '' },
      ]),
    );
    await host.updateComplete;

    const rowKinds = () =>
      Array.from(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]')).map((r) => r.getAttribute('data-kind'));
    // DEFAULT: the two preference effects are de-flooded; only the system event shows.
    expect(rowKinds()).toEqual(['index']);

    const toggle = host.shadowRoot!.querySelector('[data-testid="ledger-routine-toggle"]') as HTMLElement | null;
    expect(toggle).not.toBeNull();
    expect(toggle!.textContent).toContain('routine'); // relabelled from "navigation"
    expect(toggle!.textContent).toContain('(2)');

    toggle!.click();
    await host.updateComplete;
    expect(rowKinds()).toContain('effect'); // revealed
  });

  it('shows an "Only routine activity" curated-empty state with a reveal affordance', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        { kind: 'effect', occurredAt: '2026-05-26T00:00:00.000Z', originator: 'user', effectKind: 'set-ui-mode', subject: '' },
        { kind: 'navigation', occurredAt: '2026-05-26T00:00:01.000Z', originator: 'user', targetSurface: 'core.system-surface' },
      ]),
    );
    await host.updateComplete;

    // No rows survive curation, but it's because everything was routine — not a real filter miss.
    expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]').length).toBe(0);
    const empty = host.shadowRoot!.querySelector('[data-testid="ledger-empty"]');
    expect(empty!.textContent).toContain('Only routine activity');
    expect(empty!.textContent).not.toContain('No activity matches');

    // The inline reveal brings the routine rows back.
    const reveal = host.shadowRoot!.querySelector('[data-testid="ledger-reveal-routine"]') as HTMLElement | null;
    expect(reveal).not.toBeNull();
    reveal!.click();
    await host.updateComplete;
    expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]').length).toBe(2);
  });

  it('closes the live stream when disconnected', async () => {
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    host.remove();
    expect(src.closed).toBe(true);
  });

  // tempdoc 612 §UX — "new since you looked": foreground rows newer than the one shared seen-cursor get
  // a mark + a header count; "mark all read" advances the cursor; routine rows are never marked.
  describe('unread "new since you looked" marker', () => {
    const foregroundRows = [
      { kind: 'index', occurredAt: '2026-05-26T00:00:02.000Z', originator: 'system', collection: 'c', state: 'DONE' },
      { kind: 'operation', occurredAt: '2026-05-26T00:00:03.000Z', originator: 'agent', operationId: 'core.reindex', outcome: 'SUCCESS' },
      // a routine row (must NEVER be marked new even when newer than the cursor)
      { kind: 'navigation', occurredAt: '2026-05-26T00:00:09.000Z', originator: 'user', targetSurface: 'core.system-surface' },
    ];

    it('marks foreground rows newer than the cursor as new, with a header count (cursor = never looked)', async () => {
      const src = mount(); // beforeEach reset the cursor to '' → everything is new
      src.emitFrame(snapshotFrame(foregroundRows));
      host.showRoutine = true; // reveal the routine row too, to prove it is NOT marked
      await host.updateComplete;

      const newDots = host.shadowRoot!.querySelectorAll('[data-testid="ledger-new-dot"]');
      expect(newDots.length).toBe(2); // the 2 foreground rows, NOT the routine navigation
      const count = host.shadowRoot!.querySelector('[data-testid="ledger-new-count"]');
      expect(count!.textContent).toContain('2');
      // the routine navigation row is present (revealed) but carries no new-dot
      const navRow = Array.from(host.shadowRoot!.querySelectorAll('[data-testid="ledger-row"]')).find(
        (r) => r.getAttribute('data-kind') === 'navigation',
      );
      expect(navRow).toBeTruthy();
      expect(navRow!.querySelector('[data-testid="ledger-new-dot"]')).toBeNull();
    });

    it('shows no marks when the cursor is already current', async () => {
      markSeen('2026-05-26T00:00:59.000Z'); // seen past all rows
      const src = mount();
      src.emitFrame(snapshotFrame(foregroundRows));
      await host.updateComplete;
      expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-new-dot"]').length).toBe(0);
      expect(host.shadowRoot!.querySelector('[data-testid="ledger-new-count"]')).toBeNull();
    });

    it('"mark all read" advances the one shared cursor and clears the marks', async () => {
      const src = mount();
      src.emitFrame(snapshotFrame(foregroundRows));
      await host.updateComplete;
      expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-new-dot"]').length).toBe(2);

      (host.shadowRoot!.querySelector('[data-testid="ledger-mark-read"]') as HTMLElement).click();
      await host.updateComplete;

      // the cursor advanced to the newest FOREGROUND row (the agent op at :03, not the routine nav at :09)
      expect(getSeenCursor()).toBe('2026-05-26T00:00:03.000Z');
      expect(host.shadowRoot!.querySelectorAll('[data-testid="ledger-new-dot"]').length).toBe(0);
    });
  });
});
