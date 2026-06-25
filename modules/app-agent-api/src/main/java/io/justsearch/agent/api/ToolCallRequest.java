/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.agent.api;

import java.util.Objects;

/**
 * A parsed tool call from the LLM response.
 *
 * @param id tool call id assigned by the model
 * @param toolName name of the tool to invoke
 * @param arguments raw JSON string of the tool arguments
 */
public record ToolCallRequest(String id, String toolName, String arguments) {
  public ToolCallRequest {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(toolName, "toolName");
    Objects.requireNonNull(arguments, "arguments");
  }
}
