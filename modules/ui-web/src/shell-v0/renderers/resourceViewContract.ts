// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 3a.1.9 §A.4a — Resource-view renderer contract.
 *
 * Parallel to slice 3a.1.5's JSON Forms renderer contract, but for
 * Resource-view renderers (`<jf-table>`, `<jf-status-card>`,
 * `<jf-timeseries-sparkline>`, etc.). Pins the typed prop surface
 * every renderer registered for a Category MUST accept.
 *
 * Why a separate contract: slice 3a.1.5's eight properties
 * (`schema, uischema, path, data, errors, enabled, visible, onChange,
 * userConfig`) describe JSON Forms control / layout renderers — the
 * dispatcher feeds form fragments. Resource-view renderers have a
 * different prop surface: the dispatcher feeds whole-Resource state
 * (the current value / collection / event ring) plus presentation
 * metadata. The contract is also Category-typed: TABULAR renderers
 * accept `(items, primaryKey, schema, rowActions?)`; STATE renderers
 * accept `(value, schema)`; etc.
 *
 * Tester contract: Resource-view renderers do NOT have a tester
 * function (unlike JSON Forms renderers). Dispatch is by
 * `(category, hint?, density?)` lookup in the Resource-view registry
 * (`resourceRegistry.ts`). The contract here pins what reactive
 * properties each registered renderer must declare.
 *
 * Lifecycle: the `<jf-resource-view>` consumer mounts the renderer in
 * its `connectedCallback`, binds props from the strategy's emitted
 * state, observes events, and unmounts in `disconnectedCallback`.
 * Renderers don't open their own subscriptions — the strategy does.
 *
 * Conformance: every default renderer is exercised by a fixture-based
 * conformance test (`resourceViewContract.conformance.test.ts`) that
 * sets the Category-typed prop bundle and verifies the element
 * renders without throwing.
 */

import type { TemplateResult } from 'lit';
import type { JsonSchema } from '@jsonforms/core';
// Allowlisted in eslint.config.js — see 511-followup-B. Resource-view
// renderer contract; reads the wire `Presentation` shape for the
// renderer registry's typed dispatch.
import type { Presentation } from '../../api/types/registry.js';
import type { RendererUserConfig } from './userConfig.js';

/**
 * Common base properties every Resource-view renderer accepts.
 *
 * Renderers don't have to *use* every property; absence is treated
 * as "renderer doesn't surface that signal." But they MUST accept
 * the property without erroring (typed-prop conformance).
 */
export interface ResourceViewBaseProps {
  /**
   * i18n keys + iconography hooks. Renderer reads `presentation.labelKey`
   * for in-renderer header chrome (when applicable; some renderers
   * defer to `<jf-resource-view>`'s outer chrome).
   */
  presentation: Presentation;
  /**
   * Connection state from the strategy: 'idle' (pre-mount),
   * 'connecting' (post-mount, pre-first-frame), 'connected'
   * (first frame received), 'error' (strategy reported failure).
   */
  connectionState: 'idle' | 'connecting' | 'connected' | 'error';
  /**
   * Slice 3a.1.7 thread-through: per-pane / per-user customization
   * overrides. Renderer applies via `applyUserConfigToColumns`-style
   * helpers when applicable.
   */
  userConfig: RendererUserConfig | undefined;
  /**
   * Slice 3a.1.7 thread-through: pane id namespace for userConfig
   * overrides. Empty string when standalone (no override application).
   */
  paneId: string;
}

// ============================================================
// Per-Category typed prop bundles
// ============================================================

/**
 * Renderer for `Category.STATE`. Single value; renderer formats per
 * schema. Default: `<jf-status-card>` (existing).
 */
export interface StateRendererProps<T = unknown> extends ResourceViewBaseProps {
  value: T | null;
  schema: JsonSchema | null;
}

/**
 * Renderer for `Category.EVENT_STREAM`. Bounded ring of events.
 * Default: `<jf-status-card>` placeholder until `<jf-event-stream-list>`
 * ships in Phase 7.
 */
export interface EventStreamRendererProps<T = unknown>
  extends ResourceViewBaseProps {
  events: T[];
  schema: JsonSchema | null;
}

/**
 * Renderer for `Category.HISTORY`. Bounded ring of past entries.
 * Default: TBD; same shape as EVENT_STREAM.
 */
