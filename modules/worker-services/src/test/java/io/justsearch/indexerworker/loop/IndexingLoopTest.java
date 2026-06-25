package io.justsearch.indexerworker.loop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.DocumentFieldOps;
import io.justsearch.adapters.lucene.runtime.IndexRuntimeIOException;
import io.justsearch.adapters.lucene.runtime.IndexCountOps;
import io.justsearch.adapters.lucene.runtime.IndexingCoordinator;
import io.justsearch.indexerworker.coordination.WorkerSignalBus;
import io.justsearch.indexerworker.extract.ContentExtractor;
import io.justsearch.indexerworker.extract.ContentExtractor.ExtractionResult;
import io.justsearch.indexerworker.extract.ContentExtractorProvider;
import io.justsearch.indexerworker.extract.ExtractionArtifact;
import io.justsearch.indexerworker.extract.ExtractionStatus;
import io.justsearch.indexerworker.extract.ProcessExtractionSandbox;
import io.justsearch.indexerworker.extract.TikaExtractionPolicy;
import io.justsearch.indexerworker.extract.TimeboxedContentExtractor;
import io.justsearch.indexerworker.extract.ValidatedExtractionArtifact;
import io.justsearch.indexerworker.fixtures.TestDocumentBuilder;
import io.justsearch.indexerworker.ingest.IngestionOutcome;
import io.justsearch.indexerworker.ingest.IngestionOutcomeClass;
import io.justsearch.indexerworker.ingest.IngestionReasonCodes;
import io.justsearch.indexerworker.loop.ops.IndexingDocumentOps;
import io.justsearch.indexerworker.splade.SpladeEncoder;
import io.justsearch.indexerworker.queue.JobQueue;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.indexing.api.IndexApi.IndexDocument;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link IndexingLoop} and its extracted ops classes.
 *
 * <p>Uses ByteBuddy experimental mode (-Dnet.bytebuddy.experimental=true) for JDK 25 compatibility.
 *
 * <p>Pure function tests target the public static methods in {@link IndexingDocumentOps}. Business
 * logic tests (processJob, buildDocument) require a comprehensive test fixture due to many
 * dependencies (metrics, telemetry, content extractor, etc.) and are better suited for integration
 * tests.
 */
@ExtendWith(MockitoExtension.class)
class IndexingLoopTest {

  // ==================== Mockito Smoke Test ====================

  @Nested
  @DisplayName("Mockito compatibility")
  class MockitoSmokeTest {

    @Mock JobQueue mockJobQueue;

    @Test
    @DisplayName("Mockito works with JDK 25 experimental mode")
    void mockitoWorks() {
      when(mockJobQueue.queueDepth()).thenReturn(42L);
      // Direct invocation on mock is intentional for this smoke test
      @SuppressWarnings("DirectInvocationOnMock")
      long result = mockJobQueue.queueDepth();
      assertEquals(42L, result);
      verify(mockJobQueue).queueDepth();
    }
  }

  // ==================== Pure Function Tests (IndexingDocumentOps) ====================

  @Nested
  @DisplayName("normalizeMimeBase()")
  class NormalizeMimeBaseTests {

    @Test
    @DisplayName("null returns null")
    void nullReturnsNull() {
      assertNull(IndexingDocumentOps.normalizeMimeBase(null));
    }

    @Test
    @DisplayName("MIME with charset is stripped and lowercased")
    void charsetIsStripped() {
      assertEquals("text/plain", IndexingDocumentOps.normalizeMimeBase("text/plain; charset=UTF-8"));
      assertEquals("text/html", IndexingDocumentOps.normalizeMimeBase("TEXT/HTML; charset=ISO-8859-1"));
    }

    @Test
    @DisplayName("simple MIME is lowercased")
    void simpleMimeIsLowercased() {
      assertEquals("application/json", IndexingDocumentOps.normalizeMimeBase("APPLICATION/JSON"));
    }
  }

  @Nested
  @DisplayName("contentPreview()")
  class ContentPreviewTests {

    @Test
    @DisplayName("null returns empty string")
    void nullReturnsEmpty() {
      assertEquals("", IndexingDocumentOps.contentPreview(null));
    }

    @Test
    @DisplayName("short content is returned unchanged")
    void shortContentUnchanged() {
      String shortText = "Hello, World!";
      assertEquals(shortText, IndexingDocumentOps.contentPreview(shortText));
    }

    @Test
    @DisplayName("long content truncated to 4096 characters")
    void longContentTruncated() {
      String longText = "a".repeat(5000);
      String result = IndexingDocumentOps.contentPreview(longText);
      assertEquals(4096, result.length());
      assertEquals("a".repeat(4096), result);
    }
  }

  @Nested
  @DisplayName("classifyFileKind()")
  class ClassifyFileKindTests {

    @Test
    @DisplayName("application/pdf returns 'pdf'")
    void pdfReturns() {
      assertEquals("pdf", IndexingDocumentOps.classifyFileKind(Path.of("doc.pdf"), "application/pdf"));
    }

    @Test
    @DisplayName("image/* returns 'image'")
    void imageReturns() {
      assertEquals("image", IndexingDocumentOps.classifyFileKind(Path.of("pic.png"), "image/png"));
      assertEquals("image", IndexingDocumentOps.classifyFileKind(Path.of("photo.jpg"), "image/jpeg"));
    }

