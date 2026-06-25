// SPDX-License-Identifier: Apache-2.0
/**
 * 569 Move 8 — the user-authored interaction statechart (the behavior engine).
 *
 * Behavior is authored the SAME way content is: a declarative composition over a closed host
 * vocabulary the kernel interprets — never executes. The unit is an interaction statechart, the
 * behavior analogue of the Move-3 content engine and the branching generalization of the linear
 * `Macro` (a Macro is a 1-path, guardless statechart):
 *
 *   - STATES — named points (generalizing 550's pending|running|terminal lifecycle + 561 modes).
 *   - TRANSITIONS — a declarative (state, event) -> state' table; "structure is data".
 *   - GUARDS — the already-shipped non-Turing-complete `evaluateBinding` (no new language, no
 *     author logic — read-only, side-effect-free, terminating).
 *   - EFFECTS — NAMED entries in the closed authorable {@link Effect} vocabulary (incl.
 *     invoke-operation via operationId); the kernel renders them through {@link applyEffect}
 *     (journaled — undo/replay/audit for free). The user declares WHICH effect fires, never its body.
 *
 * The breaking forms are UNREPRESENTABLE (Move 2, behavior side): the grammar has NO field for
 * a handler/closure/effect-body/raw code, and an effect must be a known AUTHORABLE kind — so
 * arbitrary-code and privilege-escalating behavior cannot be expressed, only composed. (Effects
 * still pass the same dispatch/trust seam as every other effect — a statechart cannot escalate
 * privilege; it composes the team's actions, it does not author them — Move 5.)
 */
import type { Effect, EffectKind, EffectDispatcher } from '../effect.js';
import { evaluateBinding } from '../../themes/bindingExpr.js';
// 569 Fix B — the default dispatcher routes effects through the 550 trust seam (Autonomy Dial →
// propose/dispatch). User-origin behaves exactly like raw applyEffect; agent-origin is gated.
import { createGatedEffectDispatcher } from './gatedDispatch.js';

/** A single (event -> target) edge with an optional guard and named transition effects. */
export interface Transition {
  /** The event name this edge fires on. */
  readonly on: string;
  /** The next state id; omitted = a self-transition (effects only, no state change). */
  readonly target?: string;
  /** A non-Turing-complete guard expression ({@link evaluateBinding}); absent = always taken. */
  readonly guard?: string;
  /** Named effects fired when this edge is taken — the closed authorable vocabulary only. */
  readonly effects?: readonly Effect[];
}

/** A named state with its outgoing transitions. */
export interface InteractionState {
  readonly id: string;
  readonly transitions?: readonly Transition[];
}

/** A user-authored interaction statechart. */
export interface InteractionStatechart {
  readonly id: string;
  /** The initial state id (must match a state). */
  readonly initial: string;
  readonly states: readonly InteractionState[];
}

/**
 * The AUTHORABLE subset of the closed {@link Effect} union — the "intent" effects a user
 * statechart may fire. The kernel-only RESULT kinds (`data-result`, `data-error`,
 * `set-form-value`, `undo-operation`) are emitted by the kernel/operations, not authored, so a
 * statechart cannot forge a data result or an undo. This subset is the behavior analogue of the
 * authorable-component vocabulary (Move 3).
 */
export const AUTHORABLE_EFFECT_KINDS: ReadonlySet<EffectKind> = new Set<EffectKind>([
  'noop',
  'navigate',
  'open-pane',
  'close-pane',
  'toast',
  'invoke-operation',
  'set-selection',
  'clear-selection',
  'focus-element',
  'scroll-to',
  'open-modal',
  'close-modal',
  'copy-to-clipboard',
  // 569 §14 — Effect v3 presentation + search intent kinds (all author-declarable).
  'set-appearance',
  'set-ui-mode',
  'apply-presentation',
  'save-settings',
  'set-search-query',
  'set-search-filter',
]);

