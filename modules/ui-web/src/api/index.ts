// SPDX-License-Identifier: Apache-2.0
/**
 * API module exports.
 *
 * Re-exports from:
 * - domains/*: Domain-specific API functions (status, settings, indexing, search, inference, packs)
 * - http.ts: Core HTTP utilities (request, endpoint resolution)
 * - streams.ts: SSE streaming helpers
 */

// Domain modules (preferred)
export * from './domains';

// Core utilities
export * from './http';
export * from './streams';
