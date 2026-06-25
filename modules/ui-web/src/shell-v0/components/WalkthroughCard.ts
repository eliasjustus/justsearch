// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 521 §16.4 — `<jf-walkthrough-card>` Lit element.
 *
 * Mounted once near the bottom of Shell. Picks the highest-priority
 * non-dismissed, non-complete walkthrough from {@link WalkthroughRegistry}
 * and renders its current step (title, body, step counter, Next +
 * Dismiss buttons). State persists in
 * {@link '../state/UserStateDocument.ts'} so progress survives reloads.
 *
 * Step completion modes:
 *   - `Next` button: marks the current step complete and advances.
 *   - `completionEvent: 'onCommand:<id>'`: when the CommandRegistry fires
 *     that command, the step is marked complete automatically. (V1
 *     event vocabulary; future kinds fold in additively.)
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import './Button.js';

import {
  listWalkthroughs,
  onWalkthroughCatalogChange,
  type WalkthroughContribution,
  type WalkthroughStep,
} from '../commands/WalkthroughRegistry.js';
import { onCommandInvoked } from '../commands/CommandRegistry.js';
import {
  dismissWalkthrough,
  getWalkthroughProgress,
  markWalkthroughStepComplete,
  setWalkthroughActiveStep,
  subscribeWalkthroughProgress,
  onSettingChanged,
  type WalkthroughProgress,
} from '../state/UserStateDocument.js';
import { getSessionPluginRegistry } from '../plugin-api/sessionRegistry.js';

interface ActiveStepView {
  readonly walkthrough: WalkthroughContribution;
  readonly step: WalkthroughStep;
  readonly stepIndex: number;
  readonly totalSteps: number;
}

export class WalkthroughCard extends JfElement {
  static properties = {
    active: { state: true },
  };

  declare active: ActiveStepView | null;

  private unsubs: Array<() => void> = [];
  // Tempdoc 521 §22 Phase C — the active step's onSettingChanged
  // subscription is rebuilt every refresh() to track the current
  // step's key (each step may target a different dotted-path).
  private settingChangedUnsub: (() => void) | null = null;

  constructor() {
    super();
    this.active = null;
  }

