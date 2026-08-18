/**
 * Tempdoc 840 Phase 5 — the composers behind the user-facing install.
 *
 * These pin the two properties the phase exists for and that a render test cannot state as sharply:
 * an UNKNOWN rate/horizon is OMITTED rather than zeroed, and `unavailable` is not `declined`.
 */

import { describe, expect, it } from 'vitest';
import {
  NECESSITY_CONSEQUENCE,
  composeComponentGroups,
  composeComponentRow,
  composeStageRows,
  composeTransferLine,
  friendlyInstallMessage,
  searchReadyNotice,
  toNecessity,
  type InstallComponentEstimate,
} from './installComponents.js';
import type { AiInstallStatus } from '../../api/generated/schema-types/ai-install-status.js';

const EMBEDDING: InstallComponentEstimate = {
  id: 'embedding',
  label: 'Search embeddings',
  description: 'Turns your documents into vectors so search can find them by meaning.',
  tier: 'retrieval-core',
  necessity: 'required',
  declinable: false,
  declined: false,
  totalBytes: 1_200_000_000,
  downloadBytes: 1_200_000_000,
  state: 'to-download',
};

const RERANKER: InstallComponentEstimate = {
  id: 'reranker',
  label: 'Search reranker',
  description: 'Re-orders the top results so the best answer is first.',
  tier: 'retrieval-enrichment',
  necessity: 'improves-results',
  declinable: true,
  declined: false,
  totalBytes: 340_000_000,
  downloadBytes: 340_000_000,
  state: 'to-download',
};

const CUDA: InstallComponentEstimate = {
  id: 'cuda-runtime',
  label: 'GPU runtime libraries',
  description: 'CUDA libraries the GPU models run on.',
  tier: 'runtime',
  necessity: 'infrastructure',
  declinable: false,
  declined: false,
  totalBytes: 2_000_000_000,
  downloadBytes: 0,
  state: 'unavailable',
  unavailableReason: 'No CUDA-capable GPU was detected on this machine.',
};

const CHAT: InstallComponentEstimate = {
  id: 'chat',
  label: 'Chat model',
  description: 'Answers questions and writes summaries about your documents.',
  tier: 'llm',
  necessity: 'adds-feature',
  declinable: true,
  declined: true,
  totalBytes: 6_400_000_000,
  downloadBytes: 0,
  state: 'declined',
};

describe('composeTransferLine — an unknown segment is omitted, never zeroed', () => {
  it('omits BOTH segments when the wire says unknown (-1), rather than reading "0 B/s · 0s left"', () => {
    expect(composeTransferLine({ bytesPerSecond: -1, remainingSeconds: -1 })).toBeNull();
  });

  it('omits the rate but keeps a known horizon (each segment stands or falls alone)', () => {
    expect(composeTransferLine({ bytesPerSecond: -1, remainingSeconds: 125 })).toBe('~2m 5s left');
  });

  it('omits the horizon but keeps a known rate', () => {
    expect(composeTransferLine({ bytesPerSecond: 2_097_152, remainingSeconds: -1 })).toBe('2.0 MB/s');
  });

  it('renders both when both are measured', () => {
    expect(composeTransferLine({ bytesPerSecond: 1_048_576, remainingSeconds: 90 })).toBe(
      '1.0 MB/s · ~1m 30s left',
    );
  });

  it('treats a 0 rate as unknown — a young transfer must not be described as stopped', () => {
    expect(composeTransferLine({ bytesPerSecond: 0, remainingSeconds: -1 })).toBeNull();
  });

  it('keeps a 0 HORIZON — "nothing left to move" is a value, unlike a 0 rate', () => {
    expect(composeTransferLine({ bytesPerSecond: -1, remainingSeconds: 0 })).toBe('~0s left');
  });

  it('omits everything for an absent status (no run, no fabricated numbers)', () => {
    expect(composeTransferLine(null)).toBeNull();
    expect(composeTransferLine({})).toBeNull();
  });
});

describe('composeComponentRow — opt-out, and unavailable is not declined', () => {
  it('starts every supported component SELECTED (opt-out, not opt-in)', () => {
    expect(composeComponentRow(RERANKER).selected).toBe(true);
    expect(composeComponentRow(EMBEDDING).selected).toBe(true);
  });

  it('offers a control only where the user may actually say no', () => {
    expect(composeComponentRow(RERANKER).togglable).toBe(true);
    expect(composeComponentRow(EMBEDDING).togglable).toBe(false);
    expect(composeComponentRow(CUDA).togglable).toBe(false);
  });

  it('renders unavailable hardware with a REASON and NO control — an unticked box implies a choice', () => {
    const row = composeComponentRow(CUDA);
    expect(row.state).toBe('unavailable');
    expect(row.togglable).toBe(false);
    expect(row.availability).toEqual({
      kind: 'unavailable',
      reason: 'No CUDA-capable GPU was detected on this machine.',
    });
    expect(row.stateText).toBe('Not supported here');
  });

  it('keeps the control on a DECLINED component so the choice can be undone', () => {
    const row = composeComponentRow(CHAT);
    expect(row.selected).toBe(false);
    expect(row.togglable).toBe(true);
    expect(row.availability.kind).toBe('available');
  });

  it('never marks a declinable component unavailable — the two states must not be one presentation', () => {
    expect(composeComponentRow(CHAT).availability.kind).not.toBe('unavailable');
  });

  it('falls back to a reason even when the planner sent an empty one (no blank explanation)', () => {
    const row = composeComponentRow({ ...CUDA, unavailableReason: '   ' });
    expect(row.availability.kind).toBe('unavailable');
    expect(row.availability.kind === 'unavailable' && row.availability.reason.length).toBeGreaterThan(0);
  });

  it('withholds a size rather than printing "0 B" when the registry declares none', () => {
    expect(composeComponentRow({ ...RERANKER, totalBytes: 0 }).sizeText).toBeNull();
  });

  it('treats an unknown necessity as REQUIRED — a category this build does not know is not switch-off-able', () => {
    expect(toNecessity('some-future-category')).toBe('required');
    expect(toNecessity(undefined)).toBe('required');
  });
});

