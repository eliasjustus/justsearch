// SPDX-License-Identifier: Apache-2.0
/**
 * conversationListStore — lightweight conversation management (tempdoc 510 Design D).
 *
 * Lists, resumes, titles, and exports conversations. Separate from AiStateStore
 * because conversations are a content domain, not an AI state concern.
 */

export interface Conversation {
  id: string;
  title: string | null;
  createdAt: number;
  lastActiveAt: number;
  messageCount: number;
  firstUserMessage: string;
  shapeId: string;
  // Slice 513 — branching: when this conversation was forked from another,
  // parentSessionId points at the source and branchPointMessageId records
  // the last message included from the parent. Both omitted on root sessions.
  parentSessionId?: string;
  branchPointMessageId?: string;
}

interface ConversationListState {
  conversations: Conversation[];
  activeId: string | null;
  loading: boolean;
}

let state: ConversationListState = {
  conversations: [],
  activeId: null,
  loading: false,
};

const listeners = new Set<(s: ConversationListState) => void>();
const TITLES_KEY = 'jf-conversation-titles';
const RECENT_SESSIONS_KEY = 'jf-chat-recent-sessions';
const MAX_RECENT = 10;
let apiBase = '';

export interface RecentSession {
  sessionId: string;
  timestamp: number;
}

// Tempdoc 562: the recent-session pointer NEVER carries message content. The previous shape cached a
// plaintext `firstMessage` slice in localStorage, which the resume card rendered on the start screen even
// while the chat store was encrypted + locked — a real content leak outside the encryption boundary. The
// resume preview is now derived from the lock-safe backend conversation list instead (`firstUserMessage`,
// which `listSessions` returns as "" while locked). This reader also purges any legacy plaintext at rest.
export function getRecentSessions(): RecentSession[] {
  try {
    const raw = localStorage.getItem(RECENT_SESSIONS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw) as Array<Record<string, unknown>>;
    const clean: RecentSession[] = parsed
      .filter((s) => typeof s.sessionId === 'string')
      .map((s) => ({ sessionId: s.sessionId as string, timestamp: Number(s.timestamp) || 0 }));
    const cleanRaw = JSON.stringify(clean);
    if (cleanRaw !== raw) {
      // Legacy entries carried a `firstMessage` — rewrite the sanitized list to purge the cached plaintext.
      try { localStorage.setItem(RECENT_SESSIONS_KEY, cleanRaw); } catch { /* */ }
    }
    return clean;
  } catch { return []; }
}

export function recordRecentSession(sessionId: string): void {
  try {
    const sessions = getRecentSessions().filter((s) => s.sessionId !== sessionId);
    sessions.unshift({ sessionId, timestamp: Date.now() });
    if (sessions.length > MAX_RECENT) sessions.length = MAX_RECENT;
    localStorage.setItem(RECENT_SESSIONS_KEY, JSON.stringify(sessions));
  } catch { /* */ }
}

export function forgetRecentSession(sessionId: string): void {
  try {
    const sessions = getRecentSessions().filter((s) => s.sessionId !== sessionId);
    localStorage.setItem(RECENT_SESSIONS_KEY, JSON.stringify(sessions));
  } catch { /* */ }
}

function emit(): void {
  for (const l of listeners) l(state);
}

function loadTitles(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(TITLES_KEY) ?? '{}');
  } catch { return {}; }
}

function saveTitle(id: string, title: string): void {
  try {
    const titles = loadTitles();
    titles[id] = title;
    localStorage.setItem(TITLES_KEY, JSON.stringify(titles));
  } catch { /* */ }
}

export function setConversationApiBase(base: string): void {
  apiBase = base;
}

export function subscribeConversationList(
  listener: (s: ConversationListState) => void,
): () => void {
  listeners.add(listener);
  listener(state);
  return () => listeners.delete(listener);
}

export function getConversationListState(): ConversationListState {
  return state;
}

