// SPDX-License-Identifier: Apache-2.0
/**
 * DiagnosticChannel registry primitive — a GENERATED single-authority projection (tempdoc 560 §4c).
 *
 * The catalog entry shape (`DiagnosticChannel` / `DiagnosticChannelCatalog`), its enums, the selector,
 * and the value refs all DERIVE from the generated `DiagnosticChannelWire`
 * (record→JSON-Schema→{TS,Zod}, `modules/app-agent-api/.../UIDiagnosticChannelView.java`) — retiring
 * the prior hand-mirror (the last live instance of the registry hand-mirror drift class the
 * `AuditPolicy` bug proved). The generated `diagnosticChannelWireSchema` is the runtime authority,
 * consumed at the `DiagnosticChannelCatalogClient` parse boundary.
 *
 * The `DiagnosticEvent` / `DiagnosticEventEnvelope` SSE-payload shapes stay hand-authored — they are a
 * DISTINCT streamed surface (served by the app-observability stream controller, not
 * `RegistryController`); projecting them is a separate 564-style follow-up.
 */
import { z } from 'zod';
import {
  type DiagnosticChannelWire,
  diagnosticChannelWireSchema,
} from '../generated/schema-types/diagnostic-channel';
import type { Presentation, Provenance } from './registry.js';

/** The generated runtime validator for a DiagnosticChannel entry. */
export { diagnosticChannelWireSchema };

// ============================================================
// Discriminator vocabularies — DERIVED from the generated wire
// ============================================================

/** Sub-category axis — derived from the generated wire. */
export type SubCategory = DiagnosticChannelWire['selector']['defaultSubCategory'];

/** Closed list useful for exhaustive switches. */
export const SUB_CATEGORIES: readonly SubCategory[] = [
  'CORE_DIAGNOSTIC',
  'LIBRARY_TRACE',
  'BOOT_TRACE',
  'DELIVERY_INTERNAL',
] as const;

/** Privacy class an emitted diagnostic event may carry — derived from the generated wire. */
export type DataClass = DiagnosticChannelWire['dataClasses'][number];

/** Producer-side mechanism — derived from the generated wire. */
export type ProducerKind = DiagnosticChannelWire['producer'];

/** Subscription-side delivery mode — derived from the generated wire. */
export type DeliveryMode = DiagnosticChannelWire['deliveryMode'];

/** Consumer-side trust gate — derived from the generated wire. */
export type ConsumerPermission = DiagnosticChannelWire['consumerPermission'];

// ============================================================
// Common value types — DERIVED
// ============================================================

/** DiagnosticChannel id (bare string) — derived from the generated wire. */
export type DiagnosticChannelRef = DiagnosticChannelWire['id'];

/** Selector mapping logger namespace prefixes to sub-categories — derived from the generated wire. */
export type LoggerNamespaceSelector = DiagnosticChannelWire['selector'];

// ============================================================
// DiagnosticChannel primitive — DERIVED
// ============================================================

/**
 * Wire shape of a DiagnosticChannel catalog entry — a generated projection of `UIDiagnosticChannelView`.
 * The discriminator `type:"diagnostic-channel"` is omitted from the FE type (the generated Zod still
 * validates it); the nested `presentation`/`provenance` use the FE-facing named types. `consumers`
 * (flat `{consumerId, audience}`) is now first-class — the prior hand-mirror omitted it.
 */
export type DiagnosticChannel = Omit<
  DiagnosticChannelWire,
  'type' | 'presentation' | 'provenance'
> & {
  presentation: Presentation;
  provenance: Provenance;
};

/**
 * Catalog envelope returned by `GET /api/registry/diagnostic-channels`.
 * Wire shape mirrors `RegistryController.handleDiagnosticChannels`.
 */
export interface DiagnosticChannelCatalog {
  $schema?: string;
  schemaVersion: string;
  catalogVersion: number;
  namespace: string;
  primitive: 'DiagnosticChannel';
  entries: DiagnosticChannel[];
}

/**
 * Runtime validator for the DiagnosticChannel catalog envelope (tempdoc 560 §4c parse boundary). The
 * thin envelope is hand-composed; each entry is validated by the GENERATED `diagnosticChannelWireSchema`
 * — the single runtime authority. Consumed by `DiagnosticChannelCatalogClient` via `parseWireContract`.
 */
export const diagnosticChannelCatalogSchema = z.object({
  $schema: z.string().optional(),
  schemaVersion: z.string(),
  catalogVersion: z.number(),
  namespace: z.string(),
  primitive: z.literal('DiagnosticChannel'),
  entries: z.array(diagnosticChannelWireSchema),
});

// ============================================================
// Per-event payload (SSE stream) — DEFERRED hand-authored surface
// ============================================================
// `DiagnosticEvent` / `DiagnosticEventEnvelope` are the per-emission SSE payload, served by the
// app-observability stream controller (NOT RegistryController). They are a distinct streamed/data-tier
// surface; projecting them to a generated wire is a separate 564-style follow-up. Kept hand-authored
// here (and allowlisted in the contribution-surface gate as the deferred surface).

/**
 * Single emission on a DiagnosticChannel SSE stream. Mirrors
 * `modules/app-observability/.../DiagnosticEvent.java`.
 */
export interface DiagnosticEvent {
  level: string;
  message: string;
  loggerName: string;
  threadName: string;
  threadId: number;
  /** ISO-8601 wall-clock string. */
  timestamp: string;
  mdc: Record<string, string>;
  dataClasses: DataClass[];
  subCategory: SubCategory;
}

/**
 * Wire-explicit `kind`-discriminated wrapper. Mirrors `DiagnosticEventEnvelope.java`. V1 emits
 * `kind === 'log-event'`; future diagnostic kinds extend without a breaking wire change.
 */
export interface DiagnosticEventEnvelope {
  kind: string;
  event: DiagnosticEvent;
}

/** Wire constant for V1 log-event emissions. */
export const KIND_LOG_EVENT = 'log-event';
