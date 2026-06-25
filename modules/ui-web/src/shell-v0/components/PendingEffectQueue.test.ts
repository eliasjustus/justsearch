// @vitest-environment happy-dom

/**
 * Tempdoc 543 §21.E — PendingEffectQueue chrome surface tests.
 *
 * Verifies the accept/reject buttons drive the underlying substrate
 * and that the surface collapses to data-empty when the queue drains.
 */

import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { PendingEffectQueue } from './PendingEffectQueue.js';
import {
  proposeEffect,
  listPending,
  __resetPendingForTest,
} from '../substrates/pending-effects/index.js';
import {
  listJournal,
  __resetJournalForTest,
} from '../substrates/effects/index.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';
import {
  __seedForTest as __seedOpsForTest,
  __resetForTest as __resetOpsForTest,
} from '../../api/registry/OperationCatalogClient.js';
import type { Operation, OperationCatalog } from '../../api/types/registry.js';

// Ensure registration of the custom element.
void PendingEffectQueue;

// §32 #2B — minimal operation-catalog seed so getOperation(id).policy.risk
// resolves at the accept gate.
function opWithRisk(id: string, risk: 'LOW' | 'MEDIUM' | 'HIGH'): Operation {
  return {
    id,
    presentation: { labelKey: id, descriptionKey: id, iconHint: null, category: null },
    intf: { errors: [], inputs: {}, result: {}, uiHints: {} },
    policy: { risk, confirm: { kind: 'NONE' }, audit: 'METADATA_ONLY', undoSupported: false },
    availability: {},
    lineage: { affects: [], supersedes: [] },
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executors: ['UI'],
    audience: 'USER',
    consumers: [],
  };
}
function seedOp(id: string, risk: 'LOW' | 'MEDIUM' | 'HIGH'): void {
  const catalog: OperationCatalog = {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'core',
    primitive: 'Operation',
    entries: [opWithRisk(id, risk)],
  };
  __seedOpsForTest(catalog);
}

let host: HTMLElement;

beforeEach(() => {
  __resetPendingForTest();
  __resetJournalForTest();
  __resetOpsForTest();
  host = document.createElement('jf-pending-effect-queue');
  document.body.appendChild(host);
});

afterEach(() => {
  document.body.removeChild(host);
  __resetOpsForTest();
});

async function flush(): Promise<void> {
  // Lit's render cycle is async; let microtasks run.
  await Promise.resolve();
  await Promise.resolve();
}

/**
 * 574 B (remediation) — accept/reject are <jf-button>; the action fires from the native <button>
 * inside the composed <jf-control>, not a host click. Awaits both render passes, then clicks.
 */
