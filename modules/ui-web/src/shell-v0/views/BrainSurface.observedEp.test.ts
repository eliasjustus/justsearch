/**
 * Tempdoc 805 G.3 W-TRUTH — the Brain surface's observed-EP presentation.
 *
 * Round 11 (tempdoc 734 R11-F3): an upgraded machine lost GPU for every ONNX encoder while
 * `/api/ai/runtime/status` reported the feature "active" with a live session. The status response
 * now carries the observed execution provider beside that intent; these tests pin what the surface
 * says about it — and, crucially, that it says nothing positive when there is no observation.
 */

import { describe, expect, it } from 'vitest';
import { observedEpLabel, shouldHintRepairForGpuFallback } from './BrainSurface';
import type { AiRuntimeStatus, InstallStatus } from '../state/aiStateStore.js';

describe('observedEpLabel', () => {
  it('names CUDA when the session actually runs on GPU', () => {
    expect(observedEpLabel({ id: 'reranker', modelActive: true, executionProvider: 'cuda' })).toBe('CUDA');
  });

  it('names the fallback AND its reason — the round-11 case', () => {
    expect(
      observedEpLabel({
        id: 'reranker',
        status: 'active',
        modelActive: true,
        executionProvider: 'cpu',
        gpuFallback: true,
        fallbackReason: 'ORT CUDA native pack incomplete (missing: onnxruntime_providers_cuda.dll)',
      }),
    ).toBe('CPU (fallback: ORT CUDA native pack incomplete (missing: onnxruntime_providers_cuda.dll))');
  });

  it('says CPU plainly when CPU is the intent (citation-scorer is CPU-only by design)', () => {
    expect(observedEpLabel({ id: 'citation_scorer', modelActive: true, executionProvider: 'cpu' })).toBe('CPU');
  });

  it('falls back to the intent wording when nothing has been observed yet', () => {
    expect(observedEpLabel({ id: 'reranker', modelActive: true })).toBe('active');
    expect(observedEpLabel({ id: 'reranker', modelActive: false, executionProvider: 'unknown' })).toBe('inactive');
  });

  it('reports a fallback even without a reason string', () => {
    expect(observedEpLabel({ id: 'reranker', executionProvider: 'cpu', gpuFallback: true })).toBe(
      'CPU (GPU fallback)',
    );
  });
});

describe('shouldHintRepairForGpuFallback', () => {
  const fallbackRuntime: AiRuntimeStatus = {
    onnxFeatures: [{ id: 'reranker', modelActive: true, executionProvider: 'cpu', gpuFallback: true }],
  };
  const healthyRuntime: AiRuntimeStatus = {
    onnxFeatures: [{ id: 'reranker', modelActive: true, executionProvider: 'cuda' }],
  };
  const repairable: InstallStatus = {
    state: 'idle',
    phase: 'idle',
    installedFully: true,
    repairNeeded: true,
  };
  const complete: InstallStatus = {
    state: 'idle',
    phase: 'idle',
    installedFully: true,
    repairNeeded: false,
  };

  it('hints when an observed fallback coincides with a repairable install gap', () => {
    expect(shouldHintRepairForGpuFallback(fallbackRuntime, repairable)).toBe(true);
  });

  it('stays silent when nothing is missing — a fallback no download can fix is not a repair prompt', () => {
    expect(shouldHintRepairForGpuFallback(fallbackRuntime, complete)).toBe(false);
  });

  it('stays silent when a file is missing but nothing observably fell back', () => {
    expect(shouldHintRepairForGpuFallback(healthyRuntime, repairable)).toBe(false);
  });

  it('stays silent while either half is unknown', () => {
    expect(shouldHintRepairForGpuFallback(null, repairable)).toBe(false);
    expect(shouldHintRepairForGpuFallback(fallbackRuntime, null)).toBe(false);
  });
});
