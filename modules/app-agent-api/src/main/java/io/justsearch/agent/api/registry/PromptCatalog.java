/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Module-owned catalog of Prompt entries.
 *
 * <p>Per slice 481 §7 step 1 (2026-05-08): extends {@link PrimitiveCatalog} for the
 * shared shape.
 */
public interface PromptCatalog extends PrimitiveCatalog<Prompt, PromptRef> {

  @Override
  String namespace();

  @Override
  List<Prompt> definitions();

  static PromptCatalog of(String namespace, List<Prompt> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Prompt> defs = List.copyOf(definitions);
    return new PromptCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Prompt> definitions() {
        return defs;
      }
    };
  }
}
