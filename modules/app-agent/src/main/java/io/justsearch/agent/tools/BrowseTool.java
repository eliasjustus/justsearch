/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.knowledge.FolderBrowseRequest;
import io.justsearch.app.api.knowledge.FolderBrowseResponse;
import io.justsearch.app.api.knowledge.FolderFilesRequest;
import io.justsearch.app.api.knowledge.FolderFilesResponse;
import io.justsearch.configuration.resolved.ConfigStore;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Read-only tool for browsing indexed folder structure. Auto-approved (no user gate).
 *
 * <p>Returns a compact text summary of folders for LLM consumption, including names, file counts,
 * and sizes.
 */
/**
 * Read-only folder-browse tool. Per Phase 12 of tempdoc 429: previously implemented
 * {@code ToolDefinition}; now a plain class invoked via
 * {@link io.justsearch.app.services.registry.operations.handlers.BrowseOperationHandler}.
 */
public final class BrowseTool {
  private static final Logger LOG = LoggerFactory.getLogger(BrowseTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int DEFAULT_MAX_FOLDERS =
      Math.max(1, Math.min(200, resolveBrowseDefaultMaxFolders()));

  private static int resolveBrowseDefaultMaxFolders() {
    ConfigStore cs = ConfigStore.globalOrNull();
    return cs != null ? cs.get().agent().browseDefaultMaxFolders() : 20;
  }
  private static final int MAX_MAX_FOLDERS = 200;

  /** Values that small LLMs commonly send when they mean "show top-level roots." */
  private static final Set<String> ROOT_SENTINELS =
      Set.of("/", ".", "..", "root", "roots", "top", "*");

  private static final int DEFAULT_MAX_FILES = 20;
  private static final int MAX_MAX_FILES = 200;

  private static final String PARAMETER_SCHEMA =
      """
      {
        "type": "object",
        "properties": {
          "parent_path": {
            "type": "string",
            "description": "Folder path to list subfolders of (relative or absolute). Omit to list top-level indexed roots. Use paths from previous browse results."
          },
          "max_folders": {
            "type": "integer",
            "description": "Maximum folders to return (default 20, max 200)",
            "default": 20,
            "maximum": 200
          },
          "list_files": {
            "type": "boolean",
            "description": "If true, list individual files instead of subfolders. Auto-triggers when a folder has no subfolders.",
            "default": false
          },
          "max_files": {
            "type": "integer",
            "description": "Maximum files to return when listing files (default 20, max 200)",
            "default": 20,
            "maximum": 200
          }
        }
      }
      """;

  private final BrowseCallback browseCallback;
  private final FilesCallback filesCallback;
  private final Supplier<List<RootInfo>> rootsSupplier;

  public BrowseTool(
      BrowseCallback browseCallback,
      FilesCallback filesCallback,
      Supplier<List<RootInfo>> rootsSupplier) {
    this.browseCallback = browseCallback;
    this.filesCallback = filesCallback;
    this.rootsSupplier = rootsSupplier;
  }

  /** Backward-compatible constructor without file listing support. */
  public BrowseTool(BrowseCallback browseCallback, Supplier<List<RootInfo>> rootsSupplier) {
    this(browseCallback, null, rootsSupplier);
  }

  /** Per tempdoc 429 §C.G: parameter schema preserved as a constant for unit tests. */
  public static String parameterSchema() {
    return PARAMETER_SCHEMA;
  }

  public OperationResult execute(String argumentsJson) {
    try {
      // --- shared setup: parse all args ---
      String parentPath = null;
      int maxFolders = DEFAULT_MAX_FOLDERS;
      int maxFiles = DEFAULT_MAX_FILES;
      boolean listFiles = false;

      if (argumentsJson != null && !argumentsJson.isBlank()) {
        JsonNode args = MAPPER.readTree(argumentsJson);
        if (args.has("parent_path") && !args.get("parent_path").isNull()) {
          parentPath = args.get("parent_path").asText().strip();
          if (parentPath.isEmpty() || ROOT_SENTINELS.contains(parentPath.toLowerCase())) {
            parentPath = null;
          }
        }
        if (args.has("max_folders")) {
          maxFolders = Math.min(args.get("max_folders").asInt(DEFAULT_MAX_FOLDERS), MAX_MAX_FOLDERS);
          if (maxFolders < 1) maxFolders = DEFAULT_MAX_FOLDERS;
        }
        if (args.has("list_files")) {
          listFiles = args.get("list_files").asBoolean(false);
        }
        if (args.has("max_files")) {
          maxFiles = Math.min(args.get("max_files").asInt(DEFAULT_MAX_FILES), MAX_MAX_FILES);
          if (maxFiles < 1) maxFiles = DEFAULT_MAX_FILES;
        }
      }

      // --- shared setup: resolve + validate path ---
      if (parentPath != null) {
        if (!AgentToolPaths.looksAbsolute(parentPath)) {
          String resolved = resolveRelativeParent(parentPath);
          if (resolved != null) {
            parentPath = resolved;
          }
        }
        String rejection = validateParentPath(parentPath);
        if (rejection != null) {
          return OperationResult.failure(rejection);
        }
      }

      // --- branch: explicit file listing ---
      if (listFiles) {
        if (parentPath == null) {
          return OperationResult.failure(
              "list_files requires a parent_path. Omit list_files to see top-level roots.");
        }
        if (filesCallback == null) {
          return OperationResult.failure("File listing is not available.");
        }
        return executeFileList(parentPath, maxFiles);
      }

      // --- branch: folder listing (with auto-fallback) ---
      return executeFolderList(parentPath, maxFolders);

    } catch (Exception e) {
      LOG.error("BrowseTool execution failed", e);
      return OperationResult.failure("Browse error: " + e.getMessage());
    }
  }

  private OperationResult executeFolderList(String parentPath, int maxFolders) {
    FolderBrowseResponse response;
    if (parentPath == null) {
      List<RootInfo> roots = rootsSupplier.get();
      List<FolderBrowseResponse.Folder> folders =
          roots.stream()
              .map(r -> new FolderBrowseResponse.Folder(r.path(), r.name(), -1, -1, 0))
              .toList();
      response = new FolderBrowseResponse(folders, 0, false);
    } else {
      var request = new FolderBrowseRequest(parentPath, maxFolders);
      response = browseCallback.listFolders(request);
      if (response == null) {
        return OperationResult.failure("Browse returned no response");
      }
    }

    // Auto-fallback: empty folders → try files, fall back to hint on empty files
    if (response.folders().isEmpty() && parentPath != null && filesCallback != null) {
      FolderFilesResponse filesResponse =
          filesCallback.listFiles(
              new FolderFilesRequest(parentPath, DEFAULT_MAX_FILES, List.of()));
      if (filesResponse != null && !filesResponse.files().isEmpty()) {
        return OperationResult.success(formatFileResults(filesResponse, parentPath));
      }
      // files also empty — fall through to original formatResults() with hint logic
    }

    return OperationResult.success(formatResults(response, parentPath));
  }

  private OperationResult executeFileList(String parentPath, int maxFiles) {
    FolderFilesResponse filesResponse =
        filesCallback.listFiles(new FolderFilesRequest(parentPath, maxFiles, List.of()));
    if (filesResponse == null) {
      return OperationResult.failure("File listing returned no response");
    }
    return OperationResult.success(formatFileResults(filesResponse, parentPath));
  }

  private String formatResults(FolderBrowseResponse response, String parentPath) {
    List<FolderBrowseResponse.Folder> folders = response.folders();
    boolean hasParent = parentPath != null && !parentPath.isEmpty();
    if (folders.isEmpty()) {
      if (!hasParent) {
        return "No indexed folders found.";
      }
      String displayParent = toRelativePath(parentPath);
      String msg = "No folders found under \"" + displayParent + "\".";
      if (!AgentToolPaths.looksAbsolute(parentPath)) {
        List<RootInfo> roots = rootsSupplier.get();
        if (!roots.isEmpty()) {
          var hint = new StringBuilder(msg);
          hint.append(" HINT: Use a path starting with one of these root names:");
          for (RootInfo root : roots) {
            hint.append(" \"").append(root.name()).append("\"");
          }
          return hint.toString();
        }
      }
      return msg;
    }

    var sb = new StringBuilder();
    if (hasParent) {
      sb.append(String.format("Folders under \"%s\":%n", toRelativePath(parentPath)));
    } else {
      sb.append(String.format("Top-level indexed folders:%n"));
    }

    for (int i = 0; i < folders.size(); i++) {
      var folder = folders.get(i);
      if (folder.fileCount() < 0) {
        sb.append(String.format("[%d] %s%n", i + 1, folder.name()));
      } else {
        String size = formatSize(folder.totalSizeBytes());
        sb.append(
            String.format(
                "[%d] %s (%d files, %s)%n", i + 1, folder.name(), folder.fileCount(), size));
      }
      sb.append(String.format("    Path: %s%n", toRelativePath(folder.path())));
    }

    sb.append(String.format("%nFound %d folders (took %dms).", folders.size(), response.tookMs()));
    if (response.truncated()) {
      sb.append(" Results truncated — increase max_folders or narrow parent_path.");
    }
    return sb.toString();
  }

  private String formatFileResults(FolderFilesResponse response, String parentPath) {
    List<FolderFilesResponse.FileEntry> files = response.files();
    String displayParent = toRelativePath(parentPath);

    if (files.isEmpty()) {
      return String.format("No files found in \"%s\".", displayParent);
    }

    var sb = new StringBuilder();
    sb.append(String.format("Files in \"%s\":%n", displayParent));

    for (int i = 0; i < files.size(); i++) {
      Map<String, String> fields = files.get(i).fields();
      String filename = fields.getOrDefault("filename", "(unknown)");
      String path = fields.getOrDefault("path", "");
      String sizeStr = fields.getOrDefault("size_bytes", "");

      sb.append(String.format("[%d] %s", i + 1, filename));
      if (!sizeStr.isEmpty()) {
        try {
          sb.append(String.format(" (%s)", formatSize(Long.parseLong(sizeStr))));
        } catch (NumberFormatException e) {
          // skip size
        }
      }
      sb.append(String.format("%n"));
      if (!path.isEmpty()) {
        sb.append(String.format("    Path: %s%n", toRelativePath(path)));
      }
    }

    sb.append(String.format("%nFound %d files", files.size()));
    if (response.totalCount() > files.size()) {
      sb.append(String.format(" (showing %d of %d total)", files.size(), response.totalCount()));
    }
    sb.append(String.format(" (took %dms).", response.tookMs()));
    return sb.toString();
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
    if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
    return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
  }

  /**
   * Converts an absolute path to a relative path by stripping the indexed root prefix. Returns the
   * path unchanged if no root matches or if roots are unavailable.
   */
  String toRelativePath(String absolutePath) {
    List<RootInfo> roots;
    try {
      roots = rootsSupplier.get();
    } catch (Exception e) {
      return absolutePath;
    }
    if (roots == null || roots.isEmpty()) {
      return absolutePath;
    }
    try {
      Path absPath = Path.of(absolutePath).normalize();
      for (RootInfo root : roots) {
        Path rootPath = Path.of(root.path()).normalize();
        if (absPath.startsWith(rootPath)) {
          Path relative = rootPath.relativize(absPath);
          if (relative.toString().isEmpty()) {
            return root.name();
          }
          return root.name() + "/" + relative.toString().replace('\\', '/');
        }
      }
    } catch (InvalidPathException e) {
      // Fall through
    }
    return absolutePath;
  }

  /**
   * Resolves a relative parent_path against indexed roots by matching the first path component
   * against root names. Returns the resolved absolute path, or null if no root matches.
   */
  private String resolveRelativeParent(String relativePath) {
    List<RootInfo> roots;
    try {
      roots = rootsSupplier.get();
    } catch (Exception e) {
      return null;
    }
    return AgentToolPaths.resolveRelativePath(relativePath, roots);
  }

  /**
   * Validates that parent_path is an absolute path under one of the indexed roots. Returns null if
   * valid, or an error message string if rejected.
   */
  private String validateParentPath(String parentPath) {
    List<RootInfo> roots;
    try {
      roots = rootsSupplier.get();
    } catch (Exception e) {
      LOG.warn("Failed to get roots for path validation", e);
      return null; // Degrade gracefully
    }
    if (roots == null || roots.isEmpty()) {
      return null; // No roots configured — allow any path
    }
    List<String> rootPaths = roots.stream().map(RootInfo::path).toList();
    return AgentToolPaths.validateAgainstRoots(parentPath, rootPaths, "parent_path");
  }

  /** Lightweight root info returned by the roots supplier. */
  public record RootInfo(String path, String name) {}

  /** Callback for browsing the indexed folder structure. */
  @FunctionalInterface
  public interface BrowseCallback {
    FolderBrowseResponse listFolders(FolderBrowseRequest request);
  }

  /** Callback for listing individual files in a folder. */
  @FunctionalInterface
  public interface FilesCallback {
    FolderFilesResponse listFiles(FolderFilesRequest request);
  }
}
