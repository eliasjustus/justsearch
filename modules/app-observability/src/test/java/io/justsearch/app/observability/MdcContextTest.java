package io.justsearch.app.observability;

import static org.junit.jupiter.api.Assertions.*;

import io.justsearch.ipc.logging.MdcContext;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class MdcContextTest {
  @Test
  void requestAndPipelineScopesPopulateAndRestore() {
    MDC.clear();
    assertNull(MDC.get("trace_id"));
    try (var req = MdcContext.request("trace-1", "req-1")) {
      assertNotNull(req);
      assertEquals("trace-1", MDC.get("trace_id"));
      assertEquals("req-1", MDC.get("request_id"));
      try (var pipe = MdcContext.pipeline("search_default")) {
        assertNotNull(pipe);
        assertEquals("search_default", MDC.get("pipeline_name"));
        // pipeline_hash + budget_profile retired by tempdoc 400 LR2-d (orphan per ADR 0014)
        assertNull(MDC.get("pipeline_hash"));
        assertNull(MDC.get("budget_profile"));
      }
      assertNull(MDC.get("pipeline_name"));
      assertEquals("trace-1", MDC.get("trace_id"));
    }
    assertNull(MDC.get("trace_id"));
  }

  @Test
  void nullOrEmptyInputsDoNotPopulate() {
    MDC.clear();
    assertNull(MDC.get("trace_id"));
    try (var req = MdcContext.request(null, "")) {
      assertNotNull(req);
      assertNull(MDC.get("trace_id"));
      assertNull(MDC.get("request_id"));
      try (var pipe = MdcContext.pipeline("")) {
        assertNotNull(pipe);
        assertNull(MDC.get("pipeline_name"));
      }
      assertNull(MDC.get("pipeline_name"));
      try (var st = MdcContext.stage("")) {
        assertNotNull(st);
        assertNull(MDC.get("stage_id"));
      }
    }
    assertTrue(MDC.getCopyOfContextMap() == null || MDC.getCopyOfContextMap().isEmpty());
  }
}
