// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 874 — chatWidthState.
 *
 * Projection over UserStateDocument's `chatWidth` slice: the reader's chosen
 * measure for the Search v3 chat column.
 *
 * WHY PRESETS AND NOT A SLIDER: the column's width is one of the few
 * geometry facts three separate components have to agree on (the transcript,
 * the composer band and the context bar all share one edge by construction —
 * they read the same `--measure-prose`). A free-form px slider has no
 * vocabulary to pin: every value is as valid as every other, so nothing in
 * the suite can assert "the wide preset is 56rem" and per-pixel drift between
 * the three sites becomes unrepresentable-in-a-test rather than
 * unrepresentable-in-the-code. Three named stops are bounded, nameable and
 * testable — `CHAT_WIDTH_MEASURE` below IS the pinned vocabulary.
 *
 * The preference is FRONTEND-LOCAL: it lives in the user-state document
 * (`localStorage['justsearch.userState.v2']`), cross-profile, with no backend
 * setting behind it. It is a rendering preference like `surfaceMode`, not a
 * configured behaviour.
 *
 * Structure mirrors `viewerAudienceState.ts`: signal tick + subscribeProjection
 * + getter / subscribe / setter.
 */
import { signal } from '@lit-labs/signals';
import {
  getDocument,
  subscribeProjection,
  mutateDocument,
} from './UserStateDocument.js';

/** The three named stops the settings control offers. */
export type ChatWidth = 'narrow' | 'default' | 'wide';

/**
 * The measure each preset resolves to. This map is the single authority for
 * the numbers; `sv3-tokens.css.ts` declares the DEFAULT (`--measure-prose:
 * 48rem`) and this override is written onto the Search v3 host at runtime.
 */
export const CHAT_WIDTH_MEASURE: Readonly<Record<ChatWidth, string>> = {
  narrow: '42rem',
  default: '48rem',
  wide: '56rem',
};

/** The preset a reader who never chose one gets — matches the token declaration. */
export const DEFAULT_CHAT_WIDTH: ChatWidth = 'default';

/** The custom property the presets write. Declared in `sv3-tokens.css.ts`. */
export const CHAT_WIDTH_VAR = '--measure-prose';

/** The measure for a preset; an unrecognised input resolves to the default's value. */
export function chatWidthMeasure(width: ChatWidth): string {
  return CHAT_WIDTH_MEASURE[width] ?? CHAT_WIDTH_MEASURE[DEFAULT_CHAT_WIDTH];
}

// Reactivity tick — bumped whenever the document's chatWidth changes.
// getChatWidth() reads the VALUE live from the document (always current, so it
// never desyncs even if UserStateDocument's listeners are cleared, e.g. a test
// reset) and tracks this tick so SignalWatcher consumers re-render on change. A
// mirrored value-signal would go stale when the sync listener is cleared — this
// tick-over-live-read avoids that failure mode.
const _chatWidthTick = signal(0);
subscribeProjection(
  (doc) => doc.chatWidth ?? DEFAULT_CHAT_WIDTH,
  () => _chatWidthTick.set(_chatWidthTick.get() + 1),
);

/**
 * Snapshot of the current chat-width preset. Default `'default'`. Reading this
 * inside a SignalWatcher render() makes the consumer reactive automatically;
 * the value is read live from UserStateDocument.
 */
export function getChatWidth(): ChatWidth {
  void _chatWidthTick.get();
  return getDocument().chatWidth ?? DEFAULT_CHAT_WIDTH;
}

/**
 * Subscribe to chat-width changes. Listener fires once with the current value
 * on subscribe (via the document's `subscribeProjection` contract), then on
 * every mutation — so a subscriber gets its initial apply for free.
 */
export function subscribeChatWidth(listener: (w: ChatWidth) => void): () => void {
  return subscribeProjection((doc) => doc.chatWidth ?? DEFAULT_CHAT_WIDTH, listener);
}

/** Set the chat-width preset. Persists immediately; live consumers re-apply via their subscription. */
export function setChatWidth(width: ChatWidth): void {
  mutateDocument((doc) => ({ ...doc, chatWidth: width }));
}
