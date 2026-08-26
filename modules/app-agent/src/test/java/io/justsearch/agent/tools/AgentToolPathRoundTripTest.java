/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DocumentService;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchRequest;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponse;
import io.justsearch.app.api.knowledge.KnowledgeSearchResponseBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tempdoc 877 §2.7 — the round-trip nobody was asserting: the path {@code core_browse_folders}
 * EMITS must be a path every other tool ACCEPTS.
 *
 * <p>Browse emits root-relative paths ({@code BrowseTool.toRelativePath}, a measured 227 §A.6
 * decision), search / read-document / ingest all resolve them, and {@code FileOperationsTool} was
 * the one tool that did not — a model echoing a browse result back as a destination was refused
 * {@code DEST_NOT_SANDBOXED} for naming a file browse had just shown it. Four tools agreeing by
 * luck is not a convention; this test is what makes it one.
 */
final class AgentToolPathRoundTripTest {

  @TempDir Path tempDir;

  private Path root;
  private Path target;
  private AgentToolPaths.RootsView rootsView;

  @BeforeEach
  void setUp() throws IOException {
    root = tempDir.resolve("docs");
    Files.createDirectories(root.resolve("explanation"));
    target = root.resolve("explanation").resolve("overview.md");
    rootsView =
        AgentToolPaths.RootsView.of(
            () -> List.of(new BrowseTool.RootInfo(root.toString(), root.getFileName().toString())));
  }

  @Test
  @DisplayName("a path browse emits is accepted by search, read-document and file-operations alike")
  void browseEmittedPathRoundTripsThroughEveryTool() throws IOException {
    String emitted = pathAsBrowseEmitsIt();
    assertFalse(
        AgentToolPaths.looksAbsolute(emitted),
        "the premise of this test is that browse emits a ROOT-RELATIVE path: " + emitted);
    assertEquals("docs/explanation/overview.md", emitted);

    assertEquals(target.toString(), searchResolves(emitted), "search path_prefix");
    assertEquals(target.toString(), readDocumentResolves(emitted), "read-document path");
    fileOperationsResolves(emitted);
    assertTrue(Files.exists(target), "file-operations must land the file where browse pointed");
  }

  /** What the model actually reads off a {@code core_browse_folders} file listing. */
  private String pathAsBrowseEmitsIt() {
    BrowseTool.BrowseCallback browse = req -> null;
    BrowseTool.FilesCallback files =
        req ->
            new FolderFilesResponse(
                List.of(
                    new FolderFilesResponse.FileEntry(
                        target.toString(),
                        Map.of("filename", "overview.md", "path", target.toString()))),
                1L,
                0L);
    BrowseTool tool = new BrowseTool(browse, files, rootsView);

    OperationResult result =
        tool.execute(
            "{\"parent_path\":\""
                + root.resolve("explanation").toString().replace("\\", "\\\\")
                + "\",\"list_files\":true}");
    assertTrue(result.success(), result.message());

    for (String line : result.message().split("\\R")) {
      String trimmed = line.strip();
      if (trimmed.startsWith("Path: ")) {
        return trimmed.substring("Path: ".length());
      }
    }
    throw new AssertionError("browse emitted no Path: line — " + result.message());
  }

  private String searchResolves(String emitted) {
    var captured = new AtomicReference<KnowledgeSearchRequest>();
    KnowledgeSearchResponse response = KnowledgeSearchResponseBuilder.builder().tookMs(1).build();
    SearchTool.SearchCallback search =
        req -> {
          captured.set(req);
          return response;
        };
    OperationResult result =
        new SearchTool(search, rootsView)
            .execute("{\"query\":\"anything\",\"path_prefix\":\"" + emitted + "\"}");

    assertTrue(result.success(), result.message());
    assertNotNull(captured.get(), "search must have been dispatched");
    assertNotNull(captured.get().filters(), "a path_prefix must produce filters");
    return captured.get().filters().pathPrefix();
  }

  private String readDocumentResolves(String emitted) {
    var seen = new AtomicReference<String>();
    ReadDocumentTool.SliceFetcher fetch =
        (docId, offset, max) -> {
          seen.set(docId);
          return CompletableFuture.completedFuture(
              new DocumentService.DocumentSlice(
                  docId, "page text", Map.of(), true, false, 9, null));
        };
    OperationResult result =
        new ReadDocumentTool(fetch, rootsView).execute("{\"path\":\"" + emitted + "\"}");

    assertTrue(result.success(), result.message());
    return seen.get();
  }

  /** MOVE a scratch file ONTO the path browse named, with that path as the destination. */
  private void fileOperationsResolves(String emitted) throws IOException {
    Path scratch = root.resolve("scratch.md");
    Files.writeString(scratch, "moved content");

    FileOperationsTool tool =
        new FileOperationsTool(
            () -> List.of(root),
            mappings -> mappings.size(),
            new FileOperationLog(tempDir.resolve("data").resolve("file-operations")),
            rootsView);

    OperationResult result =
        tool.execute(
            "{\"operations\":[{\"op\":\"MOVE\",\"source\":\""
                + scratch.toString().replace("\\", "\\\\")
                + "\",\"destination\":\""
                + emitted
                + "\"}]}");

    assertTrue(
        result.success(),
        "a root-relative destination straight out of browse must not be refused: "
            + result.message());
  }
}
