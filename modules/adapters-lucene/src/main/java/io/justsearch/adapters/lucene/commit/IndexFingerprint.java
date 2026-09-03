/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.adapters.lucene.commit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single rebuild-requiring identity of an index: a SHA-256 over a canonical JSON rendering of
 * the <em>effective physical index shape</em> — everything that decides what bytes end up in the
 * Lucene directory, and nothing else.
 *
 * <p>It replaces the five parity keys {@code schema_ver} / {@code schema_fp} /
 * {@code index_schema_fp} / {@code analyzer_fp} / {@code similarity_fp}. Those were untruthful in
 * both directions: {@code schema_ver} tracked the search-intent grammar version and could never
 * fire, while {@code index_schema_fp} hashed the whole catalog <em>file</em>, so an annotation-only
 * edit (an {@code rmwPolicy} added to a field) flipped it against an index that was physically
 * still perfectly compatible (tempdoc 804, tempdoc 915 §C).
 *
 * <h2>What is in, and why</h2>
 *
 * <ul>
 *   <li>{@code catalog_schema_version} — the catalog's own {@code version}; a deliberate,
 *       author-driven break lever.
 *   <li>{@code fields} — the <em>physical projection</em> of each field: {@code id}, {@code type},
 *       {@code stored}, {@code docValues}, {@code multiValued}, {@code analyzer}, {@code roles}
 *       (roles decide the Lucene field construction — {@code filter} picks a keyword/doc-values
 *       shape) and, for vector fields, {@code dimension} + {@code similarity}. Sorted by id;
 *       roles sorted within a field.
 *   <li>{@code analyzer_fp} — the analyzer definitions, because index-time analysis decides the
 *       postings.
 *   <li>{@code vector_format} — {@code float32} vs {@code int8_sq}: a different
 *       {@code KnnVectorsFormat} on disk.
 *   <li>{@code hnsw} — {@code m} and {@code ef_construction}, the two parameters that shape the
 *       graph that is written.
 *   <li>{@code chunking} — target/overlap/minimum tokens plus the splitter's algorithm version:
 *       these decide where chunk documents begin and end.
 *   <li>{@code embedding_model_sha256} / {@code splade_model_sha256} — the models whose output is
 *       stored in the vector and sparse fields.
 * </ul>
 *
 * <h2>What is deliberately out</h2>
 *
 * <ul>
 *   <li>{@code rmwPolicy} field annotations — read-modify-write preservation policy. {@code
 *       FieldMapper} rejects an {@code rmwPolicy} on any stored or doc-values field, so by
 *       construction it can only describe fields that are not read back from disk: it changes
 *       runtime backfill behaviour, never bytes. This exclusion is the specific fix for 804's
 *       over-trigger.
 *   <li>Query-time scoring: BM25 {@code k1}/{@code b} ({@code similarity_fp}), field boosts
 *       ({@code boosts_fp}), and HNSW {@code ef_search}. These change ranking, not storage, so a
 *       change to them must never cost the user a reindex.
 *   <li>The search-intent grammar, prompt packs and templates. They never touched the index.
 * </ul>
 *
 * <h2>Indeterminate inputs are not mismatches</h2>
 *
 * <p>A model fingerprint is tri-state. {@code NOT_CONFIGURED} (no model resolvable for this
 * deployment at all) is a determinate answer and hashes as JSON {@code null}. {@code INDETERMINATE}
 * (a model file is configured but its digest could not be read) is <em>not</em> an answer, and
 * {@link #compute} returns {@link Optional#empty()} rather than inventing one. Callers stamp
 * nothing and compare nothing in that case: a transiently unreadable model file must not be
 * indistinguishable from a swapped one, because the consequence of the latter is a full rebuild
 * (`green-masked-destructive`).
 */
public final class IndexFingerprint {

  /** Key under which the fingerprint is stamped into Lucene commit user data. */
  public static final String COMMIT_META_KEY = "index_fingerprint";

  /**
   * Version of the {@link #compute} rendering itself. Bump it when the canonical JSON shape
   * changes in a way that must invalidate existing indexes even though every input is unchanged.
   */
  public static final String RENDERING_VERSION = "1";

  private static final ObjectMapper M =
      JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

  private IndexFingerprint() {}

  /** Whether a model's identity is known, knowably absent, or simply unavailable right now. */
  public enum ModelState {
    /** A model is configured and its digest was read. */
    PRESENT,
    /** No model of this kind is configured for this deployment — a determinate answer. */
    NOT_CONFIGURED,
    /** A model is configured but its digest could not be resolved — not an answer. */
    INDETERMINATE
  }

  /** Tri-state identity of one model that contributes content to the index. */
  public record ModelFingerprint(ModelState state, String sha) {
    public ModelFingerprint {
      Objects.requireNonNull(state, "state");
    }

    public static ModelFingerprint present(String sha) {
      Objects.requireNonNull(sha, "sha");
      return new ModelFingerprint(ModelState.PRESENT, sha);
    }

    public static ModelFingerprint notConfigured() {
      return new ModelFingerprint(ModelState.NOT_CONFIGURED, null);
    }

    public static ModelFingerprint indeterminate() {
      return new ModelFingerprint(ModelState.INDETERMINATE, null);
    }

    /**
     * Maps an {@code Optional} digest to the tri-state, given whether a model file was found at
     * all. A resolvable path with no digest is {@link ModelState#INDETERMINATE}; no path is
     * {@link ModelState#NOT_CONFIGURED}.
     */
    public static ModelFingerprint of(boolean configured, Optional<String> sha) {
      if (!configured) {
        return notConfigured();
      }
      return sha.map(ModelFingerprint::present).orElseGet(ModelFingerprint::indeterminate);
    }
  }

  /** One field's physical shape. {@code analyzer} and {@code vector} are nullable. */
  public record FieldShape(
      String id,
      String type,
      boolean stored,
      boolean docValues,
      boolean multiValued,
      String analyzer,
      List<String> roles,
      Integer vectorDimension,
      String vectorSimilarity) {
    public FieldShape {
      Objects.requireNonNull(id, "id");
      roles = roles == null ? List.of() : List.copyOf(roles);
    }
  }

  /** Chunk-splitting parameters that decide chunk document boundaries. */
  public record Chunking(
      int targetTokens, int overlapTokens, int minTokens, String algorithmVersion) {}

  /** HNSW graph-construction parameters. Nulls mean "Lucene default". */
  public record Hnsw(Integer m, Integer efConstruction) {}

  /** The complete, ordered input set. */
  public record Inputs(
      String catalogSchemaVersion,
      List<FieldShape> fields,
      String analyzerFingerprint,
      String vectorFormat,
      Hnsw hnsw,
      Chunking chunking,
      ModelFingerprint embeddingModel,
      ModelFingerprint spladeModel) {
    public Inputs {
      Objects.requireNonNull(fields, "fields");
      Objects.requireNonNull(analyzerFingerprint, "analyzerFingerprint");
      Objects.requireNonNull(vectorFormat, "vectorFormat");
      Objects.requireNonNull(hnsw, "hnsw");
      Objects.requireNonNull(chunking, "chunking");
      Objects.requireNonNull(embeddingModel, "embeddingModel");
      Objects.requireNonNull(spladeModel, "spladeModel");
    }
  }

  /**
   * Renders {@code inputs} as canonical JSON and returns its SHA-256, or {@link Optional#empty()}
   * if any model fingerprint is {@link ModelState#INDETERMINATE}.
   */
  public static Optional<String> compute(Inputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    if (inputs.embeddingModel().state() == ModelState.INDETERMINATE
        || inputs.spladeModel().state() == ModelState.INDETERMINATE) {
      return Optional.empty();
    }
    return Optional.of(sha256Hex(canonicalJson(inputs)));
  }

  /**
   * The canonical JSON that {@link #compute} hashes. Exposed so a failing parity check can show
   * an operator <em>which</em> input moved instead of two opaque digests.
   */
  public static byte[] canonicalJson(Inputs inputs) {
    Objects.requireNonNull(inputs, "inputs");
    TreeMap<String, Object> root = new TreeMap<>();
    root.put("rendering_version", RENDERING_VERSION);
    root.put("catalog_schema_version", inputs.catalogSchemaVersion());
    root.put("analyzer_fp", inputs.analyzerFingerprint());
    root.put("vector_format", inputs.vectorFormat());

    TreeMap<String, Object> hnsw = new TreeMap<>();
    hnsw.put("m", inputs.hnsw().m());
    hnsw.put("ef_construction", inputs.hnsw().efConstruction());
    root.put("hnsw", hnsw);

    TreeMap<String, Object> chunking = new TreeMap<>();
    chunking.put("target_tokens", inputs.chunking().targetTokens());
    chunking.put("overlap_tokens", inputs.chunking().overlapTokens());
    chunking.put("min_tokens", inputs.chunking().minTokens());
    chunking.put("algorithm_version", inputs.chunking().algorithmVersion());
    root.put("chunking", chunking);

    root.put("embedding_model_sha256", modelValue(inputs.embeddingModel()));
    root.put("splade_model_sha256", modelValue(inputs.spladeModel()));

    List<FieldShape> sortedFields = new ArrayList<>(inputs.fields());
    sortedFields.sort((a, b) -> a.id().compareTo(b.id()));
    List<Object> fieldNodes = new ArrayList<>(sortedFields.size());
    for (FieldShape f : sortedFields) {
      TreeMap<String, Object> node = new TreeMap<>();
      node.put("id", f.id());
      node.put("type", f.type());
      node.put("stored", f.stored());
      node.put("doc_values", f.docValues());
      node.put("multi_valued", f.multiValued());
      node.put("analyzer", f.analyzer());
      List<String> roles = new ArrayList<>(f.roles());
      java.util.Collections.sort(roles);
      node.put("roles", roles);
      if (f.vectorDimension() != null || f.vectorSimilarity() != null) {
        TreeMap<String, Object> vector = new TreeMap<>();
        vector.put("dimension", f.vectorDimension());
        vector.put("similarity", f.vectorSimilarity());
        node.put("vector", vector);
      }
      fieldNodes.add(node);
    }
    root.put("fields", fieldNodes);

    try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
        JsonGenerator g =
            M.tokenStreamFactory()
                .createGenerator(bos)
                .configure(StreamWriteFeature.AUTO_CLOSE_TARGET, true)) {
      M.writeValue(g, root);
      g.flush();
      return bos.toByteArray();
    } catch (IOException e) {
      throw new IllegalStateException("Failed rendering canonical index-fingerprint JSON", e);
    }
  }

  private static String modelValue(ModelFingerprint fp) {
    return fp.state() == ModelState.PRESENT ? fp.sha() : null;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Process-wide model-fingerprint providers.
  //
  // The fingerprint has to come out identical at three sites that build it independently: the
  // commit path, the parity guard's "expected" snapshot, and the green-cutover verification. Two of
  // its inputs — the embedding and SPLADE model digests — are only knowable in the Worker's model
  // modules, which adapters-lucene cannot depend on. The Worker installs suppliers here once at
  // boot; everything else (Head, tests, tools) sees the NOT_CONFIGURED default, consistently.
  // Same shape as ConfigStore.globalOrNull() and EmbeddingFingerprint's own process-wide cache.
  // ---------------------------------------------------------------------------------------------

  private static final AtomicReference<Supplier<ModelFingerprint>> EMBEDDING_PROVIDER =
      new AtomicReference<>(ModelFingerprint::notConfigured);
  private static final AtomicReference<Supplier<ModelFingerprint>> SPLADE_PROVIDER =
      new AtomicReference<>(ModelFingerprint::notConfigured);

  private static final AtomicReference<Supplier<Integer>> VECTOR_DIMENSION_PROVIDER =
      new AtomicReference<>(() -> null);

  /**
   * Installs the effective vector dimension — the one the {@code FieldMapper} actually builds
   * fields with, which is not always the catalog's declared value (the Worker overrides it to 1024
   * when BGE-M3 is the active sparse model). Returning {@code null} means "use what the catalog
   * declares".
   *
   * <p>Separate from reading {@code index.vector.dimension} out of config, deliberately: the
   * override is env-driven, and a fingerprint that consulted a different source than the field
   * construction would be exactly the kind of near-miss this key exists to eliminate.
   */
  public static void installEffectiveVectorDimension(Supplier<Integer> provider) {
    VECTOR_DIMENSION_PROVIDER.set(Objects.requireNonNull(provider, "provider"));
  }

  /** The installed effective vector dimension, or {@code null} to use the catalog's declaration. */
  public static Integer effectiveVectorDimension() {
    try {
      return VECTOR_DIMENSION_PROVIDER.get().get();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Installs the process-wide model-fingerprint providers. Call once, early, from the Worker. */
  public static void installModelFingerprintProviders(
      Supplier<ModelFingerprint> embedding, Supplier<ModelFingerprint> splade) {
    EMBEDDING_PROVIDER.set(Objects.requireNonNull(embedding, "embedding"));
    SPLADE_PROVIDER.set(Objects.requireNonNull(splade, "splade"));
  }

  /** Restores the NOT_CONFIGURED defaults. For tests that installed a provider. */
  public static void resetModelFingerprintProviders() {
    EMBEDDING_PROVIDER.set(ModelFingerprint::notConfigured);
    SPLADE_PROVIDER.set(ModelFingerprint::notConfigured);
    VECTOR_DIMENSION_PROVIDER.set(() -> null);
  }

  /** The embedding model's tri-state identity, per the installed provider. */
  public static ModelFingerprint embeddingModel() {
    return safeGet(EMBEDDING_PROVIDER.get());
  }

  /** The SPLADE model's tri-state identity, per the installed provider. */
  public static ModelFingerprint spladeModel() {
    return safeGet(SPLADE_PROVIDER.get());
  }

  private static ModelFingerprint safeGet(Supplier<ModelFingerprint> supplier) {
    try {
      ModelFingerprint fp = supplier.get();
      return fp == null ? ModelFingerprint.indeterminate() : fp;
    } catch (RuntimeException e) {
      // A provider that throws has not told us the model is absent — it has told us nothing.
      return ModelFingerprint.indeterminate();
    }
  }
}
