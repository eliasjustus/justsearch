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
   * The smallest page worth serving. Below this the tool refuses rather than pages: a 100-char page
   * of a 27 KB document is not a read, it is an excerpt with extra round-trips.
   */
  static final int MIN_PAGE_CHARS = 200;

  /**
   * The per-call page size. DERIVED from {@code AgentContextCompressor.MAX_TOOL_RESULT_CHARS}
   * (config {@code agent.maxToolResultChars}, default 4000) rather than a bare literal, so a
   * lowered cap shrinks the page instead of clipping it: Layer-2 truncation must never cut a page —
   * a read that arrives clipped is exactly the excerpt-shaped result this tool exists to replace.
   * {@code ReadDocumentToolTest} pins the arithmetic by running a full page through the real {@code
   * truncate} and asserting it comes back unchanged.
   *
   * <p>The floor is ZERO, deliberately. A {@code Math.max(MIN_PAGE_CHARS, …)} floor would DEFEAT the
   * derivation at exactly the caps it was added for: under a cap of ~500 the floor wins, the page
   * exceeds the cap, and Layer-2 clips it — the silent failure this constant exists to prevent,
   * reintroduced by the guard meant to bound it. So the arithmetic is allowed to go small and
   * {@link #execute(JsonNode)} refuses out loud when it lands under {@link #MIN_PAGE_CHARS}.
   */
  public static final int READ_PAGE_CHARS =
      Math.max(
          0,
          Math.min(
              DEFAULT_PAGE_CHARS,
              io.justsearch.agent.ToolResultCarrier.layerTwoCapChars() - PAGE_HEADROOM_CHARS));

  /** How long to wait on the Worker fetch before degrading to a failure result. */
  private static final long FETCH_TIMEOUT_MS = 15_000;

  /** Shared tail for every unreadable-document failure: the two tools that could still find it. */
  private static final String RECOVERY_GUIDANCE =
      "Use core_browse_folders to find the absolute path, or core_search_index to search inside it.";

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
    if (READ_PAGE_CHARS < MIN_PAGE_CHARS) {
      // A configuration refusal, not an argument one — checked before any work so the operator sees
      // the cause rather than a stream of uselessly small pages.
      return OperationResult.failure(
          "agent.maxToolResultChars is too small to page a document (it leaves "
              + READ_PAGE_CHARS
              + " chars per page, minimum "
              + MIN_PAGE_CHARS
              + "). Raise it to at least "
              + (MIN_PAGE_CHARS + PAGE_HEADROOM_CHARS)
              + ", or use core_search_index to find passages instead.");
    }
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

    Integer offsetArg = intArg(args, "offset_chars", 0);
    if (offsetArg == null) {
      return badIntArg(args, "offset_chars");
    }
    Integer maxArg = intArg(args, "max_chars", READ_PAGE_CHARS);
    if (maxArg == null) {
      return badIntArg(args, "max_chars");
    }
    int offsetChars = Math.max(0, offsetArg);
    int maxChars = maxArg <= 0 ? READ_PAGE_CHARS : Math.min(maxArg, READ_PAGE_CHARS);

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
      // Tempdoc 878: an unusable slice is not automatically a MISSING one. The producers set
      // `error` ("not_found", "missing_doc_id", the Worker's own prose), and reporting a read
      // failure as "not found in the index" sends the model hunting for a path that exists. The
      // PRESENCE of a reason is the discriminator — never the reason's wording, which would make
      // the Worker's message text a contract.
      String reason = slice == null ? null : slice.error();
      return reason == null ? notFound(path) : unreadable(path, reason);
    }

    String text = slice.content() == null ? "" : slice.content();
    // Flatten ONCE, here, and use the result for BOTH the carrier line and the evidence excerpt.
    // Tempdoc 868 §B.1: the citation matcher verifies an opened source against its literal text, so
    // that literal has to be the string the model was SHOWN. Emitting the raw slice as `excerpt`
    // while showing the flattened page made every read source verified against text that differed
    // from the prompt by every newline and quote in it.
    String pageText = flatten(text);
    int startChar = offsetChars;
    int endChar = offsetChars + text.length();
    boolean truncated = slice.truncated();
    int nextOffset = truncated ? Math.max(endChar, slice.nextOffsetChars()) : endChar;

    if (pageText.isBlank()) {
      // The Worker answers found=true with empty content in two very different situations, and they
      // need different answers. At offset 0 the document is IN the index but has no extracted text
      // (an extraction dropout, tempdoc 790) — a failure, and one that must name the reason, because
      // "no text" and "no tier could read it" are not the same fact to a user. Past offset 0 it just
      // means the previous page ended exactly at the document's end: a normal, successful terminus.
      return offsetChars == 0
          ? noExtractedText(path, slice)
          : OperationResult.success(endOfDocument(path, offsetChars));
    }

    return OperationResult.success(
        formatPage(path, pageText, startChar, endChar, truncated, nextOffset, slice.totalChars()),
        buildReadEvidence(path, titleOf(slice, path), pageText, startChar, endChar, truncated));
  }

  /**
   * The extraction-dropout arm. Fails rather than succeeding with an empty page, because an empty
   * page is worse than useless downstream: it mints an opened source with a BLANK literal, and a
   * blank literal makes {@code DocumentService.matchCitationsAgainst} fall back to an index lookup
   * whose {@code -1} chunk ordinal is clamped to {@code 0} — an opened source silently verified
   * against a chunk nobody read.
   */
  private static OperationResult noExtractedText(
      String path, DocumentService.DocumentSlice slice) {
    String reason = metadata(slice, "extraction_reason_code");
    if (reason.isBlank()) {
      reason = metadata(slice, "extraction_method");
    }
    return OperationResult.failure(
        "No extracted text for "
            + path
            + (reason.isBlank() ? "" : " (reason: " + reason + ")")
            + "; the index has no readable content for this document. Try core_search_index for"
            + " passages from other documents on the topic.");
  }

  /** The normal terminus: a previous page ended exactly at the document's end. */
  private static String endOfDocument(String path, int offsetChars) {
    return "[read] " + path + " — end of document; no more text after char " + offsetChars + ".";
  }

  private static String metadata(DocumentService.DocumentSlice slice, String key) {
    Object value = slice.metadata() == null ? null : slice.metadata().get(key);
    return value == null ? "" : value.toString().strip();
  }

  /**
   * The model-facing text: a header naming the exact span read, then the page on ONE carrier line
   * ({@code ToolResultCarrier.readLine}) so Layer-3 compression can strip the body in a later
   * iteration while the header — the fact that this document WAS read, and where the next page
   * starts — survives.
   *
   * <p>Tempdoc 878: the span is stated OUT OF the whole whenever the producer knows the whole.
   * Without a denominator a model at n_ctx 4096 cannot choose between paging and sampling a ~27 KB
   * document, so it pages to exhaustion. {@code totalChars == 0} means the producer could not say
   * (an older Worker, a source with no total) — the header then reads exactly as it did before,
   * because inventing a total is worse than omitting one.
   */
  private static String formatPage(
      String path,
      String pageText,
      int startChar,
      int endChar,
      boolean truncated,
      int nextOffset,
      int totalChars) {
    var sb = new StringBuilder();
    sb.append("[read] ").append(path).append(" — chars ").append(startChar).append('–')
        .append(endChar);
    if (totalChars > 0) {
      sb.append(" of ").append(totalChars);
    }
    if (truncated) {
      // "of more" is the DEGRADED denominator — it exists only for the case where no real one is
      // available, so it is dropped the moment the real total is in the header.
      if (totalChars <= 0) {
        sb.append(" of more");
      }
      sb.append("; More: call core_read_document again with offset_chars=").append(nextOffset);
    }
    sb.append(System.lineSeparator());
    sb.append(io.justsearch.agent.ToolResultCarrier.readLine(pageText));
    return sb.toString();
  }

  /**
   * The carrier line is ONE line by contract; a page's newlines collapse into it. Called exactly
   * once per read, at the seam — see {@link #execute(JsonNode)} for why the flattened form, not the
   * raw slice, is what the evidence carries.
   */
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
   * <p>{@code excerpt} is the FLATTENED page, uncapped — byte-for-byte the string written onto the
   * carrier line, because {@code AgentCitationResolver} verifies an opened source against this
   * literal instead of re-fetching a chunk, and a literal that is not what the model was shown is
   * not a verification. {@code startChar}/{@code endChar} stay on the RAW slice's coordinates, since
   * they address the document in the index, not the rendering.
   */
  private static Map<String, Object> buildReadEvidence(
      String path, String title, String pageText, int startChar, int endChar, boolean truncated) {
    var item = new LinkedHashMap<String, Object>();
    item.put("path", path);
    item.put("title", title);
    item.put("excerpt", pageText);
    item.put("startChar", startChar);
    item.put("endChar", endChar);
    item.put("truncated", truncated);
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("readResults", List.<Map<String, Object>>of(Map.copyOf(item)));
    return Map.copyOf(evidence);
  }

  private static OperationResult notFound(String path) {
    return OperationResult.failure(
        "Document not found in the index: " + path + ". " + RECOVERY_GUIDANCE);
  }

  /**
   * The slice was unusable AND said why. The reason is passed through verbatim rather than mapped,
   * because the vocabulary belongs to the producers and a map would silently stale; the recovery
   * guidance is still attached, so the model gets a next step either way.
   */
  private static OperationResult unreadable(String path, String reason) {
    return OperationResult.failure(
        "Could not read \"" + path + "\": " + reason + ". " + RECOVERY_GUIDANCE);
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

  /**
   * Tempdoc 878 — three outcomes, not two: the value, the default (field absent), and {@code null}
   * for "present but unusable", which the caller turns into a loud refusal via {@link
   * #badIntArg(JsonNode, String)}.
   *
   * <p>A stringified number is COERCED rather than ignored, because small models emit {@code
   * {"offset_chars": "3000"}} constantly and the old fallback-to-default silently re-served page 1 —
   * a duplicate page that looks to the model like progress, which is the worst failure shape
   * available here. Anything else (a non-numeric string, an object, an array, a boolean) is refused
   * out loud so the model can correct the call instead of reading the same page forever.
   *
   * <p>Tempdoc 877 is building a shared {@code ToolArgs} helper with this same contract; fold this
   * into it when it lands.
   */
  private static Integer intArg(JsonNode args, String field, int fallback) {
    if (!args.has(field) || args.get(field).isNull()) {
      return fallback;
    }
    JsonNode node = args.get(field);
    if (node.isNumber()) {
      return node.asInt(fallback);
    }
    if (node.isString()) {
      try {
        return Integer.valueOf(node.asString().strip());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }

  private static OperationResult badIntArg(JsonNode args, String field) {
    return OperationResult.failure(
        field
            + " must be a whole number, but was "
            + args.get(field)
            + ". Call core_read_document again with "
            + field
            + " as a number, e.g. \"offset_chars\": 3000.");
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
