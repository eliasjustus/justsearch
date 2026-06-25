// SPDX-License-Identifier: Apache-2.0
/**
 * Phase 5a of slice 3a-1-8: per-envelope contract version tagging — FE-side
 * opt-in diagnostic helper. Pairs symmetrically with the Java-side
 * `WireContractVersion` at `modules/app-api/.../stream/WireContractVersion.java`.
 *
 * Per slice 3a-1-8e (rewrite, 2026-05-07): this helper is preserved as an
 * opt-in diagnostic affordance for streams that want self-describing frames
 * (replay logs, trace export, integration test fixtures). It is NOT the
 * substrate's primary runtime-continuous negotiation mechanism. The
 * substrate's runtime-continuous commitment is discharged at the Resource
 * layer via mid-session evolution events on `/infra/capabilities/stream`;
 * see `10-kernel/05-contract-substrate.md` §"Runtime Negotiation" +
 * `50-decisions/09-contract-substrate.md` §"Why runtime negotiation lives
 * at the Resource layer".
 *
 * Consumers of the diagnostic tag read the `contractVersion` field via
 * {@link readContractVersion} for trace correlation; mid-session contract
 * evolution is handled by Resource-layer subscription, not by per-envelope
 * inspection. Absent field means the producer hasn't opted in to the
 * diagnostic helper.
 */

/** Current wire-Category contract version. Mirror of `WireContractVersion.CURRENT`. */
export const WIRE_CONTRACT_VERSION = '0.2.0';

/** Field name on the SSE envelope's payload object. */
export const WIRE_CONTRACT_VERSION_FIELD = 'contractVersion';

/**
 * Reads the contract version from a wire payload object, if present.
 *
 * @returns the version string if the producer tagged the frame; `undefined`
 *   otherwise (consumer falls back to handshake's reported version).
 */
export function readContractVersion(payload: unknown): string | undefined {
  if (typeof payload !== 'object' || payload === null) return undefined;
  const value = (payload as { contractVersion?: unknown }).contractVersion;
  return typeof value === 'string' ? value : undefined;
}
