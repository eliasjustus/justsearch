/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.KnowledgeIngestResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tool for ingesting files into the knowledge index. Requires user approval (WRITE safety level).
 *
 * <p>Accepts file or folder paths. Folders are expanded recursively to individual files. After
 * ingestion, the Worker processes files for indexing, text extraction, and embedding.
 */
/**
 * Write-tier ingest tool. Per Phase 12 of tempdoc 429: previously implemented
 * {@code ToolDefinition}; now a plain class invoked via
 * {@link io.justsearch.app.services.registry.operations.handlers.IngestOperationHandler}.
 */
public final class IngestTool {
  private static final Logger LOG = LoggerFactory.getLogger(IngestTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  static final int MAX_PATHS = 100;
  static final int MAX_EXPANDED_FILES = 10_000;

  private static final String PARAMETER_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "paths": {
            "type": "array",
            "description": "File or folder paths to ingest. Accepts absolute or relative paths — relative paths are resolved against indexed roots. Folders are expanded recursively.",
            "items": { "type": "string" },
            "minItems": 1,
            "maxItems": 100
          },
          "explanation": {
            "type": "string",
            "description": "Why these files are being ingested"
          }
        },
        "required": ["paths"]
      }
      """;

  private final IngestCallback ingestCallback;
  private final ScanRootCallback scanRootCallback;
  private final Supplier<List<BrowseTool.RootInfo>> rootsSupplier;

  /**
   * Constructs the agent ingest tool. Tempdoc 418 Phase B made the {@link ScanRootCallback}
   * mandatory; tempdoc 418 Phase C sub-commit A (Slice D, 2026-04-25) deleted the legacy 1- and
   * 2-arg back-compat constructors that defaulted the callback to a local-walk fallback.
   * Production wiring lives in {@code HeadAssembly}; tests pass a stub directly.
   */
  public IngestTool(
      IngestCallback ingestCallback,
      ScanRootCallback scanRootCallback,
      Supplier<List<BrowseTool.RootInfo>> rootsSupplier) {
    this.ingestCallback = ingestCallback;
    this.scanRootCallback = scanRootCallback;
    this.rootsSupplier = rootsSupplier;
  }

  /** Per tempdoc 429 §C.G: parameter schema preserved as a constant for unit tests. */
  public static String parameterSchema() {
    return PARAMETER_SCHEMA;
  }

  public OperationResult execute(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = MAPPER.readTree(argumentsJson);
      JsonNode pathsNode = args.get("paths");
      if (pathsNode == null || !pathsNode.isArray() || pathsNode.isEmpty()) {
        return OperationResult.failure("Paths array is required and must not be empty");
      }
      if (pathsNode.size() > MAX_PATHS) {
        return OperationResult.failure(
            "Too many paths: "
                + pathsNode.size()
                + " exceeds limit of "
                + MAX_PATHS
                + ". Split into smaller batches.");
      }

      // Tempdoc 418 Phase B — directories dispatch to Worker-side ScanRoot RPC; only single
      // files keep the local submitBatch path. Worker-side WorkerIngestionAuthority applies
      // the same skip rules + caller exclude_globs (empty for the agent — agent has no exclude
      // policy).
      List<Path> singleFiles = new ArrayList<>();
      int skippedCount = 0;
      int directoryAccepted = 0;
      List<String> directoryErrors = new ArrayList<>();

      for (JsonNode pathNode : pathsNode) {
        Path input = resolvePath(pathNode.asText());
        if (input == null || !Files.exists(input)) {
          skippedCount++;
          continue;
        }
        if (Files.isDirectory(input)) {
          KnowledgeIngestResponse scanResp = scanRootCallback.scanRoot(input.toString(), List.of());
          directoryAccepted += scanResp.accepted();
          if (scanResp.error() != null && !scanResp.error().isEmpty()) {
            directoryErrors.add(input + ":" + scanResp.error());
          }
        } else if (Files.isRegularFile(input) && Files.isReadable(input)) {
          singleFiles.add(input);
        } else {
          skippedCount++;
        }
      }

      if (singleFiles.isEmpty() && directoryAccepted == 0 && directoryErrors.isEmpty()) {
        return OperationResult.failure("No readable files found in the provided paths");
      }
      if (singleFiles.size() >= MAX_EXPANDED_FILES) {
        return OperationResult.failure(
            "Too many single-file paths: "
                + singleFiles.size()
                + " exceeds limit of "
                + MAX_EXPANDED_FILES
                + ". Pass directories instead so the Worker can scan in bulk.");
      }

      int singleAccepted = 0;
      String singleError = "";
      if (!singleFiles.isEmpty()) {
        KnowledgeIngestResponse fileResp = ingestCallback.ingest(singleFiles);
        singleAccepted = fileResp.accepted();
        singleError = fileResp.error() == null ? "" : fileResp.error();
      }

      String combinedError =
          directoryErrors.isEmpty()
              ? singleError
              : (singleError.isEmpty()
                  ? String.join("; ", directoryErrors)
                  : singleError + "; " + String.join("; ", directoryErrors));
      KnowledgeIngestResponse response =
          new KnowledgeIngestResponse(directoryAccepted + singleAccepted, combinedError);

      return OperationResult.success(formatResult(response, skippedCount));

    } catch (Exception e) {
      LOG.error("IngestTool execution failed", e);
      return OperationResult.failure("Ingest error: " + e.getMessage());
    }
  }

  /**
   * Resolves a path string to an absolute Path. If the path is already absolute, returns it
   * directly. If relative, tries resolving against each indexed root and returns the first match
   * that exists on disk. Returns null if no resolution succeeds.
   */
  private Path resolvePath(String raw) {
    try {
      Path p = Path.of(raw);
      if (p.isAbsolute()) {
        return p.normalize();
      }
      // Relative path — resolve against indexed roots
      List<BrowseTool.RootInfo> roots = rootsSupplier.get();
      for (BrowseTool.RootInfo root : roots) {
        Path candidate = Path.of(root.path()).resolve(p).normalize();
        if (Files.exists(candidate)) {
          LOG.debug("Resolved relative path '{}' against root '{}' → {}", raw, root.path(), candidate);
          return candidate;
        }
      }
      // Also try stripping a leading component that matches the indexed root's folder name.
      // This handles paths like "docs/explanation/file.md" when the indexed root is ".../docs".
      for (BrowseTool.RootInfo root : roots) {
        Path rootPath = Path.of(root.path());
        String rootName = rootPath.getFileName() != null ? rootPath.getFileName().toString() : "";
        if (!rootName.isEmpty() && p.getNameCount() > 1
            && p.getName(0).toString().equals(rootName)) {
          Path stripped = p.subpath(1, p.getNameCount());
          Path candidate = rootPath.resolve(stripped).normalize();
          if (Files.exists(candidate)) {
            LOG.debug(
                "Resolved path '{}' by stripping root-name prefix '{}' → {}",
                raw, rootName, candidate);
            return candidate;
          }
        }
      }
      // No root matched — fall back to absolute resolution (may fail existence check later)
      return p.toAbsolutePath().normalize();
    } catch (Exception e) {
      LOG.warn("Invalid path: '{}'", raw, e);
      return null;
    }
  }

  private String formatResult(KnowledgeIngestResponse response, int skippedPaths) {
    var sb = new StringBuilder();

    if (response.error() == null || response.error().isEmpty()) {
      sb.append(
          String.format("Ingested %d files successfully.", response.accepted()));
    } else {
      sb.append(
          String.format(
              "Ingest completed: %d accepted. Error: %s",
              response.accepted(), response.error()));
    }

    if (skippedPaths > 0) {
      sb.append(String.format(" (%d paths skipped — not found or not readable)", skippedPaths));
    }

    return sb.toString();
  }

  /** Callback for ingesting files into the knowledge index. */
  @FunctionalInterface
  public interface IngestCallback {
    KnowledgeIngestResponse ingest(List<Path> files);
  }

  /**
   * Callback for dispatching a directory scan to the Worker. Tempdoc 418 Phase B —
   * production wiring delegates to {@code KnowledgeHttpApiAdapter.scanRoot}, which calls
   * the server-streaming {@code IngestService.ScanRoot} RPC. Tests can pass a
   * local-fallback (see {@link IngestTool#defaultLocalScanCallback}).
   */
  @FunctionalInterface
  public interface ScanRootCallback {
    KnowledgeIngestResponse scanRoot(String rootPath, List<String> excludeGlobs);
  }
}
