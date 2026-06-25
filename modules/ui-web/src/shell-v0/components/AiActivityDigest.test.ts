// @vitest-environment happy-dom

/**
 * §32 U3 / Tempdoc 577 §2.14 Root I (#20) — <jf-ai-activity-digest> render tests.
 *
 * The digest now projects the ONE 550 federated ledger (openActionLedgerStream — the same live
 * read-view <jf-action-ledger> renders) since the ONE shared seen-cursor (recallCursor), not a
 * private read of the FE Effect Journal. So these drive it through an injected EventSource emitting
 * agent ledger rows. The undo/macro paths still read the journal (the undo authority), so those
 * tests record journal effects AND emit a matching ledger row (the production ingest round-trip).
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { AiActivityDigest } from './AiActivityDigest.js';
import {
  recordEffect,
  __resetJournalForTest,
} from '../substrates/effects/index.js';
import {
  __resetPendingForTest,
  proposeEffect,
} from '../substrates/pending-effects/index.js';
import {
  resolveElicit,
  listPendingElicits,
  __resetElicitForTest,
} from '../substrates/elicit/index.js';
import { listMacros, __resetMacrosForTest } from '../substrates/macros/index.js';
import { __resetActionsForTest } from '../substrates/actions/index.js';
import { __resetRecallCursor } from '../substrates/recall/recallCursor.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

void AiActivityDigest;

// Minimal EventSource double (mirrors EnvelopeStream's 'frame' listener contract — same as
// ActionLedgerView.test.ts, since the digest now reads the same stream).
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

/** An agent ledger row (the snapshot/update payload shape openActionLedgerStream projects). */
function agentRow(p: { kind: string; occurredAt: string; effectKind?: string; operationId?: string }): unknown {
  return {
    id: `${p.kind}:${p.occurredAt}`,
    kind: p.kind,
    occurredAt: p.occurredAt,
    originator: 'agent',
    ...(p.effectKind ? { effectKind: p.effectKind } : {}),
    ...(p.operationId ? { operationId: p.operationId } : {}),
  };
}

let host: AiActivityDigest;
let es: FakeEventSource | undefined;

function mount(): FakeEventSource {
  host = document.createElement('jf-ai-activity-digest') as AiActivityDigest;
  host.eventSourceFactory = (url: string) => {
    es = new FakeEventSource(url);
    return es as unknown as EventSource;
  };
  document.body.appendChild(host);
  return es!;
}

beforeEach(() => {
  __resetJournalForTest();
  __resetPendingForTest();
  __resetElicitForTest();
  __resetMacrosForTest();
  __resetActionsForTest();
  __resetRecallCursor();
  globalThis.localStorage?.clear();
  es = undefined;
});

afterEach(() => {
  if (host && host.parentNode) host.remove();
});

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
}

