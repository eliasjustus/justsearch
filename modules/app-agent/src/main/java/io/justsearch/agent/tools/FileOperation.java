/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.tools;

import java.nio.file.Path;

/**
 * A single file system operation parsed from the LLM's tool call arguments.
 *
 * @param op the operation type
 * @param source source path (null for MKDIR)
 * @param destination destination path
 */
public record FileOperation(OpType op, Path source, Path destination) {

  /** Supported file operation types (no DELETE in v1). */
  public enum OpType {
    MOVE,
    RENAME,
    MKDIR,
    COPY
  }

  /** Whether this operation requires a source path. */
  public boolean requiresSource() {
    return op != OpType.MKDIR;
  }
}
