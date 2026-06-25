/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.indexerworker.extract;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Tag schemas for Worker-owned {@code ocr.*} metrics. */
public final class OcrTags {
  private OcrTags() {}

  static final String KEY_ENGINE = "engine";
  static final String KEY_ERROR = "error";
  static final String KEY_REASON = "reason";

  static final Set<String> ENGINE_KEYS = Set.of(KEY_ENGINE);
  static final Set<String> ENGINE_AND_ERROR_KEYS;
  static final Set<String> REASON_KEYS = Set.of(KEY_REASON);

  static {
    Set<String> keys = new LinkedHashSet<>();
    keys.add(KEY_ENGINE);
    keys.add(KEY_ERROR);
    ENGINE_AND_ERROR_KEYS = Set.copyOf(keys);
  }

  public record OcrEngineTags(String engine) implements TagSchema {
    public OcrEngineTags {
      Objects.requireNonNull(engine, "engine");
    }

    public static OcrEngineTags of(String engine) {
      return new OcrEngineTags(engine);
    }

    @Override
    public Set<String> allowedKeys() {
      return ENGINE_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_ENGINE), engine);
    }
  }

  public record OcrFailureTags(String engine, String error) implements TagSchema {
    public OcrFailureTags {
      Objects.requireNonNull(engine, "engine");
      Objects.requireNonNull(error, "error");
    }

    public static OcrFailureTags of(String engine, String error) {
      return new OcrFailureTags(engine, error);
    }

    @Override
    public Set<String> allowedKeys() {
      return ENGINE_AND_ERROR_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.builder()
          .put(AttributeKey.stringKey(KEY_ENGINE), engine)
          .put(AttributeKey.stringKey(KEY_ERROR), error)
          .build();
    }
  }

  public record OcrSkipTags(OcrSkipReason reason) implements TagSchema {
    public OcrSkipTags {
      Objects.requireNonNull(reason, "reason");
    }

    public static OcrSkipTags of(OcrSkipReason reason) {
      return new OcrSkipTags(reason);
    }

    @Override
    public Set<String> allowedKeys() {
      return REASON_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_REASON), reason.wireValue());
    }
  }
}
