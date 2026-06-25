package io.justsearch.ui.api;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KnowledgeSearchController gRPC error mapping")
final class KnowledgeSearchControllerGrpcErrorTest {

  @Test
  @DisplayName("INVALID_ARGUMENT mentioning cursor is mapped as CURSOR_INVALID")
  void invalidCursorIsDetected() {
    StatusRuntimeException e =
        Status.INVALID_ARGUMENT.withDescription("invalid cursor token").asRuntimeException();
    assertTrue(KnowledgeSearchController.isInvalidCursor(e));
    assertEquals(400, ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode()));
  }

  @Test
  @DisplayName("INVALID_ARGUMENT not mentioning cursor is not treated as CURSOR_INVALID")
  void invalidArgumentNonCursorIsNotDetected() {
    StatusRuntimeException e =
        Status.INVALID_ARGUMENT.withDescription("invalid request").asRuntimeException();
    assertFalse(KnowledgeSearchController.isInvalidCursor(e));
    assertEquals(400, ApiErrorHandler.mapGrpcToHttp(e.getStatus().getCode()));
  }
}
