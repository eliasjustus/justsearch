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
 *  5. **The one ask.** A send opens a turn and dispatches it through `sv3-ask.ts`, the window's ONE
 *     issuance site, holding the `AbortController` that Stop uses and settling the turn on whichever
 *     terminal the stream reports. Phase A1's SEARCH issuance (`setQuery` + `submitSearch`, the pair
 *     `views/search-v2/SearchV2View.ts:998-999` sends) is still here and still exactly one request,
 *     but it is the SECONDARY axis now: only the palette's "Search this text" reaches it.
 *  6. **The session list** (Phase A2; conversations since F1). Window-local and in-memory by
 *     decision, not by omission — `sv3-sessions.ts` carries the reasoning and the Phase-D boundary.
 *     The host holds the list and routes everything that changes it (a send, a row click, New
 *     session, and every stream event) through the funnels above; the sidebar and the content
 *     surface render projections and issue nothing.
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
import {
  type Sv3SessionPin,
  type Sv3SessionRename,
  type Sv3SessionSelect,
} from './Sv3Sidebar.js';
import {
  clampSv3SidebarWidth,
  forgetSv3SidebarWidth,
  readStoredSv3SidebarCollapsed,
  readStoredSv3SidebarWidth,
  resolveInitialSv3SidebarWidth,
  storeSv3SidebarCollapsed,
  storeSv3SidebarWidth,
  SV3_SIDEBAR_DEFAULT_PX,
  SV3_SIDEBAR_KEY_STEP_PX,
} from './sv3-sidebar-sizing.js';
import {
  activeTurns,
  adoptRunSession,
  appendTurnDelta,
  focusSession,
  renameSession,
  toggleSessionPin,
  latestTurnRef,
  projectSv3Sessions,
  sessionById,
  setTurnEvidence,
  settleAgentTurn,
  settleTurn,
  startNewSession,
  submitInSession,
  SV3_SESSIONS_EMPTY,
  type Sv3SessionList,
} from './sv3-sessions.js';
import { sv3Ask } from './sv3-ask.js';
import {
  getAgentSessionController,
  peekAgentSessionController,
  subscribeAgentSession,
} from '../../state/agentSessionStore.js';
import type { AgentSessionController } from '../../controllers/AgentSessionController.js';
import {
  directiveAvailable,
  dispatchRunControl,
  type RunControlRefusal,
} from '../../controllers/runControlIntent.js';
import {
  deriveSv3RunPhase,
  hasServerAcknowledgedLocalDispatch,
  projectSv3RunFeed,
  projectSv3RunPrompts,
  sv3PrimaryAction,
  sv3RunNeedsPresence,
  sv3RunOutcome,
  sv3RunPresenceStart,
  sv3RunPresenceTitle,
  sv3RunSessionStatus,
  SV3_RUN_FEED_EMPTY,
  type Sv3RunFeed,
  type Sv3RunLocal,
  type Sv3RunTurnState,
  type Sv3RunView,
} from './sv3-run.js';
import { type Sv3RunDecision } from './Sv3Main.js';
import { subscribeAiState, type AiState } from '../../state/aiStateStore.js';
import { projectAvailability } from '../../state/availability.js';
import { reasonFor } from '../../state/readinessNotice.js';
import { SV3_COMMAND_SEARCH_TEXT } from './fixtures.js';
import { type Sv3PaletteRun } from './Sv3Palette.js';
import type { Sv3Palette } from './Sv3Palette.js';
import './Sv3Topbar.js';
import './Sv3Sidebar.js';
import './Sv3Main.js';
import './Sv3Composer.js';
import './Sv3Palette.js';

const COMPOSER_STATE_ATTR = 'composer-state';

