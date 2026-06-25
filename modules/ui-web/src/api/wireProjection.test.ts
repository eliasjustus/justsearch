/**
 * Tests for wireProjection.ts — bigint→number coercion + projection +
 * validate-then-project at the FE consumer boundary.
 */
import { describe, it, expect } from 'vitest';
import { create } from '@bufbuild/protobuf';
import {
  bigintToNumber,
  validateAndProject,
  projectWireMessage,
} from './wireProjection';
import {
  TimeseriesSnapshotSchema,
  MetricRefSchema,
  RenderHint,
} from './generated/metrics_pb';

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

describe('projectWireMessage', () => {
  it('projects a real protobuf-es TimeseriesSnapshot to a number-typed shape', () => {
    const message = create(TimeseriesSnapshotSchema, {
      resourceId: 'core.embedding-throughput',
      windowMs: 1_800_000n,
      sampleIntervalMs: 30_000n,
      unit: 'docs/s',
      values: [1.0, 2.5, 3.7],
      startedAt: '2026-05-06T12:00:00Z',
      endedAt: '2026-05-06T12:30:00Z',
      catalogVersion: 42n,
    });

    const projected = projectWireMessage(message);

    expect(projected.windowMs).toBe(1_800_000);
    expect(typeof projected.windowMs).toBe('number');
    expect(projected.sampleIntervalMs).toBe(30_000);
    expect(projected.catalogVersion).toBe(42);
    expect(projected.values).toEqual([1.0, 2.5, 3.7]);
    expect(projected.unit).toBe('docs/s');
    expect((projected as Record<string, unknown>).$typeName).toBeUndefined();
  });

  it('handles MetricRef with optional fields', () => {
    const withLabel = create(MetricRefSchema, {
      resourceId: 'core.queue-depth',
      label: 'Queue Depth',
      hint: RenderHint.SPARK,
    });
    const projected = projectWireMessage(withLabel);
    expect(projected.resourceId).toBe('core.queue-depth');
    expect(projected.label).toBe('Queue Depth');
    expect(projected.hint).toBe(RenderHint.SPARK);

    const withoutLabel = create(MetricRefSchema, {
      resourceId: 'core.queue-depth',
    });
    const projected2 = projectWireMessage(withoutLabel);
    expect(projected2.resourceId).toBe('core.queue-depth');
    expect(projected2.label).toBeUndefined();
  });
});

describe('validateAndProject', () => {
  it('returns valid + projected value when constraints pass', () => {
    const message = create(TimeseriesSnapshotSchema, {
      resourceId: 'core.test',
      windowMs: 1_000n,
      sampleIntervalMs: 100n,
      unit: 'count',
      values: [1, 2, 3],
      startedAt: '2026-05-06T00:00:00Z',
      endedAt: '2026-05-06T00:01:00Z',
      catalogVersion: 1n,
    });

    const result = validateAndProject(TimeseriesSnapshotSchema, message);
    expect(result.kind).toBe('valid');
    if (result.kind === 'valid') {
      expect(result.value.windowMs).toBe(1_000);
      expect(typeof result.value.windowMs).toBe('number');
      expect(result.value.resourceId).toBe('core.test');
    }
  });

  it('returns invalid + violations when constraints fail', () => {
    // sample_interval_ms has constraint int64.gt = 0; zero violates it.
    const message = create(TimeseriesSnapshotSchema, {
      resourceId: 'core.test',
      windowMs: 1_000n,
      sampleIntervalMs: 0n,
      unit: 'count',
      values: [],
      startedAt: '2026-05-06T00:00:00Z',
      endedAt: '2026-05-06T00:01:00Z',
      catalogVersion: 1n,
    });

    const result = validateAndProject(TimeseriesSnapshotSchema, message);
    expect(result.kind).toBe('invalid');
    if (result.kind === 'invalid') {
      expect(result.violations.length).toBeGreaterThan(0);
    }
  });

  it('returns invalid when string min_len constraint violated', () => {
    // started_at has string.min_len = 1; empty string violates it.
    const message = create(TimeseriesSnapshotSchema, {
      resourceId: 'core.test',
      windowMs: 1_000n,
      sampleIntervalMs: 100n,
      unit: 'count',
      values: [],
      startedAt: '',
      endedAt: '2026-05-06T00:01:00Z',
      catalogVersion: 1n,
    });

    const result = validateAndProject(TimeseriesSnapshotSchema, message);
    expect(result.kind).toBe('invalid');
  });
});
