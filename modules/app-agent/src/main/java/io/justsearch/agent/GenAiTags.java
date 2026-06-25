/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent;

import io.justsearch.telemetry.catalog.TagSchema;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import java.util.Objects;
import java.util.Set;

/**
 * Tag schemas for {@code gen_ai.*} metrics (OTel semantic-convention names). The wire keys use
 * dots in the OTel-canonical form ({@code gen_ai.operation.name}, {@code gen_ai.token.type}) and
 * are byte-stable to the pre-refactor strings.
 */
public final class GenAiTags {

  private GenAiTags() {}

  static final String KEY_OPERATION_NAME = "gen_ai.operation.name";
  static final String KEY_TOKEN_TYPE = "gen_ai.token.type";

  static final Set<String> OPERATION_KEYS = Set.of(KEY_OPERATION_NAME);
  static final Set<String> TOKEN_KEYS = Set.of(KEY_TOKEN_TYPE);

  /** Tag schema for {@code gen_ai.client.operation.duration}. */
  public record GenAiOperationTags(String operationName) implements TagSchema {

    public GenAiOperationTags {
      Objects.requireNonNull(operationName, "operationName");
    }

    @Override
    public Set<String> allowedKeys() {
      return OPERATION_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_OPERATION_NAME), operationName);
    }
  }

  /** Tag schema for {@code gen_ai.client.token.usage}. */
  public record GenAiTokenUsageTags(String tokenType) implements TagSchema {

    public GenAiTokenUsageTags {
      Objects.requireNonNull(tokenType, "tokenType");
    }

    @Override
    public Set<String> allowedKeys() {
      return TOKEN_KEYS;
    }

    @Override
    public Attributes toAttributes() {
      return Attributes.of(AttributeKey.stringKey(KEY_TOKEN_TYPE), tokenType);
    }
  }
}
