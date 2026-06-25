package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.IngestTool;
import org.junit.jupiter.api.Test;

/** Tests for {@link IngestOperationHandler} per tempdoc 429 §F.11 closure. */
final class IngestOperationHandlerTest {

  @Test
  void executeForwardsSuccess() {
    IngestTool tool = mock(IngestTool.class);
    when(tool.execute("{\"paths\":[\"a\"]}"))
        .thenReturn(OperationResult.success("ingested 1 file"));
    IngestOperationHandler handler = new IngestOperationHandler(tool);

    OperationResult result = handler.execute("{\"paths\":[\"a\"]}");

    assertTrue(result.success());
    assertEquals("ingested 1 file", result.message());
  }

  @Test
  void executeForwardsFailure() {
    IngestTool tool = mock(IngestTool.class);
    when(tool.execute("{}"))
        .thenReturn(OperationResult.failure("paths required"));
    IngestOperationHandler handler = new IngestOperationHandler(tool);

    OperationResult result = handler.execute("{}");

    assertFalse(result.success());
    assertEquals("paths required", result.message());
  }
}
