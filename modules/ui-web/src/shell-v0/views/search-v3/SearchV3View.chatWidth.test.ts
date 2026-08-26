// @vitest-environment happy-dom

/**
 * Tempdoc 874 — the LIVE application of the chat-width preset.
 *
 * The state module's own test proves the preference persists; this one proves it reaches the screen.
 * The window writes `--measure-prose` inline on its own host, where the token sheet declares it, so
 * one write re-caps the transcript, the composer band and the context bar together.
 *
 * The three claims, each fail-able for its own reason:
 *  1. **Mounting applies the current value** — `subscribeChatWidth` fires immediately, so no separate
 *     read-on-connect exists to hide a missing subscription. Delete the subscribe line and the
 *     property is never written at all.
 *  2. **A change while mounted follows** — a "read it once at mount" implementation passes (1) and
 *     fails this. Settings never touches the window; the store is the whole channel between them.
 *  3. **An unmounted window stops following** — the projection's listener set is module-lifetime, so
 *     a disposer that never ran leaks a reference to every window ever mounted.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import './SearchV3View.js';
import type { SearchV3View } from './SearchV3View.js';
import { resetSearchState } from '../../state/searchState.js';
import { __resetAiStateForTest } from '../../state/aiStateStore.js';
import { __resetConversationListForTest } from '../../state/conversationListStore.js';
import { __resetDraftProvidersForTest } from '../../controllers/draftPersistence.js';
import { __resetDraftKeptForTest } from '../../controllers/draftKeptHint.js';
import { __resetUserStateForTest } from '../../state/UserStateDocument.js';
import { setChatWidth } from '../../state/chatWidthState.js';

type Mounted = HTMLElement & { updateComplete: Promise<unknown> };

const MEASURE = '--measure-prose';

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  __resetUserStateForTest();
  __resetConversationListForTest();
  __resetDraftProvidersForTest();
  __resetDraftKeptForTest();
  __resetAiStateForTest();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, body: null }));
});

afterEach(() => {
  for (const child of [...document.body.children]) child.remove();
  resetSearchState();
  __resetAiStateForTest();
  __resetUserStateForTest();
  vi.unstubAllGlobals();
});

async function mount(): Promise<SearchV3View & Mounted> {
  const el = document.createElement('jf-sv3-window') as SearchV3View & Mounted;
  el.setAttribute('api-base', 'http://127.0.0.1:9999');
  document.body.appendChild(el);
  await el.updateComplete;
  return el;
}

describe('the window wears the chat width the reader chose', () => {
  it('carries the default measure when no preset was ever chosen', async () => {
    const el = await mount();
    expect(el.style.getPropertyValue(MEASURE)).toBe('48rem');
  });

  it('carries a previously chosen preset from the moment it mounts', async () => {
    setChatWidth('wide');
    const el = await mount();
    expect(el.style.getPropertyValue(MEASURE)).toBe('56rem');
  });

  it('follows a change made while it is mounted, across all three presets', async () => {
    const el = await mount();
    setChatWidth('narrow');
    expect(el.style.getPropertyValue(MEASURE)).toBe('42rem');
    setChatWidth('wide');
    expect(el.style.getPropertyValue(MEASURE)).toBe('56rem');
    setChatWidth('default');
    expect(el.style.getPropertyValue(MEASURE)).toBe('48rem');
  });

  it('stops following once unmounted, and re-reads when it comes back', async () => {
    const el = await mount();
    setChatWidth('wide');
    expect(el.style.getPropertyValue(MEASURE)).toBe('56rem');

    document.body.removeChild(el);
    // A detached window must NOT have followed — this is the half that makes the disconnect cleanup
    // fail-able. It also must not throw: the listener, if leaked, runs against a detached element.
    expect(() => setChatWidth('narrow')).not.toThrow();
    expect(el.style.getPropertyValue(MEASURE)).toBe('56rem');

    // A retained instance re-attaches into the preset the reader is on NOW.
    document.body.appendChild(el);
    await el.updateComplete;
    expect(el.style.getPropertyValue(MEASURE)).toBe('42rem');
  });
});
