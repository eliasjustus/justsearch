/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.ReadDocumentTool;
import io.justsearch.app.api.DocumentService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tempdoc 868 §B.2 — the read tool's contract with the three things that can silently make it
 * useless: Layer-2 truncation (a clipped page IS the excerpt-shaped result this tool replaces),
 * Layer-3 compression (a page that matches no carrier label leaves the inclusion receipt mute), and
 * the mint (a read that emitted {@code searchResults} would fabricate retrieval evidence).
 *
 * <p>Lives in {@code io.justsearch.agent} rather than beside the tool because the load-bearing
 * assertions are about the package-private compressor: the arithmetic that keeps a page whole is
 * only meaningful when run through the REAL {@code truncate}, not a copy of its constant.
 */
final class ReadDocumentToolTest {

  private static final String PATH = "/docs/report.md";

  /** Records the arguments it was called with, so the cap can be asserted at the boundary. */
  private static final class FakeFetch implements ReadDocumentTool.SliceFetcher {
    private final DocumentService.DocumentSlice slice;
    private int lastMaxChars = -1;
    private int lastOffset = -1;

    FakeFetch(DocumentService.DocumentSlice slice) {
      this.slice = slice;
    }

    @Override
    public CompletionStage<DocumentService.DocumentSlice> fetchSlice(
        String docId, int offsetChars, int maxChars) {
      this.lastOffset = offsetChars;
      this.lastMaxChars = maxChars;
      return CompletableFuture.completedFuture(slice);
    }
  }

  private static DocumentService.DocumentSlice slice(
      String content, boolean truncated, int nextOffset) {
    return slice(PATH, content, truncated, nextOffset);
  }

  private static DocumentService.DocumentSlice slice(
      String path, String content, boolean truncated, int nextOffset) {
    return new DocumentService.DocumentSlice(
        path, content, Map.of("title", "Quarterly Report"), true, truncated, nextOffset, null);
  }

  private static String page(int chars) {
    return "x".repeat(chars);
  }

  /**
   * A 400-char Windows path — the worst case {@code PAGE_HEADROOM_CHARS} claims to cover. The header
   * echoes the path in full, so the headroom budget is really "how long can a path be"; a fixture
   * using a 15-char path would leave the claim untested.
   */
  private static String longWindowsPath() {
    var sb = new StringBuilder("F:\\indexed");
    while (sb.length() < 397) {
      sb.append("\\segment");
    }
    sb.setLength(397);
    return sb + ".md";
  }

  @Test
  @DisplayName("a full page survives Layer-2 truncation unchanged — the arithmetic, not a copy of it")
  void fullPageIsNotClippedByLayerTwo() {
    String longPath = longWindowsPath();
    assertEquals(400, longPath.length(), "the fixture must exercise the headroom budget");
    var fetch =
        new FakeFetch(slice(longPath, page(ReadDocumentTool.READ_PAGE_CHARS), true, 3000));
    OperationResult result =
        new ReadDocumentTool(fetch)
            .execute("{\"path\":\"" + longPath.replace("\\", "\\\\") + "\"}");

    assertTrue(result.success(), result.message());
    // THE point of READ_PAGE_CHARS: header + carrier framing + a maximal page must still fit under
    // MAX_TOOL_RESULT_CHARS. Run through the real truncate, so a future change to either constant
    // (or to the header text) fails here instead of silently shipping clipped pages.
    String message = result.message();
    assertSame(
        message,
        AgentContextCompressor.truncate(message),
        "a maximal read page under a maximal path must reach the model whole; truncate returns the"
            + " same instance when it has nothing to cut. message length="
            + message.length()
            + " cap="
            + AgentContextCompressor.MAX_TOOL_RESULT_CHARS);
    assertFalse(message.contains("truncated,"), "no Layer-2 truncation marker");
  }

