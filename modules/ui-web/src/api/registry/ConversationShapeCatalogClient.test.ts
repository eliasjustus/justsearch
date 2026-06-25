/**
 * Slice 491 §9.D Phase E (C0) — ConversationShapeCatalogClient tests.
 *
 * Mirrors SurfaceCatalogClient.test.ts structure. Validates the catalog client's
 * fetch + ETag + lookup + listener contract.
 *
 * @vitest-environment happy-dom
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ConversationShape,
  ConversationShapeCatalog,
} from '../types/conversation-shape';
import {
  __resetConversationShapeCatalogForTest,
  __seedConversationShapeCatalogForTest,
  bootConversationShapeRegistry,
  getShape,
  listShapes,
  onConversationShapeCatalogChange,
} from './ConversationShapeCatalogClient';

function navigateChatShape(): ConversationShape {
  return {
    id: 'core.navigate-chat',
    presentation: {
      labelKey: 'registry-conversation-shape.navigate-chat.label',
      descriptionKey: 'registry-conversation-shape.navigate-chat.description',
      iconHint: null,
      category: null,
    },
    audience: 'USER',
    provenance: { tier: 'CORE', contributorId: 'core', version: '1.0' },
    executionMode: 'SUBSTRATE_DRIVEN',
    iterationMode: 'ONE_SHOT',
    persistenceMode: 'EPHEMERAL',
    promptContributorIds: ['core.url-emission-grammar'],
    contextInjectorIds: [],
    streamConsumerIds: ['core.url-extractor'],
    iterationControllerId: null,
    eventSchema: ['chunk', 'done', 'error', 'navigate.url_extracted', 'navigate.url_dispatched', 'navigate.url_rejected'],
  };
}

function summarizeShape(): ConversationShape {
  return {
    ...navigateChatShape(),
    id: 'core.summarize',
    eventSchema: ['chunk', 'done', 'error'],
  };
}

function catalogOf(...entries: ConversationShape[]): ConversationShapeCatalog {
  return {
    schemaVersion: '1.0',
    catalogVersion: 1,
    namespace: 'registry-shape',
    primitive: 'ConversationShape',
    entries,
  };
}

describe('ConversationShapeCatalogClient', () => {
  beforeEach(() => {
    __resetConversationShapeCatalogForTest();
  });
  afterEach(() => {
    __resetConversationShapeCatalogForTest();
  });

  describe('lookup API', () => {
    it('returns undefined for unknown id before boot', () => {
      expect(getShape('core.unknown-shape')).toBeUndefined();
    });

    it('returns the seeded entry by id', () => {
      __seedConversationShapeCatalogForTest(catalogOf(navigateChatShape()));
      const s = getShape('core.navigate-chat');
      expect(s?.audience).toBe('USER');
      expect(s?.executionMode).toBe('SUBSTRATE_DRIVEN');
      expect(s?.eventSchema).toContain('navigate.url_dispatched');
    });

    it('listShapes returns all entries', () => {
      __seedConversationShapeCatalogForTest(
        catalogOf(navigateChatShape(), summarizeShape()),
      );
      expect(
        listShapes()
          .map((s) => s.id)
          .sort(),
      ).toEqual(['core.navigate-chat', 'core.summarize']);
    });
  });

  describe('listener subscription', () => {
    it('fires the listener on seed', () => {
      const fired: number[] = [];
      onConversationShapeCatalogChange(() => fired.push(Date.now()));
      __seedConversationShapeCatalogForTest(catalogOf(navigateChatShape()));
      expect(fired.length).toBe(1);
    });

    it('unsubscribe stops further notifications', () => {
      const fired: number[] = [];
      const unsub = onConversationShapeCatalogChange(() => fired.push(Date.now()));
      __seedConversationShapeCatalogForTest(catalogOf(navigateChatShape()));
      expect(fired.length).toBe(1);
      unsub();
      __seedConversationShapeCatalogForTest(catalogOf(navigateChatShape(), summarizeShape()));
      expect(fired.length).toBe(1);
    });
  });

  describe('boot fetch', () => {
    it('fetches and populates from baseUrl', async () => {
      const body = catalogOf(navigateChatShape(), summarizeShape());
      const fetchImpl = vi.fn().mockResolvedValue(
        new Response(JSON.stringify(body), {
          status: 200,
          headers: { ETag: '"abc"', 'Content-Type': 'application/json' },
        }),
      );
      await bootConversationShapeRegistry('http://test', fetchImpl as unknown as typeof fetch);
      expect(fetchImpl).toHaveBeenCalledWith(
        'http://test/api/registry/shapes',
        expect.objectContaining({}),
      );
      expect(getShape('core.navigate-chat')).toBeDefined();
      expect(getShape('core.summarize')).toBeDefined();
    });

    it('handles 304 by retaining cached state', async () => {
      // Pre-populate cache
      localStorage.setItem(
        'justsearch.conversationShapeCatalog.body',
        JSON.stringify(catalogOf(navigateChatShape())),
      );
      localStorage.setItem('justsearch.conversationShapeCatalog.etag', '"abc"');
      __resetConversationShapeCatalogForTest();
      // Re-seed cache that the test reset cleared.
      localStorage.setItem(
        'justsearch.conversationShapeCatalog.body',
        JSON.stringify(catalogOf(navigateChatShape())),
      );
      localStorage.setItem('justsearch.conversationShapeCatalog.etag', '"abc"');
      const fetchImpl = vi.fn().mockResolvedValue(new Response(null, { status: 304 }));
      await bootConversationShapeRegistry('http://test', fetchImpl as unknown as typeof fetch);
      expect(fetchImpl).toHaveBeenCalled();
      const callArgs = fetchImpl.mock.calls[0]?.[1];
      expect((callArgs as { headers?: Record<string, string> })?.headers?.['If-None-Match']).toBe('"abc"');
      expect(getShape('core.navigate-chat')).toBeDefined();
    });
  });
});
