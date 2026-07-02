/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.mcp;

/**
 * Single source of truth for the versions of JustSearch's MCP surface (tempdoc 654).
 *
 * <p>Two orthogonal versions live here so that every surface that reports them — the MCP
 * {@code initialize} response ({@code McpProtocolHandler}) and the runtime manifest's
 * {@link io.justsearch.app.api.runtime.RuntimeContract} — reads the same constants by
 * construction, rather than each carrying its own literal (a fork that would silently drift).
 *
 * <ul>
 *   <li>{@link #PROTOCOL_VERSION} — the Model Context Protocol spec version this server speaks,
 *       negotiated in {@code initialize}. A dated MCP version string; bumped only when the spec
 *       makes a backward-incompatible change (MCP's own rule). Not a JustSearch choice.
 *   <li>{@link #TOOL_SURFACE_VERSION} — JustSearch's OWN version for its curated tool surface,
 *       which the MCP protocol version says nothing about. MCP has no shipped tool-surface
 *       versioning yet (SEP-986 / SEP-1575 point at per-tool SemVer), so JustSearch versions it
 *       here, SemVer-shaped, pre-aligned to that direction. Reported as MCP {@code
 *       serverInfo.version} (the MCP-native slot) and as the runtime contract's
 *       {@code mcpToolSurfaceVersion} constituent. Starts pre-1.0 per the under-promise stance.
 * </ul>
 */
public final class McpContractVersions {

  /** MCP spec version negotiated in {@code initialize}. Dated per the MCP versioning rule. */
  public static final String PROTOCOL_VERSION = "2025-11-25";

  /**
   * JustSearch's own curated-tool-surface version (SemVer). Pre-1.0 by the under-promise stance
   * (tempdoc 654 §D3/D5): the surface may still change while we settle it.
   */
  public static final String TOOL_SURFACE_VERSION = "0.1.0";

  private McpContractVersions() {}
}
