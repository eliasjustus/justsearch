/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

/** Retry decision surfaced in error metadata for observability/debugging. */
public enum RetryAction {
  RETRY,
  ABORT,
  FALLBACK
}
