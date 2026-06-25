/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.aibackend.backend;

import io.justsearch.aibackend.local.LocalIntentTranslatorConfig;
import io.justsearch.aibackend.local.LocalIntentTranslatorV2;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight backend used for tests and environments that do not yet have the llama.cpp bindings
 * wired up. It preserves the deterministic behavior that the legacy translator exposed.
 */
public final class DeterministicBackend implements AiBackend {
  private final LocalIntentTranslatorV2.Provenance provenance;

  public DeterministicBackend(LocalIntentTranslatorConfig config) {
    this.provenance =
        new LocalIntentTranslatorV2.Provenance(
            config.modelSha256(), config.backend(), config.gpuLayers());
  }

  @Override
  public BackendResponse translate(BackendRequest request) throws BackendException {
    try {
      Locale locale = request.locale();
      String json = IntentJsonTemplate.render(request.text(), locale, false, null);
      return new BackendResponse(json);
    } catch (IOException e) {
      throw new BackendException("Failed to render deterministic intent", e);
    }
  }

  @Override
  public Session createSession() {
    return new DeterministicSession();
  }

  @Override
  public LocalIntentTranslatorV2.Provenance provenance() {
    return provenance;
  }

  @Override
  public void close() {}

  private static final class DeterministicSession implements Session {

    @Override
    public BackendResponse translate(BackendRequest request) throws BackendException {
      try {
        Locale locale = request.locale();
        String json = IntentJsonTemplate.render(request.text(), locale, false, null);
        return new BackendResponse(json);
      } catch (IOException e) {
        throw new BackendException("Failed to render deterministic intent", e);
      }
    }

    @Override
    public ChunkResponse summarizeChunk(ChunkRequest request) {
      String summary = summarizeChunkText(request.text());
      boolean included = !summary.isBlank();
      return new ChunkResponse(request.chunkId(), summary, included);
    }

    @Override
    public ReduceResponse reduceChunks(ReduceRequest request) {
      if (request.chunks().size() > 32) {
        return new ReduceResponse(truncate(request.originalContent().strip(), 512), "map_reduce_skipped");
      }
      List<String> includedSummaries =
          request.chunks().stream()
              .filter(ChunkResponse::included)
              .map(ChunkResponse::summaryText)
              .filter(text -> !text.isBlank())
              .toList();
      String summary = reduceChunkSummaries(includedSummaries);
      if (summary.isBlank()) {
        summary = truncate(request.originalContent().strip(), 160);
      }
      return new ReduceResponse(summary, "ok");
    }
  }

  static String summarizeChunkText(String chunkText) {
    String normalized = chunkText == null ? "" : chunkText.strip();
    if (normalized.length() <= 200) {
      return normalized;
    }
    return normalized.substring(0, Math.min(normalized.length(), 197)) + "...";
  }

  static String reduceChunkSummaries(List<String> chunks) {
    if (chunks.isEmpty()) {
      return "";
    }
    if (chunks.size() == 1) {
      return chunks.get(0);
    }
    StringBuilder builder = new StringBuilder();
    for (String chunk : chunks) {
      if (chunk == null || chunk.isBlank()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append("\n\n");
      }
      builder.append(chunk);
    }
    return builder.toString();
  }

  static String truncate(String text, int maxChars) {
    if (text == null) {
      return "";
    }
    if (text.length() <= maxChars) {
      return text;
    }
    return text.substring(0, Math.max(0, maxChars - 3)) + "...";
  }
}
