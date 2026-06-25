// SPDX-License-Identifier: Apache-2.0
/**
 * OperationClient — framework-agnostic client for invoking Operations
 * against the backend.
 *
 * Wire boundary (slice 3a-1-2 Phase 2): POST /api/operations/{id}/invoke.
 * Request shape per `OperationInvocationRequest` (Java); response per
 * `OperationInvocationResponse`. Errors are normalized into
 * {@link OperationError} so consumers don't branch on HTTP status codes.
 *
 * Used by:
 *   - The React `useOperation()` hook (slice 3a-1-2 Phase 4).
 *   - The `wireActionButton(...)` helper (slice 3a-1-2 Phase 4).
 *   - Future Lit reactive controllers in slice 3a.2+ surfaces.
 */

/** Risk policy axes per `OperationPolicy.RiskTier` on the Java side. */
export type Risk = 'LOW' | 'MEDIUM' | 'HIGH';

/** Wire shape of the request body. Mirrors `OperationInvocationRequest`. */
export interface OperationInvocationRequest {
  args?: Record<string, unknown>;
  idempotencyKey?: string;
  confirmationToken?: string;
  /**
   * Slice 489 §17.5 — transport hint sent as the `X-JustSearch-Transport`
   * header. The backend constructs the {@code InvocationProvenance} from this
   * hint; unknown values fall back to BUTTON. Optional — absent means BUTTON
   * (preserves prior behavior).
   */
  transport?: string;
}

/** Wire shape of a successful invocation result. */
export interface OperationInvocationSuccess {
  success: true;
  message: string;
  executionId?: string;
  structuredData?: Record<string, unknown>;
}

/** Constructor configuration. */
export interface OperationClientConfig {
  /** Absolute API base (e.g., 'http://127.0.0.1:33221'). No trailing slash. */
  apiBase: string;
  /** Optional fetch override (for tests). */
  fetchImpl?: typeof fetch;
}

/**
 * Normalized error returned by {@link OperationClient.invoke} when the
 * dispatch failed for any reason.
 *
 * `errorClass` = controller-level discrimination (transport / framing layer):
 *   - `OPERATION_NOT_FOUND` — backend has no Operation with that id (404).
 *   - `BAD_REQUEST` — malformed args or missing path id (400).
 *   - `HANDLER_FAILURE` — handler ran and returned a failure result.
 *   - `HANDLER_ERROR` — handler threw uncaught (500).
 *   - `NETWORK_ERROR` — fetch threw (no response).
 *   - `SERIALIZATION_ERROR` — backend reported a serialization issue.
 *
 * `errorCode` = handler-typed error semantics (slice 3a-2-c Phase B). Only
 * populated for `errorClass === 'HANDLER_FAILURE'` paths where the handler
 * mapped a typed exception to a known token (e.g.,
 * `POLICY_ONLINE_AI_DISABLED`, `INSUFFICIENT_VRAM`, `PACK_IMPORT_RUNNING`,
 * `SETTINGS_READ_ONLY`). FE consumers branch on `errorCode` for banners,
 * retry hints, and PERMANENT/TRANSIENT classification — independent of the
 * controller-level `errorClass`.
 *
 * `errorDetails` = optional structured payload from the handler (e.g.,
 * `{mode: "online"}` for switch-inference-mode failures).
 *
 * `retryable` = handler-supplied hint: `true` if a retry is likely to
 * succeed (transient runtime failure), `false` if the failure is
 * config-level / policy-level / non-retryable, `undefined` if not
 * classified.
 */
export class OperationError extends Error {
  readonly errorClass: string;
  readonly errorCode?: string;
  readonly errorDetails?: Record<string, unknown>;
  readonly retryable?: boolean;
  readonly httpStatus?: number;
  /**
   * Tempdoc 550 C3: present on a CONFIRMATION_REQUIRED (428) error — the
   * backend-issued id of the {@link PendingAuthorization} created for this gated
   * dispatch. The consent flow approves by this id (never by re-presenting op+args).
   */
  readonly pendingId?: string;
  /**
   * Tempdoc 550 C3: present on a 428 — the computed gate behavior
   * ('INLINE_CONFIRM' | 'TYPED_CONFIRM'), so the ceremony host renders the right prompt
   * (one-click vs type-the-op-id).
   */
  readonly gateBehavior?: string;

