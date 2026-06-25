// SPDX-License-Identifier: Apache-2.0
/**
 * SettingsLitView — Lit-side settings surface (slice 3a-2-b).
 *
 * Mounts up to four `<jf-form>` instances corresponding to the panels
 * of the React `SettingsView`:
 *
 *   - Interface — `mode: 'simple' | 'advanced'`
 *   - Appearance — `theme: 'system' | 'dark' | 'light'` + `highContrast`
 *   - Keyboard — `defaultAction: 'open' | 'reveal' | 'preview'`
 *   - Desktop (Tauri-only) — `autostart: boolean` (rendered when the
 *     parent passes a non-undefined `autostart` field on `data`,
 *     signaling that the Tauri runtime is present)
 *
 * Lifecycle:
 *   - Parent (React `SettingsView`) passes the current `UISettings`
 *     via the `data` property + listens for `settings-change` events.
 *   - The `data` property is split across the four forms; each
 *     form's onChange propagates a `settings-change` CustomEvent
 *     with the partial UISettings update.
 *   - The parent dispatches Interface / Appearance / Keyboard updates
 *     via `useSettings.updateUI(...)` (the existing settings store)
 *     and Desktop updates via the Tauri autostart plugin
 *     (`@tauri-apps/plugin-autostart`).
 */

import { html, css, type TemplateResult } from 'lit';
import { JfElement } from '../primitives/JfElement.js';
import type { JsonSchema, UISchemaElement } from '@jsonforms/core';
import type { FormChangeEventDetail } from '../components/Form.js';
import '../components/Form.js';

/**
 * Subset of the React-side `UISettings` shape that this Lit view
 * surfaces. Mirrors `modules/ui-web/src/api/domains/settings.ts`
 * verbatim for the fields covered.
 */
export interface SettingsLitViewData {
  mode?: 'simple' | 'advanced';
  theme?: 'system' | 'dark' | 'light';
  highContrast?: boolean;
  defaultAction?: 'open' | 'reveal' | 'preview';
  /**
   * Tauri autostart toggle. When `undefined`, the Desktop panel is
   * NOT rendered (signal: parent's runtime is non-Tauri or hasn't
   * resolved the initial state yet). When `boolean`, the Desktop
   * panel renders + emits `settings-change` on toggle.
   */
  autostart?: boolean;
}

/**
 * Detail shape of `settings-change` events. `panel` identifies which
 * panel emitted the change (for parent-side debugging / scoped
 * updates). `update` is a partial UISettings patch (or, for the
 * `desktop` panel, the autostart field).
 */
export interface SettingsChangeEventDetail {
  panel: 'interface' | 'appearance' | 'keyboard' | 'desktop';
  update: SettingsLitViewData;
}

const INTERFACE_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    mode: {
      type: 'string',
      enum: ['simple', 'advanced'],
      title: 'Interface mode',
      description: 'Advanced unlocks AI runtime configuration, GPU controls, and library tools.',
    },
  },
};

const INTERFACE_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    {
      type: 'Control',
      scope: '#/properties/mode',
    },
  ],
} as UISchemaElement;

const APPEARANCE_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    theme: {
      type: 'string',
      enum: ['system', 'dark', 'light'],
      title: 'Theme',
    },
    highContrast: {
      type: 'boolean',
      title: 'High Contrast',
      description: 'Better visibility',
    },
  },
};

const APPEARANCE_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    { type: 'Control', scope: '#/properties/theme' },
    { type: 'Control', scope: '#/properties/highContrast' },
  ],
} as UISchemaElement;

const KEYBOARD_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    defaultAction: {
      type: 'string',
      enum: ['open', 'reveal', 'preview'],
      title: 'Enter Action',
      description: 'Default action when pressing Enter',
    },
  },
};

const KEYBOARD_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    {
      type: 'Control',
      scope: '#/properties/defaultAction',
    },
  ],
} as UISchemaElement;

const DESKTOP_SCHEMA: JsonSchema = {
  type: 'object',
  properties: {
    autostart: {
      type: 'boolean',
      title: 'Launch on startup',
      description: 'Start minimized in the system tray',
    },
  },
};

const DESKTOP_UISCHEMA: UISchemaElement = {
  type: 'VerticalLayout',
  elements: [
    {
      type: 'Control',
      scope: '#/properties/autostart',
    },
  ],
} as UISchemaElement;

