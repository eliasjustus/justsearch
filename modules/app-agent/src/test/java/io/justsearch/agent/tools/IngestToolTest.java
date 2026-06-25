package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestToolTest {

  @TempDir Path tempDir;

  /**
   * Test factory — substitutes the production Worker-side {@link IngestTool.ScanRootCallback}
   * with a local-walk fallback that mirrors the pre-Slice-D back-compat behaviour. Production
   * code now requires the 3-arg {@link IngestTool} constructor; tests that don't exercise the
   * scan path still need a callback that expands directories so the existing assertions hold.
   */
  private static IngestTool toolWithLocalScan(IngestTool.IngestCallback ingestCallback) {
    return toolWithLocalScan(ingestCallback, List::of);
  }

  private static IngestTool toolWithLocalScan(
      IngestTool.IngestCallback ingestCallback,
      Supplier<List<BrowseTool.RootInfo>> rootsSupplier) {
    return new IngestTool(ingestCallback, localScan(ingestCallback), rootsSupplier);
  }

  private static IngestTool.ScanRootCallback localScan(IngestTool.IngestCallback ingest) {
    return (rootPath, excludeGlobs) -> {
      List<Path> expanded = new ArrayList<>();
      try (Stream<Path> stream = Files.walk(Path.of(rootPath))) {
        stream
            .filter(Files::isRegularFile)
            .filter(Files::isReadable)
            .forEach(
                p -> {
                  if (expanded.size() < IngestTool.MAX_EXPANDED_FILES) {
                    expanded.add(p);
                  }
                });
      } catch (IOException e) {
        return new KnowledgeIngestResponse(0, "Local scan fallback failed: " + e.getMessage());
      }
      if (expanded.isEmpty()) {
        return new KnowledgeIngestResponse(0, "");
      }
      return ingest.ingest(expanded);
    };
  }

  @Test
  void parameterSchemaPresent() {
    // Per Phase 12 of tempdoc 429: name/description/safetyLevel/supportsUndo moved to
    // the AgentToolsOperationCatalog Operation declaration.
    assertNotNull(IngestTool.parameterSchema());
  }

  @Test
  void executeWithValidPaths() throws IOException {
    Path file = tempDir.resolve("test.txt");
    Files.writeString(file, "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var tool =
        toolWithLocalScan(
            files -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            });

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(file.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertNotNull(capturedFiles.get());
    assertEquals(1, capturedFiles.get().size());
    assertTrue(result.message().contains("1 files"));
  }

  @Test
  void executeWithEmptyPaths() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("{\"paths\": []}");
    assertFalse(result.success());
    assertTrue(result.message().contains("required"));
  }

  @Test
  void executeWithMissingPathsField() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("{}");
    assertFalse(result.success());
    assertTrue(result.message().contains("required"));
  }

  @Test
  void executeWithDirectoryExpansion() throws IOException {
    Path dir = tempDir.resolve("subdir");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve("a.txt"), "aaa");
    Files.writeString(dir.resolve("b.txt"), "bbb");
    Files.createDirectories(dir.resolve("nested"));
    Files.writeString(dir.resolve("nested").resolve("c.txt"), "ccc");

    var capturedFiles = new AtomicReference<List<Path>>();
    var tool =
        toolWithLocalScan(
            files -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            });

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(dir.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertEquals(3, capturedFiles.get().size(), "Should expand dir to 3 files");
    assertTrue(result.message().contains("3 files"));
  }

  @Test
  void executeWithNonexistentPaths() throws IOException {
    Path existing = tempDir.resolve("exists.txt");
    Files.writeString(existing, "data");
    Path missing = tempDir.resolve("missing.txt");

    var tool =
        toolWithLocalScan(
            files -> new KnowledgeIngestResponse(files.size(), ""));

    String json =
        "{\"paths\": [\"%s\", \"%s\"]}"
            .formatted(
                existing.toString().replace("\\", "\\\\"),
                missing.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertTrue(result.message().contains("1 files"), "Should ingest only existing file: " + result.message());
    assertTrue(result.message().contains("skipped"), "Should mention skipped: " + result.message());
  }

  @Test
  void executeAllPathsMissing() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));

    String json = "{\"paths\": [\"/no/such/file.txt\"]}";
    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("No readable files"));
  }

  @Test
  void executeWithIngestError() throws IOException {
    Path file = tempDir.resolve("error-test.txt");
    Files.writeString(file, "data");

    var tool =
        toolWithLocalScan(
            files -> new KnowledgeIngestResponse(0, "Worker connection lost"));

    String json =
        "{\"paths\": [\"%s\"]}"
            .formatted(file.toString().replace("\\", "\\\\"));

    OperationResult result = tool.execute(json);
    assertTrue(result.success(), "Should succeed even with error (accepted=0 is valid response)");
    assertTrue(result.message().contains("Worker connection lost"), result.message());
  }

  @Test
  void executeInvalidJson() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("not json");
    assertFalse(result.success());
    assertTrue(result.message().contains("error"));
  }

  @Test
  void executeBatchLimitExceeded() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));

    var sb = new StringBuilder("{\"paths\": [");
    for (int i = 0; i <= IngestTool.MAX_PATHS; i++) {
      if (i > 0) sb.append(",");
      sb.append("\"file-").append(i).append(".txt\"");
    }
    sb.append("]}");

    OperationResult result = tool.execute(sb.toString());
    assertFalse(result.success());
    assertTrue(result.message().contains("exceeds limit"), result.message());
    assertTrue(result.message().contains(String.valueOf(IngestTool.MAX_PATHS)));
  }

  @Test
  void expandedFileLimitIsReasonable() {
    // The expansion cap protects against a single directory with millions of files.
    // We can't create 10k files in a unit test, but we verify the constant is sane
    // and the error message format is correct.
    assertTrue(IngestTool.MAX_EXPANDED_FILES >= 1000, "Cap should allow reasonable batches");
    assertTrue(IngestTool.MAX_EXPANDED_FILES <= 100_000, "Cap should prevent memory exhaustion");
  }

  @Test
  void executeNoArgs() {
    var tool = toolWithLocalScan(files -> new KnowledgeIngestResponse(0, ""));
    OperationResult result = tool.execute("");
    assertFalse(result.success());
  }

  @Test
  void relativePathResolvedAgainstRoot() throws IOException {
    // Create a file under tempDir that simulates an indexed root
    Path docsDir = tempDir.resolve("docs");
    Files.createDirectories(docsDir);
    Files.writeString(docsDir.resolve("readme.md"), "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var roots = List.of(new BrowseTool.RootInfo(tempDir.toString(), "root"));
    var tool =
        toolWithLocalScan(
            files -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            () -> roots);

    // Pass a relative path — should resolve against the root
    String json = "{\"paths\": [\"docs/readme.md\"]}";
    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertEquals(1, capturedFiles.get().size());
    assertEquals(
        docsDir.resolve("readme.md").normalize(),
        capturedFiles.get().get(0).normalize());
  }

  @Test
  void relativePathWithRootNamePrefixStripped() throws IOException {
    // Simulates: indexed root = ".../docs", user passes "docs/explanation/file.md"
    // The leading "docs/" component matches the root folder name → should be stripped.
    Path docsRoot = tempDir.resolve("docs");
    Path explanationDir = docsRoot.resolve("explanation");
    Files.createDirectories(explanationDir);
    Files.writeString(explanationDir.resolve("file.md"), "content");

    var capturedFiles = new AtomicReference<List<Path>>();
    var roots = List.of(new BrowseTool.RootInfo(docsRoot.toString(), "docs"));
    var tool =
        toolWithLocalScan(
            files -> {
              capturedFiles.set(files);
              return new KnowledgeIngestResponse(files.size(), "");
            },
            () -> roots);

    // Path includes the root folder name as a prefix — IngestTool should strip it
    String json = "{\"paths\": [\"docs/explanation/file.md\"]}";
    OperationResult result = tool.execute(json);
    assertTrue(result.success(), result.message());
    assertEquals(1, capturedFiles.get().size());
    assertEquals(
        explanationDir.resolve("file.md").normalize(),
        capturedFiles.get().get(0).normalize());
  }

  @Test
  void relativePathNoMatchingRoot() {
    var roots = List.of(new BrowseTool.RootInfo(tempDir.toString(), "root"));
    var tool =
        toolWithLocalScan(
            files -> new KnowledgeIngestResponse(0, ""),
            () -> roots);

    // Relative path that doesn't exist under any root
    String json = "{\"paths\": [\"nonexistent/file.txt\"]}";
    OperationResult result = tool.execute(json);
    assertFalse(result.success());
    assertTrue(result.message().contains("No readable files"));
  }
}
