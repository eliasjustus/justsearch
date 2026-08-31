/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationResult;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * One JSON mapper and one "invalid args" classifier, shared by this package's {@link
 * io.justsearch.agent.api.registry.OperationHandler} implementations (tempdoc 877 §2.6).
 *
 * <p>Replaces: 19 handlers each declaring their own {@code ObjectMapper} field (in three
 * spellings — {@code JsonMapper.builder().build()}, {@code new ObjectMapper()}, {@code new
 * JsonMapper()} — verified configuration-equivalent for the {@code readTree}/{@code
 * treeToValue}/{@code readValue} uses in this package: all three build from a default {@code
 * JsonFactory} with no further customization applied at any call site) plus 9 handlers repeating
 * the identical {@code "Invalid args: " + e.getMessage()} failure text verbatim. One holder, one
 * fact each; zero behaviour change.
 */
final class HandlerJson {

  static final ObjectMapper MAPPER = JsonMapper.builder().build();

  private HandlerJson() {}

  static OperationResult invalidArgs(Exception e) {
    return OperationResult.failure("Invalid args: " + e.getMessage());
  }
}
