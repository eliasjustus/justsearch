/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.core.analyzers;

/**
 * Registry of analyzers available to the engine.
 *
 * <p>Stability: experimental
 */
public interface AnalyzerRegistry {
  AnalyzerDescriptor descriptor(String id);
}
