/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.configuration.resolved.ResolvedConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * NRT reopen strategy, resolved from {@code index.nrt.mode} (tempdoc 885 item 19).
 *
 * <p>The two arms exist to be measured against each other; the winner becomes the default. Until
 * then {@link #CONTINUOUS} is the default and is bit-identical to the pre-885 behaviour.
 */
enum NrtMode {

  /**
   * Today's behaviour: the {@link org.apache.lucene.search.ControlledRealTimeReopenThread} reopens
   * on the {@code index.nrt.target_max_stale_ms} / {@code index.nrt.max_stale_ms} bounds (500 ms /
   * 50 ms by default) regardless of whether anyone is searching, and a foreground search acquires
   * whatever the last background reopen produced.
   */
  CONTINUOUS,

  /**
   * The candidate: the background reopen thread drops to {@code index.nrt.background_reopen_ms},
   * and every foreground searcher acquisition refreshes first (blocking past {@code
   * index.nrt.on_demand_max_stale_ms}). This moves the segment-open cost onto the first query after
   * new documents rather than paying it on every background tick.
   */
  ON_DEMAND;

  private static final Logger log = LoggerFactory.getLogger(NrtMode.class);

  /**
   * Parses the configured wire value. Null, blank and unrecognised values resolve to {@link
   * #CONTINUOUS}; unrecognised values additionally WARN, because this knob exists to be A/B
   * measured and a typo must not silently select the arm the operator did not ask for.
   */
  static NrtMode parse(String raw) {
    if (raw == null || raw.isBlank()) return CONTINUOUS;
    String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
    if (ResolvedConfig.Index.NRT_MODE_ON_DEMAND.equals(v)) return ON_DEMAND;
    if (ResolvedConfig.Index.NRT_MODE_CONTINUOUS.equals(v)) return CONTINUOUS;
    log.warn(
        "Unrecognised index.nrt.mode '{}'; falling back to '{}' (valid: '{}', '{}')",
        raw,
        ResolvedConfig.Index.NRT_MODE_CONTINUOUS,
        ResolvedConfig.Index.NRT_MODE_CONTINUOUS,
        ResolvedConfig.Index.NRT_MODE_ON_DEMAND);
    return CONTINUOUS;
  }
}