  @Test
  @DisplayName("the header names the span read, and the More: line gives the next offset")
  void headerNamesTheSpanAndThePagingOffset() {
    var fetch = new FakeFetch(slice(page(500), true, 500));
    OperationResult result =
        new ReadDocumentTool(fetch).execute("{\"path\":\"" + PATH + "\",\"offset_chars\":0}");

    String firstLine = result.message().lines().findFirst().orElseThrow();
    // Pinned verbatim, dashes included: the header is non-ASCII, so a source-encoding regression
    // (a cp1252 round-trip on Windows) would otherwise reach the model as mojibake and only ever
    // be noticed by a human reading a prompt.
    assertTrue(
        firstLine.startsWith("[read] " + PATH + " — chars 0–500"),
        "got: " + firstLine);
    assertTrue(firstLine.contains("chars 0"), "the span read is stated: " + firstLine);
    assertTrue(
        firstLine.contains("core_read_document again with offset_chars=500"),
        "a truncated page must tell the model exactly how to continue: " + firstLine);
  }

  @Test
  @DisplayName("a complete page carries no More: line — the loop has a termination signal")
  void completePageHasNoMoreLine() {
    var fetch = new FakeFetch(slice(page(120), false, 120));
    OperationResult result = new ReadDocumentTool(fetch).execute("{\"path\":\"" + PATH + "\"}");
    assertFalse(
        result.message().contains("More:"),
        "signalling 'more' on a complete document is what makes agents re-read forever");
  }

  @Test
  @DisplayName("max_chars is capped at READ_PAGE_CHARS at the fetch boundary, not merely documented")
  void oversizedMaxCharsIsCappedBeforeTheFetch() {
    var fetch = new FakeFetch(slice(page(10), false, 10));
    new ReadDocumentTool(fetch)
        .execute("{\"path\":\"" + PATH + "\",\"max_chars\":100000,\"offset_chars\":42}");
    assertEquals(
        ReadDocumentTool.READ_PAGE_CHARS,
        fetch.lastMaxChars,
        "an LLM-chosen max_chars must not be able to blow past the context cap");
    assertEquals(42, fetch.lastOffset);
  }

  @Test
  @DisplayName("868 §B.3 — the structured half is readResults, never searchResults")
  void structuredDataIsReadResultsOnly() {
    var fetch = new FakeFetch(slice("the page body", true, 13));
    OperationResult result =
        new ReadDocumentTool(fetch).execute("{\"path\":\"" + PATH + "\",\"offset_chars\":0}");

    assertFalse(
        result.structuredData().containsKey("searchResults"),
        "emitting searchResults would mint sources indistinguishable from ranked hits — the 865"
            + " §7.6 invariant violation the acquisition axis exists to prevent");
    Object raw = result.structuredData().get("readResults");
    assertTrue(raw instanceof List<?>, "readResults must be a list; got " + raw);
    Map<?, ?> item = (Map<?, ?>) ((List<?>) raw).get(0);
    assertEquals(PATH, item.get("path"));
    assertEquals("Quarterly Report", item.get("title"), "stored title metadata wins over filename");
    assertEquals("the page body", item.get("excerpt"), "the excerpt is the page the model saw");
    assertEquals(0, item.get("startChar"));
    assertEquals(13, item.get("endChar"));
    assertEquals(true, item.get("truncated"));
  }

