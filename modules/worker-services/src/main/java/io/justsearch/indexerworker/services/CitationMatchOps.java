/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.services;

import io.justsearch.adapters.lucene.runtime.CommitOps;
import io.justsearch.adapters.lucene.runtime.LuceneRuntimeTypes;
import io.justsearch.adapters.lucene.runtime.ReadPathOps;
import io.justsearch.indexerworker.embed.EmbeddingProvider;
import io.justsearch.indexerworker.util.ParseUtils;
import io.justsearch.indexerworker.util.VectorUtils;
import io.justsearch.indexing.SchemaFields;
import io.justsearch.ipc.CitationMatchEntry;
import io.justsearch.ipc.MatchCitationsResponse;
import io.justsearch.reranker.CitationScorer;
import io.justsearch.reranker.CitationScorerConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.lucene.search.TermQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Citation matching logic extracted from {@link GrpcSearchService}.
 *
 * <p>Manages the lazy-initialized {@link CitationScorer} (CPU-only ONNX cross-encoder)
 * and provides an embedding-based cosine similarity fallback path.
 */
final class CitationMatchOps {
  private static final Logger log = LoggerFactory.getLogger(CitationMatchOps.class);

  static final double DEFAULT_SIMILARITY_THRESHOLD = 0.5;

  private final ReadPathOps readPathOps;
  private final CommitOps commitOps;
  private volatile EmbeddingProvider embeddingProvider;

  // Citation scoring (CPU-only ONNX cross-encoder). Tempdoc 397 §14.26 T2-E1: the composition
  // root builds the scorer eagerly; this class is a pure consumer. No lazy construction path.
  private volatile CitationScorerConfig citationScorerConfig;
  private volatile CitationScorer citationScorer;

  CitationMatchOps(ReadPathOps readPathOps, CommitOps commitOps, EmbeddingProvider embeddingProvider) {
    this.readPathOps = readPathOps;
    this.commitOps = commitOps;
    this.embeddingProvider = embeddingProvider;
  }

  void setEmbeddingProvider(EmbeddingProvider embeddingProvider) {
    this.embeddingProvider = embeddingProvider;
  }

  /**
   * Sets the citation scorer configuration.
   *
   * <p>When config is ready (enabled + model path set), the scorer will be lazily initialized
   * on first use. The scorer runs on CPU only, avoiding GPU contention with the LLM.
   *
   * @param config the citation scorer configuration
   */
  /** Returns true if the citation scorer is initialized and ready for inference. */
  boolean isCitationScorerActive() {
    CitationScorer scorer = citationScorer;
    return scorer != null && scorer.isAvailable();
  }

  /**
   * Sets the eagerly-constructed citation scorer built by the composition root
   * (tempdoc 397 §14.26 T2-E1). Replaces the pre-T2-E1 pair
   * {@code setCitationScorerSessions(SessionHandle)} + lazy {@link #getCitationScorer} init.
   * When null, citation scoring falls back to embedding-based cosine similarity.
   */
  void setCitationScorer(CitationScorer scorer) {
    this.citationScorer = scorer;
    if (scorer != null && citationScorerConfig != null) {
      var config = citationScorerConfig;
      // Tempdoc 374 sandbox round 4 issue H: resolve via ModelManifest so the
      // fingerprint identifies whichever variant Install AI placed on disk.
      Path modelOnnx =
          io.justsearch.ort.ModelManifest.loadOrDefault(config.modelPath())
              .resolveExistingModelFile(config.modelPath());
      String fingerprint = computeModelSha256(modelOnnx);
      if (fingerprint != null) {
        log.info(
            "Citation scorer wired: model={}, sha256={}",
            modelOnnx.getFileName(),
            fingerprint.substring(0, 16) + "...");
      } else {
        log.info(
            "Citation scorer wired: model={} (fingerprint unavailable)", config.modelPath());
      }
    }
  }

  void setCitationScorerConfig(CitationScorerConfig config) {
    this.citationScorerConfig = config;
    if (config != null && config.isReady()) {
      log.info("Citation scorer enabled: threshold={}, maxSeqLen={}, deadline={}ms, modelPath={}",
          config.threshold(), config.maxSequenceLength(), config.deadlineBudgetMs(),
          config.modelPath());
    }
  }

  /**
   * Returns the wired citation scorer, or {@code null} if the composition root did not wire one
   * (dev mode without contract, model absent, or tokenizer load failure). Pure getter —
   * tempdoc 397 §14.26 T2-E1 deleted the former lazy-init path.
   */
  private CitationScorer getCitationScorer() {
    return citationScorer;
  }

