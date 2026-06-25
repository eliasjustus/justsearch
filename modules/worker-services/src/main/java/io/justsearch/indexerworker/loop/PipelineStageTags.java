/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.loop;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Tag schema for {@code pipeline.stage_ms}. Carries the originating pipeline name, the stage
 * identifier, and an optional reason code (nullable). The View attached to {@code pipeline.stage_ms}
 * uses {@code cardinalityLimit(256)} as a defensive guard since {@code stageId} and
 * {@code reasonCode} are bounded by the indexing pipeline's call-graph but not by the type system.
 */
public record PipelineStageTags(String pipelineName, String stageId, String reasonCode)
    implements TagSchema {

  static final String KEY_PIPELINE_NAME = "pipeline_name";
  static final String KEY_STAGE_ID = "stage_id";
  static final String KEY_REASON_CODE = "reason_code";
  static final Set<String> KEYS;

  static {
    Set<String> ks = new LinkedHashSet<>();
    ks.add(KEY_PIPELINE_NAME);
    ks.add(KEY_STAGE_ID);
    ks.add(KEY_REASON_CODE);
    KEYS = ks;
  }

  /** Constructs tags with the canonical {@code pipeline_name="indexing.worker"}. */
  public static PipelineStageTags of(String stageId, String reasonCode) {
    return new PipelineStageTags("indexing.worker", stageId, reasonCode);
  }

  @Override
  public Set<String> allowedKeys() {
    return KEYS;
  }

  @Override
  public Attributes toAttributes() {
    var b = Attributes.builder();
    if (pipelineName != null) {
      b.put(AttributeKey.stringKey(KEY_PIPELINE_NAME), pipelineName);
    }
    if (stageId != null) {
      b.put(AttributeKey.stringKey(KEY_STAGE_ID), stageId);
    }
    if (reasonCode != null && !reasonCode.isBlank()) {
      b.put(AttributeKey.stringKey(KEY_REASON_CODE), reasonCode);
    }
    return b.build();
  }
}