    @Test
    @DisplayName("text/x-java returns 'code'")
    void codeReturns() {
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("App.java"), "text/x-java"));
    }

    @Test
    @DisplayName("null MIME with .java extension returns 'code'")
    void nullMimeCodeExtension() {
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("main.java"), null));
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("main.go"), null));
      assertEquals("code", IndexingDocumentOps.classifyFileKind(Path.of("script.py"), null));
    }

    @Test
    @DisplayName("null MIME with unknown extension returns 'unknown'")
    void nullMimeUnknown() {
      assertEquals("unknown", IndexingDocumentOps.classifyFileKind(Path.of("file.xyz"), null));
    }

    @Test
    @DisplayName("text/plain returns 'text'")
    void textReturns() {
      assertEquals("text", IndexingDocumentOps.classifyFileKind(Path.of("readme.txt"), "text/plain"));
    }
  }

  @Nested
  @DisplayName("detectLanguage()")
  class DetectLanguageTests {

    @Test
    @DisplayName("null returns null")
    void nullReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage(null));
    }

    @Test
    @DisplayName("empty string returns null")
    void emptyReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage(""));
    }

    @Test
    @DisplayName("Latin-only text returns null (uses default)")
    void latinReturnsNull() {
      assertNull(IndexingDocumentOps.detectLanguage("The quick brown fox jumps over the lazy dog."));
    }

    @Test
    @DisplayName("Chinese text returns 'zh'")
    void chineseReturnsZh() {
      assertEquals("zh", IndexingDocumentOps.detectLanguage("这是一段中文文本用于测试。"));
    }

    @Test
    @DisplayName("Japanese hiragana returns 'ja'")
    void japaneseReturnsJa() {
      assertEquals("ja", IndexingDocumentOps.detectLanguage("これは日本語のテキストです。"));
    }

    @Test
    @DisplayName("Korean text returns 'ko'")
    void koreanReturnsKo() {
      assertEquals("ko", IndexingDocumentOps.detectLanguage("이것은 한국어 텍스트입니다."));
    }

    @Test
    @DisplayName("Cyrillic (Russian) returns 'ru'")
    void russianReturnsRu() {
      assertEquals("ru", IndexingDocumentOps.detectLanguage("Это текст на русском языке."));
    }

    @Test
    @DisplayName("Arabic text returns 'ar'")
    void arabicReturnsAr() {
      assertEquals("ar", IndexingDocumentOps.detectLanguage("هذا نص باللغة العربية."));
    }
  }

  @Nested
  @DisplayName("isCodeExtension() - selected extensions")
  class IsCodeExtensionTests {

    @Test
    @DisplayName("common code extensions return true")
    void commonCodeExtensions() {
      assertTrue(IndexingDocumentOps.isCodeExtension(".java"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".py"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".js"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".ts"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".go"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".rs"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".c"));
      assertTrue(IndexingDocumentOps.isCodeExtension(".cpp"));
    }

    @Test
    @DisplayName("non-code extensions return false")
    void nonCodeExtensions() {
      assertFalse(IndexingDocumentOps.isCodeExtension(".txt"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".pdf"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".jpg"));
      assertFalse(IndexingDocumentOps.isCodeExtension(".xyz"));
    }

    @Test
    @DisplayName("empty string returns false")
    void emptyReturnsFalse() {
      assertFalse(IndexingDocumentOps.isCodeExtension(""));
    }
  }

  @Nested
  @DisplayName("isMarkdownExtension()")
  class IsMarkdownExtensionTests {

    @Test
    @DisplayName(".md returns true")
    void mdReturnsTrue() {
      assertTrue(IndexingDocumentOps.isMarkdownExtension(".md"));
    }

    @Test
    @DisplayName(".markdown returns true")
    void markdownReturnsTrue() {
      assertTrue(IndexingDocumentOps.isMarkdownExtension(".markdown"));
    }

    @Test
    @DisplayName(".txt returns false")
    void txtReturnsFalse() {
      assertFalse(IndexingDocumentOps.isMarkdownExtension(".txt"));
    }

    @Test
    @DisplayName("empty string returns false")
    void emptyReturnsFalse() {
      assertFalse(IndexingDocumentOps.isMarkdownExtension(""));
    }
  }

  // ==================== buildDocument NER Status (C1 fix validation) ====================

  @Nested
  @DisplayName("buildDocument NER status")
  class BuildDocumentNerStatus {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("sets NER_STATUS to PENDING for new documents")
    void buildDocument_setsNerStatusPending() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, null);

      assertEquals(
          SchemaFields.NER_STATUS_PENDING,
          doc.fields().get(SchemaFields.NER_STATUS),
          "New documents must have NER_STATUS=PENDING for backfill discovery");
    }
  }

  @Nested
  @DisplayName("parent_token_count plumbing")
  class ParentTokenCountTests {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("deriveParentMetadata uses SPLADE token count when encoder is available")
    void deriveParentMetadataUsesSpladeTokenCount() {
      ExtractionResult extraction = new ExtractionResult("Alpha beta gamma", null, "text/plain");
      SpladeEncoder spladeEncoder = mock(SpladeEncoder.class);
      when(spladeEncoder.tokenCount("Alpha beta gamma")).thenReturn(321L);

      IndexingDocumentOps.ParentIndexMetadata metadata =
          IndexingDocumentOps.deriveParentMetadata(
              Path.of("notes.txt"),
              extraction,
              spladeEncoder,
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class));

      assertEquals(321L, metadata.parentTokenCount());
      assertEquals("text/plain", metadata.mimeBase());
      assertEquals("text", metadata.fileKind());
    }

    @Test
    @DisplayName("deriveParentMetadata leaves token count null when SPLADE encoder is missing")
    void deriveParentMetadataWithoutSpladeLeavesTokenCountNull() {
      ExtractionResult extraction = new ExtractionResult("Alpha beta gamma", null, "text/plain");

      IndexingDocumentOps.ParentIndexMetadata metadata =
          IndexingDocumentOps.deriveParentMetadata(
              Path.of("notes.txt"),
              extraction,
              null,
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class));

      assertNull(metadata.parentTokenCount(), "missing SPLADE encoder should omit parent_token_count");
    }

    @Test
    @DisplayName("buildDocument writes parent_token_count when provided in metadata")
    void buildDocumentWritesParentTokenCount() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");
      IndexingDocumentOps.ParentIndexMetadata metadata =
          new IndexingDocumentOps.ParentIndexMetadata(
              "text/plain", "text/plain", "text", "en", 777L);

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, metadata);

      assertEquals(777L, doc.fields().get(SchemaFields.PARENT_TOKEN_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits parent_token_count when metadata does not include it")
    void buildDocumentOmitsParentTokenCountWhenAbsent() {
      ExtractionResult extraction = new ExtractionResult("Test content", null, "text/plain");
      IndexingDocumentOps.ParentIndexMetadata metadata =
          new IndexingDocumentOps.ParentIndexMetadata(
              "text/plain", "text/plain", "text", "en", null);

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("test.txt"), extraction, null, mockSignalBus, metadata);

      assertFalse(doc.fields().containsKey(SchemaFields.PARENT_TOKEN_COUNT));
    }
  }

  // Tempdoc 516 Slice 4d (W6): "reflection compatibility contract" nested class removed.
  // The two preserved methods (handleEmbeddingFailure / handleChunkEmbeddingFailure) were
  // @SuppressWarnings("unused") wrappers in IndexingLoop with no production callers — the
  // production code calls EmbeddingBackfillOps.handle*Failure(...) statics directly. The
  // wrappers + this test + the UnreferencedCodeTest allowlist entries formed a self-
  // referential dead-code loop. All three removed as part of the W6 BackfillScheduler
  // extraction.

  @Nested
  @DisplayName("extraction provenance schema fields (tempdoc 410 §11)")
  class ExtractionProvenanceFieldTests {

    @Mock WorkerSignalBus mockSignalBus;

    @Test
    @DisplayName("buildDocument writes status, policy, parser, truncated from validated artifact")
    void buildDocumentWritesArtifactProvenance() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", true)
              .validate(policy, "deadbeef");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("provenance.txt"),
              artifact,
              null,
              mockSignalBus,
              /* embeddingProvider */ null,
              /* allowEmbeddingWrites */ false,
              /* spladeEncoder */ null,
              /* parentMetadata */ null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              /* precomputedEmbedding */ null,
              new IndexingDocumentOps.SourceFileMetadata(123L, 456L));

      Map<String, Object> fields = doc.fields();
      assertEquals("SUCCESS_PARTIAL", fields.get(SchemaFields.EXTRACTION_STATUS));
      assertEquals(Boolean.TRUE, fields.get(SchemaFields.CONTENT_TRUNCATED));
      assertEquals(policy.policyId(), fields.get(SchemaFields.EXTRACTION_POLICY_ID));
      assertEquals("tika-policy-structured", fields.get(SchemaFields.EXTRACTION_PARSER_ID));
    }

    @Test
    @DisplayName("buildDocument writes EXTRACTION_REASON_CODE=SUCCESS_PARTIAL when truncated")
    void buildDocumentWritesExtractionReasonCodeForSuccessPartial() throws Exception {
      // Slice A.2 — SUCCESS_PARTIAL artifacts (truncated extraction) surface the reason code so
      // search-side callers can distinguish "got everything" from "got the prefix Tika handed us
      // before we cut it off."
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact partial =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", true)
              .validate(policy, "partial-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("partial.txt"),
              partial,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L));

      assertEquals(
          IngestionReasonCodes.SUCCESS_PARTIAL,
          doc.fields().get(SchemaFields.EXTRACTION_REASON_CODE));
    }

    @Test
    @DisplayName("buildDocument omits EXTRACTION_REASON_CODE for SUCCESS_FULL (implicit success)")
    void buildDocumentOmitsExtractionReasonCodeForSuccessFull() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact full =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", false)
              .validate(policy, "full-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("full.txt"),
              full,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L));

      assertFalse(doc.fields().containsKey(SchemaFields.EXTRACTION_REASON_CODE));
    }

    @Test
    @DisplayName("buildDocument writes parser_warnings_count from artifact warnings (Slice A.1)")
    void buildDocumentWritesParserWarningsCount() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ExtractionArtifact rawWithWarnings =
          new ExtractionArtifact(
              ExtractionStatus.SUCCESS_FULL,
              extraction,
              policy.policyId(),
              "tika-policy-structured",
              false,
              java.util.List.of("warn-1", "warn-2", "warn-3"));
      ValidatedExtractionArtifact artifact = rawWithWarnings.validate(policy, "warned-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("warnings.txt"),
              artifact,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L));

      assertEquals(3L, doc.fields().get(SchemaFields.PARSER_WARNINGS_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits parser_warnings_count when warnings list is empty")
    void buildDocumentOmitsParserWarningsCountWhenZero() throws Exception {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");
      TikaExtractionPolicy policy = TikaExtractionPolicy.defaults();
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, policy, "tika-policy-structured", false)
              .validate(policy, "no-warn-hash");

      IndexDocument doc =
          IndexingDocumentOps.buildDocument(
              Path.of("clean.txt"),
              artifact,
              null,
              mockSignalBus,
              null,
              false,
              null,
              null,
              (s, d, r) -> {},
              org.slf4j.LoggerFactory.getLogger(IndexingLoopTest.class),
              null,
              new IndexingDocumentOps.SourceFileMetadata(11L, 22L));

      assertFalse(doc.fields().containsKey(SchemaFields.PARSER_WARNINGS_COUNT));
    }

    @Test
    @DisplayName("buildDocument omits provenance fields when artifact is unavailable (legacy path)")
    void buildDocumentOmitsProvenanceWhenSourceMetadataIsLegacy() {
      ExtractionResult extraction = new ExtractionResult("Body", null, "text/plain");

      IndexDocument doc =
          TestDocumentBuilder.buildDocument(
              Path.of("legacy.txt"), extraction, null, mockSignalBus, null);

      // TestDocumentBuilder routes through the artifact path with a synthetic "test-fixture"
      // parser and default policy; the provenance fields ARE written, just with fixture values.
      assertEquals("SUCCESS_FULL", doc.fields().get(SchemaFields.EXTRACTION_STATUS));
      assertEquals(Boolean.FALSE, doc.fields().get(SchemaFields.CONTENT_TRUNCATED));
      assertEquals(
          TikaExtractionPolicy.defaults().policyId(),
          doc.fields().get(SchemaFields.EXTRACTION_POLICY_ID));
      assertEquals("test-fixture", doc.fields().get(SchemaFields.EXTRACTION_PARSER_ID));
    }
  }

  @Nested
  @DisplayName("typed ingestion outcomes")
  class TypedIngestionOutcomeTests {

    @Test
    void genericExtractionFailureDoesNotReturnPlaceholderDocument() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-extract-fail", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowingWithoutDetect(
                  new ContentExtractor.ExtractionException("parser boom")));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.PARSER_FAILED, queue.lastOutcome.outcomeClass());
      assertTrue(queue.terminalFailed, "Parser failure should be terminal for v1");
      verify(queue.indexingCoordinator, never()).indexSingle(any());
    }

    @Test
    void sandboxFailureProducesSandboxFailedOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-sandbox-fail", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowing(
                  new ProcessExtractionSandbox.SandboxExtractionException(
                      "stdout polluted", null)));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.SANDBOX_FAILED, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.SANDBOX_FAILED, queue.lastOutcome.reasonCode());
      assertFalse(queue.terminalFailed, "Sandbox failure should be retryable, not terminal");
      assertFalse(queue.deferred, "Sandbox failure should not defer without attempt");
    }

    @Test
    void budgetExceededIsTerminalBudgetOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-budget", ".txt"), "bad");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              providerThrowing(
                  new ContentExtractor.BudgetExceededException("too large", "INPUT_TOO_LARGE")));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.BUDGET_EXCEEDED, queue.lastOutcome.outcomeClass());
      assertTrue(queue.terminalFailed);
    }

    @Test
    void missingFileProducesStaleSourceOutcome() throws Exception {
      Path file = Files.createTempDirectory("js-missing").resolve("gone.txt");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("unused"));

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.DELETED_OR_MISSING, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(queue.indexingCoordinator).deleteByIdAndChunks(anyString());
    }

    @Test
    void nonRegularSourceIsSkippedBeforeExtraction() throws Exception {
      Path directory = Files.createTempDirectory("js-non-regular");
      RecordingQueue queue = new RecordingQueue();
      ContentExtractorProvider provider = mock(ContentExtractorProvider.class);
      IndexingLoop loop = newLoop(queue, provider);

      Object extracted = invokeExtractJob(loop, directory);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.SKIPPED_POLICY, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.NON_REGULAR_SOURCE, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(provider, never()).extract(any());
    }

    @Test
    void fileChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.writeString(path, "after-change");
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.SIZE_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
      assertFalse(queue.done);
    }

    @Test
    void modifiedTimeChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-mtime-stale", ".txt"), "same-size");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.setLastModifiedTime(
                      path, FileTime.fromMillis(Files.getLastModifiedTime(path).toMillis() + 2000L));
                  return new ExtractionResult("same-size", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionReasonCodes.MODIFIED_TIME_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
    }

    @Test
    void sourceKindChangedAfterExtractionDefersForFreshRetry() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-kind-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.delete(path);
                  Files.createDirectory(path);
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionReasonCodes.SOURCE_KIND_CHANGED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.deferred);
    }

    @Test
    void fileDeletedAfterExtractionDeletesExistingIndexAndMarksDone() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-delete-stale", ".txt"), "before");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws IOException {
                  Files.delete(path);
                  return new ExtractionResult("before", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });

      Object extracted = invokeExtractJob(loop, file);

      assertNull(extracted);
      assertEquals(IngestionOutcomeClass.STALE_SOURCE, queue.lastOutcome.outcomeClass());
      assertEquals(IngestionReasonCodes.DELETED_AFTER_SNAPSHOT, queue.lastOutcome.reasonCode());
      assertTrue(queue.done);
      verify(queue.indexingCoordinator).deleteByIdAndChunks(anyString());
    }

    @Test
    void writeFailureProducesWriteFailedOutcome() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-write-fail", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));
      doThrow(new RuntimeException("write boom")).when(queue.indexingCoordinator).indexSingle(any());

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      assertEquals(IngestionOutcomeClass.WRITE_FAILED, queue.lastOutcome.outcomeClass());
      assertFalse(queue.done);
    }

    @Test
    void drainingWriteRejectionDefersWithoutFailure() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-draining", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));
      doThrow(
              new IndexRuntimeIOException(
                  IndexRuntimeIOException.Reason.DRAINING, "runtime_draining", null))
          .when(queue.indexingCoordinator)
          .indexSingle(any());

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      assertEquals(IngestionOutcomeClass.WRITE_UNAVAILABLE_DRAINING, queue.lastOutcome.outcomeClass());
      assertTrue(queue.deferred);
      assertFalse(queue.terminalFailed);
    }

    @Test
    void successfulWriteRecordsSuccessOnlyWhenMarkDoneDrains() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-success", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("body"));

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));
      assertNull(queue.lastOutcome, "Write should not mark DONE before commit/mark-done drain");

      // Tempdoc 516 Slice 4a.1: drain now lives on IngestionOutcomeJournal.
      loop.getJournal().drainPending();

      assertEquals(IngestionOutcomeClass.SUCCESS_FULL, queue.lastOutcome.outcomeClass());
      assertTrue(queue.done);
      assertNotNull(queue.lastEntry);
      assertEquals("SUCCESS_FULL", queue.lastEntry.artifactStatus());
      assertEquals("test-structured", queue.lastEntry.parserId());
    }

    @Test
    void drainKeepsPendingPathsWhenOutcomeWriteFails() throws Exception {
      Path file = Files.writeString(Files.createTempFile("js-drain-fail", ".txt"), "body");
      RecordingQueue queue = new RecordingQueue();
      queue.failOutcomeWrites = true;
      IndexingLoop loop = newLoop(queue, providerReturning("body"));

      invokeWriteExtractedJob(loop, extractedJob(file, "body"));

      // Tempdoc 516 Slice 4a.1: pendingMarkDone now encapsulated in IngestionOutcomeJournal;
      // tests use the package-private snapshot accessor.
      var journal = loop.getJournal();
      assertEquals(
          1,
          journal.pendingTransitionsForTest().size(),
          "Write should enqueue a pending mark-done transition");

      journal.drainPending();

      assertEquals(
          1,
          journal.pendingTransitionsForTest().size(),
          "Failed outcome writes must keep the path in pendingMarkDone for next drain");
    }

    @Test
    void drainPendingMarkDoneRoutesSuccessPartialToMatchingLedgerOutcome() throws Exception {
      // Slice G.1 (H1 + M4) — pre-G.1 the entire pendingMarkDone batch was hardcoded to
      // SUCCESS_FULL/SUCCESS regardless of the artifact's actual status, so a SUCCESS_PARTIAL
      // document had a SUCCESS_FULL ledger row. This test mixes one full-success and one
      // partial-success extracted job in the same batch and asserts the recording queue saw
      // both outcome classes — proving drainPendingMarkDone partitions by artifact status.
      Path full = Files.writeString(Files.createTempFile("js-mixed-full", ".txt"), "fully extracted");
      Path partial =
          Files.writeString(Files.createTempFile("js-mixed-partial", ".txt"), "extracted but truncated");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop = newLoop(queue, providerReturning("ignored"));

      invokeWriteExtractedJob(loop, extractedJob(full, "fully extracted"));
      invokeWriteExtractedJob(loop, extractedJobPartial(partial, "extracted but truncated"));

      // Tempdoc 516 Slice 4a.1: drain now lives on IngestionOutcomeJournal.
      loop.getJournal().drainPending();

      java.util.Set<IngestionOutcomeClass> seen = new java.util.HashSet<>();
      for (IngestionOutcome o : queue.capturedOutcomes) {
        seen.add(o.outcomeClass());
      }
      assertTrue(
          seen.contains(IngestionOutcomeClass.SUCCESS_FULL),
          "Mixed batch must produce a SUCCESS_FULL ledger outcome; saw: " + seen);
      assertTrue(
          seen.contains(IngestionOutcomeClass.SUCCESS_PARTIAL),
          "Mixed batch must produce a SUCCESS_PARTIAL ledger outcome; saw: " + seen);
      // Reason codes also align with the doc field (Slice A.2 wires SUCCESS_PARTIAL there).
      java.util.Set<String> reasons = new java.util.HashSet<>();
      for (IngestionOutcome o : queue.capturedOutcomes) {
        reasons.add(o.reasonCode());
      }
      assertTrue(reasons.contains(IngestionReasonCodes.SUCCESS));
      assertTrue(reasons.contains(IngestionReasonCodes.SUCCESS_PARTIAL));
    }

    @Test
    void misroutedOutcomeWriteDoesNotCrashBatch() throws Exception {
      // B-H.4 defect F — recordOutcomeSafely must catch RuntimeException, not just
      // OutcomeWriteException, so the SqliteJobQueue defer-policy guard
      // (which throws IllegalArgumentException on a misrouted call) doesn't crash
      // the batch loop. Pre-fix: a single misroute would propagate up and abort the batch.
      Path bad = Files.writeString(Files.createTempFile("js-misroute-bad", ".txt"), "bad");
      Path good = Files.writeString(Files.createTempFile("js-misroute-good", ".txt"), "good");
      RecordingQueue queue = new RecordingQueue();
      queue.failOutcomeWritesAsIllegalArgument = true;
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("good", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      // The bad file's PARSER_FAILED outcome write throws IllegalArgumentException via the
      // RecordingQueue's misroute simulation. Pre-B-H.4 this would escape recordOutcomeSafely
      // (which only caught OutcomeWriteException) and crash invokeProcessBatch. Post-fix, the
      // good file still progresses to indexing.
      invokeProcessBatch(loop, List.of(new JobQueue.IndexJob(bad, null), new JobQueue.IndexJob(good, null)));

      verify(queue.indexingCoordinator).indexSingle(any());
    }

    @Test
    void badFileDoesNotBlockGoodFileInSameBatch() throws Exception {
      Path bad = Files.writeString(Files.createTempFile("js-bad-batch", ".txt"), "bad");
      Path good = Files.writeString(Files.createTempFile("js-good-batch", ".txt"), "good");
      RecordingQueue queue = new RecordingQueue();
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path) throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("good", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      invokeProcessBatch(loop, List.of(new JobQueue.IndexJob(bad, null), new JobQueue.IndexJob(good, null)));

      assertEquals(IngestionOutcomeClass.PARSER_FAILED, queue.lastOutcome.outcomeClass());
      verify(queue.indexingCoordinator).indexSingle(any());
    }

    // Tempdoc 516 Slice 1 + W2.6 — regression net for upcoming P1 extractions:

    @Test
    @DisplayName(
        "StaleSourceHandler.deleteMissingSource returns 1 on coordinator success + 0 on "
            + "coordinator exception (W2.6: isolation test for the seam Appendix A.1 named)")
    void staleSourceHandlerReturnsOneOnSuccessZeroOnException() throws Exception {
      IndexingCoordinator successCoordinator = mock(IndexingCoordinator.class);
      io.justsearch.indexerworker.loop.StaleSourceHandler okHandler =
          new io.justsearch.indexerworker.loop.StaleSourceHandler(successCoordinator);
      Path file = Files.writeString(Files.createTempFile("js-stale-ok", ".txt"), "body");
      assertEquals(1, okHandler.deleteMissingSource(file),
          "Successful coordinator.deleteByIdAndChunks must surface as 1 so the caller bumps "
              + "indexedSinceCommit. The cross-seam contract Appendix A.1 named.");
      verify(successCoordinator).deleteByIdAndChunks(anyString());

      IndexingCoordinator throwingCoordinator = mock(IndexingCoordinator.class);
      doThrow(new RuntimeException("simulated lucene failure"))
          .when(throwingCoordinator).deleteByIdAndChunks(anyString());
      io.justsearch.indexerworker.loop.StaleSourceHandler failHandler =
          new io.justsearch.indexerworker.loop.StaleSourceHandler(throwingCoordinator);
      assertEquals(0, failHandler.deleteMissingSource(file),
          "Coordinator exception must be swallowed and surfaced as 0 so the caller does not "
              + "bump indexedSinceCommit on a no-op (best-effort delete semantics).");
    }


    @Test
    @DisplayName(
        "processBatch maintains the batchIndexed+Skipped+Failed counter invariant across "
            + "mixed outcomes (regression net for Slice 4a Extractor/Writer split)")
    void processBatchMaintainsCounterInvariantAcrossMixedOutcomes() throws Exception {
      Path good1 = Files.writeString(Files.createTempFile("js-inv-good1", ".txt"), "alpha");
      Path good2 = Files.writeString(Files.createTempFile("js-inv-good2", ".txt"), "beta");
      Path bad = Files.writeString(Files.createTempFile("js-inv-bad", ".txt"), "gamma");
      Path missing = Files.createTempDirectory("js-inv-missing").resolve("gone.txt");
      Path nonRegular = Files.createTempDirectory("js-inv-dir");

      RecordingQueue queue = new RecordingQueue();
      // ContentExtractor: good paths succeed; bad throws parser exception; missing/nonRegular
      // are rejected before the extractor (filesystem checks).
      IndexingLoop loop =
          newLoop(
              queue,
              new ContentExtractorProvider() {
                @Override
                public ExtractionResult extract(Path path)
                    throws ContentExtractor.ExtractionException {
                  if (path.equals(bad)) {
                    throw new ContentExtractor.ExtractionException("parser boom");
                  }
                  return new ExtractionResult("content", null, "text/plain");
                }

                @Override
                public String detectMimeType(Path path) {
                  return "text/plain";
                }
              });
      setRunning(loop, true);

      List<JobQueue.IndexJob> jobs =
          List.of(
              new JobQueue.IndexJob(good1, null),
              new JobQueue.IndexJob(good2, null),
              new JobQueue.IndexJob(bad, null),
              new JobQueue.IndexJob(missing, null),
              new JobQueue.IndexJob(nonRegular, null));
      invokeProcessBatch(loop, jobs);

      // Tempdoc 516 Slice 3: the 4 batch counter fields were encapsulated in BatchStats.
      var batchStatsField = IndexingLoop.class.getDeclaredField("batchStats");
      batchStatsField.setAccessible(true);
      var batchStats =
          (io.justsearch.indexerworker.loop.ops.BatchStats) batchStatsField.get(loop);
      long indexed = batchStats.indexed();
      long skipped = batchStats.skipped();
      long failed = batchStats.failed();

      assertEquals(
          jobs.size(),
          indexed + skipped + failed,
          "Counter invariant must hold: indexed("
              + indexed
              + ") + skipped("
              + skipped
              + ") + failed("
              + failed
              + ") = jobs("
              + jobs.size()
              + "). Slice 4a's Extractor/Writer split must thread these counters through "
              + "BatchStats so the invariant survives extraction.");
    }

    @Test
    @DisplayName(
        "maybeFinalizeEmbeddingRebuildIfNeeded commits + stamps fingerprint + resets counters "
            + "when ECC reports rebuild complete (regression net for Slice 4c Embedding "
            + "lifecycle extraction)")
    void maybeFinalizeEmbeddingRebuildIfNeededCommitsAndResetsCounters() throws Exception {
      RecordingQueue queue = new RecordingQueue();
      CommitOps commitOps = mock(CommitOps.class);
      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
      queue.indexingCoordinator = mock(IndexingCoordinator.class);
      when(indexCountOps.countByField(any(), any())).thenReturn(0);

      IndexingLoop loop =
          new IndexingLoop(
              queue,
              queue.indexingCoordinator,
              commitOps,
              documentFieldOps,
              indexCountOps,
              () -> null,
              signalBus,
              null,
              null,
              null,
              null,
              new TimeboxedContentExtractor(
                  providerReturning("unused"),
                  Duration.ofSeconds(5),
                  (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
              null, // W7.2 — default EncoderBindings
              null); // W7.2 followup — default IndexingLoopOptions

      // ECC reports REBUILDING + completion confirmed.
      io.justsearch.indexerworker.embed.EmbeddingCompatibilityController ecc =
          mock(io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.class);
      when(ecc.state())
          .thenReturn(
              io.justsearch.indexerworker.embed.EmbeddingCompatibilityController.State.REBUILDING);
      when(ecc.checkRebuildCompletion(0L, 0)).thenReturn(true);
      loop.getEmbeddingLifecycle().setEmbeddingCompatController(ecc);

      // Pre-set indexedSinceCommit > 0 so we can assert it resets.
      var indexedField = IndexingLoop.class.getDeclaredField("indexedSinceCommit");
      indexedField.setAccessible(true);
      indexedField.setLong(loop, 7L);

      // Tempdoc 516 Slice 4c: maybeFinalize is now a wrapper that delegates to the
      // lifecycle's tryFinalizeRebuild() and resets the commit-driver counters on true.
      Method finalize =
          IndexingLoop.class.getDeclaredMethod("tryFinalizeEmbeddingRebuild");
      finalize.setAccessible(true);
      finalize.invoke(loop);

      verify(commitOps)
          .commitAndTrack(io.justsearch.adapters.lucene.runtime.CommitReason.INDEXING_LOOP_REBUILD_STAMP);
      verify(ecc).onFingerprintStamped();
      assertEquals(
          0L,
          indexedField.getLong(loop),
          "indexedSinceCommit must reset after the rebuild-stamp commit. Slice 4c's "
              + "EmbeddingProviderLifecycle extraction must preserve this cross-cutting reset "
              + "via the shouldStampRebuild/onFingerprintStamped hook described in Appendix "
              + "A.6 constraint #11.");
    }

    private IndexingLoop newLoop(RecordingQueue queue, ContentExtractorProvider provider) {
      DocumentFieldOps documentFieldOps = mock(DocumentFieldOps.class);
      IndexCountOps indexCountOps = mock(IndexCountOps.class);
      WorkerSignalBus signalBus = mock(WorkerSignalBus.class);
      queue.indexingCoordinator = mock(IndexingCoordinator.class);
      return new IndexingLoop(
          queue,
          queue.indexingCoordinator,
          mock(CommitOps.class),
          documentFieldOps,
          indexCountOps,
          () -> null,
          signalBus,
          null,
          null,
          null,
          null,
          new TimeboxedContentExtractor(
              provider,
              Duration.ofSeconds(5),
              (io.justsearch.indexerworker.extract.ExtractionMetricCatalog) null),
          null, // W7.2 — default EncoderBindings
          null); // W7.2 followup — default IndexingLoopOptions
    }

    private Object invokeExtractJob(IndexingLoop loop, Path file) throws Exception {
      // W5.2: extractJob moved to JobBatchExtractor. Call via the package-private accessor
      // + reflection on the now-private extractJob method on the extractor.
      Method method =
          JobBatchExtractor.class.getDeclaredMethod("extractJob", Path.class, String.class);
      method.setAccessible(true);
      return method.invoke(loop.getExtractor(), file, null);
    }

    private void invokeWriteExtractedJob(IndexingLoop loop, Object extractedJob) throws Exception {
      // W5.1: writeExtractedJob moved to JobBatchWriter.write.
      loop.getWriter().write((io.justsearch.indexerworker.loop.ExtractedJob) extractedJob, null);
    }

    private void invokeProcessBatch(IndexingLoop loop, List<JobQueue.IndexJob> jobs) throws Exception {
      Method method = IndexingLoop.class.getDeclaredMethod("processBatch", List.class);
      method.setAccessible(true);
      method.invoke(loop, jobs);
    }

    private void setRunning(IndexingLoop loop, boolean value) throws Exception {
      var field = IndexingLoop.class.getDeclaredField("running");
      field.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicBoolean) field.get(loop)).set(value);
    }

    private Object extractedJob(Path file, String content) throws Exception {
      return buildExtractedJob(file, content, false);
    }

    /**
     * Slice G.1 — variant of {@link #extractedJob} that constructs a SUCCESS_PARTIAL artifact
     * (truncated=true). Used by the mixed-batch drain test to verify outcome partitioning.
     */
    private Object extractedJobPartial(Path file, String content) throws Exception {
      return buildExtractedJob(file, content, true);
    }

    private Object buildExtractedJob(Path file, String content, boolean truncated) throws Exception {
      // Tempdoc 516 Slice 3: ExtractedJob moved from IndexingLoop$ExtractedJob (private nested)
      // to io.justsearch.indexerworker.loop.ops.ExtractedJob (package-public record) so the
      // upcoming Slice 4a JobBatchExtractor can return it without a circular package reference.
      ExtractionResult extraction = new ExtractionResult(content, null, "text/plain");
      FileEnvelope envelope = FileEnvelope.fromSnapshot(FileFreshnessSnapshot.capture(file));
      ValidatedExtractionArtifact artifact =
          ExtractionArtifact.full(extraction, TikaExtractionPolicy.defaults(), "test-structured", truncated)
              .validate(TikaExtractionPolicy.defaults(), envelope.pathHash());
      return new io.justsearch.indexerworker.loop.ExtractedJob(
          file,
          null,
          artifact,
          System.currentTimeMillis(),
          envelope);
    }

    private ContentExtractorProvider providerReturning(String content) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) {
          return new ExtractionResult(content, null, "text/plain");
        }

        @Override
        public String detectMimeType(Path file) {
          return "text/plain";
        }
      };
    }

    private ContentExtractorProvider providerThrowing(ContentExtractor.ExtractionException exception) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) throws ContentExtractor.ExtractionException {
          throw exception;
        }

        @Override
        public String detectMimeType(Path file) {
          return "text/plain";
        }
      };
    }

    private ContentExtractorProvider providerThrowingWithoutDetect(
        ContentExtractor.ExtractionException exception) {
      return new ContentExtractorProvider() {
        @Override
        public ExtractionResult extract(Path file) throws ContentExtractor.ExtractionException {
          throw exception;
        }

        @Override
        public String detectMimeType(Path file) {
          throw new AssertionError("Failure metrics must not re-enter MIME detection");
        }
      };
    }
  }

  private static final class RecordingQueue implements JobQueue {
    IngestionOutcome lastOutcome;
    IngestionLedgerEntry lastEntry;
    /**
     * Slice G.1 — every outcome-bearing markDone* call is appended here so tests can assert on
     * the full sequence (the prior single-{@code lastOutcome} field collapsed multiple calls
     * into the last one, which masked the LEDGER ↔ DOCUMENT inconsistency the slice fixes).
     */
    final java.util.List<IngestionOutcome> capturedOutcomes = new java.util.ArrayList<>();

    boolean done;
    boolean terminalFailed;
    boolean deferred;
    boolean failOutcomeWrites;
    boolean failOutcomeWritesAsIllegalArgument;
    IndexingCoordinator indexingCoordinator;

    private void maybeFail(String op) {
      if (failOutcomeWritesAsIllegalArgument) {
        // B-H.4 defect F — simulate the SqliteJobQueue defer-policy guard misroute.
        throw new IllegalArgumentException("simulated defer-policy misroute during " + op);
      }
      if (failOutcomeWrites) {
        throw new io.justsearch.indexerworker.queue.OutcomeWriteException(
            "simulated rollback during " + op, null);
      }
    }

    @Override
    public void open() {}

    @Override
    public int enqueue(java.util.List<Path> paths, String collection) {
      return paths == null ? 0 : paths.size();
    }

    @Override
    public java.util.List<IndexJob> pollPending(int limit) {
      return java.util.List.of();
    }

    @Override
    public void markDone(Path path) {
      done = true;
    }

    private void record(IngestionOutcome outcome) {
      lastOutcome = outcome;
      if (outcome != null) {
        capturedOutcomes.add(outcome);
      }
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome) {
      maybeFail("markDone");
      record(outcome);
      done = true;
    }

    @Override
    public void markDone(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("markDone");
      record(outcome);
      lastEntry = entry;
      done = true;
    }

    @Override
    public void markDoneTransitions(
        java.util.Collection<IngestionLedgerTransition> transitions, IngestionOutcome outcome) {
      maybeFail("markDoneTransitions");
      record(outcome);
      lastEntry =
          transitions == null || transitions.isEmpty()
              ? null
              : transitions.iterator().next().entry();
      done = true;
    }

    @Override
    public void markFailed(Path path, String errorMessage) {}

    @Override
    public void markFailed(Path path, IngestionOutcome outcome) {
      maybeFail("markFailed");
      record(outcome);
      if (outcome != null
          && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE) {
        terminalFailed = true;
      }
    }

    @Override
    public void markFailed(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("markFailed");
      record(outcome);
      lastEntry = entry;
      if (outcome != null
          && outcome.retryPolicy() == io.justsearch.indexerworker.ingest.IngestionRetryPolicy.NONE) {
        terminalFailed = true;
      }
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome) {
      maybeFail("defer");
      record(outcome);
      deferred = true;
    }

    @Override
    public void defer(Path path, IngestionOutcome outcome, IngestionLedgerEntry entry) {
      maybeFail("defer");
      record(outcome);
      lastEntry = entry;
      deferred = true;
    }

    @Override
    public int recoverStuckJobs() {
      return 0;
    }

    @Override
    public long queueDepth() {
      return 0;
    }

    @Override
    public long completedCount() {
      return 0;
    }

    @Override
    public int cleanupOldJobs(int retentionDays) {
      return 0;
    }

    @Override
    public void close() {}
  }
}
