/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.IndexingService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Handler for {@code core.add-watched-root}.
 *
 * <p>Slice 3a-2-c — LibraryView Add Folder migration. Delegates to
 * {@link IndexingService#addWatchedRoot(String, Path)} via lazy supplier
 * (mirrors ClearFailedJobsHandler / ReindexHandler patterns).
 *
 * <p>Args shape: {@code {"path": string, "collection"?: string}}. Path is
 * required; collection defaults to {@code "default"} when absent.
 */
public final class AddWatchedRootHandler implements OperationHandler {

  private static final Logger log = LoggerFactory.getLogger(AddWatchedRootHandler.class);

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private final Supplier<IndexingService> indexingSupplier;

  public AddWatchedRootHandler(Supplier<IndexingService> indexingSupplier) {
    this.indexingSupplier = Objects.requireNonNull(indexingSupplier, "indexingSupplier");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    String pathArg;
    String collection;
    try {
      JsonNode root = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
      JsonNode pathNode = root.get("path");
      if (pathNode == null || !pathNode.isTextual() || pathNode.asString().isBlank()) {
        return OperationResult.failure("Missing required arg: path");
      }
      pathArg = pathNode.asString();
      JsonNode collectionNode = root.get("collection");
      collection =
          collectionNode != null && collectionNode.isTextual() && !collectionNode.asString().isBlank()
              ? collectionNode.asString()
              : "default";
    } catch (Exception e) {
      return OperationResult.failure("Invalid args: " + e.getMessage());
    }

    IndexingService indexing;
    try {
      indexing = indexingSupplier.get();
    } catch (RuntimeException e) {
      log.warn("AddWatchedRootHandler: indexing service supplier threw", e);
      return OperationResult.failure("Indexing service unavailable: " + e.getMessage());
    }
    if (indexing == null) {
      return OperationResult.failure("Indexing service unavailable");
    }

    try {
      Path p = Paths.get(pathArg).toAbsolutePath().normalize();
      // Slice 450 §2.3 — match the REST handler's validation:
      // POST /api/indexing/roots returns INVALID_PATH when the path doesn't
      // resolve to an existing directory. The Operation handler must apply
      // the same check so it can't add a root that no walker will ever
      // visit (silent indexing failure mode).
      if (!Files.isDirectory(p)) {
        return OperationResult.failure(
            "Path does not exist or is not a directory: " + p);
      }
      indexing.addWatchedRoot(collection, p);
      return OperationResult.success(
          "Added watched root " + p, Map.of("path", p.toString(), "collection", collection));
    } catch (Exception e) {
      log.error("AddWatchedRootHandler: addWatchedRoot threw", e);
      return OperationResult.failure("Add watched root failed: " + e.getMessage());
    }
  }
}
