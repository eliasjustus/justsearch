/**
 * Tempdoc 595 §4.2 — the verdict→notice projection (was 577 Ext III's
 * readiness→notice). Pins the contract: the search banner CONSUMES the one
 * SystemHealthVerdict (it does not re-read readiness.retrieval), renders for
 * `degraded` and (637 #1) `unreachable`, words causes from the verdict's reason codes (known specifically,
 * unknown generically — never dropped) with one remedy, and tracks the verdict's
 * SEVERITY so a cosmetic degradation is calm + accurate (§10.3).
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';
import { readinessNotice, reasonFor, severityForCodes } from './readinessNotice.js';
import type { SystemHealthVerdict } from './verdict.js';

const degraded = (
  severity: SystemHealthVerdict['severity'],
  reasons: string[],
): SystemHealthVerdict => ({ kind: 'degraded', severity, reasons });

describe('readinessNotice (595 §4.2) — projects the ONE verdict into the search banner', () => {
  it('returns null for every non-rendering verdict (the banner does not render)', () => {
    for (const kind of ['operational', 'checking', 'connecting'] as const) {
      expect(readinessNotice({ kind, severity: 'info', reasons: [] })).toBeNull();
    }
    expect(
      readinessNotice({ kind: 'transitioning', severity: 'busy', reasons: ['rebuilding'] }),
    ).toBeNull();
  });

  it('637 #1: an UNREACHABLE verdict mints a LOUD disconnected notice (never a silent empty result)', () => {
    const n = readinessNotice({
      kind: 'unreachable',
      severity: 'error',
      reasons: ['binding.unreachable'],
    });
    expect(n).not.toBeNull();
    expect(n!.headline).toBe('Backend disconnected.');
    expect(n!.body).toContain('reconnecting automatically');
    expect(n!.causes).toEqual(['The connection to the search backend was lost']);
    // The backend is dead by definition, so a backend `operation` remedy would be pointless —
    // fall back to the always-actionable Open Health (the manual reload is in the body text).
    expect(n!.remedy).toEqual({
      kind: 'navigate',
      target: 'core.health-surface',
      label: 'Open Health',
    });
  });

  it('§10.3: a COSMETIC degradation (severity info) is worded CALMLY and accurately — no "keyword results"', () => {
    const n = readinessNotice(degraded('info', ['lambdamart.not_configured']));
    expect(n!.headline).toBe('Reduced search capability.');
    expect(n!.body).toContain('still fully semantic');
    expect(n!.body).not.toContain('keyword results');
    expect(n!.causes).toEqual(['Learned re-ranking (LambdaMART) is not configured']);
  });

  it('600 PART X: gpu.saturated words calmly (no raw code) — the secondary-cause leak is closed', () => {
    const n = readinessNotice(degraded('info', ['index.blocked_legacy', 'gpu.saturated']));
    expect(n!.causes).toContain('The GPU is busy; results may be slower');
    expect(n!.causes.join(' ')).not.toContain('Degraded: gpu.saturated'); // never the raw code
  });

  it('an IMPAIRING degradation (severity warn) keeps the "Semantic search degraded / keyword results" wording', () => {
    const n = readinessNotice(degraded('warn', ['worker.health.embedding_not_ready']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('keyword results');
    expect(n!.causes).toEqual(['The semantic embedding index is not ready']);
    expect(n!.remedy).toEqual({
      kind: 'operation',
      operationId: 'core.trigger-offline-processing',
    });
  });

  it('compat reason code → reindex headline + rebuild remedy + the SPECIFIC cause worded (600 Design A)', () => {
    const n = readinessNotice(degraded('warn', ['index.blocked_legacy']));
    expect(n!.headline).toBe('Reindex required.');
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
    // Tempdoc 600's core fix: the causes slot now names the SPECIFIC cause (no longer empty).
    expect(n!.causes).toEqual([
      'The index was built before semantic search was available — rebuild it to enable meaning-based results.',
    ]);
  });

  it('an unknown code words generically and falls back to Open Health — never silence', () => {
    const n = readinessNotice(degraded('warn', ['some.future.code']));
    expect(n!.causes).toEqual(['Degraded: some.future.code']);
    expect(n!.remedy).toEqual({
      kind: 'navigate',
      target: 'core.health-surface',
      label: 'Open Health',
    });
  });

  it('598 reopen (B-3): index.dense_unavailable is severity warn (can never render "fully semantic") and words as capability-OFF', () => {
    // Produce→verdict severity: a dense-block a rebuild does NOT fix (no embedding model / embedder
    // down on a COMPATIBLE index) must be `warn`, so the verdict is degraded-warn and the banner can
    // NEVER take the `info` "still fully semantic" branch — the B-3 over-claim hole this code closes.
    expect(severityForCodes(['index.dense_unavailable'])).toBe('warn');

    const n = readinessNotice(degraded('warn', ['index.dense_unavailable']));
    // NOT a reindex cause (a reindex won't add a missing/unloaded embedder) → the impairing headline,
    // not "Reindex required".
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('keyword results');
    expect(n!.body).not.toContain('fully semantic');
    expect(n!.causes).toEqual(['Semantic search is unavailable right now — showing keyword results.']);
    // No false one-click rebuild remedy (the model isn't loaded) → the always-actionable Open Health ref.
    expect(n!.remedy).toEqual({ kind: 'navigate', target: 'core.health-surface', label: 'Open Health' });
  });
});

/**
 * Sandbox round 7 — the FIRST test in this suite (and in `shell-v0`) that asserts a remedy's
 * `navigate` target against the surface that actually OWNS the capability, rather than against a
 * hardcoded expected id. That distinction is the whole point: `conversations.locked` pointed at
 * `core.settings-surface` for as long as it existed, because tempdoc 629 moved `unlockEncryption()`
 * out of Settings into the Security surface and nothing anywhere re-checked the remedy against it.
 * An id-vs-id assertion would have been updated to match the wrong id and stayed green forever.
 *
 * So the expectation is DERIVED from source: find the module that defines the unlock control, read
 * the custom-element tag it registers, and resolve that tag back to its surface id through
 * CorePlugin's registration table. The remedy must land on that id.
 */
