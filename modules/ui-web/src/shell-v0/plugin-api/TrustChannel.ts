// SPDX-License-Identifier: Apache-2.0
/**
 * Slice 477 H2.3 — TrustChannel: Provenance mint-site.
 *
 * Per 478 §4.D, the registry is a typed CONSUMER of Provenance,
 * never a producer. Trust verdicts come from a TrustChannel
 * implementation that's injected at boot (via the loader's
 * options) or via the session singleton.
 *
 * V1.5.1 ships two implementations:
 *
 *   - {@link StubTrustChannel} — synchronous, always returns
 *     UNTRUSTED. Conservative default. Used when no backend is
 *     reachable or for unit tests.
 *
 *   - {@link RemoteTrustChannel} — calls a backend endpoint
 *     `/api/plugins/verify`. The backend MAY run a real Sigstore
 *     verification (V1.5.2 — sigstore-java) or return a stub
 *     verdict. The FE is agnostic to which.
 *
 * The PluginLoader takes a TrustChannel via options. Callers
 * decide which channel to use:
 *   - Compiled-in plugins: bypass loader; registered as CORE directly.
 *   - Settings UI's "install from URL": uses the session
 *     RemoteTrustChannel (whose verdict reflects backend state).
 *   - Tests: pass StubTrustChannel or a custom mock.
 *
 * 478 §4.D properties enforced here:
 *   - Trust verdicts are output of TrustChannel.verify(); never
 *     decided inline by registry / loader / Settings UI.
 *   - Failed verification produces UNTRUSTED, never throws.
 *   - The verdict is structurally a value-type (TrustVerdict).
 */

import type { PluginTrustTier } from './PluginRegistry.js';

/**
 * Result of a TrustChannel verification. The tier is derived from
 * the verdict by trustVerdictToTier() in PluginTrust.ts; this
 * record carries the diagnostic shape the host uses for UX
 * (provenance badge, Plugins panel).
 */
export interface TrustVerdict {
  /** Trust tier the plugin should run under. */
  readonly tier: PluginTrustTier;
  /**
   * Human-readable explanation of how the verdict was reached.
   * Surfaced in the Settings UI's plugin row + provenance popover.
   * Examples: "signature verified by Sigstore (Fulcio cert from
   * github.com/acme-co)", "signature absent — running as
   * UNTRUSTED", "verification failed — backend unreachable".
   */
  readonly explanation: string;
  /**
   * If verification succeeded, the identity associated with the
   * signature (e.g., GitHub user, OIDC subject). null if the
   * verdict is UNTRUSTED or the channel doesn't expose identity.
   */
  readonly identity: string | null;
}

export interface TrustEvidence {
  /** Plugin source bytes (or hash of them) being verified. */
  readonly source: string;
  /** Optional cosign-style signature bundle (JSON string). */
  readonly signature: string | undefined;
  /** Plugin URL the source was fetched from. Logged for diagnostics. */
  readonly url: string;
}

/**
 * The single mint-site for plugin Provenance. Implementations may
 * be sync or async; the interface is async for forward-compat
 * (RemoteTrustChannel hits the network).
 */
export interface TrustChannel {
  /**
   * Verify a plugin's trust evidence and return a verdict.
   *
   * MUST NOT throw. Internal failures (network down, bundle
   * malformed, etc.) produce a verdict with tier=UNTRUSTED_PLUGIN
   * and an explanation describing the failure.
   */
  verify(evidence: TrustEvidence): Promise<TrustVerdict>;
}

/**
 * Conservative default channel: always UNTRUSTED, regardless of
 * presence/absence of signature. The V1.5.1 alpha posture: until
 * a real verification path exists (Sigstore in V1.5.2), no
 * plugin is trusted by default.
 *
 * Use this in unit tests or as a safe fallback when the remote
 * channel is unavailable.
 */
export const StubTrustChannel: TrustChannel = {
  async verify(evidence: TrustEvidence): Promise<TrustVerdict> {
    void evidence;
    return {
      tier: 'UNTRUSTED_PLUGIN',
      explanation:
        'StubTrustChannel: V1.5.1 alpha conservative default — all plugins ' +
        'run as UNTRUSTED until Sigstore backend (V1.5.2) provides real ' +
        'signature verification.',
      identity: null,
    };
  },
};

