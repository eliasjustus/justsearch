/**
 * Slice 490 §4.D — Tests for the AdvisoryStore reducer + read-state
 * join.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import {
  AdvisoryStore,
  advisoryKey,
  advisoryReducer,
  type AdvisoryEvent,
} from './AdvisoryStore.js';
import type { SseEnvelope } from '../../streaming/envelope-types.js';
import { MultiplexedStream } from '../../streaming/MultiplexedStream.js';
import {
  __resetUserStateForTest,
  getDocument,
} from '../../state/UserStateDocument.js';
import {
  __resetForTest as __resetResourceCatalogForTest,
  __seedForTest as __seedResourceCatalogForTest,
} from '../../../api/registry/ResourceCatalogClient.js';
import {
  KIND_ADVISORY,
  type RenderHint,
  type Resource,
  type ResourceCatalog,
} from '../../../api/types/registry.js';

/**
 * Slice 490 substrate-completion (P2.4) — helper to build a minimal advisory
 * Resource entry for catalog seeding. Returns the wire shape (the FE Resource
 * TS type as it would arrive from /api/registry/resources).
 */
function advisoryResource(
  id: string,
  endpoint: string,
  renderHint: RenderHint = 'PERSISTED',
): Resource {
  return {
    id,
    presentation: {
      labelKey: 'test.advisory.label',
      descriptionKey: 'test.advisory.description',
      iconHint: null,
      category: null,
    },
    schema: 'https://ssot.justsearch/v1/schemas/test.json',
    category: 'EVENT_STREAM',
    subscriptionMode: 'SSE_STREAM',
    endpoint,
    kind: KIND_ADVISORY,
    history: null,
    recovery: null,
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    privacy: { pathPolicy: 'NO_PATHS', loopbackOnly: true, resolver: null },
    itemOperations: [],
    collectionOperations: [],
    primaryKey: '',
    emissionPolicy: { renderHint, dedupeWindow: null },
    audience: 'USER',
    consumers: [],
    role: 'PRODUCT',
  };
}

/** Seed the Resource Catalog with one or more advisory Resources. */
function seedCatalog(...entries: Resource[]): void {
  const catalog: ResourceCatalog = {
    schemaVersion: '1.0',
    catalogVersion: 0,
    namespace: 'core',
    primitive: 'Resource',
    entries,
  };
  __seedResourceCatalogForTest(catalog);
}

const SAMPLE_EVENT: AdvisoryEvent = {
  classId: 'operation.completed',
  id: 'operation.completed:core.ping-backend:SUCCESS',
  occurredAt: '2026-05-12T10:00:00Z',
  renderHint: 'PERSISTED',
  diagnosticsLink: null,
  provenance: {
    transport: 'BUTTON',
    executor: 'UI',
    initiator: null,
    occurredAt: '2026-05-12T10:00:00Z',
  },
  primaryAction: null,
  bodyI18nKey: 'advisory.operation-completed.success',
  classExtras: { operationId: 'core.ping-backend', outcome: 'SUCCESS' },
};

function makeEnvelope(
  frameKind: 'LIFECYCLE' | 'UPDATE',
  payload: unknown,
  seq: number = 1,
): SseEnvelope {
  return {
    streamId: 'surface:advisory-operation-completed',
    frameKind,
    seq,
    ts: '2026-05-12T10:00:00Z',
    payload,
    resumeToken: `tok-${seq}`,
  };
}

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  readyState: number = 0;
  withCredentials = false;
  onopen: ((ev: Event) => unknown) | null = null;
  onmessage: ((ev: MessageEvent) => unknown) | null = null;
  onerror: ((ev: Event) => unknown) | null = null;
  readonly CONNECTING = 0;
  readonly OPEN = 1;
  readonly CLOSED = 2;
  private listeners = new Map<string, Set<(e: Event) => void>>();

  constructor(url: string) {
    this.url = url;
    MockEventSource.instances.push(this);
  }
  addEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    let set = this.listeners.get(type);
    if (!set) {
      set = new Set();
      this.listeners.set(type, set);
    }
    set.add(listener as (e: Event) => void);
  }
  removeEventListener(type: string, listener: EventListenerOrEventListenerObject): void {
    this.listeners.get(type)?.delete(listener as (e: Event) => void);
  }
  dispatchEvent(_e: Event): boolean {
    return true;
  }
  fireFrame(envelope: SseEnvelope): void {
    const e = new MessageEvent('frame', { data: JSON.stringify(envelope) });
    for (const l of this.listeners.get('frame') ?? []) l(e);
  }
  fireOpen(): void {
    this.readyState = this.OPEN;
    for (const l of this.listeners.get('open') ?? []) l(new Event('open'));
  }
  close(): void {
    this.readyState = this.CLOSED;
    this.listeners.clear();
  }
}

