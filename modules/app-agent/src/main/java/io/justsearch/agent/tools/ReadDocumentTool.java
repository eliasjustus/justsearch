/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DocumentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Tempdoc 868 §B.2 — the delegate's content-bearing READ: a page of one indexed document's
 * extracted text, addressed by absolute path and character offset.
 *
 * <p>Why it exists (868 §A.1): "open these named documents and summarize each" is the single most
 * requested intent in the recorded runs and had <em>zero</em> clean completions, because no tool
 * read a document — the agent searched, got budgeted excerpts, and summarized those. One run made
 * the gap explicit by inventing a {@code read} op on the HIGH-risk file-operations tool.
 *
 * <p>Why RANGED rather than whole-file (868 §A.5/§B.1): the default context window is 4096 tokens
 * while the median indexed document is ~27 KB, so a whole-file read cannot fit and would be
 * silently clipped to a 4000-char prefix by {@code AgentContextCompressor}'s Layer-2 cap — the
 * founding complaint reproduced. {@link #READ_PAGE_CHARS} sits below that cap by construction, so a
 * page is delivered whole and the model pages forward with the {@code More:} offset the header
 * gives it.
 *
 * <p>Readable universe: whatever the Worker will serve, i.e. INDEXED documents only. The Head never
 * touches document bytes (Hard Invariant #1) — this rides {@code DocumentService.fetchSlice}, which
 * is the {@code FetchDocumentSlice} gRPC. That is a stronger boundary than a path allowlist and
 * needs no new consent posture; un-indexed content stays behind the MEDIUM {@code core_ingest_files}
 * confirm.
 */
public final class ReadDocumentTool {

  private static final Logger LOG = LoggerFactory.getLogger(ReadDocumentTool.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The page size at the default Layer-2 cap (4000): ≈750 tokens, well inside n_ctx 4096. */
  static final int DEFAULT_PAGE_CHARS = 3000;

  /**
   * Headroom kept under the Layer-2 cap for the header line (path + span + {@code More:} offset)
   * and the carrier line's own framing. 600 covers a 400-char path with margin.
   */
  static final int PAGE_HEADROOM_CHARS = 600;

  /**
   * The per-call page size. DERIVED from {@code AgentContextCompressor.MAX_TOOL_RESULT_CHARS}
   * (config {@code agent.maxToolResultChars}, default 4000) rather than a bare literal, so a
   * lowered cap shrinks the page instead of clipping it: Layer-2 truncation must never cut a page —
   * a read that arrives clipped is exactly the excerpt-shaped result this tool exists to replace.
   * {@code ReadDocumentToolTest} pins the arithmetic by running a full page through the real {@code
   * truncate} and asserting it comes back unchanged.
   */
  public static final int READ_PAGE_CHARS =
      Math.max(
          200,
          Math.min(
              DEFAULT_PAGE_CHARS,
              io.justsearch.agent.ToolResultCarrier.layerTwoCapChars() - PAGE_HEADROOM_CHARS));

  /** How long to wait on the Worker fetch before degrading to a failure result. */
  private static final long FETCH_TIMEOUT_MS = 15_000;

  private final SliceFetcher sliceFetcher;
  private final Supplier<List<BrowseTool.RootInfo>> rootsSupplier; // nullable

  public ReadDocumentTool(SliceFetcher sliceFetcher) {
    this(sliceFetcher, (Supplier<List<BrowseTool.RootInfo>>) null);
  }

  public ReadDocumentTool(
      SliceFetcher sliceFetcher, Supplier<List<BrowseTool.RootInfo>> rootsSupplier) {
    this.sliceFetcher = sliceFetcher;
    this.rootsSupplier = rootsSupplier;
  }

  public OperationResult execute(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = MAPPER.readTree(argumentsJson);
      return execute(args);
    } catch (Exception e) {
      LOG.error("ReadDocumentTool execution failed", e);
      return OperationResult.failure("Read error: " + e.getMessage());
    }
  }

  /**
   * The parsed-arguments arm. {@code docId} IS the absolute path in this index (see {@code
   * PreviewController}: "Treat docId as opaque"), so one {@code path} argument addresses both.
   */
  OperationResult execute(JsonNode args) {
    String path = args.has("path") ? args.get("path").asText() : null;
    if (path == null || path.isBlank()) {
      return OperationResult.failure("A document path is required");
    }
    path = path.strip();

    // Same resolve-then-validate shape as SearchTool's path_prefix (SearchTool.java:222-236), with
    // the same degrade-open semantics: no roots configured / roots unavailable ⇒ do not reject.
    if (!AgentToolPaths.looksAbsolute(path) && rootsSupplier != null) {
      String resolved = AgentToolPaths.resolveRelativePath(path, roots());
      if (resolved != null) {
        path = resolved;
      }
    }
    String rejection = validatePath(path);
    if (rejection != null) {
      return OperationResult.failure(rejection);
    }

    int offsetChars = Math.max(0, intArg(args, "offset_chars", 0));
    int requested = intArg(args, "max_chars", READ_PAGE_CHARS);
    int maxChars = requested <= 0 ? READ_PAGE_CHARS : Math.min(requested, READ_PAGE_CHARS);

    DocumentService.DocumentSlice slice;
    try {
      CompletionStage<DocumentService.DocumentSlice> stage =
          sliceFetcher.fetchSlice(path, offsetChars, maxChars);
      if (stage == null) {
        return notFound(path);
      }
      slice = stage.toCompletableFuture().get(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      LOG.warn("Document slice fetch failed for {}: {}", path, e.toString());
      return OperationResult.failure("Could not read \"" + path + "\": " + e.getMessage());
    }
    if (slice == null || !slice.found()) {
      return notFound(path);
    }

    String text = slice.content() == null ? "" : slice.content();
    int startChar = offsetChars;
    int endChar = offsetChars + text.length();
    boolean truncated = slice.truncated();
    int nextOffset = truncated ? Math.max(endChar, slice.nextOffsetChars()) : endChar;
    String title = titleOf(slice, path);

    return OperationResult.success(
        formatPage(path, text, startChar, endChar, truncated, nextOffset),
        buildReadEvidence(path, title, text, startChar, endChar, truncated));
  }

  /**
   * The model-facing text: a header naming the exact span read, then the page on ONE carrier line
   * ({@code ToolResultCarrier.readLine}) so Layer-3 compression can strip the body in a later
   * iteration while the header — the fact that this document WAS read, and where the next page
   * starts — survives.
   */
  private static String formatPage(
      String path, String text, int startChar, int endChar, boolean truncated, int nextOffset) {
    var sb = new StringBuilder();
    sb.append("[read] ").append(path).append(" — chars ").append(startChar).append('–')
        .append(endChar);
    if (truncated) {
      sb.append(" of more; More: call core_read_document again with offset_chars=")
          .append(nextOffset);
    }
    sb.append(System.lineSeparator());
    sb.append(io.justsearch.agent.ToolResultCarrier.readLine(flatten(text)));
    return sb.toString();
  }

  /** The carrier line is ONE line by contract; a page's newlines collapse into it. */
  private static String flatten(String text) {
    return text.replace("\"", "'").replace("\r", "").replace("\n", " ").strip();
  }

  /**
   * Tempdoc 868 §B.3 — the structured half. {@code readResults} is a SECOND producer key alongside
   * {@code searchResults}: {@code AgentSession.contributeGroundingSources} mints from it with
   * {@code acquisition = "opened"}, so an opened-by-name document is never mistaken for a retrieved
   * one (865 §7.6's invariant). Deliberately NOT {@code searchResults} — emitting that key would
   * mint sources indistinguishable from search hits, which is the exact violation the acquisition
   * axis exists to prevent.
   *
   * <p>{@code excerpt} is the page text UNCAPPED: it is what the model actually saw, and {@code
   * AgentCitationResolver} verifies an opened source against this literal rather than re-fetching a
   * chunk from the index.
   */
  private static Map<String, Object> buildReadEvidence(
      String path, String title, String text, int startChar, int endChar, boolean truncated) {
    var item = new LinkedHashMap<String, Object>();
    item.put("path", path);
    item.put("title", title);
    item.put("excerpt", text);
    item.put("startChar", startChar);
    item.put("endChar", endChar);
    item.put("truncated", truncated);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("readResults", List.<Map<String, Object>>of(Map.copyOf(item)));
    return Map.copyOf(evidence);
  }

  private static OperationResult notFound(String path) {
    return OperationResult.failure(
        "Document not found in the index: "
            + path
            + ". Use core_browse_folders to find the absolute path, or core_search_index to search"
            + " inside it.");
  }

  /** Stored {@code title} metadata when present, else the file name. */
  private static String titleOf(DocumentService.DocumentSlice slice, String path) {
    Object stored = slice.metadata() == null ? null : slice.metadata().get("title");
    if (stored != null && !stored.toString().isBlank()) {
      return stored.toString();
    }
    int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
    return cut >= 0 && cut + 1 < path.length() ? path.substring(cut + 1) : path;
  }

  private static int intArg(JsonNode args, String field, int fallback) {
    return args.has(field) && args.get(field).isNumber()
        ? args.get(field).asInt(fallback)
        : fallback;
  }

  private List<BrowseTool.RootInfo> roots() {
    if (rootsSupplier == null) {
      return List.of();
    }
    try {
      List<BrowseTool.RootInfo> got = rootsSupplier.get();
      return got == null ? List.of() : got;
    } catch (Exception e) {
      LOG.warn("Failed to get roots for read path validation", e);
      return List.of();
    }
  }

  /**
   * Validates that {@code path} is absolute and under an indexed root. Returns null when valid.
   * Degrades OPEN exactly as {@code SearchTool.validatePathPrefix} does — no roots supplier, no
   * roots configured, or a throwing supplier all mean "cannot say", and the Worker's own
   * index-membership check is the real boundary regardless.
   */
  private String validatePath(String path) {
    if (rootsSupplier == null) {
      return null;
    }
    List<BrowseTool.RootInfo> rootInfos = roots();
    if (rootInfos.isEmpty()) {
      return null;
    }
    List<String> rootPaths = new ArrayList<>(rootInfos.size());
    for (BrowseTool.RootInfo r : rootInfos) {
      rootPaths.add(r.path());
    }
    return AgentToolPaths.validateAgainstRoots(path, rootPaths, "path");
  }

  /**
   * The document-slice fetch this tool is built on. Satisfied by {@code
   * DocumentService::fetchSlice}, which the Head implements over the Worker's
   * {@code FetchDocumentSlice} RPC — the Head never opens the file itself.
   */
  @FunctionalInterface
  public interface SliceFetcher {
    CompletionStage<DocumentService.DocumentSlice> fetchSlice(
        String docId, int offsetChars, int maxChars);
  }
}
