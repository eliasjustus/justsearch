// SPDX-License-Identifier: Apache-2.0
/**
 * Effect — closed discriminated union of kernel after-effects per
 * Tempdoc 543 §3.C + §13.2.2.
 *
 * Shared across the Action substrate (Slice 7 — `applyEffect`
 * dispatcher) and the Effect Journal substrate (Slice 4 — records
 * every dispatched Effect + derived inverses). Extracted to a shared
 * module so neither substrate becomes the source of truth for the
 * union; both import from here.
 *
 * Per §13.2.2: every `applyEffect` writes a typed entry to the
 * journal; consumers (Undo, Macro, Audit, AI preview) project over
 * the log. Open callbacks cannot support this — the closed union
 * structure is what enables the kernel to compute inverses from
 * Effect shape alone.
 *
 * Per §M4 follow-up tightening: union is closed at build time;
 * future Effect kinds (`open-pane`, `close-pane`, and any new ones)
 * extend by additive type widening — TS exhaustiveness in
 * `deriveInverse` catches misses.
 */

export type Effect =
  | { readonly kind: 'noop' }
  | { readonly kind: 'navigate'; readonly to: string }
  | { readonly kind: 'open-pane'; readonly paneId: string }
  | { readonly kind: 'close-pane'; readonly paneId: string }
  | { readonly kind: 'toast'; readonly message: string; readonly severity?: 'info' | 'warning' | 'error' | 'success' }
  | {
      readonly kind: 'invoke-operation';
      readonly operationId: string;
      readonly args?: Readonly<Record<string, unknown>>;
      // §32 #2B + tempdoc 550 C3 — user-consent marker for a typed-confirm
      // (HIGH-risk) operation. Set only on the user-driven accept-after-typed-confirm
      // path (a proposed destructive agent op the user explicitly approved in the
      // queue). When true, the Shell's dispatch uses invokeWithConsent: on the backend's
      // 428 it approves the backend-issued PendingAuthorization by id and re-invokes with
      // the resulting capsule. An agent's own dispatch never sets this, so the backend
      // still gates it. (Replaces the prior pre-minted confirmationToken: consent is now a
      // flag, and the capsule is minted server-side against the backend-stored op+args.)
      readonly consented?: boolean;
    }
  // ──────────────────────────────────────────────────────────────────
  // §21.D Effect union v2 — UI kinds (Tempdoc 543 §22.4 / D4).
  // Adds the 8 categories cataloged in §22.4 across the audited handler
  // pool. Backed by recordEffect + applyEffect dispatchers + inverse
  // derivations (symmetric where applicable).
  // ──────────────────────────────────────────────────────────────────
  /** Set a selection in the active surface (e.g., selected ids). */
  | { readonly kind: 'set-selection'; readonly surfaceId: string; readonly ids: ReadonlyArray<string>; readonly previousIds?: ReadonlyArray<string> }
  /** Clear all selection in the active surface. */
  | { readonly kind: 'clear-selection'; readonly surfaceId: string; readonly previousIds?: ReadonlyArray<string> }
  /** Focus a DOM element by selector or element-ref id. */
  | { readonly kind: 'focus-element'; readonly selector: string }
  /** Scroll to an element by selector. */
  | { readonly kind: 'scroll-to'; readonly selector: string; readonly behavior?: 'auto' | 'smooth'; readonly block?: 'start' | 'center' | 'end' | 'nearest' }
  /** Open a modal/dialog by id; chrome listens via `jf-open-modal`. */
  | { readonly kind: 'open-modal'; readonly modalId: string; readonly payload?: Readonly<Record<string, unknown>> }
  /** Close a modal/dialog by id; chrome listens via `jf-close-modal`. */
  | { readonly kind: 'close-modal'; readonly modalId: string }
  /** Copy a string to the clipboard. Inverse irreversible. Tempdoc 613 §6 — confirmation is a RECEIPT
   *  (caller flashes ReceiptController), not a window toast, so no `successMessage`. */
  | { readonly kind: 'copy-to-clipboard'; readonly text: string }
  /** Imperative form value mutation (used by jf-form binding helpers). */
  | { readonly kind: 'set-form-value'; readonly formId: string; readonly path: string; readonly value: unknown; readonly previousValue?: unknown }
  // ──────────────────────────────────────────────────────────────────
  // 569 §14 — Effect v3: the user-authored PRESENTATION + SEARCH intent kinds.
  // Forward-only UI-state mutations a Move-8 interaction statechart may fire; each
  // routes through the existing host AUTHORITY via a `jf-*` DOM event (the same
  // substrate→DOM contract as open-pane/invoke-operation — the dispatcher imports
  // no app-state). No author-knowable inverse (the declaration is static, so there
  // is no prior value to restore): reversibility is via journal REPLAY, and
  // deriveInverse returns null (cf. focus/scroll/copy). `set-search-*` are the seam
  // tempdoc 570 consumes (search is its mode of the one surface).
  // ──────────────────────────────────────────────────────────────────
  /** Set the app appearance — theme and/or high-contrast (host: applyAppearance). */
  | { readonly kind: 'set-appearance'; readonly theme?: 'light' | 'dark' | 'system'; readonly highContrast?: boolean }
  /** Switch the app UI mode (host: setUiMode). */
  | { readonly kind: 'set-ui-mode'; readonly mode: 'simple' | 'advanced' }
  /** Apply a catalog presentation by id — gated by the conformance floor at apply-time (host: applyPresentation). */
  | { readonly kind: 'apply-presentation'; readonly presentationId: string }
  /** Persist a settings payload to the backend (host: POST /api/settings/v2). */
  | { readonly kind: 'save-settings'; readonly settings: Readonly<Record<string, unknown>> }
  /** Set the search query string — the search domain (tempdoc 570 consumes this seam). */
  | { readonly kind: 'set-search-query'; readonly query: string }
  /** Set the search modified-date filter range (ms epoch; omit a bound to clear it). */
  | { readonly kind: 'set-search-filter'; readonly fromMs?: number; readonly toMs?: number }
  // ──────────────────────────────────────────────────────────────────
  // §32 U2 — undo a backend operation. Dispatched by the audit-log "Undo"
  // affordance for an invoke-operation entry whose executionId was captured
  // (undoSupported ops). The Shell jf-undo-operation listener calls the
  // backend POST /api/undo/{operationId}. Inverse is null (the undo IS the
  // reversal).
  // ──────────────────────────────────────────────────────────────────
  | { readonly kind: 'undo-operation'; readonly operationId: string; readonly executionId: string }
  // ──────────────────────────────────────────────────────────────────
  // §25.β5 / §21.F-FE — DataEffect arm.
  //
  // UI Effects (above) describe a side-effect to the UI. Data Effects
  // (below) describe a returned value from an operation. Both flow
  // through applyEffect + recordEffect so the Journal is the single
  // source of truth; EvaluationContext predicates can branch on the
  // latest data-result by `resultKey` via `getLatestDataResult(key)`.
  //
  // Inverse is null for both (data results can't be "undone" —
  // re-issuing the operation produces a NEW result, not a restoration).
  //
  // The live AI emitter (gated on V1.5.2 backend signing chain) will
  // emit DataEffect-kind events when its tool calls return values.
  // Until then, first-party operations can opt into the Data arm by
  // emitting data-result from their handlers.
  // ──────────────────────────────────────────────────────────────────
  /** Successful data return from an operation. */
  | { readonly kind: 'data-result'; readonly operationId: string; readonly resultKey: string; readonly result: unknown }
  /** Error return from an operation. */
  | { readonly kind: 'data-error'; readonly operationId: string; readonly resultKey: string; readonly error: string };

export type EffectKind = Effect['kind'];

/**
 * A function that renders/dispatches a single {@link Effect}. Defined here on the leaf (next to
 * {@link Effect}) so the interaction barrel and `gatedDispatch` both import it directly — 569 had it
 * on the `interaction/index.ts` barrel, which `gatedDispatch` then imported back, forming a cycle.
 */
export type EffectDispatcher = (effect: Effect) => unknown;