  /**
   * Executes citation matching: maps answer sentences to source chunks.
   *
   * <p>Tries CPU-based cross-encoder first (no GPU contention), falls back to
   * embedding-based cosine similarity if the scorer is unavailable or fails.
   *
   * @return a fully-built MatchCitationsResponse for all paths (success, fallback, error)
   */
  MatchCitationsResponse execute(
      String answerText,
      List<String> chunkDocIds,
      List<Integer> chunkIndices,
      double threshold) {
    long startTime = System.currentTimeMillis();

    log.debug("MatchCitations request: answerLen={}, chunks={}, threshold={}",
        answerText.length(), chunkDocIds.size(), threshold);

    if (answerText.isBlank() || chunkDocIds.isEmpty()) {
      return MatchCitationsResponse.newBuilder()
          .setSentencesTotal(0)
          .setSentencesMatched(0)
          .setTookMs(System.currentTimeMillis() - startTime)
          .build();
    }

    // Try CPU-based cross-encoder first (no GPU contention), fall back to embedding similarity
    CitationScorer scorer = getCitationScorer();
    if (scorer != null && scorer.isAvailable()) {
      try {
        commitOps.maybeRefresh();

        List<String> sentenceList = splitSentences(answerText.trim());

        // Look up chunk content from index
        int chunkCount = Math.min(chunkDocIds.size(), chunkIndices.size());
        List<String> chunkTexts = new ArrayList<>(chunkCount);
        List<String> resolvedChunkDocIds = new ArrayList<>(chunkCount);
        for (int i = 0; i < chunkCount; i++) {
          String chunkContent = lookupChunkContent(chunkDocIds.get(i), chunkIndices.get(i));
          chunkTexts.add(chunkContent != null ? chunkContent : "");
          resolvedChunkDocIds.add(chunkDocIds.get(i));
        }

        var csConfig = citationScorerConfig;
        long deadlineMs = csConfig != null ? csConfig.deadlineBudgetMs() : 2000;
        CitationScorer.ScoringResult result =
            scorer.scoreAll(sentenceList, chunkTexts, resolvedChunkDocIds, threshold, deadlineMs);

        List<CitationMatchEntry> matches = new ArrayList<>();
        for (CitationScorer.ScoredMatch match : result.matches()) {
          matches.add(CitationMatchEntry.newBuilder()
              .setSentenceIndex(match.sentenceIndex())
              .setSentenceText(match.sentenceText())
              .setChunkIndex(match.chunkIndex())
              .setSimilarity(match.score())
              .setParentDocId(match.parentDocId())
              .build());
        }

        return MatchCitationsResponse.newBuilder()
            .addAllMatches(matches)
            .setSentencesTotal(result.sentencesTotal())
            .setSentencesMatched(result.sentencesMatched())
            .setTookMs(System.currentTimeMillis() - startTime)
            .build();

      } catch (Exception e) {
        log.warn("CitationScorer failed, falling back to embedding path: {}", e.getMessage());
        log.debug("CitationScorer failed (stack trace)", e);
      }
    }

    // Fallback: embedding-based cosine similarity
    if (!embeddingProvider.isAvailable()) {
      return MatchCitationsResponse.newBuilder()
          .setSentencesTotal(0)
          .setSentencesMatched(0)
          .setTookMs(System.currentTimeMillis() - startTime)
          .setError("EMBEDDING_UNAVAILABLE")
          .build();
    }

    try {
      commitOps.maybeRefresh();

      // 1. Split answer into sentences using BreakIterator (handles abbreviations, decimals)
      List<String> sentenceList = splitSentences(answerText.trim());

      // 2. Embed each sentence
      List<float[]> sentenceVectors = new ArrayList<>(sentenceList.size());
      for (String sentence : sentenceList) {
        float[] vec = embeddingProvider.embedQuery(sentence);
        sentenceVectors.add(vec);
      }

      // 3. Look up chunk content and embed each chunk
      int chunkCount = Math.min(chunkDocIds.size(), chunkIndices.size());
      List<float[]> chunkVectors = new ArrayList<>(chunkCount);
      List<String> resolvedChunkDocIds = new ArrayList<>(chunkCount);
      for (int i = 0; i < chunkCount; i++) {
        String chunkContent = lookupChunkContent(chunkDocIds.get(i), chunkIndices.get(i));
        if (chunkContent != null && !chunkContent.isBlank()) {
          float[] vec = embeddingProvider.embedDocument(chunkContent);
          chunkVectors.add(vec);
          resolvedChunkDocIds.add(chunkDocIds.get(i));
        } else {
          chunkVectors.add(null);
          resolvedChunkDocIds.add(chunkDocIds.get(i));
        }
      }

      // 4. Compute cosine similarity and find best matches
      List<CitationMatchEntry> matches = new ArrayList<>();
      int sentencesMatched = 0;
      for (int si = 0; si < sentenceList.size(); si++) {
        float[] sentenceVec = sentenceVectors.get(si);
        if (sentenceVec == null || sentenceVec.length == 0) {
          continue;
        }
        double bestSim = 0.0;
        int bestChunkIdx = -1;
        for (int ci = 0; ci < chunkVectors.size(); ci++) {
          float[] chunkVec = chunkVectors.get(ci);
          if (chunkVec == null || chunkVec.length == 0) {
            continue;
          }
          double sim = VectorUtils.cosine(sentenceVec, chunkVec);
          if (sim > bestSim) {
            bestSim = sim;
            bestChunkIdx = ci;
          }
        }
        if (bestChunkIdx >= 0 && bestSim >= threshold) {
          sentencesMatched++;
          matches.add(CitationMatchEntry.newBuilder()
              .setSentenceIndex(si)
              .setSentenceText(sentenceList.get(si))
              .setChunkIndex(bestChunkIdx)
              .setSimilarity(bestSim)
              .setParentDocId(resolvedChunkDocIds.get(bestChunkIdx))
              .build());
        }
      }

      return MatchCitationsResponse.newBuilder()
          .addAllMatches(matches)
          .setSentencesTotal(sentenceList.size())
          .setSentencesMatched(sentencesMatched)
          .setTookMs(System.currentTimeMillis() - startTime)
          .build();

    } catch (Exception e) {
      log.warn("MatchCitations failed", e);
      return MatchCitationsResponse.newBuilder()
          .setSentencesTotal(0)
          .setSentencesMatched(0)
          .setTookMs(System.currentTimeMillis() - startTime)
          .setError(e.getMessage() == null ? "UNKNOWN" : e.getMessage())
          .build();
    }
  }