export async function loadConversations(): Promise<void> {
  state = { ...state, loading: true };
  emit();
  try {
    const res = await fetch(`${apiBase}/api/chat/conversations?limit=20`);
    if (!res.ok) { state = { ...state, loading: false }; emit(); return; }
    const data = await res.json();
    const titles = loadTitles();
    const conversations: Conversation[] = (data.sessions ?? []).map(
      (s: Record<string, unknown>) => ({
        id: s.sessionId as string,
        title: titles[s.sessionId as string] ?? null,
        createdAt: s.createdAtMs as number,
        lastActiveAt: s.lastActiveAtMs as number,
        messageCount: s.messageCount as number,
        firstUserMessage: s.firstUserMessage as string,
        shapeId: s.shapeId as string,
        parentSessionId: typeof s.parentSessionId === 'string'
          ? (s.parentSessionId as string)
          : undefined,
        branchPointMessageId: typeof s.branchPointMessageId === 'string'
          ? (s.branchPointMessageId as string)
          : undefined,
      }),
    );
    state = { ...state, conversations, loading: false };
    emit();
  } catch {
    state = { ...state, loading: false };
    emit();
  }
}

export function setActiveConversation(id: string | null): void {
  state = { ...state, activeId: id };
  emit();
}

/**
 * Tempdoc 610 Phase B — the ordered "version" set at a fork point. A fork is
 * identified by a base conversation `baseId` and the `branchKey` (the branch
 * point: the parent message id the branches diverge after, or the empty-prefix
 * sentinel). Version 1 is always the base (the original continuation); the
 * branches that share `(parentSessionId === baseId, branchPointMessageId ===
 * branchKey)` follow, ordered by creation time. Returns `[baseId]` alone when
 * there are no branches (callers render the pager only when length > 1). This
 * is a pure read-side projection over the already-loaded conversation list —
 * no new endpoint (the siblings are discoverable from listSessions' parent
 * pointers).
 */
export function siblingSessionsAt(
  conversations: Conversation[],
  baseId: string,
  branchKey: string,
): string[] {
  const branches = conversations
    .filter((c) => c.parentSessionId === baseId && c.branchPointMessageId === branchKey)
    .slice()
    .sort((a, b) => a.createdAt - b.createdAt)
    .map((c) => c.id);
  return [baseId, ...branches];
}

export function setConversationTitle(id: string, title: string): void {
  saveTitle(id, title);
  state = {
    ...state,
    conversations: state.conversations.map((c) =>
      c.id === id ? { ...c, title } : c,
    ),
  };
  emit();
}

export function createConversationId(): string {
  return `uc-${crypto.randomUUID()}`;
}

/**
 * Generate and persist an auto-title for the given conversation. Dispatches a
 * one-off FreeChat with a throwaway sessionId, reads the streamed reply, and
 * then deletes the throwaway session to avoid polluting persisted state.
 * Returns the generated title on success or null on failure. Best-effort —
 * never throws.
 */
export async function generateConversationTitle(
  sessionId: string,
  userMsg: string,
  aiMsg: string,
): Promise<string | null> {
  if (!userMsg || !aiMsg) return null;
  // Slice 516 FIX-T4 — prefix MUST match the Java constant
  // ConversationStore.THROWAWAY_SESSION_PREFIX
  // (modules/app-agent-api/.../conversation/ConversationStore.java).
  // FileConversationStore.deleteSession bypasses the branches-prevent-
  // deletion check for sessions starting with this prefix.
  const throwawayId = `_title_${crypto.randomUUID()}`;
  const prompt = `Generate a concise 3-5 word title for this conversation. Reply with only the title, nothing else.\n\nUser: ${userMsg.slice(0, 200)}\nAssistant: ${aiMsg.slice(0, 200)}`;
  try {
    const res = await fetch(`${apiBase}/api/chat/dispatch`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ shapeId: 'core.free-chat', prompt, sessionId: throwawayId }),
    });
    if (!res.ok || !res.body) {
      void deleteThrowaway(throwawayId);
      return null;
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let title = '';
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = decoder.decode(value, { stream: true });
      for (const line of chunk.split('\n')) {
        if (line.startsWith('data: ')) {
          try {
            const data = JSON.parse(line.slice(6));
            if (typeof data.text === 'string') title += data.text;
          } catch { /* */ }
        }
      }
    }
    void deleteThrowaway(throwawayId);
    title = title.trim().replace(/^["']|["']$/g, '');
    if (title.length === 0 || title.length >= 80) return null;
    setConversationTitle(sessionId, title);
    return title;
  } catch {
    void deleteThrowaway(throwawayId);
    return null;
  }
}

