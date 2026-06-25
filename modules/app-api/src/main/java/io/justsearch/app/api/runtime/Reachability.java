/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.api.runtime;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.soabase.recordbuilder.core.RecordBuilder;
import java.util.List;

/**
 * Tempdoc 501 Phase 30 (§13.4.2 reachability axis): typed list of every
 * transport surface the producer exposes the runtime manifest (and
 * adjacent runtime-axis endpoints) over.
 *
 * <p>The §13.2 audit found that {@code RuntimeManifest.HeadInfo} bundled
 * identity ({@code apiPort}), reachability ({@code apiBaseUrl}),
 * credentials ({@code sessionToken}), and state ({@code readyAt}) in one
 * sub-record — four axes conflated. Phase 30 extracts the reachability
 * axis into a self-contained top-level record. {@code HeadInfo.apiBaseUrl}
 * stays for one schema cycle for back-compat with the Tauri shell,
 * dev-runner, MCP discovery, and Vite proxy consumers — those callers
 * migrate to {@code reachability} at their own pace.
 *
 * <p>Each transport carries (a) a {@code kind} string discriminating
 * its protocol, (b) a {@code url} or filesystem path, and (c) an
 * {@code audience} tag. The audience-axis (§13.4.5) projection uses the
 * tag to decide which transports a public view should advertise.
 */
@RecordBuilder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Reachability(List<Transport> transports) {

  public Reachability {
    transports = transports == null ? List.of() : List.copyOf(transports);
  }

  /** Audience tag values. */
  public static final String AUDIENCE_PUBLIC = "public";

  public static final String AUDIENCE_FULL = "full";

  /** Transport kind discriminator values. */
  public static final String KIND_HTTP_REST = "http-rest";

  public static final String KIND_SSE = "sse";
  public static final String KIND_FILESYSTEM = "filesystem";
  public static final String KIND_WELL_KNOWN = "well-known";
  public static final String KIND_MCP = "mcp";
  public static final String KIND_PROBE = "probe";

  /**
   * Public projection (tempdoc 501 §13.4.5): keep transports whose
   * {@code audience} is {@code public}. Filesystem transports carry an
   * internal path; a future internal-only transport would land with
   * audience={@code full} and be filtered here.
   */
  public Reachability publicProjection() {
    if (transports.isEmpty()) {
      return this;
    }
    List<Transport> filtered =
        transports.stream().filter(t -> AUDIENCE_PUBLIC.equals(t.audience())).toList();
    if (filtered.size() == transports.size()) {
      return this;
    }
    return new Reachability(filtered);
  }

  /** One transport surface. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Transport(String kind, String url, String audience) {
    @JsonCreator
    public Transport(
        @JsonProperty("kind") String kind,
        @JsonProperty("url") String url,
        @JsonProperty("audience") String audience) {
      this.kind = kind;
      this.url = url;
      this.audience = audience == null ? AUDIENCE_PUBLIC : audience;
    }
  }
}
