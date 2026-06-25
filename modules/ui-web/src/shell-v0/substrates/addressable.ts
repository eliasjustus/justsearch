// SPDX-License-Identifier: Apache-2.0
/**
 * Addressable — typed "thing the user is acting on" — Tempdoc 543 §3.C.
 *
 * Shared type used by EvaluationContext (Slice 3) for per-row fact
 * projection and by the Action substrate (Slice 7) for `appliesTo`
 * filtering. Extracted into its own module so the two substrates
 * import a single canonical shape rather than cross-substrate.
 *
 * Distinct from Selection (`shell-v0/state/selectionState.ts`):
 * Selection is what the user has explicitly selected; Addressable
 * covers selection PLUS hover / right-click / focused-row targets
 * (a superset per 543 §12.3 #3). SelectionDescriptor wraps a
 * multi-Addressable collection with multi-select metadata.
 *
 * The closed union is intentionally open via TypeScript module
 * augmentation — plugins introducing a new AddressableKind extend
 * the union the same way 511 lets plugin axes extend
 * SurfaceContextKind. Contract: "open via augmentation, closed at
 * any given build."
 */

export type AddressableKind =
  | 'search-result'
  | 'citation'
  | 'document-passage'
  | 'agent-tool-call'
  | 'inspector-row'
  | 'corpus-item'
  | 'plugin'
  | null;

/**
 * `payload` is intentionally `unknown` — per-AddressableKind payloads
 * narrow via the `kind` discriminator. Consumers narrow with a switch
 * on `kind` then cast or use a per-kind projector to extract typed
 * facts.
 */
export interface Addressable {
  readonly kind: AddressableKind;
  readonly id: string;
  readonly payload: unknown;
}
