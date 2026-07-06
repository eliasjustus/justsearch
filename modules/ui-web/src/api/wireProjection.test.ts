/**
 * Tests for wireProjection.ts — bigint→number coercion at the FE consumer boundary.
 *
 * Tempdoc 683 (FE proto teardown): the `projectWireMessage` / `validateAndProject` describes
 * were deleted with the protoc-gen-es outputs (`metrics_pb`) — they existed only to exercise
 * protobuf-es messages + protovalidate descriptors, artifacts of the retired FE proto path.
 * The pure walker behavior (bigint coercion, range guard, recursion, `$`-prefix stripping —
 * including the message-shaped literal case) stays fully pinned below.
 */
import { describe, it, expect } from 'vitest';
import { bigintToNumber } from './wireProjection';

describe('bigintToNumber', () => {
  it('coerces bigint within safe integer range', () => {
    expect(bigintToNumber(0n)).toBe(0);
    expect(bigintToNumber(42n)).toBe(42);
    expect(bigintToNumber(-1n)).toBe(-1);
    expect(bigintToNumber(BigInt(Number.MAX_SAFE_INTEGER))).toBe(
      Number.MAX_SAFE_INTEGER,
    );
    expect(bigintToNumber(BigInt(Number.MIN_SAFE_INTEGER))).toBe(
      Number.MIN_SAFE_INTEGER,
    );
  });

  it('throws RangeError on bigint above safe integer range', () => {
    const beyond = BigInt(Number.MAX_SAFE_INTEGER) + 1n;
    expect(() => bigintToNumber(beyond)).toThrow(RangeError);
    expect(() => bigintToNumber(beyond)).toThrow(/precision would be lost/);
  });

  it('throws RangeError on bigint below safe integer range', () => {
    const beyond = BigInt(Number.MIN_SAFE_INTEGER) - 1n;
    expect(() => bigintToNumber(beyond)).toThrow(RangeError);
  });

  it('preserves non-bigint primitives', () => {
    expect(bigintToNumber('hello')).toBe('hello');
    expect(bigintToNumber(3.14)).toBe(3.14);
    expect(bigintToNumber(true)).toBe(true);
    expect(bigintToNumber(null)).toBe(null);
    expect(bigintToNumber(undefined)).toBe(undefined);
  });

  it('walks arrays recursively', () => {
    expect(bigintToNumber([1n, 2n, 3n])).toEqual([1, 2, 3]);
    expect(bigintToNumber(['a', 1n, true])).toEqual(['a', 1, true]);
  });

  it('walks plain objects recursively', () => {
    const input = { a: 1n, b: 'str', c: { nested: 7n } };
    expect(bigintToNumber(input)).toEqual({
      a: 1,
      b: 'str',
      c: { nested: 7 },
    });
  });

  it('preserves Uint8Array verbatim', () => {
    const bytes = new Uint8Array([1, 2, 3]);
    const out = bigintToNumber(bytes);
    expect(out).toBe(bytes);
  });

  it('strips $-prefixed protobuf-es internal fields', () => {
    const messageLike = {
      $typeName: 'test.Foo',
      $unknown: [{ no: 99 }],
      windowMs: 30_000n,
      unit: 'ms',
    };
    expect(bigintToNumber(messageLike)).toEqual({
      windowMs: 30_000,
      unit: 'ms',
    });
  });
});
