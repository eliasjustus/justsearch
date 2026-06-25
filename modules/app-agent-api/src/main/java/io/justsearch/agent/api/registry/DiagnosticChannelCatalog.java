/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Module-owned catalog of {@link DiagnosticChannel} entries.
 *
 * <p>Per slice 481 §7 step 1 (2026-05-08): extends {@link PrimitiveCatalog} — the
 * deferral note flagged at slice 448 §4 ("the shared substrate extraction is deferred
 * to phase 2") is now closed.
 */
public interface DiagnosticChannelCatalog
    extends PrimitiveCatalog<DiagnosticChannel, DiagnosticChannelRef> {

  @Override
  String namespace();

  @Override
  List<DiagnosticChannel> definitions();

  static DiagnosticChannelCatalog of(String namespace, List<DiagnosticChannel> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<DiagnosticChannel> defs = List.copyOf(definitions);
    return new DiagnosticChannelCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<DiagnosticChannel> definitions() {
        return defs;
      }
    };
  }
}