describe('advisoryReducer', () => {
  it('LIFECYCLE with snapshot replaces the advisory list and stamps lastFrameKind', () => {
    const initial = { advisories: [], lastFrameKind: 'initial' as const };
    const env = makeEnvelope('LIFECYCLE', {
      kind: 'snapshot',
      advisories: [SAMPLE_EVENT],
    });
    const next = advisoryReducer(initial, env);
    expect(next.advisories).toEqual([SAMPLE_EVENT]);
    expect(next.lastFrameKind).toBe('snapshot');
  });

  it('LIFECYCLE with connected preserves the list', () => {
    const initial = { advisories: [SAMPLE_EVENT], lastFrameKind: 'snapshot' as const };
    const env = makeEnvelope('LIFECYCLE', { kind: 'connected' });
    const next = advisoryReducer(initial, env);
    expect(next).toBe(initial);
  });

  it('UPDATE appends a new advisory event and stamps lastFrameKind = update', () => {
    const initial = { advisories: [SAMPLE_EVENT], lastFrameKind: 'snapshot' as const };
    const second: AdvisoryEvent = {
      ...SAMPLE_EVENT,
      id: 'operation.completed:core.ping-backend:FAILURE',
      occurredAt: '2026-05-12T10:01:00Z',
    };
    const env = makeEnvelope('UPDATE', second, 2);
    const next = advisoryReducer(initial, env);
    expect(next.advisories).toEqual([SAMPLE_EVENT, second]);
    expect(next.lastFrameKind).toBe('update');
  });

  it('UPDATE with malformed payload preserves the state', () => {
    const initial = { advisories: [SAMPLE_EVENT], lastFrameKind: 'snapshot' as const };
    const env = makeEnvelope('UPDATE', { not: 'an event' }, 2);
    const next = advisoryReducer(initial, env);
    expect(next).toBe(initial);
  });
});

describe('advisoryKey', () => {
  it('returns the wire-stamped id', () => {
    expect(advisoryKey(SAMPLE_EVENT)).toBe(
      'operation.completed:core.ping-backend:SUCCESS',
    );
  });
});

