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
    const n = readinessNotice(degraded('info', ['gpu.saturated']));
    expect(n!.causes).toContain('The GPU is busy; results may be slower');
    expect(n!.causes.join(' ')).not.toContain('Degraded: gpu.saturated'); // never the raw code
    // 804 §B5 amended the SECOND half of this case: alongside a reindex cause the banner now scopes
    // its list to causes a rebuild clears, so a secondary cause is OMITTED rather than mis-filed
    // under the Force-Rebuild remedy. PART X's guarantee — the user never sees a raw code — holds
    // either way, which is what this assertion pins.
    const alongsideReindex = readinessNotice(degraded('warn', ['index.blocked_legacy', 'gpu.saturated']));
    expect(alongsideReindex!.causes.join(' ')).not.toContain('Degraded: gpu.saturated');
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

  // ===== Tempdoc 804 §D1 (sandbox round 10 F1) — the advisory schema state is NOT a degradation =====

  it('804 §D1: index.schema_mismatch ALONE is info + honest — never "keyword-only" / "degraded", rebuild remedy kept', () => {
    // The severity is the produce-side half of the fix: the code must rank `info`, otherwise the
    // verdict is degraded-warn and the banner takes the impairing branch no matter what it says.
    expect(severityForCodes(['index.schema_mismatch'])).toBe('info');

    const n = readinessNotice(degraded('info', ['index.schema_mismatch']));
    expect(n!.headline).toBe('Index format is out of date.');
    // Round 10's defect verbatim: dense retrieval was provably live under both these claims.
    expect(n!.body).not.toContain('keyword-only');
    expect(n!.body).not.toContain('degraded');
    expect(n!.headline).not.toContain('Reindex required');
    expect(n!.body).toContain('Search is fully working');
    expect(n!.body).toContain('semantic and keyword retrieval are active');
    // A rebuild IS what adopts the newer index format, so the remedy stays actionable.
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
    // The headline+body already ARE the cause; no bullet that restates them.
    expect(n!.causes).toEqual([]);
  });

  it('804 §D1: the advisory notice still lists OTHER info causes (fresh install: schema + LambdaMART)', () => {
    const n = readinessNotice(degraded('info', ['index.schema_mismatch', 'lambdamart.not_configured']));
    expect(n!.headline).toBe('Index format is out of date.');
    expect(n!.causes).toEqual(['Learned re-ranking (LambdaMART) is not configured']);
  });

  it('804 §D1: a DEGRADING reindex cause keeps the unchanged "Reindex required / keyword-only" wording', () => {
    const n = readinessNotice(degraded('warn', ['index.embedding_mismatch']));
    expect(n!.headline).toBe('Reindex required.');
    expect(n!.body).toBe(
      'Semantic search is degraded until the index is rebuilt — results may be keyword-only.',
    );
    expect(n!.causes).toEqual([
      'The embedding model changed — rebuild the index to restore semantic search.',
    ]);
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.rebuild-index' });
  });

  it('804 §B5: the reindex branch scopes its cause list to REINDEX causes only (no rebuild-proof causes)', () => {
    const n = readinessNotice(
      degraded('warn', [
        'index.embedding_mismatch',
        'index.schema_mismatch',
        'lambdamart.not_configured',
        'inference.offline',
      ]),
    );
    expect(n!.headline).toBe('Reindex required.');
    // Only the cause a rebuild actually clears is listed under the one Force-Rebuild remedy. The
    // advisory schema code and the two rebuild-proof causes are NOT presented as reindex causes.
    expect(n!.causes).toEqual([
      'The embedding model changed — rebuild the index to restore semantic search.',
    ]);
    expect(n!.causes.join(' ')).not.toContain('LambdaMART');
    expect(n!.causes.join(' ')).not.toContain('local AI model is offline');
    expect(n!.causes.join(' ')).not.toContain('index format is out of date');
  });

  // ===== Tempdoc 804 §B5 (live round 11) — an impairing verdict is not automatically a RETRIEVAL
  // degradation: the body is selected by cause CLASS, never by severity alone. =====

  it('804 §B5: inference.offline ALONE words as AI-features-off — never "keyword results"', () => {
    const n = readinessNotice(degraded('warn', ['inference.offline']));
    expect(n!.headline).toBe('AI features unavailable.');
    // The round-11 defect verbatim: dense retrieval + the cross-encoder were live under this claim.
    // (The honest body does name keyword retrieval — as ACTIVE; what must be gone is the fallback
    // claim "Showing keyword results", i.e. keyword INSTEAD of semantic.)
    expect(n!.body).not.toContain('keyword results');
    expect(n!.body).not.toContain('Showing keyword');
    expect(n!.headline).not.toContain('Semantic search degraded');
    expect(n!.body).toContain('Search is fully working');
    expect(n!.body).toContain('semantic and keyword retrieval are active');
    expect(n!.causes).toEqual(['The local AI model is offline']);
    // Causes + remedy behave exactly as the impairing branch always did.
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.reload-inference' });
  });

  it('804 §B5: the live-observed pair (inference.offline + lambdamart) words as AI-features-off', () => {
    const n = readinessNotice(degraded('warn', ['lambdamart.not_configured', 'inference.offline']));
    expect(n!.headline).toBe('AI features unavailable.');
    expect(n!.body).not.toContain('keyword results');
    expect(n!.causes).toEqual([
      'Learned re-ranking (LambdaMART) is not configured',
      'The local AI model is offline',
    ]);
    expect(n!.remedy).toEqual({ kind: 'operation', operationId: 'core.reload-inference' });
  });

  it('804 §B5: a RETRIEVAL-impairing cause alongside the AI cause keeps the "keyword results" wording', () => {
    const n = readinessNotice(
      degraded('warn', ['worker.health.embedding_not_ready', 'inference.offline']),
    );
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).toContain('Showing keyword results');
    expect(n!.body).not.toContain('Search is fully working');
  });

  it('804 §B5: an UNCLASSIFIED code never acquires the reassuring "fully working" claim', () => {
    // The calm wording is an assertion about retrieval health; a code we cannot classify is not
    // evidence for it (same doctrine as severityForCodes' unknown ⇒ warn default).
    const n = readinessNotice(degraded('warn', ['inference.offline', 'some.future.code']));
    expect(n!.headline).toBe('Semantic search degraded.');
    expect(n!.body).not.toContain('Search is fully working');
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
