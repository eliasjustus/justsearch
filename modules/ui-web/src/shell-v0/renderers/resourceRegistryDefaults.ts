// SPDX-License-Identifier: Apache-2.0
/**
 * Default Resource-view renderer registrations — slice 3a.1.4 §B.6.
 *
 * Side-effect file: importing this module registers the four shipping
 * default renderers into the Resource-view registry. The public barrel
 * (`shell-v0/index.ts`) imports this file so consumers automatically
 * get the defaults.
 *
 * Coverage at slice 3a.1.4 close:
 *  - STATE → `<jf-status-card>` (existing; slice 3a.1 Phase 4a)
 *  - EVENT_STREAM → `<jf-status-card>` placeholder; the dedicated
 *    `<jf-event-stream-list>` element is extracted from HealthLitView's
 *    inline rendering in slice 3a.1.4 Phase 7. Until then the StatusCard
 *    serves as a usable surface for any EVENT_STREAM Resource that
 *    surfaces single conditions; multi-event renders defer to the
 *    consumer's loop (HealthLitView's existing pattern).
 *  - TABULAR → `<jf-table>` (existing; slice 3a.1 Phase 4b)
 *  - TIMESERIES → `<jf-timeseries-sparkline>` (Phase 4 ships the
 *    custom-element registration; the tag string is reserved here
 *    so dispatch is wired ahead of the renderer pair)
 *
 * NOT registered (no shipping default):
 *  - HISTORY — ships when slice 444c lands.
 *
 * (Slice 448 phase 6 (2026-05-07) retired LOG_TAIL per CONFLICT-LEDGER C-012
 * path-b. Operator-trace surfaces are modeled as the sibling DiagnosticChannel
 * primitive — see slice 448's FE substrate at
 * `modules/ui-web/src/api/registry/DiagnosticChannelCatalogClient.ts` and
 * `modules/ui-web/src/shell-v0/strategies/diagnosticChannelStrategy.ts`.)
 *
 * Consumers distinguish "wrong hint" (dispatch returns null but
 * `isCategorySupported(category)` returns true) from "Category not
 * yet implemented" (dispatch returns null AND
 * `isCategorySupported(category)` returns false).
 */

import { registerResourceRenderer } from './resourceRegistry.js';

// Side-effect imports of every Lit class the defaults register a tag for.
// Per slice 3a.1.4 §B.G.4 (live dev-server validation): registering a tag
// in the registry without importing the class file means dispatch returns
// a tag for an unregistered element. Consumers iterating
// `dispatchResourceRenderer` would see `<jf-timeseries-sparkline>` etc.
// treated as inert HTMLElement. Co-locating the side-effect imports here
// makes the defaults module the single source of truth for which custom
// elements are both registered AND imported. Plugin authors registering
// alternative renderers should follow the same pattern in their
// `register(hostApi)` hook.
import '../components/StatusCard.js';
import '../components/Table.js';
import '../components/TimeseriesPolyline.js';
import '../views/TimeseriesSparkline.js';
// Slice 3a.1.9 §B.B.D Stream-D substitute: side-effect import for
// the bespoke health-events specialty renderer registered below.
import '../views/HealthLitView.js';

// All defaults register at rank 0; plugin-supplied higher ranks override.
// Each call returns an unsubscribe function; we discard them at module load
// time because defaults stay registered for the lifetime of the bundle.
registerResourceRenderer({
  category: 'STATE',
  rank: 0,
  tag: 'jf-status-card',
});

registerResourceRenderer({
  category: 'EVENT_STREAM',
  rank: 0,
  tag: 'jf-status-card',
});

registerResourceRenderer({
  category: 'TABULAR',
  rank: 0,
  tag: 'jf-table',
});

registerResourceRenderer({
  category: 'TIMESERIES',
  rank: 0,
  tag: 'jf-timeseries-sparkline',
});

// Slice 3a.1.9 §A.8 — specialty renderer for the operation-history
// EVENT_STREAM Resource. The default EVENT_STREAM renderer is the
// status-card placeholder; for a structured ledger surface, dispatch
// to <jf-table> when the Resource declares kind="operation-history".
// rank=1 outranks the kind-less default at rank=0.
registerResourceRenderer({
  category: 'EVENT_STREAM',
  hint: 'operation-history',
  rank: 1,
  tag: 'jf-table',
});

// Slice 3a.1.9 §B.B.D Stream-D substitute — specialty renderer for the
// health-events Resource. The HealthEvent stream's snapshot carries
// `{conditions, occurrences}` (two independently-shaped sub-streams in
// one Resource) which doesn't fit the single-events-array EVENT_STREAM
// strategy cleanly. Rather than decompose the backend Resource (out of
// scope; would amend slice 444a), the substrate accommodates by
// dispatching to the existing `<jf-health-view>` Lit element which
// consumes the wire shape directly. Validates the §A.8 #2 path:
// substrate accommodates bespoke renderers via kind-discriminated
// dispatch.
registerResourceRenderer({
  category: 'EVENT_STREAM',
  hint: 'health-event-stream',
  rank: 1,
  tag: 'jf-health-view',
});
