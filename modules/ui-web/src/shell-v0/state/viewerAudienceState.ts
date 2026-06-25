// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 511-followup Track A + 511-followup-2 Track BB —
 * viewerAudienceState.
 *
 * Projection over UserStateDocument's `viewerAudience` slice.
 *
 * IMPORTANT framing: this is a VIEW PREFERENCE, not an access-
 * control boundary. The local single-user deployment doesn't
 * authenticate or authorize operation invocations; the wire ships
 * every operation's metadata to every client, and OperationClient
 * will call any operation regardless of the audience tier set here.
 * The audience gate on `<jf-operation>` / `<jf-resource>` only
 * affects what the UI RENDERS — operator-only ops are hidden from
 * the user-tier view, but they remain accessible to any code that
 * looks them up. SettingsSurface exposes this as a user-controlled
 * toggle.
 *
 * Before this projection landed, each surface hardcoded a
 * `viewer-audience` attribute on `<jf-operation>` / `<jf-resource>`,
 * making the gate theater — surfaces filtered themselves with
 * values they chose. The store-backed projection gives the gate a
 * single user-controlled input source; that part is now honest
 * within the view-preference framing.
 *
 * For a real authorization layer (session identity + server-side
 * catalog filtering + per-request audience claims), see the
 * tempdoc 511's named follow-up "Option A2." Out of scope for the
 * single-user local model.
 *
 * Pattern mirrors `userConfigState.ts` and `themeState.ts`:
 * getCurrent / subscribe / setter / __resetForTest.
 */

// Allowlisted in eslint.config.js — see 511-followup-B. State module
// projecting the viewer-audience wire enum into a persisted store.
import type { Audience } from '../../api/types/registry.js';
import { signal } from '@lit-labs/signals';
import {
  getDocument,
  subscribeProjection,
  mutateDocument,
} from './UserStateDocument.js';

type Listener = (audience: Audience) => void;

/**
 * Slice C (§1 signals) — signal mirror of the viewer-audience projection.
 * SignalWatcher consumers (`<jf-operation>`, `<jf-resource>`) read
 * `getViewerAudience()` in render() and re-render automatically when the
 * value changes — no manual subscribe/unsubscribe. Kept in sync with
 * UserStateDocument via the projection below; the `subscribe*` API is
 * retained for non-signal consumers (Settings, plugins).
 */
// Reactivity tick — bumped whenever the document's viewerAudience changes.
// getViewerAudience() reads the VALUE live from the document (always current,
// so it never desyncs even if UserStateDocument's listeners are cleared, e.g.
// a test reset) and tracks this tick so SignalWatcher consumers re-render on
// change. A mirrored value-signal would go stale when the sync listener is
// cleared — this tick-over-live-read avoids that failure mode.
const _audienceTick = signal(0);
subscribeProjection(
  (doc) => doc.viewerAudience ?? 'USER',
  () => _audienceTick.set(_audienceTick.get() + 1),
);

/**
 * Snapshot of the current viewer audience. Default 'USER'. Reading this inside
 * a SignalWatcher render() makes the consumer reactive automatically (it tracks
 * the reactivity tick); the value is read live from UserStateDocument.
 */
export function getViewerAudience(): Audience {
  void _audienceTick.get();
  return getDocument().viewerAudience ?? 'USER';
}

/**
 * Subscribe to viewer-audience changes. Listener fires once with
 * the current value on subscribe (via the document's
 * subscribeProjection contract), then on every mutation.
 */
export function subscribeViewerAudience(listener: Listener): () => void {
  return subscribeProjection(
    (doc) => doc.viewerAudience ?? 'USER',
    listener,
  );
}

/**
 * Set the viewer audience. Persists immediately. The audience-gate
 * consumers (<jf-operation> / <jf-resource>) re-render via their
 * own subscription.
 */
export function setViewerAudience(audience: Audience): void {
  mutateDocument((doc) => ({ ...doc, viewerAudience: audience }));
}
