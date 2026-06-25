/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexing.api;

/**
 * Indexing API for submitting documents to the index.
 *
 * <p>Stability: experimental
 */
public interface IndexApi {
  void index(IndexDocument document);

  /** Minimal index document representation. */
  record IndexDocument(java.util.Map<String, Object> fields) {}
}
