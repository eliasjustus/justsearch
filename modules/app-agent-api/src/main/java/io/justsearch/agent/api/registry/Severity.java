/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

/**
 * Severity level for failure renderings, validator findings, and audit events.
 *
 * <p>Per tempdoc 429 §A.6 + §A.7: validators emit findings tagged with severity;
 * the runner test asserts no ERROR findings against the seed catalog.
 */
public enum Severity {
  /** Informational; no action required. */
  INFO,
  /** Warning; build emits but does not fail. */
  WARNING,
  /** Error; fails the build. */
  ERROR
}
