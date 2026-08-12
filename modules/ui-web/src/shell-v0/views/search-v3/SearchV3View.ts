// SPDX-License-Identifier: Apache-2.0
/**
 * SearchV3View — the Search v3 window host (tempdoc 822 slices 1 and 3; wired in Phase A1).
 *
 * Derived from T3 Code (T3 Tools Inc., MIT) — see THIRD-PARTY-NOTICES.md in this directory.
 *
 * A from-scratch window rebuilt on the T3 Code donor system: no presentation code is carried over
 * from `UnifiedChatView` or search-v2 — and no search client either, in the other direction: the
 * store this host subscribes to is the SHARED `state/searchState.ts` that both shipped windows read,
 * which is what "from-scratch components, shared authorities" means in practice. This host owns six
 * things and delegates the rest:
 *
 *  1. **The token sheet.** `sv3Tokens` is applied HERE, on the window host — never on `:root`. Custom
 *     properties inherit down through every nested shadow root, so one host-scoped declaration
 *     reaches the whole window while the shipped app's palette stays untouched.
 *  2. **The window grid.** A fixed `--sidebar-width` panel that does not flex, beside a main column
 *     of topbar → content surface → composer band.
 *  3. **The scroll policy.** The window region never scrolls: this host and the main column are
 *     clipped, and the ONE scroller is the content surface's inner scroller. Chrome therefore
 *     cannot be scrolled out of reach, and there is no scroller nested inside another.
 *  4. **The composer state, and the morph between its two forms** (slice 3). The state lives here
 *     rather than in the composer because it is a WINDOW layout: hero means the composer owns the
 *     content region and there are no results; docked means the results do. Three ways in, all
 *     through the same morph: the send control, `Escape` in the field, and the `composer-state`
 *     attribute (a dev-only handle for live measurement, which is why an external write is routed
 *     through the morph rather than applied straight).
 *  5. **The one search issuance.** A send seeds the shared store's query and runs the explicit pass —
 *     the same `setQuery` + `submitSearch` pair the shipped surface sends on an explicit submit
 *     (`views/search-v2/SearchV2View.ts:998-999`), which is exactly ONE request: `submitSearch`
 *     supersedes the keystroke pass `setQuery` had scheduled. Reading back is the store subscription;
 *     what the region does with the snapshot is `sv3-results.ts`.
 *  6. **The session list** (Phase A2). Window-local and in-memory by decision, not by omission —
 *     `sv3-sessions.ts` carries the reasoning and the Phase-D boundary. The host holds the list and
 *     routes the three things that change it (a send, a row click, New search) through the one
 *     search issuance above; the sidebar renders the projection and issues nothing.
 *
 * Mounted as a hidden DEEPLINK surface, dev audience, no rail entry:
 * `#justsearch://surface/core.search-v3-surface`.
 *
 * Side-effect registers <jf-sv3-window> and its four regions.
 */
import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import { sv3Tokens } from './sv3-tokens.css.js';
import { sv3Shared } from './sv3-shared-styles.js';
import { COMPOSER_STATE_DEFAULT, WINDOW_TITLE, type Sv3ComposerState } from './fixtures.js';
import {
  setQuery,
  setSearchApiBase,
  submitSearch,
  subscribeSearch,
  type SearchState,
} from '../../state/searchState.js';
import {
  adoptSv3MorphSheet,
  releaseSv3MorphSheet,
  runSv3ComposerMorph,
} from './sv3-composer-morph.js';
import {
  type Sv3Composer,
  type Sv3ComposerStateRequest,
  type Sv3ComposerSubmit,
} from './Sv3Composer.js';
import { projectSv3Results, type Sv3ResultsView } from './sv3-results.js';
import { type Sv3SessionSelect } from './Sv3Sidebar.js';
import {
  focusSession,
  projectSv3Sessions,
  sessionById,
  startNewSession,
  submitInSession,
  SV3_SESSIONS_EMPTY,
  type Sv3SessionList,
} from './sv3-sessions.js';
import type { Sv3Palette } from './Sv3Palette.js';
import './Sv3Topbar.js';
import './Sv3Sidebar.js';
import './Sv3Main.js';
import './Sv3Composer.js';
import './Sv3Palette.js';

const COMPOSER_STATE_ATTR = 'composer-state';

/**
 * The palette chord, matched only for events that reach THIS window. The shipped shell binds the same
 * chord globally (`mod+k` → `shell.toggle-palette`), so the scope is the whole contract: a keystroke
 * outside the window must never be seen here.
 */