async function activateJfButton(el: Element | null | undefined): Promise<void> {
  if (!el) throw new Error('activateJfButton: element not found');
  await (el as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  const control = el.shadowRoot!.querySelector('jf-control')!;
  await (control as unknown as { updateComplete: Promise<unknown> }).updateComplete;
  (control.shadowRoot!.querySelector('button') as HTMLButtonElement).click();
}

describe('<jf-pending-effect-queue> (§21.E)', () => {
  it('renders nothing when queue is empty (sets data-empty)', async () => {
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(true);
  });

  it('renders a card when a Pending is proposed', async () => {
    proposeEffect({ kind: 'navigate', to: '#x' }, CORE_PROVENANCE, 'agent', {
      rationale: 'AI thinks you want this',
    });
    await flush();
    expect(host.hasAttribute('data-empty')).toBe(false);
    const cards = host.shadowRoot?.querySelectorAll('.card') ?? [];
    expect(cards.length).toBe(1);
    const card = cards[0] as HTMLElement;
    expect(card.getAttribute('data-originator')).toBe('agent');
    expect(card.querySelector('.kind')?.textContent).toBe('navigate');
    expect(card.querySelector('.rationale')?.textContent?.trim()).toBe(
      'AI thinks you want this',
    );
  });

  it('Accept button dispatches and clears the queue', async () => {
    proposeEffect({ kind: 'open-modal', modalId: 'm1' }, CORE_PROVENANCE, 'agent');
    await flush();
    const acceptBtn = host.shadowRoot?.querySelector('jf-button.accept');
    expect(acceptBtn).not.toBeNull();
    await activateJfButton(acceptBtn);
    await flush();
    expect(listPending()).toHaveLength(0);
    expect(host.hasAttribute('data-empty')).toBe(true);
    // Journal records: applyEffect's own entry + acceptPending's outcome entry.
    expect(listJournal()).toHaveLength(2);
    expect(listJournal()[1]!.pendingOutcome).toBe('accepted');
  });

  it('Reject button drops without dispatching', async () => {
    proposeEffect({ kind: 'navigate', to: '#y' }, CORE_PROVENANCE, 'agent');
    await flush();
    const rejectBtn = host.shadowRoot?.querySelector('jf-button.reject');
    expect(rejectBtn).not.toBeNull();
    await activateJfButton(rejectBtn);
    await flush();
    expect(listPending()).toHaveLength(0);
    const entries = listJournal();
    expect(entries).toHaveLength(1);
    expect(entries[0]!.pendingOutcome).toBe('rejected');
  });

  it('renders multiple cards for multiple Pendings', async () => {
    proposeEffect({ kind: 'navigate', to: '#a' }, CORE_PROVENANCE, 'agent');
    proposeEffect({ kind: 'navigate', to: '#b' }, CORE_PROVENANCE, 'system');
    await flush();
    const cards = host.shadowRoot?.querySelectorAll('.card') ?? [];
    expect(cards.length).toBe(2);
    expect(
      (cards[0] as HTMLElement).getAttribute('data-originator'),
    ).toBe('agent');
    expect(
      (cards[1] as HTMLElement).getAttribute('data-originator'),
    ).toBe('system');
  });

  // §32 R-E2 — confidence chip + most-uncertain-first sort.
  it('renders a confidence chip (warning-styled when low) and sorts most-uncertain-first', async () => {
    // Propose high-confidence FIRST, then low — the queue must sort low first.
    proposeEffect({ kind: 'navigate', to: '#hi' }, CORE_PROVENANCE, 'agent', {
      confidence: 0.9,
    });
    proposeEffect({ kind: 'navigate', to: '#lo' }, CORE_PROVENANCE, 'agent', {
      confidence: 0.2,
    });
    await flush();
    const cards = host.shadowRoot?.querySelectorAll('.card') ?? [];
    expect(cards.length).toBe(2);
    const firstChip = (cards[0] as HTMLElement).querySelector('.confidence');
    expect(firstChip?.textContent?.trim()).toBe('20%');
    expect(firstChip?.hasAttribute('data-low')).toBe(true);
    const secondChip = (cards[1] as HTMLElement).querySelector('.confidence');
    expect(secondChip?.textContent?.trim()).toBe('90%');
    expect(secondChip?.hasAttribute('data-low')).toBe(false);
  });

  it('omits the confidence chip when confidence is not supplied', async () => {
    proposeEffect({ kind: 'navigate', to: '#z' }, CORE_PROVENANCE, 'agent');
    await flush();
    const card = host.shadowRoot?.querySelector('.card') as HTMLElement;
    expect(card.querySelector('.confidence')).toBeNull();
  });

  // Tempdoc 550 C3: an AGENT-proposed MEDIUM op would gate (UNTRUSTED × MEDIUM =
  // TYPED_CONFIRM), so it now enters the inline typed-confirm in the queue — consistent with
  // HIGH, and no longer dispatched immediately into a backend 428 dead-end (the bug this fix
  // closes; the queue is now the consistent effect ceremony).
  it('agent-proposed MEDIUM op enters the typed-confirm step (would gate as UNTRUSTED)', async () => {
    seedOp('core.reindex', 'MEDIUM');
    const events: Array<Record<string, unknown>> = [];
    const lis = (e: Event) =>
      events.push((e as CustomEvent).detail as Record<string, unknown>);
    document.addEventListener('jf-invoke-operation', lis);
    try {
      const pid = proposeEffect(
        { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
        CORE_PROVENANCE,
        'agent',
      );
      await flush();
      await activateJfButton(host.shadowRoot!.querySelector('jf-button.accept'));
      await flush();
      expect(host.shadowRoot!.querySelector(`[data-testid="typed-confirm-${pid}"]`)).not.toBeNull();
      expect(events).toHaveLength(0); // not dispatched yet — awaiting the typed-confirm
    } finally {
      document.removeEventListener('jf-invoke-operation', lis);
    }
  });

  // A USER-proposed MEDIUM op dispatches in one click — at TRUSTED tier MEDIUM is AUTO, so
  // there is no gate to confirm.
  it('user-proposed MEDIUM op dispatches immediately (backend AUTO)', async () => {
    seedOp('core.reindex', 'MEDIUM');
    const events: Array<Record<string, unknown>> = [];
    const lis = (e: Event) =>
      events.push((e as CustomEvent).detail as Record<string, unknown>);
    document.addEventListener('jf-invoke-operation', lis);
    try {
      proposeEffect(
        { kind: 'invoke-operation', operationId: 'core.reindex', args: {} },
        CORE_PROVENANCE,
        'user',
      );
      await flush();
      await activateJfButton(host.shadowRoot!.querySelector('jf-button.accept'));
      await flush();
      expect(listPending()).toHaveLength(0);
      expect(events).toHaveLength(1);
      expect(events[0]!.operationId).toBe('core.reindex');
      expect(events[0]!.confirmationToken).toBeUndefined();
    } finally {
      document.removeEventListener('jf-invoke-operation', lis);
    }
  });

  // §32 #2B + tempdoc 550 C3 — a HIGH-risk op requires typing the op id; only then
  // does it dispatch. On confirm the effect is marked `consented` (NO client-side mint);
  // the Shell's dispatch recovers the backend 428 via approve-by-pendingId. The typed
  // string is only the UX gate. This component test asserts the dispatched
  // jf-invoke-operation event carries consented:true and no client-side token.
  it('HIGH op: confirm marks the effect consented and dispatches (no client-side mint)', async () => {
    seedOp('core.file-operations', 'HIGH');
    const events: Array<Record<string, unknown>> = [];
    const lis = (e: Event) =>
      events.push((e as CustomEvent).detail as Record<string, unknown>);
    document.addEventListener('jf-invoke-operation', lis);
    try {
      const pid = proposeEffect(
        { kind: 'invoke-operation', operationId: 'core.file-operations', args: {} },
        CORE_PROVENANCE,
        'agent',
      );
      await flush();
      // First click enters the typed-confirm step — nothing dispatched yet.
      await activateJfButton(host.shadowRoot!.querySelector('jf-button.accept'));
      await flush();
      const row = host.shadowRoot!.querySelector(
        `[data-testid="typed-confirm-${pid}"]`,
      );
      expect(row).not.toBeNull();
      expect(events).toHaveLength(0);
      expect(row!.querySelector('jf-button.accept')!.hasAttribute('disabled')).toBe(true);
      // Type the op id → Confirm enables → marks consented → dispatches.
      const input = row!.querySelector('input') as HTMLInputElement;
      input.value = 'core.file-operations';
      input.dispatchEvent(new Event('input'));
      await flush();
      const confirmBtn = host
        .shadowRoot!.querySelector(`[data-testid="typed-confirm-${pid}"]`)!
        .querySelector('jf-button.accept')!;
      expect(confirmBtn.hasAttribute('disabled')).toBe(false);
      await activateJfButton(confirmBtn);
      await flush();
      expect(listPending()).toHaveLength(0);
      expect(events).toHaveLength(1);
      expect(events[0]!.operationId).toBe('core.file-operations');
      // Consent is a flag now — the Shell mints server-side via approve-by-pendingId.
      // No client-side capsule, and no nominal op-id token, rides the event.
      expect(events[0]!.consented).toBe(true);
      expect(events[0]!.confirmationToken).toBeUndefined();
    } finally {
      document.removeEventListener('jf-invoke-operation', lis);
    }
  });
});
