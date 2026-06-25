/**
 * Shared mock fixture data for MSW (dev/test) and evidence capture (Playwright).
 *
 * This is the single source of truth for mock API response shapes. Both
 * `handlers.ts` (MSW) and `evidence/lib/mock-data.mjs` (Playwright) import
 * from here. Update this file when the backend response shape changes.
 *
 * Plain `.mjs` so evidence scripts (Node ESM, no build step) can import it.
 */

// ---------------------------------------------------------------------------
// /api/status
// ---------------------------------------------------------------------------

// 384: Wire format nests worker sub-records under "worker" key
export const DEFAULT_STATUS = {
  schema_version: 1,
  observed_at: new Date().toISOString(),
  status: 'ok',
  service: 'JustSearch Local API',
  uptimeMs: 60_000,
  memoryUsedBytes: 50_000_000,
  memoryMaxBytes: 100_000_000,
  indexAvailable: true,
  aiReady: true,
  embeddingReady: true,
  diskPressure: 'OK',

  // 384: Worker operational data (nested)
  worker: {
    core: {
      indexHealthy: true, indexedDocuments: 500, pendingJobs: 0,
      indexState: 'IDLE', indexSizeBytes: 10_240, pendingVduCount: 0,
      // Tempdoc 419 C3 V1/V2 P2 trend fixtures (empty by default; set per-scenario for sparkline rendering).
      recentJobQueueDepth: [], recentDocsPerSec: [],
    },
    failure: {
      failedJobs: 0, lastFailedPath: null, lastFailedErrorMessage: null,
      lastFailedAtMs: 0, nextRetryAtMs: 0, searchesZeroResultCount: 0,
      failedByFileKind: {},
    },
    migration: {
      activeGenerationId: '', activeIndexedDocuments: 500, buildingIndexedDocuments: 0,
    },
    compatibility: {
      embeddingCompatState: 'COMPATIBLE', embeddingCompatReason: 'FINGERPRINT_MATCH',
      embeddingFingerprintCurrent: 'mock-fp', embeddingFingerprintStored: 'mock-fp',
      indexSchemaFpCurrent: 'mock-schema-fp', indexSchemaFpStored: 'mock-schema-fp',
      indexSchemaCompatState: 'COMPATIBLE', reindexRequired: false, reindexRequiredReason: '',
    },
    queueDb: {
      queueDbHealthy: true, queueDbLastBackupAtMs: 0, queueDbLastQuickCheckAtMs: 0,
      queueDbLastQuickCheckOk: true, queueDbLastErrorAtMs: 0,
    },
    enrichment: {
      chunk: { chunkDocCount: 0, chunkEmbeddingCompletedCount: 0, chunkEmbeddingPendingCount: 0,
        chunkEmbeddingFailedCount: 0, chunkVectorCoveragePercent: 0, chunkVectorsReady: false },
      embeddingDocCount: 500, embeddingCompletedCount: 500, embeddingPendingCount: 0,
      embeddingFailedCount: 0, embeddingCoveragePercent: 100.0, embeddingReady: true,
      spladeDocCount: 0, spladeCompletedCount: 0, spladePendingCount: 0,
      spladeFailedCount: 0, spladeCoveragePercent: 0,
      pendingNerCount: 0, completedNerCount: 0,
      enrichmentCompleted: {}, batchTiming: { batchCount: {}, totalMs: {} },
      encoderProfiles: {
        'multilingual-e5-base': {
          calls: 100, phaseTotalUs: { encode: 400_000, tokenize: 100_000 },
          ortMinUs: 2000, ortMaxUs: 15000, ortP50Us: 4000, ortP95Us: 10000, ortP99Us: 14000,
        },
      },
    },
    gpu: {
      rerankerOrtCuda: null, embedOrtCuda: null, spladeOrtCuda: null,
      // Tempdoc 422: ner / citation / bgeM3 OrtCuda probes (closes 3-of-6 gap).
      nerOrtCuda: null, citationOrtCuda: null, bgeM3OrtCuda: null,
      embedBackend: null, embedGpuLayers: 0,
      spladeModelPath: null, rerankerModelPath: null, nerModelPath: null, nerGpuEnabled: false,
    },
    vectorFormat: {
      vectorFormatConfig: null, vectorFormatStored: null, vectorFormatActual: null,
      vectorSegmentsFloat32: 0, vectorSegmentsQuantized: 0,
    },
    telemetry: {
      contentLengthAvgChars: 0, contentLengthMinChars: 0, contentLengthMaxChars: 0,
      throughputDocsPerSec: 0, throughputWindowState: 'COLLECTING',
    },
    searchConfig: {
      chunkAwareEnabled: true, ccWeightSparse: 0.4, ccWeightDense: 0.4, ccWeightSplade: 0.2,
      branchCcWeightWhole: 0.6, branchCcWeightChunk: 0.4, branchChunkMinWeightMultiplier: 0.1,
      titleBoost: 1.5, entityBoost: 1.2, queryClassificationEnabled: true,
    },
  },

  // Grouped sub-objects (330 §4 — derived from worker sub-records)
  embedding: {
    compatState: 'COMPATIBLE', compatReason: 'FINGERPRINT_MATCH',
    fingerprintCurrent: 'mock-fp', fingerprintStored: 'mock-fp',
    ready: true, docCount: 500, completedCount: 500,
    pendingCount: 0, failedCount: 0, coveragePercent: 100.0,
  },
  schema: {
    fpCurrent: 'mock-schema-fp', fpStored: 'mock-schema-fp',
    compatState: 'COMPATIBLE', reindexRequired: false, reindexRequiredReason: '',
  },
  chunkCoverage: {
    docCount: 0, completedCount: 0, pendingCount: 0,
    failedCount: 0, coveragePercent: 0, ready: false,
  },
  queueHealth: {
    healthy: true, lastBackupAtMs: 0, lastQuickCheckAtMs: 0,
    lastQuickCheckOk: true, lastErrorAtMs: 0,
  },
  migration: {
    state: 'IDLE', buildingGenerationId: '', previousGenerationId: '',
    paused: false, pauseReason: '', switchingAgeMs: 0, switchingMaxDurationMs: 0,
    enumerator: {
      running: false, done: false, rootsTotal: 0, rootsDone: 0,
      filesSeen: 0, filesEnqueued: 0, startedAtMs: 0, finishedAtMs: 0, lastPath: '',
    },
  },

  // GPU telemetry (335 — head-side NVML)
  gpu: {
    available: false, gpuUtilizationPercent: 0, memoryUtilizationPercent: 0,
    totalVramBytes: 0, usedVramBytes: 0, freeVramBytes: 0, driverVersion: null, deviceCount: 0,
  },

  // 419 C3 V2 P0: readiness envelope. All dims default to READY so demo mode renders
  // an empty Recent Events panel. Override per scenario for V2 events to fire.
  readiness: {
    schemaVersion: 1,
    observedAt: new Date().toISOString(),
    components: {
      workerControlPlane: { state: 'READY', reasonCode: null, source: 'lifecycle_snapshot', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      indexServing:       { state: 'READY', reasonCode: null, source: 'worker_status', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      ai:                 { state: 'READY', reasonCode: null, source: 'lifecycle_inference', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      embedding:          { state: 'READY', reasonCode: null, source: 'worker_health_check', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      chunkEmbedding:     { state: 'READY', reasonCode: null, source: 'worker_status', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      lambdamartModel:    { state: 'READY', reasonCode: null, source: 'head_gpl_status', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      // Tempdoc 419 C3 V2 P1
      telemetry:          { state: 'READY', reasonCode: null, source: 'telemetry_health', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
      // Tempdoc 419 C3 V2 P3
      gpu:                { state: 'READY', reasonCode: null, source: 'gpu_saturation_monitor', observedAt: new Date().toISOString(), stale: false, stalenessMs: 0 },
    },
    composites: {
      retrieval:  { state: 'READY', reasonCodes: [] },
      aiFeatures: { state: 'READY', reasonCodes: [] },
      telemetry:  { state: 'READY', reasonCodes: [] },
    },
  },

  // RPC staleness (333)
  meta: { workerRpcAtMs: Date.now(), workerRpcStale: false },
};

// ---------------------------------------------------------------------------
// /api/settings/v2
// ---------------------------------------------------------------------------

export const DEFAULT_SETTINGS = {
  ui: {
    theme: 'dark',
    density: 'comfort',
    mode: 'simple',
    hasSeenTrustLoopNudge: true,
    excludePatterns: [],
  },
  llm: { contextWindow: 4096, maxTokens: 1024, gpuLayers: 0 },
  indexPaths: [],
};

// ---------------------------------------------------------------------------
// /api/inference/status
// ---------------------------------------------------------------------------

export const DEFAULT_INFERENCE_STATUS = {
  mode: 'offline',
  available: false,
  starting: false,
  embeddingQueueSize: 0,
  vduQueueSize: 0,
  tier: 'cpu_only',
};
