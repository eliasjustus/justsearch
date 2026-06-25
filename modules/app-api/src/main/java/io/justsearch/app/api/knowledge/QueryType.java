/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.knowledge;

/**
 * Pre-retrieval query type classification (306: Stage A, tempdoc 270 Layer 1).
 *
 * <p>Classified on the Head side from syntactic signals only (no QPP). Used to gate
 * cross-encoder reranking and LLM expansion for query types where they add latency
 * without quality benefit.
 */
public enum QueryType {
  /** Known-item search: file extensions, path fragments, filenames. Skip CE + expansion. */
  NAVIGATIONAL,
  /** Quoted phrase: user wants exact lexical match. Skip expansion. */
  EXACT_MATCH,
  /** Question or multi-term specific query. Full pipeline (default). */
  INFORMATIONAL,
  /** Broad single-term query. Full pipeline. */
  EXPLORATORY
}
