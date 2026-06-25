// @vitest-environment node
/**
 * projectFact() — the Display-value authority (tempdoc 594 §9.2). These tests pin the value
 * projection + the TERNARY presence (present / absent / unknown) over seeded observed-state, so a
 * factual chip can never render a fabricated or host-wrong value (594 §1, §11.3 #3).
 */
import { describe, it, expect } from 'vitest';
import { projectFact, isFact } from './facts';
import type { AiState } from '../state/aiStateStore';
import { known } from '../state/known';

/** projectFact only reads `aiState.status`, so a partial cast is sufficient + type-honest here. */
function withStatus(status: unknown): AiState {
  return { status } as unknown as AiState;
}

describe('projectFact — build-time constant (embedding dimension)', () => {
  it('is always present with the SSOT-generated dimension, regardless of aiState', () => {
    expect(projectFact('core.embed.dim', null)).toEqual({
      name: 'Embeddings',
      value: '768-d',
      presence: 'present',
    });
    expect(projectFact('core.embed.dim', withStatus(null)).value).toBe('768-d');
  });
});

describe('projectFact — GPU accelerator (587 host fact + provenance)', () => {
  it('UNKNOWN when status has not been polled', () => {
    expect(projectFact('core.gpu.accel', null).presence).toBe('unknown');
    expect(projectFact('core.gpu.accel', withStatus(null)).presence).toBe('unknown');
  });
  it('ABSENT (chip omitted) when the host has no GPU — never "GPU cuda12" on a CPU box', () => {
    expect(projectFact('core.gpu.accel', withStatus({ gpu: { available: false } })).presence).toBe(
      'absent',
    );
  });
  it('PRESENT "GPU CUDA" with provenance when CUDA is functional', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } }),
    );
    expect(f.presence).toBe('present');
    expect(f.value).toBe('CUDA');
    expect(f.provenance).toBe('via NVML · high confidence');
  });
  it('PRESENT "GPU" (name-only) when a GPU exists but CUDA is not functional', () => {
    const f = projectFact('core.gpu.accel', withStatus({ gpu: { available: true, cudaFunctional: false } }));
    expect(f.presence).toBe('present');
    expect(f.value).toBeNull();
  });
});

describe('projectFact — structured confidence (§11.3 #2)', () => {
  it('carries high confidence for an NVML-sourced GPU', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvml', confidence: 'HIGH' } }),
    );
    expect(f.confidence).toBe('high');
  });
  it('carries LOW confidence for an nvidia-smi-sourced GPU (so the chip can mark uncertainty)', () => {
    const f = projectFact(
      'core.gpu.accel',
      withStatus({ gpu: { available: true, cudaFunctional: true, source: 'nvidia-smi', confidence: 'LOW' } }),
    );
    expect(f.confidence).toBe('low');
  });
  it('non-GPU facts carry NO confidence field', () => {
    const f = projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: true } } }));
    expect(f.confidence).toBeUndefined();
  });
});

describe('projectFact — capability presence (SPLADE/NER/reranker)', () => {
  it('SPLADE present (name-only) / absent (omitted) / unknown', () => {
    expect(projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: true } } }))).toMatchObject(
      { presence: 'present', value: null },
    );
    expect(
      projectFact('core.splade', withStatus({ worker: { enrichment: { spladeEnabled: false } } })).presence,
    ).toBe('absent');
    expect(projectFact('core.splade', withStatus({ worker: { enrichment: {} } })).presence).toBe('unknown');
  });
  it('reranker present when a model path is configured, absent otherwise', () => {
    expect(
      projectFact('core.reranker', withStatus({ worker: { gpu: { rerankerModelPath: '/m/rr.onnx' } } })).presence,
    ).toBe('present');
    expect(projectFact('core.reranker', withStatus({ worker: { gpu: {} } })).presence).toBe('absent');
  });
});

describe('projectFact — vector precision', () => {
  it('humanizes the wire format token', () => {
    expect(projectFact('core.vector.precision', withStatus({ worker: { vectorFormat: { vectorFormatActual: 'FLOAT32' } } }))).toMatchObject(
      { name: 'Vectors', value: 'Float32', presence: 'present' },
    );
  });
});

describe('projectFact — unknown id + isFact', () => {
  it('an unregistered id yields unknown presence, never a fabricated value', () => {
    expect(projectFact('core.nope', withStatus({}))).toEqual({ name: 'core.nope', value: null, presence: 'unknown' });
  });
  it('isFact recognizes the catalog', () => {
    expect(isFact('core.embed.dim')).toBe(true);
    expect(isFact('core.nope')).toBe(false);
  });
});

describe('projectFact — status metric-facts (§17.2 migration off the bypass shells)', () => {
  const withState = (s: unknown): AiState => s as AiState;
  it('core.files formats the count, unknown when no index', () => {
    expect(
      projectFact('core.files', withState({ index: { documentCount: known(605) } })),
    ).toMatchObject({ name: 'Files', value: '605', presence: 'present' });
    expect(projectFact('core.files', withState({})).presence).toBe('unknown');
  });
  it('core.size formats bytes, unknown when no status', () => {
    expect(
      projectFact('core.size', withState({ status: { worker: { core: { indexSizeBytes: 53_477_376 } } } })).value,
    ).toBe('51.0 MB');
    expect(projectFact('core.size', withState({ status: null })).presence).toBe('unknown');
  });
  it('core.memory formats bytes', () => {
    expect(projectFact('core.memory', withState({ status: { memoryUsedBytes: 238_026_752 } })).value).toBe('227.0 MB');
  });
  it('metric-facts carry NO confidence/provenance (value-only — render plainly, §17.3)', () => {
    const f = projectFact('core.files', withState({ index: { documentCount: known(1) } }));
    expect(f.confidence).toBeUndefined();
    expect(f.provenance).toBeUndefined();
  });
});
