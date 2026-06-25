/**
 * Slice 447 §X.11.5 Phase 6 — RecoveryOverlayClient tests.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  __resetForTest,
  getOverlayRecovery,
  mergePluginRecoveryOverlays,
  onOverlayChange,
  removePluginRecoveryOverlays,
} from './RecoveryOverlayClient';
import { makePluginProvenance } from '../../shell-v0/primitives/provenance.js';

describe('RecoveryOverlayClient', () => {
  beforeEach(() => {
    __resetForTest();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('TRUSTED plugin overrides CORE condition (accepted)', () => {
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.acme',
        conditionId: 'schema.reindex-required',
        subject: 'worker.schema',
        operationRef: 'vendor.acme.fancy-reindex',
        provenance: makePluginProvenance('vendor.acme', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    expect(getOverlayRecovery('schema.reindex-required', 'worker.schema')).toBe(
      'vendor.acme.fancy-reindex',
    );
  });

  it('UNTRUSTED plugin attempting to override CORE condition is rejected', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.bad',
        conditionId: 'schema.reindex-required',
        subject: 'worker.schema',
        operationRef: 'vendor.bad.malicious',
        provenance: makePluginProvenance('vendor.bad', '0.0.0', 'UNTRUSTED_PLUGIN'),
      },
    ]);
    expect(getOverlayRecovery('schema.reindex-required', 'worker.schema')).toBeUndefined();
    expect(warn).toHaveBeenCalledTimes(1);
    expect(warn.mock.calls[0]?.[0]).toContain('UNTRUSTED');
  });

  it('UNTRUSTED plugin overriding its OWN namespace Condition is accepted', () => {
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.acme',
        conditionId: 'vendor.acme.metric-stalled',
        subject: 'vendor.acme.subject',
        operationRef: 'vendor.acme.fix',
        provenance: makePluginProvenance('vendor.acme', '0.0.0', 'UNTRUSTED_PLUGIN'),
      },
    ]);
    expect(getOverlayRecovery('vendor.acme.metric-stalled', 'vendor.acme.subject')).toBe(
      'vendor.acme.fix',
    );
  });

  it("UNTRUSTED plugin overriding ANOTHER plugin's namespace is rejected", () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.bad',
        conditionId: 'vendor.acme.metric-stalled',
        subject: 'vendor.acme.subject',
        operationRef: 'vendor.bad.malicious',
        provenance: makePluginProvenance('vendor.bad', '0.0.0', 'UNTRUSTED_PLUGIN'),
      },
    ]);
    expect(getOverlayRecovery('vendor.acme.metric-stalled', 'vendor.acme.subject')).toBeUndefined();
    expect(warn).toHaveBeenCalled();
  });

  it('removePluginRecoveryOverlays drops only the named plugin\'s entries', () => {
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.a',
        conditionId: 'vendor.a.cond',
        subject: 'vendor.a.subj',
        operationRef: 'vendor.a.op',
        provenance: makePluginProvenance('vendor.a', '0.0.0', 'TRUSTED_PLUGIN'),
      },
      {
        pluginId: 'vendor.b',
        conditionId: 'vendor.b.cond',
        subject: 'vendor.b.subj',
        operationRef: 'vendor.b.op',
        provenance: makePluginProvenance('vendor.b', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    expect(getOverlayRecovery('vendor.a.cond', 'vendor.a.subj')).toBe('vendor.a.op');
    expect(getOverlayRecovery('vendor.b.cond', 'vendor.b.subj')).toBe('vendor.b.op');

    removePluginRecoveryOverlays('vendor.a');
    expect(getOverlayRecovery('vendor.a.cond', 'vendor.a.subj')).toBeUndefined();
    expect(getOverlayRecovery('vendor.b.cond', 'vendor.b.subj')).toBe('vendor.b.op');
  });

  it('listeners fire after merge + remove', () => {
    const listener = vi.fn();
    onOverlayChange(listener);
    mergePluginRecoveryOverlays([
      {
        pluginId: 'vendor.acme',
        conditionId: 'vendor.acme.x',
        subject: 'subj',
        operationRef: 'vendor.acme.fix',
        provenance: makePluginProvenance('vendor.acme', '0.0.0', 'TRUSTED_PLUGIN'),
      },
    ]);
    expect(listener).toHaveBeenCalledTimes(1);
    removePluginRecoveryOverlays('vendor.acme');
    expect(listener).toHaveBeenCalledTimes(2);
  });
});
