/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Privacy class an emitted diagnostic event may carry.
 *
 * <p>Per slice 448 §0 D1 + §4: a single emission may carry multiple classes simultaneously
 * (the empirical scan over 17,701 worker.log lines found ~59% contained user paths and
 * ~1.3% contained config values, with substantial overlap). The wire shape is therefore
 * {@code Set<DataClass>} per channel-default and per-event extension, NOT a single-valued
 * discriminator.
 *
 * <p>Initial set; the enum is intentionally extensible by substrate amendment as new
 * privacy concerns are recognized in third-party library output.
 */
public enum DataClass {

  /** File or directory paths the user has opened, watched, or otherwise referenced. */
  USER_PATHS,

  /** Configuration keys, values, and source attribution (file paths, env, etc.). */
  CONFIG_VALUES,

  /**
   * Exception messages and stack traces. Treated as a privacy-leaking class because
   * exception bodies routinely quote the input that triggered them.
   */
  EXCEPTION_BODIES,

  /** Personally-identifiable data the application or its libraries may have logged. */
  PII
}
