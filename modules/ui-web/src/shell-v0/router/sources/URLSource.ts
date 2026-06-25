// SPDX-License-Identifier: Apache-2.0
/**
 * URLSource — Intent substrate tier 1 (slice 492).
 *
 * Owns the URL-derived ingress channel:
 *   1. **Boot read.** On `start(...)`, reads `window.location.hash` and
 *      dispatches the parsed Intent (if any) so refresh restores prior state.
 *   2. **Popstate.** Installs a `popstate` listener; on each event, re-reads
 *      the hash and dispatches the parsed Intent.
 *
 * Per slice 492: parsing is delegated to `parseUrl` (pure extractor); state
 * realization (distribute to stores, activate surface, push URL) is delegated
 * to the NavigationHandler via the router. URLSource itself is just the
 * ingress wrapper — boot the listener, parse, dispatch.
 *
 * The popstate-driven dispatch sets `DispatchOptions.pushHistory: false`
 * because the browser already moved history before firing the event;
 * pushing again would create a duplicate history entry and trap the user
 * in a self-looping back button.
 *
 * Replaces the prior duality of `URLHydrator.hydrateFromCurrentUrl` (boot
 * read with side-effects) + `URLHydrator.installPopstateHandler` (popstate)
 * + `Shell.connectedCallback`'s post-fetch hydration block. After this
 * source registers, the URL boot/popstate ingress has one home.
 */

import { parseUrl } from '../parser.js';
import type { Intent } from '../types.js';
import type { IntentSource, SourceDispatch } from './IntentSource.js';

export interface URLSourceConfig {
  /**
   * Window object — pass-through for SSR / test seam. Defaults to the
   * runtime `window`. When `window` is undefined (SSR / Node), boot and
   * teardown are no-ops.
   */
  windowImpl?: Window;
}

/** Stable Manifest-tier id this source corresponds to. */
export const URL_SOURCE_REF = 'core.url-bar';

export function createURLSource(config: URLSourceConfig = {}): IntentSource {
  const win = config.windowImpl ?? (typeof window === 'undefined' ? undefined : window);

  return {
    ref: URL_SOURCE_REF,
    start(dispatch: SourceDispatch): () => void {
      if (!win) {
        return () => {
          /* no-op when no window */
        };
      }

      // 1. Boot read — refresh-restore. Dispatches with default pushHistory:
      //    true so the URL is canonicalized (replaceState by projector or
      //    pushState by handler — handler decides; projector handles the
      //    follow-up replaceState as stores fire subscribers).
      //
      // Slice 492 §"Concrete evidence: the state-drop site": this is the
      // boot path whose previous incarnation (`URLHydrator.hydrateFromCurrentUrl`
      // + `applyState` + `Shell.activateProjection`) silently bypassed the
      // router for nav intents. After this source dispatches into the router
      // and the NavigationHandler distributes state, the state-drop defect is
      // fixed by construction.
      const intent = parseHash(win);
      if (intent) {
        dispatch(intent, { pushHistory: true });
      }

      // 2. Popstate listener — browser back/forward. The browser has already
      //    moved history; the handler must NOT push again. pushHistory: false.
      const handler = () => {
        const popped = parseHash(win);
        if (popped) {
          dispatch(popped, { pushHistory: false });
        }
      };
      win.addEventListener('popstate', handler);
      return () => {
        win.removeEventListener('popstate', handler);
      };
    },
  };
}

/**
 * Read `window.location.hash` and parse a `justsearch://...` URL into an
 * Intent envelope. Returns null when no recognized hash is present or the
 * URL is unparseable.
 *
 * Transport-tag policy:
 *   - Navigation intents stamp `URL_BAR` (refresh / popstate / URL paste).
 *   - Invocation intents also stamp `URL_BAR` (URL paste of
 *     `justsearch://op/...`). The downstream OperationsController distinguishes
 *     `URL_BAR` from `URL_DEEPLINK` (the Tauri OS-level entry); paste is
 *     `URL_BAR`.
 */
function parseHash(win: Window): Intent | null {
  const fragment = win.location.hash;
  if (!fragment || !fragment.startsWith('#justsearch://')) return null;
  const url = fragment.slice(1);
  const address = parseUrl(url);
  if (!address) {
    // The hash looked like a justsearch:// URL but didn't parse. Warn
    // symmetrically with TauriDeepLinkSource — operators / developers
    // need to see malformed input so a typo'd bookmark or stale
    // shareable URL doesn't fail silently.
    // eslint-disable-next-line no-console
    console.warn(`[URLSource] received un-parseable justsearch:// URL: ${url}`);
    return null;
  }
  return {
    address,
    transport: 'URL_BAR',
  };
}
