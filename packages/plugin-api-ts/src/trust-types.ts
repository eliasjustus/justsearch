/**
 * Trust-model types — auto-synced from the monorepo source by
 * scripts/sync-types.mjs. Edit the canonical files in
 * modules/ui-web/src/shell-v0/plugin-api/, NOT this file.
 */

export type PluginTrustTier = 'CORE' | 'TRUSTED_PLUGIN' | 'UNTRUSTED_PLUGIN';

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
