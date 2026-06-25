// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A/P-B (Slice 2) — fetch + validate the canonical thread record from
 * {@code GET /api/thread/{id}}. Kept separate from {@link ./unifiedThreadProjection} so that
 * projector stays a pure, dependency-free function (the projection-not-fork discipline).
 */
import { z } from 'zod';
import type { ThreadEvent } from './unifiedThreadProjection.js';

/** Tempdoc 561 P-A/P-A2 — the typed loop object summary (state + Turn/Iteration counts + budget). */
export interface ThreadLifecycle {
  readonly sessionId: string;
  readonly state: string;
  readonly actor: string;
  readonly turns: number;
  readonly iterations: number;
  readonly toolCalls: number;
  readonly actors: readonly string[];
  readonly budget: {
    readonly initial: number;
    readonly consumed: number;
    readonly remaining: number;
    readonly overBudget: boolean;
  };
}

/** The /api/thread response: the unified thread events + the agent runs' typed lifecycles. */
export interface ThreadResponse {
  readonly events: ThreadEvent[];
  readonly lifecycles: ThreadLifecycle[];
}

const threadEventSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
    kind: z.enum([
      'USER_MESSAGE',
      'ASSISTANT_MESSAGE',
      'TOOL_ACTIVITY',
      'PROGRESS',
      'ERROR',
      'HANDOFF',
    ]),
    originator: z.string(),
    content: z.string(),
    attributes: z.record(z.string(), z.unknown()),
  })
  .loose();

const lifecycleSchema = z
  .object({
    sessionId: z.string(),
    state: z.string(),
    actor: z.string(),
    turns: z.number(),
    iterations: z.number(),
    toolCalls: z.number(),
    actors: z.array(z.string()).default([]),
    budget: z
      .object({
        initial: z.number(),
        consumed: z.number(),
        remaining: z.number(),
        overBudget: z.boolean(),
      })
      .loose(),
  })
  .loose();

const threadResponseSchema = z
  .object({
    conversationId: z.string(),
    events: z.array(threadEventSchema),
    lifecycles: z.array(lifecycleSchema).optional(),
  })
  .loose();

const EMPTY: ThreadResponse = { events: [], lifecycles: [] };

/**
 * Fetch a conversation's canonical thread (events + the agent runs' typed lifecycles). Returns empty
 * on any failure (offline, non-200, malformed) so the caller falls back to its live state.
 */
export async function fetchUnifiedThread(
  apiBase: string,
  conversationId: string,
  signal?: AbortSignal,
): Promise<ThreadResponse> {
  try {
    const res = await fetch(`${apiBase}/api/thread/${encodeURIComponent(conversationId)}`, {
      signal,
    });
    if (!res.ok) return EMPTY;
    const parsed = threadResponseSchema.safeParse(await res.json());
    if (!parsed.success) return EMPTY;
    return {
      events: parsed.data.events as ThreadEvent[],
      lifecycles: (parsed.data.lifecycles ?? []) as ThreadLifecycle[],
    };
  } catch {
    return EMPTY;
  }
}
