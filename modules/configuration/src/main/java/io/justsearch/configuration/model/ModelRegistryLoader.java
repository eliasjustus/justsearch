/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.configuration.model;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads and deserializes the v2 model registry from JSON.
 *
 * <p>The registry JSON uses a flat structure that maps directly to the Layer 1 data model types.
 * This loader handles the JSON ↔ Java mapping, including enum deserialization for {@link
 * ModelPrecision} and {@link ExecutionProvider}.
 */
public final class ModelRegistryLoader {

  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build();

  private ModelRegistryLoader() {}

  /** Loads the registry from a classpath resource. */
  public static ModelRegistry loadFromClasspath(String resourcePath) {
    try (InputStream is =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new IOException("Resource not found: " + resourcePath);
      }
      return parseJson(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load model registry from classpath: " + resourcePath, e);
    }
  }

  /** Loads the registry from a file path. */
  public static ModelRegistry loadFromFile(Path path) {
    try (InputStream is = Files.newInputStream(path)) {
      return parseJson(is);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load model registry from file: " + path, e);
    }
  }

  private static ModelRegistry parseJson(InputStream is) {
    RawRegistry raw = JSON.readValue(is, RawRegistry.class);
    if (raw.schemaVersion != 2) {
      throw new IllegalStateException(
          "Unsupported registry schema version: " + raw.schemaVersion + " (expected 2)");
    }
    List<ModelPackage> packages = new ArrayList<>();
    if (raw.packages != null) {
      for (RawPackage rp : raw.packages) {
        packages.add(convertPackage(rp));
      }
    }
    return new ModelRegistry(raw.schemaVersion, raw.purpose, packages);
  }

  private static ModelPackage convertPackage(RawPackage rp) {
    List<ModelVariant> variants = new ArrayList<>();
    if (rp.variants != null) {
      for (RawVariant rv : rp.variants) {
        variants.add(
            new ModelVariant(
                rv.filename,
                ModelPrecision.valueOf(rv.precision),
                ExecutionProvider.valueOf(rv.targetEP),
                rv.sha256,
                rv.sizeBytes,
                rv.downloadUrl));
      }
    }
    List<SupportingFile> supporting = new ArrayList<>();
    if (rp.supportingFiles != null) {
      for (RawSupportingFile rs : rp.supportingFiles) {
        // extract: optional flag (default false) — alpha.15 archive support.
        boolean extract = rs.extract != null && rs.extract;
        supporting.add(
            new SupportingFile(rs.filename, rs.sha256, rs.sizeBytes, rs.downloadUrl, extract));
      }
    }
    return new ModelPackage(
        rp.id,
        rp.label,
        rp.description,
        rp.targetDir,
        variants,
        supporting,
        rp.minVramBytes,
        rp.termsUrl,
        rp.installRoot,
        rp.license,
        CapabilityTier.fromId(rp.tier));
  }

  // Raw deserialization types — match the JSON structure exactly.
  // These are intermediate; the public API returns Layer 1 types.

  private record RawRegistry(int schemaVersion, String purpose, List<RawPackage> packages) {}

  private record RawPackage(
      String id,
      String label,
      String description,
      String targetDir,
      String termsUrl,
      long minVramBytes,
      List<RawVariant> variants,
      List<RawSupportingFile> supportingFiles,
      String installRoot,
      String license,
      String tier) {}

  private record RawVariant(
      String filename,
      String precision,
      String targetEP,
      String sha256,
      long sizeBytes,
      String downloadUrl) {}

  private record RawSupportingFile(
      String filename, String sha256, long sizeBytes, String downloadUrl, Boolean extract) {}
}