async function deleteThrowaway(sessionId: string): Promise<void> {
  try {
    await fetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
  } catch { /* best-effort cleanup */ }
}

export interface ResumedMessage {
  role: 'user' | 'assistant';
  content: string;
  // Slice 513 — stable message id from the backend (real UUID for post-513
  // writes, synthetic idx-N for legacy data). Used as the branch-point id
  // when the user clicks "Branch here" on an assistant turn.
  id?: string;
}

export interface ResumedConversation {
  sessionId: string;
  shapeId: string;
  messages: Array<ResumedMessage>;
  // Slice 513 — when this is a branch, parentSessionId is the source and
  // branchPointMessageId is the last inherited message id. Messages up to
  // and including that id were prepended from the parent.
  parentSessionId?: string;
  branchPointMessageId?: string;
  // Slice 515 FIX-8 — parent's first user message preview, surfaced by the
  // backend so the branch banner can name the parent without a second
  // roundtrip. Falls back to undefined when the parent is missing or empty.
  parentFirstUserMessage?: string;
  // Tempdoc 610 Phase C — the effective-context floor message id (undefined =
  // no floor). The transcript still displays the full history; messages above
  // this id are out-of-context (not sent to the next LLM turn).
  contextFloor?: string;
  // Tempdoc 610 Phase D — the compaction summary attached to the floor
  // (undefined for a plain rewind). Shown via the divider's "Show summary".
  contextFloorSummary?: string;
  // Tempdoc 610 §E.3 — message ids the user excluded from the effective context
  // (still displayed in the transcript, dropped from the next prompt).
  excludedMessageIds?: string[];
  // Tempdoc 610 §J.3 — retrieved-source ids the user hid from retrieval (unit-separator-joined
  // parentDocId+chunkIndex); the Worker drops these chunks on subsequent turns.
  excludedSourceIds?: string[];
  // Tempdoc 629 (LAYER) — true when the history load returned 423 (the conversation store is
  // encrypted + locked). The transcript must render a "locked → Unlock" notice, NOT an empty
  // thread (an empty thread reads as deleted; §L4: locked must never look deleted).
  locked?: boolean;
}

