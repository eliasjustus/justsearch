// SPDX-License-Identifier: Apache-2.0
/**
 * Zod schemas for runtime boundary validation of API responses.
 *
 * These schemas are intentionally permissive (.loose()) to avoid breaking
 * on additive backend changes. They focus on validating the fields the UI actually uses.
 *
 * Usage:
 * - In dev mode: log rich diagnostics on schema mismatch
 * - In prod mode: fail gracefully with a user-safe error message
 */

import { z } from 'zod';

// ==================== Settings v2 ====================

const UiSettingsV2Schema = z
  .object({
    theme: z.enum(['system', 'dark', 'light']).optional().nullable(),
    highContrast: z.boolean().optional().nullable(),
    density: z.enum(['compact', 'comfort', 'rich']).optional().nullable(),
    defaultAction: z.enum(['open', 'reveal', 'preview']).optional().nullable(),
    inspectorWidth: z.number().optional().nullable(),
    pauseIndexingDuringAi: z.boolean().optional().nullable(),
    mode: z.enum(['simple', 'advanced']).optional().nullable(),
    hasSeenTrustLoopNudge: z.boolean().optional().nullable(),
    excludePatterns: z.array(z.string()).optional().nullable(),
    vimMode: z.boolean().optional().nullable(),
  })
  .loose();

const LlmSettingsV2Schema = z
  .object({
    serverExecutable: z.string().optional().nullable(),
    contextWindow: z.number().optional().nullable(),
    maxTokens: z.number().optional().nullable(),
    gpuLayers: z.number().optional().nullable(),
    modelPath: z.string().optional().nullable(),
    llamaLibPath: z.string().optional().nullable(),
  })
  .loose();

export const SettingsV2Schema = z
  .object({
    ui: UiSettingsV2Schema.optional().nullable(),
    llm: LlmSettingsV2Schema.optional().nullable(),
    indexPaths: z.array(z.string()).optional().nullable(),
    settingsMode: z.enum(['read_write', 'in_memory']).optional().nullable(),
  })
  .loose();


// ==================== Error Envelope ====================
// Wire shape for REST + summary SSE error responses (per docs/reference/api-contract-map.md
// §Error Response Format). Agent SSE error events have a different shape (retryAction +
// retryAttempt instead of retryable; no i18nKey on the wire — derived locally as
// "errors." + errorCode). The optional fields below cover both surfaces.
//
// Tempdoc 431 added the `i18nKey` field; consumers prefer it over the legacy
// `"errors." + errorCode` derivation when present.
export const ErrorEnvelopeSchema = z
  .object({
    error: z.string(),
    errorCode: z.string().optional(),
    errorClass: z.string().optional(),
    retryable: z.boolean().optional(),
    i18nKey: z.string().optional(),
    retryAction: z.string().optional(),
    retryAttempt: z.number().optional(),
    requestId: z.string().optional(),
  })
  .loose();

// ==================== Search Response ====================

// 380: Sub-schemas for complex nested types on SearchHit.

const MatchSpanSchema = z.object({
  field: z.string(),
  startChar: z.number(),
  endChar: z.number(),
  term: z.string().optional(),
}).loose();

const ExcerptRegionSchema = z.object({
  text: z.string(),
  startChar: z.number(),
  endChar: z.number(),
  approxLine: z.number(),
  matchSpans: z.array(MatchSpanSchema),
}).loose();

// Tempdoc 549 Phase E2: HitProvenanceSchema retired — per-hit ranking provenance is the trace slice.

const EntityVariantBreakdownSchema = z.object({
  canonicalForm: z.string(),
  totalCount: z.number(),
  variants: z.record(z.string(), z.number()),
}).loose();

// 380: All fields camelCase to match SearchHit interface after casing normalization.
const SearchHitSchema = z
  .object({
    docId: z.string(),
    score: z.number(),
    title: z.string().optional(),
    path: z.string().optional(),
    meta: z.string().optional(),
    mime: z.string().optional(),
    mimeBase: z.string().optional(),
    fileKind: z.string().optional(),
    language: z.string().optional(),
    modifiedAt: z.string().optional(),
    sizeBytes: z.string().optional(),
    contentPreview: z.string().optional(),
    collection: z.string().optional(),
    highlights: z.record(z.string(), z.array(z.string())).optional(),
    matchedFields: z.array(z.string()).optional(),
    matchSpans: z.array(MatchSpanSchema).optional(),
    excerptRegions: z.array(ExcerptRegionSchema).optional(),
    metaSource: z.string().optional(),
    metaAuthor: z.string().optional(),
    metaCategory: z.string().optional(),
    metaPublishedAt: z.string().optional(),
  })
  .loose();

export const IndexCapabilitiesSchema = z
  .object({
    embeddingCoverage: z.number().nullable().optional(),
    spladeCoverage: z.number().nullable().optional(),
    chunkEmbeddingCoverage: z.number().nullable().optional(),
    crossEncoderAvailable: z.boolean().nullable().optional(),
  })
  .loose();

export type IndexCapabilities = z.infer<typeof IndexCapabilitiesSchema>;

