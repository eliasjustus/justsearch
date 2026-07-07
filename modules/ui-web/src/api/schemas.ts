// SPDX-License-Identifier: Apache-2.0
/**
 * Zod schemas for runtime boundary validation of API responses, plus the ONE
 * wire-contract parse boundary (`parseWireContract`).
 *
 * Posture (tempdoc 683): a wire-contract mismatch THROWS in dev (impossible to
 * miss) and degrades in prod (console.error + the `wireDriftTelemetry` ring).
 *
 * The one hand schema that remains here is the deliberately permissive
 * (.loose()) agent-session snapshot detail; every record-backed wire surface
 * validates against its generated record→JSON-Schema→Zod projection in
 * `./generated/schema-types/`.
 */

import { z } from 'zod';
import { isDevMode } from './devMode';
import { recordWireDrift } from './wireDriftTelemetry';
import { agentSessionsResponseSchema } from './generated/schema-types/agent-sessions-response';

// Tempdoc 683 (X3): the hand SettingsV2Schema / UiSettingsV2Schema / LlmSettingsV2Schema are
// deleted — /api/settings/v2 validates against the generated record→JSON-Schema→Zod projection
// (`./generated/schema-types/settings-v2`) at the parse boundary in `api/domains/settings.ts`.
// Tempdoc 683: ErrorEnvelopeSchema deleted — zero runtime consumers.
// Tempdoc 683: the Search Response hand schemas (SearchResponseSchema, SearchHitSchema,
// IndexCapabilitiesSchema, …) are deleted — the generated `knowledgeSearchResponseSchema`
// (record → JSON Schema → Zod) is the live parse boundary in `api/domains/search.ts`, making
// the redundant fail-open post-map validation a drift liability, not a guard.

// ==================== Agent ====================

// Tempdoc 564 Phase 3: the sessions/history LIST surfaces are now record-backed (app-api
// AgentSessionsResponse / AgentHistoryResponse) and validated against the generated
// record→JSON-Schema→Zod projection (`generated/schema-types/agent-*`) at the parse boundary in
// `api/domains/agent.ts`. The fail-open `.loose()` AgentBatchSummarySchema / AgentHistoryResponseSchema
// / AgentSessionsResponseSchema are retired. The session SNAPSHOT (full free-form meta) remains on the
// hand schema below pending its own record migration (named follow-up).

// Tempdoc 415 follow-up (C20): persisted-session detail.
// Tempdoc 683: the hand AgentSessionSummarySchema is deleted — the summary authority is the
// generated sessions LIST item (`agentSessionsResponseSchema.shape.sessions` element). The
// snapshot below extends THAT, so the summary half can no longer drift from the wire record.

/** The generated per-session summary item (the element of AgentSessionsResponse.sessions). */
const agentSessionSummaryItemSchema = agentSessionsResponseSchema.shape.sessions
  .unwrap()
  .unwrap().element;

// Deliberately `.loose()`: the snapshot's messages/profiles maps are free-form by design
// (pending the snapshot's own record migration — named follow-up in tempdoc 564 Phase 3).
export const AgentSessionSnapshotSchema = agentSessionSummaryItemSchema
  .extend({
    messages: z.array(z.record(z.string(), z.unknown())).optional(),
    agentProfiles: z.array(z.record(z.string(), z.unknown())).optional(),
    selectedToolNames: z.array(z.string()).optional(),
    maxIterations: z.number().optional(),
    initialBudget: z.number().optional(),
    handoffHistory: z.array(z.record(z.string(), z.unknown())).optional(),
  })
  .loose();


// ==================== Validation Helpers ====================

type ValidationResult<T> =
  | { success: true; data: T }
  | { success: false; error: z.ZodError; data: T | null };

// Tempdoc 683: validateWithFallback deleted — its last consumer (the agent-session snapshot)
// now goes through parseWireContract; one boundary, one posture.

/**
 * Tempdoc 564 — the faithful wire-contract parse boundary.
 *
 * Validates a raw wire response against a Zod schema *generated from the
 * canonical JSON Schema* of the Java record. A mismatch is a real contract
 * violation. Posture (tempdoc 683):
 *
 * - **dev**: THROWS an Error carrying the context + the Zod issues — drift is
 *   a hard failure at the boundary, impossible to miss.
 * - **prod**: logs LOUDLY with the stable `[WireContract]` prefix (the 564
 *   browser gate asserts this error is absent against a live backend), records
 *   the event in the {@link recordWireDrift} ring, and returns the raw data
 *   (the downstream mapper is defensive) so drift degrades rather than crashes.
 *
 * @returns the parsed data on success, or (prod only) the original data (cast) on mismatch.
 */
export function parseWireContract<T>(
  schema: z.ZodType<T>,
  data: unknown,
  context: string
): T {
  const result = schema.safeParse(data);
  if (result.success) {
    return result.data;
  }
  if (isDevMode()) {
    throw new Error(
      `[WireContract] ${context} did not match the generated schema (contract drift): ` +
        JSON.stringify(result.error.issues)
    );
  }
  console.error(
    `[WireContract] ${context} did not match the generated schema (contract drift):`,
    result.error.issues
  );
  recordWireDrift(context, result.error.issues.length);
  return data as T;
}

/**
 * Validates data and returns a detailed result for custom handling.
 *
 * @param schema - Zod schema to validate against
 * @param data - Data to validate
 * @returns Validation result with success flag, data, and error (if any)
 */
export function validate<T>(
  schema: z.ZodType<T>,
  data: unknown
): ValidationResult<T> {
  const result = schema.safeParse(data);
  if (result.success) {
    return { success: true, data: result.data };
  }
  return { success: false, error: result.error, data: data as T | null };
}