  constructor(
    message: string,
    errorClass: string,
    httpStatus?: number,
    errorCode?: string,
    errorDetails?: Record<string, unknown>,
    retryable?: boolean,
    pendingId?: string,
    gateBehavior?: string,
  ) {
    super(message);
    this.name = 'OperationError';
    this.errorClass = errorClass;
    this.httpStatus = httpStatus;
    this.errorCode = errorCode;
    this.errorDetails = errorDetails;
    this.retryable = retryable;
    this.pendingId = pendingId;
    this.gateBehavior = gateBehavior;
  }
}

interface WireResponseShape {
  success: boolean;
  message?: string;
  executionId?: string;
  structuredData?: Record<string, unknown>;
  errorClass?: string;
  // Slice 3a-2-c Phase B — typed handler-error metadata.
  errorCode?: string;
  errorDetails?: Record<string, unknown>;
  retryable?: boolean;
  // Tempdoc 550 C3 — id of the PendingAuthorization + computed gate on a 428.
  pendingId?: string;
  gateBehavior?: string;
  // Tempdoc 550 P1 — decision context on a 428: the op's risk, reversibility, and a short
  // args summary, so the ceremony shows what's being approved (not just the op id).
  riskTier?: string;
  undoSupported?: boolean;
  argsSummary?: string;
}

export class OperationClient {
  private readonly apiBase: string;
  /** An EXPLICIT fetch override (tests / custom transport). When absent, the global fetch is
   * resolved lazily at call time (see {@link fetchImpl}) — so a shared client (getOperationClient,
   * 574 F2) respects per-test fetch mocks instead of binding whatever fetch existed at construction. */
  private readonly explicitFetch?: typeof fetch;

  constructor(config: OperationClientConfig) {
    // Empty / undefined apiBase = same-origin (relative URLs). This works in
    // Vite dev (the proxy maps /api/* to the backend) AND in Tauri-served
    // production (the desktop app serves from a single origin). Setting an
    // explicit absolute base remains supported for cross-origin / multi-host
    // scenarios + tests.
    this.apiBase = (config.apiBase ?? '').replace(/\/$/, '');
    this.explicitFetch = config.fetchImpl;
  }

  /** The fetch to use: an explicit override if configured, else the CURRENT global fetch resolved
   * per call (so runtime fetch mocks and a shared cached client both work). */
  private get fetchImpl(): typeof fetch {
    return this.explicitFetch ?? globalThis.fetch.bind(globalThis);
  }

  /**
   * Invoke an Operation by id. Throws {@link OperationError} on any failure
   * (HTTP non-2xx, handler-returned failure, network error). Returns the
   * success payload on success.
   */
  async invoke(
    operationId: string,
    request: OperationInvocationRequest = {},
  ): Promise<OperationInvocationSuccess> {
    if (!operationId) {
      throw new OperationError('operationId required', 'BAD_REQUEST');
    }
    const url = `${this.apiBase}/api/operations/${encodeURIComponent(operationId)}/invoke`;
    const body = JSON.stringify({
      args: request.args ?? {},
      idempotencyKey: request.idempotencyKey,
      confirmationToken: request.confirmationToken,
    });

    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    if (request.transport) {
      // Slice 489 §17.5 — FE→backend transport stamping. Header lets the
      // backend OperationsController construct an InvocationProvenance with
      // the right TransportTag (URL_BAR / PALETTE / RAIL / etc.) instead of
      // defaulting to BUTTON.
      headers['X-JustSearch-Transport'] = request.transport;
    }
    let res: Response;
    try {
      res = await this.fetchImpl(url, {
        method: 'POST',
        headers,
        body,
      });
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(`Network error invoking ${operationId}: ${detail}`, 'NETWORK_ERROR');
    }

    let parsed: WireResponseShape;
    try {
      parsed = (await res.json()) as WireResponseShape;
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(
        `Invalid JSON in response for ${operationId} (HTTP ${res.status}): ${detail}`,
        'SERIALIZATION_ERROR',
        res.status,
      );
    }

    if (!parsed.success) {
      // Tempdoc 550 P1: carry the 428 prompt context (risk / reversibility / args summary) on
      // the error's details bag so invokeWithConsent can enrich the ceremony prompt. A 428 is a
      // gate, not a handler error, so errorDetails is otherwise unused here.
      const details =
        parsed.errorClass === 'CONFIRMATION_REQUIRED'
          ? {
              ...(parsed.errorDetails ?? {}),
              riskTier: parsed.riskTier,
              undoSupported: parsed.undoSupported,
              argsSummary: parsed.argsSummary,
            }
          : parsed.errorDetails;
      throw new OperationError(
        parsed.message ?? `Operation ${operationId} failed`,
        parsed.errorClass ?? 'UNKNOWN',
        res.status,
        parsed.errorCode,
        details,
        parsed.retryable,
        parsed.pendingId,
        parsed.gateBehavior,
      );
    }

    return {
      success: true,
      message: parsed.message ?? '',
      executionId: parsed.executionId,
      structuredData: parsed.structuredData,
    };
  }

