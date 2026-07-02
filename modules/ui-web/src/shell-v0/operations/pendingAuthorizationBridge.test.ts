// @vitest-environment happy-dom
// SPDX-License-Identifier: Apache-2.0

/**
 * Tempdoc 655 — verifies the out-of-band (e.g. MCP-originated) pending-authorization bridge:
 * it presents the SAME ceremony a live 428 would, and on approval asks the backend to complete
 * the dispatch itself (since the frontend never held the original arguments to replay).
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const requestAuthorizationMock = vi.fn();
const approveAndExecutePendingMock = vi.fn();

vi.mock('./authorizationBroker.js', () => ({
  requestAuthorization: (...args: unknown[]) => requestAuthorizationMock(...args),
}));

vi.mock('./OperationClient.js', () => ({
  getOperationClient: () => ({
    approveAndExecutePending: (...args: unknown[]) => approveAndExecutePendingMock(...args),
  }),
}));

import { startPendingAuthorizationBridge } from './pendingAuthorizationBridge.js';
import type { SseEnvelope } from '../streaming/envelope-types.js';
import { MultiplexedStream } from '../streaming/MultiplexedStream.js';
import { SHELL_EVENT_STREAM_IDS } from '../streaming/shellEventStreamIds.js';

class FakeEventSource extends EventTarget {
  url: string;
  closed = false;
  readyState = 0;
  constructor(url: string) {
    super();
    this.url = url;
  }
  emitFrame(envelope: SseEnvelope): void {
    this.dispatchEvent(new MessageEvent('frame', { data: JSON.stringify(envelope) }));
  }
  emitOpen(): void {
    this.readyState = 1;
    this.dispatchEvent(new Event('open'));
  }
  close(): void {
    this.closed = true;
  }
}

function multiplexOn(fakeEs: FakeEventSource): MultiplexedStream {
  const mux = new MultiplexedStream({
    url: 'http://test/api/shell-events/stream',
    eventSourceFactory: () => fakeEs as unknown as EventSource,
  });
  mux.start();
  fakeEs.emitOpen();
  return mux;
}

function pendingFrame(
  seq: number,
  pendingId: string,
  overrides: Record<string, unknown> = {},
): SseEnvelope {
  return {
    streamId: SHELL_EVENT_STREAM_IDS.PENDING_AUTHORIZATIONS,
    frameKind: 'UPDATE',
    seq,
    ts: '2026-07-02T10:00:00Z',
    payload: {
      pendingId,
      operationId: 'core.ingest-files',
      argsSummary: '{"paths":["C:/tmp"]}',
      sourceTier: 'UNTRUSTED',
      riskTier: 'MEDIUM',
      gateBehavior: 'TYPED_CONFIRM',
      rationale: 'Confirmation required for operation core.ingest-files',
      ...overrides,
    },
    resumeToken: `rt-${seq}`,
  };
}

// A microtask flush — presentAndExecute is async (awaits requestAuthorization then the client call).
async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe('pendingAuthorizationBridge (tempdoc 655)', () => {
  let fakeEs: FakeEventSource;

  beforeEach(() => {
    fakeEs = new FakeEventSource('http://test/api/shell-events/stream');
    requestAuthorizationMock.mockReset();
    approveAndExecutePendingMock.mockReset();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('presents the ceremony and executes on approval', async () => {
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-001'));
    await flush();

    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    expect(requestAuthorizationMock.mock.calls[0]?.[0]).toMatchObject({
      pendingId: 'pa-001',
      operationId: 'core.ingest-files',
      gateBehavior: 'TYPED_CONFIRM',
    });
    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-001', false);

    stop();
  });

  it('does not call approveAndExecutePending when the human denies', async () => {
    requestAuthorizationMock.mockResolvedValue({ approved: false, allowAlways: false });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-002'));
    await flush();

    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);
    expect(approveAndExecutePendingMock).not.toHaveBeenCalled();

    stop();
  });

  it('passes allowAlways through to the execute call', async () => {
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: true });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-003'));
    await flush();

    expect(approveAndExecutePendingMock).toHaveBeenCalledWith('pa-003', true);

    stop();
  });

  it('dedups a replayed pendingId (e.g. ring-buffer replay on reconnect)', async () => {
    requestAuthorizationMock.mockResolvedValue({ approved: true, allowAlways: false });
    approveAndExecutePendingMock.mockResolvedValue({ executed: true, executeSuccess: true });

    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, 'pa-dup'));
    fakeEs.emitFrame(pendingFrame(2, 'pa-dup'));
    await flush();

    expect(requestAuthorizationMock).toHaveBeenCalledTimes(1);

    stop();
  });

  it('ignores a payload missing pendingId or operationId', async () => {
    const stop = startPendingAuthorizationBridge('http://test', { multiplex: multiplexOn(fakeEs) });
    fakeEs.emitFrame(pendingFrame(1, '', { operationId: undefined }));
    await flush();

    expect(requestAuthorizationMock).not.toHaveBeenCalled();

    stop();
  });

  it('is a no-op with no multiplex supplied', () => {
    const stop = startPendingAuthorizationBridge('http://test', {});
    expect(() => stop()).not.toThrow();
    expect(requestAuthorizationMock).not.toHaveBeenCalled();
  });
});
