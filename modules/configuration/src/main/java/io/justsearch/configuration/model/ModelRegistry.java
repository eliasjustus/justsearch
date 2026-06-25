/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.util.List;

/**
 * v2 model registry — package-based model definitions with variant metadata.
 *
 * <p>Replaces the v1 flat asset list ({@code model-registry.v1.json}) with a structured format
 * where each model is a package containing variants (CPU/GPU), supporting files, and metadata.
 *
 * @param schemaVersion always 2
 * @param purpose human-readable description of this registry
 * @param packages the model packages in this registry
 */
public record ModelRegistry(int schemaVersion, String purpose, List<ModelPackage> packages) {

  public ModelRegistry {
    if (packages == null) packages = List.of();
  }

  /** Finds a model package by ID, or null if not found. */
  public ModelPackage findPackage(String id) {
    return packages.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
  }
}
