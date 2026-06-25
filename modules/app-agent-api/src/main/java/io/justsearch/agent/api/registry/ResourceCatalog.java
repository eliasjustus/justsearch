/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;

/**
 * Module-owned catalog of Resource entries.
 *
 * <p>Per slice 481 §7 step 1 (2026-05-08): extends {@link PrimitiveCatalog} for the
 * shared shape; per-primitive helpers (none today on Resource) remain extensible here.
 */
public interface ResourceCatalog extends PrimitiveCatalog<Resource, ResourceRef> {

  @Override
  String namespace();

  @Override
  List<Resource> definitions();

  static ResourceCatalog of(String namespace, List<Resource> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Resource> defs = List.copyOf(definitions);
    return new ResourceCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Resource> definitions() {
        return defs;
      }
    };
  }
}