  static styles = css`
    :host {
      /* 559 Authority I: placement owned by the OverlayHost bottom-left slot. */
      max-width: 360px;
      display: block;
    }
    :host([hidden]) { display: none; }
    .card {
      background: var(--surface-1);
      color: var(--text-primary);
      border: 1px solid var(--border-subtle);
      border-left: 3px solid var(--accent-tint);
      border-radius: 0.375rem;
      padding: 0.875rem 1rem;
      box-shadow: var(--shadow-float);
      font-size: var(--font-size-sm);
      line-height: 1.45;
    }
    .header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      font-size: var(--font-size-xs);
      color: var(--text-muted);
      letter-spacing: 0.04em;
      text-transform: uppercase;
      margin-bottom: 0.375rem;
    }
    .title {
      font-weight: 600;
      font-size: var(--font-size-md);
      margin: 0 0 0.25rem 0;
      color: var(--text-primary);
    }
    .body { margin-bottom: 0.75rem; white-space: pre-wrap; }
    .actions {
      display: flex;
      justify-content: flex-end;
      gap: 0.5rem;
    }
    /* 574 B (remediation) — dismiss = jf-button (secondary), advance = jf-button variant="primary". */
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubs.push(onWalkthroughCatalogChange(() => this.refresh()));
    this.unsubs.push(subscribeWalkthroughProgress(() => this.refresh()));
    this.unsubs.push(
      onCommandInvoked((commandId) =>
        this.handleCompletionTrigger('onCommand', commandId),
      ),
    );
    // Plugin-installed: any successful install triggers the dispatcher,
    // which advances iff the active step's completionEvent's pluginId
    // matches the installed id.
    this.unsubs.push(
      getSessionPluginRegistry().onInstalled((pluginId) =>
        this.handleCompletionTrigger('extensionInstalled', pluginId),
      ),
    );
    this.refresh();
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    for (const u of this.unsubs) u();
    this.unsubs = [];
    this.settingChangedUnsub?.();
    this.settingChangedUnsub = null;
  }

  /** Tempdoc 521 §22 Phase C — bind / rebind the onSettingChanged
   *  subscription so it targets the active step's dotted-path key
   *  (instead of an upfront allowlist of four V1 keys). Called from
   *  refresh() whenever the active step changes. */
  private updateSettingChangedSubscription(): void {
    this.settingChangedUnsub?.();
    this.settingChangedUnsub = null;
    const event = this.active?.step.completionEvent;
    if (!event || !event.startsWith('onSettingChanged:')) return;
    const key = event.slice('onSettingChanged:'.length);
    this.settingChangedUnsub = onSettingChanged(key, () =>
      this.handleCompletionTrigger('onSettingChanged', key),
    );
  }

  private handleCompletionTrigger(
    kind: 'onCommand' | 'onSettingChanged' | 'extensionInstalled',
    value: string,
  ): void {
    const active = this.active;
    if (!active) return;
    const expected = active.step.completionEvent;
    if (!expected) return;
    const prefix = `${kind}:`;
    if (!expected.startsWith(prefix)) return;
    if (expected.slice(prefix.length) !== value) return;
    this.advance();
  }

  private refresh(): void {
    const walkthroughs = listWalkthroughs();
    for (const w of walkthroughs) {
      const progress = getWalkthroughProgress(w.id);
      if (progress?.dismissed) continue;
      const stepIndex = progress?.activeStepIndex ?? 0;
      if (stepIndex >= w.steps.length) continue; // already complete
      const step = w.steps[stepIndex]!;
      this.active = {
        walkthrough: w,
        step,
        stepIndex,
        totalSteps: w.steps.length,
      };
      this.hidden = false;
      this.updateSettingChangedSubscription();
      return;
    }
    this.active = null;
    this.hidden = true;
    this.updateSettingChangedSubscription();
  }

  private advance(): void {
    const active = this.active;
    if (!active) return;
    markWalkthroughStepComplete(active.walkthrough.id, active.step.id);
    const next = active.stepIndex + 1;
    setWalkthroughActiveStep(active.walkthrough.id, next);
    // subscribeWalkthroughProgress will fire refresh()
  }

  private handleDismiss(): void {
    if (!this.active) return;
    dismissWalkthrough(this.active.walkthrough.id);
  }

  override render(): TemplateResult {
    const active = this.active;
    if (!active) return html``;
    const finalStep = active.stepIndex === active.totalSteps - 1;
    return html`
      <div class="card" role="status" aria-live="polite">
        <div class="header">
          <span>${active.walkthrough.title}</span>
          <span>Step ${active.stepIndex + 1} of ${active.totalSteps}</span>
        </div>
        <h3 class="title">${active.step.title}</h3>
        <div class="body">${active.step.body}</div>
        <div class="actions">
          <jf-button label="Dismiss" .onActivate=${() => this.handleDismiss()}>Dismiss</jf-button>
          <jf-button variant="primary" label=${finalStep ? 'Done' : 'Next'} .onActivate=${() => this.advance()}>
            ${finalStep ? 'Done' : 'Next'}
          </jf-button>
        </div>
      </div>
    `;
  }

  // Visible for testing: shape inspection of the currently-rendered
  // step. The shadow-DOM render path is exercised by the
  // WalkthroughCard.render.test happy-dom suite.
  /** @internal */ getActiveStep(): ActiveStepView | null {
    return this.active;
  }
}

if (typeof customElements !== 'undefined' && !customElements.get('jf-walkthrough-card')) {
  customElements.define('jf-walkthrough-card', WalkthroughCard);
}

/** Convenience helper for callers that want to read raw progress. */
export function readWalkthroughProgress(id: string): WalkthroughProgress | undefined {
  return getWalkthroughProgress(id);
}