/**
 * Verifier that delegates to a backend `/api/plugins/verify`
 * endpoint. The backend MAY run sigstore-java (V1.5.2+) or a
 * simpler stub. The FE is agnostic.
 *
 * Wire shape (request body):
 *   { artifactSha256: string, signature: string | null, url: string }
 *
 * Wire shape (response 200):
 *   { verified: boolean, identity?: string, reason: string }
 *
 * Network failures, non-2xx responses, and malformed responses all
 * produce UNTRUSTED verdicts (with the failure mode in the
 * explanation).
 */
export class RemoteTrustChannel implements TrustChannel {
  private readonly fetchImpl: typeof fetch;

  constructor(
    private readonly apiBase: string,
    // Direct fetch is acceptable here: the plugin verification
    // endpoint is stateless POST with no session/CSRF coupling.
    // The `request()` helper would add token handling we don't
    // want for trust verification.
    // eslint-disable-next-line no-restricted-globals
    fetchImpl: typeof fetch = fetch,
  ) {
    // Bind the receiver to the global realm. A native `fetch` stored as an instance field and then
    // called as `this.fetchImpl(...)` is invoked with `this` = this RemoteTrustChannel, which the
    // browser rejects with "TypeError: Illegal invocation" (WHATWG fetch requires a Window/Worker
    // receiver). The throw is swallowed by verify()'s catch → every plugin is forced UNTRUSTED and
    // the verify POST never leaves the page. Binding once here makes the field safe to call as a
    // method and is a no-op for injected test doubles (plain functions). Mirrors the established
    // idiom in CapabilitiesHandshake / ActionLedgerClient / OperationClient. (560 §28 live fix.)
    this.fetchImpl = fetchImpl.bind(globalThis);
  }

