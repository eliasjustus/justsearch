package io.justsearch.app.services.observability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.justsearch.app.api.ApiErrorCode;
import io.justsearch.app.api.ErrorClass;
import io.justsearch.app.services.observability.HeadApiTags.ApiErrorTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiRequestTags;
import io.justsearch.app.services.observability.HeadApiTags.ApiStreamTags;
import io.justsearch.telemetry.catalog.TestMetricRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tempdoc 417 F4: smoke test for {@link HeadApiMetricCatalog}. */
final class HeadApiMetricCatalogSmokeTest {

  private TestMetricRegistry registry;
  private HeadApiMetricCatalog catalog;

  @BeforeEach
  void setUp() {
    registry = new TestMetricRegistry(HeadApiMetricCatalog.DEFINITIONS);
    catalog = new HeadApiMetricCatalog(registry);
  }

  @AfterEach
  void tearDown() {
    if (registry != null) registry.close();
  }

  @Test
  void constructsAndEmits() {
    assertNotNull(catalog.requestMs);
    assertNotNull(catalog.streamTtftMs);
    assertNotNull(catalog.errorTotal);

    var requestTags =
        new ApiRequestTags("/api/test", HttpMethod.POST, "200", HttpStatusClass.S2XX);
    catalog.requestMs.record(42L, requestTags);
    assertEquals(1L, registry.histogramCount(HeadApiMetricCatalog.REQUEST_MS, requestTags));

    var streamTags = new ApiStreamTags("/api/agent", HttpMethod.POST, StreamTransport.SSE);
    catalog.streamTtftMs.record(99L, streamTags);
    assertEquals(1L, registry.histogramCount(HeadApiMetricCatalog.STREAM_TTFT_MS, streamTags));

    var errorTags = new ApiErrorTags(ApiErrorCode.NOT_FOUND, ErrorClass.PERMANENT, "/api/test");
    catalog.errorTotal.increment(errorTags);
    assertEquals(1L, registry.counterValue(HeadApiMetricCatalog.ERROR_TOTAL, errorTags));
  }

  @Test
  void noopCatalogDoesNotThrow() {
    var noop = HeadApiMetricCatalog.noop();
    assertDoesNotThrow(
        () ->
            noop.requestMs.record(
                1L, new ApiRequestTags("/x", HttpMethod.GET, "200", HttpStatusClass.S2XX)));
  }
}
