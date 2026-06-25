/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api.registry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A tier-agnostic catalog of {@link Plugin} Manifest declarations (tempdoc 560 §4.1/§4.2). Parallels
 * {@link SurfaceCatalog} / {@link OperationCatalog}: one catalog shape, queried by id; first-party
 * (CORE) and third-party (TRUSTED/UNTRUSTED_PLUGIN) plugins live in the same catalog, distinguished
 * only by each plugin's {@link Provenance}.
 */
public interface PluginCatalog extends DeclarationCatalog<Plugin, PluginRef> {
  String namespace();

  List<Plugin> definitions();

  default Optional<Plugin> findById(PluginRef id) {
    return definitions().stream().filter(p -> p.id().equals(id)).findFirst();
  }

  default Optional<Plugin> findByIdValue(String idValue) {
    return definitions().stream().filter(p -> p.id().value().equals(idValue)).findFirst();
  }

  static PluginCatalog of(String namespace, List<Plugin> definitions) {
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(definitions, "definitions");
    final List<Plugin> defs = List.copyOf(definitions);
    return new PluginCatalog() {
      @Override
      public String namespace() {
        return namespace;
      }

      @Override
      public List<Plugin> definitions() {
        return defs;
      }
    };
  }
}