  async verify(evidence: TrustEvidence): Promise<TrustVerdict> {
    let artifactSha256: string;
    try {
      artifactSha256 = await artifactSha256OfSource(evidence.source);
    } catch (err) {
      return untrusted(`source-hash failure: ${describe(err)}`);
    }

    let response: Response;
    try {
      response = await this.fetchImpl(`${this.apiBase}/api/plugins/verify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          artifactSha256,
          signature: evidence.signature ?? null,
          url: evidence.url,
        }),
      });
    } catch (err) {
      return untrusted(`backend unreachable: ${describe(err)}`);
    }

    if (!response.ok) {
      return untrusted(`backend returned HTTP ${response.status}`);
    }

    let body: unknown;
    try {
      body = await response.json();
    } catch (err) {
      return untrusted(`backend response not JSON: ${describe(err)}`);
    }

    if (
      body === null ||
      typeof body !== 'object' ||
      typeof (body as { verified?: unknown }).verified !== 'boolean'
    ) {
      return untrusted('backend response missing required `verified` field');
    }

    const b = body as { verified: boolean; identity?: unknown; reason?: unknown };
    if (b.verified) {
      const identity = typeof b.identity === 'string' ? b.identity : null;
      return {
        tier: 'TRUSTED_PLUGIN',
        explanation:
          typeof b.reason === 'string'
            ? b.reason
            : `verified by backend${identity ? ` (identity: ${identity})` : ''}`,
        identity,
      };
    }
    return untrusted(
      typeof b.reason === 'string'
        ? `backend declined to verify: ${b.reason}`
        : 'backend declined to verify (no reason given)',
    );
  }
}

/**
 * The marker comment a first-party plugin source carries to opt into dev trust. Optional `: <id>`
 * suffix records the declared plugin id (surfaced as the verdict identity). NOT a signature — a
 * dev-only assertion (see {@link FirstPartyTrustChannel}).
 */
export const FIRST_PARTY_MARKER = '@justsearch-first-party';

/**
 * The leading window (chars) in which the first-party marker is honored. The marker is meant to be a
 * file-header comment; restricting to the head means a stray `@justsearch-first-party` deep in code
 * or a string literal cannot grant trust (560 §24).
 */
export const FIRST_PARTY_MARKER_WINDOW = 512;

/**
 * Returns the declared first-party id (empty string for a bare marker), or null if the source carries
 * no first-party marker within the leading {@link FIRST_PARTY_MARKER_WINDOW}.
 */
export function firstPartyMarkerId(source: string): string | null {
  const head = source.slice(0, FIRST_PARTY_MARKER_WINDOW);
  const m = /@justsearch-first-party(?::\s*([\w.-]+))?/.exec(head);
  if (!m) return null;
  return m[1] ?? '';
}

/**
 * Tempdoc 560 §23 — dev-gated first-party trust.
 *
 * The §4.4 PRESENTATION constraint ({@link isPresentationAdmissible} in PresentationVocabulary.ts)
 * drops an UNTRUSTED plugin's own-element surface — a plugin may only mount the constrained `jf-*`
 * host vocabulary unless it is TRUSTED. A first-party plugin that legitimately ships its own UI
 * (e.g. the Token Editor, 560 §22) therefore needs TRUSTED_PLUGIN tier to render. The production
 * trust path is a real signed verdict (RemoteTrustChannel → Sigstore, V1.5.2) that does not exist
 * yet. This channel is the minimal sandboxed stand-in: in dev only, a source carrying the
 * {@link FIRST_PARTY_MARKER} is granted TRUSTED_PLUGIN; everything else delegates unchanged to the
 * real fallback channel (RemoteTrustChannel in the Settings flow). The SES compartment + lockdown
 * still apply to a TRUSTED plugin — only the endowment bundle + presentation admission widen.
 *
 * Safety: in a production build ({@code devMode === false}) this channel never grants trust — it is
 * a pure pass-through to the fallback, so it cannot weaken the prod posture. It is trust-by-
 * assertion behind a dev gate (533's "first-party manifest field" open question, the minimal form),
 * NOT signature verification; the real signed path supersedes it when it lands.
 */
export class FirstPartyTrustChannel implements TrustChannel {
  constructor(
    private readonly fallback: TrustChannel,
    private readonly devMode: boolean = (import.meta as ImportMeta & { env?: { DEV?: boolean } }).env
      ?.DEV ?? false,
  ) {}

  async verify(evidence: TrustEvidence): Promise<TrustVerdict> {
    if (this.devMode) {
      const declaredId = firstPartyMarkerId(evidence.source);
      if (declaredId !== null) {
        // Make the elevation visible: a dev-trust grant bypasses real verification, so it should
        // never be silent. (Not a signature — the real signed path supersedes this; 560 §23/§24.)
        console.warn(
          `[FirstPartyTrustChannel] dev-only first-party trust GRANTED` +
            (declaredId ? ` to '${declaredId}'` : '') +
            ` (${evidence.url}) — NOT a signature`,
        );
        return {
          tier: 'TRUSTED_PLUGIN',
          explanation:
            `first-party dev trust: source declares '${FIRST_PARTY_MARKER}'` +
            (declaredId ? ` (${declaredId})` : '') +
            ' — dev-only assertion, not a signature (560 §23)',
          identity: declaredId || 'first-party (dev)',
        };
      }
    }
    return this.fallback.verify(evidence);
  }
}

function untrusted(explanation: string): TrustVerdict {
  return {
    tier: 'UNTRUSTED_PLUGIN',
    explanation,
    identity: null,
  };
}

function describe(err: unknown): string {
  if (err instanceof Error) return err.message;
  return String(err);
}

/**
 * Tempdoc 560 §28 — the single authority for a plugin artifact's SHA-256 (lowercase hex). The
 * operator-approval flow (Settings → "Approve & trust") and {@link RemoteTrustChannel}'s verify both
 * derive the hash from the source bytes the same way, so the digest an operator approves is exactly
 * the one the backend allowlist later matches against the verify request.
 */
export async function artifactSha256OfSource(source: string): Promise<string> {
  return sha256Hex(new TextEncoder().encode(source));
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  // Web Crypto SubtleCrypto is endowed by the host realm; not
  // restricted in V1.5.1 alpha. The SHA-256 of plugin source is
  // what the backend matches against the signed bundle's
  // artifact digest.
  // SubtleCrypto's BufferSource type rejects Uint8Array<SharedArrayBuffer>
  // unions in newer TS definitions. Re-wrap into a plain ArrayBuffer
  // slice to satisfy the type checker.
  const buf = bytes.buffer.slice(
    bytes.byteOffset,
    bytes.byteOffset + bytes.byteLength,
  ) as ArrayBuffer;
  const hashBuf = await crypto.subtle.digest('SHA-256', buf);
  const view = new Uint8Array(hashBuf);
  let hex = '';
  for (let i = 0; i < view.length; i++) {
    hex += view[i]!.toString(16).padStart(2, '0');
  }
  return hex;
}
