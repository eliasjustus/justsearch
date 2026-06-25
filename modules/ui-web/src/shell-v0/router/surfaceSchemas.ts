// SPDX-License-Identifier: Apache-2.0
/**
 * Surface-state-schema registry — slice 489 §5 FE side.
 *
 * The schemas are sourced from the backend Surface catalog at
 * `GET /api/registry/surfaces` (each Surface entry carries its own
 * `stateSchema: { schema, bindings }` per the round-5 ratification).
 * This module caches them, resolves abstract storeIds to concrete
 * StoreAdapters via {@link storeRegistry}, and exposes the resolved
 * shape to the projector / hydrator.
 *
 * Per slice 489 §17.2: the field is optional on Surface. Surfaces
 * without a schema simply don't appear in this registry; their
 * activation falls back to default state.
 */

import { getStore, type StoreAdapter } from './storeRegistry.js';

export interface RawStateBinding {
  schemaPath: string;
  storeId: string;
  storeKey: string;
}

export interface RawSurfaceStateSchema {
  schema: string;
  bindings: RawStateBinding[];
}

/** Resolved schema — each binding paired with its concrete StoreAdapter. */
export interface ResolvedSchema {
  surfaceId: string;
  /** Distinct adapters referenced by this surface's bindings (one per storeId). */
  adapters: StoreAdapter[];
  /** Per-binding pairing: schema field path → adapter + storeKey. */
  fields: Array<{ schemaPath: string; storeKey: string; adapter: StoreAdapter }>;
}

const schemas = new Map<string, RawSurfaceStateSchema>();

/** Register a raw schema (called by the hydrator after fetching /api/registry/surfaces). */
export function registerSurfaceStateSchema(
  surfaceId: string,
  schema: RawSurfaceStateSchema,
): void {
  schemas.set(surfaceId, schema);
}

/** Read the raw schema for a surface, if any. */
export function getSurfaceStateSchema(
  surfaceId: string,
): RawSurfaceStateSchema | undefined {
  return schemas.get(surfaceId);
}

/** Test-only: clear the registry. */
export function __resetSurfaceSchemasForTest(): void {
  schemas.clear();
}

/**
 * Resolve a raw schema's `storeId` bindings to live {@link StoreAdapter}
 * instances via the {@link storeRegistry}. Bindings naming an unregistered
 * storeId are silently dropped (the surface still activates; missing-binding
 * state remains at default). A WARN is logged so the gap is visible during
 * development.
 */
export function resolveSurfaceStateSchema(raw: RawSurfaceStateSchema): ResolvedSchema {
  // surfaceId isn't on the raw schema — it's keyed by surfaceId in the registry.
  // For the resolved view we leave it blank when called directly (test helpers);
  // production callers (the projector / hydrator) thread it through separately.
  const fields: ResolvedSchema['fields'] = [];
  const adapterSet = new Map<string, StoreAdapter>();
  for (const b of raw.bindings) {
    const adapter = getStore(b.storeId);
    if (!adapter) {
      // Surface declares a binding to a storeId the FE doesn't have yet —
      // log once and drop. The surface keeps activating; the missing binding
      // just won't participate in URL projection/hydration.
      // eslint-disable-next-line no-console
      console.warn(
        `[surfaceSchemas] no registered store for storeId="${b.storeId}" ` +
          `(binding schemaPath="${b.schemaPath}"); dropping`,
      );
      continue;
    }
    adapterSet.set(b.storeId, adapter);
    fields.push({
      schemaPath: b.schemaPath,
      storeKey: b.storeKey,
      adapter,
    });
  }
  return {
    surfaceId: '',
    adapters: Array.from(adapterSet.values()),
    fields,
  };
}
