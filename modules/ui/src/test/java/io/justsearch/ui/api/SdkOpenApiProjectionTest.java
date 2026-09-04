/* SPDX-License-Identifier: Apache-2.0 */
package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdkOpenApiProjectionTest {
  private static final Path SNAPSHOT =
      Path.of("..", "..", "packages", "runtime-client", "openapi", "runtime-client.openapi.json");

  @Test
  void committedSnapshotMatchesProductionRouteRegistrarsByteForByte() throws Exception {
    assertArrayEquals(Files.readAllBytes(SNAPSHOT), SdkOpenApiFixture.document());
  }

  @Test
  void projectionContainsExactlySixSelfContainedOperations() {
    Map<String, Object> document = SdkOpenApiProjection.build(SdkOpenApiFixture.app(), List.of());
    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> paths =
        (Map<String, Map<String, Object>>) document.get("paths");
    assertEquals(6, paths.size());
    assertFalse(document.toString().contains("/api/schemas/"), "runtime schema refs must be bundled");
    assertTrue(paths.containsKey("/.well-known/justsearch/manifest.json"));
    assertFalse(paths.containsKey("/api/runtime/manifest/stream"));
    assertFalse(paths.containsKey("/api/mcp/token"));
    assertFalse(paths.containsKey("/mcp"));
  }

  @Test
  void duplicateRouteAndOperationIdsFailClosed() {
    RouteContractPolicy.Contract first = RouteContractPolicy.sdkContracts().getFirst();
    assertThrows(
        IllegalArgumentException.class,
        () -> RouteContractPolicy.index(List.of(first, first)));

    RouteContractPolicy.Contract duplicateOperation =
        new RouteContractPolicy.Contract(
            "GET",
            "/different",
            RouteContractPolicy.Stability.PUBLIC_CONTRACT,
            first.sdkOperationId(),
            null,
            List.of(),
            Map.of(200, "runtime-live-response.v1.json"),
            ApiSecurityFilters.contractSecurity("GET", "/different"),
            null,
            null);
    assertThrows(
        IllegalArgumentException.class,
        () -> RouteContractPolicy.index(List.of(first, duplicateOperation)));
  }

  @Test
  void orphanedSdkRowsFailClosed() {
    List<String> registered = new ArrayList<>();
    registered.add("GET /api/runtime/manifest");
    assertThrows(
        IllegalStateException.class,
        () -> RouteContractPolicy.validateSdkRoutes(registered));
  }

  @Test
  void duplicateLiveSdkRouteFailsClosed() {
    List<String> registered =
        RouteContractPolicy.sdkContracts().stream().map(RouteContractPolicy.Contract::key).toList();
    List<String> duplicated = new ArrayList<>(registered);
    duplicated.add(registered.getFirst());
    assertThrows(
        IllegalStateException.class,
        () -> RouteContractPolicy.validateSdkRoutes(duplicated));
  }
}
