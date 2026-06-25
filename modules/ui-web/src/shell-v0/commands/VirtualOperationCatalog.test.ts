// @vitest-environment happy-dom

/**
 * Tempdoc 508 §11.5 / §13.5 — VirtualOperationCatalog tests.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import {
  decorateCommandForAgent,
  clearAgentDecoration,
  listVirtualOperations,
  serializeVirtualOperationsForAgent,
  resolveAgentToolCall,
  commandIdToWireName,
  setVirtualOperationPublisher,
  bootVirtualOperationCatalog,
  publishNow,
  __resetVirtualOperationCatalogForTest,
} from './VirtualOperationCatalog.js';
import {
  registerCommand,
  unregisterCommand,
  __resetForTest as __resetCommandRegistry,
} from './CommandRegistry.js';
import { CORE_PROVENANCE } from '../primitives/provenance.js';

let unsub: () => void = () => {};

beforeEach(() => {
  __resetVirtualOperationCatalogForTest();
  // Crude reset for CommandRegistry — re-register would be costly, so
  // we accept the state from prior tests. Commands keyed by id; tests
  // use unique ids per case.
  unsub?.();
  unsub = bootVirtualOperationCatalog();
});

describe('commandIdToWireName', () => {
  it('transliterates dot to underscore', () => {
    expect(commandIdToWireName('core.find-thing')).toBe('vop_core_find_thing');
  });
  it('prefixes vop_ to avoid core-operation collision', () => {
    expect(commandIdToWireName('alpha')).toBe('vop_alpha');
  });
});

describe('Projection from CommandRegistry', () => {
  it('commands without decoration are NOT projected', () => {
    registerCommand({
      id: 'undecorated',
      label: 'Undecorated',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    expect(listVirtualOperations()).toHaveLength(0);
    unregisterCommand('undecorated');
  });

  it('decorated command appears as virtual operation', () => {
    registerCommand({
      id: 'core.do-thing',
      label: 'Do Thing',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('core.do-thing', { description: 'Does a thing' });
    const ops = listVirtualOperations();
    expect(ops).toHaveLength(1);
    expect(ops[0]!.id).toBe('vop.core.do-thing');
    expect(ops[0]!.wireName).toBe('vop_core_do_thing');
    expect(ops[0]!.description).toBe('Does a thing');
    unregisterCommand('core.do-thing');
    clearAgentDecoration('core.do-thing');
  });

  it('agentVisible: false suppresses projection', () => {
    registerCommand({
      id: 'private-cmd',
      label: 'Private',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('private-cmd', { agentVisible: false });
    expect(listVirtualOperations().some((op) => op.sourceCommandId === 'private-cmd')).toBe(false);
    unregisterCommand('private-cmd');
    clearAgentDecoration('private-cmd');
  });

  it('operation-sourced commands are not projected (live in real catalog)', () => {
    registerCommand({
      id: 'op.something',
      label: 'Real Operation',
      source: 'operation',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('op.something', {});
    expect(listVirtualOperations().some((op) => op.sourceCommandId === 'op.something')).toBe(false);
    unregisterCommand('op.something');
    clearAgentDecoration('op.something');
  });

  it('falls back to label when description is omitted', () => {
    registerCommand({
      id: 'fb-cmd',
      label: 'Fallback Label',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('fb-cmd', {});
    const op = listVirtualOperations().find((o) => o.sourceCommandId === 'fb-cmd');
    expect(op?.description).toBe('Fallback Label');
    unregisterCommand('fb-cmd');
    clearAgentDecoration('fb-cmd');
  });

  it('parameters default to empty JSON-Schema object', () => {
    registerCommand({
      id: 'param-cmd',
      label: 'P',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('param-cmd', {});
    const op = listVirtualOperations().find((o) => o.sourceCommandId === 'param-cmd');
    expect(op?.parameters).toEqual({ type: 'object', properties: {} });
    unregisterCommand('param-cmd');
    clearAgentDecoration('param-cmd');
  });

  it('custom parameters override default', () => {
    registerCommand({
      id: 'p2',
      label: 'P2',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('p2', {
      parameters: {
        type: 'object',
        properties: { topic: { type: 'string' } },
        required: ['topic'],
      },
    });
    const op = listVirtualOperations().find((o) => o.sourceCommandId === 'p2');
    expect(op?.parameters).toMatchObject({ required: ['topic'] });
    unregisterCommand('p2');
    clearAgentDecoration('p2');
  });
});

describe('Audience filtering', () => {
  beforeEach(() => {
    registerCommand({
      id: 'audience-test',
      label: 'AT',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
  });

  it('serializeVirtualOperationsForAgent omits USER-only ops', () => {
    decorateCommandForAgent('audience-test', { audience: ['USER'] });
    const tools = serializeVirtualOperationsForAgent();
    expect(tools.some((t) => t.function.name === 'vop_audience_test')).toBe(false);
    clearAgentDecoration('audience-test');
    unregisterCommand('audience-test');
  });

  it('includes ops whose audience includes AGENT', () => {
    decorateCommandForAgent('audience-test', { audience: ['USER', 'AGENT'] });
    const tools = serializeVirtualOperationsForAgent();
    expect(tools.some((t) => t.function.name === 'vop_audience_test')).toBe(true);
    clearAgentDecoration('audience-test');
    unregisterCommand('audience-test');
  });

  it('produces the OpenAI tools envelope shape with audience field (§13 C)', () => {
    decorateCommandForAgent('audience-test', { description: 'AT description' });
    const tools = serializeVirtualOperationsForAgent();
    const t = tools.find((x) => x.function.name === 'vop_audience_test');
    expect(t).toMatchObject({
      type: 'function',
      function: {
        name: 'vop_audience_test',
        description: 'AT description',
        parameters: expect.any(Object),
      },
    });
    // Phase C: audience field is now included on the wire shape so
    // the backend can validate without trusting the FE filter.
    expect(t?.audience).toContain('AGENT');
    clearAgentDecoration('audience-test');
    unregisterCommand('audience-test');
  });
});

describe('Reverse lookup', () => {
  it('resolveAgentToolCall maps wireName back to source command id', () => {
    registerCommand({
      id: 'core.search',
      label: 'Search',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('core.search', { audience: ['USER', 'AGENT'] });
    expect(resolveAgentToolCall('vop_core_search')).toBe('core.search');
    expect(resolveAgentToolCall('unknown_wire_name')).toBeNull();
    clearAgentDecoration('core.search');
    unregisterCommand('core.search');
  });
});

describe('Publisher hook', () => {
  it('fires on change with the current operations list', () => {
    registerCommand({
      id: 'pub-test',
      label: 'PT',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    const publishFn = vi.fn();
    setVirtualOperationPublisher(publishFn);
    decorateCommandForAgent('pub-test', {});
    expect(publishFn).toHaveBeenCalled();
    const lastCall = publishFn.mock.calls.at(-1)![0];
    expect(lastCall.some((op: { sourceCommandId: string }) => op.sourceCommandId === 'pub-test')).toBe(true);
    setVirtualOperationPublisher(null);
    clearAgentDecoration('pub-test');
    unregisterCommand('pub-test');
  });
});

describe('publishNow (tempdoc 508-followup §β2)', () => {
  it('resolves with no publisher attached', async () => {
    setVirtualOperationPublisher(null);
    await expect(publishNow()).resolves.toBeUndefined();
  });

  it('awaits the publisher promise before resolving', async () => {
    let resolveFetch!: () => void;
    const fetchGate = new Promise<void>((resolve) => {
      resolveFetch = resolve;
    });
    let publishCompleted = false;
    setVirtualOperationPublisher(async () => {
      await fetchGate;
      publishCompleted = true;
    });
    const inFlight = publishNow();
    // Tick the microtask queue — without awaiting fetchGate, the publisher
    // has not completed.
    await Promise.resolve();
    expect(publishCompleted).toBe(false);
    resolveFetch();
    await inFlight;
    expect(publishCompleted).toBe(true);
    setVirtualOperationPublisher(null);
  });

  it('swallows publisher errors so boot continues', async () => {
    setVirtualOperationPublisher(async () => {
      throw new Error('backend not ready');
    });
    await expect(publishNow()).resolves.toBeUndefined();
    setVirtualOperationPublisher(null);
  });

  it('passes the current operations snapshot to the publisher', async () => {
    registerCommand({
      id: 'now-test',
      label: 'NT',
      source: 'shell',
      provenance: CORE_PROVENANCE,
      handler: () => {},
    });
    decorateCommandForAgent('now-test', {});
    const publishFn = vi.fn();
    setVirtualOperationPublisher(publishFn);
    await publishNow();
    expect(publishFn).toHaveBeenCalled();
    const args = publishFn.mock.calls.at(-1)![0];
    expect(args.some((op: { sourceCommandId: string }) => op.sourceCommandId === 'now-test')).toBe(true);
    setVirtualOperationPublisher(null);
    clearAgentDecoration('now-test');
    unregisterCommand('now-test');
  });
});