const isPaletteChord = (event: KeyboardEvent): boolean =>
  (event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'k';

const isComposerState = (value: string | null): value is Sv3ComposerState =>
  value === 'hero' || value === 'docked';

export class SearchV3View extends JfElement {
  static styles = [
    sv3Tokens,
    sv3Shared,
    css`
      :host {
        display: flex;
        height: 100%;
        min-height: 0;
        overflow: hidden;
        /* The containing block for the palette overlay, which is why the palette can be window-scoped
           at all: it is absolutely positioned against THIS box and cannot reach the shipped chrome. */
        position: relative;
        background: var(--background);
        color: var(--foreground);
        font-family: var(--font-sans);
        font-size: var(--font-size-sv3-sm);
      }
      jf-sv3-sidebar {
        flex: 0 0 var(--sidebar-width);
        width: var(--sidebar-width);
      }
      .column {
        display: flex;
        flex-direction: column;
        flex: 1 1 auto;
        min-width: 0;
        min-height: 0;
        overflow: hidden;
        /* The containing block for the hero composer, which leaves the flow to centre itself over
           the content region. */
        position: relative;
      }
    `,
  ];

  static properties = {
    composerState: { type: String, reflect: true, attribute: COMPOSER_STATE_ATTR },
    apiBase: { type: String, attribute: 'api-base' },
    searchSnapshot: { state: true },
    asked: { state: true },
    sessions: { state: true },
  };

  declare composerState: Sv3ComposerState;
  /** Set by the shell on every render of a mounted surface (`chrome/Shell.ts:2945-2949`). */
  declare apiBase: string;
  /** The latest store emission. Null only until the subscription's first (immediate) call. */
  declare searchSnapshot: SearchState | null;
  /**
   * Whether THIS window has sent anything. The store is a process-wide singleton, so without this
   * the window would render another surface's results as its own the moment it docked.
   */
  declare asked: boolean;
  /**
   * The window's own session list — in-memory, window-local, and NOT a store (see `sv3-sessions.ts`
   * for why the authority question belongs to Phase D).
   */
  declare sessions: Sv3SessionList;

  private searchUnsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.composerState = COMPOSER_STATE_DEFAULT;
    this.apiBase = '';
    this.searchSnapshot = null;
    this.asked = false;
    this.sessions = SV3_SESSIONS_EMPTY;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    adoptSv3MorphSheet();
    setSearchApiBase(this.apiBase || '');
    this.searchUnsubscribe = subscribeSearch((snapshot) => {
      this.searchSnapshot = snapshot;
    });
    // Scoped to the HOST, not to `window`. A host listener is only reached by events whose composed
    // path runs through this window, so a chord pressed anywhere else in the shipped app is invisible
    // here by construction — there is no "is the focus inside?" test to get wrong. Capture phase so
    // the palette's own field cannot swallow the chord before the window sees it.
    this.addEventListener('keydown', this.onHostKeydown, true);
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    releaseSv3MorphSheet();
    this.searchUnsubscribe?.();
    this.searchUnsubscribe = null;
    this.removeEventListener('keydown', this.onHostKeydown, true);
  }

  /**
   * The shell re-sets `api-base` on a CACHED element rather than reconstructing it, so the base has
   * to follow the attribute and not just the first connect.
   */
  protected override updated(changed: Map<string, unknown>): void {
    if (changed.has('apiBase')) setSearchApiBase(this.apiBase || '');
  }

  private readonly onHostKeydown = (event: KeyboardEvent): void => {
    if (!isPaletteChord(event)) return;
    event.preventDefault();
    event.stopPropagation();
    // The deepest node in the composed path is the real invoker; `document.activeElement` retargets
    // to this host at the shadow boundary and would send focus back to a non-focusable element.
    const invoker = (event.composedPath()[0] ?? null) as HTMLElement | null;
    this.togglePalette(invoker);
  };

  private get palette(): Sv3Palette | null {
    return this.shadowRoot?.querySelector('jf-sv3-palette') ?? null;
  }

  /** The one way the palette opens or closes, whichever affordance asked. */
  togglePalette(invoker: HTMLElement | null): void {
    const palette = this.palette;
    if (palette === null) return;
    if (palette.open) palette.hide();
    else void palette.show(invoker);
  }

  private onPaletteRequest(event: Event): void {
    this.togglePalette((event.composedPath()[0] ?? null) as HTMLElement | null);
  }

  override attributeChangedCallback(name: string, older: string | null, value: string | null): void {
    // An external write of the dev handle animates like every other route into the state. Lit's own
    // reflection also lands here, but by then the property already holds the value, so it falls
    // through to the default path and cannot loop.
    if (
      name === COMPOSER_STATE_ATTR &&
      isComposerState(value) &&
      this.hasUpdated &&
      value !== this.composerState
    ) {
      void this.setComposerState(value);
      return;
    }
    super.attributeChangedCallback(name, older, value);
  }

  /** The one way the state changes: applied inside the scoped view transition (donor §5.5). */
  async setComposerState(next: Sv3ComposerState): Promise<void> {
    if (next === this.composerState) return;
    const composer = this.shadowRoot?.querySelector('jf-sv3-composer');
    const apply = async (): Promise<void> => {
      this.composerState = next;
      await this.updateComplete;
      // The regions schedule their OWN updates off this render, and the API captures the "after"
      // state when this callback resolves. Waiting on a FRAME here would deadlock: the browser
      // suspends rendering until the callback settles, so a requested frame is never serviced and
      // the transition is skipped at the ~4s callback timeout (measured). Their update promises are
      // microtask-backed and settle regardless.
      await Promise.all(
        [...(this.shadowRoot?.querySelectorAll('jf-sv3-main, jf-sv3-composer') ?? [])].map(
          (region) => (region as HTMLElement & { updateComplete: Promise<unknown> }).updateComplete,
        ),
      );
    };
    if (composer === null || composer === undefined) {
      await apply();
      return;
    }
    await runSv3ComposerMorph(composer, apply);
  }

  private onStateRequest(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerStateRequest>).detail;
    if (!isComposerState(detail?.state ?? null)) return;
    void this.setComposerState(detail.state);
  }

  /**
   * A send is one search and one morph, in that order: the request goes out in this tick, so the
   * region is already showing the pending state by the time the morph settles.
   */
  private onComposerSubmit(event: Event): void {
    this.runSearch(((event as CustomEvent<Sv3ComposerSubmit>).detail?.query ?? '').trim());
  }

  /**
   * The ONE path a search takes, whichever affordance asked: the composer's send, or a session row
   * re-running what it already asked. A second issuance site is how the two would drift apart —
   * a row click that bypassed this would leave the session list describing a search it never ran.
   */
  private runSearch(rawQuery: string): void {
    const query = rawQuery.trim();
    if (query === '') return;
    this.sessions = submitInSession(this.sessions, query, Date.now());
    this.asked = true;
    setQuery(query);
    submitSearch();
    void this.setComposerState('docked');
  }

  private get composer(): Sv3Composer | null {
    return this.shadowRoot?.querySelector('jf-sv3-composer') ?? null;
  }

  /** A row click re-runs THAT session's query: it claims the row first, so the re-run lands there. */
  private onSessionSelect(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionSelect>).detail?.id ?? '';
    const session = sessionById(this.sessions, id);
    if (session === null) return;
    this.sessions = focusSession(this.sessions, id);
    this.runSearch(session.query);
  }

  /**
   * New search: back to the hero with an empty draft and nothing claimed about the corpus. The
   * sessions so far stay in the list — starting one is not ending the others.
   */
  private onSessionNew(): void {
    this.sessions = startNewSession(this.sessions);
    this.asked = false;
    this.composer?.clearDraft();
    void this.setComposerState('hero');
  }

  render(): TemplateResult {
    const results: Sv3ResultsView = projectSv3Results(this.searchSnapshot, this.asked);
    // Relative timestamps are computed HERE, on render, and never ticked: a sidebar that re-renders
    // itself every second is continuous motion at rest, which the donor's duty-cycle law rules out.
    // `isRefining` counts too: the store runs a re-query BEHIND displayed results quietly
    // (`state/searchState.ts:611` — no skeleton, so the content surface keeps the old rows), and the
    // row's dot is then the only thing on screen saying the session is running a pass.
    const snapshot = this.searchSnapshot;
    const sessionGroups = projectSv3Sessions(this.sessions, {
      searching: this.asked && (snapshot?.isSearching === true || snapshot?.isRefining === true),
      now: Date.now(),
    });
    return html`
      <jf-sv3-sidebar
        .groups=${sessionGroups}
        data-testid="sv3-sidebar"
        @sv3-session-select=${this.onSessionSelect}
        @sv3-session-new=${this.onSessionNew}
      ></jf-sv3-sidebar>
      <div
        class="column"
        data-testid="sv3-column"
        @sv3-composer-state-request=${this.onStateRequest}
        @sv3-composer-submit=${this.onComposerSubmit}
        @sv3-palette-request=${this.onPaletteRequest}
      >
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        <jf-sv3-main
          state=${this.composerState}
          .view=${results}
          data-testid="sv3-main"
        ></jf-sv3-main>
        <jf-sv3-composer state=${this.composerState} data-testid="sv3-composer"></jf-sv3-composer>
      </div>
      <!-- LAST in the shadow root on purpose: the palette and the hero composer share the overlay
           rung, so DOM order is what puts the palette on top. -->
      <jf-sv3-palette data-testid="sv3-palette"></jf-sv3-palette>
    `;
  }
}

customElements.define('jf-sv3-window', SearchV3View);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-window': SearchV3View;
  }
}
