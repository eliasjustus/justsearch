// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.5 / §13.5 Phase B — VirtualToolDispatcher tests.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { dispatchVirtualToolCall } from './VirtualToolDispatcher.js';
import {
  registerCommand,
  unregisterCommand,
  invokeCommandWithResult,
  __resetForTest as resetCommandRegistry,
} from './CommandRegistry.js';
import {
  decorateCommandForAgent,
  clearAgentDecoration,
  bootVirtualOperationCatalog,
  __resetVirtualOperationCatalogForTest,
} from './VirtualOperationCatalog.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

let unsub: () => void = () => {};

beforeEach(() => {
  resetCommandRegistry();
  __resetVirtualOperationCatalogForTest();
  unsub?.();
  unsub = bootVirtualOperationCatalog();
});

describe('invokeCommandWithResult — awaitable command invocation', () => {
  it('returns ok=true with stringified output for a sync handler', async () => {
    registerCommand({
      id: 'sync-cmd',
      label: 'S',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => 'hello',
    });
    const res = await invokeCommandWithResult('sync-cmd');
    expect(res.ok).toBe(true);
    expect(res.output).toBe('hello');
    unregisterCommand('sync-cmd');
  });

  it('returns ok=true with empty string output for void return', async () => {
    registerCommand({
      id: 'void-cmd',
      label: 'V',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    const res = await invokeCommandWithResult('void-cmd');
    expect(res.ok).toBe(true);
    expect(res.output).toBe('');
    unregisterCommand('void-cmd');
  });

  it('returns ok=true for async handler with awaited result', async () => {
    registerCommand({
      id: 'async-cmd',
      label: 'A',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: async () => 'async-output',
    });
    const res = await invokeCommandWithResult('async-cmd');
    expect(res.ok).toBe(true);
    expect(res.output).toBe('async-output');
    unregisterCommand('async-cmd');
  });

  it('returns ok=false on handler throw', async () => {
    registerCommand({
      id: 'throw-cmd',
      label: 'T',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {
        throw new Error('boom');
      },
    });
    const res = await invokeCommandWithResult('throw-cmd');
    expect(res.ok).toBe(false);
    expect(res.error).toBe('boom');
    unregisterCommand('throw-cmd');
  });

  it('returns ok=false when command not registered', async () => {
    const res = await invokeCommandWithResult('not-here');
    expect(res.ok).toBe(false);
    expect(res.error).toContain('not found');
  });
});

describe('VirtualToolDispatcher.dispatchVirtualToolCall', () => {
  it('routes a known wireName to its source command and reports ok', async () => {
    registerCommand({
      id: 'do-thing',
      label: 'Do Thing',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => 'thing-done',
    });
    decorateCommandForAgent('do-thing', { audience: ['USER', 'AGENT'] });
    const fetchSpy = vi.fn().mockResolvedValue(new Response('{}'));
    const res = await dispatchVirtualToolCall(
      { sessionId: 's1', callId: 'c1', wireName: 'vop_do_thing', arguments: '{}' },
      { apiBase: 'http://test', fetchImpl: fetchSpy as unknown as typeof fetch },
    );
    expect(res.ok).toBe(true);
    expect(res.output).toBe('thing-done');
    // POST to /api/chat/agent/tool-result with correct body
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0]!;
    expect(url).toBe('http://test/api/chat/agent/tool-result');
    const body = JSON.parse((init as RequestInit).body as string);
    expect(body).toEqual({
      sessionId: 's1',
      callId: 'c1',
      success: true,
      output: 'thing-done',
      errorDetail: '',
    });
    clearAgentDecoration('do-thing');
    unregisterCommand('do-thing');
  });

  it('POSTs a failure when wireName has no registered command (stale tool)', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response('{}'));
    const res = await dispatchVirtualToolCall(
      { sessionId: 's2', callId: 'c2', wireName: 'vop_unknown', arguments: '{}' },
      { apiBase: 'http://test', fetchImpl: fetchSpy as unknown as typeof fetch },
    );
    expect(res.ok).toBe(false);
    expect(res.error).toContain('not registered');
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const body = JSON.parse((fetchSpy.mock.calls[0]![1] as RequestInit).body as string);
    expect(body.success).toBe(false);
    expect(body.errorDetail).toContain('not registered');
  });

  it('POSTs a failure when the handler throws', async () => {
    registerCommand({
      id: 'fail-cmd',
      label: 'F',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {
        throw new Error('intentional');
      },
    });
    decorateCommandForAgent('fail-cmd', { audience: ['USER', 'AGENT'] });
    const fetchSpy = vi.fn().mockResolvedValue(new Response('{}'));
    const res = await dispatchVirtualToolCall(
      { sessionId: 's3', callId: 'c3', wireName: 'vop_fail_cmd', arguments: '{}' },
      { apiBase: 'http://test', fetchImpl: fetchSpy as unknown as typeof fetch },
    );
    expect(res.ok).toBe(false);
    const body = JSON.parse((fetchSpy.mock.calls[0]![1] as RequestInit).body as string);
    expect(body.errorDetail).toBe('intentional');
    clearAgentDecoration('fail-cmd');
    unregisterCommand('fail-cmd');
  });

  it('swallows fetch network errors silently (agent will time out)', async () => {
    registerCommand({
      id: 'ok-cmd',
      label: 'O',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => 'done',
    });
    decorateCommandForAgent('ok-cmd', { audience: ['USER', 'AGENT'] });
    const fetchSpy = vi.fn().mockRejectedValue(new Error('network down'));
    // Should NOT throw — dispatcher swallows the failed POST.
    const res = await dispatchVirtualToolCall(
      { sessionId: 's4', callId: 'c4', wireName: 'vop_ok_cmd', arguments: '{}' },
      { apiBase: 'http://test', fetchImpl: fetchSpy as unknown as typeof fetch },
    );
    expect(res.ok).toBe(true);
    clearAgentDecoration('ok-cmd');
    unregisterCommand('ok-cmd');
  });
});

