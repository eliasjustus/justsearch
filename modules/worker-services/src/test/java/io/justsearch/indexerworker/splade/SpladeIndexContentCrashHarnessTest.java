package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.store.FSDirectory;
import org.junit.jupiter.api.DisplayName;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tempdoc 686 crash forensics (manual/evidence harness, not CI): the full-corpus enrichment
 * backfill crash-looped with a native Rust panic in {@code
 * TokenizersLibrary.getTokenCharSpans} during {@code HuggingFaceTokenizer.batchEncode} (3
 * identical hs_err dumps, thread "indexing-loop"). This harness replays EXACTLY the strings
 * production tokenized — every stored {@code content} and {@code chunk_content} field from the
 * crashed run's on-disk Lucene index — through the same tokenizer in production-shaped batches.
 *
 * <p>A poison document ⇒ deterministic crash at a fixed position (named by the progress file
 * tmp/686-crash-harness-progress.txt, written before each batch). No crash over the full corpus
 * ⇒ input-independent native-state/scale issue instead.
 */
@DisplayName("686: SPLADE tokenizer crash harness over real index contents")
class SpladeIndexContentCrashHarnessTest {

  private static final Path REPO_ROOT = resolveRepoRoot();
  // Models live in the MAIN checkout; from a worktree that's up to ~7 levels above this
  // module, so walk like the bounded-tokenize test does instead of hardcoding a machine path.
  private static final Path TOKENIZER_PATH = resolveTokenizerPath();

  private static Path resolveTokenizerPath() {
    Path p = Path.of("").toAbsolutePath();
    for (int i = 0; i < 8 && p != null; i++) {
      Path tok = p.resolve("models/splade/naver-splade-v3/tokenizer.json");
      if (Files.exists(tok)) {
        return tok;
      }
      p = p.getParent();
    }
    return Path.of("models/splade/naver-splade-v3/tokenizer.json");
  }
  private static final Path INDEX_DIR =
      REPO_ROOT.resolve("tmp/headless-eval-data/index/default/indices/g-20260710-072554");
  private static final Path PROGRESS =
      REPO_ROOT.resolve("tmp/686-crash-harness-progress.txt");
  private static final int BATCH = 4; // production SPLADE batch size (tempdoc 691)

  static boolean assetsAvailable() {
    return Files.exists(TOKENIZER_PATH) && Files.exists(INDEX_DIR);
  }

  private static Path resolveRepoRoot() {
    Path p = Path.of("").toAbsolutePath();
    while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) {
      p = p.getParent();
    }
    return p == null ? Path.of("") : p;
  }

  @Test
  @EnabledIf("assetsAvailable")
  @Timeout(value = 30, unit = TimeUnit.MINUTES)
  @DisplayName("batch-encode every stored content/chunk_content from the crashed run's index")
  void replayAllIndexContents() throws Exception {
    List<String> batchTexts = new ArrayList<>(BATCH);
    List<String> batchIds = new ArrayList<>(BATCH);
    long total = 0;
    try (FSDirectory dir = FSDirectory.open(INDEX_DIR);
        DirectoryReader reader = DirectoryReader.open(dir);
        HuggingFaceTokenizer tokenizer =
            HuggingFaceTokenizer.newInstance(
                TOKENIZER_PATH, Map.of("truncation", "false", "padding", "false"))) {
      StoredFields stored = reader.storedFields();
      for (int i = 0; i < reader.maxDoc(); i++) {
        org.apache.lucene.document.Document doc = stored.document(i);
        String id = firstNonNull(doc.get("doc_id"), doc.get("doc_uid"), "lucene#" + i);
        String content = doc.get("content");
        String chunk = doc.get("chunk_content");
        if (content != null && !content.isBlank()) {
          batchTexts.add(content);
          batchIds.add(id + ":content");
        }
        if (chunk != null && !chunk.isBlank()) {
          batchTexts.add(chunk);
          batchIds.add(id + ":chunk");
        }
        if (batchTexts.size() >= BATCH) {
          total += flushBatch(tokenizer, batchTexts, batchIds, total);
        }
      }
      if (!batchTexts.isEmpty()) {
        total += flushBatch(tokenizer, batchTexts, batchIds, total);
      }
    }
    log("COMPLETED WITHOUT CRASH: " + total + " texts");
  }

  private long flushBatch(
      HuggingFaceTokenizer tokenizer, List<String> texts, List<String> ids, long done)
      throws IOException {
    log("batch at " + done + " ids=" + String.join(",", ids));
    Encoding[] encs = tokenizer.batchEncode(texts);
    assertNotNull(encs);
    int n = texts.size();
    texts.clear();
    ids.clear();
    return n;
  }

  private static String firstNonNull(String... vals) {
    for (String v : vals) {
      if (v != null) {
        return v;
      }
    }
    return "?";
  }

  private static void log(String line) throws IOException {
    Files.writeString(
        PROGRESS,
        line + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }
}
