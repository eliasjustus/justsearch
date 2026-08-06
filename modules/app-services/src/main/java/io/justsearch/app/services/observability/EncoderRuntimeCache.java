/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.observability;

import io.justsearch.app.api.inference.EncoderRuntimeView;
import io.justsearch.ort.EncoderRole;
import java.util.Map;

/**
 * Head-side access to the per-encoder runtime accelerator views derived by {@link
 * EncoderRuntimeExplainer} (tempdoc 805 G.3).
 *
 * <p>Mirrors {@code WorkerFeatureCache}'s shape: a functional read that never throws and returns an
 * empty map when the Worker has not answered yet, so a consumer polling {@code
 * /api/ai/runtime/status} degrades to "unknown" rather than to a positive claim.
 */
@FunctionalInterface
public interface EncoderRuntimeCache {

  /** Last-known per-role runtime views; empty when unknown. */
  Map<EncoderRole, EncoderRuntimeView> encoderRuntime();
}
