// SPDX-License-Identifier: Apache-2.0
// @vitest-environment happy-dom

/**
 * Tempdoc 511-followup Track C — Behavioral Pass-8 mirror helper.
 *
 * Replaces the fake exhaustiveness check (Record<keyof T, true>) with
 * a behavioral assertion: mutating each wire field MUST produce a
 * rendered-output diff if the field's declared role is 'visual' or
 * 'gate', and MUST NOT produce a diff if the role is 'routing' or
 * 'elided'.
 *
 * This catches the "fake consumption" failure mode the original
 * Pass-8 mirror couldn't: a strategy that lists every key in its
 * roles record but doesn't actually read most of them fails the
 * behavioral test because mutating those keys produces no diff —
 * the test demands the role and the behavior agree.
 *
 * Usage (in a strategy's test file):
 *
 *   it('Pass-8 mirror — field roles match behavior', () => {
 *     assertBehavioralPass8({
 *       reference: REFERENCE_OPERATION,
 *       roles: OPERATION_BUTTON_ROLES,
 *       strategy: operationButtonStrategy,
 *       ctx: {},
 *       host: { apiBase: '', viewerAudience: 'DEVELOPER' },
 *       mutations: {
 *         id: (op) => ({ ...op, id: 'core.mutated' }),
 *         policy: (op) => ({ ...op, policy: { ...op.policy, confirm: { kind: 'NONE' } }}),
 *         // ... one entry per Operation field
 *       },
 *     });
 *   });
 */

import { render, nothing as litNothing } from 'lit';
import { expect } from 'vitest';
import type {
  AggregateOf,
  WireAggregateKind,
} from './aggregateKinds.js';
import type {
  SurfaceContextKind,
  SurfaceContextOf,
} from './surfaceContextKinds.js';
import type {
  AggregateStrategy,
  StrategyHost,
  StrategyResult,
} from './aggregateRegistry.js';
import { classifiedKeys, type FieldRoles } from './assertExhaustive.js';

export interface BehavioralPass8Spec<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
> {
  /** Fully-populated reference aggregate used as the baseline render. */
  reference: AggregateOf<K>;
  /** Per-field role classification. */
  roles: FieldRoles<AggregateOf<K>>;
  /** Strategy under test. */
  strategy: AggregateStrategy<K, C>;
  /** Context object passed to the strategy. */
  ctx: SurfaceContextOf<C>;
  /**
   * Host. The viewerAudience SHOULD permit the reference's audience
   * (use 'DEVELOPER' to clear all gates) so the baseline renders;
   * the gate test below independently checks gating semantics.
   */
  host: StrategyHost & { viewerAudience?: AggregateOf<K> extends { audience: infer A } ? A : unknown };
  /**
   * One mutation per field in the roles record. Each function takes
   * the reference and returns a mutated copy with exactly that
   * field changed. The mutation MUST produce a value different from
   * the reference's value at that field.
   */
  mutations: {
    [K2 in keyof AggregateOf<K>]: (
      ref: AggregateOf<K>,
    ) => AggregateOf<K>;
  };
}

/**
 * Serialize a strategy result to a comparable string. Renders the
 * template into a temporary DOM element and returns the innerHTML.
 * `nothing`/`null` returns serialize to '<nothing>'.
 */
function serializeOutput(result: StrategyResult): string {
  if (result === null || result === litNothing) return '<nothing>';
  const container = document.createElement('div');
  render(result, container);
  return container.innerHTML;
}

/**
 * Run the behavioral Pass-8 check. Throws via vitest `expect` if any
 * field's declared role disagrees with the observed behavior.
 */
export function assertBehavioralPass8<
  K extends WireAggregateKind,
  C extends SurfaceContextKind,
>(spec: BehavioralPass8Spec<K, C>): void {
  const baseline = serializeOutput(
    spec.strategy(spec.reference, spec.ctx, spec.host),
  );
  for (const key of classifiedKeys(spec.roles)) {
    const role = spec.roles[key];
    const mutate = spec.mutations[key];
    if (!mutate) {
      throw new Error(
        `behavioralPass8: missing mutation for key '${String(key)}'. ` +
          `Every key in the roles record must have a corresponding mutation.`,
      );
    }
    const mutatedAggregate = mutate(spec.reference);
    const mutatedOutput = serializeOutput(
      spec.strategy(mutatedAggregate, spec.ctx, spec.host),
    );
    const changed = baseline !== mutatedOutput;
    if (role === 'visual' || role === 'gate') {
      expect(
        changed,
        `Pass-8 mirror: field '${String(key)}' is classified as '${role}' but ` +
          `mutating it produced no rendered-output diff. The strategy claims ` +
          `consumption that it doesn't deliver. Either change the role to ` +
          `'routing'/'elided' or have the strategy actually read the field.`,
      ).toBe(true);
    } else {
      expect(
        changed,
        `Pass-8 mirror: field '${String(key)}' is classified as '${role}' but ` +
          `mutating it produced a rendered-output diff. The strategy is ` +
          `reading a field it claimed not to consume at this level. Either ` +
          `change the role to 'visual'/'gate' or stop reading the field.`,
      ).toBe(false);
    }
  }
}