describe('remedy targets resolve to the surface that owns the capability', () => {
  const here = dirname(fileURLToPath(import.meta.url));
  const read = (rel: string) => readFileSync(resolve(here, rel), 'utf8');

  /** The surface id whose `mountTag` is `tag`, per CorePlugin's registration table. */
  function surfaceIdForMountTag(corePluginSrc: string, tag: string): string | null {
    // Registration blocks are `{ id: 'core.x-surface', mountTag: 'jf-x-surface', ... }`; scan for
    // the id that precedes the given mountTag within the same block.
    const re = /id:\s*'(core\.[^']+)'(?:(?!id:\s*')[\s\S])*?mountTag:\s*'([^']+)'/g;
    for (const m of corePluginSrc.matchAll(re)) {
      if (m[2] === tag) return m[1]!;
    }
    return null;
  }

  it('conversations.locked → the surface that defines unlockEncryption(), not Settings', () => {
    const securitySrc = read('../views/SecuritySurface.ts');
    const settingsSrc = read('../views/SettingsSurface.ts');
    const corePluginSrc = read('../plugin-api/CorePlugin.ts');

    // 1. Establish the capability owner from source, not from memory.
    expect(securitySrc).toMatch(/unlockEncryption\s*\(/);
    expect(settingsSrc).not.toMatch(/unlockEncryption\s*\(/);

    // 2. Resolve that module's registered element tag to its surface id.
    const tagMatch = /customElements\.define\('([^']+)',\s*SecuritySurface\)/.exec(securitySrc);
    expect(tagMatch, 'SecuritySurface must register a custom element').not.toBeNull();
    const ownerSurfaceId = surfaceIdForMountTag(corePluginSrc, tagMatch![1]!);
    expect(ownerSurfaceId, `no CorePlugin surface mounts <${tagMatch![1]}>`).not.toBeNull();

    // 3. The remedy the locked-chat affordance offers must navigate THERE.
    expect(reasonFor('conversations.locked').remedy).toEqual({
      kind: 'navigate',
      target: ownerSurfaceId,
      label: 'Unlock in Security',
    });
  });
});