  /**
   * Tempdoc 550 C3 (Authorize ceremony): the user-consented recovery flow. Invoke the
   * operation; if the backend trust gate refuses with CONFIRMATION_REQUIRED (428) — which
   * carries a backend-issued {@code pendingId} — and the caller signals the user has
   * consented ({@code consented: true}, set after the in-component typed-confirm UX),
   * approve THAT pending (minting a capsule bound to the backend-stored op+args) and
   * re-invoke with the capsule.
   *
   * <p>This replaces the V1 "mint a capsule for arbitrary (op,args) then invoke" pattern:
   * the FE never asks the backend to mint for an op out of thin air — it can only approve a
   * pending the backend created when it actually gated the dispatch. Closes the Tier-0
   * "approve an un-gated op" hole (WA-5).
   *
   * <p>Two consent modes (both end at approve-by-pendingId, never an arbitrary mint):
   *
   * <ul>
   *   <li><b>confirm-first</b> — {@code consented: true}: consent was already gathered
   *       client-side (an in-component typed-confirm), so a 428 is approved unconditionally.
   *   <li><b>invoke-first</b> — {@code requestConsent: fn}: no prior consent; on a 428 the
   *       backend-issued {@link AuthorizationPrompt} is handed to {@code fn} (the unified
   *       ceremony host via the broker), which resolves the human decision. Approved →
   *       approve-by-pendingId + re-invoke; declined → the gate error is rethrown.
   * </ul>
   *
   * <p>For AUTO-gated ops (no 428) this is a single plain invoke. If a 428 arrives with
   * neither consent mode satisfied, the gate error is rethrown unchanged.
   */
  async invokeWithConsent(
    operationId: string,
    request: OperationInvocationRequest = {},
    opts: {
      consented?: boolean;
      requestConsent?: (prompt: {
        pendingId: string;
        operationId: string;
        gateBehavior: string;
        riskTier?: string;
        undoSupported?: boolean;
        argsSummary?: string;
        purpose?: string;
      }) => Promise<{ approved: boolean; allowAlways: boolean }>;
    } = {},
  ): Promise<OperationInvocationSuccess> {
    try {
      return await this.invoke(operationId, request);
    } catch (err: unknown) {
      if (
        err instanceof OperationError &&
        err.errorClass === 'CONFIRMATION_REQUIRED' &&
        err.pendingId
      ) {
        let approved = opts.consented === true;
        let allowAlways = false;
        if (!approved && opts.requestConsent) {
          // Tempdoc 550 P1: enrich the prompt with the 428's decision context (carried on the
          // error's details bag) + the gate message as the purpose.
          const d = err.errorDetails ?? {};
          const decision = await opts.requestConsent({
            pendingId: err.pendingId,
            operationId,
            gateBehavior: err.gateBehavior ?? '',
            ...(typeof d.riskTier === 'string' ? { riskTier: d.riskTier } : {}),
            ...(typeof d.undoSupported === 'boolean' ? { undoSupported: d.undoSupported } : {}),
            ...(typeof d.argsSummary === 'string' && d.argsSummary ? { argsSummary: d.argsSummary } : {}),
            ...(err.message ? { purpose: err.message } : {}),
          });
          approved = decision.approved;
          allowAlways = decision.allowAlways; // tempdoc 550 thesis IV: "allow always" → durable grant
        }
        if (approved) {
          const capsule = await this.approveByPendingId(err.pendingId, allowAlways);
          // Re-invoke with the SAME request args; the capsule binds to the backend-stored
          // args, captured from this same request on the gating invoke.
          return await this.invoke(operationId, { ...request, confirmationToken: capsule });
        }
      }
      throw err;
    }
  }