describe('AdvisoryStore', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    __resetResourceCatalogForTest();
    MockEventSource.instances = [];
    // Make our MockEventSource the default factory globally — most tests
    // instantiate AdvisoryStore directly with an explicit factory, but
    // setting the global ensures createAdvisoryStore-driven paths in
    // chrome-level tests would also use the mock.
    (globalThis as unknown as { EventSource: typeof EventSource }).EventSource =
      MockEventSource as unknown as typeof EventSource;
  });

  function makeStore(): AdvisoryStore {
    return new AdvisoryStore({
      eventSourceFactory: (url) =>
        new MockEventSource(url) as unknown as EventSource,
    });
  }

  it('subscribes synchronously with the current snapshot (empty catalog → empty)', () => {
    // Empty catalog — no advisory Resources to discover. Subscriber sees an
    // initial-state snapshot (zero advisories).
    const store = makeStore();
    store.start();
    const snapshots: number[] = [];
    store.subscribe((s) => snapshots.push(s.advisories.length));
    expect(snapshots).toEqual([0]);
  });

  it(
    'P2.2 — discovers advisory Resources from catalog and spawns one stream each',
    () => {
      seedCatalog(
        advisoryResource('core.advisory-operation-completed', '/api/advisory/op-completed/stream'),
      );
      const store = makeStore();
      store.start();
      // One advisory Resource discovered → one MockEventSource constructed.
      expect(MockEventSource.instances.length).toBe(1);
      expect(MockEventSource.instances[0]?.url).toBe('/api/advisory/op-completed/stream');
    },
  );

  it('P2.2 — non-advisory Resources are ignored (no streams spawned)', () => {
    const nonAdvisory: Resource = {
      ...advisoryResource('core.health-events', '/api/health/events/stream'),
      kind: 'health-event-stream',
      emissionPolicy: null,
    };
    seedCatalog(nonAdvisory);
    const store = makeStore();
    store.start();
    expect(MockEventSource.instances.length).toBe(0);
  });

  it('joins streamed events with persisted ack state from UserStateDocument', () => {
    seedCatalog(
      advisoryResource('core.advisory-operation-completed', '/api/advisory/op-completed/stream'),
    );
    const store = makeStore();
    store.start();
    const es = MockEventSource.instances[0];
    expect(es).toBeDefined();

    let lastSnapshot: { unreadCount: number; advisories: { acknowledged: boolean }[] } = {
      unreadCount: 0,
      advisories: [],
    };
    store.subscribe((s) => {
      lastSnapshot = { unreadCount: s.unreadCount, advisories: [...s.advisories] };
    });
    es!.fireFrame(makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [SAMPLE_EVENT] }));
    expect(lastSnapshot.unreadCount).toBe(1);
    expect(lastSnapshot.advisories[0]?.acknowledged).toBe(false);

    store.acknowledge(advisoryKey(SAMPLE_EVENT));
    expect(lastSnapshot.unreadCount).toBe(0);
    expect(lastSnapshot.advisories[0]?.acknowledged).toBe(true);
    expect(getDocument().acknowledgedAdvisories).toContain(advisoryKey(SAMPLE_EVENT));
  });

  it('slice 494 — sourceRenderHint comes from wire record, not Resource', () => {
    seedCatalog(
      advisoryResource('core.advisory-ephemeral', '/api/advisory/ephemeral/stream', 'EPHEMERAL'),
    );
    const store = makeStore();
    store.start();
    const es = MockEventSource.instances[0];
    const ephemeralEvent: AdvisoryEvent = { ...SAMPLE_EVENT, renderHint: 'EPHEMERAL' };
    es!.fireFrame(
      makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [ephemeralEvent] }),
    );
    const snap = store.snapshot();
    expect(snap.advisories[0]?.sourceRenderHint).toBe('EPHEMERAL');
  });

  it('P2.2 — aggregates events from multiple advisory Resources sorted by occurredAt', () => {
    seedCatalog(
      advisoryResource('core.advisory-a', '/api/advisory/a/stream', 'PERSISTED'),
      advisoryResource('core.advisory-b', '/api/advisory/b/stream', 'EPHEMERAL'),
    );
    const store = makeStore();
    store.start();
    expect(MockEventSource.instances.length).toBe(2);
    const esA = MockEventSource.instances[0]!;
    const esB = MockEventSource.instances[1]!;

    const eventA1: AdvisoryEvent = { ...SAMPLE_EVENT, id: 'op:a1', occurredAt: '2026-05-12T10:00:00Z', renderHint: 'PERSISTED' };
    const eventB1: AdvisoryEvent = { ...SAMPLE_EVENT, id: 'op:b1', occurredAt: '2026-05-12T10:00:30Z', renderHint: 'EPHEMERAL' };
    const eventA2: AdvisoryEvent = { ...SAMPLE_EVENT, id: 'op:a2', occurredAt: '2026-05-12T10:01:00Z', renderHint: 'PERSISTED' };

    esA.fireFrame(makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [eventA1, eventA2] }));
    esB.fireFrame(makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [eventB1] }));

    const snap = store.snapshot();
    expect(snap.advisories.map((r) => r.event.id)).toEqual(['op:a1', 'op:b1', 'op:a2']);
    // sourceRenderHint now comes from the wire record itself (slice 494).
    expect(snap.advisories[0]?.sourceRenderHint).toBe('PERSISTED');
    expect(snap.advisories[1]?.sourceRenderHint).toBe('EPHEMERAL');
    expect(snap.advisories[2]?.sourceRenderHint).toBe('PERSISTED');
  });

  it('P2.2 — isConnected = false until ALL streams connected', () => {
    seedCatalog(
      advisoryResource('core.advisory-a', '/api/advisory/a/stream'),
      advisoryResource('core.advisory-b', '/api/advisory/b/stream'),
    );
    const store = makeStore();
    store.start();
    expect(MockEventSource.instances.length).toBe(2);
    expect(store.snapshot().isConnected).toBe(false);

    // Both streams connect; aggregate flips true.
    MockEventSource.instances[0]!.fireOpen();
    expect(store.snapshot().isConnected).toBe(false);
    MockEventSource.instances[1]!.fireOpen();
    expect(store.snapshot().isConnected).toBe(true);
  });

  it('acknowledgeAll marks every current advisory acknowledged', () => {
    seedCatalog(
      advisoryResource('core.advisory-operation-completed', '/api/advisory/op-completed/stream'),
    );
    const store = makeStore();
    store.start();
    const es = MockEventSource.instances[0];

    const second: AdvisoryEvent = {
      ...SAMPLE_EVENT,
      id: 'operation.completed:core.ping-backend:FAILURE',
      occurredAt: '2026-05-12T10:01:00Z',
    };
    es!.fireFrame(
      makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [SAMPLE_EVENT, second] }),
    );
    expect(store.snapshot().unreadCount).toBe(2);

    store.acknowledgeAll();
    expect(store.snapshot().unreadCount).toBe(0);
    expect(getDocument().acknowledgedAdvisories?.length).toBe(2);
  });

  it('acknowledged list is FIFO-capped at ACKNOWLEDGED_ADVISORIES_CAP', async () => {
    seedCatalog(
      advisoryResource('core.advisory-operation-completed', '/api/advisory/op-completed/stream'),
    );
    const { ACKNOWLEDGED_ADVISORIES_CAP } = await import(
      '../../state/UserStateDocument.js'
    );
    const store = makeStore();
    store.start();
    // Acknowledge CAP + 50 unique keys; observe FIFO eviction.
    for (let i = 0; i < ACKNOWLEDGED_ADVISORIES_CAP + 50; i++) {
      store.acknowledge(`core.test-${i}@2026-05-12T10:00:00Z`);
    }
    const acks = getDocument().acknowledgedAdvisories ?? [];
    expect(acks.length).toBe(ACKNOWLEDGED_ADVISORIES_CAP);
    // Oldest entries (0..49) should be evicted; index 0 is now `core.test-50@...`.
    expect(acks[0]).toBe('core.test-50@2026-05-12T10:00:00Z');
    expect(acks[acks.length - 1]).toBe(
      `core.test-${ACKNOWLEDGED_ADVISORIES_CAP + 49}@2026-05-12T10:00:00Z`,
    );
  });

  it('UPDATE-stream event appends and bumps unreadCount', () => {
    seedCatalog(
      advisoryResource('core.advisory-operation-completed', '/api/advisory/op-completed/stream'),
    );
    const store = makeStore();
    store.start();
    const es = MockEventSource.instances[0];

    es!.fireFrame(makeEnvelope('LIFECYCLE', { kind: 'snapshot', advisories: [] }, 1));
    expect(store.snapshot().unreadCount).toBe(0);

    es!.fireFrame(makeEnvelope('UPDATE', SAMPLE_EVENT, 2));
    expect(store.snapshot().unreadCount).toBe(1);
    expect(store.snapshot().advisories[0]?.event.classId).toBe('operation.completed');
  });

  it('P2.2 — stop() unsubscribes from catalog AND stops all streams', () => {
    seedCatalog(
      advisoryResource('core.advisory-a', '/api/advisory/a/stream'),
      advisoryResource('core.advisory-b', '/api/advisory/b/stream'),
    );
    const store = makeStore();
    store.start();
    expect(MockEventSource.instances.length).toBe(2);
    const esA = MockEventSource.instances[0]!;
    const esB = MockEventSource.instances[1]!;
    store.stop();
    // Both EventSources closed (readyState === CLOSED).
    expect(esA.readyState).toBe(esA.CLOSED);
    expect(esB.readyState).toBe(esB.CLOSED);
  });

  // Tempdoc 559 Authority III — the client-originated ephemeral channel folds
  // into the one model.
  it('559 — emitEphemeral injects a local-origin EPHEMERAL record into the snapshot', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    store.emitEphemeral({ message: 'Bookmarked', severity: 'success' });
    expect(latest!.advisories.length).toBe(1);
    const rec = latest!.advisories[0]!;
    expect(rec.origin).toBe('local');
    expect(rec.sourceRenderHint).toBe('EPHEMERAL');
    expect(rec.event.severity).toBe('success');
    expect(rec.toast?.message).toBe('Bookmarked');
  });

  it('559 — local EPHEMERAL records never inflate the unread count', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    store.emitEphemeral({ message: 'Navigated to Search' });
    expect(latest!.advisories.length).toBe(1);
    // EPHEMERAL is toast-only — the rail badge / inbox must not count it.
    expect(latest!.unreadCount).toBe(0);
  });

  it('559 — dropEphemeral removes the local record from the snapshot', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    store.emitEphemeral({ message: 'Copied' });
    const key = latest!.advisories[0]!.key;
    store.dropEphemeral(key);
    expect(latest!.advisories.length).toBe(0);
  });

  // Tempdoc 602 R4 — opt-in single-occupancy per classId.
  it('602 R4 — supersede:true drops the prior same-classId local record', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    store.emitEphemeral({ message: 'Navigated to Search', classId: 'core.navigation' });
    expect(latest!.advisories.length).toBe(1);
    store.emitEphemeral({
      message: 'Navigated to Library',
      classId: 'core.navigation',
      supersede: true,
    });
    // The first nav toast is replaced, not stacked.
    expect(latest!.advisories.length).toBe(1);
    expect(latest!.advisories[0]!.toast?.message).toBe('Navigated to Library');
  });

  it('602 R4 — supersede only collapses the SAME classId, not unrelated default toasts', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    // Two unrelated default-class one-offs, then a superseding nav toast.
    store.emitEphemeral({ message: 'Copied' });
    store.emitEphemeral({ message: 'AI is offline', severity: 'warning' });
    store.emitEphemeral({
      message: 'Navigated to Search',
      classId: 'core.navigation',
      supersede: true,
    });
    // The two core.ephemeral one-offs survive; only same-class would collapse.
    expect(latest!.advisories.length).toBe(3);
  });

  it('602 R4 — supersede without a prior same-classId record is a no-op append', () => {
    const store = makeStore();
    store.start();
    let latest: import('./AdvisoryStore.js').AdvisorySnapshot | null = null;
    store.subscribe((s) => {
      latest = s;
    });
    store.emitEphemeral({
      message: 'Navigated to Search',
      classId: 'core.navigation',
      supersede: true,
    });
    expect(latest!.advisories.length).toBe(1);
    expect(latest!.advisories[0]!.toast?.message).toBe('Navigated to Search');
  });
});

