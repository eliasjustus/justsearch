package io.justsearch.app.services.registry.operations.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.api.registry.OperationResult;
import io.justsearch.agent.tools.BrowseTool;
import org.junit.jupiter.api.Test;

/** Tests for {@link BrowseOperationHandler} per tempdoc 429 §F.11 closure. */
final class BrowseOperationHandlerTest {

  @Test
  void executeForwardsSuccess() {
    BrowseTool tool = mock(BrowseTool.class);
    when(tool.execute("{}")).thenReturn(OperationResult.success("folders listed"));
    BrowseOperationHandler handler = new BrowseOperationHandler(tool);

    OperationResult result = handler.execute("{}");

    assertTrue(result.success());
    assertEquals("folders listed", result.message());
  }

  @Test
  void executeForwardsFailure() {
    BrowseTool tool = mock(BrowseTool.class);
    when(tool.execute("{}")).thenReturn(OperationResult.failure("invalid path"));
    BrowseOperationHandler handler = new BrowseOperationHandler(tool);

    OperationResult result = handler.execute("{}");

    assertFalse(result.success());
    assertEquals("invalid path", result.message());
  }
}
