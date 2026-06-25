// SPDX-License-Identifier: Apache-2.0
/**
 * Phase 4 of slice 3a-1-8: FE-side runtime invariant validator for the
 * wire-Category contract substrate.
 *
 * Pairs with the Java-side `WireContractValidator` in
 * `modules/api-contract-projection-java`. Same protovalidate descriptors,
 * same CEL evaluation, same reject behavior — symmetric across producer
 * and consumer per ADR-09a §"Decision" axis 3 (runtime invariants).
 *
 * V1 usage: opt-in. Consumers that need symmetric runtime enforcement on
 * the FE side (e.g., when validating a wire payload from a third-party
 * producer or rejecting malformed inputs from local construction in tests)
 * call `validateWireMessage(message)`.
 *
 * Existing `api/schemas.ts` Zod-based validators continue to work for V1;
 * incremental migration to protovalidate per the substrate's
 * capability-vs-mandate discipline.
 */

import { createValidator, type Validator } from '@bufbuild/protovalidate';
import type { DescMessage, MessageShape } from '@bufbuild/protobuf';

let validatorInstance: Validator | undefined;

function instance(): Validator {
  if (!validatorInstance) {
    validatorInstance = createValidator();
  }
  return validatorInstance;
}

/**
 * Validates a wire-format message against its protovalidate constraints.
 *
 * @returns the protovalidate result. `kind === 'valid'` indicates all
 *   constraints satisfied. `kind === 'invalid'` exposes `violations`.
 */
export function validateWireMessage<Desc extends DescMessage>(
  schema: Desc,
  message: MessageShape<Desc>,
): ReturnType<Validator['validate']> {
  return instance().validate(schema, message);
}

/**
 * Convenience: throws on validation failure. Use at deserialization
 * boundaries where the wire payload's invariants must be enforced.
 */
export function validateWireMessageOrThrow<Desc extends DescMessage>(
  schema: Desc,
  message: MessageShape<Desc>,
): void {
  const result = instance().validate(schema, message);
  if (result.kind !== 'valid') {
    const reason =
      result.kind === 'invalid'
        ? result.violations.map(v => v.message ?? v.ruleId).join('; ')
        : `unexpected validator state: ${result.kind}`;
    throw new Error(`Wire contract validation failed: ${reason}`);
  }
}
