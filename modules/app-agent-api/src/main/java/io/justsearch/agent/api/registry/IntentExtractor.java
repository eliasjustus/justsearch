/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.stream.Stream;

/**
 * Pure-function SPI that converts an {@link IntentSource}'s raw payload shape
 * into one or more {@link Intent} envelopes.
 *
 * <p>Per tempdoc 487 §4.2: one SPI category, many implementations. Each
 * {@link IntentSource} registration names an extractor by {@link #id()}; the
 * source-specific call site retrieves the typed extractor and feeds it the
 * source's payload. The router then consumes the resulting envelopes.
 *
 * <h3>Purity rule (tempdoc §4.2)</h3>
 *
 * <p>Extractors are pure functions: given the same {@code raw} input, they
 * produce the same {@code Stream<Intent>} output, and they perform no I/O or
 * state mutation during the conversion. Specifically, an extractor MUST NOT:
 *
 * <ul>
 *   <li>dispatch intents (that is the router's role),
 *   <li>validate source trust (that is the trust lattice in
 *       {@code OperationExecutorImpl}'s role),
 *   <li>record audit (that is the dispatcher's existing audit hook's role),
 *   <li>branch on operation vs surface (the {@link ShellAddress} partition
 *       already does that),
 *   <li>apply environmental side-effects (e.g., URL hydration restoring
 *       store state — that is the source's bootstrapping code's role,
 *       outside the extractor proper).
 * </ul>
 *
 * <p>Pure-function shape makes extractors trivially unit-testable and
 * trivially composable.
 *
 * @param <R> the raw source-shape payload type (e.g., {@code String} for URL
 *     decoders, a tool-call record for the agent-loop extractor)
 */
public interface IntentExtractor<R> {

  /** Stable id (e.g., {@code core.markdown-url}). */
  String id();

  /** Declaration provenance — gates whether this extractor can register. */
  Provenance provenance();

  /**
   * Convert a raw source-shape payload into zero or more {@link Intent} envelopes.
   *
   * <p>Returning an empty stream means "the input did not yield a parseable
   * intent" (e.g., a chat response containing no URLs). Returning multiple
   * envelopes means "the input yielded several intents in document order"
   * (e.g., a chat response with multiple URLs).
   *
   * <p>Implementations MUST NOT throw on malformed input — return an empty
   * stream instead. Throwing crosses the SPI's pure-function contract.
   */
  Stream<Intent> extract(R raw);
}
