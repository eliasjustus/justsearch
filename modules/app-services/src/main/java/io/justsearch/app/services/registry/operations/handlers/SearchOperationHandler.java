/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.SearchTool;
import java.util.Objects;

/**
 * OperationHandler wrapper for {@link SearchTool}.
 *
 * <p>Per tempdoc 429 §E.9 + Phase 12: thin delegating adapter — {@code SearchTool}
 * now returns {@link OperationResult} directly, so the handler just forwards.
 */
public final class SearchOperationHandler implements OperationHandler {

  private final SearchTool tool;

  public SearchOperationHandler(SearchTool tool) {
    this.tool = Objects.requireNonNull(tool, "tool");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    return tool.execute(argumentsJson);
  }
}
