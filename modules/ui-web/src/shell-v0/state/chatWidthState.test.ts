// @vitest-environment happy-dom

/**
 * Tempdoc 874 — the chat-width preset projection.
 *
 * Two things are pinned here that nothing else can catch:
 *  1. **The vocabulary** — narrow/default/wide resolve to exactly 42/48/56rem. The presets exist so
 *     the width has a nameable, assertable value; a silent re-tuning of one stop is a change to the
 *     product's reading measure and should have to edit this test to happen.
 *  2. **The serialization round-trip** — the slice survives a document re-parse. `chatWidth` is
 *     threaded through FIVE places in `UserStateDocument` (both interfaces, `viewFromStorage`,
 *     `storageFromView`, and the parse + return of the sanitizer); missing any one of the last three
 *     drops the preference silently on reload and every in-memory assertion still passes.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import {
  CHAT_WIDTH_MEASURE,
  CHAT_WIDTH_VAR,
  DEFAULT_CHAT_WIDTH,
  chatWidthMeasure,
  getChatWidth,
  setChatWidth,
  subscribeChatWidth,
  type ChatWidth,
} from './chatWidthState.js';
import {
  __resetUserStateForTest,
  __resetInMemoryStateForTest,
  __DOCUMENT_STORAGE_KEY,
} from './UserStateDocument.js';

beforeEach(() => {
  __resetUserStateForTest();
});

afterEach(() => {
  __resetUserStateForTest();
});

describe('the preset vocabulary', () => {
  it('resolves each stop to its measure, verbatim', () => {
    expect(CHAT_WIDTH_MEASURE.narrow).toBe('42rem');
    expect(CHAT_WIDTH_MEASURE.default).toBe('48rem');
    expect(CHAT_WIDTH_MEASURE.wide).toBe('56rem');
    expect(chatWidthMeasure('narrow')).toBe('42rem');
    expect(chatWidthMeasure('default')).toBe('48rem');
    expect(chatWidthMeasure('wide')).toBe('56rem');
  });

  it('falls back to the default measure for a value outside the vocabulary', () => {
    // A persisted document from a future build (or a hand-edited one) must not produce an empty
    // custom-property write, which would collapse the column to its content width.
    expect(chatWidthMeasure('enormous' as ChatWidth)).toBe('48rem');
  });

  it('names the custom property the token sheet declares', () => {
    expect(CHAT_WIDTH_VAR).toBe('--measure-prose');
  });
});

describe('the projection over the user-state document', () => {
  it('is the default preset when nothing was ever chosen', () => {
    expect(getChatWidth()).toBe('default');
    expect(DEFAULT_CHAT_WIDTH).toBe('default');
  });

  it('reads back what was set', () => {
    setChatWidth('wide');
    expect(getChatWidth()).toBe('wide');
  });

  it('survives a document reload — the slice is serialized, not just held in memory', () => {
    setChatWidth('narrow');
    // Cross-profile: at the document top level, NOT under profiles[id] (mirrors surfaceMode).
    const raw = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!);
    expect(raw.chatWidth).toBe('narrow');
    expect(raw.profiles.default.chatWidth).toBeUndefined();
    // Drop the in-memory cache but keep localStorage: the next read re-parses the stored body, so a
    // missing spread in viewFromStorage / storageFromView / the sanitizer surfaces here as 'default'.
    __resetInMemoryStateForTest();
    expect(getChatWidth()).toBe('narrow');
  });

  it('reads a document written BEFORE this slice existed as the default', () => {
    // Every user upgrading into 874 has one of these: a real, valid v2 body with no `chatWidth` key
    // at all. The migration claim is that such a document needs no migration — the sanitizer leaves
    // the key absent and the projection's `?? DEFAULT_CHAT_WIDTH` supplies the answer. Built from a
    // GENUINE persisted body with the key deleted rather than a hand-written literal, so it stays a
    // real document (profiles, version, the sibling cross-profile slices) and not a fixture that
    // happens to parse.
    setChatWidth('wide');
    const raw = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!);
    delete raw.chatWidth;
    expect(raw.version).toBe(2); // still the real shape, not an empty object
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(raw));
    __resetInMemoryStateForTest();

    expect(() => getChatWidth()).not.toThrow();
    expect(getChatWidth()).toBe('default');
    // And the pre-874 document is still writable — the reader can choose a preset from it.
    setChatWidth('narrow');
    expect(getChatWidth()).toBe('narrow');
  });

  it('drops a malformed chatWidth on parse', () => {
    setChatWidth('wide');
    const raw = JSON.parse(localStorage.getItem(__DOCUMENT_STORAGE_KEY)!);
    raw.chatWidth = 'enormous'; // not a valid literal
    localStorage.setItem(__DOCUMENT_STORAGE_KEY, JSON.stringify(raw));
    __resetInMemoryStateForTest();
    expect(getChatWidth()).toBe('default');
  });
});

describe('subscription', () => {
  it('fires with the current value on subscribe, then on every change, and stops when disposed', () => {
    setChatWidth('wide');
    const seen: ChatWidth[] = [];
    const dispose = subscribeChatWidth((w) => seen.push(w));
    // The immediate fire is what makes a subscriber's initial apply free — SearchV3View relies on it
    // instead of a separate read-on-connect.
    expect(seen).toEqual(['wide']);

    setChatWidth('narrow');
    expect(seen).toEqual(['wide', 'narrow']);

    dispose();
    setChatWidth('default');
    expect(seen).toEqual(['wide', 'narrow']);
    // The store itself still moved — the disposer stopped the listener, not the write.
    expect(getChatWidth()).toBe('default');
  });
});
