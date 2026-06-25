// SPDX-License-Identifier: Apache-2.0
/**
 * describeEffect — human-readable, one-line label for an Effect.
 *
 * Tempdoc 543-fwd idea #4 (undo-label) / #8 (mass-undo-confirm preview) /
 * #12 (macro dry-run diff). These three surfaces all need to name "what an
 * effect does" in plain language. The label is DERIVED from the closed Effect
 * union's own fields — it surfaces data that already exists on the effect, it
 * does not invent or fetch anything.
 *
 * Pure + exhaustive over the union (the `never` default fails the TS build if a
 * new Effect kind is added without a label), so every consumer renders a stable
 * phrase rather than a raw `kind` string.
 */

import type { Effect } from '../effect.js';

/** Quote a short identifier for display; empty → a placeholder. */
function q(value: string): string {
  return value ? `"${value}"` : '(unnamed)';
}

export function describeEffect(effect: Effect): string {
  switch (effect.kind) {
    case 'noop':
      return 'No-op';
    case 'navigate':
      return `Navigate to ${effect.to}`;
    case 'open-pane':
      return `Open pane ${q(effect.paneId)}`;
    case 'close-pane':
      return `Close pane ${q(effect.paneId)}`;
    case 'toast':
      return `Show message: ${effect.message}`;
    case 'invoke-operation':
      return `Run ${effect.operationId}`;
    case 'set-selection':
      return `Select ${effect.ids.length} item${effect.ids.length === 1 ? '' : 's'} in ${effect.surfaceId}`;
    case 'clear-selection':
      return `Clear selection in ${effect.surfaceId}`;
    case 'focus-element':
      return `Focus ${effect.selector}`;
    case 'scroll-to':
      return `Scroll to ${effect.selector}`;
    case 'open-modal':
      return `Open dialog ${q(effect.modalId)}`;
    case 'close-modal':
      return `Close dialog ${q(effect.modalId)}`;
    case 'copy-to-clipboard':
      return 'Copy to clipboard';
    case 'set-form-value':
      return `Set ${effect.path} in form ${q(effect.formId)}`;
    case 'set-appearance':
      return `Set appearance${effect.theme ? ` → ${effect.theme}` : ''}${
        effect.highContrast !== undefined
          ? ` (high-contrast ${effect.highContrast ? 'on' : 'off'})`
          : ''
      }`;
    case 'set-ui-mode':
      return `Set UI mode → ${effect.mode}`;
    case 'apply-presentation':
      return `Apply presentation ${q(effect.presentationId)}`;
    case 'save-settings':
      return 'Save settings';
    case 'set-search-query':
      return `Set search query ${q(effect.query)}`;
    case 'set-search-filter':
      return 'Set search filter';
    case 'undo-operation':
      return `Undo ${effect.operationId}`;
    case 'data-result':
      return `Result from ${effect.operationId}`;
    case 'data-error':
      return `Error from ${effect.operationId}`;
    default: {
      const _exhaustive: never = effect;
      void _exhaustive;
      return 'Unknown effect';
    }
  }
}

/**
 * 543-fwd #12 — the before→after CHANGE an effect would make, for the macro
 * dry-run DIFF (an actionable diff, not just a list). `before` is present only
 * where a prior state exists (symmetric open/close, set-form-value's previous
 * value); otherwise the row shows just the `after` action. Exhaustive over the
 * union (the `never` default fails the build if a kind is added without a row).
 */
export interface EffectChange {
  readonly before?: string;
  readonly after: string;
}

export function describeChange(effect: Effect): EffectChange {
  switch (effect.kind) {
    case 'noop':
      return { after: 'nothing' };
    case 'navigate':
      return { after: `view → ${effect.to}` };
    case 'open-pane':
      return { before: 'closed', after: `pane ${q(effect.paneId)} open` };
    case 'close-pane':
      return { before: 'open', after: `pane ${q(effect.paneId)} closed` };
    case 'open-modal':
      return { before: 'closed', after: `dialog ${q(effect.modalId)} open` };
    case 'close-modal':
      return { before: 'open', after: `dialog ${q(effect.modalId)} closed` };
    case 'toast':
      return { after: `message: ${effect.message}` };
    case 'invoke-operation':
      return { after: `runs ${effect.operationId}` };
    case 'undo-operation':
      return { after: `undoes ${effect.operationId}` };
    case 'set-selection':
      return { after: `${effect.ids.length} selected in ${effect.surfaceId}` };
    case 'clear-selection':
      return { before: 'selection', after: 'nothing selected' };
    case 'focus-element':
      return { after: `focus ${effect.selector}` };
    case 'scroll-to':
      return { after: `scroll to ${effect.selector}` };
    case 'copy-to-clipboard':
      return { after: 'clipboard updated' };
    case 'set-form-value':
      return {
        before: effect.previousValue === undefined ? '(unset)' : String(effect.previousValue),
        after: `${effect.path} = ${String(effect.value)}`,
      };
    case 'set-appearance':
      return {
        after: `appearance${effect.theme ? ` → ${effect.theme}` : ''}${
          effect.highContrast !== undefined ? ` (hc ${effect.highContrast ? 'on' : 'off'})` : ''
        }`,
      };
    case 'set-ui-mode':
      return { after: `UI mode → ${effect.mode}` };
    case 'apply-presentation':
      return { after: `presentation → ${q(effect.presentationId)}` };
    case 'save-settings':
      return { after: 'settings saved' };
    case 'set-search-query':
      return { after: `search query → ${q(effect.query)}` };
    case 'set-search-filter':
      return { after: 'search filter set' };
    case 'data-result':
      return { after: `result from ${effect.operationId}` };
    case 'data-error':
      return { after: `error from ${effect.operationId}` };
    default: {
      const _exhaustive: never = effect;
      void _exhaustive;
      return { after: 'unknown' };
    }
  }
}