// Tempdoc 549 Phase E3: PipelineExecutionSchema retired — per-stage timing + component status
// are on the unified searchTrace.
// Tempdoc 549 Phase E4: SearchIntrospectionSchema retired — superseded by SearchTrace.
// Tempdoc 564 Phase 2: the empty `SearchTraceSchema = z.object({}).loose()` (a "validates
// nothing" placeholder) is removed. The SearchTrace is now the generated record→JSON-Schema→Zod
// projection (the generated search-trace module), validated at the searchState parse boundary; the
// ergonomic `SearchResponse` (api/domains/search.ts) no longer carries searchTrace (549 E4), so the
// field is dropped here too — this schema validates only the mapper output shape.

export const SearchResponseSchema = z
  .object({
    hits: z.array(SearchHitSchema),
    totalHits: z.number(),
    queryTimeMs: z.number().optional(),
    nextCursor: z.string().optional(),
    facets: z.record(z.string(), z.record(z.string(), z.number())).optional(),
    facetsTruncated: z.boolean().optional(),
    // Tempdoc 549 U4 (Slice 6): flat correction* fields removed; carried by introspection.
    entityFacetVariants: z.record(z.string(), z.array(EntityVariantBreakdownSchema)).optional(),
    indexCapabilities: IndexCapabilitiesSchema.nullable().optional(),
  })
  .loose();


// ==================== Agent ====================

// Tempdoc 564 Phase 3: the sessions/history LIST surfaces are now record-backed (app-api
// AgentSessionsResponse / AgentHistoryResponse) and validated against the generated
// record→JSON-Schema→Zod projection (`generated/schema-types/agent-*`) at the parse boundary in
// `api/domains/agent.ts`. The fail-open `.loose()` AgentBatchSummarySchema / AgentHistoryResponseSchema
// / AgentSessionsResponseSchema are retired. The session SNAPSHOT (full free-form meta) remains on the
// hand schema below pending its own record migration (named follow-up).

// Tempdoc 415 follow-up (C20): persisted-session list + detail.

const AgentTerminationReasonSchema = z
  .object({
    disposition: z.string(),
    errorCode: z.string().nullable().optional(),
    cancelTrigger: z.string().nullable().optional(),
  })
  .loose();

export const AgentSessionSummarySchema = z
  .object({
    sessionId: z.string(),
    startedAt: z.string().optional(),
    updatedAt: z.string().optional(),
    state: z.string(),
    resumable: z.boolean(),
    iterationsUsed: z.number().optional(),
    toolCallsExecuted: z.number().optional(),
    totalTokensUsed: z.number().optional(),
    activeAgentId: z.string().nullable().optional(),
    terminationReason: AgentTerminationReasonSchema.nullable().optional(),
    preview: z.string().optional(),
  })
  .loose();

export const AgentSessionSnapshotSchema = AgentSessionSummarySchema.extend({
  messages: z.array(z.record(z.string(), z.unknown())).optional(),
  agentProfiles: z.array(z.record(z.string(), z.unknown())).optional(),
  selectedToolNames: z.array(z.string()).optional(),
  maxIterations: z.number().optional(),
  initialBudget: z.number().optional(),
  handoffHistory: z.array(z.record(z.string(), z.unknown())).optional(),
}).loose();


// ==================== Validation Helpers ====================

// Detect dev mode safely (Vite sets this via import.meta.env)
const IS_DEV = (() => {
  try {
     
    return (import.meta as any).env?.DEV === true;
  } catch {
    return false;
  }
})();

type ValidationResult<T> =
  | { success: true; data: T }
  | { success: false; error: z.ZodError; data: T | null };

/**
 * Validates data against a Zod schema with permissive handling.
 *
 * In dev mode: logs detailed diagnostics on validation failure.
 * In prod mode: silently continues with the original data (fail open).
 *
 * @param schema - Zod schema to validate against
 * @param data - Data to validate
 * @param context - Context string for logging (e.g., "GET /api/status")
 * @returns The validated data (or original data on failure in prod)
 */
export function validateWithFallback<T>(
  schema: z.ZodType<T>,
  data: unknown,
  context: string
): T {
  const result = schema.safeParse(data);
  if (result.success) {
    return result.data;
  }

  if (IS_DEV) {
    console.warn(
      `[Schema Validation] ${context} failed validation:`,
      result.error.format()
    );
    console.warn('[Schema Validation] Original data:', data);
  }

  // Fail open: return original data as-is (casted)
  return data as T;
}

/**
 * Tempdoc 564 — the faithful wire-contract parse boundary.
 *
 * Unlike {@link validateWithFallback} (a *.loose()* hand-Zod that fails open
 * silently), this validates a raw wire response against a Zod schema *generated
 * from the canonical JSON Schema* of the Java record. A mismatch is a real
 * contract violation, so it is logged LOUDLY with a stable `[WireContract]`
 * prefix — observable, not swallowed (the 564 browser gate asserts this error
 * is absent against a live backend). It still returns the data (the downstream
 * mapper is defensive) so a contract drift degrades rather than crashes.
 *
 * @returns the parsed data on success, or the original data (cast) on mismatch.
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
  console.error(
    `[WireContract] ${context} did not match the generated schema (contract drift):`,
    result.error.issues
  );
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