describe('§28.W13 — agent-originator threading', () => {
  it('dispatchVirtualToolCall routes Action-shaped invocations through invokeAndApply with originator=agent', async () => {
    const { registerAction, __resetActionsForTest } = await import(
      '../substrates/actions/index.js'
    );
    const {
      recordEffect,
      listJournalByOriginator,
      __resetJournalForTest,
    } = await import('../substrates/effects/index.js');
    __resetActionsForTest();
    __resetJournalForTest();

    // Register an Action under the shell.* id; invokeCommandWithResult
    // resolves 'shell.foo' to 'core.action.shell.foo' via the §28.W10
    // resolver and routes through invokeAndApply with originator='agent'.
    registerAction({
      id: 'core.action.shell.foo',
      title: 'Foo',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });

    // Register the command id for VirtualOperationCatalog to project.
    registerCommand({
      id: 'shell.foo',
      label: 'Foo',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => 'foo-result',
    });
    decorateCommandForAgent('shell.foo', { audience: ['USER', 'AGENT'] });

    const fetchSpy = vi.fn().mockResolvedValue(new Response('ok'));
    await dispatchVirtualToolCall(
      { sessionId: 's', callId: 'c', wireName: 'vop_shell_foo', arguments: '{}' },
      { apiBase: 'http://test', fetchImpl: fetchSpy as unknown as typeof fetch },
    );
    // The Action's noop effect should appear in the journal under
    // originator='agent' because invokeCommandWithResult threaded it
    // through invokeAndApply → applyEffect → recordEffect.
    const agentEntries = listJournalByOriginator('agent');
    expect(agentEntries.length).toBeGreaterThan(0);
    // The effect kind from our Action's handler is 'noop'.
    expect(agentEntries.some((e) => e.effect.kind === 'noop')).toBe(true);

    // Sanity: a direct user-originated recordEffect is NOT in the agent list.
    recordEffect({ kind: 'noop' }, CORE_PROVENANCE);
    const userEntries = listJournalByOriginator('user');
    expect(userEntries.some((e) => e.effect.kind === 'noop')).toBe(true);

    clearAgentDecoration('shell.foo');
    unregisterCommand('shell.foo');
  });

  it('invokeCommandWithResult({originator: "user"}) keeps default user attribution', async () => {
    const { registerAction, __resetActionsForTest } = await import(
      '../substrates/actions/index.js'
    );
    const { listJournalByOriginator, __resetJournalForTest } = await import(
      '../substrates/effects/index.js'
    );
    __resetActionsForTest();
    __resetJournalForTest();

    registerAction({
      id: 'core.action.shell.bar',
      title: 'Bar',
      provenance: CORE_PROVENANCE,
      handler: () => ({ kind: 'noop' as const }),
    });
    registerCommand({
      id: 'shell.bar',
      label: 'Bar',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => 'bar',
    });
    await invokeCommandWithResult('shell.bar'); // default originator=user
    expect(listJournalByOriginator('user').length).toBeGreaterThan(0);
    expect(listJournalByOriginator('agent').length).toBe(0);
    unregisterCommand('shell.bar');
  });
});
