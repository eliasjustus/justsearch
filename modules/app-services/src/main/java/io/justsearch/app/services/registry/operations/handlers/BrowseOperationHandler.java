/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.BrowseTool;
import java.util.Objects;

/** OperationHandler wrapper for {@link BrowseTool}. Per tempdoc 429 §E.9 + Phase 12: thin delegating adapter. */
public final class BrowseOperationHandler implements OperationHandler {

  private final BrowseTool tool;

  public BrowseOperationHandler(BrowseTool tool) {
    this.tool = Objects.requireNonNull(tool, "tool");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    return tool.execute(argumentsJson);
  }
}