describe('AdvisoryStore — tempdoc 662 multiplexed path', () => {
  beforeEach(() => {
    __resetUserStateForTest();
    __resetResourceCatalogForTest();
    MockEventSource.instances = [];
  });

  function multiplexOn(): { multiplex: MultiplexedStream; source: MockEventSource } {
    let source!: MockEventSource;
    const multiplex = new MultiplexedStream({
      url: 'http://test/api/shell-events/stream',
      eventSourceFactory: (url) => {
        source = new MockEventSource(url);
        return source as unknown as EventSource;
      },
    });
    multiplex.start();
    source.fireOpen(); // realistic EventSource ordering: 'open' precedes any 'frame'
    return { multiplex, source };
  }

  it('a Resource whose endpoint is recognized subscribes on the multiplexer — no EventSource opened', () => {
    seedCatalog(
      advisoryResource(
        'core.advisory-operation-completed',
        '/api/advisory/operation-completed/stream',
      ),
    );
    const { multiplex, source } = multiplexOn();
    const store = new AdvisoryStore({ multiplex });
    store.start();

    // Only the ONE multiplexed connection's EventSource was opened — no per-Resource socket.
    expect(MockEventSource.instances.length).toBe(1);
    expect(MockEventSource.instances[0]).toBe(source);

    let lastSnapshot: { unreadCount: number } = { unreadCount: 0 };
    store.subscribe((s) => {
      lastSnapshot = { unreadCount: s.unreadCount };
    });
    source.fireFrame({
      streamId: 'surface:advisory-operation-completed',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-07-01T00:00:00Z',
      payload: { kind: 'snapshot', advisories: [SAMPLE_EVENT] },
      resumeToken: 'tok-1',
    });
    expect(lastSnapshot.unreadCount).toBe(1);
  });

  it('an unrecognized advisory endpoint falls back to its own direct EnvelopeStream', () => {
    seedCatalog(
      advisoryResource('core.advisory-future-class', '/api/advisory/future-class/stream'),
    );
    const { multiplex } = multiplexOn();
    (globalThis as unknown as { EventSource: typeof EventSource }).EventSource =
      MockEventSource as unknown as typeof EventSource;
    const store = new AdvisoryStore({ multiplex });
    store.start();

    // The multiplexed connection's socket (1) PLUS a direct fallback socket for the
    // unrecognized endpoint (2) — the new advisory class is not silently dropped.
    expect(MockEventSource.instances.length).toBe(2);
    expect(MockEventSource.instances[1]?.url).toBe('/api/advisory/future-class/stream');
  });

  it('two recognized Resources share the one multiplexed connection (no second socket)', () => {
    seedCatalog(
      advisoryResource(
        'core.advisory-operation-completed',
        '/api/advisory/operation-completed/stream',
      ),
      advisoryResource(
        'core.advisory-health-recoverable',
        '/api/advisory/health-recoverable/stream',
      ),
    );
    const { multiplex, source } = multiplexOn();
    const store = new AdvisoryStore({ multiplex });
    store.start();

    expect(MockEventSource.instances.length).toBe(1); // both Resources share the ONE socket

    let lastSnapshot: { advisories: { event: { classId: string } }[] } = { advisories: [] };
    store.subscribe((s) => {
      lastSnapshot = { advisories: s.advisories.map((a) => ({ event: { classId: a.event.classId } })) };
    });
    source.fireFrame({
      streamId: 'surface:advisory-operation-completed',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-07-01T00:00:00Z',
      payload: { kind: 'snapshot', advisories: [SAMPLE_EVENT] },
      resumeToken: 'tok-a-1',
    });
    const healthEvent: AdvisoryEvent = { ...SAMPLE_EVENT, classId: 'health.recoverable', id: 'hr-1' };
    source.fireFrame({
      streamId: 'surface:advisory-health-recoverable',
      frameKind: 'LIFECYCLE',
      seq: 1,
      ts: '2026-07-01T00:00:01Z',
      payload: { kind: 'snapshot', advisories: [healthEvent] },
      resumeToken: 'tok-b-1',
    });
    expect(lastSnapshot.advisories).toHaveLength(2);
    expect(lastSnapshot.advisories.map((a) => a.event.classId)).toEqual(
      expect.arrayContaining(['operation.completed', 'health.recoverable']),
    );
  });
});
