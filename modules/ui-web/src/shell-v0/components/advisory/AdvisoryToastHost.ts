// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 490 §4.D — Toast host: ephemeral overlay rendering newly-arrived
 * advisories briefly before they vanish into the inbox.
 *
 * V1 renders every new (unacknowledged-on-arrival) advisory as a toast
 * regardless of {@code emissionPolicy.renderHint}. Future iterations can
 * gate by RenderHint == EPHEMERAL once that policy field is consumed
 * upstream of the FE.
 *
 * Toasts auto-dismiss after {@link TOAST_DURATION_MS}. Clicking the toast
 * marks the advisory acknowledged (inbox immediately reflects the read
 * state via UserStateDocument's projection).
 */

import { css, html, type TemplateResult, nothing } from 'lit';
import { JfElement } from '../../primitives/JfElement.js';
import {
  AdvisoryStore,
  type AdvisoryRecord,
  type AdvisorySnapshot,
} from './AdvisoryStore.js';
import { advisoryClassChrome } from './AdvisoryClassChrome.js';
import { type NoticeTone, type NoticeLive } from '../SystemNotice.js';
// Tempdoc 613 §14 — the local toast's tone/politeness/dwell are a projection of its declared severity,
// not render-site literals (the former `severityToTone` is absorbed by this one authority).
import { presentationForSeverity } from '../../state/messageClasses.js';
import '../SystemNotice.js';
import type { OperationClient } from '../../operations/OperationClient.js';
import '../DispatchSource.js';

const TOAST_DURATION_MS = 5000;

interface VisibleToast {
  readonly record: AdvisoryRecord;
  /**
   * Slice 490 Pass-8 follow-up — null when the upstream store's
   * {@code renderHint} is {@code REQUIRES_ACK}; the toast persists until the
   * user clicks (which acknowledges + dismisses). For {@code EPHEMERAL} and
   * {@code PERSISTED} stores, the timeout fires after
   * {@link TOAST_DURATION_MS}.
   */
  timeoutId: ReturnType<typeof setTimeout> | null;
}

export class AdvisoryToastHost extends JfElement {
  static properties = {
    store: { attribute: false },
    operationClient: { attribute: false },
    visible: { state: true },
  };

  declare store: AdvisoryStore | null;
  declare operationClient: OperationClient | null;
  declare visible: VisibleToast[];

  private storeUnsubscribe: (() => void) | null = null;
  private seenKeys = new Set<string>();
  // Tempdoc 602 R4 — keys currently animating out, so the supersede prune in
  // onSnapshot does not double-dismiss a toast already being removed.
  private exiting = new Set<string>();
// Slice 490 substrate-completion (P2.3) — renderHint is now per-event
// (record.sourceRenderHint) rather than per-store. The toast host reads the
// hint at pushToast() time from the individual record; no store-level field
// needs to be tracked here. Removed the v1 `currentRenderHint` shadow field.
  /**
   * Slice 490 follow-up — flips on the first LIFECYCLE snapshot frame the store
   * delivers, regardless of payload size. Before that flag, no advisory is treated
   * as toast-worthy (the snapshot's contents are historical replay). After, every
   * advisory whose key is new vs `seenKeys` fires a toast.
   *
   * <p>Replaces the prior `seenKeys.size === 0` heuristic, which mis-classified the
   * first UPDATE as "snapshot seed" when the first snapshot was empty.
   */
  private hasSeenFirstSnapshot = false;

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost top-right slot. */
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      pointer-events: none;
    }
    /* 559 notice-presentation: the toast wrapper owns float/animation/interaction;
       the inner <jf-system-notice> owns the notice shell (bg/border/tone/padding). */
    .toast {
      pointer-events: auto;
      min-width: 18rem;
      max-width: 24rem;
      border-radius: 0.5rem;
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
      font-size: var(--font-size-sm);
      line-height: 1.35;
      cursor: pointer;
      animation: jf-toast-in 180ms ease-out;
    }
    .title {
      font-weight: 600;
      margin-bottom: 0.25rem;
    }
    .meta {
      color: var(--text-secondary);
      font-size: var(--font-size-xs);
    }
    .action-row {
      display: flex;
      gap: 0.5rem;
      margin-top: 0.5rem;
    }
    .action-btn {
      padding: 0.25rem 0.625rem;
      font-size: var(--font-size-xs);
      font-weight: 600;
      border: 1px solid var(--accent);
      border-radius: 0.25rem;
      background: transparent;
      color: var(--accent);
      cursor: pointer;
      pointer-events: auto;
    }
    .action-btn:hover {
      background: var(--accent);
      color: var(--surface-2);
    }
    .action-btn.running {
      opacity: 0.6;
      pointer-events: none;
    }
    .action-btn.success {
      border-color: var(--accent-success);
      color: var(--text-success);
      transition: border-color var(--duration-normal), color var(--duration-normal);
    }
    .action-btn.failed {
      border-color: var(--accent-warning);
      color: var(--text-warning);
      transition: border-color var(--duration-normal), color var(--duration-normal);
    }
    .toast.exiting {
      animation: jf-toast-out 180ms ease-in forwards;
    }
    @keyframes jf-toast-in {
      from {
        opacity: 0;
        transform: translateY(-4px);
      }
      to {
        opacity: 1;
        transform: translateY(0);
      }
    }
    @keyframes jf-toast-out {
      from {
        opacity: 1;
        transform: translateY(0);
      }
      to {
        opacity: 0;
        transform: translateY(-8px);
      }
    }
    /* a11y — honor prefers-reduced-motion: no slide/fade. dismiss() has a 250ms
       setTimeout fallback, so the lost animationend is safe. */
    @media (prefers-reduced-motion: reduce) {
      .toast,
      .toast.exiting {
        animation: none;
      }
    }
  `;

  constructor() {
    super();
    this.store = null;
    this.operationClient = null;
    this.visible = [];
  }

  private keydownListener: ((e: KeyboardEvent) => void) | null = null;

  override connectedCallback(): void {
    super.connectedCallback();
    if (this.store) {
      this.storeUnsubscribe = this.store.subscribe((s) => this.onSnapshot(s));
    }
    this.keydownListener = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key === 'z' && !e.shiftKey) {
        const undoToast = this.visible.find(
          (t) => t.record.event.primaryActionKind === 'undo',
        );
        if (undoToast) {
          e.preventDefault();
          const btn = this.shadowRoot?.querySelector(
            `[data-key="${CSS.escape(undoToast.record.key)}"] .action-btn`,
          ) as HTMLButtonElement | null;
          if (btn) btn.click();
        }
      }
    };
    document.addEventListener('keydown', this.keydownListener);
  }

  override disconnectedCallback(): void {
    if (this.storeUnsubscribe) {
      this.storeUnsubscribe();
      this.storeUnsubscribe = null;
    }
    if (this.keydownListener) {
      document.removeEventListener('keydown', this.keydownListener);
      this.keydownListener = null;
    }
    for (const t of this.visible) {
      if (t.timeoutId !== null) clearTimeout(t.timeoutId);
    }
    this.visible = [];
    super.disconnectedCallback();
  }

  private onSnapshot(s: AdvisorySnapshot): void {
    // Tempdoc 602 R4 — supersede reconcile: a superseding emit drops the prior
    // same-classId local record from the store, so any toast for a departed
    // local record is animated out here. Without this the replaced toast lingers
    // until its own timeout and briefly stacks beside its successor.
    const liveLocalKeys = new Set(
      s.advisories.filter((r) => r.origin === 'local').map((r) => r.key),
    );
    for (const t of this.visible) {
      if (t.record.origin === 'local' && !liveLocalKeys.has(t.record.key)) {
        this.dismiss(t.record.key);
      }
    }
    // 559 Authority III — local-origin ephemeral records (emitEphemeralToast) are
    // ALWAYS live (a client action just happened), so they bypass the frame-kind
    // replay gate below. Toast each unseen local record immediately.
    for (const r of s.advisories) {
      if (r.origin !== 'local') continue;
      if (this.seenKeys.has(r.key)) continue;
      this.seenKeys.add(r.key);
      this.pushToast(r);
    }
    // Slice 490 follow-up — gate (stream-origin) toast emission on the typed `lastFrameKind`
    // discriminator, not the seenKeys-empty heuristic. Three regimes:
    //   1. lastFrameKind === 'initial' (no frame yet): seed nothing. No toast.
    //   2. lastFrameKind === 'snapshot' (we just received a LIFECYCLE snapshot):
    //      seed seenKeys silently — historical advisories are not toast-worthy.
    //      Flip hasSeenFirstSnapshot so subsequent snapshots can re-seed.
    //   3. lastFrameKind === 'update' (a new advisory arrived): toast each key
    //      we haven't seen + that isn't already acknowledged.
    // The earlier heuristic mis-classified the FIRST update as a "snapshot seed"
    // whenever the first LIFECYCLE snapshot was empty.
    if (s.lastFrameKind === 'initial') return;
    if (s.lastFrameKind === 'snapshot') {
      for (const r of s.advisories) this.seenKeys.add(r.key);
      this.hasSeenFirstSnapshot = true;
      return;
    }
    // lastFrameKind === 'update'
    if (!this.hasSeenFirstSnapshot) {
      // An UPDATE arrived before any LIFECYCLE snapshot — treat the current
      // advisories as the implicit snapshot baseline. Defensive against
      // reconnect-without-snapshot edge cases.
      for (const r of s.advisories) this.seenKeys.add(r.key);
      this.hasSeenFirstSnapshot = true;
      return;
    }
    for (const r of s.advisories) {
      if (this.seenKeys.has(r.key) || r.acknowledged) continue;
      this.seenKeys.add(r.key);
      this.pushToast(r);
    }
  }

  private pushToast(record: AdvisoryRecord): void {
    // Slice 490 substrate-completion (P2.3) — REQUIRES_ACK toasts persist until
    // clicked (no auto-dismiss); EPHEMERAL + PERSISTED auto-dismiss after
    // TOAST_DURATION_MS. The dispatch is per-event via record.sourceRenderHint
    // — multiple advisory classes with different renderHints coexist cleanly.
    const durationMs = record.toast?.durationMs ?? TOAST_DURATION_MS;
    // Tempdoc 613 §14 — a local ERROR toast is sticky (no auto-dismiss): an error must not silently
    // auto-vanish. Derived from the declared severity, the same way REQUIRES_ACK persists a stream toast.
    const sticky =
      record.origin === 'local' &&
      presentationForSeverity(record.event.severity).sticky;
    const timeoutId =
      record.sourceRenderHint === 'REQUIRES_ACK' || sticky
        ? null
        : setTimeout(() => this.dismiss(record.key), durationMs);
    this.visible = [...this.visible, { record, timeoutId }];
  }

  private dismiss(key: string): void {
    const target = this.visible.find((t) => t.record.key === key);
    // Tempdoc 602 R4 — idempotent: a toast already animating out (e.g. the
    // onSnapshot supersede prune raced a timeout) must not be processed twice.
    if (!target || this.exiting.has(key)) return;
    this.exiting.add(key);
    if (target.timeoutId !== null) clearTimeout(target.timeoutId);
    // 559 Authority III — local-origin records live in the store's ephemeral
    // list; clear them there so they leave the snapshot (they never persist).
    if (target.record.origin === 'local') this.store?.dropEphemeral(key);
    const remove = () => {
      this.visible = this.visible.filter((t) => t.record.key !== key);
      this.exiting.delete(key);
    };
    const el = this.shadowRoot?.querySelector(
      `[data-key="${CSS.escape(key)}"]`,
    ) as HTMLElement | null;
    if (el && typeof el.getAnimations === 'function') {
      el.classList.add('exiting');
      el.addEventListener('animationend', remove, { once: true });
      setTimeout(remove, 250);
    } else {
      remove();
    }
  }

  private handleClick(record: AdvisoryRecord): void {
    // 559 Authority III — local records aren't persisted; dismiss drops them.
    // Stream records acknowledge (reflected in the inbox read-state projection).
    if (record.origin !== 'local' && this.store) this.store.acknowledge(record.key);
    this.dismiss(record.key);
  }

  private async handleAction(e: Event, record: AdvisoryRecord): Promise<void> {
    e.stopPropagation();
    // 559 Authority III — local ephemeral records carry a plain callback action
    // (e.g. nav-toast "Go back"), not an operation invocation.
    if (record.origin === 'local') {
      record.toast?.onAction?.();
      this.dismiss(record.key);
      return;
    }
    const action = record.event.primaryAction;
    if (!action || !this.operationClient) return;
    const btn = e.currentTarget as HTMLButtonElement;
    btn.classList.add('running');
    btn.textContent = 'Running…';
    try {
      const args = action.defaultArgsJson
        ? JSON.parse(action.defaultArgsJson)
        : {};
      if (record.event.primaryActionKind === 'undo' && args.executionId) {
        await this.operationClient.undo(action.target, args.executionId);
      } else {
        await this.operationClient.invoke(action.target, {
          args,
          transport: 'BUTTON',
        });
      }
      btn.classList.remove('running');
      btn.classList.add('success');
      btn.textContent = '✓ Done';
      if (this.store) this.store.acknowledge(record.key);
      setTimeout(() => this.dismiss(record.key), 1000);
    } catch {
      btn.classList.remove('running');
      btn.classList.add('failed');
      btn.textContent = '✕ Failed';
    }
  }

  override render(): TemplateResult | typeof nothing {
    if (this.visible.length === 0) return nothing;
    return html`${this.visible.map((t) => {
      // 559 Authority III — local-origin records render their literal message +
      // severity tone + plain callback action (no advisory class chrome / meta).
      const isLocal = t.record.origin === 'local';
      // Local records carry their own message/tone; don't resolve class chrome
      // for them (avoids a spurious "unknown classId" warning for core.ephemeral).
      const chrome = isLocal
        ? { icon: '', label: '', toneClass: '' }
        : advisoryClassChrome(t.record.event.classId);
      const extras = t.record.event.classExtras ?? {};
      const title = isLocal
        ? (t.record.toast?.message ?? '')
        : t.record.event.classId === 'operation.completed'
          ? `${extras.operationId ?? t.record.event.classId}`
          : chrome.label;
      // 559 notice-presentation — the severity tone is a NoticeTone routed to the
      // shared <jf-system-notice>, not a per-host CSS class.
      // Tempdoc 613 §14 — a LOCAL toast's tone AND announcement politeness (live) are one projection of
      // its declared severity (error/warning announce assertively). Stream records keep their chrome tone
      // and the renderHint-driven politeness.
      const localPresentation = isLocal
        ? presentationForSeverity(t.record.event.severity)
        : null;
      const tone: NoticeTone = localPresentation
        ? localPresentation.tone
        : t.record.event.classId === 'operation.completed'
          ? extras.outcome === 'SUCCESS'
            ? 'success'
            : 'warning'
          : toneClassToNotice(chrome.toneClass);
      const live: NoticeLive = localPresentation
        ? localPresentation.live
        : t.record.sourceRenderHint === 'REQUIRES_ACK'
          ? 'alert'
          : 'status';
      const action = t.record.event.primaryAction;
      const actionLabel = isLocal
        ? t.record.toast?.actionLabel
        : action
          ? t.record.event.primaryActionKind === 'undo'
            ? 'Undo'
            : (action.target.split('.').pop() ?? 'Fix')
          : undefined;
      return html`
          <div
            class="toast"
            data-key=${t.record.key}
            @click=${() => this.handleClick(t.record)}
          >
            <jf-system-notice tone=${tone} live=${live}>
              <div class="title">${isLocal ? nothing : chrome.icon} ${title}</div>
              ${isLocal
                ? nothing
                : html`<div class="meta">
                    ${formatTime(t.record.event.occurredAt)}
                    ${t.record.event.provenance
                      ? html` • <jf-dispatch-source .provenance=${t.record.event.provenance}></jf-dispatch-source>`
                      : nothing}
                  </div>`}
              ${actionLabel
                ? html`
                    <div class="action-row">
                      <button
                        class="action-btn"
                        @click=${(e: Event) => this.handleAction(e, t.record)}
                      >
                        ${actionLabel}
                      </button>
                    </div>
                  `
                : nothing}
            </jf-system-notice>
          </div>
        `;
    })}`;
  }
}

/** Map an advisory-class chrome toneClass ('success'/'failure'/…) to a NoticeTone. */
function toneClassToNotice(tc: string): NoticeTone {
  switch (tc) {
    case 'success':
      return 'success';
    case 'error':
      return 'error';
    case 'failure':
    case 'warning':
      return 'warning';
    default:
      return 'neutral';
  }
}

function formatTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString();
  } catch {
    return iso;
  }
}

if (
  typeof customElements !== 'undefined' &&
  !customElements.get('jf-advisory-toast-host')
) {
  customElements.define('jf-advisory-toast-host', AdvisoryToastHost);
}
