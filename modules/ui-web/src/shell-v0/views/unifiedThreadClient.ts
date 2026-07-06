// SPDX-License-Identifier: Apache-2.0
/**
 * Tempdoc 561 P-A/P-B (Slice 2) — fetch + validate the canonical thread record from
 * {@code GET /api/thread/{id}}. Kept separate from {@link ./unifiedThreadProjection} so that
 * projector stays a pure, dependency-free function (the projection-not-fork discipline).
 */
import { z } from 'zod';
import type { ThreadEvent } from './unifiedThreadProjection.js';

/**
 * Tempdoc S4a (risk-review finding #1) — the closed vocabulary of backend event kinds THIS build
 * recognizes. Kept as its own named array (not inlined in the Zod enum call) so
 * `scripts/ci/check-thread-event-kinds.mjs` can regex-extract it and cross-check it against the
 * backend's `InteractionEventKind` Java enum (FORWARD: every Java member appears here; BACKWARD: every
 * member here is a real Java member) — the sync check the sibling gates (`check-message-classes.mjs`,
 * `check-readiness-reason-codes.mjs`) already establish the house pattern for.
 */
const KNOWN_EVENT_KINDS = [
  'USER_MESSAGE',
  'ASSISTANT_MESSAGE',
  'TOOL_ACTIVITY',
  'PROGRESS',
  'ERROR',
  'HANDOFF',
] as const;

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

/**
 * The exact pre-S4a schema/strictness for a KNOWN-kind event — unchanged: `kind` must be one of the
 * six {@link KNOWN_EVENT_KINDS}, and originator/content/attributes are all required. A known-kind event
 * that fails this (e.g. a missing `content`) is still treated as invalid — S4a only changes the BLAST
 * RADIUS of that failure (this one event is dropped, tempdoc S4a §4c), never the strictness itself.
 */
const knownThreadEventSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
    kind: z.enum(KNOWN_EVENT_KINDS),
    originator: z.string(),
    content: z.string(),
    attributes: z.record(z.string(), z.unknown()),
  })
  .loose();

/** The minimal structural shape ANY event (known or not) must have to be worth degrading, rather than
 * dropping outright: a stable id + an authoritative order key. */
const baseEventShapeSchema = z
  .object({
    id: z.string(),
    occurredAt: z.string(),
  })
  .loose();

const KNOWN_EVENT_KIND_SET: ReadonlySet<string> = new Set(KNOWN_EVENT_KINDS);

/**
 * Tempdoc S4a (risk-review finding #1) — parse ONE wire event, forward-tolerantly:
 *
 *  - A known-kind event parses under the exact pre-S4a strict schema (unchanged rigor).
 *  - An event whose `kind` is a STRING but not one of the known kinds degrades to a generic
 *    `UNKNOWN` event (the raw kind preserved as `rawKind`) as long as the base structural fields
 *    (id/occurredAt) parse — originator/content/attributes are loose passthrough, defaulted when
 *    absent/malformed, since a not-yet-shipped backend kind's shape isn't yet known to this build.
 *  - Anything else (missing id/occurredAt, or a KNOWN kind that fails its own strict schema) is
 *    structurally invalid and returns `null` — the caller drops it and warns, but the array survives.
 *
 * Returns `null` for a dropped event; never throws.
 */
export function parseThreadEvent(raw: unknown): ThreadEvent | null {
  const known = knownThreadEventSchema.safeParse(raw);
  if (known.success) return known.data;

  const base = baseEventShapeSchema.safeParse(raw);
  if (!base.success) return null; // missing required base fields — structurally invalid, drop it

  const rawObj = raw as Record<string, unknown>;
  const kind = typeof rawObj.kind === 'string' ? rawObj.kind : undefined;
  if (kind === undefined || KNOWN_EVENT_KIND_SET.has(kind)) {
    // Either no kind at all, or a KNOWN kind that still failed the strict schema above (e.g. a missing
    // required field) — preserve known-kind strictness rather than silently degrading it to UNKNOWN.
    return null;
  }

  return {
    id: base.data.id,
    occurredAt: base.data.occurredAt,
    kind: 'UNKNOWN',
    rawKind: kind,
    originator: typeof rawObj.originator === 'string' ? rawObj.originator : '',
    content: typeof rawObj.content === 'string' ? rawObj.content : '',
    attributes:
      rawObj.attributes !== null &&
      typeof rawObj.attributes === 'object' &&
      !Array.isArray(rawObj.attributes)
        ? (rawObj.attributes as Record<string, unknown>)
        : {},
  };
}

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

/**
 * Tempdoc S4a — the top-level envelope: `events` is validated only as AN ARRAY here (each element is
 * validated individually by {@link parseThreadEvent} below), so one malformed/unrecognized-kind event
 * can no longer sink the whole response the way an array-wide `z.array(threadEventSchema)` schema did
 * (a single non-enum `kind` used to fail the ENTIRE parse — the risk-review finding this closes).
 */
const threadResponseEnvelopeSchema = z
  .object({
    conversationId: z.string(),
    events: z.array(z.unknown()),
    lifecycles: z.array(lifecycleSchema).optional(),
  })
  .loose();

const EMPTY: ThreadResponse = { events: [], lifecycles: [] };

/**
 * Fetch a conversation's canonical thread (events + the agent runs' typed lifecycles). Returns empty
 * on any failure (offline, non-200, malformed envelope) so the caller falls back to its live state.
 *
 * <p>Tempdoc S4a — event parsing is now PER-EVENT, not all-or-nothing: a structurally invalid event
 * (or a known kind that fails its own strict schema) is dropped with a single console.warn naming the
 * count; an event with an unrecognized `kind` string degrades to a generic `UNKNOWN` item rather than
 * blanking the whole thread (see {@link parseThreadEvent}).
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
    const parsed = threadResponseEnvelopeSchema.safeParse(await res.json());
    if (!parsed.success) return EMPTY;

    const events: ThreadEvent[] = [];
    let droppedCount = 0;
    for (const rawEvent of parsed.data.events) {
      const event = parseThreadEvent(rawEvent);
      if (event === null) {
        droppedCount++;
        continue;
      }
      events.push(event);
    }
    if (droppedCount > 0) {
      console.warn(
        `fetchUnifiedThread: dropped ${droppedCount} structurally invalid thread event(s) for conversation ${conversationId}`,
      );
    }

    return {
      events,
      lifecycles: (parsed.data.lifecycles ?? []) as ThreadLifecycle[],
    };
  } catch {
    return EMPTY;
  }
}