describe('composeComponentGroups — grouped by consequence, in consequence order', () => {
  it('groups by necessity in order and drops empty categories', () => {
    const groups = composeComponentGroups([CHAT, CUDA, RERANKER, EMBEDDING]);
    expect(groups.map((g) => g.necessity)).toEqual([
      'required',
      'improves-results',
      'adds-feature',
      'infrastructure',
    ]);
    expect(groups[0]!.rows.map((r) => r.id)).toEqual(['embedding']);
  });

  it('states what is LOST, not what the category is called', () => {
    const groups = composeComponentGroups([RERANKER]);
    expect(groups[0]!.consequence).toBe('Search works; results are worse without it.');
    expect(NECESSITY_CONSEQUENCE['adds-feature']).toContain('chat');
  });

  it('is empty (not a fabricated placeholder) when the preview has no components', () => {
    expect(composeComponentGroups(undefined)).toEqual([]);
    expect(composeComponentGroups([])).toEqual([]);
  });
});

const RUNNING: AiInstallStatus = {
  state: 'running',
  currentStage: 'enrichment',
  readyCapabilities: ['retrieval-core', 'runtime'],
  stages: [
    {
      stage: 'core',
      label: 'Search core',
      state: 'completed',
      capabilities: ['retrieval-core', 'runtime'],
      totalBytes: 1_000,
      downloadedBytes: 1_000,
    },
    {
      stage: 'enrichment',
      label: 'Retrieval enrichment',
      state: 'running',
      capabilities: ['retrieval-enrichment'],
      totalBytes: 400,
      downloadedBytes: 100,
    },
    {
      stage: 'chat',
      label: 'Chat & AI answers',
      state: 'blocked',
      capabilities: ['llm'],
      totalBytes: 6_000,
      downloadedBytes: 0,
      blockedReason: 'Not enough disk space: 6.0 GB needed, 2.1 GB free.',
    },
  ],
};

describe('composeStageRows / searchReadyNotice — staged progress', () => {
  it('marks the stage the run is actually in', () => {
    const rows = composeStageRows(RUNNING);
    expect(rows.map((r) => r.current)).toEqual([false, true, false]);
  });

  it('computes a percent only where a byte total exists', () => {
    const rows = composeStageRows(RUNNING);
    expect(rows[1]!.percent).toBe(25);
    expect(composeStageRows({ stages: [{ stage: 'x', label: 'X', state: 'pending' }] })[0]!.percent)
      .toBeNull();
  });

  it('surfaces a blocked stage as an ACTIONABLE reason, not a generic failure', () => {
    const blocked = composeStageRows(RUNNING)[2]!;
    expect(blocked.stateText).toBe('Cannot start');
    expect(blocked.blockedReason).toBe('Not enough disk space: 6.0 GB needed, 2.1 GB free.');
  });

  it('says search is usable once the retrieval core is READY, and names what is still coming', () => {
    const notice = searchReadyNotice(RUNNING);
    expect(notice).toContain('Search is ready');
    expect(notice).toContain('Retrieval enrichment');
  });

  it('stays SILENT until the backend says the retrieval core is ready', () => {
    expect(searchReadyNotice({ ...RUNNING, readyCapabilities: [] })).toBeNull();
    expect(searchReadyNotice(null)).toBeNull();
  });
});

describe('friendlyInstallMessage — U5: a path is not a name', () => {
  const packages: AiInstallStatus['packages'] = [
    { packageId: 'embedding', label: 'Search embeddings', state: 'downloading' },
    { packageId: 'chat', label: 'Chat model', state: 'pending' },
  ];

  it('replaces the target path with the in-flight component label', () => {
    expect(
      friendlyInstallMessage('Downloading onnx/gte-multilingual-base/model.onnx...', packages),
    ).toBe('Downloading Search embeddings…');
  });

  it('keeps the retry suffix the backend appends', () => {
    expect(
      friendlyInstallMessage(
        'Downloading onnx/gte-multilingual-base/model.onnx... (attempt 2 of 3)',
        packages,
      ),
    ).toBe('Downloading Search embeddings… (attempt 2 of 3)');
  });

  it('leaves the message untouched when no package is in flight — an ugly truth beats a wrong label', () => {
    expect(friendlyInstallMessage('Downloading onnx/model.onnx...', [])).toBe(
      'Downloading onnx/model.onnx...',
    );
    expect(friendlyInstallMessage('Downloading onnx/model.onnx...', undefined)).toBe(
      'Downloading onnx/model.onnx...',
    );
  });

  it('leaves every other phase message alone', () => {
    expect(friendlyInstallMessage('Restoring AI runtime...', packages)).toBe(
      'Restoring AI runtime...',
    );
    expect(friendlyInstallMessage(null, packages)).toBe('');
  });
});