  /**
   * Tempdoc 550 C3: approve a backend-created PendingAuthorization by id, minting a consent
   * capsule bound to its stored (operationId, args). POST /api/authorizations/approve
   * {@code {pendingId}}. Returns the opaque capsule token. The approve gesture references
   * only the id — it cannot substitute a different op/args (that is the hardening).
   */
  async approveByPendingId(pendingId: string, allowAlways = false): Promise<string> {
    if (!pendingId) {
      throw new OperationError('pendingId required', 'BAD_REQUEST');
    }
    const url = `${this.apiBase}/api/authorizations/approve`;
    let res: Response;
    try {
      res = await this.fetchImpl(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // Tempdoc 550 thesis IV: allowAlways records a durable grant for (op, sourceTier).
        body: JSON.stringify({ pendingId, allowAlways }),
      });
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(
        `Network error approving pending ${pendingId}: ${detail}`,
        'NETWORK_ERROR',
      );
    }
    let parsed: { capsule?: string };
    try {
      parsed = (await res.json()) as { capsule?: string };
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(
        `Invalid JSON from approve for pending ${pendingId} (HTTP ${res.status}): ${detail}`,
        'SERIALIZATION_ERROR',
        res.status,
      );
    }
    if (!res.ok || !parsed.capsule) {
      throw new OperationError(
        `Failed to approve pending ${pendingId} (HTTP ${res.status})`,
        'CAPSULE_MINT_FAILED',
        res.status,
      );
    }
    return parsed.capsule;
  }

  async undo(
    operationId: string,
    executionId: string,
  ): Promise<OperationInvocationSuccess> {
    if (!operationId) throw new OperationError('operationId required', 'BAD_REQUEST');
    if (!executionId) throw new OperationError('executionId required', 'BAD_REQUEST');
    const url = `${this.apiBase}/api/undo/${encodeURIComponent(operationId)}`;
    let res: Response;
    try {
      res = await this.fetchImpl(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ executionId }),
      });
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(`Network error undoing ${operationId}: ${detail}`, 'NETWORK_ERROR');
    }
    let parsed: WireResponseShape;
    try {
      parsed = (await res.json()) as WireResponseShape;
    } catch (err: unknown) {
      const detail = err instanceof Error ? err.message : String(err);
      throw new OperationError(
        `Invalid JSON in undo response for ${operationId} (HTTP ${res.status}): ${detail}`,
        'SERIALIZATION_ERROR',
        res.status,
      );
    }
    if (!parsed.success) {
      throw new OperationError(
        parsed.message ?? `Undo of ${operationId} failed`,
        parsed.errorClass ?? 'UNKNOWN',
        res.status,
      );
    }
    return {
      success: true,
      message: parsed.message ?? '',
      executionId: parsed.executionId,
      structuredData: parsed.structuredData,
    };
  }
}

let sharedClient: OperationClient | null = null;
let sharedBase: string | null = null;

/**
 * tempdoc 574 §17 F2 — the ONE shared OperationClient for the apiBase-only case. The client is
 * stateless (a thin POST wrapper), so re-minting it per component/row is a benign-but-real fork with
 * no single apiBase authority; this returns one shared instance, re-minted only when the apiBase
 * changes. Sites needing a custom `fetchImpl` (resolvePathLazy) or a trust-scoped client (the plugin
 * host) construct directly — they have a reason to.
 */
export function getOperationClient(apiBase: string): OperationClient {
  if (!sharedClient || sharedBase !== apiBase) {
    sharedClient = new OperationClient({ apiBase });
    sharedBase = apiBase;
  }
  return sharedClient;
}
