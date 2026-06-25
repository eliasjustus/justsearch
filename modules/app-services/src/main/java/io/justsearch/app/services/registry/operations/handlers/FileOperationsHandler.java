/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.registry.operations.handlers;

import io.justsearch.agent.api.registry.OperationHandler;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.FileOperationsTool;
import java.util.Objects;

/**
 * OperationHandler wrapper for {@link FileOperationsTool}.
 *
 * <p>Per tempdoc 429 §E.3 + Phase 12: thin delegating adapter — the underlying
 * tool now returns {@link OperationResult} directly, threading {@code batchId}
 * through {@code OperationResult.executionId()} for undo correlation.
 */
public final class FileOperationsHandler implements OperationHandler {

  private final FileOperationsTool tool;

  public FileOperationsHandler(FileOperationsTool tool) {
    this.tool = Objects.requireNonNull(tool, "tool");
  }

  @Override
  public OperationResult execute(String argumentsJson) {
    return tool.execute(argumentsJson);
  }

  @Override
  public OperationResult undo(String executionId) {
    return tool.undo(executionId);
  }
}