/** The grip names all three of its gestures, because two of them are not discoverable by pointing. */
const SIDEBAR_GRIP_LABEL =
  'Resize the sidebar — arrow keys resize, Home returns to automatic, double-click resets';

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
        /* The donor's own collapse animation (ui/sidebar.tsx:283 — transition-[width] duration-200
           ease-linear). Suppressed during a drag below, for the donor's reason: an eased width lags
           a pointer that is setting it directly. */
        transition:
          flex-basis var(--duration-sv3-layout) var(--ease-sv3-linear),
          width var(--duration-sv3-layout) var(--ease-sv3-linear);
      }
      :host([sidebar-collapsed]) jf-sv3-sidebar {
        flex-basis: var(--sidebar-width-icon);
        width: var(--sidebar-width-icon);
      }
      :host([resizing]) jf-sv3-sidebar {
        transition: none;
      }
      /* THE GRIP (tempdoc 822 Phase F5). The donor's anatomy exactly (ui/sidebar.tsx:602): a w-4
         (16px) hit area straddling the boundary (-translate-x-1/2) with a 2px LINE drawn by ::after
         at its centre, invisible until hover. A native button rather than the donor's tabIndex={-1}
         rail, so the keyboard half of the boundary exists at all — the same construction
         views/search-v2/SearchV2View.ts:1937-1957 uses for the same job. */
      button.sidebar-grip {
        position: absolute;
        inset-block: 0;
        left: var(--sidebar-width);
        transform: translateX(-50%);
        inline-size: var(--space-4);
        padding: 0;
        border: 0;
        background: transparent;
        cursor: w-resize;
        z-index: var(--z-sticky);
        /* A drag must not be interpreted as a page scroll/pan gesture mid-gesture. */
        touch-action: none;
        transition: left var(--duration-sv3-layout) var(--ease-sv3-linear);
      }
      :host([sidebar-collapsed]) button.sidebar-grip {
        left: var(--sidebar-width-icon);
        cursor: e-resize;
      }
      :host([resizing]) button.sidebar-grip {
        transition: none;
      }
      button.sidebar-grip::after {
        content: '';
        position: absolute;
        inset-block: 0;
        left: 50%;
        inline-size: 2px;
        background: transparent;
        transition: background-color var(--duration-sv3-micro) var(--ease-sv3-enter);
      }
      button.sidebar-grip:hover::after {
        background: var(--sidebar-border);
      }
      /* Focus lights the LINE rather than drawing a ring: an outline around a 16px-wide, full-height
         hit area reads as a second boundary beside the first. The ring colour is used so the
         indicator is unmistakably a focus state and not the hover treatment. */
      button.sidebar-grip:focus-visible {
        outline: none;
      }
      button.sidebar-grip:focus-visible::after {
        background: var(--ring);
      }
      @media (prefers-reduced-motion: reduce) {
        jf-sv3-sidebar,
        button.sidebar-grip,
        button.sidebar-grip::after {
          transition: none;
        }
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
    aiSnapshot: { state: true },
    streaming: { state: true },
    sidebarWidthPx: { state: true },
    sidebarCollapsed: { type: Boolean, reflect: true, attribute: 'sidebar-collapsed' },
    resizing: { type: Boolean, reflect: true },
    renamingId: { state: true },
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
  /** The observed-state authority's latest emission; the ONE input to this window's availability. */
  declare aiSnapshot: AiState | null;
  /** A response is in flight. Window-level, not session-level: the composer's slot is one slot. */
  declare streaming: boolean;
  /**
   * The sidebar's chosen width (tempdoc 822 Phase F5). Kept EXPANDED-only: collapsing renders the
   * icon rail without touching this number, which is what makes "expand restores the width I chose"
   * true by construction rather than by saving and re-applying it.
   */
  declare sidebarWidthPx: number;
  declare sidebarCollapsed: boolean;
  /** A drag is in progress — the transitions stand down so the panel tracks the pointer exactly. */
  declare resizing: boolean;
  /** The session whose title is being edited, or null. */
  declare renamingId: string | null;

  private searchUnsubscribe: (() => void) | null = null;
  private aiUnsubscribe: (() => void) | null = null;
  private agentUnsubscribe: (() => void) | null = null;
  /** The in-flight ask's abort handle; null exactly when no response is streaming. */
  private askAbort: AbortController | null = null;
  /**
   * What this window remembers about the delegated run it dispatched — including the explicit turn
   * ref that is its `activeTurnId`. Not reactive state: it is mutated in place by the controller's
   * notifications (a latch and two flags), and every one of those paths already re-renders.
   */
  private run: Sv3RunLocal | null = null;
  /** Whether the run was observed LIVE, so its terminal is an EDGE rather than a repeated verdict. */
  private runLive = false;
  /**
   * Controller run ids this window has already given a session (Phase F3 presence). Adoption happens
   * once per run: without the latch, the same live run would be re-adopted on the next notification
   * after its turn settled, and the sidebar would grow a row per frame.
   */
  private readonly adoptedRunIds = new Set<string>();

  constructor() {
    super();
    this.composerState = COMPOSER_STATE_DEFAULT;
    this.apiBase = '';
    this.searchSnapshot = null;
    this.asked = false;
    this.sessions = SV3_SESSIONS_EMPTY;
    this.aiSnapshot = null;
    this.streaming = false;
    this.sidebarWidthPx = SV3_SIDEBAR_DEFAULT_PX;
    this.sidebarCollapsed = false;
    this.resizing = false;
    this.renamingId = null;
  }

  override connectedCallback(): void {
    super.connectedCallback();
    adoptSv3MorphSheet();
    this.restoreSidebarPreferences();
    setSearchApiBase(this.apiBase || '');
    this.searchUnsubscribe = subscribeSearch((snapshot) => {
      this.searchSnapshot = snapshot;
    });
    this.aiUnsubscribe = subscribeAiState((snapshot) => {
      this.aiSnapshot = snapshot;
    });
    // Subscribing does NOT create a controller (the read below is a `peek`), so a window that never
    // delegates never starts the agent controller's polling as a side effect of being mounted.
    this.agentUnsubscribe = subscribeAgentSession(this.onAgentUpdate);
    // The window may be mounting BESIDE a run that is already going (a surface switch, a re-mount).
    // The store notifies on change only, so the first look has to be taken here — see
    // `syncRunPresence` for why an unrepresented live run is this window's problem to state.
    this.syncRunPresence();
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
    this.aiUnsubscribe?.();
    this.aiUnsubscribe = null;
    // The RUN is not cancelled here. Unlike the ask stream (which this window owns), a delegated run
    // is hosted by the product-wide controller and may be watched from another surface; tearing it
    // down because this dev window unmounted would be this window deciding for the whole product.
    this.agentUnsubscribe?.();
    this.agentUnsubscribe = null;
    // Lifecycle containment: an unmounted window's stream would keep a connection open against the
    // shared channel budget and settle a turn nobody can see.
    this.abortAsk();
    this.removeEventListener('keydown', this.onHostKeydown, true);
  }

  /**
   * The shell re-sets `api-base` on a CACHED element rather than reconstructing it, so the base has
   * to follow the attribute and not just the first connect.
   */
  protected override updated(changed: Map<string, unknown>): void {
    if (changed.has('apiBase')) setSearchApiBase(this.apiBase || '');
    if (changed.has('sidebarWidthPx')) this.applySidebarWidth(this.sidebarWidthPx);
  }

  /**
   * `--sidebar-width` is written as an INLINE custom property on the host — the donor's own mechanism
   * (`AppSidebarLayout.tsx:164-169` / `ui/sidebar.tsx:503`), which is why the panel, the grip's
   * position and the collapse animation all read one number instead of three. Inline beats the token
   * sheet's `:host` declaration, so the 16rem default stays the value the window opens at when
   * nothing has been chosen.
   */
  private applySidebarWidth(px: number): void {
    this.style.setProperty('--sidebar-width', `${px}px`);
  }

  /**
   * The box the sidebar and the main region actually share — the donor's `wrapper`, not the viewport.
   *
   * An UNMEASURABLE box (0, i.e. not laid out yet) yields no ceiling rather than a tiny one: an
   * unknown width is not a narrow width, and treating it as one would collapse a remembered
   * preference to the floor as a side effect of the window not having been painted. The FLOOR still
   * applies — `clampSv3SidebarWidth` keeps it on the outside — so nothing illegal gets through.
   */
  private availableWidth(): number {
    const measured = this.getBoundingClientRect().width;
    return measured > 0 ? measured : Number.POSITIVE_INFINITY;
  }

  private restoreSidebarPreferences(): void {
    this.sidebarCollapsed = readStoredSv3SidebarCollapsed();
    this.sidebarWidthPx = resolveInitialSv3SidebarWidth(
      readStoredSv3SidebarWidth(),
      this.availableWidth(),
    );
    this.applySidebarWidth(this.sidebarWidthPx);
  }

  /** A chosen width is adopted AND remembered; the two are one act (818 L13). */
  private adoptSidebarWidth(px: number): void {
    this.sidebarWidthPx = px;
    storeSv3SidebarWidth(px);
  }

  /**
   * L13 / donor `resetSidebarWidth` (`AppSidebarLayout.tsx:150-157`): returning the boundary to
   * automatic FORGETS the remembered width rather than storing the default over it — the reader
   * withdrew a choice, and a window that stored "256" would reopen at 256 even after the default moved.
   */
  private resetSidebarWidth(): void {
    forgetSv3SidebarWidth();
    this.sidebarWidthPx = resolveInitialSv3SidebarWidth(null, this.availableWidth());
  }

  /**
   * The donor's drag (`ui/sidebar.tsx:408-520`), ported: pointer capture so the gesture survives the
   * pointer outrunning the 16px handle, the width written DIRECTLY during the move (a re-render per
   * frame would re-project every session row), the clamp taken from the box measured AT DRAG TIME,
   * and the chosen width adopted once at the end.
   */
  private onGripPointerDown(event: PointerEvent): void {
    if (event.button !== 0 || this.sidebarCollapsed) return;
    event.preventDefault();
    const grip = event.currentTarget as HTMLElement;
    grip.setPointerCapture?.(event.pointerId);
    const available = this.availableWidth();
    const startX = event.clientX;
    const startWidth = this.sidebarWidthPx;
    let width = startWidth;
    this.resizing = true;
    const move = (moved: PointerEvent): void => {
      width = clampSv3SidebarWidth(startWidth + (moved.clientX - startX), available);
      this.applySidebarWidth(width);
    };
    const end = (): void => {
      grip.removeEventListener('pointermove', move);
      grip.removeEventListener('pointerup', end);
      grip.removeEventListener('pointercancel', end);
      this.resizing = false;
      this.adoptSidebarWidth(width);
    };
    grip.addEventListener('pointermove', move);
    grip.addEventListener('pointerup', end);
    grip.addEventListener('pointercancel', end);
  }

  /**
   * The keyboard half of the SAME boundary — same clamp, same floor, one nudge at a time. The donor
   * has no equivalent (its rail is `tabIndex={-1}`); this is `views/search-v2`'s answer, and the
   * a11y contract's: a boundary a pointer can move must be movable without one.
   */
  private onGripKeydown(event: KeyboardEvent): void {
    if (event.key === 'Home' || event.key === 'Escape') {
      event.preventDefault();
      this.resetSidebarWidth();
      return;
    }
    if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
    event.preventDefault();
    const step = event.key === 'ArrowRight' ? SV3_SIDEBAR_KEY_STEP_PX : -SV3_SIDEBAR_KEY_STEP_PX;
    this.adoptSidebarWidth(
      clampSv3SidebarWidth(this.sidebarWidthPx + step, this.availableWidth()),
    );
  }

  /**
   * A click on the grip EXPANDS a collapsed panel and otherwise does nothing — the donor's own rule
   * (`ui/sidebar.tsx:540-556`: the rail toggles exactly when it cannot resize). No drag-suppression
   * latch is needed on this side of it, because a collapsed panel is the only state in which the
   * click does anything and {@link onGripPointerDown} refuses to start a drag there.
   */
  private onGripClick(): void {
    if (this.sidebarCollapsed) this.setSidebarCollapsed(false);
  }

  private setSidebarCollapsed(collapsed: boolean): void {
    this.sidebarCollapsed = collapsed;
    storeSv3SidebarCollapsed(collapsed);
  }

  private onSidebarToggle(): void {
    this.setSidebarCollapsed(!this.sidebarCollapsed);
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
   * A send is one ask and one morph, in that order: the request goes out in this tick, so the
   * transcript is already showing the streaming turn by the time the morph settles.
   *
   * THREE destinations, decided here and nowhere else (the composer announces a draft and a tier; it
   * does not know what a run is). The mid-run case comes FIRST and overrides the tier: a submit while
   * a delegated run is live JOINS that run as a steering directive — it is not an interrupt and it is
   * not a second commitment, so pressing Ctrl+Enter mid-run cannot start a competing run.
   */
  private onComposerSubmit(event: Event): void {
    const detail = (event as CustomEvent<Sv3ComposerSubmit>).detail;
    const text = (detail?.query ?? '').trim();
    if (text === '') return;
    if (this.steerableRun !== null) {
      this.steerLiveRun(text);
      return;
    }
    if (detail?.tier === 'delegate') {
      this.delegate(text);
      return;
    }
    void this.runAsk(text);
  }

  /** The composer's availability, projected from the ONE observed-state authority. */
  private get askUnavailableReason(): string {
    const availability = projectAvailability('documents', this.aiSnapshot);
    return availability.kind === 'unavailable' ? availability.reason : '';
  }

  /**
   * The DELEGATE tier's own gate, from the same authority. It is a strict SUPERSET of the ask tier's:
   * both need a live backend and a loaded model, but only the ask tier additionally needs an indexed
   * document to ground an answer in. Reading one reason for both would therefore refuse a delegation
   * the agent could have served — the two tiers get two projections rather than one shared guess.
   */
  private get delegateUnavailableReason(): string {
    const availability = projectAvailability('agent', this.aiSnapshot);
    return availability.kind === 'unavailable' ? availability.reason : '';
  }

  /**
   * The ONE path a question takes. There is no second entry: the composer refuses an unavailable or
   * busy send before it leaves, so this method is not a second gate re-deciding the same question —
   * it opens the turn, dispatches through the window's single ask site, and settles that turn.
   */
  private async runAsk(rawQuestion: string): Promise<void> {
    const question = rawQuestion.trim();
    if (question === '') return;
    this.sessions = submitInSession(this.sessions, question, Date.now());
    const ref = latestTurnRef(this.sessions);
    if (ref === null) return;
    this.composer?.clearDraft();
    void this.setComposerState('docked');

    const abort = new AbortController();
    this.askAbort = abort;
    this.streaming = true;
    // Every terminal below settles the SAME ref the dispatch opened, so a reader who claims another
    // session mid-stream still gets the answer written where it was asked.
    const settle = (
      status: 'complete' | 'halted' | 'refused' | 'failed',
      detail = '',
    ): void => {
      this.sessions = settleTurn(this.sessions, ref, status, Date.now(), detail);
      // Only THIS dispatch's terminal may clear the window's busy state: a later ask has already
      // installed its own controller, and clearing it here would strand a live stream with a Send
      // control in the slot.
      if (this.askAbort === abort) {
        this.askAbort = null;
        this.streaming = false;
      }
    };
    await sv3Ask(
      {
        apiBase: this.apiBase,
        question,
        conversationId: ref.sessionId,
        signal: abort.signal,
      },
      {
        onDelta: (text) => {
          this.sessions = appendTurnDelta(this.sessions, ref, text);
        },
        onEvidence: (evidence) => {
          this.sessions = setTurnEvidence(this.sessions, ref, evidence);
        },
        onDone: () => settle('complete'),
        // The lock's refusal is worded by the ONE reason vocabulary, not re-phrased here.
        onRefused: () => settle('refused', reasonFor('conversations.locked').wording),
        onHalted: () => settle('halted'),
        onFailed: (message) => settle('failed', message),
      },
    );
  }

  /** Halting is always the reader's; the turn settles `halted` through the sink's own terminal. */
  private abortAsk(): void {
    this.askAbort?.abort();
  }

  /* ── The delegate tier (tempdoc 822 Phase F2) ─────────────────────────────────────────────── */

  /**
   * The shared run controller as a READ. `peek` never constructs one, so "is a run live?" can be
   * asked on every render without this window creating a controller — and starting its polling — as
   * a side effect of being looked at.
   */
  private agentController(): AgentSessionController | null {
    return peekAgentSessionController();
  }

  /**
   * The live run this window owns AND that accepts a steer, or null. Both halves matter: the
   * controller is product-wide, so a run this window did not dispatch is not this window's to
   * redirect, and only an `agent` run has an interject channel at all (a workflow or background run
   * does not) — the seam's own lifecycle predicate decides the rest.
   */
  private get steerableRun(): AgentSessionController | null {
    const ctrl = this.agentController();
    if (ctrl === null || this.run === null) return null;
    if (ctrl.runKind !== 'agent') return null;
    return directiveAvailable(ctrl, { kind: 'interject', text: '' }) ? ctrl : null;
  }

  /**
   * The DELEGATE path: the draft becomes an agent task. The causal order mirrors the ask — the turn
   * is opened first, then the run is dispatched — so the transcript already shows what was committed
   * before anything can come back, and the run has a turn to be written to from its very first frame.
   *
   * The run is HOSTED, never re-implemented: the shared `AgentSessionController` runs it and every
   * directive leaves through the ONE `dispatchRunControl` seam (`governance/steering-surfaces.v1.json`).
   */
  private delegate(text: string): void {
    // Defence in depth, not a second gate: the composer already refuses an unavailable delegate and
    // keeps the draft. This exists because `delegate` is reachable from the window's own routing.
    if (this.delegateUnavailableReason !== '') return;
    this.sessions = submitInSession(this.sessions, text, Date.now(), 'agent');
    const ref = latestTurnRef(this.sessions);
    if (ref === null) return;
    this.composer?.clearDraft();
    void this.setComposerState('docked');

    const ctrl = getAgentSessionController(this.apiBase);
    // The run's thread events are stamped with THIS window's session, so a delegated run lands under
    // the conversation that asked for it rather than under whatever the controller ran last.
    ctrl.conversationId = ref.sessionId;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      // The window's slice of a product-wide conversation: everything before this belongs to someone
      // else's run and must never be counted into this one's receipt.
      entryStart: ctrl.conversation.length,
      sessionIdAtDispatch: ctrl.sessionId,
      acknowledged: false,
      haltRequested: false,
      haltDispatched: false,
    };
    this.runLive = false;
    this.requestUpdate();
    void dispatchRunControl(ctrl, { kind: 'initiate', prompt: text });
  }

  /**
   * A mid-run submit JOINS the live turn. Through the seam, like every other per-run directive.
   *
   * Named `steerLiveRun` and not `steer` on purpose: the steering register bans a bare `.steer(` call
   * anywhere but the seam, and `this.steer(` would read as exactly that to the gate's scan. A method
   * whose name makes a correct call look like the forbidden one is a trap for the next reader too.
   */
  private steerLiveRun(text: string): void {
    const ctrl = this.steerableRun;
    if (ctrl === null) return;
    this.composer?.clearDraft();
    void dispatchRunControl(ctrl, { kind: 'interject', text });
  }

  /**
   * The reader's stop. Recorded FIRST and dispatched second, because the record of the decision is
   * what makes `halted` an honest receipt outcome — and because a stop pressed before the stream
   * opened cannot be delivered yet (the seam's predicate refuses a halt on a run with no abort handle
   * yet). Remembering it means the next update delivers it, instead of the decision being dropped.
   */
  private haltRun(): void {
    const local = this.run;
    if (local === null) return;
    local.haltRequested = true;
    this.deliverHalt();
    this.requestUpdate();
  }

  private deliverHalt(): void {
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl === null || local === null || local.haltDispatched) return;
    void dispatchRunControl(ctrl, { kind: 'halt' }).then((result) => {
      // Only an ACCEPTED halt closes the door; a lifecycle refusal leaves the request pending so the
      // next update can try again, and does so without a retry storm.
      if ((result as RunControlRefusal | undefined)?.refused !== true) local.haltDispatched = true;
    });
  }

  /**
   * The shared controller moved. Three things happen: the optimistic handoff latches the first time
   * the SERVER says anything, a pending halt is delivered once the run can honour it, and the run's
   * TERMINAL edge is detected so exactly one receipt lands.
   */
  /**
   * Does this window have an OPEN turn standing for a run? A settled turn stands for nothing: its
   * receipt is written and a later run is not the same run.
   */
  private get runRepresented(): boolean {
    const local = this.run;
    if (local === null) return false;
    const turn = sessionById(this.sessions, local.sessionId)?.turns.find(
      (t) => t.id === local.turnId,
    );
    return turn?.status === 'streaming';
  }

  /**
   * PRESENCE (tempdoc 822 Phase F3) — the fix for F2's named finding: *window-local in-memory
   * sessions orphan a live run on reload*. A fresh window showed zero sessions while the run went on
   * holding server-side, so the window was silently disagreeing with the product about what was
   * happening.
   *
   * The rule this establishes: the SHARED CONTROLLER is the authority on whether a run is live, and
   * this window's memory is not. When the controller reports a live or holding run that no session
   * here accounts for, the window synthesises one — titled with the run's own task text, carrying an
   * open agent turn, landing on the Active shelf — and adopts it as the run it renders. `entryStart`
   * is 0 because this window dispatched nothing: the whole conversation the controller holds is that
   * run's. `acknowledged` is true for the same reason — there is no optimistic local echo to yield;
   * every frame of it came from the server already.
   *
   * It reads through `peek`, so a window that never delegates still constructs no controller and
   * starts no polling by being mounted (the F2 law). And it does not CLAIM the adopted session: the
   * run is news, not a navigation.
   */
  private syncRunPresence(): void {
    const ctrl = this.agentController();
    if (ctrl === null) return;
    // The controller's conversation accumulates across runs, so the LIVE run's slice starts at the
    // task it was given — not at 0, which would read a finished run's steps as this one's.
    const start = sv3RunPresenceStart(ctrl);
    const feed = projectSv3RunFeed(ctrl, start);
    const probe = {
      status: sv3RunSessionStatus(ctrl, feed),
      represented: this.runRepresented,
      runId: ctrl.sessionId,
      adoptedRunIds: this.adoptedRunIds,
    };
    if (!sv3RunNeedsPresence(probe)) return;
    const { list, ref } = adoptRunSession(this.sessions, sv3RunPresenceTitle(ctrl), Date.now());
    this.sessions = list;
    this.run = {
      sessionId: ref.sessionId,
      turnId: ref.turnId,
      entryStart: start,
      sessionIdAtDispatch: null,
      acknowledged: true,
      haltRequested: false,
      haltDispatched: false,
    };
    // Observed live at adoption, so the run's terminal is an EDGE for this window too and the
    // adopted turn gets its one receipt instead of streaming forever.
    this.runLive = true;
    if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
  }

  private readonly onAgentUpdate = (): void => {
    // Presence first: a run this window has no session for gets one before the frame is read, so the
    // rest of this method has something to write the run's progress into.
    this.syncRunPresence();
    const ctrl = this.agentController();
    const local = this.run;
    if (ctrl !== null && local !== null) {
      // Latched once and never read again: a run whose evidence later disappears cannot push this
      // window back into claiming it is still sending.
      if (!local.acknowledged && hasServerAcknowledgedLocalDispatch(local, ctrl)) {
        local.acknowledged = true;
      }
      // A run this window is already rendering is a run it must never ALSO adopt as presence — the
      // id is only knowable once the server names it, which is here rather than at dispatch.
      if (ctrl.sessionId !== null) this.adoptedRunIds.add(ctrl.sessionId);
      const feed = projectSv3RunFeed(ctrl, local.entryStart);
      const status = sv3RunSessionStatus(ctrl, feed);
      if (status === 'live' || status === 'holding') {
        this.runLive = true;
        if (local.haltRequested) this.deliverHalt();
      } else if (this.runLive) {
        this.runLive = false;
        this.concludeRun(local, feed);
      }
    }
    this.requestUpdate();
  };

  /**
   * The run's terminal: exactly ONE receipt, written to the turn the dispatch opened. The count comes
   * from the SAME feed projection the cards were rendered from, so the receipt can never disagree
   * with what the reader watched — and it is addressed by ref, so a later run started from another
   * surface cannot write its numbers into this window's turn.
   */
  private concludeRun(local: Sv3RunLocal, feed: Sv3RunFeed): void {
    this.sessions = settleAgentTurn(
      this.sessions,
      { sessionId: local.sessionId, turnId: local.turnId },
      sv3RunOutcome(feed, local.haltRequested),
      feed.toolCallCount,
      Date.now(),
    );
  }

  /** A typed prompt resolved by its OWN control — never by anything typed into the composer. */
  private onRunDecision(event: Event): void {
    const ctrl = this.agentController();
    const detail = (event as CustomEvent<Sv3RunDecision>).detail;
    if (ctrl === null || detail === undefined) return;
    if (detail.kind === 'budget') {
      if (detail.decision === 'stop') this.markHaltRequested();
      void dispatchRunControl(ctrl, { kind: 'budget-decision', decision: detail.decision });
      return;
    }
    if (detail.decision === 'stop') this.markHaltRequested();
    void dispatchRunControl(ctrl, { kind: 'context-decision', decision: detail.decision });
  }

  /** A gate resolved with "stop" IS the reader halting, so the receipt must say so. */
  private markHaltRequested(): void {
    if (this.run !== null) this.run.haltRequested = true;
  }

  /**
   * The `answer` rung of the primary slot. The composer cannot resolve a typed prompt, so the control
   * does the one honest thing available to it: it takes the reader to the decision.
   */
  private onComposerAnswer(): void {
    const main = this.shadowRoot?.querySelector('jf-sv3-main');
    const prompt = main?.shadowRoot?.querySelector<HTMLElement>('[data-testid="sv3-run-prompt"]');
    if (prompt === null || prompt === undefined) return;
    prompt.scrollIntoView({ block: 'nearest' });
    prompt.querySelector('button')?.focus();
  }

  /**
   * The SECONDARY axis (822 §4b course correction). Phase A1's search seam stays wired and tested,
   * but a plain submit no longer reaches it — it is called from the palette's "Search this text"
   * command, which keeps the seam demonstrable until the deferred search-integration conversation
   * decides what search means in a conversational window. It deliberately does NOT touch the session
   * list: a search is not a turn, and recording it as one would fabricate a conversation.
   */
  private runSearch(rawQuery: string): void {
    const query = rawQuery.trim();
    if (query === '') return;
    this.asked = true;
    setQuery(query);
    submitSearch();
    void this.setComposerState('docked');
  }

  private onPaletteRun(event: Event): void {
    if ((event as CustomEvent<Sv3PaletteRun>).detail?.id !== SV3_COMMAND_SEARCH_TEXT) return;
    this.runSearch(this.composer?.draft ?? '');
  }

  private get composer(): Sv3Composer | null {
    return this.shadowRoot?.querySelector('jf-sv3-composer') ?? null;
  }

  /**
   * A row click CLAIMS that conversation and shows its transcript. It re-runs nothing: a session is
   * a thread now, and re-issuing its opening question on a click would append a turn the reader
   * never asked for (Phase F1 — A2's row click re-ran the search, which was right for a search list
   * and is wrong for a conversation).
   */
  private onSessionSelect(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionSelect>).detail?.id ?? '';
    // An edit in another row is DROPPED rather than committed, the donor's rule
    // (`ChatHeader.tsx:143-149`): navigating away must not write text the reader walked away from.
    this.renamingId = null;
    this.sessions = focusSession(this.sessions, id, Date.now());
    void this.setComposerState('docked');
  }

  /**
   * The reader names a conversation (tempdoc 822 Phase F5). The three phases meet here because the
   * window owns the list: the row raises intent, the panel says which row, and only this decides.
   * `commit` routes through `renameSession`, so an empty title reverts by the pure module's rule
   * rather than by a check duplicated at the view.
   */
  private onSessionRename(event: Event): void {
    const detail = (event as CustomEvent<Sv3SessionRename>).detail;
    if (detail === undefined) return;
    if (detail.phase === 'start') {
      this.renamingId = detail.id;
      return;
    }
    this.renamingId = null;
    if (detail.phase === 'commit') {
      this.sessions = renameSession(this.sessions, detail.id, detail.title ?? '');
    }
  }

  /**
   * The reader parks a conversation on the Pinned shelf, or takes it off. It is a list write and
   * nothing else — pinning does not claim the conversation, does not reorder anything, and does not
   * move a run off the Active shelf (a blocked run cannot be hidden; `projectSv3Sessions` owns that).
   */
  private onSessionPin(event: Event): void {
    const id = (event as CustomEvent<Sv3SessionPin>).detail?.id ?? '';
    this.sessions = toggleSessionPin(this.sessions, id);
  }

  /**
   * New session: back to the hero with an empty draft and nothing claimed about the corpus. The
   * sessions so far stay in the list — starting one is not ending the others. An in-flight response
   * IS ended, because its own session is no longer the one on screen and a stream nobody is watching
   * still spends a connection.
   */
  private onSessionNew(): void {
    this.abortAsk();
    this.renamingId = null;
    this.sessions = startNewSession(this.sessions);
    this.asked = false;
    this.composer?.clearDraft();
    void this.setComposerState('hero');
  }

  /**
   * The Stop slot serves BOTH streams, because there is one slot. Which one it halts is decided here,
   * by which one is actually running — the composer says the reader pressed Stop and nothing more.
   */
  private onComposerStop(): void {
    if (this.streaming) {
      this.abortAsk();
      return;
    }
    this.haltRun();
  }

  /**
   * TWO AXES, ONE PHASE (donor pattern 1). The SESSION axis is what the shared controller says; the
   * TURN axis is what this window's own turn is doing — including the optimistic window before the
   * server has acknowledged the dispatch, which no controller field can report because the controller
   * is optimistic too. `deriveSv3RunPhase` collapses them into the one value everything renders from,
   * so the slot, the feed, the sidebar colour and the receipt cannot each decide separately.
   */
  private projectRun(): Sv3RunView | null {
    const local = this.run;
    if (local === null) return null;
    const ctrl = this.agentController();
    const feed = ctrl === null ? SV3_RUN_FEED_EMPTY : projectSv3RunFeed(ctrl, local.entryStart);
    const prompts = ctrl === null ? [] : projectSv3RunPrompts(ctrl, feed);
    const turn = sessionById(this.sessions, local.sessionId)?.turns.find(
      (t) => t.id === local.turnId,
    );
    const turnState: Sv3RunTurnState =
      turn === undefined || turn.status !== 'streaming'
        ? 'settled'
        : local.acknowledged
          ? 'open'
          : 'dispatching';
    return {
      turnId: local.turnId,
      phase: deriveSv3RunPhase({ session: sv3RunSessionStatus(ctrl, feed), turn: turnState }),
      feed,
      prompts,
    };
  }

  render(): TemplateResult {
    const results: Sv3ResultsView = projectSv3Results(this.searchSnapshot, this.asked);
    // Relative timestamps are computed HERE, on render, and never ticked: a sidebar that re-renders
    // itself every second is continuous motion at rest, which the donor's duty-cycle law rules out.
    // `isRefining` counts too: the store runs a re-query BEHIND displayed results quietly
    // (`state/searchState.ts:611` — no skeleton, so the content surface keeps the old rows), and the
    // row's dot is then the only thing on screen saying the session is running a pass.
    const snapshot = this.searchSnapshot;
    const run = this.projectRun();
    const turns = activeTurns(this.sessions);
    const pendingPrompt = run !== null && run.prompts.length > 0;
    const slot = sv3PrimaryAction({
      pendingPrompt,
      running:
        this.streaming ||
        run?.phase === 'dispatching' ||
        run?.phase === 'running' ||
        run?.phase === 'holding',
      followUp: turns.length > 0,
    });
    const sessionGroups = projectSv3Sessions(this.sessions, {
      searching: this.asked && (snapshot?.isSearching === true || snapshot?.isRefining === true),
      // Named by session, not by flag: only the conversation that OPENED the parked run may wear the
      // act-now colour, whichever row the reader happens to be looking at.
      awaitingDecisionIn: pendingPrompt && this.run !== null ? this.run.sessionId : null,
      now: Date.now(),
    });
    return html`
      <jf-sv3-sidebar
        .groups=${sessionGroups}
        .renamingId=${this.renamingId}
        ?collapsed=${this.sidebarCollapsed}
        data-testid="sv3-sidebar"
        @sv3-session-select=${this.onSessionSelect}
        @sv3-session-pin=${this.onSessionPin}
        @sv3-session-new=${this.onSessionNew}
        @sv3-session-rename=${this.onSessionRename}
        @sv3-sidebar-toggle=${this.onSidebarToggle}
      ></jf-sv3-sidebar>
      <button
        type="button"
        class="sidebar-grip"
        data-testid="sv3-sidebar-grip"
        aria-label=${SIDEBAR_GRIP_LABEL}
        title=${SIDEBAR_GRIP_LABEL}
        @pointerdown=${this.onGripPointerDown}
        @keydown=${this.onGripKeydown}
        @click=${this.onGripClick}
        @dblclick=${this.resetSidebarWidth}
      ></button>
      <div
        class="column"
        data-testid="sv3-column"
        @sv3-composer-state-request=${this.onStateRequest}
        @sv3-composer-submit=${this.onComposerSubmit}
        @sv3-composer-stop=${this.onComposerStop}
        @sv3-composer-answer=${this.onComposerAnswer}
        @sv3-run-decision=${this.onRunDecision}
        @sv3-palette-request=${this.onPaletteRequest}
      >
        <jf-sv3-topbar window-title=${WINDOW_TITLE} data-testid="sv3-topbar"></jf-sv3-topbar>
        <jf-sv3-main
          state=${this.composerState}
          .view=${results}
          .turns=${turns}
          .run=${run}
          data-testid="sv3-main"
        ></jf-sv3-main>
        <jf-sv3-composer
          state=${this.composerState}
          slot-kind=${slot.kind}
          slot-reason=${slot.reason}
          ?steerable=${this.steerableRun !== null}
          unavailable-reason=${this.askUnavailableReason}
          delegate-unavailable-reason=${this.delegateUnavailableReason}
          data-testid="sv3-composer"
        ></jf-sv3-composer>
      </div>
      <!-- LAST in the shadow root on purpose: the palette and the hero composer share the overlay
           rung, so DOM order is what puts the palette on top. -->
      <jf-sv3-palette data-testid="sv3-palette" @sv3-palette-run=${this.onPaletteRun}></jf-sv3-palette>
    `;
  }
}

customElements.define('jf-sv3-window', SearchV3View);

declare global {
  interface HTMLElementTagNameMap {
    'jf-sv3-window': SearchV3View;
  }
}
