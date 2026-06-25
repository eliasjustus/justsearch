/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.services.mcphost;

import java.util.Objects;

/**
 * A tool advertised by an external MCP server (the result of {@code tools/list}).
 *
 * <p>{@code inputSchemaJson} is the raw JSON-Schema string the server published for the tool's
 * arguments; it is carried verbatim (not parsed) so it can be projected directly onto an
 * {@code Operation.intf} input schema when the tool becomes an EXECUTABLE declaration (tempdoc 560
 * §4.4). The host owns truth (§4.5): this is a faithful description of what the server offers, never
 * authority over it.
 */
public record McpTool(String name, String description, String inputSchemaJson) {
  public McpTool {
    Objects.requireNonNull(name, "name");
    if (name.isBlank()) {
      throw new IllegalArgumentException("MCP tool name must not be blank");
    }
    description = description == null ? "" : description;
    inputSchemaJson = (inputSchemaJson == null || inputSchemaJson.isBlank()) ? "{}" : inputSchemaJson;
  }
}
