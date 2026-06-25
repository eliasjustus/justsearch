package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.SearchTool;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link SearchOperationHandler} per tempdoc 429 §F.11 closure.
 *
 * <p>Verifies the handler delegates to the wrapped {@link SearchTool} and translates
 * {@link OperationResult} returned by {@link OperationResult} correctly (success/failure preservation,
 * executionId threading).
 */
final class SearchOperationHandlerTest {

  @Test
  void executeForwardsSuccessFromWrappedTool() {
    SearchTool tool = mock(SearchTool.class);
    when(tool.execute("{\"query\":\"x\"}")).thenReturn(OperationResult.success("results"));
    SearchOperationHandler handler = new SearchOperationHandler(tool);

    OperationResult result = handler.execute("{\"query\":\"x\"}");

    assertTrue(result.success());
    assertEquals("results", result.message());
    assertTrue(result.executionId().isEmpty());
  }

  @Test
  void executeForwardsFailureFromWrappedTool() {
    SearchTool tool = mock(SearchTool.class);
    when(tool.execute("{}")).thenReturn(OperationResult.failure("invalid query"));
    SearchOperationHandler handler = new SearchOperationHandler(tool);

    OperationResult result = handler.execute("{}");

    assertFalse(result.success());
    assertEquals("invalid query", result.message());
  }

  @Test
  void executePreservesExecutionIdWhenPresent() {
    SearchTool tool = mock(SearchTool.class);
    when(tool.execute("{}")).thenReturn(OperationResult.success("ok", "exec-123"));
    SearchOperationHandler handler = new SearchOperationHandler(tool);

    OperationResult result = handler.execute("{}");

    assertTrue(result.success());
    assertTrue(result.executionId().isPresent());
    assertEquals("exec-123", result.executionId().get());
  }
}
