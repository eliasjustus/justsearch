// @vitest-environment happy-dom

/**
 * Slice 515 FIX-9 — conversationListStore branching tests.
 *
 * Covers the store-level surface added in slice 513 (branchConversation,
 * fetchMessageIds, resumeConversation parent pointers). Vitest mocks
 * global.fetch so the tests stay purely unit-level — no dev stack needed.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  setConversationApiBase,
  branchConversation,
  fetchMessageIds,
  resumeConversation,
  deleteConversationWithCascade,
  siblingSessionsAt,
  getRecentSessions,
  recordRecentSession,
  type Conversation,
  __resetConversationListForTest,
} from './conversationListStore.js';

function conv(id: string, opts: Partial<Conversation> = {}): Conversation {
  return {
    id,
    title: null,
    createdAt: 0,
    lastActiveAt: 0,
    messageCount: 0,
    firstUserMessage: '',
    shapeId: 'core.rag-ask',
    ...opts,
  };
}

describe('Tempdoc 610 Phase B — siblingSessionsAt', () => {
  it('returns the base alone when there are no branches', () => {
    const convs = [conv('P'), conv('other', { parentSessionId: 'P', branchPointMessageId: 'XX' })];
    expect(siblingSessionsAt(convs, 'P', 'm1')).toEqual(['P']);
  });

  it('orders base first, then branches at the same fork by creation time', () => {
    const convs = [
      conv('P'),
      conv('B2', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 200 }),
      conv('B1', { parentSessionId: 'P', branchPointMessageId: 'm1', createdAt: 100 }),
      // different branch point — excluded
      conv('B3', { parentSessionId: 'P', branchPointMessageId: 'm9', createdAt: 150 }),
      // different parent — excluded
      conv('X', { parentSessionId: 'Q', branchPointMessageId: 'm1', createdAt: 50 }),
    ];
    expect(siblingSessionsAt(convs, 'P', 'm1')).toEqual(['P', 'B1', 'B2']);
  });

  it('groups empty-prefix (first-message) edits under the sentinel key', () => {
    const convs = [
      conv('P'),
      conv('E1', { parentSessionId: 'P', branchPointMessageId: '__empty_prefix__', createdAt: 10 }),
    ];
    expect(siblingSessionsAt(convs, 'P', '__empty_prefix__')).toEqual(['P', 'E1']);
  });
});

interface RecordedCall {
  url: string;
  method: string;
}

function mockFetch(handler: (url: string, method: string) => Response): RecordedCall[] {
  const calls: RecordedCall[] = [];
  global.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
    const u = url.toString();
    const method = init?.method ?? 'GET';
    calls.push({ url: u, method });
    return Promise.resolve(handler(u, method));
  }) as typeof fetch;
  return calls;
}

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

describe('conversationListStore branching', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('branchConversation POSTs the branch endpoint with fromMsgId and returns new sessionId', async () => {
    const calls = mockFetch((url) => {
      if (url.includes('/branch?fromMsgId=')) {
        return jsonResponse({
          sessionId: 'uc-branch-1',
          parentSessionId: 'parent-1',
          branchPointMessageId: 'msg-X',
        });
      }
      return jsonResponse({}, 404);
    });
    const result = await branchConversation('parent-1', 'msg-X', 'first-msg preview');
    expect(result).toBe('uc-branch-1');
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toContain('/api/chat/conversations/parent-1/branch');
    expect(calls[0]?.url).toContain('fromMsgId=msg-X');
  });

  it('branchConversation URL-encodes parent + msgId path segments', async () => {
    const calls = mockFetch(() => jsonResponse({ sessionId: 'uc-x' }));
    await branchConversation('weird/parent id', 'msg with spaces', 'preview');
    expect(calls[0]?.url).toContain('weird%2Fparent%20id');
    expect(calls[0]?.url).toContain('msg%20with%20spaces');
  });

  it('branchConversation returns null on non-2xx response', async () => {
    mockFetch(() => jsonResponse({ error: 'bad' }, 400));
    const result = await branchConversation('parent-1', 'msg-X');
    expect(result).toBeNull();
  });

  it('branchConversation returns null when response lacks sessionId', async () => {
    mockFetch(() => jsonResponse({}));
    const result = await branchConversation('p', 'm');
    expect(result).toBeNull();
  });

  it('branchConversation returns null on network error', async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error('netdown'))) as typeof fetch;
    const result = await branchConversation('p', 'm');
    expect(result).toBeNull();
  });

  it('fetchMessageIds returns the message list with ids', async () => {
    mockFetch((url) => {
      if (url.includes('/history')) {
        return jsonResponse({
          messages: [
            { role: 'user', content: 'q1', id: 'idA' },
            { role: 'assistant', content: 'a1', id: 'idB' },
          ],
        });
      }
      return jsonResponse({}, 404);
    });
    const out = await fetchMessageIds('parent-1');
    expect(out).not.toBeNull();
    expect(out).toEqual([
      { role: 'user', content: 'q1', id: 'idA' },
      { role: 'assistant', content: 'a1', id: 'idB' },
    ]);
  });

  it('fetchMessageIds returns null on non-2xx', async () => {
    mockFetch(() => jsonResponse({}, 500));
    expect(await fetchMessageIds('x')).toBeNull();
  });

  it('fetchMessageIds returns null on network error', async () => {
    global.fetch = vi.fn(() => Promise.reject(new Error('boom'))) as typeof fetch;
    expect(await fetchMessageIds('x')).toBeNull();
  });

  it('resumeConversation surfaces parentSessionId + branchPointMessageId when the backend includes them', async () => {
    mockFetch(() =>
      jsonResponse({
        messages: [{ role: 'user', content: 'q', id: 'mid-1' }],
        parentSessionId: 'parent-A',
        branchPointMessageId: 'mid-1',
      }),
    );
    const resumed = await resumeConversation('branch-A', 'core.free-chat');
    expect(resumed.parentSessionId).toBe('parent-A');
    expect(resumed.branchPointMessageId).toBe('mid-1');
    expect(resumed.messages).toEqual([{ role: 'user', content: 'q', id: 'mid-1' }]);
  });

  it('resumeConversation omits parent pointers for a root session', async () => {
    mockFetch(() => jsonResponse({ messages: [{ role: 'user', content: 'q', id: 'a' }] }));
    const resumed = await resumeConversation('root-1', 'core.free-chat');
    expect(resumed.parentSessionId).toBeUndefined();
    expect(resumed.branchPointMessageId).toBeUndefined();
  });

  it('Slice 516 FIX-T5: resumeConversation surfaces parentFirstUserMessage from response', async () => {
    mockFetch(() =>
      jsonResponse({
        messages: [{ role: 'user', content: 'q', id: 'mid' }],
        parentSessionId: 'parent-1',
        branchPointMessageId: 'mid',
        parentFirstUserMessage: 'How do I summarize PDFs?',
      }),
    );
    const resumed = await resumeConversation('branch-1', 'core.free-chat');
    expect(resumed.parentFirstUserMessage).toBe('How do I summarize PDFs?');
  });

  it('Slice 516 FIX-T5: branchConversation records the new session as recent (when preview supplied)', async () => {
    mockFetch(() => jsonResponse({ sessionId: 'uc-branched-recent' }));
    // Pre-condition: localStorage has no entry for the new id.
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    const result = await branchConversation('p', 'm', 'first user msg preview');
    expect(result).toBe('uc-branched-recent');
    const stored = JSON.parse(localStorage.getItem(KEY) ?? '[]') as Array<Record<string, unknown>>;
    const entry = stored.find((s) => s.sessionId === 'uc-branched-recent');
    expect(entry).toBeDefined();
    // Tempdoc 562: the recent-session pointer must NOT carry message content (no plaintext at rest); the
    // preview is derived from the lock-safe backend list instead.
    expect(entry).not.toHaveProperty('firstMessage');
    expect(JSON.stringify(entry)).not.toContain('first user msg preview');
    localStorage.removeItem(KEY); // cleanup
  });

  it('Slice 516 FIX-T5: branchConversation does NOT record when no preview is supplied', async () => {
    mockFetch(() => jsonResponse({ sessionId: 'uc-no-preview' }));
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    await branchConversation('p', 'm'); // no preview
    const stored = JSON.parse(localStorage.getItem(KEY) ?? '[]') as Array<{
      sessionId: string;
    }>;
    expect(stored.find((s) => s.sessionId === 'uc-no-preview')).toBeUndefined();
    localStorage.removeItem(KEY); // cleanup
  });

  it('Tempdoc 562: recordRecentSession stores ONLY the pointer (no message content)', () => {
    const KEY = 'jf-chat-recent-sessions';
    localStorage.removeItem(KEY);
    recordRecentSession('uc-pointer-only');
    const raw = localStorage.getItem(KEY) ?? '[]';
    expect(raw).not.toContain('firstMessage');
    const stored = JSON.parse(raw) as Array<Record<string, unknown>>;
    expect(stored[0]).toMatchObject({ sessionId: 'uc-pointer-only' });
    expect(stored[0]).not.toHaveProperty('firstMessage');
    localStorage.removeItem(KEY);
  });

  it('Tempdoc 562: getRecentSessions purges legacy cached plaintext at rest', () => {
    const KEY = 'jf-chat-recent-sessions';
    // Seed a LEGACY entry that carried plaintext message content (the pre-562 shape).
    localStorage.setItem(
      KEY,
      JSON.stringify([{ sessionId: 'uc-legacy', firstMessage: 'SECRET plaintext leak', timestamp: 123 }]),
    );
    const sessions = getRecentSessions();
    // The reader returns pointer-only entries…
    expect(sessions).toEqual([{ sessionId: 'uc-legacy', timestamp: 123 }]);
    // …AND rewrites localStorage so the cached plaintext is purged at rest.
    const after = localStorage.getItem(KEY) ?? '';
    expect(after).not.toContain('SECRET plaintext leak');
    expect(after).not.toContain('firstMessage');
    localStorage.removeItem(KEY);
  });
});

describe('deleteConversationWithCascade (Slice 517 FIX-U1)', () => {
  beforeEach(() => {
    __resetConversationListForTest();
    setConversationApiBase('');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('happy path: 2xx delete returns ok:true and clears local state', async () => {
    const calls = mockFetch(() => jsonResponse({ ok: true }));
    const result = await deleteConversationWithCascade('uc-1');
    expect(result).toEqual({ ok: true });
    expect(calls).toHaveLength(1);
    expect(calls[0]?.method).toBe('DELETE');
  });

  it('409 without onChildsFound callback returns ok:false with childIds', async () => {
    mockFetch(() =>
      jsonResponse({
        error: 'has branches',
        errorCode: 'BRANCHES_PREVENT_DELETION',
        childSessionIds: ['child-1', 'child-2'],
      }, 409),
    );
    const result = await deleteConversationWithCascade('parent-1');
    expect(result.ok).toBe(false);
    expect(result.childIds).toEqual(['child-1', 'child-2']);
  });

  it('409 with onChildsFound returning false leaves parent + children intact', async () => {
    const calls = mockFetch(() =>
      jsonResponse({
        errorCode: 'BRANCHES_PREVENT_DELETION',
        childSessionIds: ['child-X'],
      }, 409),
    );
    const onChildsFound = vi.fn().mockResolvedValue(false);
    const result = await deleteConversationWithCascade('parent-X', onChildsFound);
    expect(result.ok).toBe(false);
    expect(result.childIds).toEqual(['child-X']);
    expect(onChildsFound).toHaveBeenCalledWith(['child-X']);
    // Only the original DELETE attempt — no cascade.
    expect(calls.filter((c) => c.method === 'DELETE')).toHaveLength(1);
  });

  it('409 with onChildsFound returning true cascades: children first, then parent', async () => {
    // Sequence the fetch responses: first parent DELETE → 409 with one
    // child id; child DELETE → 200; parent retry → 200.
    let parentAttempt = 0;
    const fetched: string[] = [];
    global.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      const u = url.toString();
      const method = init?.method ?? 'GET';
      fetched.push(`${method} ${u}`);
      if (u.includes('/parent-1') && method === 'DELETE') {
        parentAttempt++;
        if (parentAttempt === 1) {
          return Promise.resolve(jsonResponse({
            errorCode: 'BRANCHES_PREVENT_DELETION',
            childSessionIds: ['child-A'],
          }, 409));
        }
        return Promise.resolve(jsonResponse({ ok: true }));
      }
      if (u.includes('/child-A') && method === 'DELETE') {
        return Promise.resolve(jsonResponse({ ok: true }));
      }
      return Promise.resolve(jsonResponse({}, 404));
    }) as typeof fetch;

    const onChildsFound = vi.fn().mockResolvedValue(true);
    const result = await deleteConversationWithCascade('parent-1', onChildsFound);
    expect(result).toEqual({ ok: true });
    expect(onChildsFound).toHaveBeenCalledWith(['child-A']);
    // Sequence: parent-1 (409) → child-A (200) → parent-1 (200).
    expect(fetched).toEqual([
      'DELETE /api/chat/conversations/parent-1',
      'DELETE /api/chat/conversations/child-A',
      'DELETE /api/chat/conversations/parent-1',
    ]);
  });

  it('cascade aborts if a child delete fails — parent stays intact', async () => {
    let parentAttempt = 0;
    global.fetch = vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
      const u = url.toString();
      const method = init?.method ?? 'GET';
      if (u.includes('/parent-2') && method === 'DELETE') {
        parentAttempt++;
        return Promise.resolve(jsonResponse({
          errorCode: 'BRANCHES_PREVENT_DELETION',
          childSessionIds: ['child-fail'],
        }, 409));
      }
      if (u.includes('/child-fail') && method === 'DELETE') {
        return Promise.resolve(jsonResponse({ error: 'network glitch' }, 500));
      }
      return Promise.resolve(jsonResponse({}, 404));
    }) as typeof fetch;

    const onChildsFound = vi.fn().mockResolvedValue(true);
    const result = await deleteConversationWithCascade('parent-2', onChildsFound);
    expect(result.ok).toBe(false);
    // parent was attempted exactly once — no retry after child failure.
    expect(parentAttempt).toBe(1);
  });
});