export async function resumeConversation(
  sessionId: string,
  shapeId: string,
): Promise<ResumedConversation> {
  try {
    const res = await fetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/history`);
    // Tempdoc 629 (LAYER): 423 = the conversation store is encrypted + locked. Surface it as a typed
    // locked state so the chat surface renders an unlock affordance instead of an empty (deleted-looking)
    // transcript. Other non-OK responses remain a benign empty resume.
    if (res.status === 423) return { sessionId, shapeId, messages: [], locked: true };
    if (!res.ok) return { sessionId, shapeId, messages: [] };
    const data = (await res.json()) as {
      messages?: Array<{ role?: string; content?: string; id?: string }>;
      parentSessionId?: string;
      branchPointMessageId?: string;
      parentFirstUserMessage?: string;
      contextFloor?: string;
      contextFloorSummary?: string;
      excludedMessageIds?: string[];
      excludedSourceIds?: string[];
    };
    const messages = (data.messages ?? [])
      .filter((m) => (m.role === 'user' || m.role === 'assistant') && typeof m.content === 'string')
      .map((m): ResumedMessage => ({
        role: m.role as 'user' | 'assistant',
        content: m.content as string,
        id: typeof m.id === 'string' ? m.id : undefined,
      }));
    setActiveConversation(sessionId);
    return {
      sessionId,
      shapeId,
      messages,
      parentSessionId: typeof data.parentSessionId === 'string'
        ? data.parentSessionId
        : undefined,
      branchPointMessageId: typeof data.branchPointMessageId === 'string'
        ? data.branchPointMessageId
        : undefined,
      parentFirstUserMessage: typeof data.parentFirstUserMessage === 'string'
        ? data.parentFirstUserMessage
        : undefined,
      contextFloor: typeof data.contextFloor === 'string' ? data.contextFloor : undefined,
      contextFloorSummary:
        typeof data.contextFloorSummary === 'string' ? data.contextFloorSummary : undefined,
      excludedMessageIds: Array.isArray(data.excludedMessageIds)
        ? data.excludedMessageIds.filter((x): x is string => typeof x === 'string')
        : undefined,
      excludedSourceIds: Array.isArray(data.excludedSourceIds)
        ? data.excludedSourceIds.filter((x): x is string => typeof x === 'string')
        : undefined,
    };
  } catch {
    return { sessionId, shapeId, messages: [] };
  }
}

/**
 * Tempdoc 610 Phase C — set the session's effective-context floor (the next
 * LLM turn's prompt starts at this message; the transcript still shows
 * everything above it). Returns true on success.
 */
export async function setContextFloor(
  sessionId: string,
  floorMessageId: string,
): Promise<boolean> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ floorMessageId }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/** Tempdoc 610 Phase C — clear the floor (restore full context). */
export async function clearContextFloor(sessionId: string): Promise<boolean> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor`,
      { method: 'DELETE' },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 Phase D — compact: the backend summarizes the messages above the
 * floor (one-shot LLM) and attaches the summary to the floor. Returns the
 * generated summary, or null on failure (LLM unavailable, nothing to compact).
 */
export async function compactContext(
  sessionId: string,
  floorMessageId: string,
): Promise<string | null> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/compact`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ floorMessageId }),
      },
    );
    if (!res.ok) return null;
    const data = (await res.json()) as { contextFloorSummary?: string };
    return typeof data.contextFloorSummary === 'string' ? data.contextFloorSummary : null;
  } catch {
    return null;
  }
}

/**
 * Tempdoc 610 §E.2 — edit the compaction summary in place. The floor is unchanged; only the stored
 * summary text is replaced (the backend reuses compactContext with the current floor message id), so
 * the corrected summary stands in for the dropped turns on the next prompt. Returns true on success.
 */
export async function editContextFloorSummary(
  sessionId: string,
  summaryText: string,
): Promise<boolean> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/context-floor/summary`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ summaryText }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 §E.3 — toggle whether a single message is excluded from the effective context. The
 * transcript still displays it; only the next prompt drops it. Returns true on success.
 */
export async function setMessageExcluded(
  sessionId: string,
  messageId: string,
  excluded: boolean,
): Promise<boolean> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(
        sessionId,
      )}/messages/${encodeURIComponent(messageId)}/exclude`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ excluded }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Tempdoc 610 §J.3 — toggle whether a retrieved source (a unit-separator-joined parentDocId+chunkIndex
 * id) is hidden from this conversation's retrieval. The Worker drops the matching chunk on subsequent
 * turns; past transcript turns are unaffected. The sourceId rides in the body (it has slashes/colons).
 */
export async function setSourceExcluded(
  sessionId: string,
  sourceId: string,
  excluded: boolean,
): Promise<boolean> {
  try {
    const res = await fetch(
      `${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/sources/exclude`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ sourceId, excluded }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Slice 513 — refresh the stable message ids for a conversation that was
 * just extended in-memory by a streaming send. The store-side ids are
 * authoritative; this fetches the latest history and returns it so the
 * caller can splice ids into its own thread without rebuilding state.
 *
 * Returns null on transport failure so callers can no-op safely.
 */
export async function fetchMessageIds(
  sessionId: string,
): Promise<Array<{ role: string; content: string; id?: string }> | null> {
  try {
    const res = await fetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}/history`);
    if (!res.ok) return null;
    const data = (await res.json()) as {
      messages?: Array<{ role?: string; content?: string; id?: string }>;
    };
    return (data.messages ?? []).map((m) => ({
      role: typeof m.role === 'string' ? m.role : '',
      content: typeof m.content === 'string' ? m.content : '',
      id: typeof m.id === 'string' ? m.id : undefined,
    }));
  } catch {
    return null;
  }
}

/**
 * Slice 513 — branch a conversation from a specific assistant message. The
 * backend creates a new sessionId whose loadHistory will lazily resolve the
 * parent prefix up to and including {@code fromMsgId}. The FE records the
 * new sessionId as a recent session and returns it so the caller can navigate
 * the chat surface into the branch.
 */