/** The single required string field per authorable effect kind (the primary-field check). */
const EFFECT_PRIMARY_FIELD: Partial<Record<EffectKind, string>> = {
  navigate: 'to',
  'open-pane': 'paneId',
  'close-pane': 'paneId',
  toast: 'message',
  'invoke-operation': 'operationId',
  'set-selection': 'surfaceId',
  'clear-selection': 'surfaceId',
  'focus-element': 'selector',
  'scroll-to': 'selector',
  'open-modal': 'modalId',
  'close-modal': 'modalId',
  'copy-to-clipboard': 'text',
  // 569 §14 — the kinds with a single required string field (set-appearance /
  // save-settings / set-search-filter carry optional/object/number fields, so they
  // have no string primary-field entry — consistent with noop).
  'set-ui-mode': 'mode',
  'apply-presentation': 'presentationId',
  'set-search-query': 'query',
};

function validateEffect(e: unknown, path: string, errors: string[]): void {
  if (e === null || typeof e !== 'object') {
    errors.push(`${path} must be an effect object`);
    return;
  }
  const kind = (e as Record<string, unknown>)['kind'];
  if (typeof kind !== 'string' || !AUTHORABLE_EFFECT_KINDS.has(kind as EffectKind)) {
    errors.push(
      `${path}.kind ${JSON.stringify(kind)} is not an AUTHORABLE effect — behavior composes the ` +
        `closed authorable Effect vocabulary (no handler/closure/code, no kernel-only result kind).`,
    );
    return;
  }
  const required = EFFECT_PRIMARY_FIELD[kind as EffectKind];
  if (required && typeof (e as Record<string, unknown>)[required] !== 'string') {
    errors.push(`${path} (kind ${kind}) must carry a string \`${required}\``);
  }
}

const ID_RE = /^[a-z][a-z0-9.-]*$/i;

export type StatechartValidation =
  | { readonly ok: true; readonly chart: InteractionStatechart }
  | { readonly ok: false; readonly errors: readonly string[] };

function rejectUnknownKeys(
  obj: Record<string, unknown>,
  allowed: readonly string[],
  prefix: string,
  errors: string[],
): void {
  const set = new Set(allowed);
  for (const k of Object.keys(obj)) {
    if (!set.has(k)) {
      errors.push(
        `${prefix}${k} is not an authorable field — the statechart has no slot for it ` +
          `(Move 2: a handler / effect body / raw code is unrepresentable).`,
      );
    }
  }
}

/**
 * Validate a candidate interaction statechart. Accepts unknown input; never throws; accumulates
 * all errors. Enforces structural unrepresentability of the breaking forms (no code field; every
 * effect is a known authorable kind; targets resolve to declared states).
 */
export function validateStatechart(candidate: unknown): StatechartValidation {
  const errors: string[] = [];
  if (candidate === null || typeof candidate !== 'object') {
    return { ok: false, errors: ['statechart is not an object'] };
  }
  const c = candidate as Record<string, unknown>;
  rejectUnknownKeys(c, ['id', 'initial', 'states'], '', errors);

  if (typeof c['id'] !== 'string' || !ID_RE.test(c['id'] as string)) {
    errors.push(`id must be a string matching ${ID_RE}`);
  }
  const states = c['states'];
  if (!Array.isArray(states) || states.length === 0) {
    errors.push('states must be a non-empty array');
    return { ok: false, errors };
  }
  const stateIds = new Set<string>();
  states.forEach((s, i) => {
    if (s === null || typeof s !== 'object') {
      errors.push(`states[${i}] must be an object`);
      return;
    }
    const id = (s as Record<string, unknown>)['id'];
    if (typeof id !== 'string' || id.length === 0) {
      errors.push(`states[${i}].id must be a non-empty string`);
    } else {
      stateIds.add(id);
    }
  });

  if (typeof c['initial'] !== 'string' || !stateIds.has(c['initial'] as string)) {
    errors.push(`initial must name a declared state; got ${JSON.stringify(c['initial'])}`);
  }

  states.forEach((s, i) => {
    if (s === null || typeof s !== 'object') return;
    const st = s as Record<string, unknown>;
    rejectUnknownKeys(st, ['id', 'transitions'], `states[${i}].`, errors);
    const transitions = st['transitions'];
    if (transitions === undefined) return;
    if (!Array.isArray(transitions)) {
      errors.push(`states[${i}].transitions must be an array when present`);
      return;
    }
    transitions.forEach((t, j) => {
      const tp = `states[${i}].transitions[${j}]`;
      if (t === null || typeof t !== 'object') {
        errors.push(`${tp} must be an object`);
        return;
      }
      const tr = t as Record<string, unknown>;
      rejectUnknownKeys(tr, ['on', 'target', 'guard', 'effects'], `${tp}.`, errors);
      if (typeof tr['on'] !== 'string' || (tr['on'] as string).length === 0) {
        errors.push(`${tp}.on must be a non-empty event name`);
      }
      if (tr['target'] !== undefined && !stateIds.has(tr['target'] as string)) {
        errors.push(`${tp}.target ${JSON.stringify(tr['target'])} is not a declared state`);
      }
      if (tr['guard'] !== undefined && typeof tr['guard'] !== 'string') {
        errors.push(`${tp}.guard must be a string expression when present`);
      }
      if (tr['effects'] !== undefined) {
        if (!Array.isArray(tr['effects'])) {
          errors.push(`${tp}.effects must be an array when present`);
        } else {
          tr['effects'].forEach((e, k) => validateEffect(e, `${tp}.effects[${k}]`, errors));
        }
      }
    });
  });

  if (errors.length > 0) return { ok: false, errors };
  return { ok: true, chart: candidate as InteractionStatechart };
}

