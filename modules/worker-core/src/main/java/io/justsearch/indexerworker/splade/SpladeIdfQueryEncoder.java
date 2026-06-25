/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.splade;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.modality.nlp.Vocabulary;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IDF-weighted SPLADE query encoder — inference-free alternative to ONNX query encoding.
 *
 * <p>Replaces neural query encoding with a simple token lookup: tokenize the query with the same
 * WordPiece tokenizer, then assign each token its pre-computed IDF weight from the training corpus
 * (MS MARCO). This approach is used by OpenSearch doc-v3 models and produces comparable quality to
 * neural query encoding at sub-millisecond latency.
 *
 * <p>Thread-safe: the tokenizer is thread-safe (DJL contract), and the IDF map is immutable after
 * construction.
 */
public final class SpladeIdfQueryEncoder {

  private static final Logger log = LoggerFactory.getLogger(SpladeIdfQueryEncoder.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** BERT special token IDs to exclude: [PAD], [UNK], [CLS], [SEP], [MASK]. */
  private static final Set<Integer> SKIP_TOKEN_IDS = Set.of(0, 100, 101, 102, 103);

  private final HuggingFaceTokenizer tokenizer;
  private final Vocabulary vocabulary;
  private final Map<String, Float> idfMap;

  /**
   * Creates an IDF query encoder.
   *
   * @param idfJsonPath path to the {@code idf.json} file (token → IDF weight map)
   * @param tokenizer shared HuggingFace tokenizer (from SpladeEncoder)
   * @param vocabulary shared vocabulary (from SpladeEncoder)
   * @throws IOException if the IDF file cannot be read or parsed
   */
  public SpladeIdfQueryEncoder(
      Path idfJsonPath, HuggingFaceTokenizer tokenizer, Vocabulary vocabulary) throws IOException {
    this.tokenizer = tokenizer;
    this.vocabulary = vocabulary;
    try (InputStream in = Files.newInputStream(idfJsonPath)) {
      this.idfMap = MAPPER.readValue(in, new TypeReference<Map<String, Float>>() {});
    }
    log.info("Loaded IDF table: {} entries from {}", idfMap.size(), idfJsonPath);
  }

  /**
   * Encodes a query string into a sparse vector using IDF-weighted token lookup.
   *
   * @param text the query text
   * @return sparse vector mapping token strings to IDF weights
   */
  public Map<String, Float> encode(String text) {
    Encoding encoding = tokenizer.encode(text);
    return encodeFromTokenIds(encoding.getIds());
  }

  /**
   * Core encoding logic: converts token IDs to a sparse vector using IDF weights.
   *
   * <p>Package-private for unit testing without requiring a real tokenizer.
   *
   * @param tokenIds token IDs from the tokenizer
   * @return sparse vector mapping token strings to IDF weights (deduplicated, filtered)
   */
  Map<String, Float> encodeFromTokenIds(long[] tokenIds) {
    Map<String, Float> result = new LinkedHashMap<>();
    for (long id : tokenIds) {
      int tokenId = (int) id;
      if (SKIP_TOKEN_IDS.contains(tokenId)) {
        continue;
      }
      String token = vocabulary.getToken(tokenId);
      if (token == null || result.containsKey(token)) {
        continue;
      }
      Float weight = idfMap.get(token);
      if (weight != null && weight > 0.0f) {
        result.put(token, weight);
      }
    }
    return result;
  }
}
