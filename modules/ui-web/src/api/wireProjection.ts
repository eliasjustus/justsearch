// SPDX-License-Identifier: Apache-2.0
/**
 * Substrate-extension follow-up to slice 3a-1-8 §A.11.4b: bigint → number
 * projection at the FE consumer boundary.
 *
 * Why this exists: protobuf-es emits `int64` proto fields as `bigint` in
 * generated TypeScript. Existing consumers (StatusDeck, useSystemStore,
 * deriveHeadlineStatus, TimeseriesSparkline render tests, etc.) expect
 * `number`. Per-consumer bigint adoption would touch 50+ sites + 30+ test
 * fixtures (every literal `0` becoming `0n`, etc.). The wrapper authored
 * here converts at the boundary instead, preserving consumer ergonomics.
 *
 * Precision profile: `Number(bigint)` is lossy above 2^53 - 1
 * (Number.MAX_SAFE_INTEGER ≈ 9.007e15). Production fields verified safe:
 *   - Memory bytes: max 8.5 PB (production servers far below).
 *   - Uptime ms: max ≈ 285_429 years.
 *   - Queue depths / doc counts: max 9 quadrillion.
 *   - Catalog versions / sequence numbers: monotonic int64; safe in
 *     practice for any deployment lifetime.
 *
 * The walker throws `RangeError` when a bigint value falls outside the safe
 * integer range, surfacing precision-loss bugs at runtime rather than
 * silently rounding. Consumers that legitimately need full int64 precision
 * (none today) can use the protobuf-es Message directly via `MessageShape`.
 *
 * Pairs with `wireValidator.ts`: validate first, then project.
 */

import type { DescMessage, Message, MessageShape } from '@bufbuild/protobuf';
import type { Violation } from '@bufbuild/protovalidate';
import { validateWireMessage } from './wireValidator';

/**
 * Type-level transform: maps `bigint` → `number` recursively while
 * preserving non-bigint shape. Skips Uint8Array/Date/RegExp/Function.
 */
export type Projection<T> =
  T extends bigint
    ? number
    : T extends Uint8Array | Date | RegExp | ((...args: never[]) => unknown)
      ? T
      : T extends ReadonlyArray<infer U>
        ? Array<Projection<U>>
        : T extends object
          ? { [K in keyof T]: Projection<T[K]> }
          : T;

/**
 * Walks `value`, replacing `bigint` with `Number(bigint)` and stripping
 * protobuf-es internal `$`-prefixed message metadata (`$typeName`,
 * `$unknown`). Throws `RangeError` if any bigint exceeds the safe integer
 * range — surfaces precision-loss bugs rather than silently rounding.
 */
export function bigintToNumber<T>(value: T): Projection<T> {
  return walk(value) as Projection<T>;
}

const MAX_SAFE_BIGINT = BigInt(Number.MAX_SAFE_INTEGER);
const MIN_SAFE_BIGINT = BigInt(Number.MIN_SAFE_INTEGER);

function walk(value: unknown): unknown {
  if (typeof value === 'bigint') {
    if (value > MAX_SAFE_BIGINT || value < MIN_SAFE_BIGINT) {
      throw new RangeError(
        `bigint ${value} outside JavaScript safe integer range ` +
          `(±${Number.MAX_SAFE_INTEGER}); precision would be lost`,
      );
    }
    return Number(value);
  }
  if (Array.isArray(value)) {
    return value.map(walk);
  }
  if (value instanceof Uint8Array) return value;
  if (value instanceof Date) return value;
  if (value instanceof RegExp) return value;
  if (value === null || typeof value !== 'object') return value;

  const out: Record<string, unknown> = {};
  for (const key of Object.keys(value as Record<string, unknown>)) {
    if (key.startsWith('$')) continue;
    out[key] = walk((value as Record<string, unknown>)[key]);
  }
  return out;
}

/**
 * Validates a protobuf-es message against its protovalidate constraints
 * AND projects it to a number-typed plain shape suitable for FE consumers.
 *
 * Returns one of:
 *   - `{ kind: 'valid', value: Projection<MessageShape<Desc>> }`
 *   - `{ kind: 'invalid', violations: Violation[] }`
 *
 * The projection retains every non-internal field; only `bigint` → `number`
 * coercion and `$`-prefix stripping happen.
 */
export type ProjectionResult<Desc extends DescMessage> =
  | { kind: 'valid'; value: Projection<MessageShape<Desc>> }
  | { kind: 'invalid'; violations: readonly Violation[] };

export function validateAndProject<Desc extends DescMessage>(
  schema: Desc,
  message: MessageShape<Desc>,
): ProjectionResult<Desc> {
  const result = validateWireMessage(schema, message);
  if (result.kind === 'invalid') {
    return { kind: 'invalid', violations: result.violations };
  }
  if (result.kind === 'error') {
    throw result.error;
  }
  return { kind: 'valid', value: bigintToNumber(message) };
}

/**
 * Convenience: project without validation. Use when the caller already
 * validated the message (e.g., trusted internal construction) and only
 * needs the bigint → number coercion.
 */
export function projectWireMessage<Msg extends Message>(
  message: Msg,
): Projection<Msg> {
  return bigintToNumber(message);
}
