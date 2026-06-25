/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.runtime;

/**
 * Guard that enforces analyzer/similarity/schema/boosts parity on shard open.
 *
 * <p>Stability: experimental
 */
public interface IndexOpenGuard {
  /** Perform parity checks and throw if parity cannot be guaranteed. */
  void checkOnOpen();
}
