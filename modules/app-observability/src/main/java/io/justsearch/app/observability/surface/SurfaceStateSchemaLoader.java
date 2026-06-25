/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.app.observability.surface;

import io.justsearch.agent.api.registry.StateBinding;
import io.justsearch.agent.api.registry.SurfaceRef;
import io.justsearch.agent.api.registry.SurfaceStateSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads {@link SurfaceStateSchema} JSON-Schema source from classpath resources
 * for slice-489 URL-addressable surfaces.
 *
 * <p>Per slice 489 round-7 §F7 (2026-05-12): the schema source moved out of
 * inline {@code CoreSurfaceCatalog} Java string concatenation into resource
 * files at {@code SSOT/schemas/surface/<surfaceRef.value>.v1.json}.
 * Pattern mirrors the existing {@code registry-surface.*.properties} resource
 * convention for i18n keys: per-surface files, classpath-loaded at static-init
 * time. The bindings stay in Java because they reference typed
 * {@link StateBinding} records with abstract {@code storeId} values the FE
 * resolves at runtime — embedding them in the JSON would dilute the type
 * boundary.
 *
 * <p><strong>Boot-time validation.</strong> The loader parses the resource
 * file's content as JSON via Jackson (already on the {@code app-observability}
 * classpath). Malformed schema source throws {@link IllegalStateException} at
 * boot — fail-fast surfaces misconfigured catalog declarations on the very
 * first request rather than degrading silently per surface. This is the
 * authoritative gate; the {@code RegistryController} emit-time check (slice
 * 489 round-7 §F6) is defense-in-depth for hot-reload / classpath-corruption
 * cases that are effectively impossible in a packaged build.
 */
public final class SurfaceStateSchemaLoader {

  private static final ObjectMapper MAPPER = JsonMapper.builder().build();
  private static final String RESOURCE_ROOT = "/SSOT/schemas/surface/";
  private static final String RESOURCE_SUFFIX = ".v1.json";

  private SurfaceStateSchemaLoader() {
    /* no instances */
  }

  /**
   * Load the {@code SurfaceStateSchema} for a surface, combining the
   * classpath-loaded JSON Schema source with the caller-supplied bindings.
   *
   * <p>Returns {@link Optional#empty()} if the resource file does not exist
   * (surface is not URL-addressable — the chrome falls back to default state
   * on activation, per slice 489 §17.2's "optional" stateSchema resolution).
   *
   * @throws IllegalStateException if the resource file exists but is malformed
   *     JSON (boot-time fail-fast)
   * @throws IllegalArgumentException if the resource file exists but the
   *     {@link SurfaceStateSchema} record's own compact-constructor invariants
   *     reject it (e.g., empty schema source)
   */
  public static Optional<SurfaceStateSchema> load(
      SurfaceRef id, List<StateBinding> bindings) {
    String resource = RESOURCE_ROOT + id.value() + RESOURCE_SUFFIX;
    String source = readResource(resource);
    if (source == null) {
      return Optional.empty();
    }
    // Boot-time well-formedness check — parse the source via Jackson; a
    // malformed declaration throws IllegalStateException so the surface
    // catalog never reaches consumers with a corrupted schema.
    try {
      MAPPER.readTree(source);
    } catch (JacksonException e) {
      throw new IllegalStateException(
          "Malformed JSON in surface state schema resource '" + resource + "': "
              + e.getMessage(),
          e);
    }
    return Optional.of(new SurfaceStateSchema(source, bindings));
  }

  /** Load and require — throws if the resource file is absent. */
  public static SurfaceStateSchema require(
      SurfaceRef id, List<StateBinding> bindings) {
    return load(id, bindings)
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Required surface state schema resource not found for surfaceRef='"
                        + id.value() + "' (expected at classpath "
                        + RESOURCE_ROOT + id.value() + RESOURCE_SUFFIX + ")"));
  }

  /**
   * Read a classpath resource as a UTF-8 string. Returns null when the
   * resource does not exist (caller distinguishes "not addressable" from
   * "malformed declaration"). Throws {@link IllegalStateException} on
   * unexpected I/O failure during read.
   */
  private static String readResource(String path) {
    try (InputStream in = SurfaceStateSchemaLoader.class.getResourceAsStream(path)) {
      if (in == null) {
        return null;
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(
          "I/O error reading surface state schema resource '" + path + "': " + e.getMessage(),
          e);
    }
  }
}
