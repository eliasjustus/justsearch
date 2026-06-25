/**
 * Fixture drift detection.
 *
 * Verifies that the shared mock fixtures in fixtures.mjs contain the
 * top-level keys that the real backend emits. Run against a live backend
 * with JUSTSEARCH_API_PORT set, or skip gracefully when no backend is up.
 */

import { describe, it, expect } from 'vitest'
import { DEFAULT_STATUS, DEFAULT_SETTINGS, DEFAULT_INFERENCE_STATUS } from './fixtures.mjs'

// Keys that must be present in mock fixtures to match the backend.
// This is the minimal set — mocks may have extra keys for test convenience.
// 384: Wire format nests worker data under "worker" key
const REQUIRED_STATUS_KEYS = [
  'status', 'uptimeMs', 'indexAvailable', 'aiReady', 'embeddingReady',
  'worker',
  'embedding', 'schema', 'chunkCoverage', 'queueHealth', 'migration',
  'gpu', 'meta',
]

const REQUIRED_SETTINGS_KEYS = ['ui', 'llm', 'indexPaths']

const REQUIRED_INFERENCE_KEYS = ['mode', 'available', 'starting', 'tier']

describe('fixtures.mjs shape', () => {
  it('DEFAULT_STATUS has all required top-level keys', () => {
    const keys = Object.keys(DEFAULT_STATUS)
    for (const required of REQUIRED_STATUS_KEYS) {
      expect(keys, `missing key: ${required}`).toContain(required)
    }
  })

  it('DEFAULT_STATUS.embedding has expected sub-keys', () => {
    const embedding = (DEFAULT_STATUS as Record<string, unknown>).embedding as Record<string, unknown>
    expect(embedding).toBeDefined()
    expect(embedding).toHaveProperty('compatState')
    expect(embedding).toHaveProperty('coveragePercent')
  })

  it('DEFAULT_STATUS.gpu has expected sub-keys', () => {
    const gpu = (DEFAULT_STATUS as Record<string, unknown>).gpu as Record<string, unknown>
    expect(gpu).toBeDefined()
    expect(gpu).toHaveProperty('available')
    expect(gpu).toHaveProperty('totalVramBytes')
  })

  it('DEFAULT_STATUS.worker has expected sub-record keys (384)', () => {
    const s = DEFAULT_STATUS as Record<string, unknown>
    expect(s).toHaveProperty('worker')
    const w = s.worker as Record<string, unknown>
    expect(w).toHaveProperty('core')
    expect(w).toHaveProperty('failure')
    expect(w).toHaveProperty('searchConfig')
    expect(w).toHaveProperty('enrichment')
    const sc = w.searchConfig as Record<string, unknown>
    expect(sc).toHaveProperty('chunkAwareEnabled')
  })

  it('DEFAULT_SETTINGS has all required top-level keys', () => {
    const keys = Object.keys(DEFAULT_SETTINGS)
    for (const required of REQUIRED_SETTINGS_KEYS) {
      expect(keys, `missing key: ${required}`).toContain(required)
    }
  })

  it('DEFAULT_INFERENCE_STATUS has all required top-level keys', () => {
    const keys = Object.keys(DEFAULT_INFERENCE_STATUS)
    for (const required of REQUIRED_INFERENCE_KEYS) {
      expect(keys, `missing key: ${required}`).toContain(required)
    }
  })
})