export async function branchConversation(
  parentSessionId: string,
  fromMsgId: string,
  firstMessagePreview?: string,
): Promise<string | null> {
  try {
    const url = `${apiBase}/api/chat/conversations/${encodeURIComponent(parentSessionId)}/branch`
      + `?fromMsgId=${encodeURIComponent(fromMsgId)}`;
    const res = await fetch(url, { method: 'POST' });
    if (!res.ok) return null;
    const data = (await res.json()) as { sessionId?: string };
    const newSessionId = data.sessionId;
    if (!newSessionId) return null;
    // Record as recent so the resume prompt + history dropdown surface it
    // without waiting for the next listSessions roundtrip.
    // Tempdoc 562: record the session POINTER only (no cached content); the preview is derived from the
    // lock-safe backend list. Still gated on a non-empty preview so only meaningful branches are surfaced.
    if (firstMessagePreview) recordRecentSession(newSessionId);
    return newSessionId;
  } catch {
    return null;
  }
}

export async function deleteConversation(sessionId: string): Promise<boolean> {
  try {
    const res = await fetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
    if (!res.ok) return false;
    forgetRecentSession(sessionId);
    state = {
      ...state,
      conversations: state.conversations.filter((c) => c.id !== sessionId),
      activeId: state.activeId === sessionId ? null : state.activeId,
    };
    emit();
    return true;
  } catch {
    return false;
  }
}

/**
 * Slice 517 FIX-U1 — delete with cascade support for branched conversations.
 *
 * If the backend rejects with HTTP 409 + BRANCHES_PREVENT_DELETION (the
 * slice 515 + 516 guarantee that orphaned branches can't be silently
 * created), this function:
 *  1. extracts the {@code childSessionIds} from the response body,
 *  2. invokes {@code onChildsFound(childIds)} so the caller can prompt the
 *     user "delete the children too?" (returns a {@code Promise<boolean>}),
 *  3. if the user confirms, deletes the children sequentially then retries
 *     the parent.
 *
 * Sequential (not Promise.all) — simpler error semantics, cascade depth is
 * typically 1 today, cost is bounded by N branches.
 *
 * The original {@link deleteConversation} signature is preserved for
 * backward compatibility; callers that don't need cascade UX keep using it.
 */
export interface DeleteCascadeResult {
  ok: boolean;
  childIds?: string[];
}

export async function deleteConversationWithCascade(
  sessionId: string,
  onChildsFound?: (childIds: string[]) => Promise<boolean>,
): Promise<DeleteCascadeResult> {
  try {
    const res = await fetch(`${apiBase}/api/chat/conversations/${encodeURIComponent(sessionId)}`, {
      method: 'DELETE',
    });
    if (res.ok) {
      forgetRecentSession(sessionId);
      state = {
        ...state,
        conversations: state.conversations.filter((c) => c.id !== sessionId),
        activeId: state.activeId === sessionId ? null : state.activeId,
      };
      emit();
      return { ok: true };
    }
    if (res.status === 409) {
      let childIds: string[] = [];
      try {
        const body = (await res.json()) as { childSessionIds?: string[] };
        if (Array.isArray(body.childSessionIds)) childIds = body.childSessionIds;
      } catch {
        // best-effort body parse; if it fails we still surface the 409
      }
      if (!onChildsFound) return { ok: false, childIds };
      const userConfirmed = await onChildsFound(childIds);
      if (!userConfirmed) return { ok: false, childIds };
      // Sequential cascade delete: children first, then parent. If any
      // child delete fails, abort the cascade and leave the parent intact.
      for (const childId of childIds) {
        const childResult = await deleteConversationWithCascade(childId);
        if (!childResult.ok) return { ok: false, childIds };
      }
      // Retry parent now that branches are gone.
      return deleteConversationWithCascade(sessionId);
    }
    return { ok: false };
  } catch {
    return { ok: false };
  }
}

export function exportConversationMarkdown(
  thread: Array<{ role: string; content: string }>,
  title?: string | null,
): string {
  const lines: string[] = [];
  if (title) lines.push(`# ${title}`, '');
  for (const m of thread) {
    lines.push(`**${m.role === 'user' ? 'User' : 'Assistant'}**: ${m.content}`, '');
  }
  return lines.join('\n');
}

export function __resetConversationListForTest(): void {
  state = { conversations: [], activeId: null, loading: false };
  listeners.clear();
  apiBase = '';
}
