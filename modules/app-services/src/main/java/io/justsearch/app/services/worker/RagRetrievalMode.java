/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.worker;

/**
 * Sealed-style enum of RAG retrieval outcomes. Wire format byte-stable to the strings emitted by
 * {@link RemoteDocumentService} pre-refactor.
 */
public enum RagRetrievalMode {
  RAG("rag"),
  FALLBACK("fallback"),
  ERROR("error");

  private final String wire;

  RagRetrievalMode(String wire) {
    this.wire = wire;
  }

  public String wireValue() {
    return wire;
  }
}
