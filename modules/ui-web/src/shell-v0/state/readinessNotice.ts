// SPDX-License-Identifier: Apache-2.0
/**
 * readinessNotice — Tempdoc 577 Goal 1 Phase 2 (Ext III).
 *
 * The one projection from the observed-state readiness record (`ReadinessView`,
 * the aiStateStore authority fed by `/api/status` composites) to a degradation
 * NOTICE that can explain itself: *state* (the headline), *cause* (worded from
 * the same closed reason-code vocabulary the backend emits —
 * `LifecycleReasonCode` + the worker-health probe codes), and *remedy* (a
 * reference the surface dispatches: an operation id through the one
 * `OperationClient` seam, or a surface navigation). A notice minted from this
 * projection cannot be cause-less: an unknown code degrades to honest generic
 * wording + the "Open Health" remedy, never silence.
 *
 * Pure data → data; the surface owns rendering and dispatch. Unknown codes are
 * BY DESIGN not dropped — they word generically and keep the Health remedy.
 */
import type { SystemHealthVerdict } from './verdict.js';

export type NoticeRemedy =
  /** Dispatched via `<jf-op-button>` — the label/risk/ceremony come from the
   *  operation catalog, never from here (the hardcoded-label path stays
   *  structurally unreachable, per OpButton's design). */
  | { kind: 'operation'; operationId: string }
  | { kind: 'navigate'; target: string; label: string };

export interface ReadinessNoticeView {
  /** Bold lead-in ("Reindex required." / "Semantic search degraded."). */
  headline: string;
  /** The standing consequence sentence (kept from the B3 banner wording). */
  body: string;
  /** Worded causes projected from `reasonCodes` (deduped; [] when none known). */
  causes: string[];
  /** The highest-priority actionable remedy for the worded causes. */
  remedy: NoticeRemedy;
}

/** The "Open Health" fallback remedy — always actionable, never wrong. */
const OPEN_HEALTH: NoticeRemedy = {
  kind: 'navigate',
  target: 'core.health-surface',
  label: 'Open Health',
};

/**
 * The closed cause vocabulary: backend reason code → (wording, remedy?).
 * Codes the backend emits today (`LifecycleReasonCode` + StatusLifecycleHandler's
 * worker-health probes). A row WITHOUT a remedy falls back to Open Health.
 * Order in the list is priority order for picking the notice's single remedy.
 */
/**
 * Presentation severity (tempdoc 595 §10.5). `ok`/`busy` are produced by the
 * verdict (operational / transitioning); reason codes only ever map to the
 * degradation subset (`info`/`warn`/`error`). One vocabulary, no second authority.
 */
export type Severity = 'ok' | 'info' | 'busy' | 'warn' | 'error';

/** Per-reason severity. Unset ⇒ `warn` (the honest default for an impairing code). */
type ReasonSeverity = 'info' | 'warn' | 'error';