/** 574 B — digest actions are <jf-button>; activate the native button in the composed control. */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('<jf-ai-activity-digest> (§32 U3 / 577 #20)', () => {
  it('collapses (data-empty) when the unified log has no agent activity', async () => {
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  it('summarizes agent activity by kind (from the one log) and offers undo-all', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        agentRow({ kind: 'navigate', effectKind: 'navigate', occurredAt: '2026-05-26T00:00:01.000Z' }),
        agentRow({ kind: 'navigate', effectKind: 'navigate', occurredAt: '2026-05-26T00:00:02.000Z' }),
      ]),
    );
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    const summary = host.shadowRoot?.querySelector('[data-testid="ai-digest-summary"]');
    expect(summary?.textContent).toContain('2 navigates');
    expect(host.shadowRoot?.querySelector('[data-testid="ai-digest-undo-all"]')).not.toBeNull();
  });

  it('"Mark as seen" advances the shared cursor and collapses the digest', async () => {
    const src = mount();
    src.emitFrame(
      snapshotFrame([agentRow({ kind: 'operation', occurredAt: '2026-05-26T00:00:01.000Z' })]),
    );
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="ai-digest-mark-seen"]'));
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  it('reacts to a live UPDATE row after mount (stream subscription)', async () => {
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
    // A live agent operation appended to the one log must re-render the digest.
    src.emitFrame(
      updateFrame(agentRow({ kind: 'operation', occurredAt: '2026-05-26T00:00:02.000Z' }), 2),
    );
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    expect(
      host.shadowRoot?.querySelector('[data-testid="ai-digest-summary"]')?.textContent,
    ).toContain('1 operation');
  });

  it('543-fwd #8 — "Undo all AI actions" stages a confirm preview, then confirm undoes', async () => {
    // The undo authority is the journal; the digest visibility derives from the one log. So record
    // the reversible agent effects (journal) AND emit matching ledger rows (the production ingest).
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'open-modal', modalId: 'b' }, CORE_PROVENANCE, { originator: 'agent' });
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        agentRow({ kind: 'open-pane', effectKind: 'open-pane', occurredAt: '2026-05-26T00:00:01.000Z' }),
        agentRow({ kind: 'open-modal', effectKind: 'open-modal', occurredAt: '2026-05-26T00:00:02.000Z' }),
      ]),
    );
    await flush();
    const closes: string[] = [];
    const onClosePane = ((e: Event) => closes.push(`pane:${(e as CustomEvent).detail.paneId}`)) as EventListener;
    const onCloseModal = ((e: Event) => closes.push(`modal:${(e as CustomEvent).detail.modalId}`)) as EventListener;
    document.addEventListener('jf-close-pane', onClosePane);
    document.addEventListener('jf-close-modal', onCloseModal);
    try {
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="ai-digest-undo-all"]'));
      await flush();
      const rows = host.shadowRoot?.querySelectorAll('[data-testid="ai-digest-undo-row"]') ?? [];
      expect(rows.length).toBe(2);
      expect(closes).toEqual([]);
      await activateJfButton(host.shadowRoot?.querySelector('[data-testid="ai-digest-undo-confirm-yes"]'));
      await flush();
      expect(closes).toEqual(['modal:b', 'pane:a']);
      expect(host.shadowRoot?.querySelector('[data-testid="ai-digest-undo-confirm"]')).toBeNull();
    } finally {
      document.removeEventListener('jf-close-pane', onClosePane);
      document.removeEventListener('jf-close-modal', onCloseModal);
    }
  });

  it('543-fwd #9 — "Save as macro" captures the agent\'s actions into a macro', async () => {
    recordEffect({ kind: 'invoke-operation', operationId: 'core_search_index' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, { originator: 'agent' });
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        agentRow({ kind: 'operation', operationId: 'core_search_index', occurredAt: '2026-05-26T00:00:01.000Z' }),
        agentRow({ kind: 'navigate', effectKind: 'navigate', occurredAt: '2026-05-26T00:00:02.000Z' }),
      ]),
    );
    await flush();
    const btn = host.shadowRoot?.querySelector('[data-testid="ai-digest-save-macro"]');
    expect(btn).not.toBeNull();
    await activateJfButton(btn);
    await flush();
    const reqs = listPendingElicits();
    expect(reqs).toHaveLength(1);
    resolveElicit(reqs[0]!.id, { label: 'What the AI did' });
    await flush();
    const macros = listMacros();
    expect(macros).toHaveLength(1);
    expect(macros[0]!.label).toBe('What the AI did');
    expect(macros[0]!.effects.map((e) => e.kind)).toEqual(['invoke-operation', 'navigate']);
  });

  it('577 #16 — the undo-confirm names the irreversible remainder (honest partial undo)', async () => {
    // open-pane is reversible (inverse derivable); toast is irreversible (no inverse). The confirm
    // preview lists the reversible one AND must say the irreversible one will remain.
    recordEffect({ kind: 'open-pane', paneId: 'a' }, CORE_PROVENANCE, { originator: 'agent' });
    recordEffect({ kind: 'toast', message: 'noted' }, CORE_PROVENANCE, { originator: 'agent' });
    const src = mount();
    src.emitFrame(
      snapshotFrame([
        agentRow({ kind: 'open-pane', effectKind: 'open-pane', occurredAt: '2026-05-26T00:00:01.000Z' }),
        agentRow({ kind: 'toast', effectKind: 'toast', occurredAt: '2026-05-26T00:00:02.000Z' }),
      ]),
    );
    await flush();
    await activateJfButton(host.shadowRoot?.querySelector('[data-testid="ai-digest-undo-all"]'));
    await flush();
    const rows = host.shadowRoot?.querySelectorAll('[data-testid="ai-digest-undo-row"]') ?? [];
    expect(rows.length, 'only the reversible action is previewed for undo').toBe(1);
    const note = host.shadowRoot?.querySelector('[data-testid="ai-digest-irreversible"]');
    expect(note, 'the irreversible remainder is named').not.toBeNull();
    expect(note?.textContent).toContain("can't be undone");
  });

  it('shows the agent-pending badge for pending agent proposals (independent of the log)', async () => {
    proposeEffect({ kind: 'noop' }, CORE_PROVENANCE, 'agent');
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    const badge = host.shadowRoot?.querySelector('[data-testid="ai-digest-pending"]');
    expect(badge).not.toBeNull();
    expect(badge?.textContent).toContain('1 pending');
  });

  it('closes the live stream when disconnected', async () => {
    const src = mount();
    src.emitFrame(snapshotFrame([]));
    await flush();
    host.remove();
    expect(src.closed).toBe(true);
  });
});
