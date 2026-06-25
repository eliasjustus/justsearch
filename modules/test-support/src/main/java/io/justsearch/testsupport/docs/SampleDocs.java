/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.docs;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Loader for deterministic document sets consumed by {@code MiniIndexFixture}. */
public final class SampleDocs {
  private static final String BASE_PATH = "test-support/sample-docs/";
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, SampleDocSet> CACHE = new ConcurrentHashMap<>();

  private SampleDocs() {}

  public static SampleDocSet catalogSmoke() {
    return byName("catalog-smoke");
  }

  public static SampleDocSet pagingStress() {
    return byName("paging-stress");
  }

  public static SampleDocSet budgetTight() {
    return byName("budget-tight");
  }

  public static SampleDocSet byName(String name) {
    Objects.requireNonNull(name, "name");
    return CACHE.computeIfAbsent(name, SampleDocs::load);
  }

  private static SampleDocSet load(String name) {
    String resource = BASE_PATH + name + ".json";
    try (InputStream in = SampleDocs.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Missing sample docs resource " + resource);
      }
      DocSetSpec spec = MAPPER.readValue(in, DocSetSpec.class);
      List<SampleDoc> docs =
          Optional.ofNullable(spec.docs())
              .orElse(Collections.emptyList())
              .stream()
              .map(DocSpec::toSampleDoc)
              .toList();
      String pagingConfig =
          Optional.ofNullable(spec.pagingConfig()).filter(s -> !s.isBlank()).orElse("search_after");
      return new SampleDocSet(
          Optional.ofNullable(spec.name()).filter(s -> !s.isBlank()).orElse(name),
          resource,
          pagingConfig,
          spec.withAnnVectors(),
          spec.withIndexerWorker(),
          docs);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read sample docs " + resource, e);
    }
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DocSetSpec(
      String name,
      @JsonProperty("paging_config") String pagingConfig,
      @JsonProperty("with_ann_vectors") boolean withAnnVectors,
      @JsonProperty("with_indexer_worker") boolean withIndexerWorker,
      List<DocSpec> docs) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record DocSpec(
      String id,
      String title,
      String body,
      Map<String, List<String>> facets,
      Map<String, Object> metadata,
      List<Double> embedding) {
    SampleDoc toSampleDoc() {
      return new SampleDoc(id, title, body, facets, metadata, embedding);
    }
  }

  /** Immutable view over a named set of documents shipped with the repository. */
  public static final class SampleDocSet {
    private final String name;
    private final String resourcePath;
    private final String pagingConfig;
    private final boolean withAnnVectors;
    private final boolean withIndexerWorker;
    private final List<SampleDoc> docs;

    SampleDocSet(
        String name,
        String resourcePath,
        String pagingConfig,
        boolean withAnnVectors,
        boolean withIndexerWorker,
        List<SampleDoc> docs) {
      this.name = Objects.requireNonNull(name, "name");
      this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
      this.pagingConfig = Objects.requireNonNull(pagingConfig, "pagingConfig");
      this.withAnnVectors = withAnnVectors;
      this.withIndexerWorker = withIndexerWorker;
      this.docs = List.copyOf(docs);
    }

    public String name() {
      return name;
    }

    public String resourcePath() {
      return resourcePath;
    }

    public String pagingConfig() {
      return pagingConfig;
    }

    public boolean withAnnVectors() {
      return withAnnVectors;
    }

    public boolean withIndexerWorker() {
      return withIndexerWorker;
    }

    public List<SampleDoc> docs() {
      return docs;
    }
  }
}