  @Test
  @DisplayName("the title falls back to the file name when the document carries no title metadata")
  void titleFallsBackToFileName() {
    var untitled =
        new DocumentService.DocumentSlice(PATH, "body", Map.of(), true, false, 4, null);
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(untitled)).execute("{\"path\":\"" + PATH + "\"}");
    Map<?, ?> item = (Map<?, ?>) ((List<?>) result.structuredData().get("readResults")).get(0);
    assertEquals("report.md", item.get("title"));
  }

  @Test
  @DisplayName("a document the index does not hold fails with the two tools that could find it")
  void notFoundNamesTheRecoveryPath() {
    var missing =
        new DocumentService.DocumentSlice(
            PATH, "", Map.of(), false, false, 0, "not_found");
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(missing)).execute("{\"path\":\"" + PATH + "\"}");

    assertFalse(result.success());
    assertTrue(result.message().startsWith("Document not found in the index: " + PATH));
    assertTrue(result.message().contains("core_browse_folders"));
    assertTrue(result.message().contains("core_search_index"));
  }

  @Test
  @DisplayName("877 §2.6: a Worker failure is reported as a Worker failure, not as 'not found'")
  void workerErrorIsNotReportedAsNotFound() {
    // found=false carries a REASON and the tool used to discard it, so a Worker that answered
    // "index is rebuilding" told the model the document did not exist — a lie that sends it
    // browsing for a path that is already correct.
    var workerFailure =
        new DocumentService.DocumentSlice(
            PATH, "", Map.of(), false, false, 0, "index segment unreadable: rebuild in progress");
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(workerFailure)).execute("{\"path\":\"" + PATH + "\"}");

    assertFalse(result.success());
    assertTrue(
        result.message().contains("index segment unreadable: rebuild in progress"),
        "the Worker's own reason must reach the model: " + result.message());
    assertFalse(
        result.message().startsWith("Document not found in the index"),
        "a Worker failure must not be relabelled as an absent document: " + result.message());
  }

  @Test
  @DisplayName("877 §2.6: the Worker's own absent-document wording keeps the not-found remedy")
  void workerAbsentWordingStillNamesTheRecoveryPath() {
    // GrpcSearchService.fetchDocumentSlice answers a missing document with this exact prose. It is
    // an absent document, not a Worker fault, so it must keep the message that names the two tools
    // that can find the right path — recognising the marker by content, not by which side minted it.
    var missing =
        new DocumentService.DocumentSlice(
            PATH, "", Map.of(), false, false, 0, "Document not found in index");
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(missing)).execute("{\"path\":\"" + PATH + "\"}");

    assertFalse(result.success());
    assertTrue(result.message().startsWith("Document not found in the index: " + PATH));
    assertTrue(result.message().contains("core_browse_folders"));
  }

  @Test
  @DisplayName("868: an indexed document with NO extracted text fails and names the reason — never an empty page")
  void emptyExtractionAtOffsetZeroFails() {
    // The Worker answers found=true with empty content for an extraction dropout (tempdoc 790), so
    // "found" is not "readable". Succeeding here would mint an opened source with a blank literal,
    // and a blank literal sends the matcher back to an index lookup with the document-level -1 chunk
    // ordinal clamped to 0 — the re-fetch an opened source must never do.
    var dropout =
        new DocumentService.DocumentSlice(
            PATH,
            "",
            Map.of("extraction_reason_code", "EXTRACTION_DROPOUT_NO_TIER", "extraction_method", "NONE"),
            true,
            false,
            0,
            null);
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(dropout)).execute("{\"path\":\"" + PATH + "\"}");

    assertFalse(result.success(), "an unreadable document is a failure, not an empty success");
    assertTrue(result.message().contains(PATH), "the path is named: " + result.message());
    assertTrue(
        result.message().contains("EXTRACTION_DROPOUT_NO_TIER"),
        "'no text' and 'no tier could read it' are different facts to a user: " + result.message());
    assertTrue(result.structuredData().isEmpty(), "no readResults, so nothing can be minted");
  }

  @Test
  @DisplayName("868: the reason falls back to extraction_method when no reason code is stored")
  void emptyExtractionFallsBackToTheExtractionMethod() {
    var dropout =
        new DocumentService.DocumentSlice(
            PATH, "", Map.of("extraction_method", "NONE"), true, false, 0, null);
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(dropout)).execute("{\"path\":\"" + PATH + "\"}");
    assertFalse(result.success());
    assertTrue(result.message().contains("NONE"), result.message());
  }

  @Test
  @DisplayName("868: a blank page PAST offset 0 is the normal terminus — success, and no readResults")
  void blankPagePastOffsetZeroIsEndOfDocument() {
    // Distinct from the dropout above: the previous page ended exactly at the document's end, so
    // this is a successful "you are done", not a failure. It carries no readResults because there is
    // no text to be evidence of.
    var atEnd = slice("", false, 3000);
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(atEnd))
            .execute("{\"path\":\"" + PATH + "\",\"offset_chars\":3000}");

    assertTrue(result.success(), "reaching the end of a document is not an error");
    assertTrue(result.message().contains("end of document"), result.message());
    assertTrue(result.message().contains("3000"), "the offset that ended it: " + result.message());
    assertFalse(
        result.structuredData().containsKey("readResults"),
        "an empty page is not evidence of anything");
    assertFalse(result.message().contains("More:"), "nothing more to page to");
  }

  @Test
  @DisplayName("868 §B.1: the evidence excerpt IS the string on the carrier line, character for character")
  void excerptIsExactlyWhatTheModelWasShown() {
    // The matcher verifies an opened source against its literal text, so a literal that differs from
    // the prompt by every newline and quote in the page is not a verification. Flatten once, use the
    // one result for both.
    var multiline = slice("line one\r\nline \"two\"\nline three", false, 31);
    OperationResult result =
        new ReadDocumentTool(new FakeFetch(multiline)).execute("{\"path\":\"" + PATH + "\"}");

    Map<?, ?> item = (Map<?, ?>) ((List<?>) result.structuredData().get("readResults")).get(0);
    String excerpt = (String) item.get("excerpt");
    assertEquals("line one line 'two' line three", excerpt, "flattened once, at the seam");
    assertTrue(
        result.message().contains("Read: \"" + excerpt + "\""),
        "the carrier line must carry exactly the excerpt string; got: " + result.message());
    assertEquals(0, item.get("startChar"), "spans stay on the RAW slice's coordinates");
    assertEquals(
        31,
        item.get("endChar"),
        "…including the newline chars the flattening dropped — the span addresses the document in"
            + " the index, not the rendering (31 raw vs 30 flattened)");
  }

  @Test
  @DisplayName("a missing path is refused before any fetch")
  void missingPathIsRefused() {
    var fetch = new FakeFetch(slice("body", false, 4));
    OperationResult result = new ReadDocumentTool(fetch).execute("{}");
    assertFalse(result.success());
    assertEquals(-1, fetch.lastOffset, "no fetch is attempted without a path");
  }

  // ---------------------------------------------------------------------------------------------
  // Layer-3: the third carrier label
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("868 §B.2 — a read message is textIntact before a Layer-3 pass and textRemoved after")
  void readMessageIsClassifiedByTheReceiptOnBothSidesOfTheStrip() {
    // Without a carrier label a read result would match NEITHER pattern, so the receipt would put it
    // in neither set — inclusion-mute, exactly the silence 865 §7.5 built ToolResultCarrier to end.
    var fetch = new FakeFetch(slice(page(1200), false, 1200));
    OperationResult read = new ReadDocumentTool(fetch).execute("{\"path\":\"" + PATH + "\"}");

    var messages = new ArrayList<Map<String, Object>>();
    messages.add(toolMessage("call-read", read.message()));

    AgentContextCompressor.CompressionReceipt before =
        AgentContextCompressor.receiptFor(messages, java.util.Set.of());
    assertTrue(
        before.textIntact().contains("call-read"),
        "the page is in front of the model, so the receipt must say so");
    assertFalse(before.textRemoved().contains("call-read"));

    // A second tool message so the compressor has something to keep (keepLastResults = 1) and the
    // read message is one it will actually rewrite.
    messages.add(toolMessage("call-later", "a later, uncompressed result"));
    AgentContextCompressor.CompressionReceipt after =
        new AgentContextCompressor(true, 200, 1).compressToolMessages(messages);

    assertTrue(
        after.textRemoved().contains("call-read"),
        "after the strip the page is gone from the prompt, and the receipt must say THAT");
    assertFalse(after.textIntact().contains("call-read"));

    String compressed = (String) messages.get(0).get("content");
    assertTrue(
        compressed.contains("[read] " + PATH),
        "the header must survive the strip: the run keeps the fact that this document was read and"
            + " where the next page starts, while the page body — the expensive part — goes. got: "
            + compressed);
    assertFalse(
        compressed.contains(page(1200)), "the page body itself must be gone: " + compressed);
  }

  private static Map<String, Object> toolMessage(String callId, String content) {
    var m = new LinkedHashMap<String, Object>();
    m.put("role", "tool");
    m.put("tool_call_id", callId);
    m.put("content", content);
    return m;
  }
}
