// @vitest-environment happy-dom

/**
 * Tempdoc 575 §17 Face C (review fix) — pins the install/pack live-projection law that the System
 * Self-View consumes: a running install is "stalled" iff its backend heartbeat has aged past the FE
 * freshness window. Clock-injected, no real poll (mirrors the inFlightLiveness tests).
 */

import { describe, it, expect } from 'vitest';
import { installStatusUrl, projectInstallStatus } from './aiInstallBridge.js';
import { AI_INSTALL_STALE_MS } from './aiInstallLiveness.js';

describe('575 §17 Face C — aiInstallBridge.projectInstallStatus', () => {
  const NOW = 1_000_000_000;

  it('a running install with a fresh heartbeat is running (not stalled)', () => {
    const p = projectInstallStatus({ state: 'running', updatedAtEpochMs: NOW - 1000 }, NOW);
    expect(p.state).toBe('running');
    expect(p.stalled).toBe(false);
  });

  it('a running install whose heartbeat aged past the window is stalled', () => {
    const p = projectInstallStatus(
      { state: 'running', updatedAtEpochMs: NOW - (AI_INSTALL_STALE_MS + 1) },
      NOW,
    );
    expect(p.state).toBe('running');
    expect(p.stalled).toBe(true);
  });

  it('a non-running state is never stalled', () => {
    expect(projectInstallStatus({ state: 'failed', updatedAtEpochMs: 1 }, NOW).stalled).toBe(false);
    expect(projectInstallStatus({ state: 'succeeded', updatedAtEpochMs: 1 }, NOW).stalled).toBe(false);
    expect(projectInstallStatus({ state: 'idle' }, NOW).stalled).toBe(false);
  });

  it('defaults a missing payload to an idle, non-stalled projection', () => {
    const p = projectInstallStatus({}, NOW);
    expect(p.state).toBe('idle');
    expect(p.stalled).toBe(false);
    expect(p.message).toBe('');
  });

  it('composes the packaged backend URL from apiBase', () => {
    expect(installStatusUrl('http://127.0.0.1:18080')).toBe(
      'http://127.0.0.1:18080/api/ai/install/status',
    );
    expect(installStatusUrl('http://127.0.0.1:18080/')).toBe(
      'http://127.0.0.1:18080/api/ai/install/status',
    );
  });
});