  /**
   * Splits text into sentences using {@link java.text.BreakIterator}.
   * Better than regex for boundary detection but still imperfect on abbreviations (Dr., Mr.).
   */
  static List<String> splitSentences(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    BreakIterator bi = BreakIterator.getSentenceInstance(Locale.ENGLISH);
    bi.setText(text);
    List<String> sentences = new ArrayList<>();
    int start = bi.first();
    for (int end = bi.next(); end != BreakIterator.DONE; start = end, end = bi.next()) {
      String sentence = text.substring(start, end).trim();
      if (!sentence.isEmpty()) {
        sentences.add(sentence);
      }
    }
    return sentences;
  }

  /**
   * Looks up chunk content by parent doc ID and chunk index from the Lucene index.
   *
   * <p>Queries by parent_doc_id (keyword field, term-indexed) and then filters
   * by chunk_index in Java. chunk_index is a long/DocValues field without an
   * inverted index, so it cannot be queried via TermQuery or LongPoint.
   *
   * @return chunk content text, or null if not found
   */
  /** Computes SHA-256 of a model file for fingerprint comparison. */
  private static String computeModelSha256(Path modelFile) {
    if (modelFile == null || !Files.exists(modelFile)) {
      return null;
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8 * 1024 * 1024]; // 8 MB
      try (InputStream in = Files.newInputStream(modelFile)) {
        int read;
        while ((read = in.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError("SHA-256 not available", e);
    } catch (IOException e) {
      log.warn("Failed to compute SHA-256 for {}", modelFile.getFileName(), e);
      return null;
    }
  }

  private String lookupChunkContent(String parentDocId, int chunkIndex) {
    try {
      // Query by parent_doc_id only (term-indexed keyword), fetch enough to find the right chunk
      TermQuery query =
          new TermQuery(
              new org.apache.lucene.index.Term(SchemaFields.PARENT_DOC_ID, parentDocId));
      LuceneRuntimeTypes.SearchResult result = readPathOps.search(query, 500,
          Set.of(SchemaFields.CHUNK_CONTENT, SchemaFields.CHUNK_INDEX), null, null);
      for (var hit : result.hits()) {
        int idx = ParseUtils.parseIntSafe(hit.fields().get(SchemaFields.CHUNK_INDEX), -1);
        if (idx == chunkIndex) {
          return hit.fields().get(SchemaFields.CHUNK_CONTENT);
        }
      }
      return null;
    } catch (Exception e) {
      log.debug("Failed to lookup chunk {}:{}: {}", parentDocId, chunkIndex, e.getMessage());
      return null;
    }
  }
}
