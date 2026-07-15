// @vitest-environment happy-dom

/**
 * Live-confirmed defect: an already-rendered chat thread stayed fully readable after the
 * conversation store locked (a real `POST /api/conversations/encryption/lock` left the rendered
 * thread pixel-identical while the Security panel independently confirmed
 * `Encrypted (passphrase) · locked`; navigating away and back did not clear it).
 *
 * Root cause: `historyLocked` was set exactly once, at `loadConversation()` time, from the initial
 * GET's 423. The view already subscribes to `subscribeAiState` (whose `s.status` carries the polled
 * `/api/status` snapshot including `conversationProtection.state` — the same field `atRestCard.ts`
 * and `SecuritySurface.ts` read), but the callback never read it, so a lock originating elsewhere
 * (idle/auto-lock, another tab) left the transcript on screen forever.
 *
 * These tests drive the REAL SUBSCRIPTION, not the derivation method: `subscribeAiState` is a
 * controllable fake (mirroring `SecuritySurface.unlock-refresh.test.ts`) that CAPTURES the listener
 * the component hands it during `connectedCallback`; the element is then genuinely mounted, and the
 * captured listener is invoked. This is deliberate — a test that called the derivation method
 * directly would still pass if the `subscribeAiState(...)` wiring in `connectedCallback` were
 * deleted, i.e. it would be green against the very defect it claims to cover. That is the A1
 * failure mode (`BrainSurface.indexing-escape.test.ts`: 5 green revert-proven tests alongside a
 * permanently dead button) and the pattern logged as RECURRING FE test-blindness in the
 * observations store. `wiringIsLive` below pins the wiring itself.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import './UnifiedChatView.js';

// The listener the component hands to subscribeAiState during connectedCallback. Captured, not
// replayed: the test drives the component through the SAME callback production uses.
let capturedListener: ((s: unknown) => void) | null = null;
const unsubscribeSpy = vi.fn();

// Chat-capable baseline, mirroring the real store's first frame. maybeAutoRun stays a no-op (no
// autoRunPending flag, empty inputDraft), so pushing states here cannot trigger a send().
const AI_STATE_BASE = {
  capabilities: { chat: true, rag: true, extract: false, embedding: false },
  activity: { state: 'idle', shapeId: null, startedAtMs: null, canCancel: false, cancel: null },
  status: null,
};

vi.mock('../state/aiStateStore.js', () => ({
  subscribeAiState: vi.fn((listener: (s: unknown) => void) => {
    capturedListener = listener;
    return unsubscribeSpy;
  }),
  setAiActivity: vi.fn(),
  getAiState: () => AI_STATE_BASE,
  startAiStateStore: vi.fn(),
  stopAiStateStore: vi.fn(),
}));

// connectedCallback fires loadConversations() / refreshUnifiedThread() at the network. Stub fetch so
// they resolve to empty instead of spraying ECONNREFUSED; irrelevant to the assertions below.
function stubFetch(): void {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ sessions: [], events: [], lifecycles: [] }),
    }),
  );
}

interface ChatHarness extends HTMLElement {
  thread: unknown[];
  historyLocked: boolean;
  affordance: string;
  updateComplete: Promise<unknown>;
}

/**
 * Mount for real, so connectedCallback runs and the production subscription actually happens.
 *
 * The tier is pinned OFF the `retrieve` default deliberately. `deriveAffordance` (agencyPosture.ts:95)
 * returns `retrieve` for an unpinned view, and `renderAnswerPlane` skips the entire thread block —
 * `historyLocked` gate included — in that tier (UnifiedChatView.ts:2398). So `retrieve` renders no
 * transcript to leak and cannot express the defect; a thread being on screen at all (the live repro's
 * precondition) implies a pinned non-retrieve tier, which is what `'none'` (free-chat) is here.
 */
function mount(): ChatHarness {
  const el = document.createElement('jf-unified-chat-view') as ChatHarness;
  document.body.appendChild(el);
  el.affordance = 'none';
  el.thread = [{ role: 'user', content: 'is the Q3 filing final?', shapeId: 'core.free-chat' }];
  el.historyLocked = false;
  return el;
}

function pushStatus(status: unknown): void {
  // Fails loudly rather than silently no-op'ing if the wiring is gone — the whole point of the file.
  if (!capturedListener) {
    throw new Error('subscribeAiState listener was never captured: the aiState subscription is not wired');
  }
  capturedListener({ ...AI_STATE_BASE, status });
}

beforeEach(() => {
  capturedListener = null;
  stubFetch();
});

afterEach(() => {
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
  vi.clearAllMocks();
});

describe('UnifiedChatView — historyLocked tracks the polled conversationProtection state', () => {
  it('wiringIsLive: connectedCallback subscribes to the aiState store, and disconnect tears it down', () => {
    const el = mount();

    // If the `subscribeAiState(...)` line in connectedCallback is deleted, every test below is
    // vacuous — so pin the wiring explicitly rather than inferring it.
    expect(capturedListener).not.toBeNull();

    el.remove();
    expect(unsubscribeSpy).toHaveBeenCalled();
  });

  it('a lock observed on the status poll flips historyLocked to true (the live-confirmed defect)', () => {
    const el = mount();

    pushStatus({ conversationProtection: { state: 'locked' } });

    expect(el.historyLocked).toBe(true);
    // The thread data itself is untouched by the derivation — only the render gate flips.
    expect(el.thread).toHaveLength(1);
  });

  it('swaps the rendered transcript for the locked notice once the lock arrives over the subscription', async () => {
    const el = mount();
    await el.updateComplete;
    // Precondition: the message text really is on screen before the lock — otherwise "it is gone
    // afterwards" would prove nothing.
    expect(el.shadowRoot?.textContent).toContain('is the Q3 filing final?');
    expect(el.shadowRoot?.querySelector('.history-locked')).toBeNull();

    pushStatus({ conversationProtection: { state: 'locked' } });
    await el.updateComplete;

    // The user-visible half, and the confidentiality claim itself: the notice replaces the message
    // text. Asserting the flag alone would not prove the render path consumes it.
    expect(el.shadowRoot?.querySelector('.history-locked')).not.toBeNull();
    expect(el.shadowRoot?.textContent).not.toContain('is the Q3 filing final?');
  });

  it('an unlock observed on the status poll flips historyLocked back to false', () => {
    const el = mount();
    el.historyLocked = true;

    pushStatus({ conversationProtection: { state: 'unlocked' } });

    expect(el.historyLocked).toBe(false);
  });

  it('a status snapshot with no conversationProtection field leaves historyLocked unchanged (no false-unlock)', () => {
    const el = mount();
    el.historyLocked = true;

    pushStatus({ gpu: { available: true } });

    expect(el.historyLocked).toBe(true);
  });

  it('a null status (poll not yet resolved) leaves historyLocked unchanged', () => {
    const el = mount();
    el.historyLocked = true;

    pushStatus(null);

    expect(el.historyLocked).toBe(true);
  });
});
