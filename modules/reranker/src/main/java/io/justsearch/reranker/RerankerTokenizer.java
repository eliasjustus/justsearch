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
    long[][] rowIds = new long[batchSize][];
    long[][] rowMasks = new long[batchSize][];
    long[][] rowTypeIds = new long[batchSize][];

    for (int i = 0; i < batchSize; i++) {
      // Encode query-document pair using the tokenizer's built-in pair support
      Encoding encoding = tokenizer.encode(query, documents[i]);
      rowIds[i] = encoding.getIds();
      rowMasks[i] = encoding.getAttentionMask();
      rowTypeIds[i] = encoding.getTypeIds();
    }

    return pack(rowIds, rowMasks, rowTypeIds, maxLength);
  }

  /**
   * Encodes pairs and refuses to return a silently truncated batch (tempdoc 836 §1.3(b)).
   *
   * <p>Truncation here is not "a shorter passage": {@link #pack} keeps the FIRST {@code maxLength}
   * tokens, so an overlong pair loses its trailing {@code [SEP]} and its second segment's
   * {@code token_type_ids} are cut mid-run — the model is fed a malformed pair and scores it
   * anyway. Callers that have windowed their passages to fit (citation scoring) use this method so
   * that a window which still overflows is a loud failure rather than an invisible one.
   *
   * @throws PairTooLongException if any pair exceeds the configured maximum sequence length
   */
  public EncodedBatch encodePairsStrict(String query, String[] documents) {
    return requireUntruncated(encodePairs(query, documents), maxLength);
  }

  /**
   * Returns the batch, or raises if any of its pairs was cut. The guard is separate from encoding
   * so the refusal is testable without a tokenizer model on disk.
   *
   * @throws PairTooLongException if any pair exceeded {@code maxLength}
   */
  static EncodedBatch requireUntruncated(EncodedBatch batch, int maxLength) {
    if (batch.truncatedPairs() > 0) {
      throw new PairTooLongException(
          "cross-encoder pair exceeds maxLength="
              + maxLength
              + " in "
              + batch.truncatedPairs()
              + " of "
              + batch.batchSize()
              + " pairs (longest "
              + batch.longestPairTokens()
              + " tokens)");
    }
    return batch;
  }

  /**
   * Truncates each row to {@code maxLength}, pads the remainder, and records how many rows had to
   * be cut. Pure function — the truncation-detection half of §1.3(b) lives here so it is testable
   * without a tokenizer model on disk.
   */
  static EncodedBatch pack(long[][] rowIds, long[][] rowMasks, long[][] rowTypeIds, int maxLength) {
    int batchSize = rowIds.length;
    long[][] inputIds = new long[batchSize][maxLength];
    long[][] attentionMask = new long[batchSize][maxLength];
    long[][] tokenTypeIds = new long[batchSize][maxLength];

    int truncatedPairs = 0;
    int longestPairTokens = 0;
    for (int i = 0; i < batchSize; i++) {
      long[] ids = rowIds[i];
      long[] mask = rowMasks[i];
      long[] typeIds = rowTypeIds[i];

      longestPairTokens = Math.max(longestPairTokens, ids.length);
      if (ids.length > maxLength) {
        truncatedPairs++;
      }

      int copyLen = Math.min(ids.length, maxLength);
      System.arraycopy(ids, 0, inputIds[i], 0, copyLen);
      if (mask != null && mask.length > 0) {
        System.arraycopy(mask, 0, attentionMask[i], 0, Math.min(mask.length, maxLength));
      }
      if (typeIds != null && typeIds.length > 0) {
        System.arraycopy(typeIds, 0, tokenTypeIds[i], 0, Math.min(typeIds.length, maxLength));
      }
      // Remaining positions are already 0 (default array initialization = padding)
    }

    return new EncodedBatch(
        inputIds, attentionMask, tokenTypeIds, batchSize, maxLength, truncatedPairs,
        longestPairTokens);
  }

  /** Returns the configured maximum sequence length. */
  public int maxLength() {
    return maxLength;
  }

  /**
   * Raised when a (query, document) pair does not fit the model's sequence window, so scoring it
   * would mean scoring a malformed pair.
   */
  public static final class PairTooLongException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PairTooLongException(String message) {
      super(message);
    }
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
   * @param truncatedPairs how many pairs did not fit {@code seqLength} and were cut (tempdoc 836
   *     §1.3(b) — the tokenizer's own truncation was previously invisible to every caller)
   * @param longestPairTokens token count of the longest pair before truncation
   */
  public record EncodedBatch(
      long[][] inputIds,
      long[][] attentionMask,
      long[][] tokenTypeIds,
      int batchSize,
      int seqLength,
      int truncatedPairs,
      int longestPairTokens) {}
}
