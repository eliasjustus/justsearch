// SPDX-License-Identifier: Apache-2.0
import { css, html, nothing, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import { requestSurfaceNavigation } from '../controllers/navigateRequest.js';
import {
  getAppUpdateStatus,
  subscribeAppUpdate,
  type AppUpdateStatus,
} from '../state/appUpdateState.js';
import './Button.js';

/** Persistent host-chrome notice for actionable application-update states. */
export class AppUpdateBanner extends JfElement {
  static properties = {
    status: { state: true },
  };

  declare status: AppUpdateStatus | null;
  private unsubscribe: (() => void) | null = null;

  constructor() {
    super();
    this.status = getAppUpdateStatus();
  }

  static styles = css`
    :host {
      display: block;
      grid-area: update;
      color: var(--text-primary);
      font-family: system-ui, -apple-system, sans-serif;
    }
    :host([hidden]) {
      display: none;
    }
    .notice {
      min-height: 2.5rem;
      box-sizing: border-box;
      display: flex;
      align-items: center;
      gap: 0.75rem;
      padding: 0.4rem 1rem;
      border-bottom: 1px solid var(--accent-tint-45);
      background: var(--accent-tint-16);
      font-size: var(--font-size-sm);
    }
    .repair {
      border-bottom-color: var(--accent-danger-45);
      background: var(--accent-danger-16);
    }
    .message {
      flex: 1;
    }
  `;

  override connectedCallback(): void {
    super.connectedCallback();
    this.unsubscribe = subscribeAppUpdate((status) => {
      this.status = status;
      this.toggleAttribute(
        'hidden',
        status?.state !== 'available' && status?.state !== 'repair_required',
      );
    });
  }

  override disconnectedCallback(): void {
    super.disconnectedCallback();
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  override render(): TemplateResult {
    const status = this.status;
    if (status?.state !== 'available' && status?.state !== 'repair_required') {
      return html`${nothing}`;
    }
    const repair = status.state === 'repair_required';
    return html`
      <div
        class=${`notice${repair ? ' repair' : ''}`}
        role=${repair ? 'alert' : 'status'}
        data-testid="app-update-banner"
      >
        <span class="message">
          ${repair
            ? `The update to ${status.availableVersion ?? 'the target release'} needs repair.`
            : `JustSearch ${status.availableVersion ?? 'update'} is available.`}
        </span>
        <jf-button
          variant=${repair ? 'secondary' : 'primary'}
          label="Open app update settings"
          .onActivate=${() => requestSurfaceNavigation('core.settings-surface')}
        >
          ${repair ? 'Repair instructions' : 'Review and install'}
        </jf-button>
      </div>
    `;
  }
}

if (!customElements.get('jf-app-update-banner')) {
  customElements.define('jf-app-update-banner', AppUpdateBanner);
}

