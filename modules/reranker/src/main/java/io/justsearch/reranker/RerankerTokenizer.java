/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.reranker;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

/**
 * Wrapper around HuggingFace tokenizer for cross-encoder input preparation.
 *
 * <p>Encodes query-document pairs into token IDs and attention masks suitable for ONNX inference.
 */
public final class RerankerTokenizer implements Closeable {
  private final HuggingFaceTokenizer tokenizer;
  private final int maxLength;

  /**
   * Creates a tokenizer from a local tokenizer.json file.
   *
   * @param tokenizerPath path to tokenizer.json
   * @param maxLength maximum sequence length (truncates longer sequences)
   * @throws UncheckedIOException if the tokenizer file cannot be read
   */
  public RerankerTokenizer(Path tokenizerPath, int maxLength) {
    try {
      this.tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to load tokenizer from " + tokenizerPath, e);
    }
    this.maxLength = maxLength;
  }

  /**
   * Encodes query-document pairs for cross-encoder inference.
   *
   * <p>Each document is paired with the query using BERT-style encoding: [CLS] query [SEP] document
   * [SEP]
   *
   * @param query the search query
   * @param documents array of document texts to encode
   * @return encoded batch with input_ids, attention_mask, and token_type_ids arrays
   */
  public EncodedBatch encodePairs(String query, String[] documents) {
    int batchSize = documents.length;
    long[][] inputIds = new long[batchSize][maxLength];
    long[][] attentionMask = new long[batchSize][maxLength];
    long[][] tokenTypeIds = new long[batchSize][maxLength];

    for (int i = 0; i < batchSize; i++) {
      // Encode query-document pair using the tokenizer's built-in pair support
      Encoding encoding = tokenizer.encode(query, documents[i]);
      long[] ids = encoding.getIds();
      long[] mask = encoding.getAttentionMask();
      long[] typeIds = encoding.getTypeIds();

      // Copy with truncation to maxLength, pad remainder with 0s
      int copyLen = Math.min(ids.length, maxLength);
      System.arraycopy(ids, 0, inputIds[i], 0, copyLen);
      System.arraycopy(mask, 0, attentionMask[i], 0, copyLen);
      if (typeIds != null && typeIds.length > 0) {
        System.arraycopy(typeIds, 0, tokenTypeIds[i], 0, Math.min(typeIds.length, maxLength));
      }
      // Remaining positions are already 0 (default array initialization = padding)
    }

    return new EncodedBatch(inputIds, attentionMask, tokenTypeIds, batchSize, maxLength);
  }

  /** Returns the configured maximum sequence length. */
  public int maxLength() {
    return maxLength;
  }

  @Override
  public void close() {
    tokenizer.close();
  }

  /**
   * Batch of encoded inputs ready for ONNX inference.
   *
   * @param inputIds token IDs array [batchSize][seqLength]
   * @param attentionMask attention mask array [batchSize][seqLength]
   * @param tokenTypeIds segment IDs array [batchSize][seqLength] (0 for query, 1 for document)
   * @param batchSize number of samples in batch
   * @param seqLength sequence length (with padding)
   */
  public record EncodedBatch(
      long[][] inputIds,
      long[][] attentionMask,
      long[][] tokenTypeIds,
      int batchSize,
      int seqLength) {}
}
