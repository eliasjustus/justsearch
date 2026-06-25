/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Subscription-side delivery mode for a {@link DiagnosticChannel}.
 *
 * <p>Per slice 448 §4: V1 ships {@link #SSE_STREAM} only. Operator-trace surfaces are
 * structurally a firehose; ONE_SHOT and POLLING modes (which Resources support) are not
 * meaningful for log/trace data. Reserved-but-not-introduced is intentional — adding new
 * values here is a substrate amendment, not a per-channel decision.
 */
public enum DeliveryMode {

  /** Server-Sent Events stream over the universal envelope (slice 436). V1 only. */
  SSE_STREAM
}
