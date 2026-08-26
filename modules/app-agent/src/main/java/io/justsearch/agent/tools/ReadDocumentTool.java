/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.app.api.DocumentService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import tools.jackson.databind.JsonNode;

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

  private final SliceFetcher sliceFetcher;
  private final AgentToolPaths.RootsView rootsView;

  public ReadDocumentTool(SliceFetcher sliceFetcher) {
    this(sliceFetcher, (Supplier<List<BrowseTool.RootInfo>>) null);
  }

  public ReadDocumentTool(
      SliceFetcher sliceFetcher, Supplier<List<BrowseTool.RootInfo>> rootsSupplier) {
    this(sliceFetcher, AgentToolPaths.RootsView.of(rootsSupplier));
  }

  /** Tempdoc 877 §2.4 — the shared roots view {@code AgentToolFactory.assemble} builds once. */
  public ReadDocumentTool(SliceFetcher sliceFetcher, AgentToolPaths.RootsView rootsView) {
    this.sliceFetcher = sliceFetcher;
    this.rootsView = rootsView == null ? AgentToolPaths.RootsView.of(null) : rootsView;
  }

  public OperationResult execute(String argumentsJson) {
    if (argumentsJson == null || argumentsJson.isBlank()) {
      return OperationResult.failure("No arguments provided");
    }
    try {
      JsonNode args = ToolArgs.parse(argumentsJson);
      return execute(args);
    } catch (Exception e) {
      return AgentToolErrors.classify("core_read_document", "Read error", e);
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
    String path = ToolArgs.stringArg(args, "path");
    if (path == null || path.isBlank()) {
      return OperationResult.failure("A document path is required");
    }
    path = path.strip();

    // Same resolve-then-validate shape as SearchTool's path_prefix (SearchTool.java:222-236), with
    // the same degrade-open semantics: no roots configured / roots unavailable ⇒ do not reject.
    if (!AgentToolPaths.looksAbsolute(path)) {
      String resolved = rootsView.resolveRelative(path);
      if (resolved != null) {
        path = resolved;
      }
    }
    String rejection = rootsView.validate(path, "path");
    if (rejection != null) {
      return OperationResult.failure(rejection);
    }

    int offsetChars = ToolArgs.intArg(args, "offset_chars", 0, 0, Integer.MAX_VALUE);
    int maxChars = ToolArgs.intArg(args, "max_chars", READ_PAGE_CHARS, 1, READ_PAGE_CHARS);

    DocumentService.DocumentSlice slice;
    try {
      CompletionStage<DocumentService.DocumentSlice> stage =
          sliceFetcher.fetchSlice(path, offsetChars, maxChars);
      if (stage == null) {
        return notFound(path);
      }
      slice =
          stage
              .toCompletableFuture()
              .get(io.justsearch.agent.AgentTimeouts.toolFetchMs(), TimeUnit.MILLISECONDS);
    } catch (Exception e) {
      // Tempdoc 877 §2.6 — the prefix is preserved verbatim (it names the document, which the
      // generic classifier cannot); what is new is the typed code: a fetch that timed out or hit an
      // unreachable Worker now reports itself RETRYABLE instead of as an untyped string.
      return AgentToolErrors.classify(
          "core_read_document", "Could not read \"" + path + "\"", e);
    }
    if (slice == null || !slice.found()) {
      return notFoundOrWorkerError(path, slice == null ? null : slice.error());
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
        formatPage(path, pageText, startChar, endChar, truncated, nextOffset),
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
   */
  private static String formatPage(
      String path, String pageText, int startChar, int endChar, boolean truncated, int nextOffset) {
    var sb = new StringBuilder();
    sb.append("[read] ").append(path).append(" — chars ").append(startChar).append('–')
        .append(endChar);
    if (truncated) {
      sb.append(" of more; More: call core_read_document again with offset_chars=")
          .append(nextOffset);
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
    evidence.put(OperationResult.READ_RESULTS_KEY, List.<Map<String, Object>>of(Map.copyOf(item)));
    return Map.copyOf(evidence);
  }

  private static OperationResult notFound(String path) {
    return OperationResult.failure(
        "Document not found in the index: "
            + path
            + ". Use core_browse_folders to find the path, or core_search_index to search"
            + " inside it.");
  }

  /**
   * Tempdoc 877 §2.6 — {@code found=false} carries a REASON, and the tool used to throw it away:
   * every unsuccessful fetch was reported as "Document not found in the index", including one where
   * the Worker said something else entirely. A reason that says the document is absent keeps the
   * not-found message (it is the one with the actionable remedy); any other reason is the Worker
   * telling us something we must not overwrite with a guess.
   *
   * <p>The absent-markers are recognised by CONTENT, not by provenance: {@code "not_found"} comes
   * from {@code DocumentService.fetchSlice}'s in-process default and {@code "missing_doc_id"} from
   * {@code RemoteDocumentService}, but the Worker itself answers a missing document with the prose
   * {@code "Document not found in index"} ({@code GrpcSearchService.fetchDocumentSlice}). Keying on
   * "ours vs theirs" would turn the single most common real case into an opaque relay of the
   * Worker's wording, losing the browse/search remedy the model needs.
   */
  private static OperationResult notFoundOrWorkerError(String path, String error) {
    if (error == null || isAbsentMarker(error)) {
      return notFound(path);
    }
    return OperationResult.failure("Could not read \"" + path + "\": " + error);
  }

  private static boolean isAbsentMarker(String error) {
    String normalized = error.strip().toLowerCase(java.util.Locale.ROOT);
    return normalized.equals("not_found")
        || normalized.equals("missing_doc_id")
        || normalized.contains("not found");
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
