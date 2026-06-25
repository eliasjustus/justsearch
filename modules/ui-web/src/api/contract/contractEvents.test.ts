/**
 * Slice 3a-1-8e (ship-option a, 2026-05-07) — unit tests for the contract
 * events FE consumer module. Exercises subscribe/dispatch/unsubscribe
 * across all four event kinds, the reaction-outcome emit path, and the
 * decoder.
 */

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import {
  __emitForTest,
  __resetForTest,
  __setReactionOutcomeSinkForTest,
  decodeContractEvent,
  emitReactionOutcome,
  subscribe,
  type ContractEvent,
} from './contractEvents.js';

describe('contractEvents', () => {
  beforeEach(() => {
    __resetForTest();
  });

  afterEach(() => {
    __resetForTest();
  });

  describe('subscribe + dispatch', () => {
    test('capability-registered fires only matching subscribers', () => {
      const listener = vi.fn();
      subscribe('capability-registered', listener);

      __emitForTest({
        kind: 'capability-registered',
        capabilityId: 'core.library',
        capabilityType: 'resource',
      });

      expect(listener).toHaveBeenCalledOnce();
      expect(listener.mock.calls[0]?.[0].capabilityId).toBe('core.library');
    });

    test('capability-unregistered does NOT fire capability-registered listeners', () => {
      const registered = vi.fn();
      const unregistered = vi.fn();
      subscribe('capability-registered', registered);
      subscribe('capability-unregistered', unregistered);

      __emitForTest({
        kind: 'capability-unregistered',
        capabilityId: 'core.library',
        capabilityType: 'resource',
      });

      expect(registered).not.toHaveBeenCalled();
      expect(unregistered).toHaveBeenCalledOnce();
    });

    test('catalog-membership-changed filtered by category', () => {
      const resourceListener = vi.fn();
      const operationListener = vi.fn();
      subscribe('catalog-membership-changed', resourceListener, {
        category: 'resource',
      });
      subscribe('catalog-membership-changed', operationListener, {
        category: 'operation',
      });

      __emitForTest({
        kind: 'catalog-membership-changed',
        category: 'resource',
        added: ['core.new-resource'],
      });

      expect(resourceListener).toHaveBeenCalledOnce();
      expect(operationListener).not.toHaveBeenCalled();
    });

    test('capability-registered filtered by capabilityType', () => {
      const resourceTypeListener = vi.fn();
      const surfaceTypeListener = vi.fn();
      subscribe('capability-registered', resourceTypeListener, {
        capabilityType: 'resource',
      });
      subscribe('capability-registered', surfaceTypeListener, {
        capabilityType: 'surface',
      });

      __emitForTest({
        kind: 'capability-registered',
        capabilityId: 'core.lib',
        capabilityType: 'surface',
      });

      expect(resourceTypeListener).not.toHaveBeenCalled();
      expect(surfaceTypeListener).toHaveBeenCalledOnce();
    });

    test('unsubscribe stops further dispatch', () => {
      const listener = vi.fn();
      const unsubscribe = subscribe('reaction-outcome', listener);

      __emitForTest({
        kind: 'reaction-outcome',
        capabilityId: 'core.lib',
        consumerId: 'TestConsumer',
        outcome: 'APPLIED',
      });
      expect(listener).toHaveBeenCalledOnce();

      unsubscribe();
      __emitForTest({
        kind: 'reaction-outcome',
        capabilityId: 'core.lib',
        consumerId: 'TestConsumer',
        outcome: 'APPLIED',
      });
      expect(listener).toHaveBeenCalledOnce(); // still 1
    });

    test('listener errors do not break other listeners', () => {
      const failing = vi.fn(() => {
        throw new Error('listener boom');
      });
      const succeeding = vi.fn();
      subscribe('capability-registered', failing);
      subscribe('capability-registered', succeeding);

      __emitForTest({
        kind: 'capability-registered',
        capabilityId: 'x',
        capabilityType: 'resource',
      });

      expect(failing).toHaveBeenCalledOnce();
      expect(succeeding).toHaveBeenCalledOnce();
    });
  });

  describe('emitReactionOutcome', () => {
    test('routes to injected sink when set', () => {
      const captured: ContractEvent[] = [];
      __setReactionOutcomeSinkForTest((e) => captured.push(e));

      emitReactionOutcome(
        'core.library',
        'ResourceCatalogClient',
        'APPLIED',
      );

      expect(captured).toHaveLength(1);
      expect(captured[0]).toMatchObject({
        kind: 'reaction-outcome',
        capabilityId: 'core.library',
        consumerId: 'ResourceCatalogClient',
        outcome: 'APPLIED',
      });
    });

    test('captures REJECTED with reason', () => {
      const captured: ContractEvent[] = [];
      __setReactionOutcomeSinkForTest((e) => captured.push(e));

      emitReactionOutcome(
        'plugin.foo',
        'PluginRegistry',
        'REJECTED',
        'unknown capability type',
      );

      expect(captured[0]).toMatchObject({
        outcome: 'REJECTED',
        reason: 'unknown capability type',
      });
    });

    test('default sink logs to console when no sink set', () => {
      const debugSpy = vi.spyOn(console, 'debug').mockImplementation(() => {});
      try {
        emitReactionOutcome('x', 'C', 'DEGRADED', 'cache stale');
        expect(debugSpy).toHaveBeenCalledOnce();
      } finally {
        debugSpy.mockRestore();
      }
    });
  });

  describe('decodeContractEvent', () => {
    test('decodes a well-formed payload', () => {
      const payload = {
        kind: 'capability-registered',
        capabilityId: 'core.library',
        capabilityType: 'resource',
        attributes: { iconHint: 'book' },
      };
      const event = decodeContractEvent(payload);
      expect(event).toMatchObject({
        kind: 'capability-registered',
        capabilityId: 'core.library',
        capabilityType: 'resource',
      });
      expect(event?.attributes).toEqual({ iconHint: 'book' });
    });

    test('decodes catalog-membership-changed with arrays', () => {
      const payload = {
        kind: 'catalog-membership-changed',
        category: 'operation',
        added: ['op.a', 'op.b'],
        removed: ['op.c'],
      };
      const event = decodeContractEvent(payload);
      expect(event?.added).toEqual(['op.a', 'op.b']);
      expect(event?.removed).toEqual(['op.c']);
    });

    test('rejects payloads missing kind', () => {
      expect(decodeContractEvent({ capabilityId: 'x' })).toBeUndefined();
      expect(decodeContractEvent({ kind: 123 })).toBeUndefined();
      expect(decodeContractEvent({ kind: '' })).toBeUndefined();
      expect(decodeContractEvent(null)).toBeUndefined();
      expect(decodeContractEvent('not an object')).toBeUndefined();
    });

    test('forward-compat: unknown kind is decoded but typed as string', () => {
      const event = decodeContractEvent({
        kind: 'future-event-variant',
        attributes: { foo: 'bar' },
      });
      expect(event?.kind).toBe('future-event-variant');
    });

    test('non-string array entries filtered out', () => {
      const event = decodeContractEvent({
        kind: 'catalog-membership-changed',
        category: 'resource',
        added: ['valid', 123, null, 'also-valid'],
      });
      expect(event?.added).toEqual(['valid', 'also-valid']);
    });
  });
});