export interface HistoryRendererProps<T = unknown> extends ResourceViewBaseProps {
  entries: T[];
  schema: JsonSchema | null;
  /** Optional retention metadata from the catalog declaration. */
  retention?: { mode: string; capacity?: number | null } | null;
}

/**
 * Per-row action renderer. Returns a Lit `TemplateResult` (or
 * equivalent) for the trailing actions cell of `<jf-table>`. Slice
 * 3a.1.9 §A.7's `<jf-row-actions>` is the canonical implementation;
 * plugins can register alternatives.
 */
export type RowActionsRenderer<T = Record<string, unknown>> = (
  row: T,
  primaryKey: string,
) => TemplateResult;

/**
 * Renderer for `Category.TABULAR`. Keyed-map state shape; primary key
 * comes from `Resource.primaryKey`. Default: `<jf-table>`.
 */
export interface TabularRendererProps<T extends Record<string, unknown> = Record<string, unknown>>
  extends ResourceViewBaseProps {
  /**
   * Items keyed by `primaryKey`. The renderer iterates and renders
   * rows. The Map preserves insertion order; for arbitrary ordering
   * the renderer applies its own sort.
   */
  items: Map<string, T>;
  schema: JsonSchema | null;
  /** Field name on each row that uniquely identifies it. */
  primaryKey: string;
  /**
   * Optional per-row affordance renderer. Bound by
   * `<jf-resource-view>` when the Resource declares non-empty
   * `itemOperations`. Absent when no affordances are declared.
   */
  rowActions?: RowActionsRenderer<T>;
}

/**
 * Renderer for `Category.TIMESERIES`. Existing
 * `<jf-timeseries-sparkline>` matches this shape (slice 3a.1.4).
 */
export interface TimeseriesRendererProps extends ResourceViewBaseProps {
  /**
   * Latest TimeseriesSnapshot from the strategy (or null pre-fetch).
   * Wire shape lives in `api/generated/wire-types.ts`.
   */
  snapshot: { values?: unknown[] } | null;
  /** Localized display label. */
  label: string;
}

// LogTailRendererProps deleted in slice 448 phase 6 — Category.LOG_TAIL retired
// per CONFLICT-LEDGER C-012 path-b. Operator-trace surfaces are modeled by the
// sibling DiagnosticChannel primitive (slice 448), with renderer props for log
// surfaces co-located with the DiagnosticChannel substrate.

// ============================================================
// Event surface (renderer-emitted CustomEvents)
// ============================================================

/**
 * Renderer event the host (`<jf-resource-view>`) re-emits to the shell.
 * Categories that surface row-level interaction emit `row-click`.
 */
export interface RowClickEventDetail<T = Record<string, unknown>> {
  row: T;
  index: number;
}

/**
 * Forwarded from a child `<jf-action-button>`; the host shell observes
 * to wire toasts / banners on Operation outcomes. The
 * `<jf-resource-view>` consumer re-emits this so the host doesn't need
 * to drill into the renderer's internal slot tree.
 */
export interface ActionInvokeEventDetail {
  operationId: string;
  /** Row context (TABULAR only); absent for collection-level Operations. */
  row?: Record<string, unknown>;
  /** Risk tier from the Operation's policy. */
  risk: 'LOW' | 'MEDIUM' | 'HIGH';
}

// ============================================================
// Conformance helpers (used by the contract test)
// ============================================================

/**
 * Names of the reactive properties each Category's renderer MUST
 * accept. The conformance test instantiates each registered default
 * renderer and verifies these properties are settable without
 * throwing.
 *
 * "Accepts" means assignable; the renderer doesn't have to surface
 * the value visibly. A renderer that reads only a subset of its
 * Category's required props is conformant — it just renders less.
 */
export const REQUIRED_PROPS_BY_CATEGORY: Record<string, readonly string[]> = {
  STATE: [
    'value',
    'schema',
    'presentation',
    'connectionState',
    'userConfig',
    'paneId',
  ],
  EVENT_STREAM: [
    'events',
    'schema',
    'presentation',
    'connectionState',
    'userConfig',
    'paneId',
  ],
  HISTORY: [
    'entries',
    'schema',
    'presentation',
    'connectionState',
    'userConfig',
    'paneId',
  ],
  TABULAR: [
    'items',
    'schema',
    'primaryKey',
    'presentation',
    'connectionState',
    'userConfig',
    'paneId',
  ],
  TIMESERIES: [
    'snapshot',
    'label',
    'presentation',
    'connectionState',
    'userConfig',
    'paneId',
  ],
} as const;
