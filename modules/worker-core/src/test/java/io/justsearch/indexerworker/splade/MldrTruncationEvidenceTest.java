package io.justsearch.indexerworker.splade;

import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * One-shot evidence-gathering test for tempdoc 273 Phase 1.
 *
 * <p>Reads mldr-en corpus, tokenizes each document, and records truncation
 * pressure evidence using SpladeTruncationEvidence. Requires local assets:
 * - models/splade/naver-splade-v3/tokenizer.json
 * - tmp/beir-cache/mldr-en/raw/mldr-en/corpus.jsonl
 */
@DisplayName("273: mldr-en truncation evidence")
@Tag("evidence")
class MldrTruncationEvidenceTest {

  private static final Path REPO_ROOT = resolveRepoRoot();
  private static final Path TOKENIZER_PATH =
      REPO_ROOT.resolve("models/splade/naver-splade-v3/tokenizer.json");
  private static final Path CORPUS_PATH =
      REPO_ROOT.resolve("tmp/beir-cache/mldr-en/raw/mldr-en/corpus.jsonl");
  private static final Path EVIDENCE_OUTPUT =
      REPO_ROOT.resolve("tmp/273-evidence/splade/mldr-en-truncation-evidence.json");
  private static final int MAX_SEQ_LEN = 256;
  private static final int WINDOW_OVERLAP = 64;
  private static final ObjectMapper JSON = new ObjectMapper();

  static boolean assetsAvailable() {
    return Files.exists(TOKENIZER_PATH) && Files.exists(CORPUS_PATH);
  }

  @Test
  @EnabledIf("assetsAvailable")
  @Timeout(value = 5, unit = TimeUnit.MINUTES)
  @DisplayName("gather truncation evidence from mldr-en corpus")
  @SuppressWarnings("unchecked")
  void gatherTruncationEvidence() throws IOException {
    SpladeTruncationEvidence evidence = new SpladeTruncationEvidence(MAX_SEQ_LEN, WINDOW_OVERLAP);

    try (HuggingFaceTokenizer tokenizer =
        HuggingFaceTokenizer.newInstance(
            TOKENIZER_PATH, Map.of("truncation", "false", "padding", "false"));
        BufferedReader reader = Files.newBufferedReader(CORPUS_PATH)) {
      String line;
      int count = 0;
      while ((line = reader.readLine()) != null) {
        Map<String, Object> doc = JSON.readValue(line, Map.class);
        String text = (String) doc.getOrDefault("text", "");
        String title = (String) doc.getOrDefault("title", "");
        String combined = title.isBlank() ? text : title + " " + text;

        long[] ids = tokenizer.encode(combined).getIds();
        evidence.record(ids.length);
        count++;

        if (count % 10000 == 0) {
          System.out.printf("Processed %,d documents...%n", count);
        }
      }
      System.out.printf("Total documents processed: %,d%n", count);
    }

    assertTrue(evidence.hasEvidence(), "should have recorded evidence");

    Map<String, Object> snapshot = evidence.snapshot(TOKENIZER_PATH.getParent());
    System.out.println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot));

    Path parent = EVIDENCE_OUTPUT.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    evidence.write(EVIDENCE_OUTPUT, TOKENIZER_PATH.getParent());
    System.out.println("Evidence written to: " + EVIDENCE_OUTPUT);
  }

  private static Path resolveRepoRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("gradlew.bat"))
          && Files.exists(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    return Path.of("").toAbsolutePath();
  }
}