/** The result of evaluating one event against the current state. */
export interface TransitionResult {
  readonly target: string;
  readonly effects: readonly Effect[];
}

/**
 * Pure evaluator: the first transition out of `currentStateId` whose `on` matches `event` and
 * whose guard (if any) passes against `ctx`. Returns null when no transition matches (a no-op).
 * No side effects — the machine dispatches the returned effects.
 */
export function evaluateTransition(
  chart: InteractionStatechart,
  currentStateId: string,
  event: string,
  ctx: Record<string, unknown> = {},
): TransitionResult | null {
  const state = chart.states.find((s) => s.id === currentStateId);
  if (!state?.transitions) return null;
  for (const t of state.transitions) {
    if (t.on !== event) continue;
    if (t.guard !== undefined && !evaluateBinding(t.guard, ctx)) continue;
    return { target: t.target ?? currentStateId, effects: t.effects ?? [] };
  }
  return null;
}

/** Re-exported from the leaf `effect.ts` (where it now lives) so barrel consumers are unaffected;
 *  the inline definition created the `index.ts ↔ gatedDispatch.ts` cycle (569 CI-2 fix). */
export type { EffectDispatcher };

/**
 * The runtime machine: holds the current state, dispatches each matched transition's named
 * effects through the kernel dispatcher (journaled), and advances the state. The branching
 * generalization of `runMacro` — a Macro is this machine over a single guardless path.
 */
export class InteractionMachine {
  private current: string;
  private readonly listeners = new Set<(state: string) => void>();

  constructor(
    private readonly chart: InteractionStatechart,
    private readonly dispatch: EffectDispatcher = createGatedEffectDispatcher(),
  ) {
    this.current = chart.initial;
  }

  /** The current state id. */
  get state(): string {
    return this.current;
  }

  /** Subscribe to state changes; fires immediately with the current state. */
  subscribe(listener: (state: string) => void): () => void {
    this.listeners.add(listener);
    listener(this.current);
    return () => {
      this.listeners.delete(listener);
    };
  }

  /**
   * Send an event: evaluate the transition, dispatch its named effects (each journaled by the
   * kernel), then advance the state. Returns the effects fired (empty when no transition matched).
   */
  send(event: string, ctx: Record<string, unknown> = {}): readonly Effect[] {
    const result = evaluateTransition(this.chart, this.current, event, ctx);
    if (!result) return [];
    for (const effect of result.effects) this.dispatch(effect);
    if (result.target !== this.current) {
      this.current = result.target;
      for (const l of this.listeners) l(this.current);
    }
    return result.effects;
  }
}

/** Construct a machine for a statechart (initial state = `chart.initial`). */
export function createMachine(
  chart: InteractionStatechart,
  dispatch?: EffectDispatcher,
): InteractionMachine {
  return new InteractionMachine(chart, dispatch);
}
