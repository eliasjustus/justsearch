/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.ner;

import java.util.List;

/**
 * Result of NER extraction for a single document.
 *
 * <p>Each list contains deduplicated entity surface forms extracted across all chunks.
 * Values are raw NER spans (not disambiguated); disambiguation happens in Phase C via SQLite sidecar.
 */
public record NerResult(
    List<String> persons, List<String> organizations, List<String> locations) {

  public static final NerResult EMPTY = new NerResult(List.of(), List.of(), List.of());

  public boolean isEmpty() {
    return persons.isEmpty() && organizations.isEmpty() && locations.isEmpty();
  }
}