const CAUSE_ROWS: ReadonlyArray<{
  code: string;
  wording: string;
  remedy?: NoticeRemedy;
  /**
   * User-impact tier (595 §10.3): `info` = graceful/transient/cosmetic (search
   * still serves); `warn` = impairing (default); `error` = search broken.
   */
  severity?: ReasonSeverity;
}> = [
  {
    // Tempdoc 637 #1 — FE-derived (declared in readiness-reason-codes.v1.json feDerived); the
    // backend never emits it. Minted by computeVerdict when the FE→backend binding is dead.
    code: 'binding.unreachable',
    wording: 'The connection to the search backend was lost',
    severity: 'error',
  },
  {
    code: 'worker.health.embedding_not_ready',
    wording: 'The semantic embedding index is not ready',
    remedy: { kind: 'operation', operationId: 'core.trigger-offline-processing' },
  },
  {
    code: 'chunk_embedding.not_ready',
    wording: 'Passage embeddings are not ready',
    remedy: { kind: 'operation', operationId: 'core.trigger-offline-processing' },
  },
  {
    code: 'chunk_embedding.in_progress',
    wording: 'Passage embeddings are still being computed',
    // In-progress: nothing to trigger — watching it in Health is the action.
    severity: 'info',
  },
  {
    code: 'worker.health.embedding_probe_missing',
    wording: 'The embedding model could not be probed',
  },
  {
    code: 'inference.offline',
    wording: 'The local AI model is offline',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
  },
  { code: 'inference.starting', wording: 'The local AI model is still starting', severity: 'info' },
  // Tempdoc 656 — the AI capability's previously-generic "offline" reason, now specific. No
  // remedy operation is registered for install/import actions today (unlike
  // core.reload-inference), so these fall back to the Open-Health reference — same pattern as
  // vdu.missing_mmproj / ocr.engine_missing below, rather than pointing at a nonexistent operation.
  {
    code: 'inference.model_not_configured',
    wording: 'No local AI chat model is configured yet',
    severity: 'warn',
  },
  {
    code: 'inference.model_not_found',
    wording: 'The configured local AI chat model file could not be found',
    severity: 'warn',
  },
  {
    code: 'inference.runtime_not_installed',
    wording: 'The local AI runtime (llama-server) is not installed',
    severity: 'warn',
  },
  {
    code: 'inference.policy_online_ai_disabled',
    wording: 'Local AI is disabled by administrator policy',
    severity: 'info',
  },
  {
    code: 'inference.policy_gpu_disabled',
    wording: 'GPU acceleration for local AI is disabled by administrator policy',
    severity: 'info',
  },
  {
    // Tempdoc 656 (post-implementation review fix): this code is the shared catch-all for both
    // activation failures (self-test/apply) AND deactivation failures (rollback) in
    // RuntimeActivationService.mapToLifecycleReason — worded direction-neutrally so it isn't wrong
    // when the user was actually deactivating.
    code: 'inference.activation_failed',
    wording: 'The local AI runtime failed to switch modes',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'warn',
  },
  {
    code: 'ocr.disabled',
    wording: 'OCR is disabled, so scanned text cannot be indexed yet',
    severity: 'warn',
  },
  {
    code: 'ocr.engine_missing',
    wording: 'OCR is unavailable because Tesseract is not installed or not on PATH',
    severity: 'warn',
  },
  {
    code: 'ocr.language_missing',
    wording: 'OCR is missing a required language pack',
    severity: 'warn',
  },
  {
    code: 'vdu.ai_offline',
    wording: 'Visual document understanding is waiting for the local AI model',
    remedy: { kind: 'operation', operationId: 'core.reload-inference' },
    severity: 'info',
  },
  {
    code: 'vdu.insufficient_vram',
    wording: 'Visual document understanding needs more GPU memory for the selected vision model',
    severity: 'warn',
  },
  {
    code: 'vdu.missing_mmproj',
    wording: 'Visual document understanding is missing its vision projector file',
    severity: 'warn',
  },
  {
    code: 'vdu.circuit_open',
    wording: 'Visual document understanding is paused after repeated failures',
    severity: 'warn',
  },
  { code: 'worker.throughput_stalled', wording: 'Indexing throughput has stalled' },
  {
    code: 'worker.throughput_degraded',
    wording: 'Indexing throughput is degraded',
    severity: 'info',
  },
  {
    code: 'worker.starting',
    wording: 'The knowledge server is still starting',
    severity: 'info',
  },
  { code: 'worker.spawn.failed', wording: 'The knowledge server failed to start', severity: 'error' },
  // Tempdoc 627 — transient: a supervised restart is in flight. Calm (info) + no remedy — it self-recovers;
  // the verdict promotes this to a "Restarting…" transitioning state so a routine self-heal isn't alarming.
  {
    code: 'worker.recovering',
    wording: 'The knowledge server is restarting',
    severity: 'info',
  },
  // Tempdoc 627 — terminal give-up: the supervisor exhausted its restart budget and stopped retrying.
  // Distinct from transient codes; does not self-recover. No one-click remedy (a worker respawn is
  // what just failed) ⇒ Open-Health fallback, mirroring worker.spawn.failed.
  {
    code: 'worker.restart_exhausted',
    wording: 'The knowledge server stopped responding and could not be recovered',
    severity: 'error',
  },
  // GPU acceleration unavailable ⇒ CPU fallback: slower, not broken (info).
  {
    code: 'ort_cuda.missing_dlls',
    wording: 'GPU acceleration is unavailable (missing CUDA DLLs)',
    severity: 'info',
  },
  {
    code: 'ort_cuda.provider_failed',
    wording: 'GPU acceleration failed to initialize',
    severity: 'info',
  },
  // Tempdoc 600 PART X — the GPU-saturation readiness code (aiFeatures composite). When retrieval is
  // independently degraded, the verdict appends this as a SECONDARY cause; without a row it rendered the
  // raw `Degraded: gpu.saturated` (reproduced live). A transient performance dip, not a broken
  // capability ⇒ calm `info`, no one-click remedy (Open-Health fallback).
  {
    code: 'gpu.saturated',
    wording: 'The GPU is busy; results may be slower',
    severity: 'info',
  },
  // Tempdoc 586 P-1b — the LambdaMART (learned-ranking) reason codes the backend
  // emits (LifecycleReasonCode.LAMBDAMART_*). Informational: re-ranking quality is
  // reduced but keyword/semantic retrieval still serves, so no remedy operation —
  // these fall back to the Open Health reference rather than promising a one-click fix.
  // 595 §10.3: an optional re-ranker being off must NOT alarm like a broken index.
  {
    code: 'lambdamart.not_configured',
    wording: 'Learned re-ranking (LambdaMART) is not configured',
    severity: 'info',
  },
  {
    code: 'lambdamart.training',
    wording: 'Learned re-ranking (LambdaMART) is still training',
    severity: 'info',
  },
  {
    code: 'lambdamart.failed',
    wording: 'Learned re-ranking (LambdaMART) failed to load',
    severity: 'info',
  },
  // Tempdoc 596 §17 — FE-derived: a control gated on "no documents indexed". The backend does NOT
  // emit this code (it never appears in `reasonCodes`, so the banner/verdict are unaffected); it
  // exists here only so the per-affordance availability projection (`reasonFor`) speaks ONE vocabulary
  // with the banner. The remedy is to add a folder, which lives on the Library surface.
  {
    code: 'no_documents',
    wording: 'No documents indexed yet',
    remedy: { kind: 'navigate', target: 'core.library-surface', label: 'Add documents' },
    severity: 'info',
  },
  // Tempdoc 629 (#3) — FE-derived: the conversation store is encrypted + locked (history 423'd). The
  // backend never emits this readiness code; it lives here so the locked-chat affordance speaks the ONE
  // CAUSE_ROWS vocabulary instead of hardcoding its wording (the honesty-as-typed-guarantee for the gate).
  {
    code: 'conversations.locked',
    wording: 'Your chat history is encrypted and locked',
    // The remedy must point at the surface that OWNS the unlock capability: `unlockEncryption()`
    // lives on SecuritySurface (`core.security-surface`, CorePlugin.ts), NOT on Settings — 629 moved
    // the encryption control out of Settings and this remedy was left behind pointing one hop short.
    remedy: { kind: 'navigate', target: 'core.security-surface', label: 'Unlock in Security' },
    severity: 'warn',
  },
  // Tempdoc 600 Design A — the embedding/schema compatibility causes the worker emits on the
  // `retrieval` readiness composite (StatusLifecycleHandler.compatBlockedReason). Previously these
  // reached the verdict only as a coarse `reindexRequired` boolean → a generic, cause-less banner;
  // now each names its specific, actionable cause. All are impairing (semantic search is off until a
  // rebuild) but NOT broken (keyword search still serves) ⇒ `warn`, with the rebuild remedy.
  {
    code: 'index.blocked_legacy',
    wording:
      'The index was built before semantic search was available — rebuild it to enable meaning-based results.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  {
    code: 'index.embedding_legacy',
    wording:
      "Semantic search isn't available on this index yet — rebuild it to enable meaning-based results.",
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  // Tempdoc 804 §D1 — ADVISORY, not degrading, and therefore NOT in REINDEX_CAUSE_CODES below.
  // `index_schema_fp` is a content hash of the canonical `SSOT/catalogs/fields.v1.json`
  // (SsotCommitMetadataSource.java:81-93, compared at IndexStatusOps.java:995) and has ZERO
  // query-path consumers: the dense leg is gated by the EMBEDDING fingerprint
  // (SearchPlanner.java:87 via allowQueryEmbeddings()), never by this one. Sandbox round 10
  // reproduced the consequence of getting this wrong — `schema_mismatch` true while dense retrieval
  // was provably live, under a red "results may be keyword-only" banner. A rebuild picks up newer
  // index features; it does not restore anything that is broken ⇒ `info`, rebuild remedy retained.
  {
    code: 'index.schema_mismatch',
    wording:
      'The index format is out of date — search is fully working; rebuilding picks up newer index features.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'info',
  },
  // Tempdoc 628 Stage C — the index was detected corrupt and is being automatically rebuilt from your
  // files. No one-click rebuild remedy: it's already rebuilding, so the honest affordance is to watch
  // progress on Health. Impairing (results temporarily incomplete) but self-healing ⇒ `warn`.
  {
    code: 'index.rebuilding',
    wording:
      'The index was corrupted and is being rebuilt from your files — results are temporarily incomplete.',
    severity: 'warn',
  },
  {
    code: 'index.embedding_mismatch',
    wording: 'The embedding model changed — rebuild the index to restore semantic search.',
    remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    severity: 'warn',
  },
  // Tempdoc 598 reopen (B-3) — the dense leg can't run for a reason a REBUILD does NOT fix (the
  // embedding model isn't loaded, or the embedder is down on a COMPATIBLE index). Distinct from the
  // index.*_legacy/_mismatch reindex causes: there is no one-click rebuild remedy (a reindex won't add
  // a missing model), so it falls back to the Open Health reference. Impairing (semantic genuinely off,
  // AUTO degraded to keyword) but NOT broken (keyword search still serves) ⇒ `warn`. This row stops the
  // banner over-claiming "fully semantic" while a query actually ran keyword (the §59 hole). NOTE: the
  // final wording is co-owned with tempdoc 600 (the consumed-copy authority); this is the source-side
  // vocabulary entry the produce-side (StatusLifecycleHandler.denseUnavailableReason) requires.
  {
    code: 'index.dense_unavailable',
    wording: 'Semantic search is unavailable right now — showing keyword results.',
    severity: 'warn',
  },
];

/**
 * Tempdoc 600 Design A — the reason codes that mean "a reindex/rebuild restores semantic search"
 * (the worker's embedding/schema BLOCKED_* compat states, surfaced on the `retrieval` composite).
 * The ONE place that knows which codes carry the "Reindex required" headline, so the 595 verdict
 * (`verdictHeadline`/`verdictBody`) and this banner cannot disagree. Replaces the old synthetic
 * `reindex-required` token that was minted from a boolean (tempdoc 600 PART III §16).
 *
 * Tempdoc 804 §D1 — this is the DEGRADING bucket: every member genuinely gates the dense leg
 * (embedding fingerprint / no fingerprint at all), so the "results may be keyword-only" consequence
 * is true of all of them. `index.schema_mismatch` was removed from this set: it is advisory (see its
 * CAUSE_ROWS row) and lumping it here made the banner assert a degradation that measurably was not
 * happening.
 */
const REINDEX_CAUSE_CODES: ReadonlySet<string> = new Set([
  'index.blocked_legacy',
  'index.embedding_legacy',
  'index.embedding_mismatch',
]);

/**
 * The advisory index-format code (804 §D1): the stored catalog fingerprint differs from the current
 * one, with no query-path consequence. Named here so surfaces that already headline the rebuild
 * story do not have to string-match it (the round-2 "dedup by code, not wording" ruling).
 */
export const INDEX_SCHEMA_MISMATCH = 'index.schema_mismatch';

/** True when {@code code} is an embedding/schema compat cause that a rebuild fixes. */
export function isReindexCause(code: string): boolean {
  return REINDEX_CAUSE_CODES.has(code);
}

/**
 * Tempdoc 804 §B5 (live round-11 finding) — the reason codes that mean the RETRIEVAL pipeline
 * itself fell back (or cannot be asserted live). Same shape as REINDEX_CAUSE_CODES above: the ONE
 * place that knows which causes justify the "showing keyword results" consequence, so the impairing
 * banner stops deriving that claim from SEVERITY alone. Observed defect: on a fully-enriched index
 * with only `lambdamart.not_configured` (info) + `inference.offline` (warn), the verdict is warn and
 * the banner read "Semantic search degraded / Showing keyword results" while dense retrieval AND the
 * cross-encoder were provably live — an AI-features cause worded as a retrieval fallback.
 *
 * The three REINDEX_CAUSE_CODES are retrieval-impairing too, but can never reach the branch this set
 * guards (the reindex branch returns first), so they are not restated here.
 */
const RETRIEVAL_IMPAIRING_CODES: ReadonlySet<string> = new Set([
  // The dense leg is positively known not to serve (StatusLifecycleHandler.denseUnavailableReason).
  'index.dense_unavailable',
  // The embedder is down ⇒ query embeddings unavailable ⇒ AUTO degrades to keyword.
  'worker.health.embedding_not_ready',
  // The embedder could not be probed: we do NOT know both legs are live, and the reassuring wording
  // requires positive knowledge (same doctrine as severityForCodes' unknown ⇒ warn default).
  'worker.health.embedding_probe_missing',
  // Passage vectors absent / partial ⇒ passage-level dense retrieval is not fully serving.
  'chunk_embedding.not_ready',
  'chunk_embedding.in_progress',
  // The index is being rebuilt from source: results are temporarily incomplete on both legs.
  'index.rebuilding',
  // The knowledge server is not serving (or not serving yet): retrieval as a whole is impaired, so
  // the "search is fully working" claim would be flatly false.
  'worker.starting',
  'worker.recovering',
  'worker.spawn.failed',
  'worker.restart_exhausted',
]);

/**
 * True when {@code code} means retrieval itself is impaired. An UNKNOWN code counts as impairing:
 * the calm "search is fully working" wording is an assertion, and we never assert full retrieval
 * health from a code we cannot classify (mirrors `severityForCodes`, which refuses to downgrade an
 * unrecognized degradation to `info`).
 */
function isRetrievalImpairing(code: string): boolean {
  if (RETRIEVAL_IMPAIRING_CODES.has(code)) return true;
  return !CAUSE_ROWS.some((row) => row.code === code);
}

/**
 * The codes that mean the local AI model is not available, so chat/answer features are off while
 * retrieval is untouched. Positive gate (not merely "no retrieval cause"): several non-retrieval
 * causes are also non-AI (`ocr.*`, `worker.throughput_*`, `conversations.locked`), and wording those
 * as "AI features unavailable" would repeat the very defect this fixes in the other direction.
 * Excluded on purpose: `inference.starting` (transient, owned by the calm `info` branch),
 * `inference.policy_*` (policy-disabled never "comes online"), `vdu.ai_offline` (document
 * understanding, not chat).
 */
const AI_MODEL_UNAVAILABLE_CODES: ReadonlySet<string> = new Set([
  'inference.offline',
  'inference.model_not_configured',
  'inference.model_not_found',
  'inference.runtime_not_installed',
  'inference.activation_failed',
]);

const SEVERITY_RANK: Record<ReasonSeverity, number> = { info: 0, warn: 1, error: 2 };

/**
 * Worst-of reason severity (595 §10.5) — the verdict's degradation severity.
 * An unknown / unmapped code defaults to `warn` (never silently `info`): we don't
 * downgrade an unrecognized degradation to "cosmetic". Empty ⇒ `warn`.
 */
export function severityForCodes(codes: readonly string[]): Severity {
  let worst: ReasonSeverity = codes.length === 0 ? 'warn' : 'info';
  let sawAny = false;
  for (const code of codes) {
    const row = CAUSE_ROWS.find((c) => c.code === code);
    const sev: ReasonSeverity = row?.severity ?? 'warn';
    if (!sawAny || SEVERITY_RANK[sev] > SEVERITY_RANK[worst]) {
      worst = sev;
      sawAny = true;
    }
  }
  return worst;
}

/**
 * Project the ONE system-health verdict (tempdoc 595 §4.2) into the search
 * window's degradation notice. The banner CONSUMES the verdict — it does NOT
 * re-read `readiness.retrieval` (so it can no longer contradict the Health
 * header/footer, the §1.1 third-interpreter split). Returns null unless the
 * verdict is `degraded`; the wording tracks the verdict's SEVERITY, so a cosmetic
 * degradation (e.g. LambdaMART off, severity `info`) is worded calmly and
 * accurately — never the over-claiming "showing keyword results" that misdescribes
 * a re-ranking gap as a retrieval failure (595 §10.3).
 */
export function readinessNotice(verdict: SystemHealthVerdict): ReadinessNoticeView | null {
  // Tempdoc 637 #1: a dead/replaced FE→backend binding surfaces AS a loud notice at its own
  // layer — never a silent empty result one layer up. The backend is unreachable, so the remedy
  // is a client action (reload), conveyed in the body; the button falls back to Open Health
  // (a backend `operation` remedy would be pointless against a dead backend). In production the
  // shell re-resolves automatically (637 #1 Phase 2), so the notice self-clears on reconnect.
  if (verdict.kind === 'unreachable') {
    return {
      headline: 'Backend disconnected.',
      body: 'Lost the connection to the search backend — reconnecting automatically. Reload the app if this persists.',
      causes: wordCauses(verdict.reasons),
      remedy: OPEN_HEALTH,
    };
  }
  if (verdict.kind !== 'degraded') return null;
  // Tempdoc 600 Design A: the actionable cause now arrives as real reason codes (the worker's
  // embedding/schema compat codes), not a synthetic boolean-derived token — so the `causes` slot
  // names the SPECIFIC cause instead of being empty.
  const codes = verdict.reasons;
  // Tempdoc 804 §B5/§D1 — cause-list SCOPING: the rebuild headline lists only the causes a rebuild
  // clears. Before, every verdict reason was rendered under the one Force-Rebuild remedy, so causes
  // a rebuild cannot fix (`lambdamart.not_configured`, `inference.offline`) read as reindex causes.
  // Non-reindex codes keep their own rows and remedies via the paths below (this branch is only
  // reached when a genuinely degrading reindex cause is present).
  const reindexCauses = codes.filter(isReindexCause);
  if (reindexCauses.length > 0) {
    return {
      headline: 'Reindex required.',
      body: 'Semantic search is degraded until the index is rebuilt — results may be keyword-only.',
      causes: wordCauses(reindexCauses),
      remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
    };
  }
  if (verdict.severity === 'info') {
    // Tempdoc 804 §D1 — the ADVISORY index-format state. Reached only when no degrading cause is
    // present (the reindex branch above and the `info` severity together guarantee that), so the
    // banner can state the measured truth: retrieval is intact on both legs. The rebuild remedy
    // stays — a rebuild is what adopts the newer index format.
    if (codes.includes(INDEX_SCHEMA_MISMATCH)) {
      return {
        headline: 'Index format is out of date.',
        body: 'Search is fully working — both semantic and keyword retrieval are active. Rebuilding will pick up newer index features.',
        causes: wordCauses(codes.filter((c) => c !== INDEX_SCHEMA_MISMATCH)),
        remedy: { kind: 'operation', operationId: 'core.rebuild-index' },
      };
    }
    // §10.3 — cosmetic/optional degradation: search still serves fully; say so
    // accurately and calmly (consistent with the Health header's "Reduced capability").
    return {
      headline: 'Reduced search capability.',
      body: 'An optional capability is unavailable; results are still fully semantic.',
      causes: wordCauses(codes),
      remedy: pickRemedy(codes),
    };
  }
  // Tempdoc 804 §B5 (round-11 live finding) — an impairing degradation is not automatically a
  // RETRIEVAL degradation. Select the consequence by cause CLASS, the way the reindex branch above
  // already does: only a retrieval-impairing cause licenses "showing keyword results", and only a
  // positively-known AI-model cause licenses the calm AI-features wording. Anything else (an
  // unclassified code, or a non-retrieval non-AI cause like `ocr.disabled`) keeps the conservative
  // impairing wording below rather than acquiring a new claim we cannot back.
  if (!codes.some(isRetrievalImpairing) && codes.some((c) => AI_MODEL_UNAVAILABLE_CODES.has(c))) {
    return {
      headline: 'AI features unavailable.',
      body: 'Search is fully working — both semantic and keyword retrieval are active. Chat and answer features are unavailable until the AI model is online.',
      causes: wordCauses(codes),
      remedy: pickRemedy(codes),
    };
  }
  // Impairing degradation (warn/error): retrieval genuinely fell back.
  return {
    headline: 'Semantic search degraded.',
    body: 'Showing keyword results; relevance ranking may be reduced.',
    causes: wordCauses(codes),
    remedy: pickRemedy(codes),
  };
}

/** Word each known code; unknown codes word generically (deduped, original order). */
function wordCauses(codes: readonly string[]): string[] {
  const out: string[] = [];
  for (const code of codes) {
    const row = CAUSE_ROWS.find((c) => c.code === code);
    const wording = row ? row.wording : `Degraded: ${code}`;
    if (!out.includes(wording)) out.push(wording);
  }
  return out;
}

/** First mapped remedy in CAUSE_ROWS priority order; Open Health otherwise. */
function pickRemedy(codes: readonly string[]): NoticeRemedy {
  for (const row of CAUSE_ROWS) {
    if (row.remedy && codes.includes(row.code)) return row.remedy;
  }
  return OPEN_HEALTH;
}

/**
 * Tempdoc 596 §17 — the per-affordance read-seam over the ONE reason vocabulary. The CONTROL-scoped
 * projection (`projectAvailability`) reads `{ wording, remedy }` for a single reason-code here, the
 * same `CAUSE_ROWS` the SYSTEM-scoped banner (`readinessNotice`) and the 595 verdict (`severityForCodes`)
 * project from — so a control's reason and the window's reason, and their remedies, cannot drift. An
 * unknown code words generically (mirrors `wordCauses`' fallback), never silence.
 */
export function reasonFor(code: string): { wording: string; remedy?: NoticeRemedy } {
  const row = CAUSE_ROWS.find((c) => c.code === code);
  return row ? { wording: row.wording, remedy: row.remedy } : { wording: `Degraded: ${code}` };
}
