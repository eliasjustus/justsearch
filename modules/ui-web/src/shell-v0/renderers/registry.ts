// SPDX-License-Identifier: Apache-2.0
/**
 * Renderer registry barrel for the Shell V0 Lit JSON Forms binding.
 *
 * Per slice 3a.0: the JSON Forms renderer registry is a list of
 * `(tester, tag)` pairs. The tester returns a numeric rank for a given
 * `(uischema, schema)`; the highest-ranked renderer wins.
 *
 * Cycle structure (tempdoc 530 UI-cycle gate): the renderer types + rank
 * constants live in the leaf `rendererTypes.ts`; `dispatchRenderer`, the
 * registry store, and `registerRenderer` live in the leaf `dispatch.ts`.
 * Each control/layout module SELF-REGISTERS its `(tester, tag)` into the
 * dispatch store at load (next to its `customElements.define`), mirroring
 * the sibling `registerXUiRenderer` / `resourceRegistryDefaults` patterns.
 * No control/layout imports THIS aggregator — they import the two leaves
 * only — so the registry→control→registry cycle is gone.
 *
 * This module's only job is to be the convenience barrel: importing it
 * runs every default renderer's registration side effect (the side-effect
 * imports below) and re-exports the leaf API. `rendererRegistry` is the
 * live store view, populated by those side effects — a single source of
 * truth (no separate hand-maintained list).
 */

import { getRendererRegistry } from './dispatch.js';

// Re-export the leaf types + ranks + dispatch helpers so existing
// importers of `./registry.js` keep working unchanged.
export type {
  RendererRank,
  RendererTester,
  RendererEntry,
} from './rendererTypes.js';
export {
  RANK_BASIC_CONTROL,
  RANK_LAYOUT,
  RANK_SPECIALIZED_CONTROL,
  RANK_STRUCTURAL_CONTROL,
} from './rendererTypes.js';
export { dispatchRenderer, getRendererRegistry, registerRenderer } from './dispatch.js';

// Side-effect imports: each module registers its `(tester, tag)` into the
// dispatch store at load (see each file's `registerRenderer(...)` call next
// to its `customElements.define`). Importing this barrel therefore
// guarantees the full default renderer set is registered. The
// x-ui-renderer dispatcher wins on rank (100) regardless of import order;
// other renderers resolve by rank, ties broken by registration order.
import './controls/TextControl.js';
import './controls/NumberControl.js';
import './controls/BooleanControl.js';
import './controls/EnumControl.js';
import './controls/DateControl.js';
import './controls/TimeControl.js';
import './controls/ObjectControl.js';
import './controls/ArrayControl.js';
// Tempdoc 543 §13.3.1 — Form primitive's x-ui-renderer dispatcher (rank 100).
import './controls/XUiRendererControl.js';
import './layouts/VerticalLayout.js';
import './layouts/HorizontalLayout.js';
import './layouts/GroupLayout.js';
import './layouts/CategorizationLayout.js';
// Side-effect: first-party `corpus-picker` x-ui-renderer hint. Registers
// into the x-ui-renderer hint registry (not the main renderer registry).
import './controls/CorpusPickerRenderer.js';
// Tempdoc 543 §20.7 B1 — SettingsSurface's "default action" enum renderer.
import './controls/EnterActionPickerRenderer.js';
// 569 Fix 1 — bespoke-quality renderers so a DECLARED settings body matches the hand-authored
// look (enum → .option-btn grid; boolean → .switch), enabling the declaration as the DEFAULT.
import './controls/OptionButtonGroupRenderer.js';
import './controls/ToggleSwitchRenderer.js';
// 569 Fix A — bespoke-quality renderers for the §9 results-list + agent surface KINDS.
import './controls/SearchResultsRenderer.js';
import './controls/SourceChipsRenderer.js';
// 569 §18.C #2 / Phase 0 — the four bespoke SURFACE renderers (folder-card / shortcuts-table /
// list-items / metric-card) are NOT eagerly imported here: they are LAZY-loaded on first dispatch
// via `controls/lazyHintLoaders.ts` (`ensureXUiRenderer`), so their bytes leave the eager `app_main`
// bundle and load only when the user visits the surface that declares them (Library / Help / Health).
// Their source `registerXUiRenderer(...)` calls are unchanged, so the `check-declared-surfaces` gate
// (which scans source) still sees one renderer per hint.

/**
 * The full default renderer registry — the live store populated by the
 * self-registration side effects of the imports above. The 13 defaults:
 * 9 controls (incl. the x-ui-renderer dispatcher) + 4 layouts. (The
 * corpus / enter-action renderers register into the x-ui-renderer hint
 * registry, not here.) Captured after the imports above have run, so it
 * holds the full set.
 */
export const rendererRegistry = getRendererRegistry();