export class SettingsLitView extends JfElement {
  static override properties = {
    data: { attribute: false },
    enabled: { type: Boolean },
  } as const;

  declare data: SettingsLitViewData;
  declare enabled: boolean;

  constructor() {
    super();
    this.data = {};
    this.enabled = true;
  }

  static styles = css`
    :host {
      display: block;
      color: var(--justsearch-shell-text);
      font: inherit;
    }
    .panel {
      margin-block-end: 1rem;
      padding: var(--justsearch-shell-form-control-spacing, 0.75rem);
      border: 1px solid var(--justsearch-shell-form-input-border, #ccc);
      border-radius: var(--justsearch-shell-form-input-radius, 4px);
      background: var(--justsearch-shell-form-button-bg, transparent);
    }
    .panel-title {
      font-weight: 600;
      margin-block-end: 0.5rem;
    }
  `;

  override render(): TemplateResult {
    return html`
      <section class="panel" aria-label="Interface">
        <div class="panel-title">Interface</div>
        <jf-form
          .schema=${INTERFACE_SCHEMA}
          .uischema=${INTERFACE_UISCHEMA}
          .data=${{ mode: this.data.mode ?? 'simple' }}
          ?enabled=${this.enabled}
          @form-change=${this.handlePanelChange('interface')}
        ></jf-form>
      </section>

      <section class="panel" aria-label="Appearance">
        <div class="panel-title">Appearance</div>
        <jf-form
          .schema=${APPEARANCE_SCHEMA}
          .uischema=${APPEARANCE_UISCHEMA}
          .data=${{
            theme: this.data.theme ?? 'system',
            highContrast: this.data.highContrast ?? false,
          }}
          ?enabled=${this.enabled}
          @form-change=${this.handlePanelChange('appearance')}
        ></jf-form>
      </section>

      <section class="panel" aria-label="Keyboard">
        <div class="panel-title">Keyboard</div>
        <jf-form
          .schema=${KEYBOARD_SCHEMA}
          .uischema=${KEYBOARD_UISCHEMA}
          .data=${{ defaultAction: this.data.defaultAction ?? 'open' }}
          ?enabled=${this.enabled}
          @form-change=${this.handlePanelChange('keyboard')}
        ></jf-form>
      </section>

      ${this.data.autostart === undefined
        ? null
        : html`
            <section class="panel" aria-label="Desktop">
              <div class="panel-title">Desktop</div>
              <jf-form
                .schema=${DESKTOP_SCHEMA}
                .uischema=${DESKTOP_UISCHEMA}
                .data=${{ autostart: this.data.autostart }}
                ?enabled=${this.enabled}
                @form-change=${this.handlePanelChange('desktop')}
              ></jf-form>
            </section>
          `}
    `;
  }

  private readonly handlePanelChange =
    (panel: SettingsChangeEventDetail['panel']) =>
    (e: Event): void => {
      const detail = (e as CustomEvent<FormChangeEventDetail>).detail;
      const data = detail.data as Record<string, unknown>;
      const update: SettingsLitViewData = {};
      // Project the form's data into the partial UISettings shape.
      // Forms are scoped per-panel, so each panel's data covers a
      // disjoint subset of UISettings fields.
      if (panel === 'interface' && typeof data.mode === 'string') {
        update.mode = data.mode as SettingsLitViewData['mode'];
      } else if (panel === 'appearance') {
        if (typeof data.theme === 'string') {
          update.theme = data.theme as SettingsLitViewData['theme'];
        }
        if (typeof data.highContrast === 'boolean') {
          update.highContrast = data.highContrast;
        }
      } else if (panel === 'keyboard' && typeof data.defaultAction === 'string') {
        update.defaultAction = data.defaultAction as SettingsLitViewData['defaultAction'];
      } else if (panel === 'desktop' && typeof data.autostart === 'boolean') {
        update.autostart = data.autostart;
      }
      this.dispatchEvent(
        new CustomEvent<SettingsChangeEventDetail>('settings-change', {
          detail: { panel, update },
          bubbles: true,
          composed: true,
        }),
      );
    };
}

customElements.define('jf-settings-view', SettingsLitView);
