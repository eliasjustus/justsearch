/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.ReadDocumentTool;
import java.util.Objects;

/**
 * OperationHandler wrapper for {@link ReadDocumentTool} (tempdoc 868 §B.2).
 *
 * <p>Thin delegating adapter, exactly like {@link SearchOperationHandler}: the tool already returns
 * {@link OperationResult}, so the handler just forwards.
 */
public final class ReadDocumentHandler implements OperationHandler {

  private final ReadDocumentTool tool;

  public ReadDocumentHandler(ReadDocumentTool tool) {
    this.tool = Objects.requireNonNull(tool, "tool");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    return tool.execute(argumentsJson);
  }
}
