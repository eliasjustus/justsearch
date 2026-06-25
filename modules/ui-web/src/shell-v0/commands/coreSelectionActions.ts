// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 526 §4.3 / §4.5 — initial core SelectionActions registry rows.
 *
 * Each row encodes ONE (operation, applicable-when) pair, projecting into
 * whichever presentation contexts that action makes sense in (floating menu,
 * context menu, palette, keyboard, ask-AI button).
 *
 * Tempdoc 526 §16 retraction: this file originally included rows for
 * core.explain / core.compare operations and ConversationTurn /
 * HealthCondition kinds. Those routed to wrong shapes under misleading labels
 * (Explain → rag-ask, Compare → rag-ask) or had no v1 FE producer. Per
 * substrate-without-consumer-flavors, the rows ship with their concrete
 * shape's first slice.
 */

import {
  registerSelectionAction,
  type SelectionActionContribution,
} from './SelectionActionRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

const CORE_ACTIONS: ReadonlyArray<SelectionActionContribution> = [
  // Text-range × summarize — F2-first.
  {
    id: 'core.selection.summarize-text-range',
    appliesTo: 'selectionKind == text-range && selectionTextLength > 0',
    operation: 'core.summarize',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Summarize', priority: 10 },
      contextMenu: { label: 'Summarize selection', category: 'ai' },
      palette: {
        label: 'AI: Summarize selection',
        keywords: ['summarize', 'selection', 'ai'],
        description: 'Summarize the selected text',
      },
      askAiButton: { label: 'Summarize' },
    },
  },
  // Text-range × ask — F9 "Ask AI" on a selection.
  {
    id: 'core.selection.ask-text-range',
    appliesTo: 'selectionKind == text-range && selectionTextLength > 0',
    operation: 'core.ask',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Ask AI', priority: 20 },
      contextMenu: { label: 'Ask AI about selection', category: 'ai' },
      palette: {
        label: 'AI: Ask about selection',
        keywords: ['ask', 'selection', 'ai'],
        description: 'Ask the AI a question about the selected text',
      },
      askAiButton: { label: 'Ask AI' },
    },
  },
  // Citation × ask — G21 bidirectional doc↔answer.
  {
    id: 'core.selection.ask-citation',
    appliesTo: 'selectionKind == citation',
    operation: 'core.ask',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Ask about citation', priority: 30 },
      contextMenu: { label: 'Ask AI about this citation', category: 'ai' },
      palette: {
        label: 'AI: Ask about cited passage',
        keywords: ['cite', 'citation', 'ask'],
        description: 'Continue the conversation about the cited passage',
      },
    },
  },
  // Item × summarize — BrowseSurface "Summarize" context-menu path.
  {
    id: 'core.selection.summarize-item',
    appliesTo: 'selectionKind == search-hit || selectionKind == browse-node || selectionKind == plugin-item',
    operation: 'core.summarize',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      contextMenu: { label: 'Summarize', category: 'ai' },
      palette: {
        label: 'AI: Summarize item',
        keywords: ['summarize', 'document', 'ai'],
        description: 'Summarize the selected item',
      },
    },
  },
  // Item × ask — BrowseSurface "Ask about" context-menu path.
  {
    id: 'core.selection.ask-item',
    appliesTo: 'selectionKind == search-hit || selectionKind == browse-node || selectionKind == plugin-item',
    operation: 'core.ask',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      contextMenu: { label: 'Ask about', category: 'ai' },
      palette: {
        label: 'AI: Ask about item',
        keywords: ['ask', 'document', 'ai'],
        description: 'Ask the AI a question about the selected item',
      },
    },
  },
  // Result-set × summarize — multi-doc summarize via SearchSurface
  // multi-select (G25). Tempdoc 526 §17 T1B adds the floating-menu
  // presentation so the F9 menu shows above the multi-selected rows.
  {
    id: 'core.selection.summarize-result-set',
    appliesTo: 'selectionKind == result-set',
    operation: 'core.summarize',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Summarize all', priority: 10 },
      contextMenu: { label: 'Summarize all', category: 'ai' },
      palette: {
        label: 'AI: Summarize selected documents',
        keywords: ['summarize', 'docs', 'multi', 'ai'],
        description: 'Summarize all selected documents',
      },
    },
  },
  // Result-set × ask — G25 / multi-doc Q&A.
  {
    id: 'core.selection.ask-result-set',
    appliesTo: 'selectionKind == result-set',
    operation: 'core.ask',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Ask about all', priority: 20 },
      contextMenu: { label: 'Ask AI about these', category: 'ai' },
    },
  },
  // Health-condition × ask — F6 / F21 (tempdoc 526 §17 T1C). Routed via
  // core.ask (since core.explain was retracted in §16 F5); the LLM gets
  // the condition's summary as context and the rag-ask shape generates an
  // explanation. Action label still reads "Explain" for UX clarity.
  {
    id: 'core.selection.explain-health-condition',
    appliesTo: 'selectionKind == health-condition',
    operation: 'core.ask',
    source: 'core',
    provenance: CORE_PROVENANCE,
    presentation: {
      floating: { label: 'Explain this condition', priority: 10 },
      contextMenu: { label: 'Explain this condition', category: 'ai' },
      palette: {
        label: 'AI: Explain this health condition',
        keywords: ['explain', 'health', 'condition'],
        description: 'Ask the AI to explain the selected health condition',
      },
    },
  },
];

let registered = false;
export function registerCoreSelectionActions(): void {
  if (registered) return;
  registered = true;
  for (const a of CORE_ACTIONS) registerSelectionAction(a);
}

export function __resetCoreSelectionActionsRegisteredForTest(): void {
  registered = false;
}
