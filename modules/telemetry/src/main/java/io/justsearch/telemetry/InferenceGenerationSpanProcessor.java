/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Span processor that attaches the current inference-runtime generation as an attribute on
 * every span at start. Tempdoc 518 Appendix F W2.2.
 *
 * <p>Reads the generation lazily via {@link InferenceGenerationContext#current()} so it picks
 * up transition-driven changes between consecutive spans. Sentinel value {@code -1L}
 * (unregistered or no startup yet) skips the attribute.
 */
final class InferenceGenerationSpanProcessor implements SpanProcessor {
  private static final Logger LOG = LoggerFactory.getLogger(InferenceGenerationSpanProcessor.class);
  private static final AttributeKey<Long> INFERENCE_GENERATION =
      AttributeKey.longKey("justsearch.inference.generation");

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    long generation;
    try {
      generation = InferenceGenerationContext.current();
    } catch (RuntimeException ex) {
      // Defensive: a misbehaving supplier must not break span creation.
      LOG.debug("InferenceGenerationContext supplier threw; skipping attribute", ex);
      return;
    }
    if (generation < 0L) {
      return;
    }
    span.setAttribute(INFERENCE_GENERATION, generation);
  }

  @Override
  public boolean isStartRequired() {
    return true;
  }

  @Override
  public void onEnd(ReadableSpan span) {
    // No-op.
  }

  @Override
  public boolean isEndRequired() {
    return false;
  }
}
