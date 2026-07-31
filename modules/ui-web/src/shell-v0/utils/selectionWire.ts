// SPDX-License-Identifier: Apache-2.0
/**
 * The one projection from the FE selection store's {@link SelectionItem} to the wire
 * {@link SelectionPayload} the backend's `SelectionContextInjector` decodes as `body.selection`.
 *
 * Extracted from `SelectionActionsMenu` so the chat window's `send()` forwards the user's live
 * selection through the SAME mapping the selection-actions menu already used — a second copy
 * would be free to disagree about, say, which field of a `search-hit` is the document id.
 */

import type { SelectionItem } from '../state/selectionState.js';
import type { SelectionPayload } from '../../api/types/selection.js';

export function selectionItemToWirePayload(
  item: SelectionItem | undefined,
): SelectionPayload | null {
  if (!item) return null;
  switch (item.kind) {
    case 'text-range':
      return {
        kind: 'text-range',
        address: item.address,
        selectionText: item.selectionText,
        hostEntity: item.hostEntity,
      };
    case 'citation':
      return { kind: 'citation', citation: item.citation, promotedFrom: item.promotedFrom };
    case 'result-set':
      return { kind: 'result-set', items: item.items, query: item.query };
    case 'health-condition':
      return {
        kind: 'health-condition',
        conditionId: item.conditionId,
        severity: item.severity,
        summary: item.summary,
      };
    case 'search-hit':
      return {
        kind: 'item',
        itemKind: 'search-hit',
        itemId: item.hitId,
        label: item.title,
      };
    case 'browse-node':
      return {
        kind: 'item',
        itemKind: 'browse-node',
        itemId: item.path,
      };
    case 'plugin-item':
      return {
        kind: 'item',
        itemKind: 'plugin-item',
        itemId: item.itemId,
        label: item.label,
      };
  }
}
