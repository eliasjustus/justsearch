/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.testsupport.fixtures;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import io.justsearch.testsupport.docs.SampleDoc;
import io.justsearch.testsupport.docs.SampleDocs;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Lightweight deterministic fixture that materialises a small index snapshot for paging harnesses.
 */
public final class MiniIndexFixture implements AutoCloseable {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path SNAPSHOT_ROOT = Path.of("build", "test-support", "mini-index");
  private static final Path FIXTURE_LOG = Path.of("reports", "phase10", "fixtures", "mini-index-fixture.log");

  private final SampleDocs.SampleDocSet docSet;
  private final List<SampleDoc> docs;
  private final long seed;
  private final boolean withAnnVectors;
  private final boolean withIndexerWorker;
  private final Path snapshotDir;
  private final Handles handles;

  private MiniIndexFixture(Builder builder) {
    this.docSet = Objects.requireNonNull(builder.docSet, "docSet");
    this.docs = List.copyOf(docSet.docs());
    this.seed = builder.seed != null ? builder.seed.longValue() : 8675309L;
    this.withAnnVectors =
        builder.withAnnVectors != null ? builder.withAnnVectors.booleanValue() : docSet.withAnnVectors();
    this.withIndexerWorker =
        builder.withIndexerWorker != null
            ? builder.withIndexerWorker.booleanValue()
            : docSet.withIndexerWorker();
    this.snapshotDir = createSnapshotDir();
    this.handles =
        new Handles(
            docSet.resourcePath(),
            docSet.pagingConfig(),
            withAnnVectors,
            withIndexerWorker);
    persistSnapshot();
    logCreation();
  }

  public static Builder builder() {
    return new Builder();
  }

  public Handles handles() {
    return handles;
  }

  public Path snapshotDir() {
    return snapshotDir;
  }

  public List<SampleDoc> docs() {
    return docs;
  }

  @Override
  public void close() {
    // Nothing to release; kept for try-with-resources symmetry.
  }

  private Path createSnapshotDir() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      docs.forEach(doc -> digest.update(doc.id().getBytes(StandardCharsets.UTF_8)));
      digest.update(Long.toString(seed).getBytes(StandardCharsets.UTF_8));
      String hash = HexFormat.of().formatHex(digest.digest()).substring(0, 12);
      Path dir = SNAPSHOT_ROOT.resolve(docSet.name()).resolve(hash);
      Files.createDirectories(dir);
      return dir;
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to create snapshot directory", e);
    }
  }

  private void persistSnapshot() {
    try {
      Files.createDirectories(snapshotDir);
      Path docsPath = snapshotDir.resolve("docs.json");
      Path metadataPath = snapshotDir.resolve("metadata.json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(docsPath.toFile(), docs);
      ObjectNode metadata = MAPPER.createObjectNode();
      metadata.put("sample_set", docSet.name());
      metadata.put("seed", seed);
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(metadataPath.toFile(), metadata);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write snapshot", e);
    }
  }

  private void logCreation() {
    try {
      Files.createDirectories(FIXTURE_LOG.getParent());
      ObjectNode logEntry = MAPPER.createObjectNode();
      logEntry.put("timestamp", Instant.now().toString());
      logEntry.put("sample_set", docSet.name());
      logEntry.put("snapshot", snapshotDir.toString());
      logEntry.put("with_ann_vectors", withAnnVectors);
      logEntry.put("with_indexer_worker", withIndexerWorker);
      String payload = logEntry.toString() + System.lineSeparator();
      Files.writeString(
          FIXTURE_LOG,
          payload,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to append fixture log", e);
    }
  }

  public static final class Builder {
    private SampleDocs.SampleDocSet docSet = SampleDocs.catalogSmoke();
    private Long seed;
    private Boolean withAnnVectors;
    private Boolean withIndexerWorker;

    private Builder() {}

    public Builder docs(SampleDocs.SampleDocSet docSet) {
      this.docSet = Objects.requireNonNull(docSet, "docSet");
      return this;
    }

    public Builder seed(long value) {
      this.seed = value;
      return this;
    }

    public Builder withAnnVectors(boolean value) {
      this.withAnnVectors = value;
      return this;
    }

    public Builder withIndexerWorker(boolean value) {
      this.withIndexerWorker = value;
      return this;
    }

    public MiniIndexFixture build() {
      return new MiniIndexFixture(this);
    }
  }

  public record Handles(
      String pathToSampleDocs,
      String pagingConfig,
      boolean withAnnVectors,
      boolean withIndexerWorker) {}
}
