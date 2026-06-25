/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.metrics;

/** String constants for map-based batch timing keys (354). */
public final class BatchTimingKeys {
  public static final String EMBED = "embed";
  public static final String SPLADE = "splade";
  public static final String NER = "ner";
  public static final String FETCH = "fetch";
  public static final String WRITE = "write";
  public static final String TOTAL = "total";

  private BatchTimingKeys() {}
}
