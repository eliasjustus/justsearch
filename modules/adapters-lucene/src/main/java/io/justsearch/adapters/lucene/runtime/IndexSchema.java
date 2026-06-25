/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.runtime;

import io.justsearch.adapters.lucene.analyzers.SsotAnalyzerRegistry;
import io.justsearch.adapters.lucene.commit.JsonSchemaCommitMetadataValidator;
import io.justsearch.adapters.lucene.commit.SsotCommitMetadataSource;
import io.justsearch.configuration.FieldCatalogDef;
import io.justsearch.indexing.runtime.CommitMetadataSource;
import io.justsearch.indexing.runtime.CommitMetadataValidator;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Immutable schema value for the Lucene runtime — sharable across runtimes,
 * holds no lifecycle resources.
 *
 * <p>Tempdoc 406 substrate. The schema captures the catalog-derived
 * configuration that does not change per open period: field mapping,
 * analyzer registry, commit metadata wiring, and an optional KNN vector
 * format override. Lifecycle resources (Directory, IndexWriter,
 * SearcherManager, CRTRT) live on per-phase {@code RuntimeSession} values
 * built via {@link LuceneRuntimeBuilder}.
 *
 * <p>Pre-flight verified the analyzer registry holds only additive
 * caches and Lucene {@code Analyzer} instances (thread-safe). Sharing a
 * single {@code IndexSchema} across multiple runtimes is therefore safe.
 */
public record IndexSchema(
    FieldMapper fieldMapper,
    SsotAnalyzerRegistry analyzerRegistry,
    Supplier<CommitMetadataSource> metadataSourceSupplier,
    CommitMetadataValidator metadataValidator,
    KnnVectorsFormat knnVectorsFormatOverride) {

  public IndexSchema {
    Objects.requireNonNull(fieldMapper, "fieldMapper");
    Objects.requireNonNull(analyzerRegistry, "analyzerRegistry");
    Objects.requireNonNull(metadataSourceSupplier, "metadataSourceSupplier");
    Objects.requireNonNull(metadataValidator, "metadataValidator");
    // knnVectorsFormatOverride may be null (use default)
  }

  /**
   * Builds a schema from a field catalog with default SSOT-backed metadata
   * wiring (matches {@code IndexRuntimeFactory.createLifecycleManager} defaults).
   */
  public static IndexSchema fromCatalog(FieldCatalogDef catalog) {
    Objects.requireNonNull(catalog, "catalog");
    return new IndexSchema(
        new FieldMapper(catalog),
        new SsotAnalyzerRegistry(),
        SsotCommitMetadataSource::new,
        new JsonSchemaCommitMetadataValidator(),
        null);
  }

  /**
   * Builds a schema from a field catalog with caller-supplied commit metadata
   * source + validator (overrides the SSOT-backed defaults).
   */
  public static IndexSchema fromCatalog(
      FieldCatalogDef catalog, CommitMetadataSource source, CommitMetadataValidator validator) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(validator, "validator");
    return new IndexSchema(
        new FieldMapper(catalog),
        new SsotAnalyzerRegistry(),
        () -> source,
        validator,
        null);
  }

  /** Variant that accepts a metadata source supplier (for late-bound metadata). */
  public static IndexSchema fromCatalog(
      FieldCatalogDef catalog,
      Supplier<CommitMetadataSource> sourceSupplier,
      CommitMetadataValidator validator) {
    Objects.requireNonNull(catalog, "catalog");
    Objects.requireNonNull(sourceSupplier, "sourceSupplier");
    Objects.requireNonNull(validator, "validator");
    return new IndexSchema(
        new FieldMapper(catalog),
        new SsotAnalyzerRegistry(),
        sourceSupplier,
        validator,
        null);
  }

  /** Begin building a runtime targeting a persistent index path. */
  public LuceneRuntimeBuilder atPath(Path indexPath) {
    return new LuceneRuntimeBuilder(this, Objects.requireNonNull(indexPath, "indexPath"));
  }

  /** Begin building a runtime targeting an ephemeral (auto-temp) index path. */
  public LuceneRuntimeBuilder ephemeral() {
    return new LuceneRuntimeBuilder(this, null);
  }

  /** Returns the expected vector dimension from the SSOT field catalog, or null if not configured. */
  public Integer ssotVectorDimension() {
    return fieldMapper.ssotVectorDimensionOrNull();
  }

  /**
   * Validates that all required fields exist in the field catalog. Call at startup to fail fast on
   * schema misconfigurations.
   */
  public void validateIndexableFields(Set<String> requiredFields) {
    Set<String> catalogFields = fieldMapper.fieldDefs().keySet();
    Set<String> missing = new java.util.HashSet<>(requiredFields);
    missing.removeAll(catalogFields);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "SCHEMA MISMATCH: Field catalog missing required fields: "
              + missing
              + ". Add them to SSOT/catalogs/fields.v1.json to fix.");
    }
    Logger log = LoggerFactory.getLogger(IndexSchema.class);
    log.info(
        "Schema validation passed: {} indexable fields verified in catalog",
        requiredFields.size());
  }
}
