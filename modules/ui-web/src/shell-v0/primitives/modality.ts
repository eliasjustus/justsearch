// SPDX-License-Identifier: Apache-2.0
/**
 * ModalityController — tempdoc 574 Move 4 (the runtime-modality authority).
 *
 * The ONE place modal background-behaviour lives. §16 S9 / P5 found modal hosts each hand-rolled
 * (or omitted) the modality contract: scroll-lock was MISSING everywhere (0 sites), and focus-restore
 * was copy-pasted in 3 of 6 hosts (ElicitHost / EffectAuditLog / MacroDryRun, each commented
 * "residue #5") and absent in the rest. This controller makes the contract one authority every modal
 * host composes, so "a modal that doesn't lock background scroll / doesn't restore focus" is no longer
 * something each host must remember.
 *
 * Contract on {@link enter}: (a) save the currently-focused element; (b) lock background scroll
 * (reference-counted, so stacked modals release the lock only when the last closes). On {@link exit}:
 * release the scroll-lock and restore focus to the saved element. Native `<dialog>.showModal()` already
 * provides the background-`inert` + focus-trap + top-layer for the dialog-based hosts, so this
 * controller deliberately owns only the two facets the platform does NOT: scroll-lock + focus-restore
 * (the residue-#5 fix). Non-dialog hosts that need inert/trap should move to native `<dialog>` (the
 * structural fix that also drops their hand-picked z-index — S4), not re-hand-roll it here.
 */
import type { ReactiveController, ReactiveControllerHost } from 'lit';

let scrollLockCount = 0;
let savedHtmlOverflow: string | null = null;

/** Lock background scroll (reference-counted across stacked modals). */
function acquireScrollLock(): void {
  if (typeof document === 'undefined') return;
  if (scrollLockCount === 0) {
    savedHtmlOverflow = document.documentElement.style.overflow;
    document.documentElement.style.overflow = 'hidden';
  }
  scrollLockCount++;
}

/** Release one scroll-lock reference; restore overflow when the last modal closes. */
function releaseScrollLock(): void {
  if (typeof document === 'undefined' || scrollLockCount === 0) return;
  scrollLockCount--;
  if (scrollLockCount === 0) {
    document.documentElement.style.overflow = savedHtmlOverflow ?? '';
    savedHtmlOverflow = null;
  }
}

export class ModalityController implements ReactiveController {
  private active = false;
  private savedFocus: HTMLElement | null = null;

  constructor(host: ReactiveControllerHost) {
    host.addController(this);
  }

  /** Call when the modal opens: save focus + lock background scroll. Idempotent. */
  enter(): void {
    if (this.active) return;
    this.active = true;
    this.savedFocus =
      typeof document !== 'undefined' ? (document.activeElement as HTMLElement | null) : null;
    acquireScrollLock();
  }

  /** Call when the modal closes: release scroll-lock + restore focus. Idempotent. */
  exit(): void {
    if (!this.active) return;
    this.active = false;
    releaseScrollLock();
    // Restore focus to the element that had it before the modal opened (the residue-#5 fix:
    // native <dialog> only auto-restores for invoker clicks, not property-driven opens).
    const target = this.savedFocus;
    this.savedFocus = null;
    if (target && typeof target.focus === 'function' && target.isConnected) {
      target.focus();
    }
  }

  hostDisconnected(): void {
    // A modal torn down while open must not leak the scroll-lock.
    this.exit();
  }
}
